package com.sep490.vsa.usecase.comment;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * UC 1.9 Update Comment (Cập nhật bình luận), {@code PUT /api/comments/{id}} with {@code {content}}.
 * <p>
 * Follows SRS Report 3, II.1.9 (normal flow 1–7, alternative flows 4a–4c, 5a) and CLAUDE.md "Blog feed – Post
 * Detail": own published comment, reply or answer; same length, keyword and link checks as a new one; sets
 * {@code is_edited}, keeps likes and the best-answer mark, no notification; "Edits are not limited" (no 15 s spacing,
 * daily number or repeated-text rule). UI-only flows (3a Hủy/Escape, 3b no change sends nothing, Enter/Shift+Enter,
 * 6c the text kept after signing in again) are not tested here.
 */
@DisplayName("UC 1.9 – Update Comment (Cập nhật bình luận)")
class UpdateCommentTest extends CommentUseCaseSupport {

    // ================================================================ normal flow

    @Test
    @DisplayName("TC-1.9-01 [Luồng chính] Người viết sửa bình luận: lưu nội dung mới, đánh dấu \"đã sửa\", giữ lượt "
            + "thích và câu trả lời, không báo cho ai, số bình luận không đổi")
    void writerEditsTheirComment() throws Exception {
        long author = member();
        long writer = member();
        long liker = member();
        long replier = member();
        long blog = post(author);
        long comment = commentOk(writer, blog, null, "Bình luận ban đầu");
        long reply = commentOk(replier, blog, comment, "Một câu trả lời");
        like(liker, comment).andExpect(status().isOk());
        int authorNotices = notificationsOf(author).size();
        int writerNotices = notificationsOf(writer).size();
        int replierNotices = notificationsOf(replier).size();

        JsonNode data = data(edit(writer, comment, "  Bình luận đã sửa lại cho rõ hơn  ")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.message").value(SAVED)));

        // Step 6: new text saved and marked edited
        assertThat(commentRow(comment).get("content")).isEqualTo("Bình luận đã sửa lại cho rõ hơn");
        assertThat(commentRow(comment).get("is_edited")).isEqualTo(true);
        assertThat(commentRow(comment).get("status")).isEqualTo("PUBLISHED");
        // Step 7: the refreshed post shows "· đã sửa", the like and the reply are kept
        assertThat(data.get("id").asLong()).isEqualTo(blog);
        JsonNode node = node(data.get("comments"), comment);
        assertThat(node.get("content").asText()).isEqualTo("Bình luận đã sửa lại cho rõ hơn");
        assertThat(node.get("edited").asBoolean()).isTrue();
        assertThat(node.get("mine").asBoolean()).isTrue();
        assertThat(node.get("likeCount").asInt()).isEqualTo(1);
        assertThat(node.get("replies")).hasSize(1);
        assertThat(node.get("replies").get(0).get("id").asLong()).isEqualTo(reply);
        assertThat(node.get("replies").get(0).get("edited").asBoolean()).isFalse();
        // Postconditions: the comment count and the notifications are unchanged
        assertThat(commentCount(blog)).isEqualTo(2);
        assertThat(notificationsOf(author)).hasSize(authorNotices);
        assertThat(notificationsOf(writer)).hasSize(writerNotices);
        assertThat(notificationsOf(replier)).hasSize(replierNotices);
    }

    @Test
    @DisplayName("TC-1.9-02 [Luồng chính] Sửa một câu trả lời (reply): chỉ reply đó được đánh dấu \"đã sửa\"")
    void writerEditsTheirReply() throws Exception {
        long blog = post(member());
        long first = member();
        long replier = member();
        long parent = commentOk(first, blog, null, "Bình luận gốc");
        long reply = commentOk(replier, blog, parent, "Câu trả lời ban đầu");

        JsonNode data = data(edit(replier, reply, "Câu trả lời đã sửa").andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value(SAVED)));

        JsonNode parentNode = node(data.get("comments"), parent);
        assertThat(parentNode.get("edited").asBoolean()).isFalse();
        assertThat(parentNode.get("content").asText()).isEqualTo("Bình luận gốc");
        JsonNode replyNode = parentNode.get("replies").get(0);
        assertThat(replyNode.get("id").asLong()).isEqualTo(reply);
        assertThat(replyNode.get("content").asText()).isEqualTo("Câu trả lời đã sửa");
        assertThat(replyNode.get("edited").asBoolean()).isTrue();
        assertThat(((Number) commentRow(reply).get("parent_id")).longValue()).isEqualTo(parent);
    }

