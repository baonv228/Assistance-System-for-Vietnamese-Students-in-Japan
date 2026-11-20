package com.sep490.vsa.usecase.qa;

import com.fasterxml.jackson.databind.JsonNode;
import com.sep490.vsa.feature.moderation.ImageModerator.Verdict;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.sql.Date;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * UC 1.15 Update Question ("Cập nhật câu hỏi") – SRS Report 3, II.1.12, with the field rules of II.1.11 Submit
 * Question and the community rules of II.1.5 / 1.6. CLAUDE.md ("Write Post – Edit"): GET/PUT
 * {@code /api/questions/{id}/edit|{id}}, {@code keepMediaIds}, an edit never counts as a new post, only a video added
 * to a question that had none counts toward the daily video limit, the points are paid once ({@code awardOnce}).
 * <p>
 * Not testable on the server (UI only): 3a Cancel with MSG15, "everything typed is kept", the wording of the form
 * notes of 2c (the server only gives the status they are chosen from, checked in TC-1.15-12).
 */
@DisplayName("UC 1.15 – Update Question (Cập nhật câu hỏi)")
class UpdateQuestionTest extends QuestionTestSupport {

    @Test
    @DisplayName("TC-1.15-01 [Luồng chính 1-2] – Form \"Sửa câu hỏi\" có sẵn câu hỏi, chủ đề, mô tả, địa điểm và ảnh hiện tại")
    void editFormIsFilledWithTheCurrentQuestion() throws Exception {
        String t = token();
        long asker = member();
        long place = place("Ga Nippori " + t);
        List<Long> topicIds = topics("Visa và cư trú", "Nhà ở");
        long id = body(multipartAs(asker, "POST", "/api/questions", "question",
                question("Hỏi về nhà " + t, "Mô tả ban đầu", topicIds, List.of(place)), photo())
                .andExpect(status().isOk())).at("/data/id").asLong();
        awaitStatus(id, "PUBLISHED");

        JsonNode data = data(getAs(asker, "/api/questions/{id}/edit", id).andExpect(status().isOk()));

        assertThat(data.get("id").asLong()).isEqualTo(id);
        assertThat(data.get("type").asText()).isEqualTo("QA");
        assertThat(data.get("status").asText()).isEqualTo("PUBLISHED");
        assertThat(data.get("title").asText()).isEqualTo("Hỏi về nhà " + t);
        assertThat(data.get("content").asText()).isEqualTo("Mô tả ban đầu");
        assertThat(data.get("topicIds")).extracting(JsonNode::asLong).containsExactlyInAnyOrderElementsOf(topicIds);
        assertThat(data.get("places").findValues("id")).extracting(JsonNode::asLong).containsExactly(place);
        assertThat(data.get("media")).hasSize(1);
        assertThat(data.at("/media/0/type").asText()).isEqualTo("IMAGE");
        assertThat(data.at("/media/0/scanStatus").asText()).isEqualTo("PASSED");
        assertThat(data.at("/options/limits/titleMax").asInt()).isEqualTo(150);
    }

    @Test
    @DisplayName("TC-1.15-02 [Luồng chính 3-8] – Lưu thay đổi: \"Đã lưu thay đổi.\", đánh dấu đã sửa, thay chủ đề và địa điểm; câu trả lời, lượt thích, câu trả lời hay nhất và điểm được giữ")
    void savingKeepsAnswersLikesBestAnswerAndPoints() throws Exception {
        String t = token();
        long asker = member();
        long helper = member();
        long fan = member();
        long oldPlace = place("Cũ " + t);
        long newPlace = place("Mới " + t);
        long id = ask(asker, "Hỏi cũ " + t, "Mô tả cũ", topics("Nhà ở"), List.of(oldPlace));
        long answer = answerQuestion(helper, id, "Bạn hỏi văn phòng quận nhé.");
        postAs(fan, null, "/api/questions/{id}/like", id).andExpect(status().isOk());
        chooseBest(asker, id, answer).andExpect(status().isOk());
        int usageBefore = usagePosts(asker);

        putAs(asker, edit("Hỏi mới " + t, "Mô tả mới", topics("Visa và cư trú", "Y tế"), List.of(newPlace), List.of()),
                "/api/questions/{id}", id)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value(SAVED_CHANGES))
                .andExpect(jsonPath("$.data.status").value("PUBLISHED"));

