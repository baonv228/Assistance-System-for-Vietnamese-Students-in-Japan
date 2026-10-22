package com.sep490.vsa.feature.qa;

import com.sep490.vsa.common.security.AppUserPrincipal;
import com.sep490.vsa.feature.moderation.ImageModerator;
import com.sep490.vsa.feature.moderation.ImageModerator.Verdict;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.is;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.security.web.context.HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Hỏi-Đáp end to end against MySQL (own database {@code asvsj_test}, like BlogWriteFlowTest). Every test tags its
 * titles with a random word and searches for it, so earlier runs never change the result.
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:mysql://localhost:3306/asvsj_test?createDatabaseIfNotExist=true&useUnicode=true&characterEncoding=utf8&connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true",
        "app.media.local-dir=target/test-uploads",
        "app.moderation.retry-initial-delay-ms=3600000",
        "app.places.osm.enabled=false"              // ASVSJ places only: no calls to OpenStreetMap from the tests
})
@AutoConfigureMockMvc
@ActiveProfiles("local")
class QaFlowTest {

    private static final String CSRF = "test-csrf-token";

    @Autowired
    private MockMvc mvc;
    @Autowired
    private JdbcTemplate jdbc;
    @MockBean
    private ImageModerator moderator;

    private String word;       // random word in every title of this test
    private long asker;
    private long helper;       // answers questions
    private long other;
    private long topic;
    private long scamTopic;
    private long place;

    @BeforeEach
    void setUp() {
        when(moderator.engine()).thenReturn("OPENAI");
        when(moderator.check(any(byte[].class))).thenReturn(new Verdict("PASS", "{}"));
        word = "qa" + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        asker = user();
        helper = user();
        other = user();
        topic = jdbc.queryForObject("SELECT id FROM blog_topics WHERE name = 'Visa và cư trú'", Long.class);
        scamTopic = jdbc.queryForObject("SELECT id FROM blog_topics WHERE name = 'Cảnh báo lừa đảo'", Long.class);
        place = jdbc.queryForObject("SELECT MIN(id) FROM places WHERE status = 'ACTIVE'", Long.class);
    }

