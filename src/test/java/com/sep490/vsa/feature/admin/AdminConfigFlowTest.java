package com.sep490.vsa.feature.admin;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jayway.jsonpath.JsonPath;
import com.sep490.vsa.feature.auth.EmailCodeCleanup;
import com.sep490.vsa.feature.moderation.ContentFilter;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
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

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * System settings, banned keywords, the activity log and the daily clean-up, against MySQL ({@code asvsj_test}), with
 * sessions from the real login API.
 * <ul>
 *   <li>A5/A7: only the settings the system reads can be changed, each to a whole number in its range; the others
 *       answer 400 and show as "Chưa áp dụng".</li>
 *   <li>A6/A10: a keyword needs 3 letters or digits (2 in Japanese or Chinese) and may not repeat another with the
 *       same accents once spaces and signs are removed.</li>
 *   <li>A9: values with quotes, backslashes and script tags are logged as JSON and read back as they were.</li>
 *   <li>A14: the daily job removes old log rows (at least 30 days kept) and finished sign-up codes only.</li>
 * </ul>
 * Every setting row is put back as it was after each test; the users, keywords, log rows and codes of a test are
 * removed.
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:mysql://localhost:3306/asvsj_test?createDatabaseIfNotExist=true&useUnicode=true&characterEncoding=utf8&connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true",
        "app.media.local-dir=target/test-uploads",
        "app.moderation.retry-initial-delay-ms=3600000",
        "app.places.osm.enabled=false"
})
@AutoConfigureMockMvc
@ActiveProfiles("local")
class AdminConfigFlowTest {

    private static final String CSRF = "test-csrf-token";
    private static final String PASSWORD = "matkhau123";
    private static final String HASH = new BCryptPasswordEncoder().encode(PASSWORD);

    /** The settings the system reads and their allowed values (the agreed table). */
    private static final Map<String, int[]> APPLIED = new LinkedHashMap<>();

    static {
        APPLIED.put("report_hide_threshold", new int[]{1, 100});
        APPLIED.put("new_account_days", new int[]{0, 365});
        APPLIED.put("posts_per_day", new int[]{1, 50});
        APPLIED.put("pins_per_day", new int[]{1, 50});
        APPLIED.put("videos_per_day", new int[]{0, 10});
        APPLIED.put("max_moderators", new int[]{1, 3});
        APPLIED.put("featured_top_n", new int[]{1, 3});
        APPLIED.put("featured_min_score", new int[]{0, 1000});
        APPLIED.put("lock_attempts", new int[]{3, 20});
        APPLIED.put("lock_minutes", new int[]{1, 1440});
        APPLIED.put("password_min_length", new int[]{8, 72});
        APPLIED.put("email_code_minutes", new int[]{1, 60});
        APPLIED.put("email_code_attempts", new int[]{3, 20});
        APPLIED.put("email_code_resend_seconds", new int[]{0, 600});
        APPLIED.put("register_ticket_minutes", new int[]{5, 1440});
        APPLIED.put("log_keep_days", new int[]{30, 3650});
    }

    private static final Set<String> NOT_APPLIED = Set.of("false_report_max", "mute_days", "proof_keep_days",
            "closed_report_threshold", "removed_keep_days", "verify_token_hours", "reset_token_minutes",
            "duplicate_warn_radius_m", "merge_suggest_radius_m");

    @Autowired
    private MockMvc mvc;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private ObjectMapper json;
    @Autowired
    private ActivityLogService logService;
    @Autowired
    private EmailCodeCleanup codeCleanup;
    @Autowired
    private ContentFilter contentFilter;

    private final String tag = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
    private final List<Long> users = new ArrayList<>();
    private List<Map<String, Object>> settingsBefore;

    @BeforeEach
    void rememberSettings() {
        settingsBefore = jdbc.queryForList("SELECT setting_key, setting_value, updated_by, updated_at FROM system_settings");
    }

