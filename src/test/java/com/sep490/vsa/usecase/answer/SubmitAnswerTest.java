package com.sep490.vsa.usecase.answer;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * UC 1.19 Submit Answer (Gửi câu trả lời), SRS Report 3 section II.1.16.
 * <p>
 * Follows the normal flow (steps 3–6: checks, publish at once, answer count, "Chưa ai trả lời", notifications, no
 * points) and the alternative flows the server decides (1a, 3a, 3b, 3c, 4a). The limits of 4a ("cần chốt" constants
 * of {@code BlogActionService}) and the "Chưa ai trả lời" rule (published top-level answer of an active account) are the
 * ones CLAUDE.md records under "Blog feed" and "Hỏi-Đáp".
 * <p>
 * Not covered here (page only): the toast texts the page shows itself, the text kept in the box after an error, the
 * highlight of the new answer and 5b (the sign-in message and the draft kept in sessionStorage).
 */
@DisplayName("UC 1.19 – Submit Answer (Gửi câu trả lời)")
class SubmitAnswerTest extends AnswerTestSupport {

    private static final String TOO_FAST = "Bạn bình luận hơi nhanh\\. Vui lòng đợi ([1-9]|1[0-5]) giây rồi thử lại\\.";
    private static final String DAILY_LIMIT = "Bạn đã đạt giới hạn bình luận trong ngày của tài khoản mới (dưới 7 ngày: "
            + "tối đa 20 bình luận mỗi ngày). Vui lòng thử lại vào ngày mai.";                          // MSG44
    private static final String REPEATED = "Bạn đã gửi nội dung này ở nhiều bài khác. Hãy viết bình luận riêng cho bài này.";

    // ================================================================ normal flow

    @Test
    @DisplayName("TC-1.19-01 [Luồng chính] Câu trả lời được đăng ngay, số câu trả lời tăng, câu hỏi rời \"Chưa ai trả lời\" và người trả lời không được cộng điểm")
    void answerIsPublishedAtOnceAndLeavesUnanswered() throws Exception {
        String word = token();
        long asker = member();
        long helper = member();
        long q = question(asker, word);
        assertThat(listed(word, "UNANSWERED")).containsExactly(q);
        assertThat(card(word).get("unanswered").asBoolean()).isTrue();
        int pointsBefore = points(helper);

        JsonNode body = body(answer(helper, q, "Bạn lên văn phòng quận mang theo hộ chiếu nhé.").andExpect(status().isOk()));

        assertThat(body.get("message").asText()).isEqualTo("Đã đăng câu trả lời.");
        long answerId = lastCommentId(q, helper);
        JsonNode node = node(body.get("data"), answerId);
        assertThat(node.get("content").asText()).isEqualTo("Bạn lên văn phòng quận mang theo hộ chiếu nhé.");
        assertThat(node.get("parentId").isNull()).isTrue();
        assertThat(node.get("author").get("id").asLong()).isEqualTo(helper);
        assertThat(node.get("mine").asBoolean()).isTrue();
        assertThat(body.get("data").get("commentCount").asInt()).isEqualTo(1);

        Map<String, Object> row = commentRow(answerId);
        assertThat(row.get("status")).isEqualTo("PUBLISHED");
        assertThat(row.get("parent_id")).isNull();
        assertThat(commentCount(q)).isEqualTo(1);

        assertThat(listed(word, "UNANSWERED")).isEmpty();
        JsonNode card = card(word);
        assertThat(card.get("unanswered").asBoolean()).isFalse();
        assertThat(card.get("answerCount").asInt()).isEqualTo(1);
        assertThat(card.get("solved").asBoolean()).isFalse();

        // Postconditions: "Answering earns no points"
        assertThat(points(helper)).isEqualTo(pointsBefore);
        assertThat(pointLines(helper)).isZero();
    }

