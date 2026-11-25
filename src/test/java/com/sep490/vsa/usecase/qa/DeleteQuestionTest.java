package com.sep490.vsa.usecase.qa;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * UC 1.16 Delete Question ("Xóa câu hỏi") – SRS Report 3, II.1.13. CLAUDE.md ("Blog feed – Post Detail",
 * "Hỏi-Đáp"): {@code DELETE /api/questions/{id}} is a hard delete of the asker's own question, its points are
 * withdrawn, open report cases are closed; an id of the other kind answers 404.
 * <p>
 * Not testable on the server (UI only): 2 the MSG16 dialog, 3a Cancel, 4c the redirect to sign-in and back
 * (the API answers 401 and deletes nothing, TC-1.16-09), "returns to Hỏi-Đáp".
 */
@DisplayName("UC 1.16 – Delete Question (Xóa câu hỏi)")
class DeleteQuestionTest extends QuestionTestSupport {

    @Test
    @DisplayName("TC-1.16-01 [Luồng chính 4-7] – Người hỏi xóa câu hỏi: \"Đã xóa câu hỏi.\"; câu hỏi, câu trả lời, phản hồi, lượt thích, lượt lưu, liên kết chủ đề và địa điểm bị xóa hẳn")
    void askerDeletesTheQuestionWithEverythingUnderIt() throws Exception {
        String t = token();
        long asker = member();
        long helper = member();
        long fan = member();
        long place = place("Chỗ hỏi " + t);
        long id = ask(asker, "Sẽ xóa " + t, "Mô tả", topics("Nhà ở", "Y tế"), List.of(place));
        long answer = answerQuestion(helper, id, "Câu trả lời sẽ bị xóa theo.");
        long reply = reply(fan, id, answer, "Phản hồi dưới câu trả lời.");
        postAs(fan, null, "/api/questions/{id}/like", id).andExpect(status().isOk());
        postAs(fan, null, "/api/questions/{id}/save", id).andExpect(status().isOk());
        postAs(fan, null, "/api/comments/{id}/like", answer).andExpect(status().isOk());

        deleteAs(asker, "/api/questions/{id}", id)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.message").value(DELETED));

        assertThat(blogExists(id)).isFalse();
        assertThat(commentExists(answer)).isFalse();
        assertThat(commentExists(reply)).isFalse();
        for (String table : new String[]{"blog_likes", "saved_blogs", "blog_topic_links", "blog_place_links"}) {
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE blog_id = ?", Integer.class, id))
                    .as(table).isZero();
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM comment_likes WHERE comment_id = ?", Integer.class, answer))
                .isZero();
        // The place itself stays on the map (Delete Post postcondition)
        assertThat(jdbc.queryForObject("SELECT status FROM places WHERE id = ?", String.class, place)).isEqualTo("ACTIVE");
        getAs(null, "/api/questions/{id}", id).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value(MSG19));
        assertThat(listIds(asker, t, null, null)).isEmpty();
        assertThat(data(getAs(fan, "/api/me/saved")).toString()).doesNotContain("Sẽ xóa " + t);
    }

