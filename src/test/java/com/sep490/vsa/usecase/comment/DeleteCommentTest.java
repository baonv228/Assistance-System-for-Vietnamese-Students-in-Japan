package com.sep490.vsa.usecase.comment;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * UC 1.10 Delete Comment (Xóa bình luận), {@code DELETE /api/comments/{id}}.
 * <p>
 * Follows SRS Report 3, II.1.10 (normal flow 4–7, alternative flows 4a, 4b, postconditions) and CLAUDE.md "Blog feed –
 * Post Detail": "own; a comment with replies becomes an empty REMOVED placeholder", placeholders left without replies
 * are removed, {@code comment_count} counts only published comments of active accounts. UI-only flows (2 the MSG16
 * dialog, 3a Hủy, 4c being sent to sign in and back) are not tested here.
 */
@DisplayName("UC 1.10 – Delete Comment (Xóa bình luận)")
class DeleteCommentTest extends CommentUseCaseSupport {

    private static final String PLACEHOLDER_REASON = "Tác giả đã tự xóa nội dung.";

    // ================================================================ normal flow

    @Test
    @DisplayName("TC-1.10-01 [Luồng chính] Xóa bình luận không có câu trả lời: xóa hẳn cùng lượt thích, cập nhật số "
            + "bình luận, không báo cho ai")
    void commentWithoutRepliesIsDeletedForGood() throws Exception {
        long author = member();
        long writer = member();
        long liker = member();
        long blog = post(author);
        long kept = commentOk(member(), blog, null, "Bình luận khác vẫn còn");
        long comment = commentOk(writer, blog, null, "Bình luận sẽ bị xóa");
        like(liker, comment).andExpect(status().isOk());
        assertThat(commentCount(blog)).isEqualTo(2);
        int authorNotices = notificationsOf(author).size();
        int pointsBefore = points(writer);

        JsonNode data = data(remove(writer, comment)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.message").value(DELETED)));

        assertThat(commentExists(comment)).isFalse();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM comment_likes WHERE comment_id = ?", Integer.class, comment))
                .isZero();
        assertThat(commentCount(blog)).isEqualTo(1);
        // Step 7: the refreshed comments
        assertThat(data.get("id").asLong()).isEqualTo(blog);
        assertThat(data.get("commentCount").asInt()).isEqualTo(1);
        assertThat(node(data.get("comments"), comment)).isNull();
        assertThat(node(data.get("comments"), kept)).isNotNull();
        // Postcondition: nobody is notified; comments earn no points, so none are taken
        assertThat(notificationsOf(author)).hasSize(authorNotices);
        assertThat(notificationsOf(writer)).isEmpty();
        assertThat(points(writer)).isEqualTo(pointsBefore);
    }

    @Test
    @DisplayName("TC-1.10-02 [Luồng chính 5] Xóa bình luận đã có câu trả lời: để lại chỗ trống \"Bình luận đã bị xóa.\" "
            + "(không tên, không nội dung, mất lượt thích), các câu trả lời vẫn đọc được")
    void commentWithRepliesBecomesAnEmptyPlaceholder() throws Exception {
        long blog = post(member());
        long writer = member();
        long replier = member();
        long comment = commentOk(writer, blog, null, "Bình luận có người trả lời");
        long reply = commentOk(replier, blog, comment, "Câu trả lời của người khác");
        like(replier, comment).andExpect(status().isOk());

        JsonNode data = data(remove(writer, comment).andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value(DELETED)));

        Map<String, Object> row = commentRow(comment);
        assertThat(row.get("status")).isEqualTo("REMOVED");
        assertThat(row.get("content")).isEqualTo("");
        assertThat(row.get("removed_at")).isNotNull();
        assertThat(((Number) row.get("like_count")).intValue()).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM comment_likes WHERE comment_id = ?", Integer.class, comment))
                .isZero();
        assertThat(commentExists(reply)).isTrue();
        assertThat(commentRow(reply).get("status")).isEqualTo("PUBLISHED");
        // The page draws the placeholder: removed, no writer, no text, the reply under it
        JsonNode node = node(data.get("comments"), comment);
        assertThat(node.get("removed").asBoolean()).isTrue();
        assertThat(node.get("author").isNull()).isTrue();
        assertThat(node.get("content").asText()).isEmpty();
        assertThat(node.get("replies")).hasSize(1);
        assertThat(node.get("replies").get(0).get("id").asLong()).isEqualTo(reply);
        assertThat(node.get("replies").get(0).get("content").asText()).isEqualTo("Câu trả lời của người khác");
        // Only the reply is still counted
        assertThat(commentCount(blog)).isEqualTo(1);
        assertThat(data.get("commentCount").asInt()).isEqualTo(1);
        // Guests see the same placeholder
        JsonNode guestView = data(getAs(null, "/api/blogs/{id}", blog).andExpect(status().isOk()));
        assertThat(node(guestView.get("comments"), comment).get("removed").asBoolean()).isTrue();
    }

