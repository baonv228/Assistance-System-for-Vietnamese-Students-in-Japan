package com.sep490.vsa.feature.user;

import com.jayway.jsonpath.JsonPath;
import com.sep490.vsa.common.exception.ApiException;
import com.sep490.vsa.common.security.AppUserPrincipal;
import com.sep490.vsa.feature.home.InterestService;
import com.sep490.vsa.feature.saved.SavedRepository;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.http.MediaType;
import org.springframework.jdbc.BadSqlGrammarException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.nio.charset.StandardCharsets;
import java.sql.SQLSyntaxErrorException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;
import static org.springframework.security.web.context.HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * "Điều chỉnh" in "Dành cho bạn" on the member Home ({@code PUT /api/me/feed-preferences}): area, stage and "Chủ đề
 * quan tâm" are saved together or not at all, end to end against MySQL ({@code asvsj_test}). Not one transaction: the
 * saves commit, and every test deletes its accounts (their topics go with them) afterwards. What the topics do to the
 * feed is checked in HomeFeedFlowTest, the topic rules in InterestFlowTest.
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:mysql://localhost:3306/asvsj_test?createDatabaseIfNotExist=true&useUnicode=true&characterEncoding=utf8&connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true",
        "app.media.local-dir=target/test-uploads",
        "app.moderation.retry-initial-delay-ms=3600000",
        "app.places.osm.enabled=false"
})
@AutoConfigureMockMvc
@ActiveProfiles("local")
class FeedPreferencesFlowTest {

    private static final String CSRF = "test-csrf-token";
    private static final String NOT_SIGNED_IN = "Bạn chưa đăng nhập.";

    @Autowired
    private MockMvc mvc;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private ProfileService profiles;
    @SpyBean
    private SavedRepository saved;

    private final List<Long> users = new ArrayList<>();
    private long member;
    private long areaA;
    private long areaB;

    @BeforeEach
    void setUp() {
        member = user("USER", "ACTIVE");
        areaA = jdbc.queryForObject("SELECT MIN(id) FROM areas WHERE is_active", Long.class);
        areaB = jdbc.queryForObject("SELECT MAX(id) FROM areas WHERE is_active", Long.class);
    }

    @AfterEach
    void cleanUp() {
        for (long id : users) {
            jdbc.update("DELETE FROM users WHERE id = ?", id);
        }
    }

    @Test
    void areaStageAndTopicsAreSavedInOneRequest() throws Exception {
        long housing = topic("Nhà ở");
        long japanese = topic("Học tiếng Nhật");
        String body = putPreferences(member, "USER", areaA, "PRE", List.of(japanese, housing, japanese))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Đã lưu."))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(row(member)).containsEntry("area_id", areaA).containsEntry("stage", "PRE");
        assertThat(chosenIds(member)).containsExactlyInAnyOrder(housing, japanese);
        // The answer is what is saved now: the area's name, the topics in the Blog's order (a repeat counts once)
        assertThat(((Number) JsonPath.read(body, "$.data.areaId")).longValue()).isEqualTo(areaA);
        assertThat((String) JsonPath.read(body, "$.data.areaName")).isEqualTo(
                jdbc.queryForObject("SELECT display_name FROM areas WHERE id = ?", String.class, areaA));
        assertThat((String) JsonPath.read(body, "$.data.stage")).isEqualTo("PRE");
        assertThat(longs(JsonPath.read(body, "$.data.topicIds"))).isEqualTo(jdbc.queryForList("""
                SELECT id FROM blog_topics WHERE id IN (?, ?) ORDER BY sort_order, name""", Long.class, housing, japanese));

        // topicIds null (the topics did not change or could not be loaded): area and stage only, the topics stay
        putPreferences(member, "USER", areaB, "ARR", null).andExpect(status().isOk());
        assertThat(row(member)).containsEntry("area_id", areaB).containsEntry("stage", "ARR");
        assertThat(chosenIds(member)).containsExactlyInAnyOrder(housing, japanese);

        // Empty clears the topics; no area and no stage ("Chưa chọn", the page sends null) clear those
        putJson(member, "USER", "{\"areaId\":null,\"stage\":null,\"topicIds\":[]}").andExpect(status().isOk())
                .andExpect(jsonPath("$.data.areaId").doesNotExist())
                .andExpect(jsonPath("$.data.stage").doesNotExist())
                .andExpect(jsonPath("$.data.topicIds").isEmpty());
        assertThat(row(member)).containsEntry("area_id", null).containsEntry("stage", null);
        assertThat(chosenIds(member)).isEmpty();
    }

