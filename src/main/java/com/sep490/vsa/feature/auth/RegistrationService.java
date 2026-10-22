package com.sep490.vsa.feature.auth;

import com.sep490.vsa.common.exception.ApiException;
import com.sep490.vsa.common.mail.EmailSender;
import com.sep490.vsa.common.security.TokenHasher;
import com.sep490.vsa.common.settings.SystemSettings;
import com.sep490.vsa.feature.auth.AuthDtos.CompleteRegistrationRequest;
import com.sep490.vsa.feature.auth.AuthDtos.SendCodeResponse;
import com.sep490.vsa.feature.auth.AuthDtos.VerifyCodeResponse;
import com.sep490.vsa.feature.auth.EmailVerificationCodeRepository.SentCode;
import com.sep490.vsa.feature.legal.LegalService;
import com.sep490.vsa.feature.moderation.ContentFilter;
import com.sep490.vsa.feature.user.User;
import com.sep490.vsa.feature.user.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayDeque;
import java.util.Comparator;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static com.sep490.vsa.feature.auth.AuthRules.normalizeEmail;
import static com.sep490.vsa.feature.auth.AuthRules.within;
import static com.sep490.vsa.feature.auth.EmailVerificationCode.PURPOSE_REGISTER;

/**
 * Email sign-up in three steps: send a 6-digit code → verify it (get a ticket) → set name and password.
 * <p>
 * Limits of step 1 (S4): one code per mailbox every email_code_resend_seconds and {@value AuthRules#CODES_PER_DAY} in
 * 24 hours per mailbox ({@link AuthRules#mailbox}: a "+tag" or Gmail dots do not make a new mailbox),
 * {@value AuthRules#CODES_PER_ADDRESS_HOUR} an hour per client address, {@value AuthRules#CODES_PER_DAY_SITE} in 24
 * hours for the whole site. The code lifetime, the wrong attempts, the resend wait, the ticket lifetime and the
 * password minimum are Admin settings (system_settings), read on every request.
 */
@Service
public class RegistrationService {

    private static final Logger log = LoggerFactory.getLogger(RegistrationService.class);
    private static final String ALREADY_REGISTERED = "Email này đã được đăng ký. Hãy đăng nhập.";
    private static final long HOUR_MS = 60 * 60_000L;
    /** Client addresses remembered for the hourly limit; past this, finished ones are dropped (then all of them). */
    private static final int ADDRESS_MEMORY_MAX = 5000;

    private final EmailVerificationCodeRepository codes;
    private final UserRepository users;
    private final LegalService legalService;
    private final PasswordEncoder passwordEncoder;
    private final EmailSender emailSender;
    private final SystemSettings settings;
    private final ContentFilter filter;
    private final TransactionTemplate tx;
    private final Clock clock;
    /** Codes sent for each client address in the last hour. Kept in memory: a restart simply resets it. */
    private final Map<String, Deque<Long>> codesByAddress = new ConcurrentHashMap<>();
    /** Codes are checked and saved one at a time, so requests sent together cannot all pass the limits. */
    private final Object sendLock = new Object();

    public RegistrationService(EmailVerificationCodeRepository codes, UserRepository users, LegalService legalService,
                               PasswordEncoder passwordEncoder, EmailSender emailSender, SystemSettings settings,
                               ContentFilter filter, PlatformTransactionManager transactionManager, Clock clock) {
        this.codes = codes;
        this.users = users;
        this.legalService = legalService;
        this.passwordEncoder = passwordEncoder;
        this.emailSender = emailSender;
        this.settings = settings;
        this.filter = filter;
        this.tx = new TransactionTemplate(transactionManager);
        this.clock = clock;
    }

    /**
     * Step 1. The checks and the new code run in a short transaction; the email is sent once it has committed (S12),
     * so a slow mail server never holds a database connection. When sending fails, the new code is deleted again (it
     * reached nobody: it counts toward no limit, and a code sent before stays valid) and the user gets the error as
     * before. The code sent last is the only one that can be verified; the older open ones are closed after sending.
     */
    public SendCodeResponse sendCode(String rawEmail, String clientAddress) {
        String email = normalizeEmail(rawEmail);
        String address = clientAddress == null ? "" : clientAddress;
        Duration validFor = codeValidFor();
        Duration resendAfter = Duration.ofSeconds(within(settings.getInt(SystemSettings.EMAIL_CODE_RESEND_SECONDS,
                (int) AuthRules.RESEND_AFTER.toSeconds()), 0, 86_400));
        String code = TokenHasher.randomDigits(AuthRules.CODE_DIGITS);

        long slot = takeAddressSlot(address);
        long codeId;
        try {
            synchronized (sendLock) {
                codeId = tx.execute(status -> saveCode(email, code, validFor, resendAfter));
            }
        } catch (RuntimeException e) {
            releaseAddressSlot(address, slot);
            throw e;
        }

        try {
            emailSender.send(email, "[Senpai ơi] Mã xác thực đăng ký: " + code, """
                    Chào bạn,

                    Mã xác thực đăng ký tài khoản Senpai ơi của bạn là: %s

                    Mã có hiệu lực trong %d phút. Nếu bạn không yêu cầu đăng ký, hãy bỏ qua email này.

                    Senpai ơi – Hệ thống hỗ trợ sinh viên Việt Nam tại Nhật Bản
                    """.formatted(code, validFor.toMinutes()));
        } catch (RuntimeException e) {
            releaseAddressSlot(address, slot);
            try {
                tx.executeWithoutResult(status -> codes.deleteById(codeId));
            } catch (RuntimeException cleanup) {
                e.addSuppressed(cleanup);
            }
            throw e;
        }

        try {
            tx.executeWithoutResult(status ->
                    codes.invalidateOlderOpenCodes(email, PURPOSE_REGISTER, codeId, clock.instant()));
        } catch (RuntimeException e) {
            // The email is out; older codes cannot be verified anyway (only the newest one is checked)
            log.warn("Could not close the older sign-up codes after sending a new one: {}", e.toString());
        }
        return new SendCodeResponse(email, validFor.toMinutes(), resendAfter.toSeconds());
    }

