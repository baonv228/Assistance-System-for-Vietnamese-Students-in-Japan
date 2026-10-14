package com.sep490.vsa.feature.auth;

import com.sep490.vsa.common.exception.ApiException;
import com.sep490.vsa.common.mail.EmailSender;
import com.sep490.vsa.common.security.TokenHasher;
import com.sep490.vsa.feature.auth.AuthDtos.CompleteRegistrationRequest;
import com.sep490.vsa.feature.auth.AuthDtos.SendCodeResponse;
import com.sep490.vsa.feature.auth.AuthDtos.VerifyCodeResponse;
import com.sep490.vsa.feature.legal.LegalService;
import com.sep490.vsa.feature.user.User;
import com.sep490.vsa.feature.user.UserRepository;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

import static com.sep490.vsa.feature.auth.AuthRules.normalizeEmail;
import static com.sep490.vsa.feature.auth.EmailVerificationCode.PURPOSE_REGISTER;

/**
 * Email sign-up in three steps: send a 6-digit code → verify it (get a ticket) → set name and password.
 */
@Service
public class RegistrationService {

    private final EmailVerificationCodeRepository codes;
    private final UserRepository users;
    private final LegalService legalService;
    private final PasswordEncoder passwordEncoder;
    private final EmailSender emailSender;
    private final Clock clock;

    public RegistrationService(EmailVerificationCodeRepository codes, UserRepository users, LegalService legalService,
                               PasswordEncoder passwordEncoder, EmailSender emailSender, Clock clock) {
        this.codes = codes;
        this.users = users;
        this.legalService = legalService;
        this.passwordEncoder = passwordEncoder;
        this.emailSender = emailSender;
        this.clock = clock;
    }

    @Transactional
    public SendCodeResponse sendCode(String rawEmail) {
        String email = normalizeEmail(rawEmail);
        Instant now = clock.instant();
        if (users.existsByEmailIgnoreCase(email)) {
            throw ApiException.conflict("Email này đã được đăng ký. Hãy đăng nhập hoặc dùng email khác.");
        }
        codes.findFirstByEmailAndPurposeOrderByCreatedAtDesc(email, PURPOSE_REGISTER).ifPresent(last -> {
            Duration since = Duration.between(last.getCreatedAt(), now);
            if (since.compareTo(AuthRules.RESEND_AFTER) < 0) {
                long wait = AuthRules.RESEND_AFTER.minus(since).toSeconds() + 1;
                throw ApiException.tooManyRequests("Vui lòng đợi " + wait + " giây rồi gửi lại mã.");
            }
        });
        if (codes.countByEmailAndPurposeAndCreatedAtAfter(email, PURPOSE_REGISTER, now.minus(Duration.ofDays(1)))
                >= AuthRules.CODES_PER_DAY) {
            throw ApiException.tooManyRequests("Email này đã nhận quá nhiều mã hôm nay. Vui lòng thử lại vào ngày mai.");
        }

        codes.invalidateOpenCodes(email, PURPOSE_REGISTER, now);
        String code = TokenHasher.randomDigits(AuthRules.CODE_DIGITS);
        codes.save(new EmailVerificationCode(email, PURPOSE_REGISTER, hashCode(email, code), now, AuthRules.CODE_VALID_FOR));

        emailSender.send(email, "[Senpai ơi] Mã xác thực đăng ký: " + code, """
                Chào bạn,

                Mã xác thực đăng ký tài khoản Senpai ơi của bạn là: %s

                Mã có hiệu lực trong %d phút. Nếu bạn không yêu cầu đăng ký, hãy bỏ qua email này.

                Senpai ơi – Hệ thống hỗ trợ sinh viên Việt Nam tại Nhật Bản
                """.formatted(code, AuthRules.CODE_VALID_FOR.toMinutes()));

        return new SendCodeResponse(email, AuthRules.CODE_VALID_FOR.toMinutes(), AuthRules.RESEND_AFTER.toSeconds());
    }

    /** Wrong attempts must be saved even though an error is returned, so no rollback for ApiException. */
    @Transactional(noRollbackFor = ApiException.class)
    public VerifyCodeResponse verifyCode(String rawEmail, String code) {
        String email = normalizeEmail(rawEmail);
        Instant now = clock.instant();
        EmailVerificationCode entry = codes.findFirstByEmailAndPurposeOrderByCreatedAtDesc(email, PURPOSE_REGISTER)
                .filter(c -> !c.isUsed() && !c.isVerified())
                .orElseThrow(() -> ApiException.badRequest("Không tìm thấy mã xác thực. Hãy bấm Gửi lại mã."));
        if (entry.isExpired(now)) {
            throw ApiException.badRequest("Mã xác thực đã hết hạn. Hãy bấm Gửi lại mã.");
        }
        if (entry.getAttempts() >= AuthRules.CODE_MAX_ATTEMPTS) {
            throw ApiException.tooManyRequests("Bạn đã nhập sai quá nhiều lần. Hãy bấm Gửi lại mã.");
        }
        if (!TokenHasher.matches(entry.getCodeHash(), hashCode(email, code))) {
            entry.recordWrongAttempt();
            int left = AuthRules.CODE_MAX_ATTEMPTS - entry.getAttempts();
            throw ApiException.badRequest(left > 0
                    ? "Mã xác thực không đúng. Bạn còn " + left + " lần thử."
                    : "Mã xác thực không đúng. Hãy bấm Gửi lại mã.");
        }
        String ticket = TokenHasher.randomToken();
        entry.markVerified(now, TokenHasher.sha256(ticket), AuthRules.TICKET_VALID_FOR);
        return new VerifyCodeResponse(ticket, AuthRules.TICKET_VALID_FOR.toMinutes());
    }

    @Transactional
    public User complete(CompleteRegistrationRequest request) {
        Instant now = clock.instant();
        EmailVerificationCode entry = codes.findByTicketHash(TokenHasher.sha256(request.ticket()))
                .filter(c -> c.isTicketValid(now))
                .orElseThrow(() -> ApiException.badRequest("Phiên đăng ký đã hết hạn. Vui lòng nhập lại email để nhận mã mới."));
        String email = entry.getEmail();
        if (users.existsByEmailIgnoreCase(email)) {
            throw ApiException.conflict("Email này đã được đăng ký. Hãy đăng nhập.");
        }
        User user = users.save(User.register(email, request.displayName().trim(),
                passwordEncoder.encode(request.password()), null, entry.getVerifiedAt()));
        legalService.recordSignUpConsents(user);
        entry.markUsed(now);
        return user;
    }

    /** The email is part of the hash so the same code for two people gives different hashes. */
    private static String hashCode(String email, String code) {
        return TokenHasher.sha256(email + ":" + code);
    }
}
