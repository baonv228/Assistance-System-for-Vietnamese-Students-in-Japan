package com.sep490.vsa.usecase.answer;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * UC 1.20 Update Answer (Cập nhật câu trả lời), SRS Report 3 section II.1.17, through {@code PUT /api/comments/{id}}
 * (shared by comments and answers, CLAUDE.md "Post Detail": same length, keyword and link checks as a new one, sets
 * {@code is_edited}, keeps likes and the best-answer mark, no notification).
 * <p>
 * Not covered here (page only): 3a Cancel / Escape, 3b No change (the page sends nothing), Enter / Shift+Enter, the
 * edited text kept in the box after an error, and 6c (the sign-in message and the draft kept after signing in).
 */
@DisplayName("UC 1.20 – Update Answer (Cập nhật câu trả lời)")
class UpdateAnswerTest extends AnswerTestSupport {

    @Test
    @DisplayName("TC-1.20-01 [Luồng chính] Người viết sửa câu trả lời: lưu nội dung mới, đánh dấu \"đã sửa\", giữ lượt thích, phản hồi và số câu trả lời, không báo ai")
    void writerEditsTheirAnswer() throws Exception {
        String word = token();
        long asker = member();
        long helper = member();
        long liker = member();
        long q = question(asker, word);
        long answerId = answered(helper, q, "Bạn lên văn phòng quận nhé.");
        long replyId = replied(liker, q, answerId, "Văn phòng quận mở mấy giờ?");
        postAs(liker, null, "/api/comments/{id}/like", answerId).andExpect(status().isOk());
        int notesBefore = notificationsOf(asker).size() + notificationsOf(liker).size() + notificationsOf(helper).size();
        int countBefore = commentCount(q);

        JsonNode body = body(edit(helper, answerId, "Bạn lên văn phòng quận, nhớ mang hộ chiếu nhé.").andExpect(status().isOk()));

        // The server answers "Đã lưu bình luận."; the page shows "Đã lưu câu trả lời." for an answer itself (SRS step 7)
        assertThat(body.get("message").asText()).isEqualTo("Đã lưu bình luận.");
        JsonNode node = node(body.get("data"), answerId);
        assertThat(node.get("content").asText()).isEqualTo("Bạn lên văn phòng quận, nhớ mang hộ chiếu nhé.");
        assertThat(node.get("edited").asBoolean()).isTrue();
        assertThat(node.get("likeCount").asInt()).isEqualTo(1);
        assertThat(node.get("replies")).extracting(r -> r.get("id").asLong()).containsExactly(replyId);

        Map<String, Object> row = commentRow(answerId);
        assertThat(row.get("content")).isEqualTo("Bạn lên văn phòng quận, nhớ mang hộ chiếu nhé.");
        assertThat(row.get("is_edited")).isEqualTo(true);
        assertThat(row.get("status")).isEqualTo("PUBLISHED");
        assertThat(((Number) row.get("like_count")).intValue()).isEqualTo(1);
        assertThat(commentCount(q)).isEqualTo(countBefore);
        assertThat(card(word).get("answerCount").asInt()).isEqualTo(countBefore);
        assertThat(notificationsOf(asker).size() + notificationsOf(liker).size() + notificationsOf(helper).size())
                .isEqualTo(notesBefore);
    }

    @Test
    @DisplayName("TC-1.20-02 [Luồng chính] Người viết sửa phản hồi (trả lời dưới một câu trả lời): phản hồi vẫn nằm dưới câu trả lời và được đánh dấu \"đã sửa\"")
    void writerEditsTheirReply() throws Exception {
        long asker = member();
        long helper = member();
        long q = question(asker, token());
        long answerId = answered(helper, q, "Bạn hỏi ở quầy số 3 nhé.");
        long replyId = replied(asker, q, answerId, "Cảm ơn bạn");

        JsonNode body = body(edit(asker, replyId, "Cảm ơn bạn, mình hỏi được rồi.").andExpect(status().isOk()));

        assertThat(body.get("message").asText()).isEqualTo("Đã lưu bình luận.");
        JsonNode reply = node(body.get("data"), answerId).get("replies").get(0);
        assertThat(reply.get("id").asLong()).isEqualTo(replyId);
        assertThat(reply.get("content").asText()).isEqualTo("Cảm ơn bạn, mình hỏi được rồi.");
        assertThat(reply.get("edited").asBoolean()).isTrue();
        assertThat(commentRow(replyId).get("parent_id")).isEqualTo(answerId);
    }