    // ================================================================ 4a invalid content

    @Test
    @DisplayName("TC-1.9-03 [Luồng thay thế 4a] Nội dung trống hoặc chỉ khoảng trắng: \"Hãy viết nội dung bình luận.\", "
            + "bình luận giữ nguyên")
    void emptyTextIsRefused() throws Exception {
        long writer = member();
        long comment = commentOk(writer, post(member()), null, "Nội dung gốc");

        for (String empty : new String[]{"", "   ", null}) {
            edit(writer, comment, empty).andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.success").value(false))
                    .andExpect(jsonPath("$.message").value(EMPTY));
        }
        assertUnchanged(comment, "Nội dung gốc");
    }

    @Test
    @DisplayName("TC-1.9-04 [Biên] Sửa thành 2.001 ký tự bị từ chối, 2.000 ký tự được lưu")
    void lengthBoundary() throws Exception {
        long writer = member();
        long comment = commentOk(writer, post(member()), null, "Nội dung gốc");

        edit(writer, comment, chars(2001)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(TOO_LONG));
        assertUnchanged(comment, "Nội dung gốc");

        edit(writer, comment, chars(2000)).andExpect(status().isOk());
        assertThat((String) commentRow(comment).get("content")).hasSize(2000);
    }

    // ================================================================ 4b comment or post not found

    @Test
    @DisplayName("TC-1.9-05 [Luồng thay thế 4b] Bình luận không có, bị ẩn vì báo cáo, bị gỡ, hoặc đã xóa (chỉ còn chỗ "
            + "trống): \"Không tìm thấy bình luận này.\"")
    void goneCommentCannotBeEdited() throws Exception {
        long blog = post(member());
        long writer = member();
        long other = member();
        long hidden = commentOk(writer, blog, null, "Sẽ bị ẩn vì báo cáo");
        jdbc.update("UPDATE comments SET status = 'HIDDEN' WHERE id = ?", hidden);
        long removed = commentOk(writer, blog, null, "Moderator sẽ gỡ");
        jdbc.update("UPDATE comments SET status = 'REMOVED', removed_at = NOW() WHERE id = ?", removed);
        // A comment the writer deleted while it had replies stays as an empty placeholder
        long placeholder = commentOk(writer, blog, null, "Sẽ tự xóa");
        commentOk(other, blog, placeholder, "Câu trả lời được giữ lại");
        remove(writer, placeholder).andExpect(status().isOk());

        for (long id : List.of(hidden, removed, placeholder, 999_999_999L)) {
            edit(writer, id, "Sửa lại").andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.message").value(COMMENT_NOT_FOUND));
        }
        assertThat(commentRow(hidden).get("content")).isEqualTo("Sẽ bị ẩn vì báo cáo");
        assertThat(commentRow(placeholder).get("content")).isEqualTo("");
    }

    @Test
    @DisplayName("TC-1.9-06 [Luồng thay thế 4b] Bài không còn (bị ẩn, bị gỡ, tác giả bài bị khóa): MSG19")
    void commentOnAPostNoLongerAvailableAnswersMsg19() throws Exception {
        long writer = member();
        for (String change : List.of("HIDDEN", "REMOVED", "AUTHOR_LOCKED")) {
            long author = member();
            long blog = post(author);
            long comment = commentOk(writer, blog, null, "Bình luận trước khi bài mất");
            if ("AUTHOR_LOCKED".equals(change)) {
                setStatus(author, "LOCKED");
            } else {
                jdbc.update("UPDATE blogs SET status = ? WHERE id = ?", change, blog);
            }
            edit(writer, comment, "Sửa sau khi bài mất").andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.message").value(MSG19));
            assertUnchanged(comment, "Bình luận trước khi bài mất");
        }
    }

    // ================================================================ 4c not the writer

