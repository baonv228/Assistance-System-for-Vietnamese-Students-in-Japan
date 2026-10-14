package com.sep490.vsa.feature.auth;

import com.sep490.vsa.common.exception.ApiException;
import com.sep490.vsa.feature.user.User;
import com.sep490.vsa.feature.user.UserRepository;
import com.sep490.vsa.feature.user.UserStatus;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

import static com.sep490.vsa.feature.auth.AuthRules.normalizeEmail;

/** Email + password login with lock-out after repeated wrong passwords. */
@Service
public class AuthService {

    private static final String WRONG_CREDENTIALS = "Email hoặc mật khẩu không đúng.";

    private final UserRepository users;
    private final PasswordEncoder passwordEncoder;
    private final Clock clock;
    /** Compared against when the email does not exist, so both cases take about the same time. */
    private final String dummyHash;

    public AuthService(UserRepository users, PasswordEncoder passwordEncoder, Clock clock) {
        this.users = users;
        this.passwordEncoder = passwordEncoder;
        this.clock = clock;
        this.dummyHash = passwordEncoder.encode("not-a-real-password-1");
    }

    /** Failed attempts must be saved even though an error is returned, so no rollback for ApiException. */
    @Transactional(noRollbackFor = ApiException.class)
    public User login(String rawEmail, String password) {
        Instant now = clock.instant();
        User user = users.findByEmailIgnoreCase(normalizeEmail(rawEmail)).orElse(null);
        if (user == null) {
            passwordEncoder.matches(password, dummyHash);
            throw ApiException.unauthorized(WRONG_CREDENTIALS);
        }
        if (user.getStatus() != UserStatus.ACTIVE) {
            throw ApiException.forbidden("Tài khoản này đã bị khóa. Vui lòng liên hệ quản trị viên.");
        }
        if (user.isLockedAt(now)) {
            long minutes = Math.max(1, Duration.between(now, user.getLockedUntil()).toMinutes() + 1);
            throw new ApiException(HttpStatus.LOCKED,
                    "Bạn đã nhập sai mật khẩu quá nhiều lần. Vui lòng thử lại sau " + minutes + " phút.");
        }
        if (user.getPasswordHash() == null) {
            throw ApiException.badRequest(
                    "Tài khoản này được tạo bằng Google hoặc Facebook. Hãy đăng nhập bằng nút tương ứng.");
        }
        if (!passwordEncoder.matches(password, user.getPasswordHash())) {
            user.recordFailedLogin(now, AuthRules.LOGIN_MAX_ATTEMPTS, AuthRules.LOGIN_LOCK);
            if (user.isLockedAt(now)) {
                throw new ApiException(HttpStatus.LOCKED, "Bạn đã nhập sai mật khẩu "
                        + AuthRules.LOGIN_MAX_ATTEMPTS + " lần. Tài khoản tạm khóa "
                        + AuthRules.LOGIN_LOCK.toMinutes() + " phút.");
            }
            int left = AuthRules.LOGIN_MAX_ATTEMPTS - user.getFailedLoginCount();
            throw ApiException.unauthorized(left <= 2
                    ? WRONG_CREDENTIALS + " Còn " + left + " lần thử trước khi tài khoản bị tạm khóa."
                    : WRONG_CREDENTIALS);
        }
        user.recordSuccessfulLogin(now);
        return user;
    }

    @Transactional(readOnly = true)
    public User get(Long id) {
        return users.findById(id).orElseThrow(() -> ApiException.unauthorized("Bạn chưa đăng nhập."));
    }
}
