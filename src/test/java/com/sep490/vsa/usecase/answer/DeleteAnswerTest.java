package com.sep490.vsa.usecase.answer;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * UC 1.21 Delete Answer (Xóa câu trả lời), SRS Report 3 section II.1.18, through {@code DELETE /api/comments/{id}}.
 * Also follows CLAUDE.md "Hỏi-Đáp": solved = the best answer is still published by an active account; unanswered = no
 * published answer by an active account; a deleted best answer loses its mark and its +5 points.
 * <p>
 * Not covered here (page only): the confirmation dialog MSG16 and 3a Cancel, and 4c (the redirect to sign in and back).
 */
@DisplayName("UC 1.21 – Delete Answer (Xóa câu trả lời)")
class DeleteAnswerTest extends AnswerTestSupport {

    @Test
    @DisplayName("TC-1.21-01 [Luồng chính] Câu trả lời không có phản hồi bị xóa hẳn cùng lượt thích; số câu trả lời cập nhật; trả về \"Đã xóa bình luận.\"")
    void answerWithoutRepliesIsDeletedWithItsLikes() throws Exception {
        long asker = member();
        long helper = member();
        long other = member();
        long liker = member();
        long q = question(asker, token());
        long kept = answered(other, q, "Câu trả lời ở lại");
        long answerId = answered(helper, q, "Câu trả lời sẽ xóa");
        postAs(liker, null, "/api/comments/{id}/like", answerId).andExpect(status().isOk());
        assertThat(commentCount(q)).isEqualTo(2);
        int helperPoints = points(helper);

        JsonNode body = body(remove(helper, answerId).andExpect(status().isOk()));

        assertThat(body.get("message").asText()).isEqualTo("Đã xóa bình luận.");
        assertThat(commentExists(answerId)).isFalse();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM comment_likes WHERE comment_id = ?", Integer.class, answerId))
                .isZero();
        assertThat(commentCount(q)).isEqualTo(1);
        assertThat(body.get("data").get("commentCount").asInt()).isEqualTo(1);
        assertThat(body.get("data").get("comments")).extracting(c -> c.get("id").asLong()).containsExactly(kept);
        assertThat(points(helper)).isEqualTo(helperPoints);          // an ordinary answer earned no points
    }

    @Test
    @DisplayName("TC-1.21-02 [Luồng chính – bước 5] Câu trả lời có phản hồi chỉ bị làm trống, giữ chỗ \"Bình luận đã bị xóa.\" để phản hồi vẫn đọc được")
    void answerWithRepliesBecomesAPlaceholder() throws Exception {
        long asker = member();
        long helper = member();
        long liker = member();
        long q = question(asker, token());
        long answerId = answered(helper, q, "Câu trả lời có người hỏi lại");
        long replyId = replied(asker, q, answerId, "Bạn nói rõ hơn được không?");
        postAs(liker, null, "/api/comments/{id}/like", answerId).andExpect(status().isOk());

        JsonNode body = body(remove(helper, answerId).andExpect(status().isOk()));

        Map<String, Object> row = commentRow(answerId);
        assertThat(row.get("status")).isEqualTo("REMOVED");
        assertThat(row.get("content")).isEqualTo("");
        assertThat(((Number) row.get("like_count")).intValue()).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM comment_likes WHERE comment_id = ?", Integer.class, answerId))
                .isZero();

        JsonNode placeholder = node(body.get("data"), answerId);
        assertThat(placeholder.get("removed").asBoolean()).isTrue();
        assertThat(placeholder.get("content").asText()).isEmpty();
        assertThat(placeholder.get("author").isNull()).isTrue();
        assertThat(placeholder.get("replies")).extracting(r -> r.get("id").asLong()).containsExactly(replyId);
        assertThat(placeholder.get("replies").get(0).get("content").asText()).isEqualTo("Bạn nói rõ hơn được không?");
        assertThat(commentCount(q)).isEqualTo(1);                    // only the reply is still counted
    }

    @Test
    @DisplayName("TC-1.21-03 [Luồng chính – bước 6] Chỗ trống không còn phản hồi nào thì cũng bị xóa; câu hỏi trở lại \"Chưa ai trả lời\"")
    void placeholderLeftWithoutRepliesIsRemoved() throws Exception {
        String word = token();
        long asker = member();
        long helper = member();
        long replier = member();
        long q = question(asker, word);
        long answerId = answered(helper, q, "Câu trả lời sẽ thành chỗ trống");
        long replyId = replied(replier, q, answerId, "Phản hồi duy nhất");
        remove(helper, answerId).andExpect(status().isOk());
        assertThat(commentExists(answerId)).isTrue();

        remove(replier, replyId).andExpect(status().isOk());

        assertThat(commentExists(replyId)).isFalse();
        assertThat(commentExists(answerId)).isFalse();
        assertThat(commentsOf(q)).isZero();
        assertThat(commentCount(q)).isZero();
        assertThat(listed(word, "UNANSWERED")).containsExactly(q);
    }

