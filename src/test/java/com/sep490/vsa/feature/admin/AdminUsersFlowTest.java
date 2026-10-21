package com.sep490.vsa.feature.admin;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jayway.jsonpath.JsonPath;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.AfterEach;
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
import java.util.ArrayList;
import java.util.Comparator;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.forwardedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrlPattern;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Account management by an Admin, against MySQL ({@code asvsj_test}), with sessions from the real login API.
 * <ul>
 *   <li>A3: the Moderator limit holds on promotion, on unlock, and for two promotions sent at the same moment.</li>
 *   <li>A11: the dashboard leaves deleted accounts out and counts active Moderators like the limit does.</li>
 *   <li>A15: a huge page number, ties in the log order, %, _ and \ in the search, a missing user (404).</li>
 *   <li>S15: the admin pages have clean addresses, only for Admins; the old .html addresses answer 301.</li>
 * </ul>
 * The shared test database holds many Moderators made by other tests, so the limit tests first lock them: inside a
 * rolled-back transaction, or (for the parallel test, which must commit) unlocked again afterwards.
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:mysql://localhost:3306/asvsj_test?createDatabaseIfNotExist=true&useUnicode=true&characterEncoding=utf8&connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true",
        "app.media.local-dir=target/test-uploads",
        "app.moderation.retry-initial-delay-ms=3600000",
        "app.places.osm.enabled=false"
})
@AutoConfigureMockMvc
@ActiveProfiles("local")
class AdminUsersFlowTest {

    private static final String CSRF = "test-csrf-token";
    private static final String PASSWORD = "matkhau123";
    private static final String HASH = new BCryptPasswordEncoder().encode(PASSWORD);

    @Autowired
    private MockMvc mvc;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private ObjectMapper json;

    private final String tag = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
    private final List<Long> users = new ArrayList<>();
    /** Moderators of other tests locked by the parallel test, unlocked again afterwards. */
    private final List<Long> lockedModerators = new ArrayList<>();
    private String maxModeratorsBefore;

    @AfterEach
    void cleanUp() {
        for (int from = 0; from < lockedModerators.size(); from += 500) {
            List<Long> part = lockedModerators.subList(from, Math.min(lockedModerators.size(), from + 500));
            jdbc.update("UPDATE users SET status = 'ACTIVE' WHERE id IN (" + String.join(",",
                    part.stream().map(String::valueOf).toList()) + ")");
        }
        if (maxModeratorsBefore != null) {
            jdbc.update("UPDATE system_settings SET setting_value = ? WHERE setting_key = 'max_moderators'",
                    maxModeratorsBefore);
        }
        for (long id : users) {
            jdbc.update("DELETE FROM activity_logs WHERE actor_id = ? OR (target_type = 'USER' AND target_id = ?)", id, id);
            jdbc.update("DELETE FROM users WHERE id = ?", id);
        }
        jdbc.update("DELETE FROM activity_logs WHERE action = ?", "PAGING_" + tag);
    }

    // ---------------------------------------------------------------- A3: Moderator limit

    @Test
    @Transactional
    void unlockingAModeratorCannotGoOverTheLimit() throws Exception {
        jdbc.update("UPDATE users SET status = 'LOCKED' WHERE role = 'MODERATOR' AND status = 'ACTIVE'");
        jdbc.update("UPDATE system_settings SET setting_value = '3' WHERE setting_key = 'max_moderators'");
        MockHttpSession admin = signIn(user("ADMIN", "ACTIVE"));
        long m1 = user("MODERATOR", "ACTIVE");
        user("MODERATOR", "ACTIVE");
        user("MODERATOR", "ACTIVE");
        long m4 = user("MODERATOR", "LOCKED");

        changeStatus(admin, m4, "ACTIVE").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(limit(3)));
        assertThat(statusOf(m4)).isEqualTo("LOCKED");

