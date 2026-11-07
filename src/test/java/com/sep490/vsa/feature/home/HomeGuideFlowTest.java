package com.sep490.vsa.feature.home;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sep490.vsa.common.security.AppUserPrincipal;
import com.sep490.vsa.feature.guide.GuideRequest;
import com.sep490.vsa.feature.guide.GuideService;
import com.sep490.vsa.feature.moderation.ImageModerator;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Guides on the member Home ("Việc tiếp theo", the guides of "Dành cho bạn") and "Cẩm nang của tôi"
 * ({@code GET /api/me/guides/progress}), end to end against MySQL ({@code asvsj_test}, like GuideFlowTest, whose
 * context it shares). Every test runs in a transaction that is rolled back: it hides the guides other runs left in
 * stage LEAVE, then makes its own (G1 ordinary, G2 checklist with two tasks, G3 ordinary, plus a draft and a deleted
 * guide that must never count) and its own members.
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:mysql://localhost:3306/${guide.test.database:asvsj_test}?createDatabaseIfNotExist=true&useUnicode=true&characterEncoding=utf8&connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true",
        "app.places.osm.enabled=false", "app.moderation.retry-initial-delay-ms=3600000",
        "app.media.local-dir=target/test-guide-uploads"
})
@AutoConfigureMockMvc
@ActiveProfiles("local")
@Transactional
class HomeGuideFlowTest {

    private static final String LONG_SUMMARY = "Chuẩn bị giấy tờ trước khi về nước. ".repeat(6);

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    @Autowired GuideService guideService;
    @MockBean ImageModerator imageModerator;

    private AppUserPrincipal moderator;
    private long g1;
    private long g2;
    private long g3;
    private long draft;
    private long deleted;
    private long taskA;
    private long taskB;

    @BeforeEach
    void guides() {
        // Guides of other runs in LEAVE are hidden by id (row locks only, rolled back with the test)
        List<Long> visible = jdbc.queryForList(
                "SELECT id FROM guide_items WHERE deleted_at IS NULL AND stage_code = 'LEAVE'", Long.class);
        if (!visible.isEmpty()) {
            new NamedParameterJdbcTemplate(jdbc).update(
                    "UPDATE guide_items SET deleted_at = NOW() WHERE id IN (:ids)", Map.of("ids", visible));
        }
        moderator = principal(user("MODERATOR", null));
        g1 = guide("G1", 1, "Tóm tắt G1");
        g2 = guide("G2", 2, "Tóm tắt G2", "Hủy hợp đồng nhà", "Đóng tài khoản ngân hàng");
        g3 = guide("G3", 3, LONG_SUMMARY);
        List<Long> tasks = jdbc.queryForList(
                "SELECT id FROM guide_preparation_tasks WHERE guide_id = ? ORDER BY sort_order, id", Long.class, g2);
        taskA = tasks.get(0);
        taskB = tasks.get(1);
        // Never shown: a draft and a deleted guide, both ahead of the others in guide order
        draft = guide("Nháp", 0, null);
        jdbc.update("UPDATE guide_items SET published = FALSE WHERE id = ?", draft);
        deleted = guide("Đã xóa", 0, null);
        jdbc.update("UPDATE guide_items SET deleted_at = NOW() WHERE id = ?", deleted);
    }

    // ---------------------------------------------------------------- "Việc tiếp theo" and "Dành cho bạn"