    @AfterEach
    void cleanUp() {
        for (Map<String, Object> row : settingsBefore) {
            jdbc.update("UPDATE system_settings SET setting_value = ?, updated_by = ?, updated_at = ? WHERE setting_key = ?",
                    row.get("setting_value"), row.get("updated_by"), row.get("updated_at"), row.get("setting_key"));
        }
        for (long id : users) {
            jdbc.update("DELETE FROM banned_keywords WHERE created_by = ?", id);
            jdbc.update("DELETE FROM activity_logs WHERE actor_id = ? OR (target_type = 'USER' AND target_id = ?)", id, id);
            jdbc.update("DELETE FROM users WHERE id = ?", id);
        }
        jdbc.update("DELETE FROM activity_logs WHERE description LIKE ?", "%" + tag + "%");
        jdbc.update("DELETE FROM email_verification_codes WHERE email LIKE ?", "%" + tag + "%");
        contentFilter.keywordsChanged();
    }

    // ---------------------------------------------------------------- A5/A7: settings

    @Test
    void theSettingsPageMarksWhichSettingsAreApplied() throws Exception {
        MockHttpSession admin = signIn(user("ADMIN", "ACTIVE"));
        String body = mvc.perform(get("/api/admin/settings").session(admin)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        List<Map<String, Object>> items = JsonPath.read(body, "$.data");
        Map<String, Map<String, Object>> byKey = items.stream()
                .collect(Collectors.toMap(i -> (String) i.get("settingKey"), i -> i));
        assertThat(byKey.keySet()).containsAll(APPLIED.keySet()).containsAll(NOT_APPLIED);

        APPLIED.forEach((key, range) -> {
            assertThat(byKey.get(key).get("wired")).as(key).isEqualTo(true);
            assertThat(byKey.get(key).get("min")).as(key).isEqualTo(range[0]);
            assertThat(byKey.get(key).get("max")).as(key).isEqualTo(range[1]);
            assertThat(byKey.get(key).get("group")).as(key).isNotEqualTo("Khác");
        });
        for (String key : NOT_APPLIED) {
            assertThat(byKey.get(key).get("wired")).as(key).isEqualTo(false);
            assertThat(byKey.get(key).get("min")).as(key).isNull();
            assertThat(byKey.get(key).get("max")).as(key).isNull();
        }
        // The four sign-up settings have their own labels now, not the raw key under "Khác"
        assertThat(byKey.get("email_code_minutes").get("label")).isEqualTo("Thời hạn mã xác thực email (phút)");
        assertThat(byKey.get("register_ticket_minutes").get("label")).isEqualTo("Thời hạn hoàn tất đăng ký (phút)");
    }

    @Test
    void everyAppliedSettingTakesOnlyAWholeNumberInItsRange() throws Exception {
        MockHttpSession admin = signIn(user("ADMIN", "ACTIVE"));
        for (Map.Entry<String, int[]> entry : APPLIED.entrySet()) {
            String key = entry.getKey();
            int min = entry.getValue()[0];
            int max = entry.getValue()[1];
            String range = "phải là số nguyên từ " + min + " đến " + max + ".";

            refused(admin, key, Integer.toString(min - 1), range);
            setting(admin, key, Integer.toString(min)).andExpect(status().isOk());
            assertThat(value(key)).as(key).isEqualTo(Integer.toString(min));
            setting(admin, key, Integer.toString(max)).andExpect(status().isOk());
            assertThat(value(key)).as(key).isEqualTo(Integer.toString(max));
            refused(admin, key, Integer.toString(max + 1), range);
            refused(admin, key, "abc", range);
            refused(admin, key, "+5", range);
            refused(admin, key, "５", range);          // fullwidth digit
            refused(admin, key, "9".repeat(256), range);
            refused(admin, key, "1.5", range);
            assertThat(value(key)).as(key + " after refused values").isEqualTo(Integer.toString(max));
        }
        // Spaces around the number are dropped and leading zeros too
        setting(admin, "posts_per_day", "  007 ").andExpect(status().isOk());
        assertThat(value("posts_per_day")).isEqualTo("7");
        setting(admin, "posts_per_day", " ").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Giá trị cấu hình không được để trống."));
        setting(admin, "posts_per_day", "a".repeat(300)).andExpect(status().isBadRequest());
    }

    @Test
    void settingsNotInUseCannotBeChanged() throws Exception {
        long adminId = user("ADMIN", "ACTIVE");
        MockHttpSession admin = signIn(adminId);
        for (String key : NOT_APPLIED) {
            String before = value(key);
            setting(admin, key, "5").andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.message").value(AdminConfigService.NOT_APPLIED));
            assertThat(value(key)).as(key).isEqualTo(before);
        }
        setting(admin, "no_such_setting", "5").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Không tìm thấy cấu hình với mã: no_such_setting"));
        assertThat(logsBy(adminId)).isZero();
    }

    @Test
    void aSettingChangeIsLoggedOnceAndTheSameValueIsNotLogged() throws Exception {
        long adminId = user("ADMIN", "ACTIVE");
        MockHttpSession admin = signIn(adminId);
        jdbc.update("UPDATE system_settings SET setting_value = '5' WHERE setting_key = 'report_hide_threshold'");
        setting(admin, "report_hide_threshold", "05").andExpect(status().isOk());
        assertThat(logsBy(adminId)).isZero();

        setting(admin, "report_hide_threshold", "12").andExpect(status().isOk());
        assertThat(value("report_hide_threshold")).isEqualTo("12");
        assertThat(jdbc.queryForObject("SELECT updated_by FROM system_settings WHERE setting_key = 'report_hide_threshold'",
                Long.class)).isEqualTo(adminId);
        Map<String, Object> row = jdbc.queryForMap(
                "SELECT old_value, new_value, description FROM activity_logs WHERE actor_id = ? AND action = 'SETTING_UPDATE'",
                adminId);
        assertThat(jsonOf(row.get("old_value"))).containsExactly(Map.entry("report_hide_threshold", "5"));
        assertThat(jsonOf(row.get("new_value"))).containsExactly(Map.entry("report_hide_threshold", "12"));
        assertThat(row.get("description")).isEqualTo("Cập nhật cấu hình [Ngưỡng ẩn nội dung] từ \"5\" thành \"12\"");

        // A value with quotes, a backslash and a script tag is refused before anything is written (it was a 500)
        setting(admin, "report_hide_threshold", "5\"\\</script>").andExpect(status().isBadRequest());
        assertThat(value("report_hide_threshold")).isEqualTo("12");
        assertThat(logsBy(adminId)).isEqualTo(1);
    }

    // ---------------------------------------------------------------- A6/A10: banned keywords

    @Test
    void aKeywordNeedsEnoughLettersAndMayNotRepeatAnotherWithTheSameAccents() throws Exception {
        long adminId = user("ADMIN", "ACTIVE");
        MockHttpSession admin = signIn(adminId);

        addKeyword(admin, "   ").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Từ khóa không được để trống."));
        for (String noLetters : new String[] {"!!!", "​", "- . -", "​​​"}) {
            addKeyword(admin, noLetters).andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.message").value("Từ khóa phải có chữ hoặc số."));
        }
        for (String tooShort : new String[] {"a", "ab", "A B", "đ.ó", "a​b"}) {
            addKeyword(admin, tooShort).andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.message").value("Từ khóa phải có ít nhất 3 chữ cái hoặc chữ số."));
        }
        // Japanese and Chinese: two characters already make a word (詐欺 "scam"), one does not
        for (String oneCharacter : new String[] {"金", "サ", " 詐 ."}) {
            addKeyword(admin, oneCharacter).andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.message").value("Từ khóa tiếng Nhật hoặc tiếng Trung phải có ít nhất 2 chữ."));
        }
        addKeyword(admin, "詐欺").andExpect(status().isOk()).andExpect(jsonPath("$.data.keyword").value("詐欺"));
        addKeyword(admin, "abc").andExpect(status().isOk());
        addKeyword(admin, "x".repeat(101)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Từ khóa không được vượt quá 100 ký tự."));

        // The seeded "vay nóng": the same letters with the same accents, spaces and signs aside, are the same keyword
        for (String same : new String[] {"vay nóng", "VAY-NÓNG", "Vay  Nóng!", " vay.nóng "}) {
            addKeyword(admin, same).andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.message").value("Từ khóa này trùng với “vay nóng” đã có."));
        }
        // Other accents, or none, make another keyword for the filter ("vay nong" also finds "vậy nóng"). But the
        // column is UNIQUE under utf8mb4_0900_ai_ci, which ignores accents (not spaces or signs): MySQL cannot store
        // these two next to "vay nóng" (error 1062), so they are refused before the INSERT, naming the keyword in the way
        for (String sameForMySql : new String[] {"vậy nóng", "vay nong"}) {
            addKeyword(admin, sameForMySql).andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.message").value("Từ khóa này trùng với “vay nóng” đã có."));
        }
        // …written another way, MySQL stores them, and they are new keywords
        addKeyword(admin, "vậy-nóng").andExpect(status().isOk());
        addKeyword(admin, "vaynong").andExpect(status().isOk());
        addKeyword(admin, "v a y n o n g").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Từ khóa này trùng với “vaynong” đã có."));
        addKeyword(admin, "Vậy  Nóng").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Từ khóa này trùng với “vậy-nóng” đã có."));

        String first = "kiếm tiền " + tag;
        long id = keywordId(addKeyword(admin, "  Kiếm Tiền " + tag.toUpperCase() + "  ").andExpect(status().isOk())
                .andExpect(jsonPath("$.data.keyword").value(first)));
        addKeyword(admin, "Kiếm-Tiền-" + tag.toUpperCase()).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Từ khóa này trùng với “" + first + "” đã có."));
        // Fullwidth letters: the database treats them as the same word too
        String ascii = "zq" + tag;
        addKeyword(admin, ascii).andExpect(status().isOk());
        StringBuilder fullwidth = new StringBuilder();
        ascii.chars().forEach(c -> fullwidth.appendCodePoint(c >= 'a' && c <= 'z' ? c - 'a' + 'ａ'
                : c >= '0' && c <= '9' ? c - '0' + '０' : c));
        addKeyword(admin, fullwidth.toString()).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Từ khóa này trùng với “" + ascii + "” đã có."));
        assertThat(jdbc.queryForList("SELECT keyword FROM banned_keywords WHERE created_by = ?", String.class, adminId))
                .containsExactlyInAnyOrder("詐欺", "abc", "vậy-nóng", "vaynong", first, ascii);

        // Deleted, the same words can be added again in another spelling
        mvc.perform(withCsrf(delete("/api/admin/keywords/" + id), admin)).andExpect(status().isOk());
        addKeyword(admin, "kiếm-tiền " + tag).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.keyword").value("kiếm-tiền " + tag));
        // The filter uses new keywords at once
        assertThat(contentFilter.bannedKeyword("Hôm nay kiếm tiền " + tag + " dễ lắm")).contains("kiếm-tiền " + tag);
        assertThat(contentFilter.bannedKeyword("これは詐欺です")).contains("詐欺");
    }

    // ---------------------------------------------------------------- A9: the activity log

    @Test
    void logValuesWithQuotesBackslashesAndScriptTagsAreStoredExactly() throws Exception {
        long adminId = user("ADMIN", "ACTIVE");
        MockHttpSession admin = signIn(adminId);

        String keyword = "say \"hi\" \\ </script> " + tag;
        long keywordId = keywordId(addKeyword(admin, keyword).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.keyword").value(keyword)));
        Map<String, Object> added = logRow(adminId, "KEYWORD_ADD");
        assertThat(added.get("old_value")).isNull();
        assertThat(jsonOf(added.get("new_value"))).containsExactly(Map.entry("keyword", keyword));
        mvc.perform(withCsrf(delete("/api/admin/keywords/" + keywordId), admin)).andExpect(status().isOk());
        Map<String, Object> deleted = logRow(adminId, "KEYWORD_DELETE");
        assertThat(jsonOf(deleted.get("old_value"))).containsExactly(Map.entry("keyword", keyword));
        assertThat(deleted.get("new_value")).isNull();

        // A long reason with quotes: the lock goes through, the reason is kept to 500 characters
        long target = user("USER", "ACTIVE");
        String start = "Lý do \"trích dẫn\" \\ </script> {\"injected\":1} " + tag + " ";
        String reason = start + "x".repeat(2000);
        mvc.perform(withCsrf(put("/api/admin/users/" + target + "/status"), admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("status", "LOCKED", "reason", reason))))
                .andExpect(status().isOk());
        assertThat(jdbc.queryForObject("SELECT status FROM users WHERE id = ?", String.class, target)).isEqualTo("LOCKED");
        String kept = reason.substring(0, AdminUserService.REASON_MAX);
        Map<String, Object> locked = logRow(adminId, "USER_STATUS_CHANGE");
        assertThat(jsonOf(locked.get("old_value"))).containsExactly(Map.entry("status", "ACTIVE"));
        // (MySQL's JSON type keeps the keys in its own order)
        assertThat(jsonOf(locked.get("new_value"))).containsOnly(Map.entry("status", "LOCKED"), Map.entry("reason", kept));
        assertThat((String) locked.get("description")).endsWith(". Lý do: " + kept).contains("thành LOCKED.");

        // Read back through the log API as the page does
        String page = mvc.perform(get("/api/admin/logs").param("action", "USER_STATUS_CHANGE").param("size", "100")
                        .session(admin)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        List<Map<String, Object>> items = JsonPath.read(page, "$.data.items");
        Map<String, Object> item = items.stream().filter(i -> Long.valueOf(target).equals(((Number) i.get("targetId")).longValue()))
                .findFirst().orElseThrow();
        assertThat(jsonOf(item.get("newValue")).get("reason")).isEqualTo(kept);
        assertThat((String) item.get("description")).contains(start);

        // A cut never splits an emoji in two
        assertThat(ActivityLogService.cut("a".repeat(499) + "😀", 500)).isEqualTo("a".repeat(499));
        assertThat(ActivityLogService.cut("a".repeat(498) + "😀", 500)).isEqualTo("a".repeat(498) + "😀");
    }

    // ---------------------------------------------------------------- A14: daily clean-up

    @Test
    void theDailyJobRemovesOldLogsAndFinishedCodesOnly() {
        Instant now = Instant.now();
        jdbc.update("UPDATE system_settings SET setting_value = '30' WHERE setting_key = 'log_keep_days'");
        long old = insertLog("old " + tag, now.minus(Duration.ofDays(31)));
        long recent = insertLog("recent " + tag, now.minus(Duration.ofDays(29)));
        logService.purgeOldLogs();
        assertThat(logExists(old)).isFalse();
        assertThat(logExists(recent)).isTrue();

        // Below 30 days (set outside the page) counts as 30; a longer setting keeps more
        jdbc.update("UPDATE system_settings SET setting_value = '5' WHERE setting_key = 'log_keep_days'");
        logService.purgeOldLogs();
        assertThat(logExists(recent)).isTrue();
        long older = insertLog("older " + tag, now.minus(Duration.ofDays(60)));
        jdbc.update("UPDATE system_settings SET setting_value = '90' WHERE setting_key = 'log_keep_days'");
        logService.purgeOldLogs();
        assertThat(logExists(older)).isTrue();
        jdbc.update("UPDATE system_settings SET setting_value = '45' WHERE setting_key = 'log_keep_days'");
        logService.purgeOldLogs();
        assertThat(logExists(older)).isFalse();
        assertThat(logExists(recent)).isTrue();

        Instant eightDays = now.minus(Duration.ofDays(8));
        Instant later = now.plus(Duration.ofMinutes(20));
        long used = code("used", eightDays, eightDays.plus(Duration.ofMinutes(10)), null, eightDays.plusSeconds(60));
        long expired = code("expired", eightDays, eightDays.plus(Duration.ofMinutes(10)), null, null);
        long ticketOver = code("ticketover", eightDays, eightDays.plus(Duration.ofMinutes(10)),
                eightDays.plus(Duration.ofMinutes(40)), null);
        long ticketOpen = code("ticketopen", eightDays, eightDays.plus(Duration.ofMinutes(10)), later, null);
        long codeOpen = code("codeopen", eightDays, later, null, null);
        long youngUsed = code("young", now.minus(Duration.ofDays(6)), now.minus(Duration.ofDays(6)), null,
                now.minus(Duration.ofDays(6)));
        codeCleanup.purgeOldCodes();
        assertThat(codeExists(used)).as("used").isFalse();
        assertThat(codeExists(expired)).as("expired").isFalse();
        assertThat(codeExists(ticketOver)).as("ticket expired").isFalse();
        assertThat(codeExists(ticketOpen)).as("ticket still valid").isTrue();
        assertThat(codeExists(codeOpen)).as("code still valid").isTrue();
        assertThat(codeExists(youngUsed)).as("younger than 7 days").isTrue();
    }

    // ---------------------------------------------------------------- helpers

    private void refused(MockHttpSession admin, String key, String value, String range) throws Exception {
        setting(admin, key, value).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString(range)));
    }

    private ResultActions setting(MockHttpSession admin, String key, String value) throws Exception {
        return mvc.perform(withCsrf(put("/api/admin/settings/" + key), admin).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("settingValue", value))));
    }

    private ResultActions addKeyword(MockHttpSession admin, String keyword) throws Exception {
        return mvc.perform(withCsrf(post("/api/admin/keywords"), admin).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("keyword", keyword))));
    }

    private static long keywordId(ResultActions result) throws Exception {
        Number id = JsonPath.read(result.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8),
                "$.data.id");
        return id.longValue();
    }

    private String value(String key) {
        return jdbc.queryForObject("SELECT setting_value FROM system_settings WHERE setting_key = ?", String.class, key);
    }

    private long logsBy(long adminId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM activity_logs WHERE actor_id = ?", Long.class, adminId);
    }

    private Map<String, Object> logRow(long actorId, String action) {
        return jdbc.queryForMap("""
                SELECT old_value, new_value, description FROM activity_logs
                WHERE actor_id = ? AND action = ? ORDER BY id DESC LIMIT 1""", actorId, action);
    }

    private Map<String, Object> jsonOf(Object value) throws Exception {
        return json.readValue((String) value, new TypeReference<LinkedHashMap<String, Object>>() {
        });
    }

    private long insertLog(String description, Instant createdAt) {
        jdbc.update("INSERT INTO activity_logs (action, target_type, description, created_at) VALUES ('TEST_RETENTION', 'TEST', ?, ?)",
                description, Timestamp.from(createdAt));
        return jdbc.queryForObject("SELECT id FROM activity_logs WHERE description = ?", Long.class, description);
    }

    private boolean logExists(long id) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM activity_logs WHERE id = ?", Long.class, id) == 1;
    }

    private long code(String name, Instant createdAt, Instant expiresAt, Instant ticketExpiresAt, Instant usedAt) {
        String email = name + tag + "@cleanup.test";
        jdbc.update("""
                INSERT INTO email_verification_codes (email, purpose, code_hash, expires_at, verified_at, ticket_hash,
                                                      ticket_expires_at, used_at, created_at)
                VALUES (?, 'REGISTER', ?, ?, ?, ?, ?, ?, ?)""",
                email, "0".repeat(64), Timestamp.from(expiresAt),
                ticketExpiresAt == null ? null : Timestamp.from(createdAt.plusSeconds(30)),
                ticketExpiresAt == null ? null : (name + tag + "0".repeat(64)).substring(0, 64),
                ticketExpiresAt == null ? null : Timestamp.from(ticketExpiresAt),
                usedAt == null ? null : Timestamp.from(usedAt), Timestamp.from(createdAt));
        return jdbc.queryForObject("SELECT id FROM email_verification_codes WHERE email = ?", Long.class, email);
    }

    private boolean codeExists(long id) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM email_verification_codes WHERE id = ?", Long.class, id) == 1;
    }

    private long user(String role, String status) {
        String email = "admincfg-" + UUID.randomUUID() + "@test.local";
        jdbc.update("""
                INSERT INTO users (email, password_hash, email_verified_at, display_name, role, status, is_adult_confirmed,
                                   created_at, deleted_at)
                VALUES (?, ?, ?, ?, ?, ?, TRUE, ?, ?)""",
                email, HASH, Timestamp.from(Instant.now()), "Config Test " + tag, role, status,
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

    private static MockHttpServletRequestBuilder withCsrf(MockHttpServletRequestBuilder request, MockHttpSession session) {
        return request.session(session).cookie(new Cookie("XSRF-TOKEN", CSRF)).header("X-XSRF-TOKEN", CSRF);
    }
}