        changeStatus(admin, m1, "LOCKED").andExpect(status().isOk());
        changeStatus(admin, m4, "ACTIVE").andExpect(status().isOk());
        assertThat(activeModerators()).isEqualTo(3);
        changeStatus(admin, m1, "ACTIVE").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(limit(3)));
        assertThat(statusOf(m1)).isEqualTo("LOCKED");

        // A lower limit: promoting and unlocking are refused, locking and demoting still work
        jdbc.update("UPDATE system_settings SET setting_value = '2' WHERE setting_key = 'max_moderators'");
        changeRole(admin, user("USER", "ACTIVE"), "MODERATOR").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(limit(2)));
        changeStatus(admin, m4, "LOCKED").andExpect(status().isOk());
        changeStatus(admin, m4, "ACTIVE").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(limit(2)));
        changeRole(admin, m4, "USER").andExpect(status().isOk());
        changeStatus(admin, m4, "ACTIVE").andExpect(status().isOk());     // a USER is not counted
        assertThat(activeModerators()).isEqualTo(2);

        // A broken value (set outside the page) counts as the default 3, never more
        jdbc.update("UPDATE system_settings SET setting_value = '99' WHERE setting_key = 'max_moderators'");
        changeRole(admin, user("USER", "ACTIVE"), "MODERATOR").andExpect(status().isOk());
        changeRole(admin, user("USER", "ACTIVE"), "MODERATOR").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(limit(3)));
    }

    @Test
    void twoPromotionsAtOnceWithOneSlotLeftOnlyOneGoesThrough() throws Exception {
        maxModeratorsBefore = jdbc.queryForObject(
                "SELECT setting_value FROM system_settings WHERE setting_key = 'max_moderators'", String.class);
        lockedModerators.addAll(jdbc.queryForList(
                "SELECT id FROM users WHERE role = 'MODERATOR' AND status = 'ACTIVE'", Long.class));
        jdbc.update("UPDATE users SET status = 'LOCKED' WHERE role = 'MODERATOR' AND status = 'ACTIVE'");
        jdbc.update("UPDATE system_settings SET setting_value = '3' WHERE setting_key = 'max_moderators'");
        MockHttpSession adminA = signIn(user("ADMIN", "ACTIVE"));
        MockHttpSession adminB = signIn(user("ADMIN", "ACTIVE"));
        user("MODERATOR", "ACTIVE");
        user("MODERATOR", "ACTIVE");

        for (int round = 0; round < 10; round++) {
            long u1 = user("USER", "ACTIVE");
            long u2 = user("USER", "ACTIVE");
            List<Object> results = together(
                    () -> changeRole(adminA, u1, "MODERATOR").andReturn().getResponse(),
                    () -> changeRole(adminB, u2, "MODERATOR").andReturn().getResponse());
            List<Integer> statuses = results.stream()
                    .map(r -> ((org.springframework.mock.web.MockHttpServletResponse) r).getStatus()).toList();
            assertThat(statuses).as("round " + round).containsExactlyInAnyOrder(200, 400);
            assertThat(activeModerators()).as("round " + round).isEqualTo(3);
            jdbc.update("UPDATE users SET role = 'USER' WHERE id IN (?, ?)", u1, u2);

            // Unlocking a Moderator at the same moment as a promotion: also only one
            long locked = user("MODERATOR", "LOCKED");
            long u3 = user("USER", "ACTIVE");
            results = together(
                    () -> changeStatus(adminA, locked, "ACTIVE").andReturn().getResponse(),
                    () -> changeRole(adminB, u3, "MODERATOR").andReturn().getResponse());
            statuses = results.stream()
                    .map(r -> ((org.springframework.mock.web.MockHttpServletResponse) r).getStatus()).toList();
            assertThat(statuses).as("round " + round + " unlock").containsExactlyInAnyOrder(200, 400);
            assertThat(activeModerators()).as("round " + round + " unlock").isEqualTo(3);
            jdbc.update("UPDATE users SET role = 'USER' WHERE id IN (?, ?)", locked, u3);
        }
    }

    // ---------------------------------------------------------------- A11: dashboard

    @Test
    void theDashboardLeavesDeletedAccountsOutAndCountsActiveModerators() throws Exception {
        MockHttpSession admin = signIn(user("ADMIN", "ACTIVE"));
        Map<String, Object> before = stats(admin);

        user("USER", "ACTIVE");
        long lockedUser = user("USER", "LOCKED");
        long deletedUser = user("USER", "DELETED");
        user("MODERATOR", "ACTIVE");
        user("MODERATOR", "LOCKED");
        user("MODERATOR", "DELETED");
        user("ACADEMIC_MANAGER", "DELETED");
        user("ADMIN", "DELETED");
        jdbc.update("UPDATE users SET false_report_count = 2 WHERE id IN (?, ?)", lockedUser, deletedUser);

        Map<String, Object> after = stats(admin);
        assertThat(delta(before, after, "totalUsers")).isEqualTo(4);
        assertThat(delta(before, after, "activeUsers")).isEqualTo(2);
        assertThat(delta(before, after, "lockedUsers")).isEqualTo(2);
        assertThat(delta(before, after, "userRoleCount")).isEqualTo(2);
        assertThat(delta(before, after, "moderatorCount")).isEqualTo(1);
        assertThat(delta(before, after, "academicManagerCount")).isZero();
        assertThat(delta(before, after, "adminCount")).isZero();
        assertThat(delta(before, after, "falseReportUsersCount")).isEqualTo(1);

        // The numbers are those of the database
        assertThat(number(after, "totalUsers")).isEqualTo(count("status IN ('ACTIVE', 'LOCKED')"));
        assertThat(number(after, "moderatorCount")).isEqualTo(count("role = 'MODERATOR' AND status = 'ACTIVE'"));
        assertThat(number(after, "userRoleCount")).isEqualTo(count("role = 'USER' AND status <> 'DELETED'"));
        assertThat(number(after, "adminCount")).isEqualTo(count("role = 'ADMIN' AND status <> 'DELETED'"));
        assertThat(after.get("maxModerators")).isEqualTo(Integer.parseInt(jdbc.queryForObject(
                "SELECT setting_value FROM system_settings WHERE setting_key = 'max_moderators'", String.class)));
    }

    // ---------------------------------------------------------------- A15: paging, search, 404

    @Test
    void aHugePageNumberGivesAnEmptyLastPageNotAnError() throws Exception {
        MockHttpSession admin = signIn(user("ADMIN", "ACTIVE"));
        for (String path : new String[] {"/api/admin/users", "/api/admin/logs"}) {
            mvc.perform(get(path).param("page", String.valueOf(Integer.MAX_VALUE)).param("size", "100").session(admin))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.page").value(ActivityLogService.MAX_PAGE))
                    .andExpect(jsonPath("$.data.items").isEmpty());
            mvc.perform(get(path).param("page", "-5").param("size", "1").session(admin))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.page").value(1));
            mvc.perform(get(path).param("page", "99999999999").session(admin)).andExpect(status().isBadRequest());
        }
    }

    @Test
    void logRowsOfTheSameSecondStayInOneOrderAcrossPages() throws Exception {
        MockHttpSession admin = signIn(user("ADMIN", "ACTIVE"));
        String action = "PAGING_" + tag;
        Timestamp sameSecond = Timestamp.from(Instant.now().minus(Duration.ofDays(1)).truncatedTo(java.time.temporal.ChronoUnit.SECONDS));
        for (int i = 0; i < 5; i++) {
            jdbc.update("INSERT INTO activity_logs (action, target_type, description, created_at) VALUES (?, 'TEST', ?, ?)",
                    action, "row " + i, sameSecond);
        }
        List<Long> expected = new ArrayList<>(jdbc.queryForList("SELECT id FROM activity_logs WHERE action = ?",
                Long.class, action));
        expected.sort(Comparator.reverseOrder());

        List<Long> seen = new ArrayList<>();
        for (int page = 1; page <= 3; page++) {
            String body = mvc.perform(get("/api/admin/logs").param("action", action).param("size", "2")
                            .param("page", String.valueOf(page)).session(admin))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(5))
                    .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
            List<Number> ids = JsonPath.read(body, "$.data.items[*].id");
            ids.forEach(id -> seen.add(id.longValue()));
        }
        assertThat(seen).containsExactlyElementsOf(expected);
    }

    @Test
    void theUserSearchFindsPercentUnderscoreAndBackslashAsTyped() throws Exception {
        MockHttpSession admin = signIn(user("ADMIN", "ACTIVE"));
        named("pct" + tag + "%done");
        named("pct" + tag + "Xdone");
        named("us" + tag + "_x");
        named("us" + tag + "Yx");
        named("bs" + tag + "\\path");

        assertThat(search(admin, "pct" + tag + "%")).isEqualTo(1);
        assertThat(search(admin, "pct" + tag + "_done")).isZero();
        assertThat(search(admin, "us" + tag + "_")).isEqualTo(1);
        assertThat(search(admin, "bs" + tag + "\\")).isEqualTo(1);
        assertThat(search(admin, "pct" + tag)).isEqualTo(2);

        // The keyword search of the banned-keyword list too
        long withPercent = jdbc.queryForObject("SELECT COUNT(*) FROM banned_keywords WHERE LOCATE('%', keyword) > 0",
                Long.class);
        String body = mvc.perform(get("/api/admin/keywords").param("search", "%").session(admin))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(JsonPath.<List<Object>>read(body, "$.data")).hasSize((int) withPercent);
    }

    @Test
    void aMissingUserAnswers404() throws Exception {
        MockHttpSession admin = signIn(user("ADMIN", "ACTIVE"));
        long missing = jdbc.queryForObject("SELECT MAX(id) FROM users", Long.class) + 1000;
        String message = "Không tìm thấy người dùng với ID: " + missing;
        mvc.perform(get("/api/admin/users/" + missing).session(admin))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.message").value(message));
        changeRole(admin, missing, "MODERATOR").andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value(message));
        changeStatus(admin, missing, "LOCKED").andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value(message));
    }

    // ---------------------------------------------------------------- S15: clean addresses

    @Test
    void theAdminPagesHaveCleanAddressesForAdminsOnly() throws Exception {
        MockHttpSession admin = signIn(user("ADMIN", "ACTIVE"));
        Map<String, String> pages = Map.of(
                "/admin", "/pages/admin/index.html",
                "/admin/users", "/pages/admin/users.html",
                "/admin/system-config", "/pages/admin/system-config.html",
                "/admin/activity-log", "/pages/admin/activity-log.html");
        MockHttpSession user = signIn(user("USER", "ACTIVE"));
        MockHttpSession moderator = signIn(user("MODERATOR", "ACTIVE"));
        MockHttpSession academic = signIn(user("ACADEMIC_MANAGER", "ACTIVE"));

        for (Map.Entry<String, String> page : pages.entrySet()) {
            String clean = page.getKey();
            String file = page.getValue();
            mvc.perform(get(clean).session(admin)).andExpect(status().isOk()).andExpect(forwardedUrl(file));
            // The old address answers 301 to the clean one, query string kept
            mvc.perform(get(file).session(admin)).andExpect(status().isMovedPermanently())
                    .andExpect(header().string("Location", clean));
            mvc.perform(get(file + "?x=1").session(admin)).andExpect(status().isMovedPermanently())
                    .andExpect(header().string("Location", clean + "?x=1"));
            // Guests go to the login page, other roles to their own home page; nobody else gets the file
            mvc.perform(get(clean)).andExpect(redirectedUrlPattern("**/login"));
            mvc.perform(get(file)).andExpect(redirectedUrlPattern("**/login"));
            mvc.perform(get(clean).session(user)).andExpect(redirectedUrl("/"));
            mvc.perform(get(file).session(user)).andExpect(redirectedUrl("/"));
            mvc.perform(get(clean).session(moderator)).andExpect(redirectedUrl("/moderator"));
            mvc.perform(get(clean).session(academic)).andExpect(redirectedUrl("/academic"));
        }
        mvc.perform(get("/pages/admin/").session(admin)).andExpect(status().isMovedPermanently())
                .andExpect(header().string("Location", "/admin"));

        // The admin scripts link to the clean addresses only
        for (String script : new String[] {"/assets/js/components/admin-layout.js", "/assets/js/pages/adminDashboard.js",
                "/assets/js/pages/adminUsers.js", "/assets/js/pages/adminConfig.js", "/assets/js/pages/adminLogs.js"}) {
            String source = mvc.perform(get(script)).andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
            assertThat(source).as(script).doesNotContain("/pages/");
        }
    }

    // ---------------------------------------------------------------- helpers

    private static String limit(int max) {
        return "Hệ thống chỉ cho phép tối đa " + max + " Moderator đang hoạt động. Hiện đã đủ chỉ tiêu.";
    }

    private long activeModerators() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM users WHERE role = 'MODERATOR' AND status = 'ACTIVE'",
                Long.class);
    }

    private long count(String where) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM users WHERE " + where, Long.class);
    }

    private Map<String, Object> stats(MockHttpSession admin) throws Exception {
        String body = mvc.perform(get("/api/admin/dashboard/stats").session(admin)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        return JsonPath.read(body, "$.data");
    }

    private static long number(Map<String, Object> stats, String name) {
        return ((Number) stats.get(name)).longValue();
    }

    private static long delta(Map<String, Object> before, Map<String, Object> after, String name) {
        return number(after, name) - number(before, name);
    }

    private long search(MockHttpSession admin, String keyword) throws Exception {
        String body = mvc.perform(get("/api/admin/users").param("keyword", keyword).session(admin))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        return ((Number) JsonPath.read(body, "$.data.total")).longValue();
    }

    private void named(String displayName) {
        long id = user("USER", "ACTIVE");
        jdbc.update("UPDATE users SET display_name = ? WHERE id = ?", displayName, id);
    }

    private String statusOf(long id) {
        return jdbc.queryForObject("SELECT status FROM users WHERE id = ?", String.class, id);
    }

    private long user(String role, String status) {
        String email = "adminusers-" + UUID.randomUUID() + "@test.local";
        jdbc.update("""
                INSERT INTO users (email, password_hash, email_verified_at, display_name, role, status, is_adult_confirmed,
                                   created_at, deleted_at)
                VALUES (?, ?, ?, ?, ?, ?, TRUE, ?, ?)""",
                email, HASH, Timestamp.from(Instant.now()), "Users Test", role, status,
                Timestamp.from(Instant.now().minus(Duration.ofDays(30))),
                "DELETED".equals(status) ? Timestamp.from(Instant.now()) : null);
        long id = jdbc.queryForObject("SELECT id FROM users WHERE email = ?", Long.class, email);
        users.add(id);
        return id;
    }

    /** Signs in through the real login API and returns the session it created. */
    private MockHttpSession signIn(long id) throws Exception {
        String email = jdbc.queryForObject("SELECT email FROM users WHERE id = ?", String.class, id);
        return (MockHttpSession) mvc.perform(post("/api/auth/login").cookie(new Cookie("XSRF-TOKEN", CSRF))
                        .header("X-XSRF-TOKEN", CSRF).contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("email", email, "password", PASSWORD))))
                .andExpect(status().isOk()).andReturn().getRequest().getSession(false);
    }

    private ResultActions changeStatus(MockHttpSession session, long target, String next) throws Exception {
        return mvc.perform(withCsrf(put("/api/admin/users/" + target + "/status"), session)
                .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"" + next + "\",\"reason\":\"thử\"}"));
    }

    private ResultActions changeRole(MockHttpSession session, long target, String next) throws Exception {
        return mvc.perform(withCsrf(put("/api/admin/users/" + target + "/role"), session)
                .contentType(MediaType.APPLICATION_JSON).content("{\"role\":\"" + next + "\"}"));
    }

    private static MockHttpServletRequestBuilder withCsrf(MockHttpServletRequestBuilder request, MockHttpSession session) {
        return request.session(session).cookie(new Cookie("XSRF-TOKEN", CSRF)).header("X-XSRF-TOKEN", CSRF);
    }

    /** Runs the calls on separate threads released at the same moment; each result or the exception it threw. */
    @SafeVarargs
    private static List<Object> together(Callable<Object>... calls) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(calls.length);
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