    @Test
    @DisplayName("TC-1.16-02 [Luồng chính 6 – Hậu điều kiện] – Điểm câu hỏi (+10) bị thu lại; +5 của người có câu trả lời hay nhất KHÔNG bị thu lại")
    void questionPointsAreWithdrawnButNotTheBestAnswerBonus() throws Exception {
        String t = token();
        long asker = member();
        long helper = member();
        long id = ask(asker, "Điểm sẽ bị thu " + t, "");
        long answer = answerQuestion(helper, id, "Câu trả lời hay nhất.");
        chooseBest(asker, id, answer).andExpect(status().isOk());
        assertThat(points(asker)).isEqualTo(10);
        assertThat(points(helper)).isEqualTo(5);

        deleteAs(asker, "/api/questions/{id}", id).andExpect(status().isOk());

        assertThat(points(asker)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM point_transactions WHERE user_id = ? AND ref_type = 'BLOG' "
                + "AND ref_id = ? AND reason = 'CONTENT_DELETED' AND delta = -10", Integer.class, asker, id)).isEqualTo(1);
        assertThat(points(helper)).isEqualTo(5);
    }

    @Test
    @DisplayName("TC-1.16-03 [Luồng chính 6] – Báo cáo đang mở về câu hỏi và các câu trả lời được đóng với ghi chú \"Tác giả đã tự xóa nội dung.\"")
    void openReportsAreClosed() throws Exception {
        String t = token();
        long asker = member();
        long helper = member();
        long reporter = member();
        long id = ask(asker, "Bị báo cáo " + t, "");
        long answer = answerQuestion(helper, id, "Câu trả lời bị báo cáo.");
        reportQuestion(reporter, id, "Spam").andExpect(status().isOk());
        reportComment(reporter, answer, "Sai sự thật").andExpect(status().isOk());

        deleteAs(asker, "/api/questions/{id}", id).andExpect(status().isOk());

        List<Map<String, Object>> cases = jdbc.queryForList("""
                SELECT target_type, status, decision_note FROM report_cases
                WHERE (target_type = 'BLOG' AND target_id = ?) OR (target_type = 'COMMENT' AND target_id = ?)""", id, answer);
        assertThat(cases).hasSize(2);
        assertThat(cases).allSatisfy(c -> {
            assertThat(c.get("status")).isEqualTo("RESOLVED");
            assertThat(c.get("decision_note")).isEqualTo("Tác giả đã tự xóa nội dung.");
        });
    }

    @Test
    @DisplayName("TC-1.16-04 [Luồng chính 5] – Ảnh của câu hỏi bị xóa khỏi kho lưu trữ")
    void photosAreDeleted() throws Exception {
        String t = token();
        long asker = member();
        long id = body(multipartAs(asker, "POST", "/api/questions", "question",
                question("Có ảnh " + t, "", topics("Khác"), List.of()), photo(), photo())
                .andExpect(status().isOk())).at("/data/id").asLong();
        awaitStatus(id, "PUBLISHED");
        List<Long> media = jdbc.queryForList("SELECT media_id FROM blog_media WHERE blog_id = ?", Long.class, id);
        assertThat(media).hasSize(2);

        deleteAs(asker, "/api/questions/{id}", id).andExpect(status().isOk());

        for (long m : media) {
            assertThat(jdbc.queryForObject("SELECT deleted_at IS NOT NULL FROM media WHERE id = ?", Boolean.class, m))
                    .as("media %s", m).isTrue();
        }
    }

    @Test
    @DisplayName("TC-1.16-05 [Luồng thay thế 4b] – Người không phải người hỏi (kể cả người trả lời) nhận MSG18; không xóa gì")
    void onlyTheAskerMayDelete() throws Exception {
        String t = token();
        long asker = member();
        long helper = member();
        long id = ask(asker, "Không ai khác xóa " + t, "");
        answerQuestion(helper, id, "Mình là người trả lời.");

        deleteAs(helper, "/api/questions/{id}", id).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value(MSG18));
        deleteAs(member(), "/api/questions/{id}", id).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value(MSG18));

        assertThat(blogStatus(id)).isEqualTo("PUBLISHED");
        assertThat(commentCount(id)).isEqualTo(1);
        assertThat(points(asker)).isEqualTo(10);
    }

    @Test
    @DisplayName("TC-1.16-06 [Luồng thay thế 4a] – Câu hỏi đã bị xóa hoặc đã bị Moderator gỡ: MSG19, không thu điểm lần hai")
    void deletedOrRemovedQuestionAnswersNotFound() throws Exception {
        String t = token();
        long asker = member();
        long deleted = ask(asker, "Xóa hai lần " + t, "");
        long removed = ask(asker, "Đã bị gỡ " + t, "");
        jdbc.update("UPDATE blogs SET status = 'REMOVED', removed_at = NOW() WHERE id = ?", removed);

        deleteAs(asker, "/api/questions/{id}", deleted).andExpect(status().isOk());
        int pointsAfterFirst = points(asker);
        deleteAs(asker, "/api/questions/{id}", deleted).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value(MSG19));
        deleteAs(asker, "/api/questions/{id}", removed).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value(MSG19));
        deleteAs(asker, "/api/questions/{id}", 999_999_999L).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value(MSG19));

        assertThat(points(asker)).isEqualTo(pointsAfterFirst);
        assertThat(blogStatus(removed)).isEqualTo("REMOVED");
    }

    @Test
    @DisplayName("TC-1.16-07 [Luồng thay thế 4a] – Id của bài viết Blog không xóa được qua Hỏi-Đáp và ngược lại (MSG19)")
    void theOtherKindIsNotDeleted() throws Exception {
        String t = token();
        long author = member();
        long postId = publishedPost(author, "Bài Blog " + t, "Nội dung");
        long questionId = ask(author, "Câu hỏi " + t, "");

        deleteAs(author, "/api/questions/{id}", postId).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value(MSG19));
        deleteAs(author, "/api/blogs/{id}", questionId).andExpect(status().isNotFound());

        assertThat(blogExists(postId)).isTrue();
        assertThat(blogExists(questionId)).isTrue();
    }

    @Test
    @DisplayName("TC-1.16-08 [Tiền điều kiện] – Câu hỏi đang bị ẩn do báo cáo, bị từ chối hoặc đang quét (chưa bị Moderator gỡ) vẫn do người hỏi xóa được")
    void unpublishedQuestionsNotRemovedMayBeDeleted() throws Exception {
        String t = token();
        long asker = member();
        long hidden = insertBlog(asker, "QA", "Đang ẩn " + t, "", "HIDDEN", daysAgo(1), topic("Khác"));
        long rejected = insertBlog(asker, "QA", "Bị từ chối " + t, "", "REJECTED", daysAgo(1), topic("Khác"));
        long processing = insertBlog(asker, "QA", "Đang quét " + t, "", "PROCESSING", daysAgo(1), topic("Khác"));

        for (long id : new long[]{hidden, rejected, processing}) {
            deleteAs(asker, "/api/questions/{id}", id).andExpect(status().isOk())
                    .andExpect(jsonPath("$.message").value(DELETED));
            assertThat(blogExists(id)).isFalse();
        }
    }

    @Test
    @DisplayName("TC-1.16-09 [Phân quyền / Luồng thay thế 4c] – Khách (hết phiên) nhận 401, nhân viên nhận MSG18; không xóa gì")
    void guestsAndStaffCannotDelete() throws Exception {
        String t = token();
        long asker = member();
        long id = ask(asker, "Khách không xóa " + t, "");

        deleteAs(null, "/api/questions/{id}", id).andExpect(status().isUnauthorized());
        long moderator = account("MODERATOR", 30);
        deleteAs(moderator, "/api/questions/{id}", id).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value(MSG18));

        assertThat(blogStatus(id)).isEqualTo("PUBLISHED");
    }

    @Test
    @DisplayName("TC-1.16-10 [Hậu điều kiện] – Câu hỏi biến mất khỏi danh sách và bộ lọc \"Chưa ai trả lời\"; số bài trong ngày không đổi")
    void deletedQuestionLeavesTheListsButNotTheDailyCount() throws Exception {
        String t = token();
        long newbie = member(2);
        long id = ask(newbie, "Rời danh sách " + t, "");
        assertThat(listIds(null, t, "UNANSWERED", null)).containsExactly(id);
        assertThat(usagePosts(newbie)).isEqualTo(1);

        deleteAs(newbie, "/api/questions/{id}", id).andExpect(status().isOk());

        assertThat(listIds(null, t, "ALL", null)).isEmpty();
        assertThat(listIds(null, t, "UNANSWERED", null)).isEmpty();
        assertThat(usagePosts(newbie)).isEqualTo(1);
    }
}