    @Test
    @DisplayName("TC-1.19-02 [Luồng chính – bước 6] Người hỏi nhận thông báo \"<tên> đã trả lời câu hỏi “<tiêu đề>” của bạn.\" mở đúng câu hỏi")
    void askerIsNotifiedOfTheAnswer() throws Exception {
        long asker = member();
        long helper = member();
        long q = question(asker, token());

        answer(helper, q, "Mình làm thủ tục này rồi, không khó đâu.").andExpect(status().isOk());

        List<Map<String, Object>> notes = notificationsOf(asker);
        assertThat(notes).hasSize(1);
        assertThat(notes.get(0).get("type")).isEqualTo("NEW_COMMENT");
        assertThat(notes.get(0).get("content"))
                .isEqualTo(displayName(helper) + " đã trả lời câu hỏi “" + title(q) + "” của bạn.");
        assertThat(notes.get(0).get("ref_type")).isEqualTo("QUESTION");
        assertThat(((Number) notes.get(0).get("ref_id")).longValue()).isEqualTo(q);
        assertThat(notificationsOf(helper)).isEmpty();
    }

    @Test
    @DisplayName("TC-1.19-03 [Luồng chính – trả lời một câu trả lời] Phản hồi nằm dưới câu trả lời, được tính vào số câu trả lời; người hỏi và người viết câu trả lời đều được báo")
    void replyHangsUnderTheAnswerAndNotifiesBoth() throws Exception {
        String word = token();
        long asker = member();
        long helper = member();
        long other = member();
        long q = question(asker, word);
        long answerId = answered(helper, q, "Bạn hỏi ở quầy số 3 nhé.");

        JsonNode body = body(reply(other, q, answerId, "Quầy số 3 ở tầng mấy vậy bạn?").andExpect(status().isOk()));

        // The server answers "Đã gửi phản hồi."; the page shows its own toast "Đã đăng bình luận." (SRS step 7)
        assertThat(body.get("message").asText()).isEqualTo("Đã gửi phản hồi.");
        long replyId = lastCommentId(q, other);
        JsonNode answerNode = node(body.get("data"), answerId);
        assertThat(answerNode.get("replies")).hasSize(1);
        assertThat(answerNode.get("replies").get(0).get("id").asLong()).isEqualTo(replyId);
        assertThat(commentRow(replyId).get("parent_id")).isEqualTo(answerId);
        assertThat(commentCount(q)).isEqualTo(2);
        assertThat(card(word).get("answerCount").asInt()).isEqualTo(2);       // "answer count including replies"

        String name = displayName(other);
        assertThat(notificationsOf(asker)).extracting(n -> n.get("content")).contains(
                name + " đã bình luận bài “" + title(q) + "” của bạn.");
        assertThat(notificationsOf(helper)).extracting(n -> n.get("content")).containsExactly(
                name + " đã trả lời bình luận của bạn trong “" + title(q) + "”.");
        assertThat(notificationsOf(helper)).extracting(n -> n.get("ref_type")).containsOnly("QUESTION");
    }

    @Test
    @DisplayName("TC-1.19-04 [Luồng chính – bước 6] Không ai được báo về câu trả lời của chính mình; trả lời câu trả lời của người hỏi chỉ báo người hỏi một lần")
    void nobodyIsNotifiedAboutTheirOwnAnswer() throws Exception {
        long asker = member();
        long other = member();
        long q = question(asker, token());

        // The asker answers their own question: nobody is notified
        long own = answered(asker, q, "Mình tự tìm được: phải đặt lịch trước trên mạng.");
        assertThat(notificationsOf(asker)).isEmpty();

        // A reply under the asker's own answer: the asker hears it once, as the asker, not twice
        reply(other, q, own, "Cảm ơn bạn đã chia sẻ cách làm.").andExpect(status().isOk());
        assertThat(notificationsOf(asker)).extracting(n -> n.get("content")).containsExactly(
                displayName(other) + " đã bình luận bài “" + title(q) + "” của bạn.");

        // The asker replies under someone's reply: only that writer is notified, the asker hears nothing of their own
        ageCommentsOf(asker, Duration.ofSeconds(20));
        long otherReply = lastCommentId(q, other);
        reply(asker, q, otherReply, "Không có gì nhé.").andExpect(status().isOk());
        assertThat(notificationsOf(asker)).hasSize(1);
        assertThat(notificationsOf(other)).extracting(n -> n.get("content")).containsExactly(
                displayName(asker) + " đã trả lời bình luận của bạn trong “" + title(q) + "”.");
    }

