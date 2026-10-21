package com.sep490.vsa.feature.auth;

import com.sep490.vsa.common.exception.ApiException;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.matchesPattern;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.cookie;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Password login against MySQL ({@code asvsj_test}, like the other flow tests): the lock-out after wrong passwords
 * (counted by the database, so parallel attempts are all counted), the Admin settings lock_attempts / lock_minutes,
 * and that an Admin lock is told only to someone who gave the right password. Every test makes its own accounts and
 * deletes them afterwards; changed settings are put back.
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:mysql://localhost:3306/asvsj_test?createDatabaseIfNotExist=true&useUnicode=true&characterEncoding=utf8&connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true",
        "app.media.local-dir=target/test-uploads",
        "app.moderation.retry-initial-delay-ms=3600000",
        "app.places.osm.enabled=false"
})
@AutoConfigureMockMvc
@ActiveProfiles("local")
class LoginFlowTest {

    private static final String CSRF = "test-csrf-token";
    private static final String PASSWORD = "matkhau123";
    private static final String WRONG_PASSWORD = "sai-mat-khau-1";
    private static final String HASH = new BCryptPasswordEncoder().encode(PASSWORD);
    private static final String WRONG = "Email hoặc mật khẩu không đúng.";
    private static final String BLOCKED = "Tài khoản này đã bị khóa. Vui lòng liên hệ quản trị viên.";
    private static final String SOCIAL_ONLY =
            "Tài khoản này được tạo bằng Google hoặc Facebook. Hãy đăng nhập bằng nút tương ứng.";
    private static final String TRY_LATER = "Bạn đã nhập sai mật khẩu quá nhiều lần. Vui lòng thử lại sau \\d+ phút\\.";

    @Autowired
    private MockMvc mvc;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private AuthService auth;

    private final List<Long> created = new ArrayList<>();
    private final Map<String, String> changedSettings = new java.util.HashMap<>();

    @AfterEach
    void cleanUp() {
        changedSettings.forEach((key, value) ->
                jdbc.update("UPDATE system_settings SET setting_value = ? WHERE setting_key = ?", value, key));
        for (long id : created) {
            jdbc.update("DELETE FROM users WHERE id = ?", id);
        }
    }