    @Test
    void nextIsTheFirstGuideNotDoneAndForYouListsTheOthers() throws Exception {
        long me = user("USER", "LEAVE");
        JsonNode home = home(me);
        JsonNode guide = home.get("guide");
        assertThat(guide.get("stageCode").asText()).isEqualTo("LEAVE");
        assertThat(guide.get("stageTitle").asText()).isEqualTo("Trước khi về nước");
        assertThat(guide.get("total").asInt()).isEqualTo(3);
        assertThat(guide.get("done").asInt()).isZero();
        JsonNode next = guide.get("next");
        assertThat(next.get("guideId").asLong()).isEqualTo(g1);
        assertThat(next.get("title").asText()).isEqualTo("G1");
        assertThat(next.get("checklist").asBoolean()).isFalse();
        assertThat(next.get("tasksTotal").asInt()).isZero();
        assertThat(next.get("nextTask").isNull()).isTrue();

        JsonNode cards = home.get("stageGuides");
        assertThat(ids(cards)).containsExactly(g2, g3);
        JsonNode checklist = cards.get(0);
        assertThat(checklist.get("checklist").asBoolean()).isTrue();
        assertThat(checklist.get("tasksDone").asInt()).isZero();
        assertThat(checklist.get("tasksTotal").asInt()).isEqualTo(2);
        assertThat(checklist.get("percent").asInt()).isZero();
        assertThat(checklist.get("nextTask").asText()).isEqualTo("Hủy hợp đồng nhà");
        assertThat(checklist.get("inProgress").asBoolean()).isFalse();
        assertThat(checklist.get("topicName").asText()).isEqualTo("Sinh hoạt");
        assertThat(checklist.get("excerpt").asText()).isEqualTo("Tóm tắt G2");
        // The summary is cut to about 120 characters of plain text
        String excerpt = cards.get(1).get("excerpt").asText();
        assertThat(excerpt).startsWith("Chuẩn bị giấy tờ trước khi về nước.").endsWith("…").doesNotContain("  ");
        assertThat(excerpt.length()).isLessThanOrEqualTo(121);
        assertThat(cards.get(1).get("checklist").asBoolean()).isFalse();
    }

    @Test
    void aStartedChecklistIsNextAndTheOthersFollowInGuideOrder() throws Exception {
        long me = user("USER", "LEAVE");
        tick(me, g2, taskA);
        JsonNode home = home(me);
        JsonNode guide = home.get("guide");
        assertThat(guide.get("done").asInt()).isZero();
        JsonNode next = guide.get("next");
        assertThat(next.get("guideId").asLong()).isEqualTo(g2);
        assertThat(next.get("checklist").asBoolean()).isTrue();
        assertThat(next.get("tasksDone").asInt()).isEqualTo(1);
        assertThat(next.get("tasksTotal").asInt()).isEqualTo(2);
        assertThat(next.get("nextTask").asText()).isEqualTo("Đóng tài khoản ngân hàng");
        assertThat(ids(home.get("stageGuides"))).containsExactly(g1, g3);
    }

    @Test
    void startedChecklistsLeadTheGuidesOfForYou() throws Exception {
        long g4 = guide("G4", 4, null, "Trả thẻ cư trú", "Báo chuyển đi");
        long g4First = jdbc.queryForObject(
                "SELECT MIN(id) FROM guide_preparation_tasks WHERE guide_id = ?", Long.class, g4);
        long me = user("USER", "LEAVE");
        tick(me, g2, taskA);
        tick(me, g4, g4First);
        JsonNode home = home(me);
        assertThat(home.at("/guide/next/guideId").asLong()).isEqualTo(g2);       // the first started one
        JsonNode cards = home.get("stageGuides");
        assertThat(ids(cards)).containsExactly(g4, g1, g3);
        assertThat(cards.get(0).get("inProgress").asBoolean()).isTrue();
        assertThat(cards.get(0).get("percent").asInt()).isEqualTo(50);
        assertThat(cards.get(0).get("nextTask").asText()).isEqualTo("Báo chuyển đi");
        assertThat(cards.get(0).get("excerpt").isNull()).isTrue();             // no summary
    }

    @Test
    void finishedGuidesCountAndTheNextOneMovesOn() throws Exception {
        long me = user("USER", "LEAVE");
        markDone(me, g1, null);
        tick(me, g2, taskA);
        tick(me, g2, taskB);
        JsonNode home = home(me);
        assertThat(home.at("/guide/done").asInt()).isEqualTo(2);
        assertThat(home.at("/guide/total").asInt()).isEqualTo(3);
        assertThat(home.at("/guide/next/guideId").asLong()).isEqualTo(g3);
        assertThat(ids(home.get("stageGuides"))).isEmpty();                  // G3 is already "Việc tiếp theo"

        markDone(me, g3, null);
        home = home(me);
        assertThat(home.at("/guide/done").asInt()).isEqualTo(3);
        assertThat(home.at("/guide/total").asInt()).isEqualTo(3);
        assertThat(home.get("guide").get("next").isNull()).isTrue();
        assertThat(ids(home.get("stageGuides"))).isEmpty();
    }

