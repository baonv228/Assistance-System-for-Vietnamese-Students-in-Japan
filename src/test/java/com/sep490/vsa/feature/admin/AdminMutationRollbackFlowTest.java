package com.sep490.vsa.feature.admin;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sep490.vsa.common.live.LiveEvents;
import com.sep490.vsa.feature.moderation.ContentFilter;
import jakarta.persistence.EntityManager;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.dao.DataAccessException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;
import javax.sql.DataSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.reset;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * ADM-DB-01: every Admin write must roll back its mutation, metadata, dependent counts and audit together.
 * No test transaction wraps these requests: assertions run after the service's real transaction has ended.
 * Failures are injected after the real audit INSERT, proving that even an inserted audit cannot survive a rollback.
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:mysql://localhost:3306/asvsj_test?createDatabaseIfNotExist=true&useUnicode=true&characterEncoding=utf8&connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true",
        "spring.datasource.hikari.maximum-pool-size=4",
        "spring.datasource.hikari.minimum-idle=1",
        "app.media.local-dir=target/test-uploads",
        "app.moderation.retry-initial-delay-ms=3600000",
        "app.places.osm.enabled=false"
})
@AutoConfigureMockMvc
@ActiveProfiles("local")
class AdminMutationRollbackFlowTest {

    private static final String CSRF = "rollback-csrf-token";
    private static final String PASSWORD = "rollback-password-123";
    private static final String HASH = new BCryptPasswordEncoder().encode(PASSWORD);
    private static final String FAILURE = "injected failure after audit insert";

    @Autowired
    private MockMvc mvc;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private ObjectMapper json;
    @Autowired
    private DataSource dataSource;
    @Autowired
    private EntityManager entityManager;
    @Autowired
    private PlatformTransactionManager transactionManager;
    @Autowired
    private LiveEvents live;
    @Autowired
    private ContentFilter filter;
    @SpyBean
    private ActivityLogService audit;

    private final String marker = "zzrollback" + UUID.randomUUID().toString().replace("-", "");
    private List<Map<String, Object>> settingsBefore;
    private long adminId;
    private long targetId;
    private long postId;
    private long keywordId;
    private MockHttpSession admin;

    enum Operation {
        ROLE("USER_ROLE_CHANGE"), LOCK("USER_STATUS_CHANGE"), UNLOCK("USER_STATUS_CHANGE"),
        SETTING("SETTING_UPDATE"), SAFETY_BATCH("SETTING_UPDATE"),
        KEYWORD_ADD("KEYWORD_ADD"), KEYWORD_EDIT("KEYWORD_UPDATE"), KEYWORD_DELETE("KEYWORD_DELETE");

        final String auditAction;

        Operation(String auditAction) {
            this.auditAction = auditAction;
        }
    }

    enum FailureKind {
        AFTER_AUDIT_INSERT, DATABASE_CHECK
    }

    static Stream<Arguments> mutationFailures() {
        return Stream.of(Operation.values()).flatMap(operation -> Stream.of(FailureKind.values())
                .map(failure -> Arguments.of(operation, failure)));
    }