    @Test
    void questionsAndPostsHaveSeparateListsAndPages() throws Exception {
        long question = ask(asker, "Gia hạn visa " + word + "?", "");
        long blogPost = write(asker, "Kinh nghiệm gia hạn visa " + word);

        mvc.perform(get("/api/questions").param("q", word))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[*].id").value(containsInAnyOrder((int) question)))
                .andExpect(jsonPath("$.data.items[0].solved").value(false))
                .andExpect(jsonPath("$.data.filtered").value(true));
        mvc.perform(get("/api/blogs").param("q", word))
                .andExpect(jsonPath("$.data.items[*].id").value(containsInAnyOrder((int) blogPost)));

        // Each kind opens only on its own page and accepts only its own actions
        mvc.perform(get("/api/questions/" + blogPost)).andExpect(status().isNotFound());
        mvc.perform(get("/api/blogs/" + question)).andExpect(status().isNotFound());
        mvc.perform(withCsrf(post("/api/blogs/" + question + "/like"), other)).andExpect(status().isNotFound());
        mvc.perform(withCsrf(post("/api/questions/" + blogPost + "/answers"), other)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"content\":\"trả lời\"}"))
                .andExpect(status().isNotFound());
        mvc.perform(withCsrf(post("/api/questions/" + question + "/like"), other))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.count").value(1));
    }

    @Test
    void answeringAndChoosingTheBestAnswerMovesTheQuestionBetweenFilters() throws Exception {
        long question = ask(asker, "Mất thẻ cư trú " + word + " thì làm gì?", "Mình ở Arakawa.");
        assertThat(ids("UNANSWERED")).containsExactly(question);
        assertThat(ids("UNSOLVED")).containsExactly(question);
        assertThat(ids("SOLVED")).isEmpty();

        // An answer, then the asker replies under it
        mvc.perform(withCsrf(post("/api/questions/" + question + "/answers"), helper)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"content\":\"Báo cảnh sát trước, rồi lên văn phòng quận.\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Đã đăng câu trả lời."))
                .andExpect(jsonPath("$.data.comments[0].content").value("Báo cảnh sát trước, rồi lên văn phòng quận."));
        long answer = jdbc.queryForObject("SELECT id FROM comments WHERE blog_id = ? AND parent_id IS NULL", Long.class, question);
        mvc.perform(withCsrf(post("/api/questions/" + question + "/answers"), asker)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"content\":\"Cảm ơn bạn!\",\"parentId\":" + answer + "}"))
                .andExpect(jsonPath("$.message").value("Đã gửi phản hồi."));
        assertThat(jdbc.queryForObject("SELECT ref_type FROM notifications WHERE user_id = ? ORDER BY id DESC LIMIT 1",
                String.class, asker)).isEqualTo("QUESTION");
        assertThat(ids("UNANSWERED")).isEmpty();
        assertThat(ids("UNSOLVED")).containsExactly(question);

        // Only the asker chooses; the helper gets +5 points and a notification that opens the question
        mvc.perform(withCsrf(put("/api/questions/" + question + "/best-answer"), other)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"answerId\":" + answer + "}"))
                .andExpect(status().isForbidden());
        mvc.perform(withCsrf(put("/api/questions/" + question + "/best-answer"), asker)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"answerId\":" + answer + "}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.bestCommentId").value((int) answer));
        assertThat(jdbc.queryForObject("SELECT points FROM users WHERE id = ?", Integer.class, helper)).isEqualTo(5);
        assertThat(jdbc.queryForObject("SELECT ref_type FROM notifications WHERE user_id = ? AND type = 'BEST_ANSWER'",
                String.class, helper)).isEqualTo("QUESTION");
        assertThat(ids("SOLVED")).containsExactly(question);
        assertThat(ids("UNSOLVED")).isEmpty();
        mvc.perform(get("/api/questions").param("q", word)).andExpect(jsonPath("$.data.items[0].solved").value(true))
                .andExpect(jsonPath("$.data.items[0].answerCount").value(2));

        mvc.perform(get("/api/questions/overview"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.stats.solved").value(org.hamcrest.Matchers.greaterThanOrEqualTo(1)))
                .andExpect(jsonPath("$.data.topics[?(@.name == 'Khác')]").exists())
                .andExpect(jsonPath("$.data.topAnswerersDays").value(30));

        // The best answer is hidden later (reports): the list no longer calls the question solved, like its page
        jdbc.update("UPDATE comments SET status = 'HIDDEN' WHERE id = ?", answer);
        assertThat(ids("SOLVED")).isEmpty();
        assertThat(ids("UNSOLVED")).containsExactly(question);
        mvc.perform(get("/api/questions").param("q", word)).andExpect(jsonPath("$.data.items[0].solved").value(false));
        mvc.perform(get("/api/questions/" + question)).andExpect(jsonPath("$.data.bestCommentId").isEmpty());
    }

    @Test
    void aHiddenAnswerKeptOnlyByAReplyLeavesTheQuestionUnanswered() throws Exception {
        long question = ask(asker, "Đổi bằng lái " + word + " ở đâu?", "");
        mvc.perform(withCsrf(post("/api/questions/" + question + "/answers"), helper)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"content\":\"Ở trung tâm sát hạch.\"}"))
                .andExpect(status().isOk());
        long answer = jdbc.queryForObject("SELECT id FROM comments WHERE blog_id = ? AND parent_id IS NULL", Long.class, question);
        mvc.perform(withCsrf(post("/api/questions/" + question + "/answers"), other)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"Mình cũng muốn biết\",\"parentId\":" + answer + "}"))
                .andExpect(status().isOk());
        assertThat(ids("UNANSWERED")).isEmpty();
        long unansweredBefore = unansweredCount();
        mvc.perform(get("/api/questions").param("q", word)).andExpect(jsonPath("$.data.items[0].unanswered").value(false));

        // The only answer is hidden by reports; its reply is still published
        jdbc.update("UPDATE comments SET status = 'HIDDEN' WHERE id = ?", answer);
        assertThat(ids("UNANSWERED")).containsExactly(question);
        assertThat(ids("UNSOLVED")).containsExactly(question);
        assertThat(unansweredCount()).isEqualTo(unansweredBefore + 1);
        // The card's "Chưa ai trả lời" follows the same rule, although answerCount is not 0 (the reply counts there)
        mvc.perform(get("/api/questions").param("q", word))
                .andExpect(jsonPath("$.data.items[0].unanswered").value(true))
                .andExpect(jsonPath("$.data.items[0].answerCount").value(org.hamcrest.Matchers.greaterThan(0)));

        // A reply alone never makes a question answered; the asker's own answer does (no rule change)
        mvc.perform(withCsrf(post("/api/questions/" + question + "/answers"), asker)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"content\":\"Mình tự tìm được rồi.\"}"))
                .andExpect(status().isOk());
        assertThat(ids("UNANSWERED")).isEmpty();
        assertThat(unansweredCount()).isEqualTo(unansweredBefore);
    }

    @Test
    void askerDeletesTheirQuestion() throws Exception {
        long question = ask(asker, "Câu hỏi sẽ xóa " + word, "");
        mvc.perform(withCsrf(delete("/api/questions/" + question), other)).andExpect(status().isForbidden());
        mvc.perform(withCsrf(delete("/api/blogs/" + question), asker)).andExpect(status().isNotFound());
        mvc.perform(withCsrf(delete("/api/questions/" + question), asker))
                .andExpect(status().isOk()).andExpect(jsonPath("$.message").value("Đã xóa câu hỏi."));
        assertThat(ids("ALL")).isEmpty();
    }

    @Test
    void askingUsesTheSameChecksAsWriting() throws Exception {
        // Required question, banned keyword, guests
        mvc.perform(withCsrf(post("/api/questions"), asker).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"\",\"content\":\"\",\"topicIds\":[" + topic + "]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.data.title").value("Hãy nhập câu hỏi."));
        mvc.perform(withCsrf(post("/api/questions"), asker).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"Ai biết chỗ vay nóng " + word + "?\",\"topicIds\":[" + topic + "]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("“vay nóng”")));
        mvc.perform(post("/api/questions").contentType(MediaType.APPLICATION_JSON)
                        .cookie(new Cookie("XSRF-TOKEN", CSRF)).header("X-XSRF-TOKEN", CSRF)
                        .content("{\"title\":\"t\",\"topicIds\":[" + topic + "]}"))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/questions/compose").session(session(asker)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.limits.titleMax").value(150));
        // The place search is shared with Write Post and needs a signed-in user (fair use of the map services)
        mvc.perform(get("/api/places/search").param("q", "nippori")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/places/search").param("q", "nippori").session(session(asker)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.items").isArray());
    }

    @Test
    void aQuestionAboutAScamIsNotAScamWarning() throws Exception {
        long question = created(mvc.perform(withCsrf(post("/api/questions"), asker).contentType(MediaType.APPLICATION_JSON)
                .content("{\"title\":\"Chỗ này có lừa đảo không " + word + "?\",\"topicIds\":[" + scamTopic
                        + "],\"placeIds\":[" + place + "]}")));
        mvc.perform(get("/api/blogs/scam-warnings").param("placeId", String.valueOf(place)))
                .andExpect(jsonPath("$.data[*].blogId", not(hasItem((int) question))));
    }

    @Test
    void listValidatesItsFilters() throws Exception {
        mvc.perform(get("/api/questions").param("status", "CLOSED")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/questions").param("q", word).param("status", "SOLVED"))
                .andExpect(jsonPath("$.data.items", is(empty())));
        mvc.perform(get("/api/questions").param("size", "5"))
                .andExpect(jsonPath("$.data.items[*].id", everyItem(org.hamcrest.Matchers.notNullValue())));
    }

    // ---------------------------------------------------------------- helpers

    private long user() {
        String email = "qa-" + UUID.randomUUID() + "@test.local";
        jdbc.update("""
                INSERT INTO users (email, display_name, role, status, is_adult_confirmed, created_at)
                VALUES (?, 'QA Test', 'USER', 'ACTIVE', TRUE, ?)""",
                email, Timestamp.from(Instant.now().minus(Duration.ofDays(30))));
        return jdbc.queryForObject("SELECT id FROM users WHERE email = ?", Long.class, email);
    }

    private static MockHttpSession session(long userId) {
        AppUserPrincipal me = new AppUserPrincipal(userId, "u" + userId + "@test.local", "QA Test", "USER", "/");
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(SPRING_SECURITY_CONTEXT_KEY, new SecurityContextImpl(
                new UsernamePasswordAuthenticationToken(me, null, List.of(new SimpleGrantedAuthority("ROLE_USER")))));
        return session;
    }

    private static MockHttpServletRequestBuilder withCsrf(MockHttpServletRequestBuilder request, long userId) {
        return request.session(session(userId)).cookie(new Cookie("XSRF-TOKEN", CSRF)).header("X-XSRF-TOKEN", CSRF);
    }

    private long ask(long userId, String title, String content) throws Exception {
        return created(mvc.perform(withCsrf(post("/api/questions"), userId).contentType(MediaType.APPLICATION_JSON)
                .content("{\"title\":\"" + title + "\",\"content\":\"" + content + "\",\"topicIds\":[" + topic + "]}"))
                .andExpect(jsonPath("$.message").value("Câu hỏi của bạn đã được đăng.")));
    }

    private long write(long userId, String title) throws Exception {
        return created(mvc.perform(withCsrf(post("/api/blogs"), userId).contentType(MediaType.APPLICATION_JSON)
                .content("{\"title\":\"" + title + "\",\"content\":\"nội dung\",\"topicIds\":[" + topic + "]}")));
    }

    private static long created(ResultActions result) throws Exception {
        String body = result.andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return Long.parseLong(body.replaceAll(".*\"data\":\\{\"id\":(\\d+).*", "$1"));
    }

    private long unansweredCount() throws Exception {
        String body = mvc.perform(get("/api/questions/overview")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return ((Number) com.jayway.jsonpath.JsonPath.read(body, "$.data.stats.unanswered")).longValue();
    }

    /** Ids of this test's questions shown with the given status filter. */
    private List<Long> ids(String status) throws Exception {
        String body = mvc.perform(get("/api/questions").param("q", word).param("status", status))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        List<Integer> found = com.jayway.jsonpath.JsonPath.read(body, "$.data.items[*].id");
        return found.stream().map(Integer::longValue).toList();
    }
}
