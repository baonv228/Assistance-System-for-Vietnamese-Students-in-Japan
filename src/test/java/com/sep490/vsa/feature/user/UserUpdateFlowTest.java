package com.sep490.vsa.feature.user;

import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Writes of the {@link User} entity against MySQL ({@code asvsj_test}, like the other flow tests). {@code User} has
 * {@code @DynamicUpdate}: a flush writes only the columns that changed, so a login that loaded the row before an Admin
 * locked or demoted the account cannot write the old status or role back (S2). The other tests check that every
 * place that changes a User still writes the columns it means to.
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:mysql://localhost:3306/asvsj_test?createDatabaseIfNotExist=true&useUnicode=true&characterEncoding=utf8&connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true",
        "app.media.local-dir=target/test-uploads",
        "app.moderation.retry-initial-delay-ms=3600000",
        "app.places.osm.enabled=false"
})
@AutoConfigureMockMvc
@ActiveProfiles("local")
class UserUpdateFlowTest {

    private static final String CSRF = "test-csrf-token";
    private static final String PASSWORD = "matkhau123";
    private static final String HASH = new BCryptPasswordEncoder().encode(PASSWORD);

    @Autowired
    private MockMvc mvc;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private UserRepository users;
    @Autowired
    private PlatformTransactionManager transactions;

    @Test
    void aLoginFlushKeepsALockAndADemotionCommittedMeanwhile() {
        long id = user("ADMIN");
        TransactionTemplate login = new TransactionTemplate(transactions);
        TransactionTemplate admin = new TransactionTemplate(transactions);
        admin.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);

        login.executeWithoutResult(tx -> {
            // AuthService.login: load the user (ACTIVE, ADMIN), check the password with BCrypt…
            User loaded = users.findById(id).orElseThrow();
            assertThat(loaded.getStatus()).isEqualTo(UserStatus.ACTIVE);
            // …meanwhile another Admin locks and demotes the account, on its own connection, and commits…
            admin.executeWithoutResult(other ->
                    jdbc.update("UPDATE users SET status = 'LOCKED', role = 'USER' WHERE id = ?", id));
            // …then the login records itself and flushes
            loaded.recordSuccessfulLogin(Instant.now());
            users.flush();
        });

        Map<String, Object> row = row(id);
        assertThat(row.get("status")).isEqualTo("LOCKED");
        assertThat(row.get("role")).isEqualTo("USER");
        assertThat(row.get("last_login_at")).isNotNull();
    }

    @Test
    void wrongPasswordsAreCountedThenLockTheAccountAndARightOneResetsTheCount() throws Exception {
        long id = user("USER");
        String email = email(id);
        for (int i = 1; i <= 4; i++) {
            login(email, "sai-mat-khau-1").andExpect(status().isUnauthorized());
            assertThat(((Number) row(id).get("failed_login_count")).intValue()).isEqualTo(i);
        }
        login(email, PASSWORD).andExpect(status().isOk());
        Map<String, Object> afterLogin = row(id);
        assertThat(((Number) afterLogin.get("failed_login_count")).intValue()).isZero();
        assertThat(afterLogin.get("last_login_at")).isNotNull();

        for (int i = 1; i <= 4; i++) {
            login(email, "sai-mat-khau-1").andExpect(status().isUnauthorized());
        }
        login(email, "sai-mat-khau-1").andExpect(status().isLocked());
        Map<String, Object> locked = row(id);
        assertThat(locked.get("locked_until")).isNotNull();
        assertThat(((Number) locked.get("failed_login_count")).intValue()).isZero();
        login(email, PASSWORD).andExpect(status().isLocked());
        assertThat(locked.get("status")).isEqualTo("ACTIVE");
    }

    @Test
    void profileUpdateAndAccountDeletionStillWriteTheirColumns() throws Exception {
        long id = user("USER");
        MockHttpSession session = signIn(email(id));
        long area = jdbc.queryForObject("SELECT MIN(id) FROM areas WHERE is_active = TRUE", Long.class);

        mvc.perform(withCsrf(put("/api/me/profile"), session).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"areaId\":" + area + ",\"stage\":\"ARR\"}"))
                .andExpect(status().isOk());
        assertThat(row(id)).containsEntry("area_id", area).containsEntry("stage", "ARR");

        mvc.perform(withCsrf(delete("/api/me"), session)).andExpect(status().isOk());
        Map<String, Object> deleted = row(id);
        assertThat(deleted).containsEntry("status", "DELETED")
                .containsEntry("email", "deleted-" + id + "@deleted.invalid")
                .containsEntry("display_name", "Tài khoản đã xóa");
        assertThat(deleted.get("password_hash")).isNull();
        assertThat(deleted.get("area_id")).isNull();
        assertThat(deleted.get("stage")).isNull();
        assertThat(deleted.get("deleted_at")).isNotNull();
    }

    // ---------------------------------------------------------------- helpers

    private long user(String role) {
        String email = "upd-" + UUID.randomUUID() + "@test.local";
        jdbc.update("""
                INSERT INTO users (email, password_hash, email_verified_at, display_name, role, status, is_adult_confirmed,
                                   created_at)
                VALUES (?, ?, ?, 'Update Test', ?, 'ACTIVE', TRUE, ?)""",
                email, HASH, Timestamp.from(Instant.now()), role,
                Timestamp.from(Instant.now().minus(Duration.ofDays(30))));
        return jdbc.queryForObject("SELECT id FROM users WHERE email = ?", Long.class, email);
    }

    private String email(long id) {
        return jdbc.queryForObject("SELECT email FROM users WHERE id = ?", String.class, id);
    }

    private Map<String, Object> row(long id) {
        return jdbc.queryForMap("""
                SELECT email, password_hash, display_name, role, status, failed_login_count, locked_until, area_id, stage,
                       last_login_at, deleted_at FROM users WHERE id = ?""", id);
    }

    private ResultActions login(String email, String password) throws Exception {
        return mvc.perform(post("/api/auth/login").cookie(new Cookie("XSRF-TOKEN", CSRF)).header("X-XSRF-TOKEN", CSRF)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}"));
    }

    /** Signs in through the real login API and returns the session it created. */
    private MockHttpSession signIn(String email) throws Exception {
        return (MockHttpSession) login(email, PASSWORD).andExpect(status().isOk())
                .andReturn().getRequest().getSession(false);
    }

    private static MockHttpServletRequestBuilder withCsrf(MockHttpServletRequestBuilder request, MockHttpSession session) {
        return request.session(session).cookie(new Cookie("XSRF-TOKEN", CSRF)).header("X-XSRF-TOKEN", CSRF);
    }
}
