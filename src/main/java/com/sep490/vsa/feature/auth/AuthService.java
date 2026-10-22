package com.sep490.vsa.feature.auth;

import com.sep490.vsa.common.exception.ApiException;
import com.sep490.vsa.common.settings.SystemSettings;
import com.sep490.vsa.feature.user.User;
import com.sep490.vsa.feature.user.UserRepository;
import com.sep490.vsa.feature.user.UserStatus;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

import static com.sep490.vsa.feature.auth.AuthRules.normalizeEmail;

/**
 * Email + password login with lock-out after repeated wrong passwords: lock_attempts wrong passwords in a row lock
 * the account for lock_minutes (Admin settings, default 5 and 30).
 * <ul>
 *   <li>The account is read in a short read-only step and BCrypt runs outside any transaction: it takes a moment, and
 *       ten logins at once must not hold ten pooled connections meanwhile.</li>
 *   <li>The outcome is then written with single UPDATE statements in a short transaction ({@link UserRepository}): the
 *       database adds 1 to the counter or clears it, so wrong passwords sent at the same moment are all counted and
 *       the lock cannot be stretched by sending them in parallel (S3).</li>
 *   <li>That an account is locked by an Admin is said only to someone who gave its right password; a wrong password
 *       gets the normal answer and is counted as for any account (S10). A deleted account answers like an unknown
 *       email.</li>
 * </ul>
 */
@Service
public class AuthService {

    private static final String WRONG_CREDENTIALS = "Email hoặc mật khẩu không đúng.";
    private static final String ACCOUNT_LOCKED = "Tài khoản này đã bị khóa. Vui lòng liên hệ quản trị viên.";
    private static final String SOCIAL_ONLY =
            "Tài khoản này được tạo bằng Google hoặc Facebook. Hãy đăng nhập bằng nút tương ứng.";
    /** failed_login_count is a TINYINT; the Admin setting is kept within this. */
    private static final int MAX_ATTEMPTS_LIMIT = 100;

    private final UserRepository users;
    private final PasswordEncoder passwordEncoder;
    private final SystemSettings settings;
    private final TransactionTemplate tx;
    private final Clock clock;
    /** Compared against when the email does not exist, so both cases take about the same time. */
    private final String dummyHash;

    public AuthService(UserRepository users, PasswordEncoder passwordEncoder, SystemSettings settings,
                       PlatformTransactionManager transactionManager, Clock clock) {
        this.users = users;
        this.passwordEncoder = passwordEncoder;
        this.settings = settings;
        this.tx = new TransactionTemplate(transactionManager);
        this.clock = clock;
        this.dummyHash = passwordEncoder.encode("not-a-real-password-1");
    }

    /** Not transactional on purpose (see the class comment); each write step opens its own short transaction. */
    public User login(String rawEmail, String password) {
        Instant now = clock.instant();
        User user = users.findByEmailIgnoreCase(normalizeEmail(rawEmail))
                .filter(u -> u.getStatus() != UserStatus.DELETED)
                .orElse(null);
        if (user == null || user.getPasswordHash() == null) {
            passwordEncoder.matches(password, dummyHash);
            if (user == null) {
                throw ApiException.unauthorized(WRONG_CREDENTIALS);
            }
            throw ApiException.badRequest(SOCIAL_ONLY);
        }
        if (user.isLockedAt(now)) {
            throw lockedUntil(user.getLockedUntil(), now);
        }
        if (!passwordEncoder.matches(password, user.getPasswordHash())) {
            throw wrongPassword(user.getId(), now);
        }
        return signIn(user.getId(), now);
    }

    @Transactional(readOnly = true)
    public User get(Long id) {
        return users.findById(id).orElseThrow(() -> ApiException.unauthorized("Bạn chưa đăng nhập."));
    }

    /** Counts the wrong password (and locks the account at the limit); returns the answer to throw. */
    private ApiException wrongPassword(long userId, Instant now) {
        int maxAttempts = AuthRules.within(
                settings.getInt(SystemSettings.LOCK_ATTEMPTS, AuthRules.LOGIN_MAX_ATTEMPTS), 1, MAX_ATTEMPTS_LIMIT);
        long lockMinutes = Math.max(1,
                settings.getInt(SystemSettings.LOCK_MINUTES, (int) AuthRules.LOGIN_LOCK.toMinutes()));
        return tx.execute(status -> {
            if (users.countFailedLogin(userId, now) == 0) {
                // Locked meanwhile by wrong passwords sent at the same moment
                return users.findById(userId)
                        .filter(u -> u.isLockedAt(now))
                        .map(u -> lockedUntil(u.getLockedUntil(), now))
                        .orElseGet(() -> ApiException.unauthorized(WRONG_CREDENTIALS));
            }
            if (users.lockAfterFailedLogins(userId, maxAttempts, now.plus(Duration.ofMinutes(lockMinutes))) == 1) {
                return new ApiException(HttpStatus.LOCKED, "Bạn đã nhập sai mật khẩu "
                        + maxAttempts + " lần. Tài khoản tạm khóa " + lockMinutes + " phút.");
            }
            int left = maxAttempts - users.findById(userId).map(User::getFailedLoginCount).orElse(0);
            return ApiException.unauthorized(left <= 2
                    ? WRONG_CREDENTIALS + " Còn " + left + " lần thử trước khi tài khoản bị tạm khóa."
                    : WRONG_CREDENTIALS);
        });
    }

    /** Right password: signs in an ACTIVE account that is not locked; otherwise says why not. */
    private User signIn(long userId, Instant now) {
        return tx.execute(status -> {
            boolean allowed = users.recordLogin(userId, now) == 1;
            User current = users.findById(userId).orElse(null);
            if (allowed && current != null) {
                return current;
            }
            if (current == null || current.getStatus() == UserStatus.DELETED) {
                throw ApiException.unauthorized(WRONG_CREDENTIALS);
            }
            if (current.isLockedAt(now)) {
                throw lockedUntil(current.getLockedUntil(), now);
            }
            throw ApiException.forbidden(ACCOUNT_LOCKED);
        });
    }

    private static ApiException lockedUntil(Instant lockedUntil, Instant now) {
        long minutes = Math.max(1, Duration.between(now, lockedUntil).toMinutes() + 1);
        return new ApiException(HttpStatus.LOCKED,
                "Bạn đã nhập sai mật khẩu quá nhiều lần. Vui lòng thử lại sau " + minutes + " phút.");
    }
}