    @Test
    @DisplayName("TC-1.21-04 [Luồng chính – bước 6] Báo cáo đang mở về câu trả lời được đóng với ghi chú \"Tác giả đã tự xóa nội dung.\"")
    void openReportsOnTheAnswerAreClosed() throws Exception {
        long asker = member();
        long helper = member();
        long reporter = member();
        long q = question(asker, token());
        long answerId = answered(helper, q, "Câu trả lời bị báo cáo");
        postAs(reporter, Map.of("reasonId", reportReason("Spam"), "note", "Quảng cáo"), "/api/comments/{id}/report", answerId)
                .andExpect(status().isOk());
        assertThat(jdbc.queryForObject("""
                SELECT status FROM report_cases WHERE target_type = 'COMMENT' AND target_id = ?""", String.class, answerId))
                .isEqualTo("OPEN");

        remove(helper, answerId).andExpect(status().isOk());

        Map<String, Object> reportCase = jdbc.queryForMap("""
                SELECT status, decision_note FROM report_cases WHERE target_type = 'COMMENT' AND target_id = ?""", answerId);
        assertThat(reportCase.get("status")).isEqualTo("RESOLVED");
        assertThat(reportCase.get("decision_note")).isEqualTo("Tác giả đã tự xóa nội dung.");
    }

    @Test
    @DisplayName("TC-1.21-05 [Luồng thay thế 5a] Xóa câu trả lời hay nhất: bỏ dấu, thu lại 5 điểm, câu hỏi chưa giải quyết, người hỏi không được báo và có thể chọn câu khác")
    void deletingTheBestAnswerRemovesTheMarkAndItsPoints() throws Exception {
        String word = token();
        long asker = member();
        long helper = member();
        long other = member();
        long q = question(asker, word);
        long best = answered(helper, q, "Câu trả lời hay nhất");
        long another = answered(other, q, "Một câu trả lời khác");
        chooseBest(asker, q, best).andExpect(status().isOk());
        assertThat(points(helper)).isEqualTo(5);
        assertThat(listed(word, "SOLVED")).containsExactly(q);
        int askerNotes = notificationsOf(asker).size();

        remove(helper, best).andExpect(status().isOk());

        assertThat(bestCommentId(q)).isNull();
        assertThat(points(helper)).isZero();
        assertThat(jdbc.queryForObject("""
                SELECT delta FROM point_transactions WHERE user_id = ? AND ref_type = 'COMMENT' AND ref_id = ?
                ORDER BY id DESC LIMIT 1""", Integer.class, helper, best)).isEqualTo(-5);
        assertThat(listed(word, "SOLVED")).isEmpty();
        assertThat(listed(word, "UNSOLVED")).containsExactly(q);
        assertThat(card(word).get("solved").asBoolean()).isFalse();
        assertThat(detail(null, q).get("bestCommentId").isNull()).isTrue();
        assertThat(notificationsOf(asker)).hasSize(askerNotes);

        // The asker may choose another answer: +5 to its writer
        chooseBest(asker, q, another).andExpect(status().isOk());
        assertThat(points(other)).isEqualTo(5);
        assertThat(listed(word, "SOLVED")).containsExactly(q);
    }

    @Test
    @DisplayName("TC-1.21-06 [Luồng thay thế 6a] Xóa câu trả lời cuối cùng: câu hỏi trở lại \"Chưa ai trả lời\"; còn câu trả lời khác thì không")
    void deletingTheLastAnswerMakesTheQuestionUnansweredAgain() throws Exception {
        String word = token();
        long asker = member();
        long first = member();
        long second = member();
        long q = question(asker, word);
        long a1 = answered(first, q, "Câu trả lời thứ nhất");
        long a2 = answered(second, q, "Câu trả lời thứ hai");
        assertThat(listed(word, "UNANSWERED")).isEmpty();

        remove(first, a1).andExpect(status().isOk());
        assertThat(listed(word, "UNANSWERED")).isEmpty();
        assertThat(card(word).get("unanswered").asBoolean()).isFalse();

        remove(second, a2).andExpect(status().isOk());
        assertThat(listed(word, "UNANSWERED")).containsExactly(q);
        JsonNode card = card(word);
        assertThat(card.get("unanswered").asBoolean()).isTrue();
        assertThat(card.get("answerCount").asInt()).isZero();
    }

