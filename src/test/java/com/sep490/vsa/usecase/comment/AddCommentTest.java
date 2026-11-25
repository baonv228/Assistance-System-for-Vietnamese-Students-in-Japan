package com.sep490.vsa.usecase.comment;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.ResultActions;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * UC 1.8 Add Comment (Thêm bình luận), {@code POST /api/blogs/{id}/comments} with {@code {content, parentId}}.
 * <p>
 * Follows SRS Report 3, II.1.8 (normal flow 1–7, alternative flows 1a, 3a–3c, 4a–4e). The posting limits are the
 * values CLAUDE.md records as decided after the SRS ("Blog feed – New comments, replies and answers": one every 15 s,
 * 20 a Japan day for accounts younger than {@code new_account_days}, the same text of ≥ 30 letters on at most 3 other
 * posts in 24 h, replies at most 20 deep; {@code comment_count} counts only comments of active accounts).
 * UI-only steps (the boxes, highlighting, "text stays in the box", the sign-in link) are not tested here.
 */
@DisplayName("UC 1.8 – Add Comment (Thêm bình luận)")
class AddCommentTest extends CommentUseCaseSupport {

    // ================================================================ normal flow

    @Test
    @DisplayName("TC-1.8-01 [Luồng chính] Thành viên bình luận bài của người khác: đăng ngay, tăng số bình luận, "
            + "báo cho tác giả, không cộng điểm")
    void memberCommentsOnAPost() throws Exception {
        long author = member();
        long commenter = member();
        long blog = post(author);
        int pointsBefore = points(commenter);

        JsonNode data = data(comment(commenter, blog, null, "  Mua thẻ IC ngay ở ga đầu tiên nhé.  ")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.message").value(POSTED)));

        long id = lastCommentId(blog, commenter);
        // Step 5: published at once (text stripped), the post's comment count updated
        assertThat(commentRow(id).get("status")).isEqualTo("PUBLISHED");
        assertThat(commentRow(id).get("content")).isEqualTo("Mua thẻ IC ngay ở ga đầu tiên nhé.");
        assertThat(commentRow(id).get("parent_id")).isNull();
        assertThat(commentCount(blog)).isEqualTo(1);
        // Step 7: the refreshed post shows the new comment
        assertThat(data.get("id").asLong()).isEqualTo(blog);
        assertThat(data.get("commentCount").asInt()).isEqualTo(1);
        JsonNode node = node(data.get("comments"), id);
        assertThat(node).isNotNull();
        assertThat(node.get("content").asText()).isEqualTo("Mua thẻ IC ngay ở ga đầu tiên nhé.");
        assertThat(node.get("mine").asBoolean()).isTrue();
        assertThat(node.get("edited").asBoolean()).isFalse();
        assertThat(node.get("author").get("id").asLong()).isEqualTo(commenter);
        // Step 6: the post's author is told "<tên> đã bình luận bài “<tiêu đề>” của bạn."
        assertThat(notificationsOf(author)).containsExactly(commentedNotice(commenter, blog));
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM notifications WHERE user_id = ? AND type = 'NEW_COMMENT' AND ref_type = 'BLOG'
                AND ref_id = ?""", Integer.class, author, blog)).isEqualTo(1);
        assertThat(notificationsOf(commenter)).isEmpty();
        // Postcondition: comments earn no points
        assertThat(points(commenter)).isEqualTo(pointsBefore);
        assertThat(pointLines(commenter)).isZero();
    }

    @Test
    @DisplayName("TC-1.8-02 [Luồng chính] Trả lời một bình luận: nằm dưới bình luận gốc, báo cho tác giả bài và "
            + "người viết bình luận được trả lời")
    void replyIsNestedAndBothWritersAreTold() throws Exception {
        long author = member();
        long first = member();
        long replier = member();
        long blog = post(author);
        long parent = commentOk(first, blog, null, "Ở Tokyo nên làm thẻ ngân hàng nào?");

        JsonNode data = data(comment(replier, blog, parent, "Yucho là dễ làm nhất bạn ạ.")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value(POSTED)));

        long reply = lastCommentId(blog, replier);
        assertThat(((Number) commentRow(reply).get("parent_id")).longValue()).isEqualTo(parent);
        JsonNode parentNode = node(data.get("comments"), parent);
        assertThat(parentNode.get("replies")).hasSize(1);
        assertThat(parentNode.get("replies").get(0).get("id").asLong()).isEqualTo(reply);
        assertThat(commentCount(blog)).isEqualTo(2);
        assertThat(notificationsOf(author)).containsExactly(commentedNotice(first, blog), commentedNotice(replier, blog));
        assertThat(notificationsOf(first)).containsExactly(repliedNotice(replier, blog));
        assertThat(notificationsOf(replier)).isEmpty();
    }

    @Test
    @DisplayName("TC-1.8-03 [Luồng chính] Trả lời bình luận của chính tác giả bài: tác giả chỉ nhận một thông báo")
    void replyToThePostAuthorsCommentNotifiesThemOnce() throws Exception {
        long author = member();
        long replier = member();
        long blog = post(author);
        long authorsComment = commentOk(author, blog, null, "Mình bổ sung thêm: nhớ mang hộ chiếu.");

        comment(replier, blog, authorsComment, "Cảm ơn bạn nhiều nhé.").andExpect(status().isOk());

        assertThat(notificationsOf(author)).containsExactly(commentedNotice(replier, blog));
    }

    @Test
    @DisplayName("TC-1.8-04 [Luồng chính] Không ai được báo về bình luận của chính mình")
    void nobodyIsNotifiedAboutTheirOwnComment() throws Exception {
        long author = member();
        long other = member();
        long blog = post(author);

        long own = commentOk(author, blog, null, "Tác giả tự bình luận bài của mình.");
        commentOk(author, blog, own, "Tác giả tự trả lời bình luận của mình.");
        long others = commentOk(other, blog, null, "Bình luận của người khác.");
        comment(other, blog, others, "Người khác tự trả lời mình.").andExpect(status().isOk());

        assertThat(notificationsOf(author)).containsExactly(commentedNotice(other, blog), commentedNotice(other, blog));
        assertThat(notificationsOf(other)).isEmpty();
        assertThat(commentCount(blog)).isEqualTo(4);
    }

    // ================================================================ 3a invalid content

    @Test
    @DisplayName("TC-1.8-05 [Luồng thay thế 3a] Nội dung trống hoặc chỉ có khoảng trắng: \"Hãy viết nội dung bình luận.\"")
    void emptyContentIsRefused() throws Exception {
        long commenter = member();
        long blog = post(member());

        comment(commenter, blog, null, "").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value(EMPTY));
        comment(commenter, blog, null, "   \n\t ").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(EMPTY));
        comment(commenter, blog, null, null).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(EMPTY));

        assertThat(commentsBy(commenter)).isZero();
        assertThat(commentCount(blog)).isZero();
    }

    @Test
    @DisplayName("TC-1.8-06 [Biên] 2.000 ký tự được đăng, 2.001 ký tự bị từ chối \"Bình luận tối đa 2.000 ký tự.\"")
    void contentLengthBoundary() throws Exception {
        long commenter = member();
        long blog = post(member());

        comment(commenter, blog, null, chars(2001)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(TOO_LONG));
        assertThat(commentsBy(commenter)).isZero();

        // Spaces around the text are not counted (the text is stripped first)
        comment(commenter, blog, null, "  " + chars(2000) + "  ").andExpect(status().isOk());
        assertThat(((String) commentRow(lastCommentId(blog, commenter)).get("content"))).hasSize(2000);
    }

    // ================================================================ 1a who may comment

    @Test
    @DisplayName("TC-1.8-07 [Luồng thay thế 1a – Phân quyền] Khách nhận 401, tài khoản nhân viên nhận MSG18, "
            + "tài khoản bị khóa mất phiên; không lưu gì")
    void onlyActiveMembersMayComment() throws Exception {
        long blog = post(member());

        comment(null, blog, null, "Khách bình luận").andExpect(status().isUnauthorized());
        for (String role : List.of("MODERATOR", "ACADEMIC_MANAGER", "ADMIN")) {
            long staff = account(role, 30);
            comment(staff, blog, null, "Nhân viên bình luận").andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.message").value(MSG18));
        }
        long locked = member();
        setStatus(locked, "LOCKED");
        comment(locked, blog, null, "Tài khoản bị khóa bình luận").andExpect(status().isUnauthorized());

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM comments WHERE blog_id = ?", Integer.class, blog)).isZero();
        // What the page reads to show "Đăng nhập để bình luận…" / "Tài khoản của bạn chỉ xem được…"
        getAs(null, "/api/blogs/{id}", blog).andExpect(jsonPath("$.data.viewer.signedIn").value(false))
                .andExpect(jsonPath("$.data.viewer.canInteract").value(false));
        getAs(account("MODERATOR", 30), "/api/blogs/{id}", blog)
                .andExpect(jsonPath("$.data.viewer.signedIn").value(true))
                .andExpect(jsonPath("$.data.viewer.canInteract").value(false));
    }

    @Test
    @DisplayName("TC-1.8-08 [Luồng thay thế 1a] Tác giả bình luận bài chưa đăng của mình (đang quét, bị từ chối, "
            + "bị ẩn): bị từ chối với MSG19, trang nhận canInteract = false")
    void unpublishedPostTakesNoComments() throws Exception {
        long author = member();
        for (String status : List.of("PROCESSING", "REJECTED", "HIDDEN")) {
            long blog = insertBlog(author, "POST", "Bài chưa đăng " + token(), "Nội dung", status, Instant.now(),
                    topic("Kinh nghiệm"));
            comment(author, blog, null, "Tự bình luận bài chưa đăng").andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.message").value(MSG19));
            getAs(author, "/api/blogs/{id}", blog)
                    .andExpect(jsonPath("$.data.viewer.author").value(true))
                    .andExpect(jsonPath("$.data.viewer.canInteract").value(false));
        }
        assertThat(commentsBy(author)).isZero();
    }

    // ================================================================ 3b invalid reply

    @Test
    @DisplayName("TC-1.8-09 [Luồng thay thế 3b] Trả lời bình luận không còn (không có, bị ẩn, đã xóa, của bài khác, "
            + "của tài khoản bị khóa): \"Bình luận bạn trả lời không còn nữa.\"")
    void replyToACommentThatIsGoneIsRefused() throws Exception {
        long author = member();
        long blog = post(author);
        long otherBlog = post(author);
        long writer = member();
        long replier = member();
        Instant earlier = Instant.now().minus(Duration.ofMinutes(5));
        long hidden = insertComment(blog, writer, null, "Bị ẩn vì báo cáo", "HIDDEN", earlier);
        long removed = insertComment(blog, writer, null, "Moderator đã gỡ", "REMOVED", earlier);
        long elsewhere = insertComment(otherBlog, writer, null, "Ở bài khác", "PUBLISHED", earlier);
        long lockedWriter = member();
        long byLocked = insertComment(blog, lockedWriter, null, "Của tài khoản bị khóa", "PUBLISHED", earlier);
        setStatus(lockedWriter, "LOCKED");

        for (long parent : List.of(999_999_999L, hidden, removed, elsewhere, byLocked)) {
            comment(replier, blog, parent, "Trả lời bình luận không còn").andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.message").value(PARENT_GONE));
        }
        assertThat(commentsBy(replier)).isZero();
    }

    @Test
    @DisplayName("TC-1.8-10 [Luồng thay thế 3b – Biên] Chuỗi trả lời: độ sâu 20 được, độ sâu 21 bị từ chối")
    void replyChainIsAtMost20Deep() throws Exception {
        long author = member();
        long blog = post(author);
        long writer = member();
        long replier = member();
        // A chain of depths 0 to 19, written earlier
        Instant at = Instant.now().minus(Duration.ofHours(1));
        Long parent = null;
        List<Long> chain = new ArrayList<>();
        for (int depth = 0; depth < 20; depth++) {
            parent = insertComment(blog, writer, parent, "Tầng " + depth, "PUBLISHED", at.plusSeconds(depth));
            chain.add(parent);
        }

        long depth20 = commentOk(replier, blog, chain.get(19), "Trả lời ở độ sâu 20");
        comment(replier, blog, depth20, "Trả lời ở độ sâu 21").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(TOO_DEEP));
        assertThat(commentsBy(replier)).isEqualTo(1);
    }

    // ================================================================ 3c post no longer available

    @Test
    @DisplayName("TC-1.8-11 [Luồng thay thế 3c] Bài bị ẩn, bị gỡ, đã xóa, là câu hỏi, hoặc tác giả bị khóa/xóa: MSG19")
    void postNoLongerAvailableAnswersMsg19() throws Exception {
        long commenter = member();
        long author = member();
        List<Long> gone = new ArrayList<>();
        for (String status : List.of("HIDDEN", "REMOVED")) {
            long blog = post(author);
            jdbc.update("UPDATE blogs SET status = ? WHERE id = ?", status, blog);
            gone.add(blog);
        }
        long lockedAuthor = member();
        gone.add(post(lockedAuthor));
        setStatus(lockedAuthor, "LOCKED");
        long deletedAuthor = member();
        gone.add(post(deletedAuthor));
        setStatus(deletedAuthor, "DELETED");
        // A question is commented through /api/questions (Hỏi-Đáp), never through /api/blogs
        gone.add(publishedQuestion(author, "Câu hỏi " + token(), "Mô tả"));
        gone.add(999_999_999L);

        for (long blog : gone) {
            comment(commenter, blog, null, "Bình luận bài không còn").andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.message").value(MSG19));
        }
        assertThat(commentsBy(commenter)).isZero();
    }

    // ================================================================ 4a / 4b community rules

    @Test
    @DisplayName("TC-1.8-12 [Luồng thay thế 4a] Từ khóa cấm (cả khi viết hoa): MSG36 nêu đúng từ khóa, không lưu gì")
    void bannedKeywordIsRefused() throws Exception {
        long commenter = member();
        long blog = post(member());

        comment(commenter, blog, null, "Vào " + KEYWORD + " chơi đi mọi người").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(MSG36));
        comment(commenter, blog, null, "Vào FB88 chơi đi mọi người").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(MSG36));

        assertThat(commentsBy(commenter)).isZero();
        assertThat(commentCount(blog)).isZero();
    }

    @Test
    @DisplayName("TC-1.8-13 [Luồng thay thế 4b] Link, số điện thoại, Zalo chỉ cho tài khoản có tag định danh; "
            + "link rút gọn bị cấm với mọi người")
    void linksAndContactsNeedAnIdentityTag() throws Exception {
        long blog = post(member());
        long plain = member();
        long tagged = member();
        grantMentorTag(tagged);

        comment(plain, blog, null, "Xem thêm ở https://example.com/huong-dan nhé").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(LINK_NEEDS_TAG));
        comment(plain, blog, null, "Cần gì cứ gọi 090 1234 5678").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(CONTACT_NEEDS_TAG));
        comment(plain, blog, null, "ib zalo 0901 234 567").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(CONTACT_NEEDS_TAG));
        comment(tagged, blog, null, "Xem ở https://bit.ly/abc123 nhé").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(SHORT_LINK));
        assertThat(commentsBy(plain) + commentsBy(tagged)).isZero();

        // An account with an identity tag may write a full link and a phone number
        commentOk(tagged, blog, null, "Xem ở https://example.com/huong-dan nhé");
        commentOk(tagged, blog, null, "Cần gì cứ gọi 090 1234 5678");
        assertThat(commentsBy(tagged)).isEqualTo(2);
    }

    @Test
    @DisplayName("TC-1.8-14 [Thứ tự kiểm tra] Từ khóa cấm trước, rồi link, rồi giới hạn đăng (bước 4)")
    void rulesAreCheckedInTheDocumentedOrder() throws Exception {
        long commenter = member();
        long blog = post(member());
        // Keyword before link
        comment(commenter, blog, null, KEYWORD + " https://example.com").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(MSG36));

        // Right after a comment, the 15 s spacing applies, yet a keyword or a link is reported first
        comment(commenter, blog, null, "Bình luận đầu tiên.").andExpect(status().isOk());
        comment(commenter, blog, null, "Ghé " + KEYWORD + " nhé").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(MSG36));
        comment(commenter, blog, null, "Xem https://example.com nhé").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(LINK_NEEDS_TAG));
        comment(commenter, blog, null, "Bình luận thứ hai.").andExpect(status().isTooManyRequests());
        assertThat(commentsBy(commenter)).isEqualTo(1);
    }

    // ================================================================ 4c–4e posting limits (CLAUDE.md values)

    @Test
    @DisplayName("TC-1.8-15 [Luồng thay thế 4c] Hai bình luận trong 15 giây: \"Bạn bình luận hơi nhanh. "
            + "Vui lòng đợi N giây rồi thử lại.\"; người khác không bị chặn; qua 15 giây thì đăng được")
    void oneNewCommentEvery15Seconds() throws Exception {
        long commenter = member();
        long other = member();
        long blog = post(member());
        long otherBlog = post(member());

        comment(commenter, blog, null, "Bình luận thứ nhất").andExpect(status().isOk());
        long first = lastCommentId(blog, commenter);
        // Another post and a reply count the same (CLAUDE.md: "one per user every 15 s")
        for (ResultActions tooFast : List.of(
                comment(commenter, otherBlog, null, "Bình luận ở bài khác"),
                comment(commenter, blog, first, "Tự trả lời"))) {
            tooFast.andExpect(status().isTooManyRequests());
            String message = body(tooFast).get("message").asText();
            Matcher m = Pattern.compile(TOO_FAST_PATTERN).matcher(message);
            assertThat(m.matches()).as(message).isTrue();
            assertThat(Integer.parseInt(m.group(1))).isBetween(1, 15);
        }
        // Somebody else is not slowed down
        comment(other, blog, null, "Mình cũng nghĩ vậy").andExpect(status().isOk());
        assertThat(commentsBy(commenter)).isEqualTo(1);

        // 15 seconds later the next comment goes through
        ageCommentsOf(commenter, Duration.ofSeconds(16));
        comment(commenter, otherBlog, null, "Bình luận ở bài khác").andExpect(status().isOk());
        assertThat(commentsBy(commenter)).isEqualTo(2);
    }

    @Test
    @DisplayName("TC-1.8-16 [Luồng thay thế 4d – Biên] Tài khoản dưới 7 ngày: bình luận thứ 20 trong ngày được, "
            + "thứ 21 nhận MSG44; tài khoản 8 ngày không bị giới hạn")
    void newAccountsWriteAtMost20CommentsADay() throws Exception {
        Instant now = Instant.now();
        Instant dayStart = japanToday().atStartOfDay(JAPAN).toInstant();
        assumeTrue(Duration.between(dayStart, now).toMinutes() >= 10, "too close to midnight in Japan");
        // Today in Japan, far enough from midnight that moving them 20 s back (commentOk) keeps them in the day
        Instant from = dayStart.isAfter(now.minus(Duration.ofHours(2))) ? dayStart.plus(Duration.ofMinutes(2))
                : now.minus(Duration.ofHours(2));
        long blog = post(member());
        long newbie = member(6);
        long veteran = member(8);
        for (int i = 0; i < 19; i++) {
            insertComment(blog, newbie, null, "Bình luận cũ " + i, "PUBLISHED", from.plusSeconds(i));
            insertComment(blog, veteran, null, "Bình luận cũ " + i, "PUBLISHED", from.plusSeconds(i));
        }

        commentOk(newbie, blog, null, "Bình luận thứ 20 hôm nay");
        comment(newbie, blog, null, "Bình luận thứ 21 hôm nay").andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.message").value(DAILY_LIMIT));
        assertThat(commentsBy(newbie)).isEqualTo(20);

        commentOk(veteran, blog, null, "Bình luận thứ 20 hôm nay");
        commentOk(veteran, blog, null, "Bình luận thứ 21 hôm nay");
        assertThat(commentsBy(veteran)).isEqualTo(21);
    }

    @Test
    @DisplayName("TC-1.8-17 [Luồng thay thế 4e – Biên] Cùng một đoạn dài đã có ở 2 bài khác thì được, ở 3 bài khác "
            + "thì bị từ chối; đoạn ngắn và chính bài đó không bị tính")
    void theSameLongTextOnMoreThanThreeOtherPostsIsRefused() throws Exception {
        long author = member();
        long commenter = member();
        String text = "Mình có kinh nghiệm làm thủ tục này rồi, bạn cứ nhắn hỏi mình nhé mọi người ơi";
        List<Long> posts = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            posts.add(post(author));
        }
        Instant earlier = Instant.now().minus(Duration.ofHours(1));
        insertComment(posts.get(0), commenter, null, text, "PUBLISHED", earlier);
        insertComment(posts.get(1), commenter, null, text, "PUBLISHED", earlier.plusSeconds(1));

        // On 2 other posts so far: the 3rd post is allowed
        commentOk(commenter, posts.get(2), null, text);
        // On 3 other posts: refused on a 4th, also when only case, accents and punctuation differ
        comment(commenter, posts.get(3), null, text).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(REPEATED));
        comment(commenter, posts.get(3), null,
                "MINH CO KINH NGHIEM lam thu tuc nay roi!!! Ban cu nhan hoi minh nhe, moi nguoi oi.")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(REPEATED));
        // The same post again is not "another post"
        commentOk(commenter, posts.get(0), null, text);
        // A short text (< 30 letters) is never counted
        for (long blog : posts) {
            commentOk(commenter, blog, null, "Cảm ơn bạn đã chia sẻ nhé!");
        }
        assertThat(commentsBy(commenter)).isEqualTo(2 + 1 + 1 + 5);
    }

    // ================================================================ comment count of active accounts

    @Test
    @DisplayName("TC-1.8-18 [Luồng chính 5] Số bình luận chỉ đếm bình luận đã đăng của tài khoản đang hoạt động")
    void commentCountCountsOnlyShownComments() throws Exception {
        long blog = post(member());
        long locked = member();
        Instant earlier = Instant.now().minus(Duration.ofMinutes(10));
        insertComment(blog, locked, null, "Của tài khoản bị khóa", "PUBLISHED", earlier);
        setStatus(locked, "LOCKED");
        insertComment(blog, member(), null, "Bị ẩn vì báo cáo", "HIDDEN", earlier);
        long commenter = member();

        JsonNode data = data(comment(commenter, blog, null, "Bình luận mới").andExpect(status().isOk()));

        assertThat(commentCount(blog)).isEqualTo(1);
        assertThat(data.get("commentCount").asInt()).isEqualTo(1);
        assertThat(data.get("comments")).hasSize(1);
    }
}