    // ================================================================ 3a invalid content

    @Test
    @DisplayName("TC-1.19-05 [Luồng thay thế 3a] Nội dung trống hoặc chỉ có khoảng trắng bị từ chối, không lưu gì")
    void emptyAnswerIsRefused() throws Exception {
        long helper = member();
        long q = question(member(), token());

        // The page checks first and shows "Hãy viết câu trả lời." (SRS 3a); the server's own text is the comment one
        assertMessage(body(answer(helper, q, "").andExpect(status().isBadRequest())), EMPTY_TEXT);
        assertMessage(body(answer(helper, q, "   \n\t ").andExpect(status().isBadRequest())), EMPTY_TEXT);
        assertMessage(body(postAs(helper, Map.of(), "/api/questions/{id}/answers", q)
                .andExpect(status().isBadRequest())), EMPTY_TEXT);
        assertThat(commentsOf(q)).isZero();
    }

    @Test
    @DisplayName("TC-1.19-06 [Biên] Đúng 2.000 ký tự được đăng (khoảng trắng hai đầu bị bỏ); 2.001 ký tự bị từ chối \"Bình luận tối đa 2.000 ký tự.\"")
    void lengthBoundaryIs2000Characters() throws Exception {
        long q = question(member(), token());
        long tooLong = member();
        long exact = member();

        assertMessage(body(answer(tooLong, q, chars(2001)).andExpect(status().isBadRequest())), TOO_LONG);
        assertThat(commentsOf(q)).isZero();

        answer(exact, q, "  " + chars(2000) + "  ").andExpect(status().isOk());
        assertThat(commentRow(lastCommentId(q, exact)).get("content")).isEqualTo(chars(2000));
    }

    // ================================================================ 3b invalid reply

    @Test
    @DisplayName("TC-1.19-07 [Luồng thay thế 3b] Trả lời một câu trả lời không còn hiển thị (bị ẩn, đã xóa, thuộc câu hỏi khác) bị từ chối \"Bình luận bạn trả lời không còn nữa.\"")
    void replyToAnAnswerNoLongerShownIsRefused() throws Exception {
        long asker = member();
        long helper = member();
        long replier = member();
        long q = question(asker, token());
        long otherQ = question(asker, token());
        long hidden = answered(helper, q, "Câu trả lời sẽ bị ẩn vì báo cáo.");
        long elsewhere = answered(helper, otherQ, "Câu trả lời ở câu hỏi khác.");
        jdbc.update("UPDATE comments SET status = 'HIDDEN' WHERE id = ?", hidden);

        String gone = "Bình luận bạn trả lời không còn nữa.";
        assertMessage(body(reply(replier, q, hidden, "Mình cũng thắc mắc").andExpect(status().isBadRequest())), gone);
        assertMessage(body(reply(replier, q, elsewhere, "Mình cũng thắc mắc").andExpect(status().isBadRequest())), gone);
        assertMessage(body(reply(replier, q, Long.MAX_VALUE, "Mình cũng thắc mắc").andExpect(status().isBadRequest())), gone);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM comments WHERE author_id = ?", Integer.class, replier)).isZero();
    }

