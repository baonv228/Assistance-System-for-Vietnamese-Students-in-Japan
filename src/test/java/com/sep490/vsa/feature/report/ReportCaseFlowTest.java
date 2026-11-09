package com.sep490.vsa.feature.report;

import com.sep490.vsa.common.security.AppUserPrincipal;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.web.context.HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** "Báo cáo nội dung" (UC-M03, UC-M04) against MySQL ({@code asvsj_test}). */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:mysql://localhost:3306/asvsj_test?createDatabaseIfNotExist=true&useUnicode=true&characterEncoding=utf8&connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true",
        "app.moderation.retry-initial-delay-ms=3600000"
})
@AutoConfigureMockMvc
@ActiveProfiles("local")
class ReportCaseFlowTest {

    private static final String CSRF = "test-csrf-token";

    @Autowired
    private MockMvc mvc;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private ReportCaseService service;

    private long moderator;
    private long author;
    private long reporter;
    private long reason;

    @BeforeEach
    void setUp() {
        // The queue is shared and paged: open cases of earlier runs must not push this test's cases off the first page
        jdbc.update("UPDATE report_cases SET status = 'RESOLVED' WHERE status = 'OPEN' AND handler_role = 'MODERATOR'");
        moderator = user("MODERATOR", 0);
        author = user("USER", 100);
        reporter = user("USER", 0);
        reason = jdbc.queryForObject("SELECT MIN(id) FROM reason_options WHERE scope = 'REPORT'", Long.class);
    }

    @Test
    void onlyAModeratorSeesTheQueue() throws Exception {
        mvc.perform(get("/api/moderator/reports").session(session(reporter, "USER"))).andExpect(status().isForbidden());
    }

    @Test
    void theQueueSplitsByUrgencyAndFilters() throws Exception {
        long normalPost = blog("POST", "PUBLISHED");
        long normal = report("BLOG", normalPost, "NORMAL");
        long hiddenPost = blog("QA", "HIDDEN");
        long urgent = report("BLOG", hiddenPost, "URGENT");

        mvc.perform(get("/api/moderator/reports").param("kind", "QA").session(session(moderator, "MODERATOR")))
                .andExpect(jsonPath("$.data.items[*].id", hasItem((int) urgent)))
                .andExpect(jsonPath("$.data.items[?(@.id == " + urgent + ")].label").value("Câu hỏi"))
                .andExpect(jsonPath("$.data.items[?(@.id == " + urgent + ")].hidden").value(true));
        mvc.perform(get("/api/moderator/reports").param("tab", "NORMAL").param("kind", "POST")
                        .session(session(moderator, "MODERATOR")))
                .andExpect(jsonPath("$.data.items[*].id", hasItem((int) normal)))
                .andExpect(jsonPath("$.data.items[?(@.id == " + normal + ")].topReason").isNotEmpty());
        mvc.perform(get("/api/moderator/reports").param("kind", "REVIEW").session(session(moderator, "MODERATOR")))
                .andExpect(jsonPath("$.data.items[*].id", not(hasItem((int) urgent))));
    }