    @Test
    void aDoneMarkDoesNotFinishAChecklist() throws Exception {
        long me = user("USER", "LEAVE");
        markDone(me, g2, null);           // "Đánh dấu đã xong" counts only for guides without a checklist
        JsonNode home = home(me);
        assertThat(home.at("/guide/done").asInt()).isZero();
        assertThat(home.at("/guide/next/guideId").asLong()).isEqualTo(g1);
        assertThat(ids(home.get("stageGuides"))).containsExactly(g2, g3);
    }

    @Test
    void withoutAStageNextFollowsArrivalAndForYouHasNoGuides() throws Exception {
        long me = user("USER", null);
        JsonNode home = home(me);
        assertThat(home.get("stage").isNull()).isTrue();
        assertThat(home.at("/guide/stageCode").asText()).isEqualTo("ARR");
        assertThat(home.at("/guide/stageTitle").asText()).isEqualTo("Khi mới đến (tuần đầu đến 3 tháng)");
        assertThat(home.get("stageGuides").isArray()).isTrue();
        assertThat(ids(home.get("stageGuides"))).isEmpty();
    }

    // ---------------------------------------------------------------- "Cẩm nang của tôi"

    @Test
    void myGuidesSumsUpEachStageAndListsStartedAndFinishedGuides() throws Exception {
        long me = user("USER", "LEAVE");
        JsonNode data = myGuides(me);
        assertThat(data.get("stage").asText()).isEqualTo("LEAVE");
        List<String> codes = new ArrayList<>();
        data.get("stages").forEach(s -> codes.add(s.get("code").asText()));
        assertThat(codes).containsExactly("PRE", "ARR", "LEAVE", "HOME");
        JsonNode leave = data.get("stages").get(2);
        assertThat(leave.get("title").asText()).isEqualTo("Trước khi về nước");
        assertThat(leave.get("done").asInt()).isZero();
        assertThat(leave.get("total").asInt()).isEqualTo(3);
        List<Boolean> current = new ArrayList<>();
        data.get("stages").forEach(s -> current.add(s.get("current").asBoolean()));
        assertThat(current).containsExactly(false, false, true, false);
        assertThat(ids(data.get("inProgress"))).isEmpty();
        assertThat(ids(data.get("recentlyDone"))).isEmpty();

        tick(me, g2, taskA);
        data = myGuides(me);
        assertThat(ids(data.get("inProgress"))).containsExactly(g2);
        JsonNode started = data.get("inProgress").get(0);
        assertThat(started.get("title").asText()).isEqualTo("G2");
        assertThat(started.get("stageCode").asText()).isEqualTo("LEAVE");
        assertThat(started.get("stageTitle").asText()).isEqualTo("Trước khi về nước");
        assertThat(started.get("tasksDone").asInt()).isEqualTo(1);
        assertThat(started.get("tasksTotal").asInt()).isEqualTo(2);
        assertThat(started.get("percent").asInt()).isEqualTo(50);
        assertThat(started.get("nextTask").asText()).isEqualTo("Đóng tài khoản ngân hàng");
        assertThat(started.get("lastActivityAt").isNull()).isFalse();
        assertThat(ids(data.get("recentlyDone"))).isEmpty();

        // G1 finished first, then G3, then G2's last task; marks on a draft or deleted guide never count
        markDone(me, g1, Instant.parse("2026-01-01T00:00:00Z"));
        markDone(me, g3, Instant.parse("2026-02-01T00:00:00Z"));
        markDone(me, draft, Instant.parse("2026-03-01T00:00:00Z"));
        markDone(me, deleted, Instant.parse("2026-03-01T00:00:00Z"));
        tick(me, g2, taskB);
        jdbc.update("UPDATE guide_preparation_progress SET completed_at = ? WHERE user_id = ?",
                Timestamp.from(Instant.parse("2026-03-01T00:00:00Z")), me);
        data = myGuides(me);
        assertThat(ids(data.get("inProgress"))).isEmpty();
        assertThat(ids(data.get("recentlyDone"))).containsExactly(g2, g3, g1);
        JsonNode last = data.get("recentlyDone").get(0);
        assertThat(last.get("stageTitle").asText()).isEqualTo("Trước khi về nước");
        assertThat(Instant.parse(last.get("doneAt").asText())).isEqualTo(Instant.parse("2026-03-01T00:00:00Z"));
        leave = data.get("stages").get(2);
        assertThat(leave.get("done").asInt()).isEqualTo(3);
        assertThat(leave.get("total").asInt()).isEqualTo(3);
    }