    @Test
    @DisplayName("TC-1.19-08 [Biên 3b] Chuỗi trả lời tối đa 20 tầng: trả lời ở tầng 20 được, sâu hơn bị từ chối \"Chuỗi trả lời đã quá dài…\"")
    void replyChainIsAtMost20LevelsDeep() throws Exception {
        long asker = member();
        long chainWriter = member();
        long replier = member();
        long q = question(asker, token());
        Instant earlier = Instant.now().minus(Duration.ofHours(1));
        Long parent = null;
        long[] chain = new long[21];                       // chain[d] sits at depth d (0 = the answer itself)
        for (int depth = 0; depth <= 20; depth++) {
            chain[depth] = insertComment(q, chainWriter, parent, "Tầng " + depth, earlier.plusSeconds(depth));
            parent = chain[depth];
        }

        // Under the comment at depth 19 the reply sits at depth 20: allowed
        reply(replier, q, chain[19], "Trả lời ở tầng 20").andExpect(status().isOk());

        // Under the comment at depth 20 it would be depth 21: refused
        ageCommentsOf(replier, Duration.ofSeconds(20));
        assertMessage(body(reply(replier, q, chain[20], "Trả lời ở tầng 21").andExpect(status().isBadRequest())),
                "Chuỗi trả lời đã quá dài. Hãy trả lời một bình luận ở phía trên.");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM comments WHERE author_id = ?", Integer.class, replier))
                .isEqualTo(1);
    }

    // ================================================================ 3c question no longer available

