package com.sep490.vsa.usecase.blog;

import com.sep490.vsa.feature.moderation.ImageModerator.Verdict;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * UC 1.7 Delete Post – SRS Report 3, "II. Use Case Specifications" 1.7 (normal flow 1–7, alternative flows 4a–4c,
 * postconditions) and CLAUDE.md "Post Detail (UC-10)": {@code DELETE /api/blogs/{id}} by the author is a hard delete,
 * the post's points are withdrawn, its files are discarded and the open report cases of the post and its comments are
 * closed; comments are deleted newest first (MySQL stops cascades at 15 levels).
 * <p>
 * Not testable on the server (pages only): step 2 dialog MSG16, 3a "Hủy", 7 the return to Blog, 4c the redirect to
 * sign in and back.
 */
@DisplayName("UC 1.7 – Delete Post (Xóa bài viết)")
class DeletePostTest extends PostWriteTestSupport {

    // ================================================================ normal flow

    @Test
    @DisplayName("TC-1.7-01 [Luồng chính – bước 4–7] Tác giả xóa bài: \"Đã xóa bài viết.\", bài cùng bình luận, lượt thích, lượt lưu, liên kết chủ đề/địa điểm bị xóa hẳn")
    void authorDeletesThePostWithEverythingAttached() throws Exception {
        String t = token();
        long author = member(30);
        long reader = member(30);
        long replier = member(30);
        long place = place("Ga " + t);
        long id = createdId(create(author, post("Sẽ xóa " + t, "Nội dung", List.of(topic("Kinh nghiệm"), topic("Khác")),
                List.of(place))));
        postAs(reader, null, "/api/blogs/{id}/like", id).andExpect(status().isOk());
        postAs(reader, null, "/api/blogs/{id}/save", id).andExpect(status().isOk());
        postAs(reader, Map.of("content", "Bình luận của mình."), "/api/blogs/{id}/comments", id)
                .andExpect(status().isOk());
        long comment = lastCommentId(id, reader);
        postAs(replier, Map.of("content", "Trả lời bình luận.", "parentId", comment), "/api/blogs/{id}/comments", id)
                .andExpect(status().isOk());
        postAs(replier, null, "/api/comments/{id}/like", comment).andExpect(status().isOk());

        deleteAs(author, "/api/blogs/{id}", id)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.message").value("Đã xóa bài viết."));

        assertThat(blogExists(id)).isFalse();
        for (String table : List.of("comments", "blog_likes", "saved_blogs", "blog_topic_links", "blog_place_links",
                "blog_media")) {
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE blog_id = ?", Integer.class, id))
                    .as(table).isZero();
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM comment_likes WHERE comment_id = ?", Integer.class,
                comment)).isZero();
        // Nobody can open it any more
        getAs(null, "/api/blogs/{id}", id).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value(MSG19));
        getAs(author, "/api/blogs/{id}", id).andExpect(status().isNotFound());
        getAs(reader, "/api/blogs?q={q}", t).andExpect(jsonPath("$.data.items").isEmpty());
    }

    @Test
    @DisplayName("TC-1.7-02 [Luồng chính – bước 5] Ảnh và video của bài bị xóa (đánh dấu xóa và xóa tệp)")
    void thePhotosAndTheVideoOfThePostAreDeleted() throws Exception {
        long author = member(30);
        long id = createdId(create(author, post("Có tệp " + token(), "Nội dung", List.of(topic("Kinh nghiệm")),
                List.of()), photo(), video(5)));
        awaitStatus(id, "PUBLISHED");
        List<Long> media = mediaOf(id);
        assertThat(media).hasSize(2);
        List<String> keys = media.stream().map(this::storageKey).toList();
        assertThat(keys).allMatch(this::fileExists);

        deleteAs(author, "/api/blogs/{id}", id).andExpect(status().isOk());
        assertThat(media).allMatch(this::mediaDeleted);
        await().atMost(Duration.ofSeconds(5)).until(() -> keys.stream().noneMatch(this::fileExists));
    }

    @Test
    @DisplayName("TC-1.7-03 [Luồng chính – bước 6] Điểm bài đã nhận (+10) bị thu lại, có dòng lịch sử điểm −10")
    void thePointsThePostEarnedAreWithdrawn() throws Exception {
        long author = member(30);
        long keep = writePost(author, "Giữ lại " + token());
        long id = writePost(author, "Sẽ xóa " + token());
        assertThat(points(author)).isEqualTo(20);

        deleteAs(author, "/api/blogs/{id}", id).andExpect(status().isOk());
        assertThat(points(author)).isEqualTo(10);
        assertThat(jdbc.queryForMap("SELECT delta, reason FROM point_transactions WHERE user_id = ? AND ref_type = 'BLOG' "
                + "AND ref_id = ? ORDER BY id DESC LIMIT 1", author, id))
                .containsEntry("delta", -10).containsEntry("reason", "CONTENT_DELETED");
        assertThat(blogExists(keep)).isTrue();
    }

    @Test
    @DisplayName("TC-1.7-04 [Luồng chính – bước 6] Báo cáo đang mở của bài và của bình luận được đóng với ghi chú \"Tác giả đã tự xóa nội dung.\"")
    void openReportsOfThePostAndItsCommentsAreClosed() throws Exception {
        long author = member(30);
        long commenter = member(30);
        long reporter = member(30);
        long id = writePost(author, "Bị báo cáo " + token());
        postAs(commenter, Map.of("content", "Bình luận bị báo cáo."), "/api/blogs/{id}/comments", id)
                .andExpect(status().isOk());
        long comment = lastCommentId(id, commenter);
        postAs(reporter, Map.of("reasonId", reportReason(), "note", "Sai sự thật"), "/api/blogs/{id}/report", id)
                .andExpect(status().isOk()).andExpect(jsonPath("$.message").value(MSG38));
        postAs(reporter, Map.of("reasonId", reportReason()), "/api/comments/{id}/report", comment)
                .andExpect(status().isOk());
        assertThat(openCases("BLOG", id)).isEqualTo(1);
        assertThat(openCases("COMMENT", comment)).isEqualTo(1);

        deleteAs(author, "/api/blogs/{id}", id).andExpect(status().isOk());
        for (Map<String, Object> c : List.of(caseOf("BLOG", id), caseOf("COMMENT", comment))) {
            assertThat(c).containsEntry("status", "RESOLVED")
                    .containsEntry("decision_note", "Tác giả đã tự xóa nội dung.");
            assertThat(c.get("handled_at")).isNotNull();
        }
        assertThat(openCases("BLOG", id)).isZero();
    }

    @Test
    @DisplayName("TC-1.7-05 [Hậu điều kiện] Địa điểm đã thêm qua bài và số bài trong ngày không thay đổi sau khi xóa")
    void placesAndTheDailyPostCountAreNotChanged() throws Exception {
        String t = token();
        long newbie = member(2);
        long place = place("Nơi " + t);
        long id = createdId(create(newbie, post("Bài 1 " + t, "Nội dung", List.of(topic("Kinh nghiệm")), List.of(place))));
        writePost(newbie, "Bài 2 " + t);
        writePost(newbie, "Bài 3 " + t);
        assertThat(usage(newbie, "posts")).isEqualTo(3);

        deleteAs(newbie, "/api/blogs/{id}", id).andExpect(status().isOk());
        assertThat(usage(newbie, "posts")).isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT status FROM places WHERE id = ?", String.class, place)).isEqualTo("ACTIVE");
        // Deleting does not give back a post of the day: the 4th post is still refused
        create(newbie, post("Bài 4 " + t, "Nội dung", List.of(topic("Kinh nghiệm")), List.of()))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.message").value(MSG44_POSTS));
    }

    @Test
    @DisplayName("TC-1.7-06 [Luồng chính] Xóa được bài có chuỗi trả lời sâu 20 tầng (bình luận được xóa từ mới đến cũ)")
    void aPostWithADeepReplyChainIsDeleted() throws Exception {
        long author = member(30);
        long commenter = member(30);
        long id = writePost(author, "Chuỗi dài " + token());
        Long parent = null;
        Instant at = Instant.now().minus(Duration.ofHours(1));
        for (int depth = 0; depth < 21; depth++) {
            jdbc.update("INSERT INTO comments (blog_id, author_id, parent_id, content, status, created_at) "
                    + "VALUES (?, ?, ?, ?, 'PUBLISHED', ?)", id, commenter, parent, "Tầng " + depth,
                    Timestamp.from(at.plusSeconds(depth)));
            parent = lastCommentId(id, commenter);
        }
        deleteAs(author, "/api/blogs/{id}", id).andExpect(status().isOk());
        assertThat(blogExists(id)).isFalse();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM comments WHERE blog_id = ?", Integer.class, id)).isZero();
    }

    @Test
    @DisplayName("TC-1.7-07 [Biên] Tác giả xóa được bài đang quét, bị từ chối hoặc bị ẩn do báo cáo (chưa bị Moderator gỡ); bài chưa từng được cộng điểm thì không bị trừ")
    void theAuthorDeletesAPostInAnyStateButRemoved() throws Exception {
        when(imageModerator.check(any(byte[].class))).thenReturn(new Verdict("ERROR", "{\"error\":\"down\"}"));
        long author = member(30);
        long processing = createdId(create(author, post("Đang quét " + token(), "Nội dung",
                List.of(topic("Kinh nghiệm")), List.of()), photo()));
        long rejected = insertBlog(author, "POST", "Bị từ chối " + token(), "Nội dung", "REJECTED", Instant.now(),
                topic("Kinh nghiệm"));
        long hidden = insertBlog(author, "POST", "Bị ẩn " + token(), "Nội dung", "HIDDEN", Instant.now(),
                topic("Kinh nghiệm"));
        long keep = writePost(author, "Giữ " + token());
        assertThat(points(author)).isEqualTo(10);

        for (long id : List.of(processing, rejected, hidden)) {
            deleteAs(author, "/api/blogs/{id}", id).andExpect(status().isOk())
                    .andExpect(jsonPath("$.message").value("Đã xóa bài viết."));
            assertThat(blogExists(id)).isFalse();
        }
        assertThat(points(author)).isEqualTo(10);
        assertThat(blogExists(keep)).isTrue();
    }

    // ================================================================ 4a – 4c

    @Test
    @DisplayName("TC-1.7-08 [Luồng thay thế 4a] Bài đã bị xóa, không tồn tại hoặc đã bị Moderator gỡ: MSG19, không xóa gì")
    void aDeletedMissingOrRemovedPostAnswersMsg19() throws Exception {
        long author = member(30);
        long id = writePost(author, "Xóa hai lần " + token());
        deleteAs(author, "/api/blogs/{id}", id).andExpect(status().isOk());
        deleteAs(author, "/api/blogs/{id}", id).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value(MSG19));
        deleteAs(author, "/api/blogs/{id}", Long.MAX_VALUE).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value(MSG19));

        long removed = writePost(author, "Bị gỡ " + token());
        jdbc.update("UPDATE blogs SET status = 'REMOVED', removed_at = NOW() WHERE id = ?", removed);
        deleteAs(author, "/api/blogs/{id}", removed).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value(MSG19));
        assertThat(blogExists(removed)).isTrue();
    }

    @Test
    @DisplayName("TC-1.7-09 [Luồng thay thế 4a] Mã của một câu hỏi không xóa được qua Blog (MSG19)")
    void aQuestionIsNotDeletedThroughTheBlog() throws Exception {
        long author = member(30);
        long question = publishedQuestion(author, "Câu hỏi " + token(), "Mô tả");
        deleteAs(author, "/api/blogs/{id}", question).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value(MSG19));
        assertThat(blogExists(question)).isTrue();
    }

    @Test
    @DisplayName("TC-1.7-10 [Luồng thay thế 4b – Phân quyền] Người không phải tác giả và tài khoản nhân viên nhận MSG18; bài và điểm giữ nguyên")
    void onlyTheAuthorDeletesThePost() throws Exception {
        long author = member(30);
        long other = member(30);
        long moderator = account("MODERATOR", 30);
        long id = writePost(author, "Của tác giả " + token());

        deleteAs(other, "/api/blogs/{id}", id).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value(MSG18));
        deleteAs(moderator, "/api/blogs/{id}", id).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value(MSG18));
        assertThat(blogExists(id)).isTrue();
        assertThat(points(author)).isEqualTo(10);
    }

    @Test
    @DisplayName("TC-1.7-11 [Luồng thay thế 4c] Chưa đăng nhập hoặc phiên đã hết (tài khoản bị khóa): 401, không xóa gì")
    void withoutASessionNothingIsDeleted() throws Exception {
        long author = member(30);
        long id = writePost(author, "Bài " + token());
        deleteAs(null, "/api/blogs/{id}", id).andExpect(status().isUnauthorized());
        setStatus(author, "LOCKED");
        deleteAs(author, "/api/blogs/{id}", id).andExpect(status().isUnauthorized());
        assertThat(blogExists(id)).isTrue();
    }

    // ---------------------------------------------------------------- helpers

    private int openCases(String type, long targetId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM report_cases WHERE target_type = ? AND target_id = ? "
                + "AND status = 'OPEN'", Integer.class, type, targetId);
    }

    private Map<String, Object> caseOf(String type, long targetId) {
        return jdbc.queryForMap("SELECT status, decision_note, handled_at FROM report_cases WHERE target_type = ? "
                + "AND target_id = ? ORDER BY id DESC LIMIT 1", type, targetId);
    }
}