    @BeforeEach
    void prepareCommittedLocalFixtures() throws Exception {
        try (var connection = dataSource.getConnection()) {
            assertThat(connection.getMetaData().getURL()).startsWith("jdbc:mysql://localhost:3306/asvsj_test");
            assertThat(connection.getCatalog()).isEqualTo("asvsj_test");
        }
        settingsBefore = jdbc.queryForList("SELECT setting_key, setting_value, updated_by, updated_at FROM system_settings ORDER BY setting_key");
        adminId = user("ADMIN");
        targetId = user("USER");
        // Deterministic changed values, including metadata to prove rollback restores more than setting_value.
        jdbc.update("UPDATE system_settings SET setting_value = '3' WHERE setting_key = 'posts_per_day'");
        jdbc.update("UPDATE system_settings SET setting_value = '2' WHERE setting_key = 'pins_per_day'");
        jdbc.update("INSERT INTO blogs (author_id, title, content, status, comment_count) VALUES (?, ?, 'Post fixture', 'PUBLISHED', 1)",
                adminId, marker);
        postId = jdbc.queryForObject("SELECT id FROM blogs WHERE title = ?", Long.class, marker);
        jdbc.update("INSERT INTO comments (blog_id, author_id, content, status) VALUES (?, ?, 'Comment fixture', 'PUBLISHED')",
                postId, targetId);
        jdbc.update("INSERT INTO banned_keywords (keyword, created_by) VALUES (?, ?)", marker + "old", adminId);
        keywordId = jdbc.queryForObject("SELECT id FROM banned_keywords WHERE keyword = ?", Long.class, marker + "old");
        filter.keywordsChanged();
        admin = (MockHttpSession) mvc.perform(post("/api/auth/login")
                        .cookie(new Cookie("XSRF-TOKEN", CSRF)).header("X-XSRF-TOKEN", CSRF)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("email", marker + "admin@test.local", "password", PASSWORD))))
                .andExpect(status().isOk()).andReturn().getRequest().getSession(false);
    }

    @AfterEach
    void restoreCommittedLocalFixtures() {
        reset(audit);
        if (settingsBefore != null) {
            for (Map<String, Object> setting : settingsBefore) {
                jdbc.update("UPDATE system_settings SET setting_value = ?, updated_by = ?, updated_at = ? WHERE setting_key = ?",
                        setting.get("setting_value"), setting.get("updated_by"), setting.get("updated_at"), setting.get("setting_key"));
            }
        }
        if (targetId != 0) {
            live.close(targetId);
        }
        if (postId != 0) {
            jdbc.update("DELETE FROM blogs WHERE id = ?", postId);
        }
        if (adminId != 0) {
            jdbc.update("DELETE FROM banned_keywords WHERE created_by = ?", adminId);
            jdbc.update("DELETE FROM activity_logs WHERE actor_id = ?", adminId);
            jdbc.update("DELETE FROM activity_logs WHERE action = ?", "LOG_" + marker);
            jdbc.update("DELETE FROM users WHERE id IN (?, ?)", adminId, targetId);
        }
        filter.keywordsChanged();
    }

    @ParameterizedTest(name = "{0} rolls back after {1}")
    @MethodSource("mutationFailures")
    void everyAdminMutationRollsBackAfterAuditOrDatabaseFailure(Operation operation, FailureKind failureKind) throws Exception {
        if (operation == Operation.UNLOCK) {
            jdbc.update("UPDATE users SET status = 'LOCKED' WHERE id = ?", targetId);
            jdbc.update("UPDATE blogs SET comment_count = 0 WHERE id = ?", postId);
        }
        var usersBefore = userRows();
        var settingsBeforeRequest = settingRows();
        var keywordsBefore = keywordRows();
        var postBefore = postRow();
        long auditBefore = auditCount();
        assertThat(filter.bannedKeyword("Example " + marker + "old")).contains(marker + "old");
        if (operation == Operation.LOCK) {
            live.open(targetId);
            assertThat(openStreams()).isEqualTo(1);
        }
        AtomicBoolean auditInserted = new AtomicBoolean();
        doAnswer(invocation -> {
            // Verify the write happened inside the transaction before inducing its failure.
            assertPendingMutation(operation);
            invocation.callRealMethod();
            entityManager.flush();
            assertThat(auditCount()).isEqualTo(auditBefore + 1);
            auditInserted.set(true);
            if (failureKind == FailureKind.DATABASE_CHECK) {
                jdbc.update("UPDATE users SET status = 'INVALID_ROLLBACK_STATUS' WHERE id = ?", targetId);
                throw new AssertionError("The users status CHECK must reject the injected DB write");
            }
            throw new IllegalStateException(FAILURE);
        }).when(audit).recordLog(eq(adminId), eq(operation.auditAction), anyString(), nullable(Long.class),
                nullable(Map.class), nullable(Map.class), anyString());

        if (failureKind == FailureKind.AFTER_AUDIT_INSERT) {
            assertThatThrownBy(() -> mvc.perform(request(operation))).hasRootCauseMessage(FAILURE);
        } else {
            assertThatThrownBy(() -> mvc.perform(request(operation))).hasRootCauseInstanceOf(SQLException.class);
        }

        assertThat(auditInserted).as("the request reached the persisted audit before failing").isTrue();
        assertThat(userRows()).isEqualTo(usersBefore);
        assertThat(settingRows()).isEqualTo(settingsBeforeRequest);
        assertThat(keywordRows()).isEqualTo(keywordsBefore);
        assertThat(postRow()).isEqualTo(postBefore);
        assertThat(auditCount()).isEqualTo(auditBefore);
        assertThat(filter.bannedKeyword("Example " + marker + "old")).contains(marker + "old");
        assertThat(filter.bannedKeyword("Example " + marker + "new")).isEmpty();
        if (operation == Operation.LOCK) {
            assertThat(openStreams()).as("a rolled-back account lock must not close the live session").isEqualTo(1);
        }
    }

    @Test
    void uniquenessForeignKeyAndCheckFailuresUndoEarlierWritesInTheSameTransaction() {
        var before = settingRows();
        List<Runnable> invalidWrites = List.of(
                () -> jdbc.update("INSERT INTO banned_keywords (keyword, created_by) VALUES (?, ?)", marker + "old", adminId),
                () -> jdbc.update("INSERT INTO banned_keywords (keyword, created_by) VALUES (?, ?)", marker + "foreign", Long.MAX_VALUE),
                () -> jdbc.update("UPDATE users SET role = 'INVALID_ROLE' WHERE id = ?", targetId));

        for (Runnable invalidWrite : invalidWrites) {
            assertThatThrownBy(() -> new TransactionTemplate(transactionManager).executeWithoutResult(transaction -> {
                jdbc.update("UPDATE system_settings SET setting_value = '17', updated_by = ?, updated_at = CURRENT_TIMESTAMP WHERE setting_key = 'posts_per_day'",
                        adminId);
                invalidWrite.run();
            })).isInstanceOf(DataAccessException.class);
            assertThat(settingRows()).as("a constraint error must undo the earlier setting write").isEqualTo(before);
            assertThat(auditCount()).isZero();
            assertThat(keywordRows()).hasSize(1);
            assertThat(jdbc.queryForObject("SELECT role FROM users WHERE id = ?", String.class, targetId)).isEqualTo("USER");
        }
    }

    @Test
    void logsPreserveJsonAndDeletedActorsWithoutExposingAccountSecrets() throws Exception {
        String goneEmail = marker + "gone@test.local";
        jdbc.update("INSERT INTO users (email, password_hash, display_name, role, status, is_adult_confirmed) "
                + "VALUES (?, ?, 'Deleted log actor', 'USER', 'ACTIVE', TRUE)", goneEmail, HASH);
        long goneId = jdbc.queryForObject("SELECT id FROM users WHERE email = ?", Long.class, goneEmail);
        String payload = "Quote \" and slash \\\\ 日本語 😀 <script>window.logInjected=1</script>";
        String encoded = json.writeValueAsString(Map.of("message", payload));
        try {
            jdbc.update("INSERT INTO activity_logs (actor_id, action, target_type, new_value, description) "
                    + "VALUES (?, ?, 'TEST', ?, ?)", goneId, "LOG_" + marker, encoded, payload);
            jdbc.update("INSERT INTO activity_logs (action, target_type, old_value, description) "
                    + "VALUES (?, 'TEST', ?, ?)", "LOG_" + marker, encoded, payload);
        } finally {
            jdbc.update("DELETE FROM users WHERE id = ?", goneId);
        }

        String response = mvc.perform(get("/api/admin/logs").param("action", "LOG_" + marker).session(admin))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        var items = json.readTree(response).path("data").path("items");
        assertThat(items.size()).isEqualTo(2);
        for (var item : items) {
            assertThat(item.path("actorId").isNull()).isTrue();
            assertThat(item.path("actorName").asText()).isEqualTo("Hệ thống");
            assertThat(item.path("actorEmail").asText()).isEqualTo("-");
            assertThat(item.path("description").asText()).isEqualTo(payload);
            var data = item.path("oldValue").isNull() ? item.path("newValue") : item.path("oldValue");
            assertThat(json.readTree(data.asText()).path("message").asText()).isEqualTo(payload);
            var fields = new HashSet<String>();
            item.fieldNames().forEachRemaining(fields::add);
            assertThat(fields).containsExactlyInAnyOrder("id", "actorId", "actorName", "actorEmail", "action",
                    "targetType", "targetId", "oldValue", "newValue", "description", "createdAt");
        }
        assertThat(response).doesNotContain(HASH, "passwordHash", "password_hash", "XSRF-TOKEN", "JSESSIONID");
        assertThat(response).doesNotContain(goneEmail);
    }

    private MockHttpServletRequestBuilder request(Operation operation) throws Exception {
        MockHttpServletRequestBuilder request;
        Object body;
        switch (operation) {
            case ROLE -> {
                request = put("/api/admin/users/" + targetId + "/role");
                body = Map.of("role", "ACADEMIC_MANAGER");
            }
            case LOCK -> {
                request = put("/api/admin/users/" + targetId + "/status");
                body = Map.of("status", "LOCKED", "reason", marker);
            }
            case UNLOCK -> {
                request = put("/api/admin/users/" + targetId + "/status");
                body = Map.of("status", "ACTIVE", "reason", marker);
            }
            case SETTING -> {
                request = put("/api/admin/settings/posts_per_day");
                body = Map.of("settingValue", "12");
            }
            case SAFETY_BATCH -> {
                request = put("/api/admin/safety-config");
                body = Map.of("posts_per_day", 12, "pins_per_day", 11);
            }
            case KEYWORD_ADD -> {
                request = post("/api/admin/keywords");
                body = Map.of("keyword", marker + "new");
            }
            case KEYWORD_EDIT -> {
                request = put("/api/admin/keywords/" + keywordId);
                body = Map.of("keyword", marker + "new");
            }
            case KEYWORD_DELETE -> {
                request = delete("/api/admin/keywords/" + keywordId);
                body = Map.of();
            }
            default -> throw new IllegalArgumentException(operation.name());
        }
        return request.session(admin).cookie(new Cookie("XSRF-TOKEN", CSRF)).header("X-XSRF-TOKEN", CSRF)
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body));
    }

    private void assertPendingMutation(Operation operation) {
        switch (operation) {
            case ROLE -> assertThat(jdbc.queryForObject("SELECT role FROM users WHERE id = ?", String.class, targetId))
                    .isEqualTo("ACADEMIC_MANAGER");
            case LOCK, UNLOCK -> {
                assertThat(jdbc.queryForObject("SELECT status FROM users WHERE id = ?", String.class, targetId))
                        .isEqualTo(operation == Operation.LOCK ? "LOCKED" : "ACTIVE");
                assertThat(jdbc.queryForObject("SELECT comment_count FROM blogs WHERE id = ?", Integer.class, postId))
                        .isEqualTo(operation == Operation.LOCK ? 0 : 1);
            }
            case SETTING, SAFETY_BATCH -> {
                assertThat(jdbc.queryForObject("SELECT setting_value FROM system_settings WHERE setting_key = 'posts_per_day'", String.class))
                        .isEqualTo("12");
                if (operation == Operation.SAFETY_BATCH) {
                    assertThat(jdbc.queryForObject("SELECT setting_value FROM system_settings WHERE setting_key = 'pins_per_day'", String.class))
                            .isEqualTo("11");
                }
            }
            case KEYWORD_ADD -> assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM banned_keywords WHERE keyword = ?", Long.class, marker + "new"))
                    .isEqualTo(1);
            case KEYWORD_EDIT -> assertThat(jdbc.queryForObject("SELECT keyword FROM banned_keywords WHERE id = ?", String.class, keywordId))
                    .isEqualTo(marker + "new");
            case KEYWORD_DELETE -> assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM banned_keywords WHERE id = ?", Long.class, keywordId))
                    .isZero();
        }
    }

    private long user(String role) {
        String email = marker + ("ADMIN".equals(role) ? "admin" : "user") + "@test.local";
        jdbc.update("INSERT INTO users (email, password_hash, email_verified_at, display_name, role, status, is_adult_confirmed, created_at) "
                        + "VALUES (?, ?, ?, 'Rollback Test', ?, 'ACTIVE', TRUE, ?)", email, HASH, Timestamp.from(Instant.now()), role,
                Timestamp.from(Instant.now().minus(Duration.ofDays(30))));
        return jdbc.queryForObject("SELECT id FROM users WHERE email = ?", Long.class, email);
    }

    private List<Map<String, Object>> userRows() {
        return jdbc.queryForList("SELECT id, role, status, updated_at FROM users WHERE id IN (?, ?) ORDER BY id", adminId, targetId);
    }

    private List<Map<String, Object>> settingRows() {
        return jdbc.queryForList("SELECT setting_key, setting_value, updated_by, updated_at FROM system_settings "
                + "WHERE setting_key IN ('posts_per_day', 'pins_per_day', 'max_moderators') ORDER BY setting_key");
    }

    private List<Map<String, Object>> keywordRows() {
        return jdbc.queryForList("SELECT id, keyword, created_by, created_at FROM banned_keywords WHERE created_by = ? ORDER BY id", adminId);
    }

    private Map<String, Object> postRow() {
        return jdbc.queryForMap("SELECT id, status, comment_count, updated_at FROM blogs WHERE id = ?", postId);
    }

    private long auditCount() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM activity_logs WHERE actor_id = ?", Long.class, adminId);
    }

    private int openStreams() {
        Integer count = ReflectionTestUtils.invokeMethod(live, "openStreams", targetId);
        return count == null ? 0 : count;
    }
}