    @Test
    @DisplayName("TC-1.10-03 [Luồng chính 6] Xóa câu trả lời cuối dưới chỗ trống: chỗ trống không còn câu trả lời "
            + "cũng được xóa (cả chuỗi chỗ trống phía trên)")
    void placeholdersLeftWithoutRepliesAreRemoved() throws Exception {
        long blog = post(member());
        long a = member();
        long b = member();
        long c = member();
        long top = commentOk(a, blog, null, "Tầng 1");
        long middle = commentOk(b, blog, top, "Tầng 2");
        long last = commentOk(c, blog, middle, "Tầng 3");
        remove(a, top).andExpect(status().isOk());
        remove(b, middle).andExpect(status().isOk());
        assertThat(commentRow(top).get("status")).isEqualTo("REMOVED");
        assertThat(commentRow(middle).get("status")).isEqualTo("REMOVED");
        assertThat(commentCount(blog)).isEqualTo(1);

        JsonNode data = data(remove(c, last).andExpect(status().isOk()));

        assertThat(commentExists(last)).isFalse();
        assertThat(commentExists(middle)).isFalse();
        assertThat(commentExists(top)).isFalse();
        assertThat(data.get("comments")).isEmpty();
        assertThat(commentCount(blog)).isZero();
    }

    @Test
    @DisplayName("TC-1.10-04 [Luồng chính] Xóa một câu trả lời (reply): bình luận cha giữ nguyên, số bình luận giảm")
    void deletingAReplyKeepsItsParent() throws Exception {
        long blog = post(member());
        long writer = member();
        long replier = member();
        long parent = commentOk(writer, blog, null, "Bình luận cha");
        long reply = commentOk(replier, blog, parent, "Câu trả lời sẽ bị xóa");

        JsonNode data = data(remove(replier, reply).andExpect(status().isOk()));

        assertThat(commentExists(reply)).isFalse();
        assertThat(commentRow(parent).get("status")).isEqualTo("PUBLISHED");
        assertThat(commentRow(parent).get("content")).isEqualTo("Bình luận cha");
        assertThat(node(data.get("comments"), parent).get("replies")).isEmpty();
        assertThat(commentCount(blog)).isEqualTo(1);
    }

    @Test
    @DisplayName("TC-1.10-05 [Luồng chính 6] Báo cáo đang mở về bình luận được đóng với ghi chú "
            + "\"Tác giả đã tự xóa nội dung.\"")
    void openReportsOnTheCommentAreClosed() throws Exception {
        long blog = post(member());
        long writer = member();
        long withReplies = commentOk(writer, blog, null, "Bình luận bị báo cáo, có câu trả lời");
        commentOk(member(), blog, withReplies, "Một câu trả lời");
        long alone = commentOk(writer, blog, null, "Bình luận bị báo cáo, không có câu trả lời");
        for (long comment : List.of(withReplies, alone)) {
            report(member(), comment).andExpect(status().isOk()).andExpect(jsonPath("$.message").value(MSG38));
            assertThat(openCases(comment)).isEqualTo(1);
        }

        remove(writer, withReplies).andExpect(status().isOk());
        remove(writer, alone).andExpect(status().isOk());

        for (long comment : List.of(withReplies, alone)) {
            assertThat(openCases(comment)).isZero();
            Map<String, Object> handled = jdbc.queryForMap("""
                    SELECT status, decision_note, handled_at FROM report_cases
                    WHERE target_type = 'COMMENT' AND target_id = ?""", comment);
            assertThat(handled.get("status")).isEqualTo("RESOLVED");
            assertThat(handled.get("decision_note")).isEqualTo(PLACEHOLDER_REASON);
            assertThat(handled.get("handled_at")).isNotNull();
        }
    }

