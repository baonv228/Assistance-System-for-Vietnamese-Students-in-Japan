package com.sep490.vsa.usecase.comment;

import com.fasterxml.jackson.databind.JsonNode;
import com.sep490.vsa.usecase.UseCaseTestBase;
import org.springframework.test.web.servlet.ResultActions;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Helpers shared by the tests of UC 1.8 Add Comment, UC 1.9 Update Comment and UC 1.10 Delete Comment. Same Spring
 * context as every other use case test (no extra annotations or mocks here).
 */
abstract class CommentUseCaseSupport extends UseCaseTestBase {

    // Messages of the SRS (UC 1.8–1.10) and of BlogActionService / ContentFilter
    static final String POSTED = "Đã đăng bình luận.";
    static final String SAVED = "Đã lưu bình luận.";
    static final String DELETED = "Đã xóa bình luận.";
    static final String EMPTY = "Hãy viết nội dung bình luận.";
    static final String TOO_LONG = "Bình luận tối đa 2.000 ký tự.";
    static final String PARENT_GONE = "Bình luận bạn trả lời không còn nữa.";
    static final String TOO_DEEP = "Chuỗi trả lời đã quá dài. Hãy trả lời một bình luận ở phía trên.";
    static final String COMMENT_NOT_FOUND = "Không tìm thấy bình luận này.";
    static final String LINK_NEEDS_TAG = "Chỉ tài khoản đã có tag định danh mới được chèn link.";
    static final String CONTACT_NEEDS_TAG =
            "Chỉ tài khoản đã có tag định danh mới được ghi số điện thoại hoặc tài khoản Zalo, LINE, Telegram.";
    static final String SHORT_LINK = "Không dùng link rút gọn (bit.ly, tinyurl…). Hãy dán link đầy đủ.";
    static final String TOO_FAST_PATTERN = "Bạn bình luận hơi nhanh\\. Vui lòng đợi (\\d+) giây rồi thử lại\\.";
    static final String DAILY_LIMIT = "Bạn đã đạt giới hạn bình luận trong ngày của tài khoản mới (dưới 7 ngày: tối đa "
            + "20 bình luận mỗi ngày). Vui lòng thử lại vào ngày mai.";
    static final String REPEATED = "Bạn đã gửi nội dung này ở nhiều bài khác. Hãy viết bình luận riêng cho bài này.";

    /** A banned keyword of V5 (gambling site), and the MSG36 text the server answers with it. */
    static final String KEYWORD = "fb88";
    static final String MSG36 = "Chưa đăng được: nội dung chứa từ khóa không được phép “fb88”. Hãy sửa rồi gửi lại.";

    // ---------------------------------------------------------------- requests

    ResultActions comment(Long asUser, long blogId, Long parentId, String content) throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("content", content);
        body.put("parentId", parentId);
        return postAs(asUser, body, "/api/blogs/{id}/comments", blogId);
    }

    /**
     * A comment sent through the API that must succeed; returns its id. The writer's comments are then made 20 s
     * older, so the 15-second spacing (Add Comment 4c) does not block the writer's next comment in the same test.
     */
    long commentOk(long asUser, long blogId, Long parentId, String content) throws Exception {
        comment(asUser, blogId, parentId, content).andExpect(status().isOk());
        long id = lastCommentId(blogId, asUser);
        ageCommentsOf(asUser, Duration.ofSeconds(20));
        return id;
    }

    ResultActions edit(Long asUser, long commentId, String content) throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("content", content);
        return putAs(asUser, body, "/api/comments/{id}", commentId);
    }

    ResultActions remove(Long asUser, long commentId) throws Exception {
        return deleteAs(asUser, "/api/comments/{id}", commentId);
    }

    ResultActions report(long asUser, long commentId) throws Exception {
        return postAs(asUser, Map.of("reasonId", reportReason()), "/api/comments/{id}/report", commentId);
    }

    ResultActions like(long asUser, long commentId) throws Exception {
        return postAs(asUser, null, "/api/comments/{id}/like", commentId);
    }

    /** A published post by {@code author} whose title holds a unique token. */
    long post(long author) {
        return publishedPost(author, "Bài thử bình luận " + token(), "Nội dung bài để bình luận.");
    }

    String title(long blogId) {
        return jdbc.queryForObject("SELECT title FROM blogs WHERE id = ?", String.class, blogId);
    }

    // ---------------------------------------------------------------- rows written straight to the database

    /** A comment inserted without the API (counters are not recounted), e.g. to set its age or status. */
    long insertComment(long blogId, long authorId, Long parentId, String content, String status, Instant at) {
        jdbc.update("""
                INSERT INTO comments (blog_id, author_id, parent_id, content, status, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?)""",
                blogId, authorId, parentId, content, status, Timestamp.from(at), Timestamp.from(at));
        return jdbc.queryForObject("SELECT MAX(id) FROM comments WHERE blog_id = ? AND author_id = ?",
                Long.class, blogId, authorId);
    }

    long reportReason() {
        return jdbc.queryForObject("""
                SELECT id FROM reason_options WHERE scope = 'REPORT' AND is_active ORDER BY sort_order, id LIMIT 1""",
                Long.class);
    }

    // ---------------------------------------------------------------- reading

    /** Texts of the notifications the user received, oldest first. */
    List<String> notificationsOf(long userId) {
        return jdbc.queryForList("SELECT content FROM notifications WHERE user_id = ? ORDER BY id", String.class, userId);
    }

    int commentsBy(long userId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM comments WHERE author_id = ?", Integer.class, userId);
    }

    int pointLines(long userId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM point_transactions WHERE user_id = ?", Integer.class, userId);
    }

    /** The node of comment {@code id} anywhere in the comment tree of a post detail ({@code data.comments}). */
    static JsonNode node(JsonNode comments, long id) {
        for (JsonNode c : comments) {
            if (c.get("id").asLong() == id) {
                return c;
            }
            JsonNode found = node(c.get("replies"), id);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    /** "<tên> đã bình luận bài “<tiêu đề>” của bạn." (UC 1.8 step 6). */
    String commentedNotice(long commenter, long blogId) {
        return displayName(commenter) + " đã bình luận bài “" + title(blogId) + "” của bạn.";
    }

    /** "<tên> đã trả lời bình luận của bạn trong “<tiêu đề>”." (UC 1.8 step 6). */
    String repliedNotice(long replier, long blogId) {
        return displayName(replier) + " đã trả lời bình luận của bạn trong “" + title(blogId) + "”.";
    }
}