    @Test
    @DisplayName("TC-1.20-03 [Luồng chính – bước 6] Sửa câu trả lời hay nhất: giữ dấu \"Câu trả lời hay nhất\" và +5 điểm, câu hỏi vẫn \"Đã giải quyết\"")
    void bestAnswerKeepsItsMarkAndPoints() throws Exception {
        String word = token();
        long asker = member();
        long helper = member();
        long q = question(asker, word);
        long answerId = answered(helper, q, "Đặt lịch trên mạng trước rồi mới đi.");
        chooseBest(asker, q, answerId).andExpect(status().isOk());
        assertThat(points(helper)).isEqualTo(5);
        int lines = pointLines(helper);

        JsonNode body = body(edit(helper, answerId, "Đặt lịch trên mạng trước, mang thẻ cư trú.").andExpect(status().isOk()));

        assertThat(body.get("data").get("bestCommentId").asLong()).isEqualTo(answerId);
        assertThat(node(body.get("data"), answerId).get("best").asBoolean()).isTrue();
        assertThat(bestCommentId(q)).isEqualTo(answerId);
        assertThat(points(helper)).isEqualTo(5);
        assertThat(pointLines(helper)).isEqualTo(lines);
        assertThat(listed(word, "SOLVED")).containsExactly(q);
    }

    @Test
    @DisplayName("TC-1.20-04 [Luồng chính – bước 5] Giới hạn gửi câu trả lời không áp dụng khi sửa (sửa ngay trong 15 giây; tài khoản mới đã đủ 20 bình luận hôm nay)")
    void postingLimitsDoNotApplyToEdits() throws Exception {
        long asker = member();
        long newbie = member(2);
        long q = question(asker, token());
        long other = question(asker, token());

        // Right after answering (inside the 15-second spacing)
        answer(newbie, q, "Câu trả lời đầu tiên").andExpect(status().isOk());
        long answerId = lastCommentId(q, newbie);
        edit(newbie, answerId, "Câu trả lời đầu tiên, sửa ngay").andExpect(status().isOk());

        // With 20 comments today, the daily limit of a new account does not block an edit either
        Instant earlier = Instant.now().minus(Duration.ofMinutes(1));
        for (int i = 0; i < 20; i++) {
            insertComment(other, newbie, null, "Bình luận " + i, earlier);
        }
        edit(newbie, answerId, "Câu trả lời đầu tiên, sửa lần hai").andExpect(status().isOk());
        assertThat(commentRow(answerId).get("content")).isEqualTo("Câu trả lời đầu tiên, sửa lần hai");
    }

    @Test
    @DisplayName("TC-1.20-05 [Luồng thay thế 4a] Nội dung trống bị từ chối \"Hãy viết nội dung bình luận.\", nội dung cũ giữ nguyên")
    void emptyTextIsRefused() throws Exception {
        long helper = member();
        long q = question(member(), token());
        long answerId = answered(helper, q, "Nội dung ban đầu");

        assertMessage(body(edit(helper, answerId, "").andExpect(status().isBadRequest())), EMPTY_TEXT);
        assertMessage(body(edit(helper, answerId, "   ").andExpect(status().isBadRequest())), EMPTY_TEXT);
        assertMessage(body(putAs(helper, Map.of(), "/api/comments/{id}", answerId).andExpect(status().isBadRequest())),
                EMPTY_TEXT);
        assertThat(commentRow(answerId).get("content")).isEqualTo("Nội dung ban đầu");
        assertThat(commentRow(answerId).get("is_edited")).isEqualTo(false);
    }