    /** Step 2. The code row is locked until the end (S8): guesses sent together are counted one after another. */
    @Transactional(noRollbackFor = ApiException.class)
    public VerifyCodeResponse verifyCode(String rawEmail, String code) {
        String email = normalizeEmail(rawEmail);
        // attempts is a TINYINT
        int maxAttempts = within(settings.getInt(SystemSettings.EMAIL_CODE_ATTEMPTS, AuthRules.CODE_MAX_ATTEMPTS),
                1, 100);
        Duration ticketValidFor = Duration.ofMinutes(within(settings.getInt(SystemSettings.REGISTER_TICKET_MINUTES,
                (int) AuthRules.TICKET_VALID_FOR.toMinutes()), 1, 1440));
        int passwordMinLength = passwordMinLength();
        EmailVerificationCode entry = codes.findFirstByEmailAndPurposeOrderByCreatedAtDescIdDesc(email, PURPOSE_REGISTER)
                .filter(c -> !c.isUsed() && !c.isVerified())
                .orElseThrow(() -> ApiException.badRequest("Không tìm thấy mã xác thực. Hãy bấm Gửi lại mã."));
        Instant now = clock.instant();
        if (entry.isExpired(now)) {
            throw ApiException.badRequest("Mã xác thực đã hết hạn. Hãy bấm Gửi lại mã.");
        }
        if (entry.getAttempts() >= maxAttempts) {
            throw ApiException.tooManyRequests("Bạn đã nhập sai quá nhiều lần. Hãy bấm Gửi lại mã.");
        }
        if (!TokenHasher.matches(entry.getCodeHash(), hashCode(email, code))) {
            entry.recordWrongAttempt();
            int left = maxAttempts - entry.getAttempts();
            throw ApiException.badRequest(left > 0
                    ? "Mã xác thực không đúng. Bạn còn " + left + " lần thử."
                    : "Mã xác thực không đúng. Hãy bấm Gửi lại mã.");
        }
        String ticket = TokenHasher.randomToken();
        entry.markVerified(now, TokenHasher.sha256(ticket), ticketValidFor);
        return new VerifyCodeResponse(ticket, ticketValidFor.toMinutes(), passwordMinLength);
    }

    /**
     * Step 3. The display name arrives already stripped (AuthDtos) and may not hold a banned keyword. The same ticket
     * sent twice at once (a double click), or the same email finished in two tabs: the first one creates the account,
     * the other answers 409 like an email that is already registered, whether it sees the ticket used or meets the new
     * row at the email's unique key.
     */
    @Transactional
    public User complete(CompleteRegistrationRequest request) {
        filter.checkDisplayName(request.displayName());
        checkPassword(request.password());
        Instant now = clock.instant();
        EmailVerificationCode entry = codes.findByTicketHash(TokenHasher.sha256(request.ticket())).orElse(null);
        if (entry == null || !entry.isTicketValid(now)) {
            if (entry != null && entry.isUsed() && users.existsByEmailIgnoreCase(entry.getEmail())) {
                throw ApiException.conflict(ALREADY_REGISTERED);
            }
            throw ApiException.badRequest("Phiên đăng ký đã hết hạn. Vui lòng nhập lại email để nhận mã mới.");
        }
        String email = entry.getEmail();
        if (users.existsByEmailIgnoreCase(email)) {
            throw ApiException.conflict(ALREADY_REGISTERED);
        }
        User user;
        try {
            user = users.saveAndFlush(User.register(email, request.displayName().trim(),
                    passwordEncoder.encode(request.password()), null, entry.getVerifiedAt()));
        } catch (DataIntegrityViolationException e) {
            // Another request with this email committed its account meanwhile (users.email is unique)
            throw ApiException.conflict(ALREADY_REGISTERED);
        }
        legalService.recordSignUpConsents(user);
        entry.markUsed(now);
        return user;
    }