    @Test
    void removingAPostHidesItTakesPointsAndTellsEveryone() throws Exception {
        long post = blog("POST", "HIDDEN");
        long kase = report("BLOG", post, "URGENT");
        jdbc.update("INSERT INTO comments (blog_id, author_id, content, status) VALUES (?, ?, 'Bình luận', 'PUBLISHED')", post, reporter);
        long comment = jdbc.queryForObject("SELECT id FROM comments WHERE blog_id = ?", Long.class, post);
        long commentCase = report("COMMENT", comment, "NORMAL", user("USER", 0));

        mvc.perform(get("/api/moderator/reports/" + kase).session(session(moderator, "MODERATOR")))
                .andExpect(jsonPath("$.data.hidden").value(true))
                .andExpect(jsonPath("$.data.link").isEmpty())
                .andExpect(jsonPath("$.data.reporters[0].id").value((int) reporter));
        act(kase, "remove", "{\"reasonId\":" + reason + ",\"note\":\"Quảng cáo\"}").andExpect(status().isOk());

        assertThat(jdbc.queryForObject("SELECT status FROM blogs WHERE id = ?", String.class, post)).isEqualTo("REMOVED");
        assertThat(jdbc.queryForObject("SELECT purge_at IS NOT NULL FROM blogs WHERE id = ?", Boolean.class, post)).isTrue();
        assertThat(jdbc.queryForObject("SELECT points FROM users WHERE id = ?", Integer.class, author)).isEqualTo(80);
        assertThat(jdbc.queryForObject("SELECT decision FROM report_cases WHERE id = ?", String.class, kase)).isEqualTo("REMOVE");
        assertThat(jdbc.queryForObject("SELECT content FROM notifications WHERE user_id = ? AND type = 'CONTENT_REMOVED'",
                String.class, author)).contains("đã bị Moderator gỡ vì:", "Quảng cáo").doesNotContain("khiếu nại");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM notifications WHERE user_id = ? AND type = 'REPORT_RESOLVED'",
                Integer.class, reporter)).isEqualTo(1);
        // The comment's own case ends with the post, without taking points from its writer
        assertThat(jdbc.queryForObject("SELECT status FROM report_cases WHERE id = ?", String.class, commentCase)).isEqualTo("RESOLVED");
        assertThat(jdbc.queryForObject("SELECT points FROM users WHERE id = ?", Integer.class, reporter)).isZero();
        mvc.perform(get("/api/blogs/" + post)).andExpect(status().isNotFound());

        // AF6: another Moderator was faster
        act(kase, "dismiss", "").andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(ReportCaseService.ALREADY_HANDLED));
        // Still in "Đã gỡ", with the way back
        mvc.perform(get("/api/moderator/reports/" + kase).session(session(moderator, "MODERATOR")))
                .andExpect(jsonPath("$.data.removed").value(true))
                .andExpect(jsonPath("$.data.removedBecause").isNotEmpty())
                .andExpect(jsonPath("$.data.purgeAt").isNotEmpty());
    }

    @Test
    void aRemovalMadeByMistakeCanBeUndone() throws Exception {
        long post = blog("POST", "HIDDEN");
        long kase = report("BLOG", post, "URGENT");
        mvc.perform(get("/api/moderator/reports").param("tab", "REMOVED").session(session(moderator, "MODERATOR")))
                .andExpect(jsonPath("$.data.items[*].id", not(hasItem((int) kase))));
        act(kase, "remove", "{\"reasonId\":" + reason + "}").andExpect(status().isOk());
        mvc.perform(get("/api/moderator/reports").param("tab", "REMOVED").session(session(moderator, "MODERATOR")))
                .andExpect(jsonPath("$.data.items[*].id", hasItem((int) kase)))
                .andExpect(jsonPath("$.data.items[?(@.id == " + kase + ")].purgeAt").isNotEmpty());
        assertThat(jdbc.queryForObject("SELECT points FROM users WHERE id = ?", Integer.class, author)).isEqualTo(80);

        act(kase, "restore", "").andExpect(status().isOk());

        assertThat(jdbc.queryForObject("SELECT status FROM blogs WHERE id = ?", String.class, post)).isEqualTo("PUBLISHED");
        assertThat(jdbc.queryForObject("SELECT purge_at IS NULL FROM blogs WHERE id = ?", Boolean.class, post)).isTrue();
        assertThat(jdbc.queryForObject("SELECT points FROM users WHERE id = ?", Integer.class, author)).isEqualTo(100);
        assertThat(jdbc.queryForObject("SELECT decision FROM report_cases WHERE id = ?", String.class, kase)).isEqualTo("KEEP");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM notifications WHERE user_id = ? AND type = 'CONTENT_RESTORED'",
                Integer.class, author)).isEqualTo(1);
        // The mistake was the Moderator's, not the reporter's
        assertThat(jdbc.queryForObject("SELECT false_report_count FROM users WHERE id = ?", Integer.class, reporter)).isZero();
        mvc.perform(get("/api/blogs/" + post)).andExpect(status().isOk());
        mvc.perform(get("/api/moderator/reports").param("tab", "REMOVED").session(session(moderator, "MODERATOR")))
                .andExpect(jsonPath("$.data.items[*].id", not(hasItem((int) kase))));
        // Twice: nothing more is paid
        act(kase, "restore", "").andExpect(status().isConflict());
        assertThat(jdbc.queryForObject("SELECT points FROM users WHERE id = ?", Integer.class, author)).isEqualTo(100);
    }

    @Test
    void aRemovedCommentComesBackAndCounts() throws Exception {
        long post = blog("POST", "PUBLISHED");
        jdbc.update("INSERT INTO comments (blog_id, author_id, content, status) VALUES (?, ?, 'Bình luận', 'PUBLISHED')", post, author);
        long comment = jdbc.queryForObject("SELECT id FROM comments WHERE blog_id = ?", Long.class, post);
        long kase = report("COMMENT", comment, "NORMAL");
        act(kase, "remove", "{\"reasonId\":" + reason + "}").andExpect(status().isOk());
        assertThat(jdbc.queryForObject("SELECT comment_count FROM blogs WHERE id = ?", Integer.class, post)).isZero();
        act(kase, "restore", "").andExpect(status().isOk());
        assertThat(jdbc.queryForObject("SELECT status FROM comments WHERE id = ?", String.class, comment)).isEqualTo("PUBLISHED");
        assertThat(jdbc.queryForObject("SELECT comment_count FROM blogs WHERE id = ?", Integer.class, post)).isEqualTo(1);
    }

    @Test
    void aReasonIsNeededToRemove() throws Exception {
        long kase = report("BLOG", blog("POST", "PUBLISHED"), "NORMAL");
        act(kase, "remove", "{}").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(ReportCaseService.CHOOSE_REASON));
        assertThat(jdbc.queryForObject("SELECT status FROM report_cases WHERE id = ?", String.class, kase)).isEqualTo("OPEN");
    }

    @Test
    void aFalseReportRestoresTheContentAndCountsAgainstTheReporter() throws Exception {
        long post = blog("POST", "HIDDEN");
        long kase = report("BLOG", post, "URGENT");
        act(kase, "dismiss", "").andExpect(status().isOk());
        assertThat(jdbc.queryForObject("SELECT status FROM blogs WHERE id = ?", String.class, post)).isEqualTo("PUBLISHED");
        assertThat(jdbc.queryForObject("SELECT points FROM users WHERE id = ?", Integer.class, author)).isEqualTo(100);
        assertThat(jdbc.queryForObject("SELECT decision FROM report_cases WHERE id = ?", String.class, kase)).isEqualTo("KEEP");
        assertThat(jdbc.queryForObject("SELECT is_false FROM reports WHERE case_id = ?", Boolean.class, kase)).isTrue();
        assertThat(jdbc.queryForObject("SELECT false_report_count FROM users WHERE id = ?", Integer.class, reporter)).isEqualTo(1);

        // Past the limit (5): reporting locks and the member is told; the count starts over
        jdbc.update("UPDATE users SET false_report_count = 5 WHERE id = ?", reporter);
        act(report("BLOG", blog("POST", "PUBLISHED"), "NORMAL"), "dismiss", "").andExpect(status().isOk());
        assertThat(jdbc.queryForObject("SELECT report_muted_until > UTC_TIMESTAMP() FROM users WHERE id = ?", Boolean.class, reporter)).isTrue();
        assertThat(jdbc.queryForObject("SELECT false_report_count FROM users WHERE id = ?", Integer.class, reporter)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM notifications WHERE user_id = ? AND type = 'REPORT_MUTED'",
                Integer.class, reporter)).isEqualTo(1);
    }

    @Test
    void removingACommentKeepsThePostAndNeverShowsItsText() throws Exception {
        long post = blog("POST", "PUBLISHED");
        String secret = "Bình luận vi phạm " + UUID.randomUUID();
        jdbc.update("INSERT INTO comments (blog_id, author_id, content, status) VALUES (?, ?, ?, 'HIDDEN')", post, author, secret);
        long comment = jdbc.queryForObject("SELECT id FROM comments WHERE content = ?", Long.class, secret);
        jdbc.update("INSERT INTO comments (blog_id, author_id, parent_id, content, status) VALUES (?, ?, ?, 'Trả lời', 'PUBLISHED')",
                post, reporter, comment);
        long kase = report("COMMENT", comment, "URGENT");

        mvc.perform(get("/api/moderator/reports/" + kase).session(session(moderator, "MODERATOR")))
                .andExpect(jsonPath("$.data.label").value("Bình luận"))
                .andExpect(jsonPath("$.data.text").value(secret))
                .andExpect(jsonPath("$.data.link").isEmpty());
        act(kase, "remove", "{\"reasonId\":" + reason + "}").andExpect(status().isOk());

        assertThat(jdbc.queryForObject("SELECT status FROM blogs WHERE id = ?", String.class, post)).isEqualTo("PUBLISHED");
        String page = mvc.perform(get("/api/blogs/" + post)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        assertThat(page).doesNotContain(secret).contains("Trả lời");
    }

    @Test
    void removingAReviewTakesItOutOfThePlace() throws Exception {
        jdbc.update("""
                INSERT INTO places (name_vi, category_id, lat, lng, source, status, created_by)
                VALUES (?, 'food', 35.7, 139.7, 'TEAM', 'ACTIVE', ?)""", "Quán " + author, author);
        long place = jdbc.queryForObject("SELECT id FROM places WHERE name_vi = ?", Long.class, "Quán " + author);
        jdbc.update("INSERT INTO reviews (author_id, place_id, rating, content, status) VALUES (?, ?, 1, 'Quán tệ lắm.', 'PUBLISHED')",
                author, place);
        long review = jdbc.queryForObject("SELECT id FROM reviews WHERE author_id = ?", Long.class, author);
        long kase = report("REVIEW", review, "NORMAL");
        mvc.perform(get("/api/moderator/reports/" + kase).session(session(moderator, "MODERATOR")))
                .andExpect(jsonPath("$.data.label").value("Review tại Quán " + author))
                .andExpect(jsonPath("$.data.link").value("/map?place=" + place));
        act(kase, "remove", "{\"reasonId\":" + reason + "}").andExpect(status().isOk());
        mvc.perform(get("/api/map/places/" + place)).andExpect(jsonPath("$.data.reviewCount").value(0));
    }

    @Test
    void aMessageShowsWhatTheReporterRevealedAndCanBeRemoved() throws Exception {
        long low = Math.min(author, reporter);
        long high = Math.max(author, reporter);
        jdbc.update("INSERT INTO chat_conversations (user_low_id, user_high_id, started_by) VALUES (?, ?, ?)", low, high, author);
        long conversation = jdbc.queryForObject("SELECT id FROM chat_conversations WHERE user_low_id = ? AND user_high_id = ?",
                Long.class, low, high);
        jdbc.update("INSERT INTO chat_messages (conversation_id, sender_id, content) VALUES (?, ?, 'Chuyển tiền cho mình')",
                conversation, author);
        long plain = jdbc.queryForObject("SELECT id FROM chat_messages WHERE conversation_id = ?", Long.class, conversation);
        long plainCase = report("MESSAGE", plain, "NORMAL");
        mvc.perform(get("/api/moderator/reports/" + plainCase).session(session(moderator, "MODERATOR")))
                .andExpect(jsonPath("$.data.label").value("Tin nhắn"))
                .andExpect(jsonPath("$.data.text").value("Chuyển tiền cho mình"))
                .andExpect(jsonPath("$.data.encrypted").value(false));
        act(plainCase, "remove", "{\"reasonId\":" + reason + "}").andExpect(status().isOk());
        act(plainCase, "restore", "").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(ReportCaseService.MESSAGE_FOR_GOOD));
        assertThat(jdbc.queryForObject("SELECT recalled_at IS NOT NULL FROM chat_messages WHERE id = ?", Boolean.class, plain)).isTrue();

        // Encrypted: only the body the reporter sent with the report, in the format of the browser's library
        jdbc.update("""
                INSERT INTO chat_messages (conversation_id, sender_id, ciphertext, iv, franking_tag)
                VALUES (?, ?, 'AAAA', 'BBBB', REPEAT('a', 64))""", conversation, author);
        long sealed = jdbc.queryForObject("SELECT MAX(id) FROM chat_messages WHERE conversation_id = ?", Long.class, conversation);
        long sealedCase = report("MESSAGE", sealed, "NORMAL");
        long reportId = jdbc.queryForObject("SELECT id FROM reports WHERE case_id = ?", Long.class, sealedCase);
        jdbc.update("INSERT INTO chat_report_evidence (report_id, message_id, plaintext) VALUES (?, ?, ?)", reportId, sealed,
                "{\"v\":1,\"text\":\"Tin bí mật\",\"files\":[{\"k\":\"x\"}]}");
        mvc.perform(get("/api/moderator/reports/" + sealedCase).session(session(moderator, "MODERATOR")))
                .andExpect(jsonPath("$.data.encrypted").value(true))
                .andExpect(jsonPath("$.data.text").value("Tin bí mật"))
                .andExpect(jsonPath("$.data.files").value(1));
    }

    @Test
    void removedContentIsDeletedOnceItsTimeIsUp() {
        long post = blog("POST", "REMOVED");
        Timestamp past = Timestamp.from(Instant.now().minus(Duration.ofDays(1)));
        Timestamp future = Timestamp.from(Instant.now().plus(Duration.ofDays(1)));
        jdbc.update("UPDATE blogs SET purge_at = ? WHERE id = ?", past, post);
        long waiting = blog("POST", "REMOVED");
        jdbc.update("UPDATE blogs SET purge_at = ? WHERE id = ?", future, waiting);
        long kept = blog("POST", "PUBLISHED");
        jdbc.update("""
                INSERT INTO comments (blog_id, author_id, content, status, purge_at) VALUES (?, ?, 'Có trả lời', 'REMOVED', ?)""",
                kept, author, past);
        long parent = jdbc.queryForObject("SELECT id FROM comments WHERE blog_id = ? AND content = 'Có trả lời'", Long.class, kept);
        jdbc.update("INSERT INTO comments (blog_id, author_id, parent_id, content, status) VALUES (?, ?, ?, 'Trả lời', 'PUBLISHED')",
                kept, reporter, parent);
        jdbc.update("INSERT INTO comments (blog_id, author_id, content, status, purge_at) VALUES (?, ?, 'Không trả lời', 'REMOVED', ?)",
                kept, author, past);

        service.purgeRemoved();

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM blogs WHERE id = ?", Integer.class, post)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM blogs WHERE id = ?", Integer.class, waiting)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT content FROM comments WHERE id = ?", String.class, parent)).isEmpty();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM comments WHERE blog_id = ? AND content = 'Không trả lời'",
                Integer.class, kept)).isZero();
    }

    // ---------------------------------------------------------------- helpers

    private ResultActions act(long caseId, String action, String body) throws Exception {
        return mvc.perform(post("/api/moderator/reports/" + caseId + "/" + action)
                .session(session(moderator, "MODERATOR"))
                .cookie(new Cookie("XSRF-TOKEN", CSRF)).header("X-XSRF-TOKEN", CSRF)
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private long report(String type, long targetId, String priority) {
        return report(type, targetId, priority, reporter);
    }

    private long report(String type, long targetId, String priority, long... reporters) {
        jdbc.update("""
                INSERT INTO report_cases (target_type, target_id, handler_role, report_count, priority)
                VALUES (?, ?, 'MODERATOR', ?, ?)""", type, targetId, reporters.length, priority);
        long id = jdbc.queryForObject("SELECT id FROM report_cases WHERE target_type = ? AND target_id = ? AND status = 'OPEN'",
                Long.class, type, targetId);
        for (long who : reporters) {
            jdbc.update("INSERT INTO reports (case_id, reporter_id, reason_id) VALUES (?, ?, ?)", id, who, reason);
        }
        return id;
    }

    private long user(String role, int points) {
        String email = "reportcase-" + UUID.randomUUID() + "@test.local";
        jdbc.update("""
                INSERT INTO users (email, display_name, role, status, is_adult_confirmed, points)
                VALUES (?, 'Báo Cáo Test', ?, 'ACTIVE', TRUE, ?)""", email, role, points);
        return jdbc.queryForObject("SELECT id FROM users WHERE email = ?", Long.class, email);
    }

    private long blog(String type, String blogStatus) {
        String title = "Bài " + UUID.randomUUID();
        jdbc.update("INSERT INTO blogs (author_id, blog_type, title, content, status) VALUES (?, ?, ?, 'Nội dung.', ?)",
                author, type, title, blogStatus);
        return jdbc.queryForObject("SELECT id FROM blogs WHERE title = ?", Long.class, title);
    }

    private MockHttpSession session(long id, String role) {
        AppUserPrincipal me = new AppUserPrincipal(id, "u@test.local", "Test", role, "/");
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(SPRING_SECURITY_CONTEXT_KEY, new SecurityContextImpl(
                new UsernamePasswordAuthenticationToken(me, null, List.of(new SimpleGrantedAuthority("ROLE_" + role)))));
        return session;
    }
}