    @Test
    @DisplayName("TC-1.9-07 [Luồng thay thế 4c – Phân quyền] Thành viên khác, tác giả bài, nhân viên: MSG18; "
            + "khách: 401; bình luận giữ nguyên")
    void onlyTheWriterMayEdit() throws Exception {
        long author = member();
        long writer = member();
        long blog = post(author);
        long comment = commentOk(writer, blog, null, "Bình luận của tôi");

        edit(member(), comment, "Người khác sửa").andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value(MSG18));
        edit(author, comment, "Tác giả bài sửa").andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value(MSG18));
        for (String role : List.of("MODERATOR", "ADMIN")) {
            edit(account(role, 30), comment, "Nhân viên sửa").andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.message").value(MSG18));
        }
        edit(null, comment, "Khách sửa").andExpect(status().isUnauthorized());

        assertUnchanged(comment, "Bình luận của tôi");
    }

    // ================================================================ 5a community rules

    @Test
    @DisplayName("TC-1.9-08 [Luồng thay thế 5a] Từ khóa cấm (MSG36), link/số điện thoại khi chưa có tag, link rút gọn: "
            + "bị từ chối, bình luận giữ nguyên")
    void newTextIsCheckedLikeANewComment() throws Exception {
        long writer = member();
        long comment = commentOk(writer, post(member()), null, "Nội dung gốc");

        edit(writer, comment, "Chơi ở " + KEYWORD + " đi").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(MSG36));
        edit(writer, comment, "Xem https://example.com nhé").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(LINK_NEEDS_TAG));
        edit(writer, comment, "ib zalo 0901 234 567").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(CONTACT_NEEDS_TAG));
        edit(writer, comment, "Xem https://bit.ly/abc123 nhé").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(SHORT_LINK));
        assertUnchanged(comment, "Nội dung gốc");

        // With an identity tag a full link is allowed, a short link still is not
        grantMentorTag(writer);
        edit(writer, comment, "Xem https://bit.ly/abc123 nhé").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(SHORT_LINK));
        edit(writer, comment, "Xem https://example.com nhé").andExpect(status().isOk());
        assertThat(commentRow(comment).get("content")).isEqualTo("Xem https://example.com nhé");
    }

    @Test
    @DisplayName("TC-1.9-09 [Luồng chính 5] Giới hạn đăng của Thêm bình luận không áp dụng khi sửa (15 giây, "
            + "20 bình luận/ngày của tài khoản mới, nội dung lặp)")
    void postingLimitsDoNotApplyToEdits() throws Exception {
        Instant dayStart = japanToday().atStartOfDay(JAPAN).toInstant();
        assumeTrue(Duration.between(dayStart, Instant.now()).toMinutes() >= 10, "too close to midnight in Japan");
        long newbie = member(2);
        long author = member();
        long blog = post(author);

        // Right after commenting, a new comment must wait 15 s, an edit need not
        comment(newbie, blog, null, "Bình luận vừa gửi").andExpect(status().isOk());
        long comment = lastCommentId(blog, newbie);
        comment(newbie, blog, null, "Bình luận mới ngay sau").andExpect(status().isTooManyRequests());
        edit(newbie, comment, "Bình luận vừa gửi, sửa ngay").andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value(SAVED));

        // 20 comments today: a new comment gets MSG44, an edit does not
        Instant earlier = Instant.now().minus(Duration.ofMinutes(5));
        for (int i = 0; i < 19; i++) {
            insertComment(blog, newbie, null, "Bình luận cũ " + i, "PUBLISHED", earlier.plusSeconds(i));
        }
        ageCommentsOf(newbie, Duration.ofSeconds(20));
        comment(newbie, blog, null, "Bình luận mới thêm").andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.message").value(DAILY_LIMIT));
        edit(newbie, comment, "Bình luận vừa gửi, sửa lần hai").andExpect(status().isOk());

        // A long text already written on 3 other posts may still be the new text of an edit
        String text = "Mình có kinh nghiệm làm thủ tục này rồi, bạn cứ nhắn hỏi mình nhé mọi người ơi";
        for (int i = 0; i < 3; i++) {
            insertComment(post(author), newbie, null, text, "PUBLISHED", earlier.plusSeconds(30 + i));
        }
        edit(newbie, comment, text).andExpect(status().isOk());
        assertThat(commentRow(comment).get("content")).isEqualTo(text);
        assertThat(commentsBy(newbie)).isEqualTo(23);
    }

    // ---------------------------------------------------------------- helpers

    private void assertUnchanged(long commentId, String content) {
        assertThat(commentRow(commentId).get("content")).isEqualTo(content);
        assertThat(commentRow(commentId).get("is_edited")).isEqualTo(false);
    }
}