    // ================================================================ 4a comment not found

    @Test
    @DisplayName("TC-1.10-06 [Luồng thay thế 4a] Bình luận đã xóa, đã thành chỗ trống, bị ẩn vì báo cáo, bị gỡ, "
            + "hoặc không có: \"Không tìm thấy bình luận này.\"")
    void goneCommentAnswersNotFound() throws Exception {
        long blog = post(member());
        long writer = member();
        long deleted = commentOk(writer, blog, null, "Đã xóa rồi");
        remove(writer, deleted).andExpect(status().isOk());
        long placeholder = commentOk(writer, blog, null, "Sẽ thành chỗ trống");
        commentOk(member(), blog, placeholder, "Câu trả lời giữ chỗ");
        remove(writer, placeholder).andExpect(status().isOk());
        long hidden = commentOk(writer, blog, null, "Bị ẩn vì nhiều báo cáo");
        jdbc.update("UPDATE comments SET status = 'HIDDEN' WHERE id = ?", hidden);
        long removed = commentOk(writer, blog, null, "Moderator đã gỡ");
        jdbc.update("UPDATE comments SET status = 'REMOVED', removed_at = NOW() WHERE id = ?", removed);
        int countBefore = commentCount(blog);

        for (long id : List.of(deleted, placeholder, hidden, removed, 999_999_999L)) {
            remove(writer, id).andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.message").value(COMMENT_NOT_FOUND));
        }
        // Nothing changed
        assertThat(commentRow(hidden).get("content")).isEqualTo("Bị ẩn vì nhiều báo cáo");
        assertThat(commentRow(removed).get("content")).isEqualTo("Moderator đã gỡ");
        assertThat(commentCount(blog)).isEqualTo(countBefore);
    }

    // ================================================================ 4b not the writer

    @Test
    @DisplayName("TC-1.10-07 [Luồng thay thế 4b – Phân quyền] Thành viên khác, tác giả bài, nhân viên: MSG18; "
            + "khách: 401; không xóa gì")
    void onlyTheWriterMayDelete() throws Exception {
        long author = member();
        long writer = member();
        long blog = post(author);
        long comment = commentOk(writer, blog, null, "Bình luận của tôi");

        remove(member(), comment).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value(MSG18));
        remove(author, comment).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value(MSG18));
        for (String role : List.of("MODERATOR", "ADMIN")) {
            remove(account(role, 30), comment).andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.message").value(MSG18));
        }
        remove(null, comment).andExpect(status().isUnauthorized());

        assertThat(commentRow(comment).get("status")).isEqualTo("PUBLISHED");
        assertThat(commentRow(comment).get("content")).isEqualTo("Bình luận của tôi");
        assertThat(commentCount(blog)).isEqualTo(1);
    }

    // ================================================================ post changed meanwhile

    @Test
    @DisplayName("TC-1.10-08 [Ngoại lệ] Bài bị ẩn sau khi bình luận: người viết vẫn xóa được bình luận của mình; "
            + "trang nhận data = null khi không còn xem được bài")
    void writerMayStillDeleteUnderAPostHiddenMeanwhile() throws Exception {
        long author = member();
        long writer = member();
        long blog = post(author);
        long comment = commentOk(writer, blog, null, "Bình luận trước khi bài bị ẩn");
        jdbc.update("UPDATE blogs SET status = 'HIDDEN' WHERE id = ?", blog);

        remove(writer, comment).andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value(DELETED))
                .andExpect(jsonPath("$.data").isEmpty());
        assertThat(commentExists(comment)).isFalse();
        assertThat(commentCount(blog)).isZero();
    }

    // ---------------------------------------------------------------- helpers

    private int openCases(long commentId) {
        return jdbc.queryForObject("""
                SELECT COUNT(*) FROM report_cases WHERE target_type = 'COMMENT' AND target_id = ? AND status = 'OPEN'""",
                Integer.class, commentId);
    }
}
