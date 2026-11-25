package com.sep490.vsa.feature.admin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sep490.vsa.feature.moderation.ContentFilter;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Additional evidence for the existing Admin scenarios, isolated from browser and other test databases. */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:mysql://localhost:3306/asvsj_admin_coverage_test?createDatabaseIfNotExist=true&useUnicode=true&characterEncoding=utf8&connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true",
        "spring.datasource.hikari.maximum-pool-size=4",
        "app.media.local-dir=target/test-uploads",
        "app.moderation.retry-initial-delay-ms=3600000",
        "app.places.osm.enabled=false"
})
@AutoConfigureMockMvc
@ActiveProfiles("local")
@Import(AdminScenarioCoverageFlowTest.FixedTime.class)
@Transactional
class AdminScenarioCoverageFlowTest {

    private static final String CSRF = "admin-coverage-csrf";
    private static final String PASSWORD = "matkhau123";
    private static final String HASH = new BCryptPasswordEncoder().encode(PASSWORD);
    // Tokyo midnight is still the previous UTC date. Historical dates also isolate seeded current-day content.
    private static final Instant NOW = Instant.parse("2001-02-02T15:00:00Z");
    private static final LocalDate TODAY = LocalDate.of(2001, 2, 3);

    @TestConfiguration(proxyBeanMethods = false)
    static class FixedTime {
        @Bean
        @Primary
        Clock coverageClock() {
            return Clock.fixed(NOW, ZoneOffset.UTC);
        }
    }

    @Autowired
    private MockMvc mvc;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private ObjectMapper json;
    @Autowired
    private ContentFilter contentFilter;

    @Test
    void invalidCsrfRejectsEveryAdminMutationWithoutChangingRowsOrAudit() throws Exception {
        MockHttpSession admin = signIn(user("ADMIN"));
        long target = user("USER");
        String existing = unique("csrfexisting");
        long keywordId = addKeyword(admin, existing);
        String added = unique("csrfadded");
        List<Map<String, Object>> settings = jdbc.queryForList("SELECT * FROM system_settings ORDER BY setting_key");
        List<Map<String, Object>> keywords = jdbc.queryForList("SELECT * FROM banned_keywords ORDER BY id");
        List<Map<String, Object>> targetRows = jdbc.queryForList("SELECT * FROM users WHERE id = ?", target);
        long logs = count("activity_logs");

        for (boolean sendCookie : new boolean[] {true, false}) {
            List<MockHttpServletRequestBuilder> requests = List.of(
                    put("/api/admin/settings/posts_per_day").content("{\"settingValue\":\"12\"}"),
                    put("/api/admin/safety-config").content("{\"posts_per_day\":12}"),
                    post("/api/admin/keywords").content(json.writeValueAsString(Map.of("keyword", added))),
                    put("/api/admin/keywords/" + keywordId).content("{\"keyword\":\"changed keyword\"}"),
                    delete("/api/admin/keywords/" + keywordId),
                    put("/api/admin/users/" + target + "/role").content("{\"role\":\"MODERATOR\"}"),
                    put("/api/admin/users/" + target + "/status").content(
                            "{\"status\":\"LOCKED\",\"reason\":\"CSRF must reject this\"}"));
            for (MockHttpServletRequestBuilder request : requests) {
                request.session(admin).contentType(MediaType.APPLICATION_JSON).header("X-XSRF-TOKEN", "wrong-token");
                if (sendCookie) {
                    request.cookie(new Cookie("XSRF-TOKEN", CSRF));
                }
                mvc.perform(request).andExpect(status().isForbidden());
                assertThat(jdbc.queryForList("SELECT * FROM system_settings ORDER BY setting_key")).isEqualTo(settings);
                assertThat(jdbc.queryForList("SELECT * FROM banned_keywords ORDER BY id")).isEqualTo(keywords);
                assertThat(jdbc.queryForList("SELECT * FROM users WHERE id = ?", target)).isEqualTo(targetRows);
                assertThat(count("activity_logs")).isEqualTo(logs);
            }
        }
    }

