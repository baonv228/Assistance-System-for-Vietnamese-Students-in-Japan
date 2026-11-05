package com.sep490.vsa.feature.admin;

import com.jayway.jsonpath.JsonPath;
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
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Overview, account details, the safety-limit cards and keyword editing of the Admin, against MySQL
 * ({@code asvsj_test}). Every test runs in a transaction that is rolled back at the end.
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:mysql://localhost:3306/asvsj_test?createDatabaseIfNotExist=true&useUnicode=true&characterEncoding=utf8&connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true",
        "app.media.local-dir=target/test-uploads",
        "app.moderation.retry-initial-delay-ms=3600000",
        "app.places.osm.enabled=false"
})
@AutoConfigureMockMvc
@ActiveProfiles("local")
@Transactional
class AdminInsightFlowTest {

    private static final String CSRF = "test-csrf-token";
    private static final String PASSWORD = "matkhau123";
    private static final String HASH = new BCryptPasswordEncoder().encode(PASSWORD);

    @Autowired
    private MockMvc mvc;
    @Autowired
    private JdbcTemplate jdbc;

    // ---------------------------------------------------------------- overview

    @Test
    void overviewShowsSevenDaysByDefaultAndIsReadOnly() throws Exception {
        MockHttpSession admin = signIn(user("ADMIN", "ACTIVE", 30));
        long logsBefore = count("activity_logs");

        String body = mvc.perform(get("/api/admin/dashboard/overview").session(admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.period.preset").value("last7days"))
                .andExpect(jsonPath("$.data.reviewTrend.days.length()").value(7))
                .andExpect(jsonPath("$.data.quotas.length()").value(6))
                .andExpect(jsonPath("$.data.configuration.length()").value(2))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

        Number total = JsonPath.read(body, "$.data.accounts.totalAccounts");
        assertThat(total.longValue()).isEqualTo(count("users WHERE status <> 'DELETED'"));
        Number roles = JsonPath.read(body, "$.data.accounts.totalUsers");
        Number mods = JsonPath.read(body, "$.data.accounts.totalModerators");
        Number academics = JsonPath.read(body, "$.data.accounts.totalAcademicManagers");
        Number admins = JsonPath.read(body, "$.data.accounts.totalAdmins");
        assertThat(roles.longValue() + mods.longValue() + academics.longValue() + admins.longValue())
                .isEqualTo(total.longValue());
        assertThat(count("activity_logs")).isEqualTo(logsBefore);       // looking at the page writes nothing
    }

    @Test
    void overviewPeriodsAreChecked() throws Exception {
        MockHttpSession admin = signIn(user("ADMIN", "ACTIVE", 30));
        String today = LocalDate.now(AdminOverviewService.JAPAN).toString();
        String tomorrow = LocalDate.now(AdminOverviewService.JAPAN).plusDays(1).toString();

        overview(admin, "today", null, null).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.reviewTrend.days.length()").value(1));
        overview(admin, "last30days", null, null).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.reviewTrend.days.length()").value(30));
        overview(admin, "custom", today, today).andExpect(status().isOk())       // from = to is one day
                .andExpect(jsonPath("$.data.reviewTrend.days.length()").value(1));
        for (String[] bad : new String[][] {
                {"custom", today, "2000-01-01"},                // start after end
                {"custom", today, tomorrow},                    // a day in the future
                {"custom", null, null},                         // missing
                {"custom", "2026/10/01", today},                // wrong format
                {"custom", "2026-02-30", today},                // no such day
                {"custom", "1990-01-01", today},                // longer than a year
                {"weekly", null, null}}) {
            overview(admin, bad[0], bad[1], bad[2]).andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.message").value(AdminOverviewService.INVALID_RANGE));
        }
    }

    @Test
    void periodAndQuotaRules() {
        LocalDate today = LocalDate.of(2026, 11, 1);
        assertThat(AdminOverviewService.resolvePeriod("last7days", null, null, today).from()).isEqualTo("2026-10-26");
        assertThat(AdminOverviewService.resolvePeriod("last30days", null, null, today).from()).isEqualTo("2026-10-03");
        assertThatThrownBy(() -> AdminOverviewService.resolvePeriod("custom", "2026-11-02", "2026-11-03", today))
                .hasMessage(AdminOverviewService.INVALID_RANGE);

        assertThat(AdminOverviewService.meter("k", "l", 7999, 10000, "u", "w").alert()).isFalse();
        assertThat(AdminOverviewService.meter("k", "l", 8000, 10000, "u", "w").alert()).isTrue();
        AdminOverviewDtos.QuotaMeter over = AdminOverviewService.meter("k", "l", 10500, 10000, "u", "w");
        assertThat(over.alert()).isTrue();
        assertThat(over.percent()).isEqualTo(100.0);
    }

    // ---------------------------------------------------------------- accounts

    @Test
    void accountListCarriesTagsAgeAndMuteAndFiltersByTag() throws Exception {
        MockHttpSession admin = signIn(user("ADMIN", "ACTIVE", 30));
        long fresh = user("USER", "ACTIVE", 2);
        long old = user("MODERATOR", "ACTIVE", 20);
        long tagId = tag("Mentor-" + UUID.randomUUID().toString().substring(0, 6));
        jdbc.update("INSERT INTO user_tags (user_id, tag_type_id, granted_by) VALUES (?, ?, ?)", fresh, tagId, old);
        jdbc.update("UPDATE users SET report_muted_until = ?, false_report_count = 6 WHERE id = ?",
                Timestamp.from(Instant.now().plus(Duration.ofDays(3))), fresh);
        jdbc.update("UPDATE users SET report_muted_until = ? WHERE id = ?",
                Timestamp.from(Instant.now().minus(Duration.ofDays(3))), old);

        String body = mvc.perform(get("/api/admin/users").param("tagId", String.valueOf(tagId)).session(admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items.length()").value(1))
                .andExpect(jsonPath("$.data.items[0].id").value(fresh))
                .andExpect(jsonPath("$.data.items[0].newAccount").value(true))
                .andExpect(jsonPath("$.data.items[0].tags[0].id").value(tagId))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat((Object) JsonPath.read(body, "$.data.items[0].reportMutedUntil")).isNotNull();

        mvc.perform(get("/api/admin/users").param("keyword", "insight-" + old).session(admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].newAccount").value(false))
                .andExpect(jsonPath("$.data.items[0].accountAgeDays").value(20))
                .andExpect(jsonPath("$.data.items[0].reportMutedUntil").doesNotExist());   // a past mute is not shown
    }

    @Test
    void accountDetailHasThreeGroupsAndRecentLogs() throws Exception {
        long adminId = user("ADMIN", "ACTIVE", 30);
        MockHttpSession admin = signIn(adminId);
        long target = user("USER", "ACTIVE", 10);
        jdbc.update("UPDATE users SET points = 120 WHERE id = ?", target);
        for (int i = 0; i < 12; i++) {
            jdbc.update("INSERT INTO activity_logs (actor_id, action, target_type, target_id, description) "
                    + "VALUES (?, 'USER_STATUS_CHANGE', 'USER', ?, ?)", adminId, target, "log " + i);
        }
        jdbc.update("INSERT INTO activity_logs (actor_id, action, target_type, target_id, description) "
                + "VALUES (?, 'USER_STATUS_CHANGE', 'USER', ?, 'other person')", adminId, adminId);

        mvc.perform(get("/api/admin/accounts/" + target).session(admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.basic.email").value("insight-" + target + "@test.local"))
                .andExpect(jsonPath("$.data.basic.accountAgeDays").value(10))
                .andExpect(jsonPath("$.data.access.points").value(120))
                .andExpect(jsonPath("$.data.access.rank.name").value("Người chỉ đường"))
                .andExpect(jsonPath("$.data.safety.recentActivity.length()").value(10))   // the 10 newest only
                .andExpect(jsonPath("$.data.safety.recentActivity[0].description").value("log 11"))
                .andExpect(jsonPath("$.data.moderatorCapacity.max").value(3));
        mvc.perform(get("/api/admin/accounts/999999999").session(admin)).andExpect(status().isNotFound());
        mvc.perform(get("/api/admin/accounts/options").session(admin)).andExpect(status().isOk());
    }

    @Test
    void accountEndpointsAreForAdminsOnly() throws Exception {
        long target = user("USER", "ACTIVE", 10);
        MockHttpSession member = signIn(user("USER", "ACTIVE", 10));
        for (String path : new String[] {"/api/admin/accounts/" + target, "/api/admin/accounts/options",
                "/api/admin/dashboard/overview", "/api/admin/safety-config", "/api/admin/users/" + target + "/status-check"}) {
            mvc.perform(get(path).session(member)).andExpect(status().isForbidden());
            mvc.perform(get(path)).andExpect(status().isUnauthorized());
        }
    }

    // ---------------------------------------------------------------- safety limits

    @Test
    void safetyValuesAreSavedTogetherWithOneLogEntry() throws Exception {
        long adminId = user("ADMIN", "ACTIVE", 30);
        MockHttpSession admin = signIn(adminId);
        jdbc.update("UPDATE system_settings SET setting_value = '3' WHERE setting_key = 'posts_per_day'");
        jdbc.update("UPDATE system_settings SET setting_value = '2' WHERE setting_key = 'pins_per_day'");
        jdbc.update("UPDATE system_settings SET setting_value = '5' WHERE setting_key = 'closed_report_threshold'");
        long logs = count("activity_logs WHERE actor_id = " + adminId);

        saveSafety(admin, "{\"posts_per_day\":\"5\",\"pins_per_day\":2,\"closed_report_threshold\":\"007\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.changed").value(true));
        assertThat(value("posts_per_day")).isEqualTo("5");
        assertThat(value("pins_per_day")).isEqualTo("2");
        assertThat(value("closed_report_threshold")).isEqualTo("7");
        assertThat(count("activity_logs WHERE actor_id = " + adminId)).isEqualTo(logs + 1);   // one entry per save
        String old = jdbc.queryForObject("SELECT old_value FROM activity_logs WHERE actor_id = ? ORDER BY id DESC LIMIT 1",
                String.class, adminId);
        assertThat(old).contains("posts_per_day").contains("closed_report_threshold").doesNotContain("pins_per_day");

        // Nothing differs from what is stored: nothing is written
        saveSafety(admin, "{\"posts_per_day\":5}").andExpect(status().isOk())
                .andExpect(jsonPath("$.data.changed").value(false));
        saveSafety(admin, "{}").andExpect(status().isOk()).andExpect(jsonPath("$.data.changed").value(false));
        assertThat(count("activity_logs WHERE actor_id = " + adminId)).isEqualTo(logs + 1);
    }

    @Test
    void everyInvalidValueIsReportedAndNothingIsSaved() throws Exception {
        long adminId = user("ADMIN", "ACTIVE", 30);
        MockHttpSession admin = signIn(adminId);
        jdbc.update("UPDATE system_settings SET setting_value = '3' WHERE setting_key = 'posts_per_day'");
        jdbc.update("UPDATE system_settings SET setting_value = '2' WHERE setting_key = 'pins_per_day'");
        long logs = count("activity_logs WHERE actor_id = " + adminId);

        // a good value next to bad ones is not saved either; both bad fields are named at once
        saveSafety(admin, "{\"posts_per_day\":\"\",\"pins_per_day\":-3,\"videos_per_day\":4}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.data.posts_per_day").exists())
                .andExpect(jsonPath("$.data.pins_per_day").exists())
                .andExpect(jsonPath("$.data.videos_per_day").doesNotExist());
        for (String bad : new String[] {"\"abc\"", "2.5", "0", "51", "null", "\" \""}) {
            saveSafety(admin, "{\"posts_per_day\":" + bad + "}").andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.data.posts_per_day").exists());
        }
        assertThat(value("posts_per_day")).isEqualTo("3");
        assertThat(value("pins_per_day")).isEqualTo("2");
        assertThat(count("activity_logs WHERE actor_id = " + adminId)).isEqualTo(logs);

        // an unknown key, a setting of another card and one the system does not read yet are refused as a whole
        saveSafety(admin, "{\"nope\":1}").andExpect(status().isBadRequest());
        saveSafety(admin, "{\"lock_minutes\":10}").andExpect(status().isBadRequest());
        saveSafety(admin, "{\"mute_days\":10}").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(AdminConfigService.NOT_APPLIED));
    }

    @Test
    void safetyCardsListTheTenParameters() throws Exception {
        MockHttpSession admin = signIn(user("ADMIN", "ACTIVE", 30));
        mvc.perform(get("/api/admin/safety-config").session(admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[0].params.length()").value(4))
                .andExpect(jsonPath("$.data[1].params.length()").value(6))
                .andExpect(jsonPath("$.data[1].params[?(@.key=='mute_days')].editable").value(false))
                .andExpect(jsonPath("$.data[1].params[?(@.key=='proof_keep_days')].editable").value(true));
    }

    // ---------------------------------------------------------------- banned keywords

    @Test
    void aKeywordCanBeEditedAndTheEditIsChecked() throws Exception {
        long adminId = user("ADMIN", "ACTIVE", 30);
        MockHttpSession admin = signIn(adminId);
        String a = "tukhoaa" + UUID.randomUUID().toString().substring(0, 6);
        String b = "tukhoab" + UUID.randomUUID().toString().substring(0, 6);
        long idA = keyword(admin, a);
        keyword(admin, b);

        updateKeyword(admin, idA, a + " moi").andExpect(status().isOk())
                .andExpect(jsonPath("$.data.keyword").value(a + " moi"));
        String log = jdbc.queryForObject("SELECT description FROM activity_logs WHERE actor_id = ? AND action = 'KEYWORD_UPDATE'",
                String.class, adminId);
        assertThat(log).contains(a).contains(a + " moi");

        updateKeyword(admin, idA, a + " moi").andExpect(status().isOk())                 // same text: nothing to save
                .andExpect(jsonPath("$.data").doesNotExist());
        assertThat(count("activity_logs WHERE actor_id = " + adminId + " AND action = 'KEYWORD_UPDATE'")).isEqualTo(1);
        updateKeyword(admin, idA, b).andExpect(status().isBadRequest());                 // equals another keyword
        updateKeyword(admin, idA, "  ").andExpect(status().isBadRequest());
        updateKeyword(admin, idA, "!!").andExpect(status().isBadRequest());
        updateKeyword(admin, 999999999L, "khong ton tai").andExpect(status().isNotFound());
        assertThat(jdbc.queryForObject("SELECT keyword FROM banned_keywords WHERE id = ?", String.class, idA))
                .isEqualTo(a + " moi");
        // Writing the same word with other capitals is the same keyword, not a duplicate of itself
        updateKeyword(admin, idA, (a + " MOI").toUpperCase()).andExpect(status().isOk());
    }

    // ---------------------------------------------------------------- helpers

    private long count(String fromAndWhere) {
        Long n = jdbc.queryForObject("SELECT COUNT(*) FROM " + fromAndWhere, Long.class);
        return n == null ? 0 : n;
    }

    private String value(String key) {
        return jdbc.queryForObject("SELECT setting_value FROM system_settings WHERE setting_key = ?", String.class, key);
    }

    private long tag(String name) {
        jdbc.update("INSERT INTO tag_types (name, description, color) VALUES (?, 'test', '#2E7D5B')", name);
        return jdbc.queryForObject("SELECT id FROM tag_types WHERE name = ?", Long.class, name);
    }

    private long keyword(MockHttpSession admin, String text) throws Exception {
        mvc.perform(withCsrf(post("/api/admin/keywords"), admin).contentType(MediaType.APPLICATION_JSON)
                .content("{\"keyword\":\"" + text + "\"}")).andExpect(status().isOk());
        return jdbc.queryForObject("SELECT id FROM banned_keywords WHERE keyword = ?", Long.class, text);
    }

    private ResultActions updateKeyword(MockHttpSession admin, long id, String text) throws Exception {
        return mvc.perform(withCsrf(put("/api/admin/keywords/" + id), admin).contentType(MediaType.APPLICATION_JSON)
                .content("{\"keyword\":\"" + text + "\"}"));
    }

    private ResultActions saveSafety(MockHttpSession admin, String json) throws Exception {
        return mvc.perform(withCsrf(put("/api/admin/safety-config"), admin).contentType(MediaType.APPLICATION_JSON)
                .content(json));
    }

    private ResultActions overview(MockHttpSession admin, String period, String from, String to) throws Exception {
        MockHttpServletRequestBuilder request = get("/api/admin/dashboard/overview").param("period", period).session(admin);
        if (from != null) request.param("from", from);
        if (to != null) request.param("to", to);
        return mvc.perform(request);
    }

    private long user(String role, String status, int ageDays) {
        String tag = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        jdbc.update("""
                INSERT INTO users (email, password_hash, email_verified_at, display_name, role, status, is_adult_confirmed,
                                   created_at)
                VALUES (?, ?, ?, ?, ?, ?, TRUE, ?)""",
                "insight-" + tag + "@test.local", HASH, Timestamp.from(Instant.now()), "Insight " + tag, role, status,
                Timestamp.from(Instant.now().minus(Duration.ofDays(ageDays)).minus(Duration.ofHours(1))));
        long id = jdbc.queryForObject("SELECT id FROM users WHERE email = ?", Long.class,
                "insight-" + tag + "@test.local");
        // the tests look an account up by "insight-<id>": give it that email
        jdbc.update("UPDATE users SET email = ? WHERE id = ?", "insight-" + id + "@test.local", id);
        return id;
    }

    private MockHttpSession signIn(long id) throws Exception {
        String email = jdbc.queryForObject("SELECT email FROM users WHERE id = ?", String.class, id);
        return (MockHttpSession) mvc.perform(post("/api/auth/login").cookie(new Cookie("XSRF-TOKEN", CSRF))
                        .header("X-XSRF-TOKEN", CSRF).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"" + PASSWORD + "\"}"))
                .andExpect(status().isOk()).andReturn().getRequest().getSession(false);
    }

    private static MockHttpServletRequestBuilder withCsrf(MockHttpServletRequestBuilder request, MockHttpSession session) {
        return request.session(session).cookie(new Cookie("XSRF-TOKEN", CSRF)).header("X-XSRF-TOKEN", CSRF);
    }
}