    /** Whatever is refused, nothing is saved: neither the area and stage nor the topics. */
    @Test
    void aRefusalSavesNothing() throws Exception {
        long housing = topic("Nhà ở");
        putPreferences(member, "USER", areaA, "ARR", List.of(housing)).andExpect(status().isOk());
        List<Long> six = jdbc.queryForList(
                "SELECT id FROM blog_topics WHERE is_active AND NOT is_other ORDER BY sort_order, name LIMIT 6", Long.class);
        assertThat(six).hasSize(6);
        List<Long> two = six.subList(1, 3);

        putPreferences(member, "USER", 999_999_999L, "PRE", two).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Khu vực không hợp lệ"));
        assertUnchanged(housing);
        putPreferences(member, "USER", areaB, "PRE", six).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Bạn chọn được tối đa 5 chủ đề."));
        assertUnchanged(housing);
        putPreferences(member, "USER", areaB, "PRE", List.of(topic("Khác"))).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Có chủ đề không còn để chọn. Hãy tải lại trang rồi chọn lại."));
        assertUnchanged(housing);
        putJson(member, "USER", "{\"areaId\":" + areaB + ",\"stage\":\"PRE\",\"topicIds\":[" + two.get(0) + ",null]}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Danh sách chủ đề không hợp lệ."));
        assertUnchanged(housing);
        putPreferences(member, "USER", areaB, "SOON", two).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Giai đoạn không hợp lệ"));
        assertUnchanged(housing);
        putJson(member, "USER", "{\"areaId\":\"abc\"}").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Dữ liệu gửi lên không đúng định dạng"));
        assertUnchanged(housing);
    }

    /**
     * The error of 11/13: the shared database still lacked V22 {@code saved_phrases}. The Home saved the topics, then
     * called PUT /api/me/profile, whose answer is the whole "Tôi" page (with the count of "Đã lưu"): it failed (500,
     * "Có lỗi xảy ra, vui lòng thử lại.") and its save was undone, so the page said "Đã lưu chủ đề quan tâm, nhưng chưa
     * lưu được khu vực và giai đoạn". The Home's one request reads nothing of "Đã lưu".
     */
    @Test
    void aTableOfAnotherFeatureThatCannotBeReadDoesNotUndoTheSave() throws Exception {
        doThrow(new BadSqlGrammarException("count", "SELECT COUNT(*) FROM saved_phrases",
                new SQLSyntaxErrorException("Table 'asvsj.saved_phrases' doesn't exist")))
                .when(saved).count(anyLong());
        long housing = topic("Nhà ở");

        // The stub does reproduce it: the "Tôi" answer fails and takes area and stage with it
        assertThatThrownBy(() -> mvc.perform(withCsrf(put("/api/me/profile"), member, "USER")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"areaId\":" + areaA + ",\"stage\":\"PRE\"}")))
                .hasRootCauseInstanceOf(SQLSyntaxErrorException.class);
        assertThat(row(member)).containsEntry("area_id", null).containsEntry("stage", null);

        putPreferences(member, "USER", areaA, "PRE", List.of(housing)).andExpect(status().isOk());
        assertThat(row(member)).containsEntry("area_id", areaA).containsEntry("stage", "PRE");
        assertThat(chosenIds(member)).containsExactly(housing);
    }

    @Test
    void guestsSignInAndStaffSaveAreaAndStageOnly() throws Exception {
        mvc.perform(put("/api/me/feed-preferences").cookie(new Cookie("XSRF-TOKEN", CSRF)).header("X-XSRF-TOKEN", CSRF)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"stage\":\"PRE\"}"))
                .andExpect(status().isUnauthorized());
        // Without the CSRF header: refused before the application runs
        mvc.perform(put("/api/me/feed-preferences").session(session(member, "USER"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"stage\":\"PRE\"}"))
                .andExpect(status().isForbidden());
        assertThat(row(member)).containsEntry("stage", null);

        // Staff have no "Chủ đề quan tâm" (MSG18), and then nothing is saved; area and stage alone are, as on Tôi
        long moderator = user("MODERATOR", "ACTIVE");
        putPreferences(moderator, "MODERATOR", areaA, "ARR", List.of(topic("Nhà ở"))).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value("Bạn không có quyền thực hiện thao tác này."));
        assertThat(row(moderator)).containsEntry("area_id", null).containsEntry("stage", null);
        assertThat(chosenIds(moderator)).isEmpty();
        putPreferences(moderator, "MODERATOR", areaA, "ARR", null).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.topicIds").isEmpty());
        assertThat(row(moderator)).containsEntry("area_id", areaA).containsEntry("stage", "ARR");

        // A deleted account (its session ends at the next request in the app; the service refuses it too)
        long deleted = user("USER", "DELETED");
        assertThatThrownBy(() -> profiles.updateFeedPreferences(deleted, areaA, "ARR", List.of()))
                .isInstanceOf(ApiException.class).hasMessage(NOT_SIGNED_IN);
        assertThat(row(deleted)).containsEntry("area_id", null).containsEntry("stage", null);
    }

    // ---------------------------------------------------------------- helpers

    private void assertUnchanged(long topicId) {
        assertThat(row(member)).containsEntry("area_id", areaA).containsEntry("stage", "ARR");
        assertThat(chosenIds(member)).containsExactly(topicId);
    }

    private long user(String role, String status) {
        String email = "feedpref-" + UUID.randomUUID() + "@test.local";
        jdbc.update("""
                INSERT INTO users (email, display_name, role, status, is_adult_confirmed, created_at)
                VALUES (?, 'Feed Preferences Test', ?, ?, TRUE, ?)""",
                email, role, status, Timestamp.from(Instant.now().minus(Duration.ofDays(30))));
        long id = jdbc.queryForObject("SELECT id FROM users WHERE email = ?", Long.class, email);
        users.add(id);
        return id;
    }

    private long topic(String name) {
        return jdbc.queryForObject("SELECT id FROM blog_topics WHERE name = ?", Long.class, name);
    }

    private List<Long> chosenIds(long userId) {
        return jdbc.queryForList("SELECT topic_id FROM user_interest_topics WHERE user_id = ?", Long.class, userId);
    }

    /** area_id (as a Long), stage and status of the users row. */
    private Map<String, Object> row(long userId) {
        Map<String, Object> row = new HashMap<>(jdbc.queryForMap(
                "SELECT area_id, stage, status FROM users WHERE id = ?", userId));
        row.computeIfPresent("area_id", (k, v) -> ((Number) v).longValue());
        return row;
    }

    private static List<Long> longs(List<Object> numbers) {
        return numbers.stream().map(n -> ((Number) n).longValue()).toList();
    }

    private static MockHttpSession session(long userId, String role) {
        AppUserPrincipal me = new AppUserPrincipal(userId, "u" + userId + "@test.local", "Feed Preferences Test", role, "/");
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(SPRING_SECURITY_CONTEXT_KEY, new SecurityContextImpl(new UsernamePasswordAuthenticationToken(
                me, null, List.of(new SimpleGrantedAuthority("ROLE_" + role)))));
        return session;
    }

    private static MockHttpServletRequestBuilder withCsrf(MockHttpServletRequestBuilder request, long userId, String role) {
        return request.session(session(userId, role)).cookie(new Cookie("XSRF-TOKEN", CSRF)).header("X-XSRF-TOKEN", CSRF);
    }

    private ResultActions putPreferences(long userId, String role, Long areaId, String stage, List<Long> topicIds)
            throws Exception {
        return putJson(userId, role, "{\"areaId\":" + areaId + ",\"stage\":" + (stage == null ? "null" : "\"" + stage + "\"")
                + ",\"topicIds\":" + topicIds + "}");
    }

    private ResultActions putJson(long userId, String role, String json) throws Exception {
        return mvc.perform(withCsrf(put("/api/me/feed-preferences"), userId, role)
                .contentType(MediaType.APPLICATION_JSON).content(json));
    }
}