    @Test
    @DisplayName("TC-1.21-07 [Luồng chính] Xóa một phản hồi dưới câu trả lời: chỉ phản hồi mất, câu trả lời giữ nguyên")
    void deletingAReplyKeepsTheAnswer() throws Exception {
        long asker = member();
        long helper = member();
        long q = question(asker, token());
        long answerId = answered(helper, q, "Câu trả lời chính");
        long replyId = replied(asker, q, answerId, "Phản hồi sẽ xóa");
        assertThat(commentCount(q)).isEqualTo(2);

        JsonNode body = body(remove(asker, replyId).andExpect(status().isOk()));

        assertThat(body.get("message").asText()).isEqualTo("Đã xóa bình luận.");
        assertThat(commentExists(replyId)).isFalse();
        assertThat(commentRow(answerId).get("status")).isEqualTo("PUBLISHED");
        assertThat(node(body.get("data"), answerId).get("replies")).isEmpty();
        assertThat(commentCount(q)).isEqualTo(1);
    }

    @Test
    @DisplayName("TC-1.21-08 [Luồng thay thế 4a] Câu trả lời đã bị xóa hoặc bị ẩn do báo cáo: \"Không tìm thấy bình luận này.\", không đổi gì")
    void answerAlreadyDeletedOrHiddenIsNotFound() throws Exception {
        long asker = member();
        long helper = member();
        long q = question(asker, token());
        long deleted = answered(helper, q, "Câu trả lời sẽ xóa hai lần");
        long hidden = answered(helper, q, "Câu trả lời bị ẩn");
        jdbc.update("UPDATE comments SET status = 'HIDDEN' WHERE id = ?", hidden);
        remove(helper, deleted).andExpect(status().isOk());

        assertMessage(body(remove(helper, deleted).andExpect(status().isNotFound())), COMMENT_NOT_FOUND);
        assertMessage(body(remove(helper, hidden).andExpect(status().isNotFound())), COMMENT_NOT_FOUND);
        assertThat(commentRow(hidden).get("status")).isEqualTo("HIDDEN");
    }

    @Test
    @DisplayName("TC-1.21-09 [Luồng thay thế 4b] Không phải người viết (người hỏi, thành viên khác) nhận MSG18; câu trả lời giữ nguyên")
    void onlyTheWriterMayDelete() throws Exception {
        long asker = member();
        long helper = member();
        long q = question(asker, token());
        long answerId = answered(helper, q, "Câu trả lời của helper");

        assertMessage(body(remove(asker, answerId).andExpect(status().isForbidden())), MSG18);
        assertMessage(body(remove(member(), answerId).andExpect(status().isForbidden())), MSG18);
        assertThat(commentRow(answerId).get("status")).isEqualTo("PUBLISHED");
        assertThat(commentCount(q)).isEqualTo(1);
    }

    @Test
    @DisplayName("TC-1.21-10 [Phân quyền] Khách bị từ chối 401; tài khoản nhân viên nhận MSG18; không xóa gì")
    void guestAndStaffCannotDelete() throws Exception {
        long helper = member();
        long q = question(member(), token());
        long answerId = answered(helper, q, "Câu trả lời gốc");

        remove(null, answerId).andExpect(status().isUnauthorized());
        assertMessage(body(remove(account("MODERATOR", 30), answerId).andExpect(status().isForbidden())), MSG18);
        assertThat(commentExists(answerId)).isTrue();
    }

    @Test
    @DisplayName("TC-1.21-11 [Ngoại lệ] Câu hỏi bị ẩn trong lúc đó: người viết vẫn xóa được câu trả lời của mình (data = null vì không mở lại được câu hỏi)")
    void writerMayStillDeleteUnderAHiddenQuestion() throws Exception {
        long asker = member();
        long helper = member();
        long q = question(asker, token());
        long answerId = answered(helper, q, "Câu trả lời dưới câu hỏi sẽ bị ẩn");
        jdbc.update("UPDATE blogs SET status = 'HIDDEN' WHERE id = ?", q);

        // Not in the SRS: the rule of BlogActionService / BlogDetailController ("Writers may still delete their own
        // comments under such a post"; the answer has data null when the post can no longer be shown)
        JsonNode body = body(remove(helper, answerId).andExpect(status().isOk()));

        assertThat(body.get("message").asText()).isEqualTo("Đã xóa bình luận.");
        assertThat(body.get("data").isNull()).isTrue();
        assertThat(commentExists(answerId)).isFalse();
    }
}
