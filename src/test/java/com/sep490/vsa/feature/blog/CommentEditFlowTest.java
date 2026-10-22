package com.sep490.vsa.feature.blog;

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
import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.security.web.context.HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Update Comment and Update Answer ({@code PUT /api/comments/{id}}) end to end against MySQL (own database
 * {@code asvsj_test}, like BlogWriteFlowTest). Every test makes its own users and posts.
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:mysql://localhost:3306/asvsj_test?createDatabaseIfNotExist=true&useUnicode=true&characterEncoding=utf8&connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true",
        "app.media.local-dir=target/test-uploads",
        "app.moderation.retry-initial-delay-ms=3600000",
        "app.places.osm.enabled=false"
})
@AutoConfigureMockMvc
@ActiveProfiles("local")
class CommentEditFlowTest {

    private static final String CSRF = "test-csrf-token";

    @Autowired
    private MockMvc mvc;
    @Autowired
    private JdbcTemplate jdbc;
    @MockBean
    private ImageModerator moderator;

    private long writer;       // writes the post or question
    private long commenter;    // comments and answers
    private long other;
    private long topic;

    @BeforeEach
    void setUp() {
        when(moderator.engine()).thenReturn("OPENAI");
        when(moderator.check(any(byte[].class))).thenReturn(new Verdict("PASS", "{}"));
        writer = user("USER");
        commenter = user("USER");
        other = user("USER");
        topic = jdbc.queryForObject("SELECT id FROM blog_topics WHERE name = 'Visa và cư trú'", Long.class);
    }

    @Test
    void authorEditsTheirCommentQuietly() throws Exception {
        long blog = write(writer);
        long comment = comment(commenter, blog, null, "Bình luận đầu tiên");
        mvc.perform(withCsrf(post("/api/comments/" + comment + "/like"), other)).andExpect(status().isOk());
        int notificationsBefore = notifications(writer);

        edit(commenter, comment, "  Bình luận đã sửa lại  ")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Đã lưu bình luận."))
                .andExpect(jsonPath("$.data.id").value(blog))
                .andExpect(jsonPath("$.data.comments[0].content").value("Bình luận đã sửa lại"))
                .andExpect(jsonPath("$.data.comments[0].edited").value(true))
                .andExpect(jsonPath("$.data.comments[0].mine").value(true))
                .andExpect(jsonPath("$.data.comments[0].likeCount").value(1));

        assertThat(jdbc.queryForObject("SELECT content FROM comments WHERE id = ?", String.class, comment))
                .isEqualTo("Bình luận đã sửa lại");
        assertThat(jdbc.queryForObject("SELECT is_edited FROM comments WHERE id = ?", Boolean.class, comment)).isTrue();
        assertThat(notifications(writer)).isEqualTo(notificationsBefore);
        assertThat(jdbc.queryForObject("SELECT comment_count FROM blogs WHERE id = ?", Integer.class, blog)).isEqualTo(1);
    }

    @Test
    void onlyTheWriterMayEdit() throws Exception {
        long blog = write(writer);
        long comment = comment(commenter, blog, null, "Của tôi");

        edit(other, comment, "Sửa hộ").andExpect(status().isForbidden());
        edit(writer, comment, "Chủ bài sửa").andExpect(status().isForbidden());
        mvc.perform(put("/api/comments/" + comment).contentType(MediaType.APPLICATION_JSON)
                        .cookie(new Cookie("XSRF-TOKEN", CSRF)).header("X-XSRF-TOKEN", CSRF)
                        .content("{\"content\":\"Khách sửa\"}"))
                .andExpect(status().isUnauthorized());

        long moderator = user("MODERATOR");
        mvc.perform(put("/api/comments/" + comment).session(session(moderator, "MODERATOR"))
                        .cookie(new Cookie("XSRF-TOKEN", CSRF)).header("X-XSRF-TOKEN", CSRF)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"content\":\"Moderator sửa\"}"))
                .andExpect(status().isForbidden());

