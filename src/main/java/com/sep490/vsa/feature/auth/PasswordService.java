package com.sep490.vsa.feature.auth;

import com.sep490.vsa.common.exception.ApiException;
import com.sep490.vsa.common.security.TokenHasher;
import com.sep490.vsa.common.security.UserSessions;
import com.sep490.vsa.common.settings.SystemSettings;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import static com.sep490.vsa.feature.auth.AuthRules.normalizeEmail;

/**
 * UC-U05 "Quên mật khẩu" and UC-U06 "Đổi mật khẩu".
 * <ul>
 *   <li>Reset: one-time links in {@code user_tokens} (purpose RESET_PASSWORD, only the SHA-256 of the token is
 *       stored), valid {@code reset_token_minutes} (30). The answer never says whether an email has an account, and
 *       the email is sent in the background ({@link PasswordMail}) so the answer time does not tell either. Per
 *       account one link a minute and 5 a day; a new link cancels the older ones. Setting the new password unlocks a
 *       login lock, confirms the email and signs every device out.</li>
 *   <li>Change: the current password is checked like a login ({@link AuthService#checkCurrentPassword}); an account
 *       made with Google/Facebook has none and sets its first one. The other devices are signed out, this one keeps
 *       its session under a new id, and an email tells the owner.</li>
 * </ul>
 * BCrypt runs outside the database transactions, as at login.
 */
@Service
public class PasswordService {

    static final String PURPOSE = "RESET_PASSWORD";
    static final String SENT = "Nếu email này có tài khoản, chúng tôi đã gửi liên kết đặt lại mật khẩu. "
            + "Liên kết có hiệu lực %d phút.";
    static final String LINK_INVALID = "Liên kết đã hết hạn hoặc đã được sử dụng. Hãy yêu cầu liên kết mới.";
    static final Duration RESEND_AFTER = Duration.ofSeconds(60);
    static final int LINKS_PER_DAY = 5;
    static final int DEFAULT_MINUTES = 30;

    private final JdbcTemplate jdbc;
    private final AuthService auth;
    private final PasswordEncoder passwordEncoder;
    private final PasswordMail mail;
    private final UserSessions sessions;
    private final SystemSettings settings;
    private final TransactionTemplate tx;
    private final Clock clock;
    private final String publicUrl;

    public PasswordService(JdbcTemplate jdbc, AuthService auth, PasswordEncoder passwordEncoder, PasswordMail mail,
                           UserSessions sessions, SystemSettings settings, PlatformTransactionManager transactionManager,
                           Clock clock, @Value("${app.public-url:http://localhost:8080}") String publicUrl) {
        this.jdbc = jdbc;
        this.auth = auth;
        this.passwordEncoder = passwordEncoder;
        this.mail = mail;
        this.sessions = sessions;
        this.settings = settings;
        this.tx = new TransactionTemplate(transactionManager);
        this.clock = clock;
        this.publicUrl = publicUrl.endsWith("/") ? publicUrl.substring(0, publicUrl.length() - 1) : publicUrl;
    }

    // ---------------------------------------------------------------- UC-U05 Quên mật khẩu

    /** Always the same answer. A link is made and sent only for an ACTIVE account within the limits. */
    public String forgot(String rawEmail) {
        long minutes = minutes();
        String email = normalizeEmail(rawEmail);
        String token = TokenHasher.randomToken();
        Boolean made = tx.execute(s -> {
            Optional<Long> userId = jdbc.query("""
                    SELECT id FROM users WHERE email = ? AND status = 'ACTIVE' FOR UPDATE""",
                    (rs, i) -> rs.getLong(1), email).stream().findFirst();
            if (userId.isEmpty()) {
                return false;
            }
            Instant now = clock.instant();
            Integer lastMinute = jdbc.queryForObject("""
                    SELECT COUNT(*) FROM user_tokens WHERE user_id = ? AND purpose = ? AND created_at > ?""",
                    Integer.class, userId.get(), PURPOSE, Timestamp.from(now.minus(RESEND_AFTER)));
            Integer lastDay = jdbc.queryForObject("""
                    SELECT COUNT(*) FROM user_tokens WHERE user_id = ? AND purpose = ? AND created_at > ?""",
                    Integer.class, userId.get(), PURPOSE, Timestamp.from(now.minus(Duration.ofDays(1))));
            if (lastMinute != null && lastMinute > 0 || lastDay != null && lastDay >= LINKS_PER_DAY) {
                return false;
            }
            // A new link cancels the older ones
            jdbc.update("""
                    UPDATE user_tokens SET expires_at = ? WHERE user_id = ? AND purpose = ? AND used_at IS NULL
                      AND expires_at > ?""", Timestamp.from(now), userId.get(), PURPOSE, Timestamp.from(now));
            jdbc.update("""
                    INSERT INTO user_tokens (user_id, purpose, token_hash, expires_at, created_at)
                    VALUES (?, ?, ?, ?, ?)""", userId.get(), PURPOSE, TokenHasher.sha256(token),
                    Timestamp.from(now.plus(Duration.ofMinutes(minutes))), Timestamp.from(now));
            return true;
        });
        if (Boolean.TRUE.equals(made)) {
            mail.sendResetLink(email, publicUrl + "/reset-password?token="
                    + URLEncoder.encode(token, StandardCharsets.UTF_8), minutes);
        }
        return SENT.formatted(minutes);
    }

    /** The account a reset link belongs to, while the link is valid: the page shows "a***@gmail.com". */
    public String check(String token) {
        return linkOwner(token, false).map(o -> masked(o.email())).orElseThrow(() -> ApiException.badRequest(LINK_INVALID));
    }

