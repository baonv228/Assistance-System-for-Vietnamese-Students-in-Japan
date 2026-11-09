package com.sep490.vsa.feature.guide;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sep490.vsa.common.exception.ApiException;
import com.sep490.vsa.common.security.AppUserPrincipal;
import com.sep490.vsa.feature.moderation.ImageModerator;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * "Lưu" and "Đánh dấu đã xong" on a guide (GuideMemberService), the viewer state of the guide page and saved guides
 * on "Đã lưu". Every test makes its own users and guides and is rolled back, so other runs never show up.
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:mysql://localhost:3306/${guide.test.database:asvsj_test}?createDatabaseIfNotExist=true&useUnicode=true&characterEncoding=utf8&connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true",
        "app.places.osm.enabled=false", "app.moderation.retry-initial-delay-ms=3600000",
        "app.media.local-dir=target/test-guide-uploads"
})
@AutoConfigureMockMvc
@ActiveProfiles("local")
@Transactional
class GuideMemberFlowTest {

    private static final String NO_PERMISSION = "Bạn không có quyền thực hiện thao tác này.";

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    @Autowired GuideService guideService;
    @Autowired GuideMemberService memberService;
    @MockBean ImageModerator imageModerator;

    private MockHttpSession moderator;

    @BeforeEach
    void setUp() {
        when(imageModerator.engine()).thenReturn("OPENAI");
        when(imageModerator.check(any())).thenReturn(new ImageModerator.Verdict("PASS", "{}"));
        moderator = session("MODERATOR", "ACTIVE");
    }

    @Test
    void guestsSeeTheGuideButMustSignInToSaveOrMarkIt() throws Exception {
        long guide = ordinaryGuide("Guest guide", "Summary");
        mvc.perform(get("/api/guides/" + guide + "/viewer")).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.signedIn").value(false))
                .andExpect(jsonPath("$.data.member").value(false))
                .andExpect(jsonPath("$.data.saved").value(false))
                .andExpect(jsonPath("$.data.progress.checklist").value(false))
                .andExpect(jsonPath("$.data.progress.done").value(false))
                .andExpect(jsonPath("$.data.progress.percent").value(0));
        for (var call : List.of(post(path(guide, "save")), delete(path(guide, "save")),
                post(path(guide, "done")), delete(path(guide, "done")))) {
            mvc.perform(csrf(call)).andExpect(status().isUnauthorized());
        }
        mvc.perform(get("/api/guides/9223372036854775807/viewer")).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Không tìm thấy cẩm nang."));
    }