    @Test
    @DisplayName("TC-1.19-09 [Luồng thay thế 3c] Câu hỏi đã bị ẩn, đã bị gỡ hoặc đã bị xóa: trả về MSG19, không lưu gì")
    void questionHiddenRemovedOrDeletedAnswersMsg19() throws Exception {
        long asker = member();
        long helper = member();
        long hidden = question(asker, token());
        long removed = question(asker, token());
        long deleted = question(asker, token());
        jdbc.update("UPDATE blogs SET status = 'HIDDEN' WHERE id = ?", hidden);
        jdbc.update("UPDATE blogs SET status = 'REMOVED' WHERE id = ?", removed);
        jdbc.update("DELETE FROM blogs WHERE id = ?", deleted);

        for (long q : new long[]{hidden, removed, deleted}) {
            assertMessage(body(answer(helper, q, "Bạn thử hỏi ở quận xem.").andExpect(status().isNotFound())), MSG19);
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM comments WHERE author_id = ?", Integer.class, helper)).isZero();
    }

    @Test
    @DisplayName("TC-1.19-10 [Luồng thay thế 3c] Tài khoản người hỏi bị khóa hoặc đã xóa: câu hỏi coi như không còn (MSG19)")
    void askerLockedOrDeletedAnswersMsg19() throws Exception {
        long lockedAsker = member();
        long deletedAsker = member();
        long helper = member();
        long q1 = question(lockedAsker, token());
        long q2 = question(deletedAsker, token());
        setStatus(lockedAsker, "LOCKED");
        setStatus(deletedAsker, "DELETED");

        assertMessage(body(answer(helper, q1, "Câu trả lời cho người bị khóa").andExpect(status().isNotFound())), MSG19);
        assertMessage(body(answer(helper, q2, "Câu trả lời cho người đã xóa").andExpect(status().isNotFound())), MSG19);
        assertThat(commentsOf(q1) + commentsOf(q2)).isZero();
    }

    @Test
    @DisplayName("TC-1.19-11 [Ngoại lệ] Mã số của một bài Blog không nhận câu trả lời qua Hỏi-Đáp (MSG19)")
    void blogPostIdIsNotAQuestion() throws Exception {
        long author = member();
        long post = publishedPost(author, "Bài viết " + token(), "Nội dung bài viết");

        assertMessage(body(answer(member(), post, "Trả lời nhầm chỗ").andExpect(status().isNotFound())), MSG19);
        assertThat(commentsOf(post)).isZero();
    }

    // ================================================================ 1a answering not available

    @Test
    @DisplayName("TC-1.19-12 [Phân quyền 1a] Khách bị từ chối 401; tài khoản nhân viên nhận MSG18; tài khoản bị khóa mất phiên (401); không lưu gì")
    void guestStaffAndLockedAccountsCannotAnswer() throws Exception {
        long asker = member();
        long q = question(asker, token());
        long moderator = account("MODERATOR", 30);
        long locked = member();
        setStatus(locked, "LOCKED");

        answer(null, q, "Khách trả lời").andExpect(status().isUnauthorized());
        assertMessage(body(answer(moderator, q, "Nhân viên trả lời").andExpect(status().isForbidden())), MSG18);
        answer(locked, q, "Tài khoản bị khóa trả lời").andExpect(status().isUnauthorized());
        assertThat(commentsOf(q)).isZero();

        // The page reads the same rule: neither may interact
        assertThat(detail(null, q).get("viewer").get("canInteract").asBoolean()).isFalse();
        assertThat(detail(moderator, q).get("viewer").get("canInteract").asBoolean()).isFalse();
        assertThat(detail(member(), q).get("viewer").get("canInteract").asBoolean()).isTrue();
    }

    @Test
    @DisplayName("TC-1.19-13 [Luồng thay thế 1a] Câu hỏi chưa được đăng (đang quét ảnh/video): người hỏi thấy câu hỏi nhưng không nhận câu trả lời")
    void unpublishedQuestionTakesNoAnswers() throws Exception {
        long asker = member();
        long q = insertBlog(asker, "QA", "Câu hỏi đang quét " + token(), "Mô tả", "PROCESSING", Instant.now(),
                topic("Kinh nghiệm"));

        JsonNode page = detail(asker, q);
        assertThat(page.get("status").asText()).isEqualTo("PROCESSING");
        assertThat(page.get("viewer").get("canInteract").asBoolean()).isFalse();

        assertMessage(body(answer(asker, q, "Tự trả lời trước").andExpect(status().isNotFound())), MSG19);
        assertMessage(body(answer(member(), q, "Người khác trả lời").andExpect(status().isNotFound())), MSG19);
        assertThat(commentsOf(q)).isZero();
    }

    // ================================================================ 4a community rules

    @Test
    @DisplayName("TC-1.19-14 [Luồng thay thế 4a] Từ khóa cấm: trả về MSG36 nêu đúng từ khóa, không lưu gì")
    void bannedKeywordIsRefused() throws Exception {
        long q = question(member(), token());
        long helper = member();

        assertMessage(body(answer(helper, q, "Ai cần vay nóng thì nhắn mình nhé").andExpect(status().isBadRequest())),
                BANNED_VAY_NONG);
        assertThat(commentsOf(q)).isZero();
    }

    @Test
    @DisplayName("TC-1.19-15 [Luồng thay thế 4a] Link, số điện thoại chỉ dành cho tài khoản có tag định danh; link rút gọn bị cấm với mọi người")
    void linksAndContactsFollowTheTagRule() throws Exception {
        long q = question(member(), token());
        long plain = member();
        long tagged = member();
        grantMentorTag(tagged);

        assertMessage(body(answer(plain, q, "Xem hướng dẫn ở https://www.moj.go.jp/isa/").andExpect(status().isBadRequest())),
                LINK_NEEDS_TAG);
        assertMessage(body(answer(plain, q, "Gọi mình số 0901234567 nhé").andExpect(status().isBadRequest())),
                PHONE_NEEDS_TAG);
        assertMessage(body(answer(tagged, q, "Xem ở bit.ly/abc123 nhé").andExpect(status().isBadRequest())), SHORT_LINK);
        assertThat(commentsOf(q)).isZero();

        answer(tagged, q, "Xem hướng dẫn ở https://www.moj.go.jp/isa/").andExpect(status().isOk());
        assertThat(commentsOf(q)).isEqualTo(1);
    }

    @Test
    @DisplayName("TC-1.19-16 [Luồng thay thế 4a] Mỗi người chỉ gửi một câu trả lời mỗi 15 giây: \"Bạn bình luận hơi nhanh. Vui lòng đợi N giây rồi thử lại.\"")
    void oneAnswerEvery15Seconds() throws Exception {
        long asker = member();
        long helper = member();
        long q1 = question(asker, token());
        long q2 = question(asker, token());

        answer(helper, q1, "Câu trả lời thứ nhất").andExpect(status().isOk());
        JsonNode refused = body(answer(helper, q2, "Câu trả lời thứ hai").andExpect(status().isTooManyRequests()));
        assertThat(refused.get("success").asBoolean()).isFalse();
        assertThat(refused.get("message").asText()).matches(TOO_FAST);
        assertThat(commentsOf(q2)).isZero();

        // 15 seconds later the next answer goes through
        ageCommentsOf(helper, Duration.ofSeconds(16));
        answer(helper, q2, "Câu trả lời thứ hai").andExpect(status().isOk());
        assertThat(commentsOf(q2)).isEqualTo(1);
    }

    @Test
    @DisplayName("TC-1.19-17 [Luồng thay thế 4a] Tài khoản dưới 7 ngày: tối đa 20 câu trả lời/bình luận mỗi ngày (MSG44); tài khoản cũ không bị giới hạn")
    void newAccountsWriteAtMost20ADay() throws Exception {
        long asker = member();
        long q = question(asker, token());
        long other = question(asker, token());
        long newbie = member(2);
        long veteran = member(30);
        Instant earlierToday = earlierTodayInJapan();
        for (int i = 0; i < 20; i++) {
            insertComment(other, newbie, null, "Bình luận số " + i, earlierToday);
            insertComment(other, veteran, null, "Bình luận số " + i, earlierToday);
        }

        assertMessage(body(answer(newbie, q, "Câu trả lời thứ 21 hôm nay").andExpect(status().isTooManyRequests())),
                DAILY_LIMIT);
        answer(veteran, q, "Câu trả lời thứ 21 hôm nay").andExpect(status().isOk());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM comments WHERE blog_id = ? AND author_id = ?",
                Integer.class, q, newbie)).isZero();
    }

    @Test
    @DisplayName("TC-1.19-18 [Luồng thay thế 4a] Cùng một nội dung dài trên tối đa 3 câu hỏi khác trong 24 giờ; lần thứ tư bị từ chối")
    void theSameLongTextOnAFourthQuestionIsRefused() throws Exception {
        long asker = member();
        long helper = member();
        long[] qs = {question(asker, token()), question(asker, token()), question(asker, token()), question(asker, token())};
        String text = "Bạn nên mang theo thẻ cư trú và hộ chiếu khi đi làm thủ tục ở văn phòng quận.";
        Instant minuteAgo = Instant.now().minus(Duration.ofMinutes(1));
        insertComment(qs[0], helper, null, text, minuteAgo);
        insertComment(qs[1], helper, null, text, minuteAgo);

        // On 2 other questions so far: the third copy is still allowed
        answer(helper, qs[2], text).andExpect(status().isOk());

        // Now on 3 other questions: the fourth copy is refused, even with other accents and spaces
        ageCommentsOf(helper, Duration.ofSeconds(20));
        assertMessage(body(answer(helper, qs[3], text.toUpperCase()).andExpect(status().isBadRequest())), REPEATED);
        assertThat(commentsOf(qs[3])).isZero();

        // A short thank-you is never counted as repeated text
        String thanks = "Cảm ơn bạn đã chia sẻ";
        for (int i = 0; i < 3; i++) {
            insertComment(qs[i], helper, null, thanks, minuteAgo);
        }
        answer(helper, qs[3], thanks).andExpect(status().isOk());
    }

    // ================================================================ helpers

    /** A moment of today (Japan date) more than 15 seconds ago, for comments that count toward today's limit. */
    private static Instant earlierTodayInJapan() {
        Instant now = Instant.now();
        Instant dayStart = ZonedDateTime.now(JAPAN).toLocalDate().atStartOfDay(JAPAN).toInstant();
        Instant candidate = now.minus(Duration.ofMinutes(1));
        return candidate.isBefore(dayStart) ? dayStart : candidate;
    }
}