    /** Sets the new password, closes every reset link of the account, lifts a login lock, signs every device out. */
    public void reset(String token, String password, String confirm) {
        checkNewPassword(password, confirm, null);
        String hash = passwordEncoder.encode(password);
        Long userId = tx.execute(s -> {
            LinkOwner owner = linkOwner(token, true).orElseThrow(() -> ApiException.badRequest(LINK_INVALID));
            Instant now = clock.instant();
            jdbc.update("""
                    UPDATE users SET password_hash = ?, failed_login_count = 0, locked_until = NULL,
                                     email_verified_at = COALESCE(email_verified_at, ?)
                    WHERE id = ?""", hash, Timestamp.from(now), owner.userId());
            jdbc.update("""
                    UPDATE user_tokens SET used_at = ? WHERE user_id = ? AND purpose = ? AND used_at IS NULL""",
                    Timestamp.from(now), owner.userId(), PURPOSE);
            return owner.userId();
        });
        sessions.endOthers(userId, null);
    }

    private record LinkOwner(long userId, String email) {
    }

    private Optional<LinkOwner> linkOwner(String token, boolean lock) {
        if (token == null || token.isBlank() || token.length() > 200) {
            return Optional.empty();
        }
        return jdbc.query("""
                SELECT t.user_id, u.email FROM user_tokens t JOIN users u ON u.id = t.user_id
                WHERE t.token_hash = ? AND t.purpose = ? AND t.used_at IS NULL AND t.expires_at > ?
                  AND u.status = 'ACTIVE'""" + (lock ? " FOR UPDATE" : ""),
                (rs, i) -> new LinkOwner(rs.getLong(1), rs.getString(2)),
                TokenHasher.sha256(token), PURPOSE, Timestamp.from(clock.instant())).stream().findFirst();
    }

    // ---------------------------------------------------------------- UC-U06 Đổi mật khẩu

    /**
     * "Lưu mật khẩu" / "Tạo mật khẩu" (an account without a password). Keeps this session (new id) and signs the
     * other devices out. @return the message to show
     */
    public String change(long userId, String current, String password, String confirm, HttpServletRequest request) {
        Account account = jdbc.query("""
                SELECT email, display_name, password_hash FROM users WHERE id = ? AND status = 'ACTIVE'""",
                (rs, i) -> new Account(rs.getString(1), rs.getString(2), rs.getString(3)), userId)
                .stream().findFirst().orElseThrow(() -> ApiException.unauthorized("Bạn chưa đăng nhập."));
        boolean first = account.passwordHash() == null;
        if (!first) {
            if (current == null || current.isEmpty()) {
                throw ApiException.invalid("Hãy nhập mật khẩu hiện tại.",
                        Map.of("currentPassword", "Hãy nhập mật khẩu hiện tại."));
            }
            auth.checkCurrentPassword(userId, current);
        }
        checkNewPassword(password, confirm, first ? null : account.passwordHash());
        String hash = passwordEncoder.encode(password);
        tx.executeWithoutResult(s -> jdbc.update("UPDATE users SET password_hash = ? WHERE id = ?", hash, userId));
        request.changeSessionId();
        sessions.endOthers(userId, request.getSession(false));
        mail.sendChanged(account.email(), account.displayName());
        return first ? "Đã tạo mật khẩu. Từ giờ bạn đăng nhập được bằng email và mật khẩu." : "Đã đổi mật khẩu.";
    }

    private record Account(String email, String displayName, String passwordHash) {
    }

    // ---------------------------------------------------------------- rules

    /**
     * 8 (password_min_length) to 72 characters and 72 bytes for BCrypt, letters and digits, the two boxes equal, and
     * for a change different from the current password. All the problems are listed at once.
     */
    void checkNewPassword(String password, String confirm, String currentHash) {
        Map<String, String> errors = new LinkedHashMap<>();
        String p = password == null ? "" : password;
        int min = AuthRules.within(settings.getInt(SystemSettings.PASSWORD_MIN_LENGTH, AuthRules.PASSWORD_MIN_LENGTH),
                1, AuthRules.PASSWORD_MAX_LENGTH);
        if (p.length() < min || p.length() > AuthRules.PASSWORD_MAX_LENGTH) {
            errors.put("password", "Mật khẩu dài từ " + min + " đến " + AuthRules.PASSWORD_MAX_LENGTH + " ký tự");
        } else if (p.getBytes(StandardCharsets.UTF_8).length > AuthRules.PASSWORD_MAX_BYTES) {
            errors.put("password", "Mật khẩu quá dài (chữ có dấu được tính 2–3 ký tự). Hãy dùng mật khẩu ngắn hơn");
        } else if (!p.matches("^(?=.*\\p{L})(?=.*\\d).+$")) {
            errors.put("password", "Mật khẩu phải có cả chữ và số");
        } else if (currentHash != null && passwordEncoder.matches(p, currentHash)) {
            errors.put("password", "Mật khẩu mới phải khác mật khẩu hiện tại.");
        }
        if (!p.equals(confirm == null ? "" : confirm)) {
            errors.put("confirmPassword", "Hai mật khẩu không khớp.");
        }
        if (!errors.isEmpty()) {
            throw ApiException.invalid(errors.values().iterator().next(), errors);
        }
    }

    private long minutes() {
        return AuthRules.within(settings.getInt("reset_token_minutes", DEFAULT_MINUTES), 5, 1440);
    }

    /** "alfheim2301@gmail.com" → "a***@gmail.com". */
    static String masked(String email) {
        int at = email.indexOf('@');
        return at <= 0 ? "***" : email.charAt(0) + "***" + email.substring(at);
    }
}
