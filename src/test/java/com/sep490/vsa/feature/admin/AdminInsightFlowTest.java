package com.sep490.vsa.feature.admin;

import com.jayway.jsonpath.JsonPath;
import com.sep490.vsa.feature.moderation.GeminiMediaReader;
import com.sep490.vsa.feature.place.OsmPlaces;
import jakarta.servlet.http.Cookie;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
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
    @SpyBean
    private JdbcTemplate jdbc;
    @Autowired
    private DataSource dataSource;

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
    void overviewQueryFailureReturnsNoPartialSummaryAndDoesNotWrite() throws Exception {
        MockHttpSession admin = signIn(user("ADMIN", "ACTIVE", 30));
        long logsBefore = count("activity_logs");
        String settingBefore = value("posts_per_day");
        // Fail a real query after the trend and account reads have already succeeded.
        doThrow(new DataAccessException("injected overview query failure") { })
                .when(jdbc).queryForObject(eq("SELECT COUNT(*) FROM moderation_scans WHERE engine = 'OPENAI' "
                        + "AND created_at >= ? AND created_at < ?"), eq(Long.class),
                        any(Timestamp.class), any(Timestamp.class));

        assertThatThrownBy(() -> mvc.perform(get("/api/admin/dashboard/overview").session(admin)))
                .hasRootCauseInstanceOf(DataAccessException.class);
        assertThat(count("activity_logs")).isEqualTo(logsBefore);
        assertThat(value("posts_per_day")).isEqualTo(settingBefore);
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
    void overviewUsesTokyoDayBoundariesAndReconcilesReviewAndReportRows() throws Exception {
        MockHttpSession admin = signIn(user("ADMIN", "ACTIVE", 30));
        long author = user("USER", "ACTIVE", 30);
        LocalDate today = LocalDate.now(AdminOverviewService.JAPAN);
        String before = mvc.perform(get("/api/admin/dashboard/overview").param("period", "custom")
                        .param("from", today.toString()).param("to", today.toString()).session(admin))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        long reviewsBefore = ((Number) JsonPath.read(before, "$.data.reviewTrend.totalReviews")).longValue();
        long dismissedBefore = ((Number) JsonPath.read(before, "$.data.reviewTrend.dismissedReports")).longValue();
        long logsBefore = count("activity_logs");
        String category = jdbc.queryForObject("SELECT id FROM place_categories ORDER BY id LIMIT 1", String.class);
        String googleId = "admin-overview-" + UUID.randomUUID();
        jdbc.update("INSERT INTO places (google_place_id, name_vi, category_id, source, status, created_by) "
                + "VALUES (?, 'Overview fixture', ?, 'TEAM', 'ACTIVE', ?)", googleId, category, author);
        long placeId = jdbc.queryForObject("SELECT id FROM places WHERE google_place_id = ?", Long.class, googleId);
        String nextGoogleId = googleId + "-next";
        jdbc.update("INSERT INTO places (google_place_id, name_vi, category_id, source, status, created_by) "
                + "VALUES (?, 'Next-day overview fixture', ?, 'TEAM', 'ACTIVE', ?)", nextGoogleId, category, author);
        long nextPlaceId = jdbc.queryForObject("SELECT id FROM places WHERE google_place_id = ?", Long.class, nextGoogleId);

        Timestamp insideToday = Timestamp.from(today.atStartOfDay(AdminOverviewService.JAPAN).toInstant().plusSeconds(3600));
        Timestamp nextDay = Timestamp.from(today.plusDays(1).atStartOfDay(AdminOverviewService.JAPAN).toInstant());
        jdbc.update("INSERT INTO reviews (author_id, place_id, rating, content, status, created_at, updated_at) "
                + "VALUES (?, ?, 5, 'Tokyo boundary fixture', 'PUBLISHED', ?, ?)", author, placeId, insideToday, insideToday);
        jdbc.update("INSERT INTO reviews (author_id, place_id, rating, content, status, created_at, updated_at) "
                + "VALUES (?, ?, 4, 'Next Tokyo day fixture', 'PUBLISHED', ?, ?)", author, nextPlaceId,
                nextDay, nextDay);

        long reviewId = jdbc.queryForObject("SELECT id FROM reviews WHERE author_id = ? AND place_id = ?",
                Long.class, author, placeId);
        jdbc.update("INSERT INTO report_cases (target_type, target_id, handler_role, status, decision, handled_at) "
                        + "VALUES ('REVIEW', ?, 'MODERATOR', 'RESOLVED', 'KEEP', ?)",
                reviewId, insideToday);

        String body = mvc.perform(get("/api/admin/dashboard/overview").param("period", "custom")
                        .param("from", today.toString()).param("to", today.toString()).session(admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.reviewTrend.days.length()").value(1))
                .andExpect(jsonPath("$.data.reviewTrend.days[0].date").value(today.toString()))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(((Number) JsonPath.read(body, "$.data.reviewTrend.totalReviews")).longValue())
                .isEqualTo(reviewsBefore + 1);
        assertThat(((Number) JsonPath.read(body, "$.data.reviewTrend.dismissedReports")).longValue())
                .isEqualTo(dismissedBefore + 1);
        assertThat(count("activity_logs")).isEqualTo(logsBefore);
    }

    @Test
    void overviewQuotaMetersReconcileUsageAndExcludeDeletedMedia() throws Exception {
        MockHttpSession admin = signIn(user("ADMIN", "ACTIVE", 30));
        String before = mvc.perform(get("/api/admin/dashboard/overview").session(admin))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        long logsBefore = count("activity_logs");
        List<Map<String, Object>> beforeMeters = JsonPath.read(before, "$.data.quotas");
        Map<String, Object> scansBefore = beforeMeters.stream().filter(m -> "OPENAI_MODERATION".equals(m.get("key")))
                .findFirst().orElseThrow();
        Map<String, Object> storageBefore = beforeMeters.stream().filter(m -> "MEDIA_STORAGE".equals(m.get("key")))
                .findFirst().orElseThrow();
        long owner = user("USER", "ACTIVE", 30);
        String storageKey = "admin-quota-" + UUID.randomUUID() + ".jpg";
        jdbc.update("INSERT INTO media (owner_id, media_type, storage_key, mime_type, size_bytes, scan_status) "
                + "VALUES (?, 'IMAGE', ?, 'image/jpeg', 1073741824, 'PASSED')", owner, storageKey);
        long media = jdbc.queryForObject("SELECT id FROM media WHERE storage_key = ?", Long.class, storageKey);
        jdbc.update("INSERT INTO media (owner_id, media_type, storage_key, mime_type, size_bytes, scan_status, deleted_at) "
                + "VALUES (?, 'IMAGE', ?, 'image/jpeg', 9876543210, 'PASSED', CURRENT_TIMESTAMP)", owner,
                "admin-quota-deleted-" + UUID.randomUUID() + ".jpg");

        LocalDate today = LocalDate.now(AdminOverviewService.JAPAN);
        Timestamp todayStart = Timestamp.from(today.atStartOfDay(AdminOverviewService.JAPAN).toInstant().plusSeconds(7200));
        Timestamp yesterday = Timestamp.from(today.minusDays(1).atStartOfDay(AdminOverviewService.JAPAN).toInstant().plusSeconds(7200));
        for (int i = 0; i < 2; i++) {
            jdbc.update("INSERT INTO moderation_scans (target_type, target_id, engine, result, created_at) "
                    + "VALUES ('MEDIA', ?, 'OPENAI', 'PASS', ?)", media, todayStart);
        }
        jdbc.update("INSERT INTO moderation_scans (target_type, target_id, engine, result, created_at) "
                + "VALUES ('MEDIA', ?, 'OPENAI', 'PASS', ?)", media, yesterday);
        jdbc.update("INSERT INTO api_usage (service, usage_date, amount) VALUES (?, ?, ?) "
                        + "ON DUPLICATE KEY UPDATE amount = VALUES(amount)",
                GeminiMediaReader.GENERATE_USAGE,
                Date.valueOf(today), 800.0);
        jdbc.update("INSERT INTO api_usage (service, usage_date, amount) VALUES (?, ?, ?) "
                        + "ON DUPLICATE KEY UPDATE amount = VALUES(amount)",
                OsmPlaces.SEARCH_USAGE, Date.valueOf(today), 79.0);

        String body = mvc.perform(get("/api/admin/dashboard/overview").session(admin))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        List<Map<String, Object>> meters = JsonPath.read(body, "$.data.quotas");
        Map<String, Object> scans = meters.stream().filter(m -> "OPENAI_MODERATION".equals(m.get("key")))
                .findFirst().orElseThrow();
        Map<String, Object> gemini = meters.stream().filter(m -> "GEMINI_GENERATE".equals(m.get("key")))
                .findFirst().orElseThrow();
        Map<String, Object> photon = meters.stream().filter(m -> "OSM_PHOTON_SEARCH".equals(m.get("key")))
                .findFirst().orElseThrow();
        Map<String, Object> storage = meters.stream().filter(m -> "MEDIA_STORAGE".equals(m.get("key")))
                .findFirst().orElseThrow();
        assertThat(((Number) scans.get("used")).longValue())
                .isEqualTo(((Number) scansBefore.get("used")).longValue() + 2);
        assertThat(((Number) gemini.get("used")).doubleValue()).isEqualTo(800.0);
        assertThat((Boolean) gemini.get("alert")).isTrue();
        assertThat(((Number) photon.get("used")).doubleValue()).isEqualTo(79.0);
        assertThat((Boolean) photon.get("alert")).isFalse();
        assertThat(((Number) storage.get("used")).doubleValue())
                .isEqualTo(((Number) storageBefore.get("used")).doubleValue() + 1.0);
        assertThat(count("activity_logs")).isEqualTo(logsBefore);
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
        long revokedTagId = tag("Revoked-" + UUID.randomUUID().toString().substring(0, 6));
        long inactiveTagId = tag("Inactive-" + UUID.randomUUID().toString().substring(0, 6));
        jdbc.update("INSERT INTO user_tags (user_id, tag_type_id, granted_by) VALUES (?, ?, ?)", fresh, tagId, old);
        jdbc.update("INSERT INTO user_tags (user_id, tag_type_id, granted_by, revoked_at) VALUES (?, ?, ?, ?)",
                fresh, revokedTagId, old, Timestamp.from(Instant.now()));
        jdbc.update("INSERT INTO user_tags (user_id, tag_type_id, granted_by) VALUES (?, ?, ?)", fresh, inactiveTagId, old);
        jdbc.update("UPDATE tag_types SET is_active = FALSE WHERE id = ?", inactiveTagId);
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
        mvc.perform(get("/api/admin/users").param("tagId", String.valueOf(revokedTagId)).session(admin))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(0));
        mvc.perform(get("/api/admin/users").param("tagId", String.valueOf(inactiveTagId)).session(admin))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(0));
        String options = mvc.perform(get("/api/admin/accounts/options").session(admin))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(JsonPath.<List<Number>>read(options, "$.data.tags[*].id").stream().map(Number::longValue).toList())
                .contains(tagId).doesNotContain(inactiveTagId);

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
        jdbc.update("INSERT INTO blogs (author_id, title, content, status) VALUES (?, ?, ?, 'REMOVED')",
                target, "Removed Admin detail " + target, "Test content");
        String category = jdbc.queryForObject("SELECT id FROM place_categories ORDER BY id LIMIT 1", String.class);
        String placeKey = "admin-detail-" + UUID.randomUUID();
        jdbc.update("INSERT INTO places (google_place_id, name_vi, category_id, source, status, created_by) "
                + "VALUES (?, 'Removed review fixture', ?, 'TEAM', 'ACTIVE', ?)", placeKey, category, adminId);
        long place = jdbc.queryForObject("SELECT id FROM places WHERE google_place_id = ?", Long.class, placeKey);
        jdbc.update("INSERT INTO reviews (author_id, place_id, rating, content, status, removed_reason) "
                + "VALUES (?, ?, 5, 'Removed review', 'REMOVED', 'test fixture')", target, place);
        for (int i = 0; i < 12; i++) {
            jdbc.update("INSERT INTO activity_logs (actor_id, action, target_type, target_id, description) "
                    + "VALUES (?, 'USER_STATUS_CHANGE', 'USER', ?, ?)", adminId, target, "log " + i);
        }
        jdbc.update("INSERT INTO activity_logs (actor_id, action, target_type, target_id, description) "
                + "VALUES (?, 'USER_STATUS_CHANGE', 'USER', ?, 'other person')", adminId, adminId);

        String detail = mvc.perform(get("/api/admin/accounts/" + target).session(admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.basic.email").value("insight-" + target + "@test.local"))
                .andExpect(jsonPath("$.data.basic.accountAgeDays").value(10))
                .andExpect(jsonPath("$.data.access.points").value(120))
                .andExpect(jsonPath("$.data.access.rank.name").value("Người chỉ đường"))
                .andExpect(jsonPath("$.data.safety.removedContentCount").value(2))
                .andExpect(jsonPath("$.data.safety.recentActivity.length()").value(10))   // the 10 newest only
                .andExpect(jsonPath("$.data.safety.recentActivity[0].description").value("log 11"))
                .andExpect(jsonPath("$.data.moderatorCapacity.max").value(3))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(detail).doesNotContain("passwordHash", "password_hash");
        mvc.perform(get("/api/admin/accounts/999999999").session(admin)).andExpect(status().isNotFound());
        String options = mvc.perform(get("/api/admin/accounts/options").session(admin))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(((Number) JsonPath.read(options, "$.data.moderatorCapacity.active")).longValue())
                .isEqualTo(jdbc.queryForObject("SELECT COUNT(*) FROM users WHERE role='MODERATOR' AND status='ACTIVE'", Long.class));
        assertThat(((Number) JsonPath.read(options, "$.data.moderatorCapacity.max")).intValue()).isEqualTo(3);
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

    @Test
    void testDatabaseUsesCurrentFlywaySchemaForAdminTables() {
        String version = jdbc.queryForObject("SELECT version FROM flyway_schema_history "
                + "WHERE success = TRUE ORDER BY installed_rank DESC LIMIT 1", String.class);
        assertThat(version).isEqualTo("24");

        for (String table : new String[] {"users", "user_tags", "tag_types", "ranks", "reviews", "blogs",
                "report_cases", "moderation_scans", "media", "api_usage", "system_settings",
                "activity_logs", "banned_keywords"}) {
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.tables "
                    + "WHERE table_schema = DATABASE() AND table_name = ?", Long.class, table))
                    .as(table + " exists in the migrated test schema").isEqualTo(1L);
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.columns "
                + "WHERE table_schema = DATABASE() AND table_name = 'system_settings' "
                + "AND column_name IN ('setting_key','setting_value','updated_by','updated_at')", Long.class))
                .isEqualTo(4L);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.columns "
                + "WHERE table_schema = DATABASE() AND table_name = 'activity_logs' "
                + "AND column_name IN ('actor_id','action','target_type','target_id','old_value','new_value','description','created_at')",
                Long.class)).isEqualTo(8L);
    }

    @Test
    void flywayBuildsTheLatestAdminSchemaFromAnEmptyDatabase() throws Exception {
        String schema = "asvsj_admin_mig_" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        try {
            Flyway fresh = Flyway.configure().dataSource(dataSource).schemas(schema).defaultSchema(schema)
                    .locations("classpath:db/migration").outOfOrder(true).load();
            fresh.migrate();
            assertThat(fresh.info().current().getVersion().getVersion()).isEqualTo("24");
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM `" + schema
                    + "`.flyway_schema_history WHERE success = TRUE AND version IS NOT NULL",
                    Long.class)).isEqualTo(24L);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.tables "
                    + "WHERE table_schema = ? AND table_name IN ('users','system_settings','activity_logs',"
                    + "'banned_keywords','reviews','report_cases','api_usage','media')", Long.class, schema))
                    .isEqualTo(8L);
            assertThat(jdbc.queryForObject("SELECT setting_value FROM `" + schema
                    + "`.system_settings WHERE setting_key='mute_days'", String.class)).isEqualTo("7");
        } finally {
            jdbc.execute("DROP DATABASE IF EXISTS `" + schema + "`");
        }
    }

    @Test
    void databaseConstraintsRejectInvalidAdminRelatedRows() throws Exception {
        long author = user("USER", "ACTIVE", 20);
        String category = jdbc.queryForObject("SELECT id FROM place_categories ORDER BY id LIMIT 1", String.class);
        String googleId = "constraint-check-" + UUID.randomUUID();
        jdbc.update("INSERT INTO places (google_place_id, name_vi, category_id, source, status, created_by) "
                + "VALUES (?, 'Constraint fixture', ?, 'TEAM', 'ACTIVE', ?)", googleId, category, author);
        long placeId = jdbc.queryForObject("SELECT id FROM places WHERE google_place_id = ?", Long.class, googleId);
        long reviewCountBefore = jdbc.queryForObject("SELECT COUNT(*) FROM reviews WHERE author_id = ?", Long.class, author);

        assertThatThrownBy(() -> jdbc.update("INSERT INTO banned_keywords (keyword, created_by) VALUES (?, ?)",
                "foreign-key-check", Long.MAX_VALUE)).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbc.update("INSERT INTO reviews (author_id, place_id, rating, content, status) "
                + "VALUES (?, ?, 0, 'Invalid rating', 'PUBLISHED')", author, placeId))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbc.update("INSERT INTO users (email, display_name, role, status, is_adult_confirmed) "
                + "VALUES (?, 'Invalid', 'USER', 'NOT_A_STATUS', TRUE)",
                "invalid-check-" + UUID.randomUUID() + "@test.local"))
                .isInstanceOf(DataAccessException.class);

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM reviews WHERE author_id = ?", Long.class, author))
                .isEqualTo(reviewCountBefore);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM banned_keywords WHERE keyword='foreign-key-check'", Long.class))
                .isZero();
    }

    @Test
    void adminReadRoutesRejectEveryNonAdminRoleAndAnonymous() throws Exception {
        String[] paths = {
                "/api/admin/dashboard/stats", "/api/admin/dashboard/overview", "/api/admin/users",
                "/api/admin/accounts/options", "/api/admin/settings", "/api/admin/safety-config",
                "/api/admin/keywords", "/api/admin/logs"
        };
        for (String role : new String[] {"USER", "MODERATOR", "ACADEMIC_MANAGER"}) {
            MockHttpSession member = signIn(user(role, "ACTIVE", 10));
            for (String path : paths) {
                mvc.perform(get(path).session(member)).andExpect(status().isForbidden());
            }
        }
        for (String path : paths) {
            mvc.perform(get(path)).andExpect(status().isUnauthorized());
        }
    }

    @Test
    void adminMutationsRequireCsrfAndLeaveDatabaseUnchanged() throws Exception {
        long adminId = user("ADMIN", "ACTIVE", 30);
        long targetId = user("USER", "ACTIVE", 10);
        MockHttpSession admin = signIn(adminId);
        long logsBefore = count("activity_logs");
        String settingBefore = value("posts_per_day");
        long keywordId = jdbc.queryForObject("SELECT id FROM banned_keywords ORDER BY id LIMIT 1", Long.class);
        String keyword = "csrf" + UUID.randomUUID().toString().substring(0, 8);

        mvc.perform(put("/api/admin/settings/posts_per_day").session(admin)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"settingValue\":\"12\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(put("/api/admin/safety-config").session(admin)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"posts_per_day\":12}"))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/admin/keywords").session(admin)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"keyword\":\"" + keyword + "\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(put("/api/admin/keywords/" + keywordId).session(admin)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"keyword\":\"changed-keyword\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(delete("/api/admin/keywords/" + keywordId).session(admin))
                .andExpect(status().isForbidden());
        mvc.perform(put("/api/admin/users/" + targetId + "/role").session(admin)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"role\":\"MODERATOR\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(put("/api/admin/users/" + targetId + "/status").session(admin)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"LOCKED\"}"))
                .andExpect(status().isForbidden());

        assertThat(value("posts_per_day")).isEqualTo(settingBefore);
        assertThat(jdbc.queryForObject("SELECT role FROM users WHERE id = ?", String.class, targetId)).isEqualTo("USER");
        assertThat(count("banned_keywords WHERE keyword = '" + keyword + "'")).isZero();
        assertThat(count("activity_logs")).isEqualTo(logsBefore);
    }

    @Test
    void nonAdminRolesCannotWriteAdminEndpointsEvenWithCsrf() throws Exception {
        long adminId = user("ADMIN", "ACTIVE", 30);
        long targetId = user("USER", "ACTIVE", 10);
        MockHttpSession admin = signIn(adminId);
        long keywordId = keyword(admin, "protected-" + UUID.randomUUID().toString().substring(0, 8));
        MockHttpSession[] members = {
                signIn(user("USER", "ACTIVE", 10)),
                signIn(user("MODERATOR", "ACTIVE", 10)),
                signIn(user("ACADEMIC_MANAGER", "ACTIVE", 10))
        };
        String settingBefore = value("posts_per_day");
        String keywordBefore = jdbc.queryForObject("SELECT keyword FROM banned_keywords WHERE id = ?", String.class, keywordId);
        long logCountBefore = count("activity_logs");

        for (MockHttpSession member : members) {
            mvc.perform(withCsrf(put("/api/admin/settings/posts_per_day"), member)
                            .contentType(MediaType.APPLICATION_JSON).content("{\"settingValue\":\"12\"}"))
                    .andExpect(status().isForbidden());
            mvc.perform(withCsrf(put("/api/admin/safety-config"), member)
                            .contentType(MediaType.APPLICATION_JSON).content("{\"posts_per_day\":12}"))
                    .andExpect(status().isForbidden());
            mvc.perform(withCsrf(post("/api/admin/keywords"), member)
                            .contentType(MediaType.APPLICATION_JSON).content("{\"keyword\":\"forbidden-keyword\"}"))
                    .andExpect(status().isForbidden());
            mvc.perform(withCsrf(put("/api/admin/keywords/" + keywordId), member)
                            .contentType(MediaType.APPLICATION_JSON).content("{\"keyword\":\"changed-keyword\"}"))
                    .andExpect(status().isForbidden());
            mvc.perform(withCsrf(delete("/api/admin/keywords/" + keywordId), member))
                    .andExpect(status().isForbidden());
            mvc.perform(withCsrf(put("/api/admin/users/" + targetId + "/role"), member)
                            .contentType(MediaType.APPLICATION_JSON).content("{\"role\":\"MODERATOR\"}"))
                    .andExpect(status().isForbidden());
            mvc.perform(withCsrf(put("/api/admin/users/" + targetId + "/status"), member)
                            .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"LOCKED\"}"))
                    .andExpect(status().isForbidden());
        }

        assertThat(value("posts_per_day")).isEqualTo(settingBefore);
        assertThat(jdbc.queryForObject("SELECT role FROM users WHERE id = ?", String.class, targetId)).isEqualTo("USER");
        assertThat(jdbc.queryForObject("SELECT status FROM users WHERE id = ?", String.class, targetId)).isEqualTo("ACTIVE");
        assertThat(jdbc.queryForObject("SELECT keyword FROM banned_keywords WHERE id = ?", String.class, keywordId))
                .isEqualTo(keywordBefore);
        assertThat(count("activity_logs")).isEqualTo(logCountBefore);
    }

    @Test
    void statusCheckIsReadOnlyAndReportsTheCurrentStatus() throws Exception {
        long adminId = user("ADMIN", "ACTIVE", 30);
        long targetId = user("USER", "ACTIVE", 10);
        MockHttpSession admin = signIn(adminId);
        long logsBefore = count("activity_logs");

        mvc.perform(get("/api/admin/users/" + targetId + "/status-check")
                        .param("target", "LOCKED").session(admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").value("ACTIVE"));
        mvc.perform(get("/api/admin/users/" + targetId + "/status-check")
                        .param("target", "INVALID").session(admin))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/admin/users/999999999/status-check")
                        .param("target", "LOCKED").session(admin))
                .andExpect(status().isNotFound());

        assertThat(jdbc.queryForObject("SELECT status FROM users WHERE id = ?", String.class, targetId)).isEqualTo("ACTIVE");
        assertThat(count("activity_logs")).isEqualTo(logsBefore);
    }

    @Test
    void activityLogActionFilterIncludesSystemActorAndExactCount() throws Exception {
        long adminId = user("ADMIN", "ACTIVE", 30);
        MockHttpSession admin = signIn(adminId);
        String action = "QA_LOG_" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        jdbc.update("INSERT INTO activity_logs (action, target_type, description) VALUES (?, 'TEST', 'system row')", action);
        jdbc.update("INSERT INTO activity_logs (actor_id, action, target_type, description) "
                + "VALUES (?, ?, 'TEST', 'admin row')", adminId, action);
        long deletedActor = user("USER", "ACTIVE", 20);
        jdbc.update("INSERT INTO activity_logs (actor_id, action, target_type, description) "
                + "VALUES (?, ?, 'TEST', 'deleted actor row')", deletedActor, action);
        jdbc.update("DELETE FROM users WHERE id = ?", deletedActor);
        jdbc.update("INSERT INTO activity_logs (action, target_type, description) "
                + "VALUES ('QA_OTHER', 'TEST', 'unrelated row')");

        mvc.perform(get("/api/admin/logs").param("action", "  " + action + "  ").param("page", "1")
                        .param("size", "10").session(admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(3))
                .andExpect(jsonPath("$.data.items.length()").value(3));
        mvc.perform(get("/api/admin/logs").session(admin))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.page").value(1))
                .andExpect(jsonPath("$.data.size").value(20))
                .andExpect(jsonPath("$.data.items.length()").isNumber());
        mvc.perform(get("/api/admin/logs").param("page", "0").param("size", "0").session(admin))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.page").value(1))
                .andExpect(jsonPath("$.data.size").value(1));
        mvc.perform(get("/api/admin/logs").param("page", "99999999999").session(admin))
                .andExpect(status().isBadRequest());
        String body = mvc.perform(get("/api/admin/logs").param("action", action).session(admin))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(JsonPath.<List<String>>read(body, "$.data.items[*].description"))
                .containsExactlyInAnyOrder("system row", "admin row", "deleted actor row");
        assertThat(JsonPath.<List<String>>read(body, "$.data.items[*].actorName"))
                .contains("Hệ thống", jdbc.queryForObject("SELECT display_name FROM users WHERE id = ?", String.class, adminId));
        mvc.perform(get("/api/admin/logs").param("action", "missing-" + action).session(admin))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(0))
                .andExpect(jsonPath("$.data.items").isEmpty());
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
        String videoLimitBefore = value("videos_per_day");
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
        assertThat(value("videos_per_day")).isEqualTo(videoLimitBefore);
        assertThat(count("activity_logs WHERE actor_id = " + adminId)).isEqualTo(logs);

        // Unknown keys and settings from another card are refused as a whole.
        saveSafety(admin, "{\"nope\":1}").andExpect(status().isBadRequest());
        saveSafety(admin, "{\"lock_minutes\":10}").andExpect(status().isBadRequest());

        // mute_days is an applied report-safety setting and can be changed within its range.
        saveSafety(admin, "{\"mute_days\":10}").andExpect(status().isOk())
                .andExpect(jsonPath("$.data.changed").value(true));
        assertThat(value("mute_days")).isEqualTo("10");
        assertThat(count("activity_logs WHERE actor_id = " + adminId)).isEqualTo(logs + 1);
    }

    @Test
    void safetyCardsListTheTenParameters() throws Exception {
        MockHttpSession admin = signIn(user("ADMIN", "ACTIVE", 30));
        mvc.perform(get("/api/admin/safety-config").session(admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[0].params.length()").value(4))
                .andExpect(jsonPath("$.data[1].params.length()").value(6))
                .andExpect(jsonPath("$.data[1].params[?(@.key=='mute_days')].editable").value(true))
                .andExpect(jsonPath("$.data[1].params[?(@.key=='mute_days')].min").value(1))
                .andExpect(jsonPath("$.data[1].params[?(@.key=='mute_days')].max").value(365))
                .andExpect(jsonPath("$.data[1].params[?(@.key=='proof_keep_days')].editable").value(true));

        String body = mvc.perform(get("/api/admin/safety-config").session(admin))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        Map<String, int[]> expected = Map.ofEntries(
                Map.entry("new_account_days", new int[] {0, 365}), Map.entry("posts_per_day", new int[] {1, 50}),
                Map.entry("pins_per_day", new int[] {1, 50}), Map.entry("videos_per_day", new int[] {0, 10}),
                Map.entry("report_hide_threshold", new int[] {1, 100}), Map.entry("false_report_max", new int[] {1, 100}),
                Map.entry("mute_days", new int[] {1, 365}), Map.entry("closed_report_threshold", new int[] {1, 100}),
                Map.entry("proof_keep_days", new int[] {1, 60}), Map.entry("removed_keep_days", new int[] {1, 365}));
        for (Map.Entry<String, int[]> entry : expected.entrySet()) {
            List<Map<String, Object>> params = JsonPath.read(body, "$.data[*].params[*]");
            Map<String, Object> param = params.stream().filter(p -> entry.getKey().equals(p.get("key")))
                    .findFirst().orElseThrow();
            assertThat(((Number) param.get("min")).intValue()).as(entry.getKey() + " min").isEqualTo(entry.getValue()[0]);
            assertThat(((Number) param.get("max")).intValue()).as(entry.getKey() + " max").isEqualTo(entry.getValue()[1]);
            assertThat(param.get("editable")).as(entry.getKey() + " editable").isEqualTo(true);
        }
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