        assertUnchanged(comment, "Của tôi");
    }

    @Test
    void theNewTextIsCheckedLikeANewComment() throws Exception {
        long blog = write(writer);
        long comment = comment(commenter, blog, null, "Nội dung gốc");

        edit(commenter, comment, "   ").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Hãy viết nội dung bình luận."));
        edit(commenter, comment, "a".repeat(2001)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Bình luận tối đa 2.000 ký tự."));
        edit(commenter, comment, "Có việc nhẹ lương cao đây").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("“việc nhẹ lương cao”")));
        edit(commenter, comment, "Xem https://bit.ly/abc nhé").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("link rút gọn")));
        edit(commenter, comment, "Xem https://example.com nhé").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("tag định danh")));

        assertUnchanged(comment, "Nội dung gốc");
        edit(commenter, comment, "a".repeat(2000)).andExpect(status().isOk());
    }

    @Test
    void onlyTaggedAccountsCommentPhoneNumbersOrChatIds() throws Exception {
        String contactNeedsTag =
                "Chỉ tài khoản đã có tag định danh mới được ghi số điện thoại hoặc tài khoản Zalo, LINE, Telegram.";
        long blog = write(writer);
        mvc.perform(withCsrf(post("/api/blogs/" + blog + "/comments"), commenter).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"ib zalo 0901 234 567\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(contactNeedsTag));
        long comment = comment(commenter, blog, null, "Mua cá đông lạnh ở Gyomu rẻ lắm");
        edit(commenter, comment, "Inbox telegram @scamvn nhé").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(contactNeedsTag));
        assertUnchanged(comment, "Mua cá đông lạnh ở Gyomu rẻ lắm");

        // An account with an identity tag may write it
        long mentorTag = jdbc.queryForObject("SELECT id FROM tag_types WHERE name = 'Mentor'", Long.class);
        jdbc.update("INSERT INTO user_tags (user_id, tag_type_id, granted_by) VALUES (?, ?, ?)",
                other, mentorTag, user("MODERATOR"));
        long tagged = comment(other, blog, null, "ib zalo 0901 234 567");
        assertThat(jdbc.queryForObject("SELECT content FROM comments WHERE id = ?", String.class, tagged))
                .isEqualTo("ib zalo 0901 234 567");
    }

    @Test
    void goneCommentsAndPostsCannotBeEdited() throws Exception {
        long blog = write(writer);
        long parent = comment(commenter, blog, null, "Sẽ bị xóa");
        comment(other, blog, parent, "Trả lời giữ lại");
        mvc.perform(withCsrf(delete("/api/comments/" + parent), commenter)).andExpect(status().isOk());
        assertThat(jdbc.queryForObject("SELECT status FROM comments WHERE id = ?", String.class, parent)).isEqualTo("REMOVED");
        edit(commenter, parent, "Sống lại").andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Không tìm thấy bình luận này."));
        assertUnchanged(parent, "");

        long hidden = comment(commenter, blog, null, "Bình luận bị ẩn");
        jdbc.update("UPDATE comments SET status = 'HIDDEN' WHERE id = ?", hidden);
        edit(commenter, hidden, "Sửa").andExpect(status().isNotFound());

        long onHiddenPost = comment(commenter, blog, null, "Bài sắp bị ẩn");
        jdbc.update("UPDATE blogs SET status = 'HIDDEN' WHERE id = ?", blog);
        edit(commenter, onHiddenPost, "Sửa").andExpect(status().isNotFound());
        jdbc.update("UPDATE blogs SET status = 'REMOVED' WHERE id = ?", blog);
        edit(commenter, onHiddenPost, "Sửa").andExpect(status().isNotFound());
        assertUnchanged(onHiddenPost, "Bài sắp bị ẩn");

        edit(commenter, 999_999_999L, "Sửa").andExpect(status().isNotFound());
    }

    @Test
    void editedBestAnswerStaysBestAndRepliesCanBeEdited() throws Exception {
        long question = ask(writer);
        long answer = comment(commenter, question, null, "Ra cục xuất nhập cảnh nhé");
        long reply = comment(other, question, answer, "Cảm ơn bạn");
        mvc.perform(withCsrf(put("/api/questions/" + question + "/best-answer"), writer)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"answerId\":" + answer + "}"))
                .andExpect(status().isOk());
        int points = jdbc.queryForObject("SELECT points FROM users WHERE id = ?", Integer.class, commenter);

        edit(commenter, answer, "Ra cục xuất nhập cảnh gần nhà nhé")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.type").value("QA"))
                .andExpect(jsonPath("$.data.bestCommentId").value(answer))
                .andExpect(jsonPath("$.data.comments[0].best").value(true))
                .andExpect(jsonPath("$.data.comments[0].edited").value(true))
                .andExpect(jsonPath("$.data.comments[0].content").value("Ra cục xuất nhập cảnh gần nhà nhé"));
        assertThat(jdbc.queryForObject("SELECT best_comment_id FROM blogs WHERE id = ?", Long.class, question)).isEqualTo(answer);
        assertThat(jdbc.queryForObject("SELECT points FROM users WHERE id = ?", Integer.class, commenter)).isEqualTo(points);

        edit(other, reply, "Cảm ơn bạn nhiều")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.comments[0].replies[0].content").value("Cảm ơn bạn nhiều"))
                .andExpect(jsonPath("$.data.comments[0].replies[0].edited").value(true));
        edit(commenter, reply, "Không phải của tôi").andExpect(status().isForbidden());
    }

    // ---------------------------------------------------------------- helpers

    private ResultActions edit(long userId, long commentId, String content) throws Exception {
        return mvc.perform(withCsrf(put("/api/comments/" + commentId), userId).contentType(MediaType.APPLICATION_JSON)
                .content("{\"content\":\"" + content + "\"}"));
    }

    private void assertUnchanged(long commentId, String content) {
        assertThat(jdbc.queryForObject("SELECT content FROM comments WHERE id = ?", String.class, commentId)).isEqualTo(content);
        assertThat(jdbc.queryForObject("SELECT is_edited FROM comments WHERE id = ?", Boolean.class, commentId)).isFalse();
    }

    private long user(String role) {
        String email = "cedit-" + UUID.randomUUID() + "@test.local";
        jdbc.update("""
                INSERT INTO users (email, display_name, role, status, is_adult_confirmed, created_at)
                VALUES (?, 'Edit Test', ?, 'ACTIVE', TRUE, ?)""",
                email, role, Timestamp.from(Instant.now().minus(Duration.ofDays(30))));
        return jdbc.queryForObject("SELECT id FROM users WHERE email = ?", Long.class, email);
    }

    private int notifications(long userId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM notifications WHERE user_id = ?", Integer.class, userId);
    }

    private static MockHttpSession session(long userId, String role) {
        AppUserPrincipal me = new AppUserPrincipal(userId, "u" + userId + "@test.local", "Edit Test", role, "/");
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(SPRING_SECURITY_CONTEXT_KEY, new SecurityContextImpl(
                new UsernamePasswordAuthenticationToken(me, null, List.of(new SimpleGrantedAuthority("ROLE_" + role)))));
        return session;
    }

    private static MockHttpServletRequestBuilder withCsrf(MockHttpServletRequestBuilder request, long userId) {
        return request.session(session(userId, "USER")).cookie(new Cookie("XSRF-TOKEN", CSRF)).header("X-XSRF-TOKEN", CSRF);
    }

    private long write(long userId) throws Exception {
        return created(mvc.perform(withCsrf(post("/api/blogs"), userId).contentType(MediaType.APPLICATION_JSON)
                .content("{\"title\":\"Bài để bình luận\",\"content\":\"nội dung\",\"topicIds\":[" + topic + "]}")));
    }

    private long ask(long userId) throws Exception {
        return created(mvc.perform(withCsrf(post("/api/questions"), userId).contentType(MediaType.APPLICATION_JSON)
                .content("{\"title\":\"Gia hạn visa ở đâu?\",\"content\":\"Cho mình hỏi\",\"topicIds\":[" + topic + "]}")));
    }

    /** A comment on a post (or a reply anywhere) through the shared comment rules; returns its id. */
    private long comment(long userId, long blogId, Long parentId, String content) throws Exception {
        String kind = jdbc.queryForObject("SELECT blog_type FROM blogs WHERE id = ?", String.class, blogId);
        String path = "QA".equals(kind) ? "/api/questions/" + blogId + "/answers" : "/api/blogs/" + blogId + "/comments";
        mvc.perform(withCsrf(post(path), userId).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"" + content + "\",\"parentId\":" + parentId + "}"))
                .andExpect(status().isOk());
        long id = jdbc.queryForObject("SELECT MAX(id) FROM comments WHERE blog_id = ? AND author_id = ?", Long.class,
                blogId, userId);
        // Written a minute earlier, so the same user's next comment is not refused by the 15-second rule
        jdbc.update("UPDATE comments SET created_at = created_at - INTERVAL 1 MINUTE WHERE id = ?", id);
        return id;
    }

    private static long created(ResultActions result) throws Exception {
        String body = result.andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return Long.parseLong(body.replaceAll(".*\"data\":\\{\"id\":(\\d+).*", "$1"));
    }
}