    @Test
    void myGuidesIsForMembersOnly() throws Exception {
        long staff = user("MODERATOR", "LEAVE");
        mvc.perform(get("/api/me/guides/progress").session(session(staff)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value("Bạn không có quyền thực hiện thao tác này."));
        mvc.perform(get("/api/me/guides/progress")).andExpect(status().isUnauthorized());
    }

    // ---------------------------------------------------------------- helpers

    private long user(String role, String stage) {
        String tag = UUID.randomUUID().toString();
        jdbc.update("""
                INSERT INTO users(email, display_name, role, status, is_adult_confirmed, stage)
                VALUES (?, ?, ?, 'ACTIVE', TRUE, ?)""", tag + "@test.local", "Guide " + tag.substring(0, 8), role, stage);
        return jdbc.queryForObject("SELECT id FROM users WHERE email = ?", Long.class, tag + "@test.local");
    }

    private AppUserPrincipal principal(long userId) {
        var row = jdbc.queryForMap("SELECT email, display_name, role FROM users WHERE id = ?", userId);
        return new AppUserPrincipal(userId, (String) row.get("email"), (String) row.get("display_name"),
                (String) row.get("role"), "/");
    }

    private MockHttpSession session(long userId) {
        AppUserPrincipal principal = principal(userId);
        var auth = new UsernamePasswordAuthenticationToken(principal, null,
                List.of(new SimpleGrantedAuthority("ROLE_" + principal.role())));
        var session = new MockHttpSession();
        session.setAttribute("SPRING_SECURITY_CONTEXT", new SecurityContextImpl(auth));
        return session;
    }

    /** A published LEAVE guide (topic "Sinh hoạt") at {@code sortOrder}; with tasks it has a checklist. */
    private long guide(String title, int sortOrder, String summary, String... tasks) {
        var request = new GuideRequest(title, summary, "LEAVE",
                List.of(new GuideRequest.Section("Nội dung", "Content", null, null)),
                "LIVING", null, "PUBLISHED", null, false, tasks.length > 0,
                Arrays.stream(tasks).map(t -> new GuideRequest.PreparationTask(null, t, null)).toList());
        long id = guideService.create(request, moderator).id();
        jdbc.update("UPDATE guide_items SET sort_order = ? WHERE id = ?", sortOrder, id);
        return id;
    }

    /** Ticks a checklist task the way the guide page does. */
    private void tick(long userId, long guideId, long taskId) throws Exception {
        mvc.perform(put("/api/guides/" + guideId + "/preparation/" + taskId).session(session(userId))
                        .cookie(new Cookie("XSRF-TOKEN", "token")).header("X-XSRF-TOKEN", "token")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"completed\":true}"))
                .andExpect(status().isOk());
    }

    /** "Đánh dấu đã xong" (written by the guide page's API, which is not part of this test). */
    private void markDone(long userId, long guideId, Instant at) {
        if (at == null) {
            jdbc.update("INSERT INTO user_guide_progress(user_id, item_id) VALUES (?, ?)", userId, guideId);
        } else {
            jdbc.update("INSERT INTO user_guide_progress(user_id, item_id, done_at) VALUES (?, ?, ?)", userId, guideId,
                    Timestamp.from(at));
        }
    }

    private JsonNode home(long userId) throws Exception {
        byte[] body = mvc.perform(get("/api/me/home").session(session(userId))).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray();
        return json.readTree(body).get("data");
    }

    private JsonNode myGuides(long userId) throws Exception {
        byte[] body = mvc.perform(get("/api/me/guides/progress").session(session(userId))).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray();
        return json.readTree(body).get("data");
    }

    private static List<Long> ids(JsonNode array) {
        List<Long> ids = new ArrayList<>();
        array.forEach(item -> ids.add(item.get("id").asLong()));
        return ids;
    }
}