        Map<String, Object> row = jdbc.queryForMap(
                "SELECT title, content, status, is_edited, like_count, best_comment_id FROM blogs WHERE id = ?", id);
        assertThat(row.get("title")).isEqualTo("Hỏi mới " + t);
        assertThat(row.get("content")).isEqualTo("Mô tả mới");
        assertThat(row.get("is_edited")).isEqualTo(true);
        assertThat(((Number) row.get("like_count")).intValue()).isEqualTo(1);
        assertThat(((Number) row.get("best_comment_id")).longValue()).isEqualTo(answer);
        assertThat(topicLinks(id)).containsExactlyElementsOf(topics("Visa và cư trú", "Y tế"));
        assertThat(placeLinks(id)).containsExactly(newPlace);
        assertThat(commentExists(answer)).isTrue();
        assertThat(points(asker)).isEqualTo(10);
        assertThat(points(helper)).isEqualTo(5);
        assertThat(usagePosts(asker)).isEqualTo(usageBefore);

        // The page shows "· đã sửa" and the question is still solved
        getAs(null, "/api/questions/{id}", id).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.edited").value(true))
                .andExpect(jsonPath("$.data.bestCommentId").value(answer));
        assertThat(card(null, t, id).get("solved").asBoolean()).isTrue();
    }

    @Test
    @DisplayName("TC-1.15-03 [Luồng thay thế 2b] – Người không phải người hỏi nhận MSG18 khi mở form hoặc lưu; câu hỏi không đổi")
    void onlyTheAskerMayEdit() throws Exception {
        String t = token();
        long asker = member();
        long other = member();
        long id = ask(asker, "Của người hỏi " + t, "");

        getAs(other, "/api/questions/{id}/edit", id).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value(MSG18));
        putAs(other, edit("Bị sửa " + t, "", topics("Khác"), List.of(), List.of()), "/api/questions/{id}", id)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value(MSG18));

        assertThat(jdbc.queryForObject("SELECT title FROM blogs WHERE id = ?", String.class, id)).isEqualTo("Của người hỏi " + t);
    }

    @Test
    @DisplayName("TC-1.15-04 [Luồng thay thế 2a] – Câu hỏi bị ẩn do báo cáo, bị gỡ hoặc đã xóa: MSG19 khi mở form và khi lưu")
    void hiddenRemovedOrDeletedQuestionCannotBeEdited() throws Exception {
        String t = token();
        long asker = member();
        long hidden = ask(asker, "Bị ẩn " + t, "");
        long removed = ask(asker, "Bị gỡ " + t, "");
        long deleted = ask(asker, "Đã xóa " + t, "");
        jdbc.update("UPDATE blogs SET status = 'HIDDEN' WHERE id = ?", hidden);
        jdbc.update("UPDATE blogs SET status = 'REMOVED' WHERE id = ?", removed);
        deleteAs(asker, "/api/questions/{id}", deleted).andExpect(status().isOk());

        for (long id : new long[]{hidden, removed, deleted}) {
            getAs(asker, "/api/questions/{id}/edit", id).andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.message").value(MSG19));
            putAs(asker, edit("Sửa " + t, "", topics("Khác"), List.of(), List.of()), "/api/questions/{id}", id)
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.message").value(MSG19));
        }
        assertThat(blogStatus(hidden)).isEqualTo("HIDDEN");
        assertThat(blogStatus(removed)).isEqualTo("REMOVED");
    }

    @Test
    @DisplayName("TC-1.15-05 [Luồng thay thế 2a] – Id của một bài viết Blog không mở được bằng \"Sửa câu hỏi\" (MSG19), và ngược lại")
    void aPostIsNotEditedAsAQuestion() throws Exception {
        String t = token();
        long author = member();
        long postId = publishedPost(author, "Bài viết " + t, "Nội dung");
        long questionId = ask(author, "Câu hỏi " + t, "");

        getAs(author, "/api/questions/{id}/edit", postId).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value(MSG19));
        putAs(author, edit("Đổi " + t, "", topics("Khác"), List.of(), List.of()), "/api/questions/{id}", postId)
                .andExpect(status().isNotFound());
        getAs(author, "/api/blogs/{id}/edit", questionId).andExpect(status().isNotFound());
        assertThat(jdbc.queryForObject("SELECT blog_type FROM blogs WHERE id = ?", String.class, postId)).isEqualTo("POST");
    }

    @Test
    @DisplayName("TC-1.15-06 [Luồng thay thế 5a] – Thông tin không hợp lệ (câu hỏi rỗng/151 ký tự, 4 chủ đề, mô tả 5.001 ký tự): báo lỗi từng trường, không lưu")
    void invalidInformationIsRefusedWithFieldErrors() throws Exception {
        String t = token();
        long asker = member();
        long id = ask(asker, "Giữ nguyên " + t, "Mô tả giữ nguyên");

        putAs(asker, edit("", chars(5001), topics("Nhà ở", "Đi lại", "Y tế", "Ăn uống"), List.of(), List.of()),
                "/api/questions/{id}", id)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(TITLE_REQUIRED))
                .andExpect(jsonPath("$.data.title").value(TITLE_REQUIRED))
                .andExpect(jsonPath("$.data.topicIds").value(TOPICS_TOO_MANY))
                .andExpect(jsonPath("$.data.content").value(DESCRIPTION_TOO_LONG));
        putAs(asker, edit(t + " " + chars(150 - t.length()), "", topics("Khác"), List.of(), List.of()),
                "/api/questions/{id}", id)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.data.title").value(TITLE_TOO_LONG));
        putAs(asker, edit("Không chủ đề " + t, "", List.of(), List.of(), List.of()), "/api/questions/{id}", id)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.data.topicIds").value(TOPIC_REQUIRED));

        Map<String, Object> row = jdbc.queryForMap("SELECT title, content, is_edited FROM blogs WHERE id = ?", id);
        assertThat(row.get("title")).isEqualTo("Giữ nguyên " + t);
        assertThat(row.get("content")).isEqualTo("Mô tả giữ nguyên");
        assertThat(row.get("is_edited")).isEqualTo(false);

        // Boundary: exactly 150 characters is accepted on an edit too
        String title150 = t + " " + chars(149 - t.length());
        assertThat(title150).hasSize(150);
        putAs(asker, edit(title150, "", topics("Khác"), List.of(), List.of()), "/api/questions/{id}", id)
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("TC-1.15-07 [Luồng thay thế 6a] – Vi phạm quy tắc cộng đồng khi sửa (link khi chưa có tag, từ khóa cấm): báo lỗi, không lưu gì")
    void ruleViolationSavesNothing() throws Exception {
        String t = token();
        long asker = member();
        long id = ask(asker, "Trước khi sửa " + t, "Mô tả sạch");

        putAs(asker, edit("Sửa có link " + t, "Xem https://example.com/a", topics("Khác"), List.of(), List.of()),
                "/api/questions/{id}", id)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(LINK_NEEDS_TAG));
        putAs(asker, edit("Sửa có " + BANNED + " " + t, "", topics("Khác"), List.of(), List.of()),
                "/api/questions/{id}", id)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(MSG36));

        Map<String, Object> row = jdbc.queryForMap("SELECT title, content, is_edited FROM blogs WHERE id = ?", id);
        assertThat(row.get("title")).isEqualTo("Trước khi sửa " + t);
        assertThat(row.get("is_edited")).isEqualTo(false);
        assertThat(topicLinks(id)).containsExactly(topic("Kinh nghiệm"));
    }

    @Test
    @DisplayName("TC-1.15-08 [Luồng thay thế 6a – video] – Tài khoản mới đã dùng 1 video hôm nay không thêm được video vào câu hỏi chưa có video")
    void newAccountCannotAddASecondVideoToday() throws Exception {
        String t = token();
        long newbie = member(2);
        long id = ask(newbie, "Chưa có video " + t, "");
        jdbc.update("UPDATE user_daily_usage SET videos = 1 WHERE user_id = ? AND usage_date = ?",
                newbie, Date.valueOf(japanToday()));

        multipartAs(newbie, "PUT", "/api/questions/" + id, "question",
                edit("Thêm video " + t, "", topics("Khác"), List.of(), List.of()), video(10))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.message").value(VIDEO_LIMIT));

        assertThat(blogStatus(id)).isEqualTo("PUBLISHED");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM blog_media WHERE blog_id = ?", Integer.class, id)).isZero();
    }

    @Test
    @DisplayName("TC-1.15-09 [Hậu điều kiện] – Sửa câu hỏi không tính là bài mới: tài khoản mới đã đủ 3 bài hôm nay vẫn sửa được, hạn mức không tăng")
    void anEditDoesNotCountTowardTheDailyLimit() throws Exception {
        String t = token();
        long newbie = member(2);
        long id = ask(newbie, "Câu hỏi 1 " + t, "");
        ask(newbie, "Câu hỏi 2 " + t, "");
        ask(newbie, "Câu hỏi 3 " + t, "");
        assertThat(usagePosts(newbie)).isEqualTo(3);

        putAs(newbie, edit("Câu hỏi 1 đã sửa " + t, "", topics("Khác"), List.of(), List.of()), "/api/questions/{id}", id)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value(SAVED_CHANGES));

        assertThat(usagePosts(newbie)).isEqualTo(3);
        assertThat(points(newbie)).isEqualTo(30);
    }

    @Test
    @DisplayName("TC-1.15-10 [Luồng thay thế 7a] – Thêm ảnh mới: câu hỏi chờ quét, ẩn với người khác; quét xong được đăng lại, không cộng điểm lần hai")
    void newPhotoSendsTheQuestionBackToTheScan() throws Exception {
        String t = token();
        long asker = member();
        long other = member();
        long id = ask(asker, "Hỏi trước " + t, "");
        CountDownLatch release = new CountDownLatch(1);
        when(imageModerator.check(any(byte[].class))).thenAnswer(inv -> {
            release.await(15, TimeUnit.SECONDS);
            return new Verdict("PASS", "{\"flagged\":false}");
        });
        String newTitle = "Hỏi có ảnh " + t;

        multipartAs(asker, "PUT", "/api/questions/" + id, "question",
                edit(newTitle, "", topics("Khác"), List.of(), List.of()), photo())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value(SAVED_CHANGES_SCANNING))
                .andExpect(jsonPath("$.data.status").value("PROCESSING"));

        getAs(other, "/api/questions/{id}", id).andExpect(status().isNotFound());
        assertThat(listIds(other, t, null, null)).isEmpty();
        getAs(asker, "/api/questions/{id}", id).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("PROCESSING"));

        release.countDown();
        awaitStatus(id, "PUBLISHED");
        assertThat(points(asker)).isEqualTo(10);
        assertThat(notificationsOf(asker))
                .contains("Bản sửa câu hỏi “" + newTitle + "” đã qua kiểm duyệt ảnh/video và đã được đăng.");
        getAs(other, "/api/questions/{id}", id).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.media.length()").value(1));
    }

    @Test
    @DisplayName("TC-1.15-11 [Luồng thay thế 7b] – Câu hỏi bị từ chối được sửa lại thì được đăng và nhận +10 điểm một lần duy nhất")
    void rejectedQuestionFixedIsPublishedAndPaidOnce() throws Exception {
        String t = token();
        long asker = member();
        photosFailTheScan();
        long id = body(multipartAs(asker, "POST", "/api/questions", "question",
                question("Bị từ chối " + t, "", topics("Khác"), List.of()), photo())
                .andExpect(status().isOk())).at("/data/id").asLong();
        awaitStatus(id, "REJECTED");
        assertThat(points(asker)).isZero();

        // 2c: the form opens on the rejected question (its status chooses the note); the rejected photo is gone
        JsonNode form = data(getAs(asker, "/api/questions/{id}/edit", id).andExpect(status().isOk()));
        assertThat(form.get("status").asText()).isEqualTo("REJECTED");

        putAs(asker, edit("Đã sửa lại " + t, "", topics("Khác"), List.of(), List.of()), "/api/questions/{id}", id)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value(SAVED_CHANGES))
                .andExpect(jsonPath("$.data.status").value("PUBLISHED"));
        assertThat(points(asker)).isEqualTo(10);

        putAs(asker, edit("Sửa thêm lần nữa " + t, "", topics("Khác"), List.of(), List.of()), "/api/questions/{id}", id)
                .andExpect(status().isOk());
        assertThat(points(asker)).isEqualTo(10);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM point_transactions WHERE ref_type = 'BLOG' AND ref_id = ? "
                + "AND reason = 'BLOG_CREATED'", Integer.class, id)).isEqualTo(1);
    }

    @Test
    @DisplayName("TC-1.15-12 [Luồng chính 3, 7] – Bỏ ảnh đã đính kèm (không có trong keepMediaIds): ảnh bị xóa, câu hỏi vẫn được đăng")
    void removedAttachmentsAreDeleted() throws Exception {
        String t = token();
        long asker = member();
        long id = body(multipartAs(asker, "POST", "/api/questions", "question",
                question("Hai ảnh " + t, "", topics("Khác"), List.of()), photo(), photo())
                .andExpect(status().isOk())).at("/data/id").asLong();
        awaitStatus(id, "PUBLISHED");
        JsonNode media = data(getAs(asker, "/api/questions/{id}/edit", id)).get("media");
        long keep = media.get(1).get("id").asLong();
        long drop = media.get(0).get("id").asLong();

        putAs(asker, edit("Còn một ảnh " + t, "", topics("Khác"), List.of(), List.of(keep)), "/api/questions/{id}", id)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("PUBLISHED"));

        assertThat(jdbc.queryForList("SELECT media_id FROM blog_media WHERE blog_id = ?", Long.class, id)).containsExactly(keep);
        assertThat(jdbc.queryForObject("SELECT deleted_at IS NOT NULL FROM media WHERE id = ?", Boolean.class, drop)).isTrue();
        assertThat(jdbc.queryForObject("SELECT deleted_at IS NULL FROM media WHERE id = ?", Boolean.class, keep)).isTrue();
    }

    @Test
    @DisplayName("TC-1.15-13 [Phân quyền] – Khách bị yêu cầu đăng nhập (401); tài khoản nhân viên nhận MSG18")
    void guestsAndStaffCannotEdit() throws Exception {
        String t = token();
        long asker = member();
        long id = ask(asker, "Không ai khác sửa " + t, "");
        Map<String, Object> body = edit("Sửa " + t, "", topics("Khác"), List.of(), List.of());

        getAs(null, "/api/questions/{id}/edit", id).andExpect(status().isUnauthorized());
        putAs(null, body, "/api/questions/{id}", id).andExpect(status().isUnauthorized());
        long moderator = account("MODERATOR", 30);
        getAs(moderator, "/api/questions/{id}/edit", id).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value(MSG18));
        putAs(moderator, body, "/api/questions/{id}", id).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value(MSG18));
        assertThat(jdbc.queryForObject("SELECT is_edited FROM blogs WHERE id = ?", Boolean.class, id)).isFalse();
    }
}