    @Test
    @DisplayName("TC-1.20-06 [Biên 4a] Đúng 2.000 ký tự được lưu; 2.001 ký tự bị từ chối \"Bình luận tối đa 2.000 ký tự.\"")
    void lengthBoundaryIs2000Characters() throws Exception {
        long helper = member();
        long q = question(member(), token());
        long answerId = answered(helper, q, "Nội dung ban đầu");

        assertMessage(body(edit(helper, answerId, chars(2001)).andExpect(status().isBadRequest())), TOO_LONG);
        assertThat(commentRow(answerId).get("content")).isEqualTo("Nội dung ban đầu");

        edit(helper, answerId, chars(2000)).andExpect(status().isOk());
        assertThat(commentRow(answerId).get("content")).isEqualTo(chars(2000));
    }

    @Test
    @DisplayName("TC-1.20-07 [Luồng thay thế 4b] Câu trả lời đã bị ẩn do báo cáo, đã bị xóa hoặc chỉ còn chỗ trống: \"Không tìm thấy bình luận này.\"")
    void answerHiddenOrDeletedIsNotFound() throws Exception {
        long asker = member();
        long helper = member();
        long q = question(asker, token());
        long hidden = answered(helper, q, "Câu trả lời sẽ bị ẩn");
        long withReply = answered(helper, q, "Câu trả lời sẽ thành chỗ trống");
        replied(asker, q, withReply, "Phản hồi giữ chỗ trống lại");
        jdbc.update("UPDATE comments SET status = 'HIDDEN' WHERE id = ?", hidden);
        remove(helper, withReply).andExpect(status().isOk());        // becomes the placeholder "Bình luận đã bị xóa."

        assertMessage(body(edit(helper, hidden, "Sửa câu trả lời bị ẩn").andExpect(status().isNotFound())),
                COMMENT_NOT_FOUND);
        assertMessage(body(edit(helper, withReply, "Sửa chỗ trống").andExpect(status().isNotFound())),
                COMMENT_NOT_FOUND);
        assertMessage(body(edit(helper, Long.MAX_VALUE, "Sửa câu trả lời đã xóa").andExpect(status().isNotFound())),
                COMMENT_NOT_FOUND);
        assertThat(commentRow(hidden).get("content")).isEqualTo("Câu trả lời sẽ bị ẩn");
    }

    @Test
    @DisplayName("TC-1.20-08 [Luồng thay thế 4b] Câu hỏi không còn (bị ẩn, bị gỡ, người hỏi bị khóa): trả về MSG19, nội dung giữ nguyên")
    void questionNoLongerAvailableAnswersMsg19() throws Exception {
        long asker = member();
        long helper = member();
        long hiddenQ = question(asker, token());
        long removedQ = question(asker, token());
        long a1 = answered(helper, hiddenQ, "Trả lời câu hỏi sẽ bị ẩn");
        long a2 = answered(helper, removedQ, "Trả lời câu hỏi sẽ bị gỡ");
        jdbc.update("UPDATE blogs SET status = 'HIDDEN' WHERE id = ?", hiddenQ);
        jdbc.update("UPDATE blogs SET status = 'REMOVED' WHERE id = ?", removedQ);

        assertMessage(body(edit(helper, a1, "Sửa").andExpect(status().isNotFound())), MSG19);
        assertMessage(body(edit(helper, a2, "Sửa").andExpect(status().isNotFound())), MSG19);

        long lockedAsker = member();
        long q3 = question(lockedAsker, token());
        long a3 = answered(helper, q3, "Trả lời người sẽ bị khóa");
        setStatus(lockedAsker, "LOCKED");
        assertMessage(body(edit(helper, a3, "Sửa").andExpect(status().isNotFound())), MSG19);

        assertThat(commentRow(a1).get("is_edited")).isEqualTo(false);
        assertThat(commentRow(a3).get("content")).isEqualTo("Trả lời người sẽ bị khóa");
    }

    @Test
    @DisplayName("TC-1.20-09 [Luồng thay thế 4c] Không phải người viết (người hỏi, thành viên khác) nhận MSG18; nội dung giữ nguyên")
    void onlyTheWriterMayEdit() throws Exception {
        long asker = member();
        long helper = member();
        long q = question(asker, token());
        long answerId = answered(helper, q, "Câu trả lời của helper");

        assertMessage(body(edit(asker, answerId, "Người hỏi sửa hộ").andExpect(status().isForbidden())), MSG18);
        assertMessage(body(edit(member(), answerId, "Người lạ sửa").andExpect(status().isForbidden())), MSG18);
        assertThat(commentRow(answerId).get("content")).isEqualTo("Câu trả lời của helper");
        assertThat(commentRow(answerId).get("is_edited")).isEqualTo(false);
    }