    @Test
    void staffAndLockedAccountsCannotSaveOrMarkGuides() throws Exception {
        long guide = ordinaryGuide("Staff guide", "Summary");
        mvc.perform(get("/api/guides/" + guide + "/viewer").session(moderator)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.signedIn").value(true))
                .andExpect(jsonPath("$.data.member").value(false))
                .andExpect(jsonPath("$.data.saved").value(false));
        for (var call : List.of(post(path(guide, "save")), post(path(guide, "done")))) {
            mvc.perform(csrf(call).session(moderator)).andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.message").value(NO_PERMISSION));
        }

        // A locked account's session ends on its next request (SessionUserRefreshFilter): it acts as a guest...
        var locked = session("USER", "LOCKED");
        mvc.perform(csrf(post(path(guide, "save"))).session(locked)).andExpect(status().isUnauthorized());
        // ...and the service itself still refuses it (MSG18) if one ever reached it
        assertThatThrownBy(() -> memberService.save(guide, principal(session("USER", "LOCKED")), true))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.FORBIDDEN));
        assertThatThrownBy(() -> memberService.markDone(guide, principal(session("USER", "LOCKED")), true))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.FORBIDDEN));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM saved_guides WHERE guide_id = ?", Integer.class, guide))
                .isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM user_guide_progress WHERE item_id = ?", Integer.class,
                guide)).isZero();
    }

    @Test
    void memberSavesAGuideOnceAndFindsItInSaved() throws Exception {
        var member = session("USER", "ACTIVE");
        String summary = "Dòng một\n\nDòng hai " + "x".repeat(300);
        long guide = ordinaryGuide("Saved guide", summary);
        int before = savedCount(member);

        for (int i = 0; i < 2; i++) {
            mvc.perform(csrf(post(path(guide, "save"))).session(member)).andExpect(status().isOk())
                    .andExpect(jsonPath("$.message").value("Đã lưu cẩm nang."))
                    .andExpect(jsonPath("$.data.saved").value(true));
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM saved_guides WHERE user_id = ? AND guide_id = ?",
                Integer.class, principal(member).id(), guide)).isEqualTo(1);
        mvc.perform(get("/api/guides/" + guide + "/viewer").session(member))
                .andExpect(jsonPath("$.data.signedIn").value(true))
                .andExpect(jsonPath("$.data.member").value(true))
                .andExpect(jsonPath("$.data.saved").value(true));

        JsonNode saved = data(mvc.perform(get("/api/me/saved").session(member)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray());
        JsonNode item = saved.get("guides").get(0);
        assertThat(saved.get("guides")).hasSize(1);
        assertThat(item.get("id").asLong()).isEqualTo(guide);
        assertThat(item.get("title").asText()).isEqualTo("Saved guide");
        assertThat(item.get("stageName").asText())
                .isEqualTo(jdbc.queryForObject("SELECT title FROM guide_stages WHERE code = 'PRE'", String.class));
        assertThat(item.get("topicName").asText())
                .isEqualTo(jdbc.queryForObject("SELECT title FROM guide_topics WHERE code = 'LIVING'", String.class));
        String excerpt = item.get("summary").asText();
        assertThat(excerpt).startsWith("Dòng một Dòng hai xxx").endsWith("…").hasSize(160);
        assertThat(item.get("savedAt").isNull()).isFalse();
        assertThat(saved.get("counts").get("guides").asInt()).isEqualTo(1);
        assertThat(saved.get("counts").get("total").asInt()).isEqualTo(1);
        assertThat(savedCount(member)).isEqualTo(before + 1);

        for (int i = 0; i < 2; i++) {
            mvc.perform(csrf(delete(path(guide, "save"))).session(member)).andExpect(status().isOk())
                    .andExpect(jsonPath("$.message").value("Đã bỏ lưu cẩm nang."))
                    .andExpect(jsonPath("$.data.saved").value(false));
        }
        mvc.perform(get("/api/me/saved").session(member))
                .andExpect(jsonPath("$.data.guides", empty()))
                .andExpect(jsonPath("$.data.counts.guides").value(0));
        mvc.perform(get("/api/guides/" + guide + "/viewer").session(member))
                .andExpect(jsonPath("$.data.saved").value(false));
        assertThat(savedCount(member)).isEqualTo(before);
    }

    @Test
    void draftAndDeletedGuidesAreNotFoundAndLeaveSaved() throws Exception {
        var member = session("USER", "ACTIVE");
        long deleted = ordinaryGuide("Deleted later", "Summary");
        long draft = ordinaryGuide("Draft later", "Summary");
        mvc.perform(csrf(post(path(deleted, "save"))).session(member)).andExpect(status().isOk());
        mvc.perform(csrf(post(path(draft, "save"))).session(member)).andExpect(status().isOk());
        assertThat(savedCount(member)).isEqualTo(2);

        jdbc.update("UPDATE guide_items SET deleted_at = NOW() WHERE id = ?", deleted);
        jdbc.update("UPDATE guide_items SET published = FALSE WHERE id = ?", draft);
        for (long guide : List.of(deleted, draft)) {
            mvc.perform(get("/api/guides/" + guide + "/viewer").session(member)).andExpect(status().isNotFound());
            for (var call : List.of(post(path(guide, "save")), delete(path(guide, "save")),
                    post(path(guide, "done")), delete(path(guide, "done")))) {
                mvc.perform(csrf(call).session(member)).andExpect(status().isNotFound())
                        .andExpect(jsonPath("$.message").value("Không tìm thấy cẩm nang."));
            }
        }
        mvc.perform(get("/api/me/saved").session(member))
                .andExpect(jsonPath("$.data.guides", empty()))
                .andExpect(jsonPath("$.data.counts.guides").value(0))
                .andExpect(jsonPath("$.data.counts.total").value(0));
        assertThat(savedCount(member)).isZero();
        // The rows were kept: published again, the guide is back on "Đã lưu"
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM saved_guides WHERE user_id = ?", Integer.class,
                principal(member).id())).isEqualTo(2);
        jdbc.update("UPDATE guide_items SET published = TRUE WHERE id = ?", draft);
        mvc.perform(get("/api/me/saved").session(member))
                .andExpect(jsonPath("$.data.guides[0].id").value(draft))
                .andExpect(jsonPath("$.data.counts.guides").value(1));
    }

    @Test
    void memberMarksAGuideWithoutChecklistDoneAndUndoesIt() throws Exception {
        var member = session("USER", "ACTIVE");
        long guide = ordinaryGuide("Done guide", "Summary");
        long userId = principal(member).id();
        for (int i = 0; i < 2; i++) {
            mvc.perform(csrf(post(path(guide, "done"))).session(member)).andExpect(status().isOk())
                    .andExpect(jsonPath("$.message").value("Đã đánh dấu xong cẩm nang."))
                    .andExpect(jsonPath("$.data.checklist").value(false))
                    .andExpect(jsonPath("$.data.done").value(true))
                    .andExpect(jsonPath("$.data.percent").value(100))
                    .andExpect(jsonPath("$.data.tasksTotal").value(0))
                    .andExpect(jsonPath("$.data.nextTask").value(nullValue()));
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM user_guide_progress WHERE user_id = ? AND item_id = ?",
                Integer.class, userId, guide)).isEqualTo(1);
        mvc.perform(get("/api/guides/" + guide + "/viewer").session(member))
                .andExpect(jsonPath("$.data.progress.done").value(true));
        // Another member's progress is their own
        mvc.perform(get("/api/guides/" + guide + "/viewer").session(session("USER", "ACTIVE")))
                .andExpect(jsonPath("$.data.progress.done").value(false));

        for (int i = 0; i < 2; i++) {
            mvc.perform(csrf(delete(path(guide, "done"))).session(member)).andExpect(status().isOk())
                    .andExpect(jsonPath("$.message").value("Đã bỏ đánh dấu đã xong."))
                    .andExpect(jsonPath("$.data.done").value(false))
                    .andExpect(jsonPath("$.data.percent").value(0));
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM user_guide_progress WHERE user_id = ? AND item_id = ?",
                Integer.class, userId, guide)).isZero();
    }

    @Test
    void aGuideWithAChecklistIsDoneWhenEveryTaskIsTicked() throws Exception {
        var member = session("USER", "ACTIVE");
        var guide = guideService.create(preparationRequest("Checklist guide", List.of(
                new GuideRequest.PreparationTask(null, "Passport", null),
                new GuideRequest.PreparationTask(null, "Visa", "Ask the school")), true), principal(moderator));
        long id = guide.id();
        long first = guide.preparationTasks().get(0).id(), second = guide.preparationTasks().get(1).id();

        for (var call : List.of(post(path(id, "done")), delete(path(id, "done")))) {
            mvc.perform(csrf(call).session(member)).andExpect(status().isBadRequest()).andExpect(jsonPath("$.message")
                    .value("Cẩm nang này có danh sách chuẩn bị. Hãy đánh dấu từng việc trong tab “Hướng dẫn chuẩn bị”."));
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM user_guide_progress WHERE item_id = ?", Integer.class, id))
                .isZero();

        // Guests see the real checklist with nothing ticked
        mvc.perform(get("/api/guides/" + id + "/viewer")).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.progress.checklist").value(true))
                .andExpect(jsonPath("$.data.progress.tasksDone").value(0))
                .andExpect(jsonPath("$.data.progress.tasksTotal").value(2))
                .andExpect(jsonPath("$.data.progress.nextTask").value("Passport"));

        tick(member, id, first);
        mvc.perform(get("/api/guides/" + id + "/viewer").session(member))
                .andExpect(jsonPath("$.data.progress.checklist").value(true))
                .andExpect(jsonPath("$.data.progress.tasksDone").value(1))
                .andExpect(jsonPath("$.data.progress.tasksTotal").value(2))
                .andExpect(jsonPath("$.data.progress.percent").value(50))
                .andExpect(jsonPath("$.data.progress.nextTask").value("Visa"))
                .andExpect(jsonPath("$.data.progress.done").value(false));
        tick(member, id, second);
        mvc.perform(get("/api/guides/" + id + "/viewer").session(member))
                .andExpect(jsonPath("$.data.progress.tasksDone").value(2))
                .andExpect(jsonPath("$.data.progress.percent").value(100))
                .andExpect(jsonPath("$.data.progress.nextTask").value(nullValue()))
                .andExpect(jsonPath("$.data.progress.done").value(true));

        // A guide with a checklist can still be saved
        mvc.perform(csrf(post(path(id, "save"))).session(member)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.saved").value(true));
    }

    // ---------------------------------------------------------------- helpers

    private long ordinaryGuide(String title, String summary) {
        return guideService.create(new GuideRequest(title, summary, "PRE",
                List.of(new GuideRequest.Section("Step one", "Content", null, null))), principal(moderator)).id();
    }

    private GuideRequest preparationRequest(String title, List<GuideRequest.PreparationTask> tasks, boolean enabled) {
        return new GuideRequest(title, "Summary", "PRE", List.of(new GuideRequest.Section("Nội dung", "Content", null, null)),
                "LIVING", null, "PUBLISHED", null, false, enabled, tasks);
    }

    private void tick(MockHttpSession member, long guideId, long taskId) throws Exception {
        mvc.perform(csrf(put("/api/guides/" + guideId + "/preparation/" + taskId)).session(member)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"completed\":true}"))
                .andExpect(status().isOk());
    }

    private int savedCount(MockHttpSession member) throws Exception {
        return data(mvc.perform(get("/api/me/profile").session(member)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray()).get("savedCount").asInt();
    }

    private JsonNode data(byte[] body) throws Exception {
        return json.readTree(body).get("data");
    }

    private static String path(long guideId, String action) {
        return "/api/guides/" + guideId + "/" + action;
    }

    private static MockHttpServletRequestBuilder csrf(MockHttpServletRequestBuilder request) {
        return request.cookie(new Cookie("XSRF-TOKEN", "token")).header("X-XSRF-TOKEN", "token");
    }

    private MockHttpSession session(String role, String status) {
        String tag = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO users(email,display_name,role,status,is_adult_confirmed) VALUES(?,?,?,?,TRUE)",
                tag + "@test.local", tag, role, status);
        long id = jdbc.queryForObject("SELECT id FROM users WHERE email=?", Long.class, tag + "@test.local");
        var principal = new AppUserPrincipal(id, tag + "@test.local", tag, role, "USER".equals(role) ? "/" : "/moderator");
        var auth = new UsernamePasswordAuthenticationToken(principal, null,
                List.of(new SimpleGrantedAuthority("ROLE_" + role)));
        var session = new MockHttpSession();
        session.setAttribute("SPRING_SECURITY_CONTEXT", new SecurityContextImpl(auth));
        return session;
    }

    private static AppUserPrincipal principal(MockHttpSession session) {
        return (AppUserPrincipal) ((SecurityContextImpl) session.getAttribute("SPRING_SECURITY_CONTEXT"))
                .getAuthentication().getPrincipal();
    }
}