    @Test
    void userDetailsValidateIdsAndOnlyExposeTheDocumentedFields() throws Exception {
        MockHttpSession admin = signIn(user("ADMIN"));
        long target = user("USER");
        JsonNode data = response(get("/api/admin/users/" + target).session(admin)).path("data");
        List<String> fields = new ArrayList<>();
        data.fieldNames().forEachRemaining(fields::add);
        assertThat(fields).contains("id", "email", "role", "status", "points", "tags")
                .containsOnly("id", "email", "displayName", "avatarUrl", "role", "roleLabel", "status", "points",
                        "falseReportCount", "createdAt", "lastLoginAt", "accountAgeDays", "newAccount",
                        "reportMutedUntil", "tags");
        assertThat(data.path("id").asLong()).isEqualTo(target);
        assertThat(data.toString()).doesNotContain(HASH, PASSWORD, "password", "token", "secret", "credential");
        long logs = count("activity_logs");
        for (String invalid : List.of("-1", "0", "9223372036854775807")) {
            String body = mvc.perform(get("/api/admin/users/" + invalid).session(admin))
                    .andExpect(status().isNotFound()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
            assertThat(body).doesNotContain(HASH, PASSWORD, "password_hash", "stackTrace");
        }
        for (String invalid : List.of("not-a-number", "9223372036854775808")) {
            mvc.perform(get("/api/admin/users/" + invalid).session(admin)).andExpect(status().isBadRequest());
        }
        assertThat(count("activity_logs")).isEqualTo(logs);
    }

    @Test
    void editingAKeywordRefreshesTheAlreadyLoadedFilterImmediately() throws Exception {
        MockHttpSession admin = signIn(user("ADMIN"));
        String oldKeyword = unique("previouskeyword");
        String newKeyword = unique("replacementkeyword");
        long id = addKeyword(admin, oldKeyword);
        assertThat(contentFilter.bannedKeyword("Before " + oldKeyword + " after")).contains(oldKeyword);
        assertThat(contentFilter.bannedKeyword("Before " + newKeyword + " after")).isEmpty();

        response(csrf(put("/api/admin/keywords/" + id), admin).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("keyword", newKeyword))));