    @Test
    @DisplayName("TC-1.20-10 [Phân quyền] Khách bị từ chối 401; tài khoản nhân viên nhận MSG18; người viết bị khóa mất phiên (401)")
    void guestStaffAndLockedWriterCannotEdit() throws Exception {
        long helper = member();
        long q = question(member(), token());
        long answerId = answered(helper, q, "Câu trả lời gốc");

        edit(null, answerId, "Khách sửa").andExpect(status().isUnauthorized());
        assertMessage(body(edit(account("MODERATOR", 30), answerId, "Moderator sửa").andExpect(status().isForbidden())),
                MSG18);
        setStatus(helper, "LOCKED");
        edit(helper, answerId, "Sửa khi bị khóa").andExpect(status().isUnauthorized());
        assertThat(commentRow(answerId).get("content")).isEqualTo("Câu trả lời gốc");
    }

    @Test
    @DisplayName("TC-1.20-11 [Luồng thay thế 5a] Nội dung sửa chứa từ khóa cấm: MSG36, nội dung cũ giữ nguyên")
    void bannedKeywordIsRefused() throws Exception {
        long helper = member();
        long q = question(member(), token());
        long answerId = answered(helper, q, "Nội dung ban đầu");

        assertMessage(body(edit(helper, answerId, "Cần vay nóng thì nhắn mình").andExpect(status().isBadRequest())),
                BANNED_VAY_NONG);
        assertThat(commentRow(answerId).get("content")).isEqualTo("Nội dung ban đầu");
    }

    @Test
    @DisplayName("TC-1.20-12 [Luồng thay thế 5a] Link/số điện thoại khi chưa có tag định danh và link rút gọn bị từ chối; có tag thì thêm được link")
    void linksAndContactsFollowTheTagRule() throws Exception {
        long q = question(member(), token());
        long plain = member();
        long tagged = member();
        grantMentorTag(tagged);
        long plainAnswer = answered(plain, q, "Câu trả lời chưa có link");
        long taggedAnswer = answered(tagged, q, "Câu trả lời của người có tag");

        assertMessage(body(edit(plain, plainAnswer, "Xem ở https://www.moj.go.jp/isa/").andExpect(status().isBadRequest())),
                LINK_NEEDS_TAG);
        assertMessage(body(edit(plain, plainAnswer, "Gọi 0901234567 nhé").andExpect(status().isBadRequest())),
                PHONE_NEEDS_TAG);
        assertMessage(body(edit(tagged, taggedAnswer, "Xem ở tinyurl.com/abcd nhé").andExpect(status().isBadRequest())),
                SHORT_LINK);
        assertThat(commentRow(plainAnswer).get("content")).isEqualTo("Câu trả lời chưa có link");

        edit(tagged, taggedAnswer, "Xem ở https://www.moj.go.jp/isa/").andExpect(status().isOk());
        assertThat(commentRow(taggedAnswer).get("content")).isEqualTo("Xem ở https://www.moj.go.jp/isa/");
    }

    @Test
    @DisplayName("TC-1.20-13 [Luồng chính – hậu điều kiện] Sửa không đổi thời điểm gửi và thứ tự câu trả lời")
    void editKeepsTheOrderOfAnswers() throws Exception {
        long asker = member();
        long first = member();
        long second = member();
        long q = question(asker, token());
        long a1 = answered(first, q, "Câu trả lời thứ nhất");
        long a2 = answered(second, q, "Câu trả lời thứ hai");
        jdbc.update("UPDATE comments SET created_at = ? WHERE id = ?", Timestamp.from(Instant.now().minusSeconds(120)), a1);
        Object createdBefore = commentRow(a1).get("created_at");

        JsonNode data = data(edit(first, a1, "Câu trả lời thứ nhất, đã bổ sung").andExpect(status().isOk()));

        assertThat(commentRow(a1).get("created_at")).isEqualTo(createdBefore);
        assertThat(data.get("comments")).extracting(c -> c.get("id").asLong()).containsExactly(a1, a2);
    }
}
