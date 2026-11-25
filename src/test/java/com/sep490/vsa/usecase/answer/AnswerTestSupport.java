package com.sep490.vsa.usecase.answer;

import com.fasterxml.jackson.databind.JsonNode;
import com.sep490.vsa.usecase.UseCaseTestBase;
import org.springframework.test.web.servlet.ResultActions;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Helpers shared by the answer use cases (Submit / Update / Delete Answer). An answer is a top-level comment of a
 * question ({@code blogs.blog_type = 'QA'}); a reply hangs under an answer ({@code parent_id}). Answers are sent to
 * {@code POST /api/questions/{id}/answers}; editing and deleting use the shared {@code /api/comments/{id}} calls.
 */
abstract class AnswerTestSupport extends UseCaseTestBase {

    protected static final String COMMENT_NOT_FOUND = "Không tìm thấy bình luận này.";
    protected static final String EMPTY_TEXT = "Hãy viết nội dung bình luận.";
    protected static final String TOO_LONG = "Bình luận tối đa 2.000 ký tự.";
    protected static final String LINK_NEEDS_TAG = "Chỉ tài khoản đã có tag định danh mới được chèn link.";
    protected static final String PHONE_NEEDS_TAG =
            "Chỉ tài khoản đã có tag định danh mới được ghi số điện thoại hoặc tài khoản Zalo, LINE, Telegram.";
    protected static final String SHORT_LINK = "Không dùng link rút gọn (bit.ly, tinyurl…). Hãy dán link đầy đủ.";
    protected static final String BANNED_VAY_NONG =
            "Chưa đăng được: nội dung chứa từ khóa không được phép “vay nóng”. Hãy sửa rồi gửi lại.";   // MSG36

    /** A published question of {@code asker} whose title holds {@code word}, so the list can find it by keyword. */
    protected long question(long asker, String word) {
        return publishedQuestion(asker, "Hỏi về thẻ cư trú " + word + "?", "Mình cần làm gì trước " + word + "?");
    }

    protected String title(long questionId) {
        return jdbc.queryForObject("SELECT title FROM blogs WHERE id = ?", String.class, questionId);
    }

    /** Submit Answer through the API: a direct answer to the question. */
    protected ResultActions answer(Long userId, long questionId, String content) throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("content", content);
        return postAs(userId, body, "/api/questions/{id}/answers", questionId);
    }

    /** Submit Answer through the API: a reply under an answer ({@code parentId}). */
    protected ResultActions reply(Long userId, long questionId, long parentId, String content) throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("content", content);
        body.put("parentId", parentId);
        return postAs(userId, body, "/api/questions/{id}/answers", questionId);
    }

    /** Answers through the API and returns the new answer's id; the writer's 15-second spacing is passed first. */
    protected long answered(long userId, long questionId, String content) throws Exception {
        ageCommentsOf(userId, Duration.ofSeconds(20));
        answer(userId, questionId, content).andExpect(status().isOk());
        return lastCommentId(questionId, userId);
    }

    /** Replies through the API and returns the new reply's id; the writer's 15-second spacing is passed first. */
    protected long replied(long userId, long questionId, long parentId, String content) throws Exception {
        ageCommentsOf(userId, Duration.ofSeconds(20));
        reply(userId, questionId, parentId, content).andExpect(status().isOk());
        return lastCommentId(questionId, userId);
    }

    protected ResultActions edit(Long userId, long commentId, String content) throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("content", content);
        return putAs(userId, body, "/api/comments/{id}", commentId);
    }

    protected ResultActions remove(Long userId, long commentId) throws Exception {
        return deleteAs(userId, "/api/comments/{id}", commentId);
    }

    protected ResultActions chooseBest(long asker, long questionId, long answerId) throws Exception {
        return putAs(asker, Map.of("answerId", answerId), "/api/questions/{id}/best-answer", questionId);
    }

    /** A comment row written straight to the DB (for chains and limits), created at {@code at}. */
    protected long insertComment(long blogId, long authorId, Long parentId, String content, Instant at) {
        jdbc.update("""
                INSERT INTO comments (blog_id, author_id, parent_id, content, status, created_at, updated_at)
                VALUES (?, ?, ?, ?, 'PUBLISHED', ?, ?)""",
                blogId, authorId, parentId, content, Timestamp.from(at), Timestamp.from(at));
        return jdbc.queryForObject("SELECT MAX(id) FROM comments WHERE blog_id = ? AND author_id = ?",
                Long.class, blogId, authorId);
    }

    /** The question as a Guest sees it in the Hỏi-Đáp list (searched by its word), or null when not listed. */
    protected JsonNode card(String word) throws Exception {
        JsonNode items = data(getAs(null, "/api/questions?q={q}", word)).get("items");
        return items.isEmpty() ? null : items.get(0);
    }

    /** Ids of the questions holding {@code word} listed under a status filter (ALL, UNANSWERED, UNSOLVED, SOLVED). */
    protected List<Long> listed(String word, String status) throws Exception {
        List<Long> ids = new ArrayList<>();
        data(getAs(null, "/api/questions?q={q}&status={s}", word, status)).get("items")
                .forEach(item -> ids.add(item.get("id").asLong()));
        return ids;
    }

    /** The question page as this viewer sees it ({@code null} = Guest). */
    protected JsonNode detail(Long viewerId, long questionId) throws Exception {
        return data(getAs(viewerId, "/api/questions/{id}", questionId));
    }

    /** The node of a comment anywhere in the tree of the page data, or null. */
    protected static JsonNode node(JsonNode detail, long commentId) {
        return find(detail.get("comments"), commentId);
    }

    private static JsonNode find(JsonNode nodes, long commentId) {
        for (JsonNode n : nodes) {
            if (n.get("id").asLong() == commentId) {
                return n;
            }
            JsonNode inReplies = find(n.get("replies"), commentId);
            if (inReplies != null) {
                return inReplies;
            }
        }
        return null;
    }

    /** The user's notifications, oldest first: type, content, ref_type, ref_id. */
    protected List<Map<String, Object>> notificationsOf(long userId) {
        return jdbc.queryForList("SELECT type, content, ref_type, ref_id FROM notifications WHERE user_id = ? ORDER BY id",
                userId);
    }

    protected int pointLines(long userId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM point_transactions WHERE user_id = ?", Integer.class, userId);
    }

    protected Long bestCommentId(long questionId) {
        return jdbc.queryForObject("SELECT best_comment_id FROM blogs WHERE id = ?", Long.class, questionId);
    }

    protected int commentsOf(long questionId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM comments WHERE blog_id = ?", Integer.class, questionId);
    }

    protected long reportReason(String label) {
        return jdbc.queryForObject("SELECT id FROM reason_options WHERE scope = 'REPORT' AND label = ?", Long.class, label);
    }

    protected static void assertMessage(JsonNode body, String message) {
        assertThat(body.get("success").asBoolean()).isFalse();
        assertThat(body.get("message").asText()).isEqualTo(message);
    }
}