        assertThat(contentFilter.bannedKeyword("Before " + oldKeyword + " after")).isEmpty();
        assertThat(contentFilter.bannedKeyword("Before " + newKeyword + " after")).contains(newKeyword);
        assertThat(jdbc.queryForObject("SELECT keyword FROM banned_keywords WHERE id = ?", String.class, id))
                .isEqualTo(newKeyword);
    }

    @Test
    void overviewPresetsReconcileEveryTokyoDayIncludingZeroDaysAndBothRangeEdges() throws Exception {
        MockHttpSession admin = signIn(user("ADMIN"));
        long author = user("USER");
        for (int daysAgo : new int[] {30, 29, 7, 6, 1, 0}) {
            Instant timestamp = TODAY.minusDays(daysAgo).atStartOfDay(AdminOverviewService.JAPAN).toInstant();
            review(author, timestamp);
            report(timestamp, "RESOLVED", "KEEP");
        }
        Instant lastSecond = TODAY.atStartOfDay(AdminOverviewService.JAPAN).toInstant().minusSeconds(1);
        review(author, lastSecond);
        report(lastSecond, "RESOLVED", "KEEP");
        Instant nextMidnight = TODAY.plusDays(1).atStartOfDay(AdminOverviewService.JAPAN).toInstant();
        review(author, nextMidnight);
        report(nextMidnight, "RESOLVED", "KEEP");
        report(NOW, "RESOLVED", "REMOVE");
        report(NOW, "OPEN", null);
        long logs = count("activity_logs");

        Map<String, Integer> presets = new LinkedHashMap<>();
        presets.put("today", 1);
        presets.put("last7days", 7);
        presets.put("last30days", 30);
        for (Map.Entry<String, Integer> preset : presets.entrySet()) {
            LocalDate from = TODAY.minusDays(preset.getValue() - 1L);
            JsonNode data = response(get("/api/admin/dashboard/overview").session(admin)
                    .param("period", preset.getKey())).path("data");
            assertThat(data.path("period").path("preset").asText()).isEqualTo(preset.getKey());
            assertThat(data.path("period").path("from").asText()).isEqualTo(from.toString());
            assertThat(data.path("period").path("to").asText()).isEqualTo(TODAY.toString());
            JsonNode days = data.path("reviewTrend").path("days");
            assertThat(days.size()).isEqualTo(preset.getValue());
            long total = 0;
            for (int i = 0; i < days.size(); i++) {
                LocalDate date = from.plusDays(i);
                long expected = reviewsBetween(date, date.plusDays(1));
                assertThat(days.get(i).path("date").asText()).isEqualTo(date.toString());
                assertThat(days.get(i).path("count").asLong()).as(preset.getKey() + " " + date).isEqualTo(expected);
                total += expected;
            }
            assertThat(data.path("reviewTrend").path("totalReviews").asLong()).isEqualTo(total);
            long expectedReports = jdbc.queryForObject("SELECT COUNT(*) FROM report_cases WHERE status = 'RESOLVED' "
                            + "AND decision = 'KEEP' AND handled_at >= ? AND handled_at < ?", Long.class,
                    midnight(from), midnight(TODAY.plusDays(1)));
            assertThat(data.path("reviewTrend").path("dismissedReports").asLong()).isEqualTo(expectedReports);
            assertThat(data.path("empty").asBoolean()).isFalse();
            if (days.size() > 1) {
                assertThat(days.get(days.size() - 3).path("count").asLong()).as("zero-filled day").isZero();
            }
        }
        JsonNode empty = response(get("/api/admin/dashboard/overview").session(admin).param("period", "custom")
                .param("from", TODAY.minusDays(3).toString()).param("to", TODAY.minusDays(2).toString())).path("data");
        assertThat(empty.path("empty").asBoolean()).isTrue();
        assertThat(empty.path("reviewTrend").path("days").size()).isEqualTo(2);
        assertThat(empty.path("reviewTrend").path("totalReviews").asLong()).isZero();
        assertThat(empty.path("reviewTrend").path("dismissedReports").asLong()).isZero();
        assertThat(count("activity_logs")).isEqualTo(logs);
    }

    @Test
    void allPinAutoSettingsExposeMetadataAndEnforceTheirFullRangesWithoutSpuriousWrites() throws Exception {
        long adminId = user("ADMIN");
        MockHttpSession admin = signIn(adminId);
        Map<String, int[]> ranges = Map.of("pin_auto_approve", new int[] {0, 1},
                "pin_auto_min_points", new int[] {0, 100000}, "pin_auto_per_day", new int[] {1, 50});
        JsonNode items = response(get("/api/admin/settings").session(admin)).path("data");
        for (Map.Entry<String, int[]> entry : ranges.entrySet()) {
            String key = entry.getKey();
            int min = entry.getValue()[0];
            int max = entry.getValue()[1];
            JsonNode meta = null;
            for (JsonNode item : items) {
                if (key.equals(item.path("settingKey").asText())) {
                    meta = item;
                    break;
                }
            }
            assertThat(meta).as(key).isNotNull();
            assertThat(meta.path("wired").asBoolean()).isTrue();
            assertThat(meta.path("min").asInt()).isEqualTo(min);
            assertThat(meta.path("max").asInt()).isEqualTo(max);
            assertThat(meta.path("label").asText()).isNotBlank().isNotEqualTo(key);
            assertThat(meta.path("group").asText()).isNotBlank();
            assertThat(meta.path("description").asText()).isNotBlank();

            for (int value : Arrays.stream(new int[] {min, min + (max - min) / 2, max}).distinct().toArray()) {
                String old = jdbc.queryForObject("SELECT setting_value FROM system_settings WHERE setting_key = ?",
                        String.class, key);
                long before = count("activity_logs");
                response(csrf(put("/api/admin/settings/" + key), admin).contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("settingValue", Integer.toString(value)))));
                Map<String, Object> row = settingRow(key);
                assertThat(row.get("setting_value")).isEqualTo(Integer.toString(value));
                boolean changed = !old.equals(Integer.toString(value));
                assertThat(count("activity_logs")).as(key + " audit count").isEqualTo(before + (changed ? 1 : 0));
                if (changed) {
                    assertThat(((Number) row.get("updated_by")).longValue()).isEqualTo(adminId);
                    assertThat(row.get("updated_at")).isNotNull();
                    Map<String, Object> audit = jdbc.queryForMap("SELECT * FROM activity_logs WHERE actor_id = ? "
                            + "AND action = 'SETTING_UPDATE' ORDER BY id DESC LIMIT 1", adminId);
                    assertThat(json.readTree(audit.get("old_value").toString()).path(key).asText()).isEqualTo(old);
                    assertThat(json.readTree(audit.get("new_value").toString()).path(key).asText())
                            .isEqualTo(Integer.toString(value));
                }
            }
            Map<String, Object> beforeInvalid = settingRow(key);
            long logs = count("activity_logs");
            for (String invalid : List.of(Integer.toString(min - 1), Integer.toString(max + 1), "1.5", "", "abc")) {
                mvc.perform(csrf(put("/api/admin/settings/" + key), admin).contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("settingValue", invalid)))).andExpect(status().isBadRequest());
                assertThat(settingRow(key)).as(key + " rejected metadata").isEqualTo(beforeInvalid);
                assertThat(count("activity_logs")).isEqualTo(logs);
            }
            response(csrf(put("/api/admin/settings/" + key), admin).contentType(MediaType.APPLICATION_JSON)
                    .content(json.writeValueAsString(Map.of("settingValue", Integer.toString(max)))));
            assertThat(settingRow(key)).as(key + " no-op metadata").isEqualTo(beforeInvalid);
            assertThat(count("activity_logs")).isEqualTo(logs);
        }
    }

    @Test
    void everyOtherAppliedSettingAlsoAcceptsAnInteriorValueAndRecordsItsExactChange() throws Exception {
        long adminId = user("ADMIN");
        MockHttpSession admin = signIn(adminId);
        Map<String, Integer> interiorValues = Map.ofEntries(
                Map.entry("report_hide_threshold", 50), Map.entry("false_report_max", 50),
                Map.entry("closed_report_threshold", 50), Map.entry("mute_days", 183),
                Map.entry("new_account_days", 182), Map.entry("posts_per_day", 25),
                Map.entry("pins_per_day", 25), Map.entry("videos_per_day", 5),
                Map.entry("max_moderators", 2), Map.entry("password_min_length", 40),
                Map.entry("lock_attempts", 11), Map.entry("lock_minutes", 720),
                Map.entry("email_code_minutes", 30), Map.entry("email_code_attempts", 11),
                Map.entry("email_code_resend_seconds", 300), Map.entry("register_ticket_minutes", 722),
                Map.entry("log_keep_days", 1840), Map.entry("proof_keep_days", 30),
                Map.entry("removed_keep_days", 183), Map.entry("featured_top_n", 2),
                Map.entry("featured_min_score", 500));
        for (Map.Entry<String, Integer> entry : interiorValues.entrySet()) {
            String key = entry.getKey();
            String value = entry.getValue().toString();
            Map<String, Object> before = settingRow(key);
            long logs = count("activity_logs");
            response(csrf(put("/api/admin/settings/" + key), admin).contentType(MediaType.APPLICATION_JSON)
                    .content(json.writeValueAsString(Map.of("settingValue", value))));
            Map<String, Object> after = settingRow(key);
            assertThat(after.get("setting_value")).as(key).isEqualTo(value);
            boolean changed = !value.equals(before.get("setting_value"));
            assertThat(count("activity_logs")).as(key + " audit count").isEqualTo(logs + (changed ? 1 : 0));
            if (changed) {
                assertThat(((Number) after.get("updated_by")).longValue()).isEqualTo(adminId);
                Map<String, Object> audit = jdbc.queryForMap("SELECT old_value, new_value FROM activity_logs "
                        + "WHERE actor_id = ? AND action = 'SETTING_UPDATE' ORDER BY id DESC LIMIT 1", adminId);
                assertThat(json.readTree(audit.get("old_value").toString()).path(key).asText())
                        .isEqualTo(before.get("setting_value").toString());
                assertThat(json.readTree(audit.get("new_value").toString()).path(key).asText()).isEqualTo(value);
            } else {
                assertThat(after).as(key + " no-op metadata").isEqualTo(before);
            }
        }
    }

    private Map<String, Object> settingRow(String key) {
        return jdbc.queryForMap("SELECT setting_value, updated_by, updated_at FROM system_settings WHERE setting_key = ?", key);
    }

    private long reviewsBetween(LocalDate from, LocalDate to) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM reviews WHERE created_at >= ? AND created_at < ?",
                Long.class, midnight(from), midnight(to));
    }

    private static Timestamp midnight(LocalDate date) {
        return Timestamp.from(date.atStartOfDay(AdminOverviewService.JAPAN).toInstant());
    }

    private void review(long author, Instant at) {
        String externalId = unique("overview");
        String category = jdbc.queryForObject("SELECT id FROM place_categories ORDER BY id LIMIT 1", String.class);
        jdbc.update("INSERT INTO places (google_place_id, name_vi, category_id, source, status, created_by) "
                + "VALUES (?, 'Admin coverage fixture', ?, 'TEAM', 'ACTIVE', ?)", externalId, category, author);
        Long place = jdbc.queryForObject("SELECT id FROM places WHERE google_place_id = ?", Long.class, externalId);
        jdbc.update("INSERT INTO reviews (author_id, place_id, rating, content, status, created_at, updated_at) "
                + "VALUES (?, ?, 5, 'Coverage fixture', 'PUBLISHED', ?, ?)", author, place, Timestamp.from(at), Timestamp.from(at));
    }

    private void report(Instant at, String state, String decision) {
        jdbc.update("INSERT INTO report_cases (target_type, target_id, handler_role, status, decision, handled_at) "
                        + "VALUES ('REVIEW', ?, 'MODERATOR', ?, ?, ?)",
                Math.abs(UUID.randomUUID().getMostSignificantBits() / 2), state, decision, Timestamp.from(at));
    }

    private long addKeyword(MockHttpSession admin, String keyword) throws Exception {
        return response(csrf(post("/api/admin/keywords"), admin).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("keyword", keyword)))).path("data").path("id").asLong();
    }

    private JsonNode response(MockHttpServletRequestBuilder request) throws Exception {
        return json.readTree(mvc.perform(request).andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsString(StandardCharsets.UTF_8));
    }

    private long user(String role) {
        String email = unique("coverage") + "@test.local";
        jdbc.update("INSERT INTO users (email, password_hash, email_verified_at, display_name, role, status, "
                        + "is_adult_confirmed, created_at) VALUES (?, ?, ?, 'Coverage user', ?, 'ACTIVE', TRUE, ?)",
                email, HASH, Timestamp.from(NOW.minusSeconds(86400)), role, Timestamp.from(NOW.minusSeconds(30L * 86400)));
        return jdbc.queryForObject("SELECT id FROM users WHERE email = ?", Long.class, email);
    }

    private MockHttpSession signIn(long id) throws Exception {
        String email = jdbc.queryForObject("SELECT email FROM users WHERE id = ?", String.class, id);
        return (MockHttpSession) mvc.perform(post("/api/auth/login").cookie(new Cookie("XSRF-TOKEN", CSRF))
                        .header("X-XSRF-TOKEN", CSRF).contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("email", email, "password", PASSWORD))))
                .andExpect(status().isOk()).andReturn().getRequest().getSession(false);
    }

    private static MockHttpServletRequestBuilder csrf(MockHttpServletRequestBuilder request, MockHttpSession session) {
        return request.session(session).cookie(new Cookie("XSRF-TOKEN", CSRF)).header("X-XSRF-TOKEN", CSRF);
    }

    private long count(String table) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Long.class);
    }

    private static String unique(String prefix) {
        return prefix + UUID.randomUUID().toString().replace("-", "");
    }
}