    @Test
    void fiveWrongPasswordsLockTheAccountUntilTheLockEnds() throws Exception {
        long id = user("ACTIVE", HASH);
        String email = email(id);
        for (int i = 1; i <= 4; i++) {
            login(email, WRONG_PASSWORD).andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.message").value(i < 3 ? WRONG
                            : WRONG + " Còn " + (5 - i) + " lần thử trước khi tài khoản bị tạm khóa."));
            assertThat(count(id)).isEqualTo(i);
        }
        Instant before = Instant.now();
        login(email, WRONG_PASSWORD).andExpect(status().isLocked())
                .andExpect(jsonPath("$.message").value("Bạn đã nhập sai mật khẩu 5 lần. Tài khoản tạm khóa 30 phút."));
        assertThat(count(id)).isZero();
        assertThat(lockedUntil(id)).isBetween(before.plus(Duration.ofMinutes(30)).minusSeconds(2),
                Instant.now().plus(Duration.ofMinutes(30)).plusSeconds(2));

        // While locked: the right password is refused, and wrong ones are not counted toward the next lock
        login(email, PASSWORD).andExpect(status().isLocked()).andExpect(jsonPath("$.message").value(matchesPattern(TRY_LATER)));
        login(email, WRONG_PASSWORD).andExpect(status().isLocked())
                .andExpect(jsonPath("$.message").value(matchesPattern(TRY_LATER)));
        assertThat(count(id)).isZero();
        assertThat(row(id).get("last_login_at")).isNull();

        // The lock has ended
        jdbc.update("UPDATE users SET locked_until = ? WHERE id = ?", Timestamp.from(Instant.now().minusSeconds(60)), id);
        login(email, PASSWORD).andExpect(status().isOk()).andExpect(jsonPath("$.data.id").value(id));
        Map<String, Object> row = row(id);
        assertThat(row.get("locked_until")).isNull();
        assertThat(count(id)).isZero();
        assertThat(row.get("last_login_at")).isNotNull();
    }

    @Test
    void signingInDropsTheCsrfTokenFromBefore() throws Exception {
        long id = user("ACTIVE", HASH);
        // The token the page used to sign in is cleared, so a token planted before the sign-in is worth nothing
        login(email(id), PASSWORD).andExpect(status().isOk())
                .andExpect(cookie().maxAge("XSRF-TOKEN", 0));
        // A failed sign-in keeps it (nothing changed)
        login(email(id), WRONG_PASSWORD).andExpect(status().isUnauthorized())
                .andExpect(cookie().doesNotExist("XSRF-TOKEN"));
    }

    @Test
    void tenWrongPasswordsSentTogetherAreAllCountedAndLockTheAccount() throws Exception {
        for (int round = 0; round < 3; round++) {
            long id = user("ACTIVE", HASH);
            String email = email(id);
            List<Callable<Object>> calls = new ArrayList<>();
            for (int i = 0; i < 10; i++) {
                calls.add(() -> auth.login(email, WRONG_PASSWORD));
            }
            List<Object> results = together(calls);

            List<HttpStatus> statuses = results.stream().map(r -> ((ApiException) r).getStatus()).toList();
            assertThat(statuses).filteredOn(s -> s == HttpStatus.UNAUTHORIZED).hasSize(4);
            assertThat(statuses).filteredOn(s -> s == HttpStatus.LOCKED).hasSize(6);
            assertThat(results).filteredOn(r -> ((ApiException) r).getMessage()
                    .equals("Bạn đã nhập sai mật khẩu 5 lần. Tài khoản tạm khóa 30 phút.")).hasSize(1);
            assertThat(lockedUntil(id)).isAfter(Instant.now().plus(Duration.ofMinutes(29)));
            assertThat(count(id)).isZero();
            login(email, PASSWORD).andExpect(status().isLocked());
        }
    }

    @Test
    void theLockFollowsTheAdminSettings() throws Exception {
        setting("lock_attempts", "3");
        setting("lock_minutes", "7");
        long id = user("ACTIVE", HASH);
        String email = email(id);
        login(email, WRONG_PASSWORD).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value(WRONG + " Còn 2 lần thử trước khi tài khoản bị tạm khóa."));
        login(email, WRONG_PASSWORD).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value(WRONG + " Còn 1 lần thử trước khi tài khoản bị tạm khóa."));
        Instant before = Instant.now();
        login(email, WRONG_PASSWORD).andExpect(status().isLocked())
                .andExpect(jsonPath("$.message").value("Bạn đã nhập sai mật khẩu 3 lần. Tài khoản tạm khóa 7 phút."));
        assertThat(lockedUntil(id)).isBetween(before.plus(Duration.ofMinutes(7)).minusSeconds(2),
                Instant.now().plus(Duration.ofMinutes(7)).plusSeconds(2));
        login(email, PASSWORD).andExpect(status().isLocked())
                .andExpect(jsonPath("$.message").value(matchesPattern(
                        "Bạn đã nhập sai mật khẩu quá nhiều lần. Vui lòng thử lại sau [78] phút\\.")));

        // A value that is not a number falls back to the agreed default (5 attempts)
        setting("lock_attempts", "năm");
        long other = user("ACTIVE", HASH);
        for (int i = 1; i <= 4; i++) {
            login(email(other), WRONG_PASSWORD).andExpect(status().isUnauthorized());
        }
        login(email(other), WRONG_PASSWORD).andExpect(status().isLocked())
                .andExpect(jsonPath("$.message").value("Bạn đã nhập sai mật khẩu 5 lần. Tài khoản tạm khóa 7 phút."));
    }

    @Test
    void aBlockedAccountIsToldSoOnlyWithItsRightPassword() throws Exception {
        long id = user("LOCKED", HASH);
        String email = email(id);
        login(email, WRONG_PASSWORD).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.message").value(WRONG));
        login(email, PASSWORD).andExpect(status().isForbidden()).andExpect(jsonPath("$.message").value(BLOCKED));
        Map<String, Object> row = row(id);
        assertThat(row.get("last_login_at")).isNull();
        assertThat(count(id)).isEqualTo(1);

        // Wrong passwords count and lock exactly as for an active account, so the answers never tell the two apart
        login(email, WRONG_PASSWORD).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.message").value(WRONG));
        login(email, WRONG_PASSWORD).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value(WRONG + " Còn 2 lần thử trước khi tài khoản bị tạm khóa."));
        login(email, WRONG_PASSWORD).andExpect(status().isUnauthorized());
        login(email, WRONG_PASSWORD).andExpect(status().isLocked())
                .andExpect(jsonPath("$.message").value("Bạn đã nhập sai mật khẩu 5 lần. Tài khoản tạm khóa 30 phút."));
        login(email, PASSWORD).andExpect(status().isLocked());
        jdbc.update("UPDATE users SET locked_until = NULL WHERE id = ?", id);
        login(email, PASSWORD).andExpect(status().isForbidden()).andExpect(jsonPath("$.message").value(BLOCKED));
        assertThat(row(id).get("last_login_at")).isNull();
        assertThat(row(id).get("status")).isEqualTo("LOCKED");
    }

    @Test
    void deletedUnknownAndSocialOnlyAccounts() throws Exception {
        // A deleted account answers like an unknown email, even with the password it had
        long deleted = user("DELETED", HASH);
        login(email(deleted), PASSWORD).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.message").value(WRONG));
        long deletedSocial = user("DELETED", null);
        login(email(deletedSocial), PASSWORD).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value(WRONG));
        login("nobody-" + UUID.randomUUID() + "@test.local", PASSWORD).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value(WRONG));

        // The Google/Facebook hint stays, for an active and a blocked account alike
        long social = user("ACTIVE", null);
        login(email(social), PASSWORD).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(SOCIAL_ONLY));
        long blockedSocial = user("LOCKED", null);
        login(email(blockedSocial), PASSWORD).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(SOCIAL_ONLY));
        assertThat(count(social)).isZero();
        assertThat(count(deleted)).isZero();
    }

    // ---------------------------------------------------------------- helpers

    private long user(String status, String hash) {
        String email = "login-" + UUID.randomUUID() + "@test.local";
        jdbc.update("""
                INSERT INTO users (email, password_hash, email_verified_at, display_name, role, status, is_adult_confirmed,
                                   created_at)
                VALUES (?, ?, ?, 'Login Test', 'USER', ?, TRUE, ?)""",
                email, hash, Timestamp.from(Instant.now()), status,
                Timestamp.from(Instant.now().minus(Duration.ofDays(30))));
        long id = jdbc.queryForObject("SELECT id FROM users WHERE email = ?", Long.class, email);
        created.add(id);
        return id;
    }

    private void setting(String key, String value) {
        changedSettings.putIfAbsent(key, jdbc.queryForObject(
                "SELECT setting_value FROM system_settings WHERE setting_key = ?", String.class, key));
        jdbc.update("UPDATE system_settings SET setting_value = ? WHERE setting_key = ?", value, key);
    }

    private String email(long id) {
        return jdbc.queryForObject("SELECT email FROM users WHERE id = ?", String.class, id);
    }

    private int count(long id) {
        return jdbc.queryForObject("SELECT failed_login_count FROM users WHERE id = ?", Integer.class, id);
    }

    private Instant lockedUntil(long id) {
        return jdbc.queryForObject("SELECT locked_until FROM users WHERE id = ?", Timestamp.class, id).toInstant();
    }

    private Map<String, Object> row(long id) {
        return jdbc.queryForMap("SELECT status, locked_until, last_login_at FROM users WHERE id = ?", id);
    }

    private ResultActions login(String email, String password) throws Exception {
        return mvc.perform(post("/api/auth/login").cookie(new Cookie("XSRF-TOKEN", CSRF)).header("X-XSRF-TOKEN", CSRF)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}"));
    }

    /** Runs the calls on separate threads, released at the same moment; an exception is returned as the result. */
    static List<Object> together(List<Callable<Object>> calls) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(calls.size());
        try {
            CountDownLatch start = new CountDownLatch(1);
            List<Future<Object>> futures = new ArrayList<>();
            for (Callable<Object> call : calls) {
                futures.add(pool.submit(() -> {
                    start.await();
                    try {
                        return call.call();
                    } catch (Exception e) {
                        return e;
                    }
                }));
            }
            start.countDown();
            List<Object> results = new ArrayList<>();
            for (Future<Object> f : futures) {
                results.add(f.get(60, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            pool.shutdownNow();
        }
    }
}