    /** Runs inside the send transaction, under {@link #sendLock}; returns the id of the new code. */
    private long saveCode(String email, String code, Duration validFor, Duration resendAfter) {
        Instant now = clock.instant();
        if (users.existsByEmailIgnoreCase(email)) {
            throw ApiException.conflict("Email này đã được đăng ký. Hãy đăng nhập hoặc dùng email khác.");
        }
        List<SentCode> lastDay = codes.findByCreatedAtAfter(now.minus(Duration.ofDays(1)));
        String mailbox = AuthRules.mailbox(email);
        List<Instant> toMailbox = lastDay.stream()
                .filter(c -> PURPOSE_REGISTER.equals(c.getPurpose()) && mailbox.equals(AuthRules.mailbox(c.getEmail())))
                .map(SentCode::getCreatedAt)
                .toList();
        Instant last = toMailbox.stream().max(Comparator.naturalOrder()).orElse(null);
        if (last != null) {
            Duration since = Duration.between(last, now);
            if (since.compareTo(resendAfter) < 0) {
                long wait = resendAfter.minus(since).toSeconds() + 1;
                throw ApiException.tooManyRequests("Vui lòng đợi " + wait + " giây rồi gửi lại mã.");
            }
        }
        if (toMailbox.size() >= AuthRules.CODES_PER_DAY) {
            throw ApiException.tooManyRequests("Email này đã nhận quá nhiều mã hôm nay. Vui lòng thử lại vào ngày mai.");
        }
        // Every code counts here (any purpose): they all go out through the same mail account
        if (lastDay.size() >= AuthRules.CODES_PER_DAY_SITE) {
            throw ApiException.tooManyRequests(
                    "Hôm nay Senpai ơi đã gửi quá nhiều mã xác thực. Vui lòng thử lại sau vài giờ.");
        }
        // created_at is a DATETIME of whole seconds and MySQL rounds: a code rounded up would look newer than it is
        Instant createdAt = now.truncatedTo(ChronoUnit.SECONDS);
        return codes.save(new EmailVerificationCode(email, PURPOSE_REGISTER, hashCode(email, code), createdAt, validFor))
                .getId();
    }

    /** Takes one of the client address's codes of the hour (429 when none is left); returns it, to give back. */
    private long takeAddressSlot(String address) {
        long now = clock.millis();
        if (codesByAddress.size() > ADDRESS_MEMORY_MAX) {
            codesByAddress.values().removeIf(times -> {
                synchronized (times) {
                    return times.isEmpty() || now - times.peekLast() >= HOUR_MS;
                }
            });
            if (codesByAddress.size() > ADDRESS_MEMORY_MAX) {
                codesByAddress.clear();
            }
        }
        Deque<Long> times = codesByAddress.computeIfAbsent(address, k -> new ArrayDeque<>());
        synchronized (times) {
            while (!times.isEmpty() && now - times.peekFirst() >= HOUR_MS) {
                times.pollFirst();
            }
            if (times.size() >= AuthRules.CODES_PER_ADDRESS_HOUR) {
                long minutes = Math.max(1, (HOUR_MS - (now - times.peekFirst()) + 59_999) / 60_000);
                throw ApiException.tooManyRequests(
                        "Bạn đã yêu cầu quá nhiều mã xác thực. Vui lòng thử lại sau " + minutes + " phút.");
            }
            times.addLast(now);
            return now;
        }
    }

    /** The request sent no code after all (refused by a check, or the email failed): it does not count. */
    private void releaseAddressSlot(String address, long slot) {
        Deque<Long> times = codesByAddress.get(address);
        if (times != null) {
            synchronized (times) {
                times.removeLastOccurrence(slot);
            }
        }
    }

    private Duration codeValidFor() {
        return Duration.ofMinutes(within(settings.getInt(SystemSettings.EMAIL_CODE_MINUTES,
                (int) AuthRules.CODE_VALID_FOR.toMinutes()), 1, 1440));
    }

    private int passwordMinLength() {
        return within(settings.getInt(SystemSettings.PASSWORD_MIN_LENGTH, AuthRules.PASSWORD_MIN_LENGTH),
                1, AuthRules.PASSWORD_MAX_LENGTH);
    }

    /**
     * password_min_length (Admin setting, so not an annotation) and BCrypt's 72 bytes (S9); the other password rules
     * are on the request (AuthDtos).
     */
    private void checkPassword(String password) {
        int min = passwordMinLength();
        if (password.length() < min) {
            String message = "Mật khẩu dài từ " + min + " đến " + AuthRules.PASSWORD_MAX_LENGTH + " ký tự";
            throw ApiException.invalid(message, Map.of("password", message));
        }
        if (password.getBytes(StandardCharsets.UTF_8).length > AuthRules.PASSWORD_MAX_BYTES) {
            String message = "Mật khẩu quá dài (chữ có dấu được tính 2–3 ký tự). Hãy dùng mật khẩu ngắn hơn";
            throw ApiException.invalid(message, Map.of("password", message));
        }
    }

    /** The email is part of the hash so the same code for two people gives different hashes. */
    private static String hashCode(String email, String code) {
        return TokenHasher.sha256(email + ":" + code);
    }
}
