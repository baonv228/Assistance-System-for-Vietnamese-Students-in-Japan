package com.sep490.vsa.usecase.blog;

import com.fasterxml.jackson.databind.JsonNode;
import com.sep490.vsa.feature.moderation.ImageModerator.Verdict;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * UC 1.6 Update Post – SRS Report 3, "II. Use Case Specifications" 1.6 (normal flow 1–8, alternative flows 2a–2c,
 * 5a, 6a, 7a, 7b, postconditions) and CLAUDE.md "Write Post (UC-11) – Edit": the same ordered checks as a new post,
 * an edit never counts as a new post, only a video added to a post that had none counts toward the daily video limit,
 * new files send the post back to PROCESSING, and the +10 is paid the first time a post is published, never twice.
 * <p>
 * Not testable on the server (pages only): 3a leaving with MSG15, "everything typed is kept".
 */
@DisplayName("UC 1.6 – Update Post (Cập nhật bài viết)")
class UpdatePostTest extends PostWriteTestSupport {

    // ================================================================ normal flow

    @Test
    @DisplayName("TC-1.6-01 [Luồng chính – bước 1–2] Form \"Sửa bài\" điền sẵn tiêu đề, chủ đề, nội dung, địa điểm, ảnh và các quy tắc")
    void editFormIsFilledWithThePostAsItIsNow() throws Exception {
        String t = token();
        long author = member(30);
        long place = place("Ga " + t);
        long id = createdId(create(author, post("Bài gốc " + t, "Nội dung gốc", List.of(topic("Kinh nghiệm")),
                List.of(place)), photo()));
        awaitStatus(id, "PUBLISHED");

        JsonNode form = data(getAs(author, "/api/blogs/{id}/edit", id).andExpect(status().isOk()));
        assertThat(form.get("id").asLong()).isEqualTo(id);
        assertThat(form.get("type").asText()).isEqualTo("POST");
        assertThat(form.get("status").asText()).isEqualTo("PUBLISHED");
        assertThat(form.get("title").asText()).isEqualTo("Bài gốc " + t);
        assertThat(form.get("content").asText()).isEqualTo("Nội dung gốc");
        assertThat(form.get("topicIds").get(0).asLong()).isEqualTo(topic("Kinh nghiệm"));
        assertThat(form.get("places").get(0).get("id").asLong()).isEqualTo(place);
        assertThat(form.get("media")).hasSize(1);
        assertThat(form.get("media").get(0).get("type").asText()).isEqualTo("IMAGE");
        assertThat(form.get("media").get(0).get("scanStatus").asText()).isEqualTo("PASSED");
        assertThat(form.get("media").get(0).get("url").asText()).contains("/media/blog/");
        assertThat(form.get("options").get("limits").get("titleMax").asInt()).isEqualTo(150);
        assertThat(form.get("options").get("topics")).isNotEmpty();
    }

    @Test
    @DisplayName("TC-1.6-02 [Luồng chính – bước 3–8] Sửa tiêu đề, chủ đề, nội dung, địa điểm: \"Đã lưu thay đổi.\", bài vẫn đăng, \"· đã sửa\"; giữ lượt thích, bình luận, điểm")
    void authorEditsTextTopicsAndPlacesKeepingLikesCommentsAndPoints() throws Exception {
        String t = token();
        long author = member(30);
        long reader = member(30);
        long oldPlace = place("Cũ " + t);
        long newPlace = place("Mới " + t);
        long id = createdId(create(author, post("Bài gốc " + t, "Nội dung gốc", List.of(topic("Kinh nghiệm")),
                List.of(oldPlace))));
        postAs(reader, null, "/api/blogs/{id}/like", id).andExpect(status().isOk());
        postAs(reader, Map.of("content", "Cảm ơn bạn đã chia sẻ."), "/api/blogs/{id}/comments", id)
                .andExpect(status().isOk());
        assertThat(points(author)).isEqualTo(10);

        List<Long> newTopics = List.of(topic("Cảnh báo lừa đảo"), topic("Khác"));
        update(author, id, edit("Bài đã sửa " + t, "Nội dung mới", newTopics, List.of(newPlace), List.of()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value(SAVED))
                .andExpect(jsonPath("$.data.id").value((int) id))
                .andExpect(jsonPath("$.data.status").value("PUBLISHED"));

        Map<String, Object> row = jdbc.queryForMap(
                "SELECT title, content, status, is_edited, like_count, comment_count FROM blogs WHERE id = ?", id);
        assertThat(row).containsEntry("title", "Bài đã sửa " + t).containsEntry("content", "Nội dung mới")
                .containsEntry("status", "PUBLISHED").containsEntry("is_edited", true)
                .containsEntry("like_count", 1).containsEntry("comment_count", 1);
        assertThat(topicsOf(id)).containsExactlyInAnyOrderElementsOf(newTopics);
        assertThat(placesOf(id)).containsExactly(newPlace);
        assertThat(points(author)).isEqualTo(10);
        assertThat(awards(id)).isEqualTo(1);
        // Step 8: the post shows "· đã sửa"
        getAs(null, "/api/blogs/{id}", id).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.edited").value(true))
                .andExpect(jsonPath("$.data.title").value("Bài đã sửa " + t));
    }

    @Test
    @DisplayName("TC-1.6-03 [Luồng chính] Gửi dạng JSON thuần khi không thêm tệp; bỏ hết địa điểm thì bài không còn liên kết địa điểm")
    void plainJsonEditWithoutFilesReplacesThePlaces() throws Exception {
        String t = token();
        long author = member(30);
        long id = createdId(create(author, post("Bài " + t, "Nội dung", List.of(topic("Kinh nghiệm")),
                List.of(place("Nơi " + t)))));
        putAs(author, edit("Bài " + t, "Nội dung", List.of(topic("Kinh nghiệm")), List.of(), List.of()),
                "/api/blogs/{id}", id)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value(SAVED));
        assertThat(placesOf(id)).isEmpty();
    }

    @Test
    @DisplayName("TC-1.6-04 [Hậu điều kiện] Sửa bài không tính là bài mới: tài khoản mới đã đủ 3 bài hôm nay vẫn sửa được, số bài trong ngày giữ nguyên")
    void anEditIsNotANewPostForTheDailyLimit() throws Exception {
        long newbie = member(2);
        long id = writePost(newbie, "Bài 1 " + token());
        writePost(newbie, "Bài 2 " + token());
        writePost(newbie, "Bài 3 " + token());

        update(newbie, id, edit("Bài 1 đã sửa " + token(), "Nội dung", List.of(topic("Kinh nghiệm")), List.of(),
                List.of())).andExpect(status().isOk());
        assertThat(usage(newbie, "posts")).isEqualTo(3);
        create(newbie, post("Bài 4 " + token(), "Nội dung", List.of(topic("Kinh nghiệm")), List.of()))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.message").value(MSG44_POSTS));
    }

    // ================================================================ 2a – 2c: which post can be edited, by whom

    @Test
    @DisplayName("TC-1.6-05 [Luồng thay thế 2a] Bài bị ẩn do báo cáo, bị gỡ hoặc đã xóa: MSG19, không thay đổi gì")
    void hiddenRemovedOrDeletedPostsAreNotEditable() throws Exception {
        long author = member(30);
        String t = token();
        long id = writePost(author, "Bài " + t);
        Map<String, Object> change = edit("Đổi " + t, "Nội dung", List.of(topic("Kinh nghiệm")), List.of(), List.of());

        for (String hidden : List.of("HIDDEN", "REMOVED")) {
            jdbc.update("UPDATE blogs SET status = ? WHERE id = ?", hidden, id);
            getAs(author, "/api/blogs/{id}/edit", id).andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.message").value(MSG19));
            update(author, id, change).andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.message").value(MSG19));
        }
        assertThat(jdbc.queryForMap("SELECT title, is_edited FROM blogs WHERE id = ?", id))
                .containsEntry("title", "Bài " + t).containsEntry("is_edited", false);

        jdbc.update("UPDATE blogs SET status = 'PUBLISHED' WHERE id = ?", id);
        deleteAs(author, "/api/blogs/{id}", id).andExpect(status().isOk());
        update(author, id, change).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value(MSG19));
    }

    @Test
    @DisplayName("TC-1.6-06 [Luồng thay thế 2a] Mã của một câu hỏi không sửa được qua trang Blog (MSG19)")
    void aQuestionIsNotEditedThroughTheBlog() throws Exception {
        long author = member(30);
        long question = publishedQuestion(author, "Câu hỏi " + token(), "Mô tả");
        getAs(author, "/api/blogs/{id}/edit", question).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value(MSG19));
        update(author, question, edit("Đổi", "Nội dung", List.of(topic("Kinh nghiệm")), List.of(), List.of()))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("TC-1.6-07 [Luồng thay thế 2b – Phân quyền] Người khác nhận MSG18; nhân viên nhận MSG18; khách phải đăng nhập (401)")
    void onlyTheAuthorEditsThePost() throws Exception {
        long author = member(30);
        long other = member(30);
        long moderator = account("MODERATOR", 30);
        String t = token();
        long id = writePost(author, "Của tác giả " + t);
        Map<String, Object> change = edit("Đổi " + t, "Nội dung", List.of(topic("Kinh nghiệm")), List.of(), List.of());

        getAs(other, "/api/blogs/{id}/edit", id).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value(MSG18));
        update(other, id, change).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value(MSG18));
        update(moderator, id, change).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value(MSG18));
        update(null, id, change).andExpect(status().isUnauthorized());
        getAs(null, "/api/blogs/{id}/edit", id).andExpect(status().isUnauthorized());

        assertThat(jdbc.queryForMap("SELECT title, is_edited FROM blogs WHERE id = ?", id))
                .containsEntry("title", "Của tác giả " + t).containsEntry("is_edited", false);
    }

    @Test
    @DisplayName("TC-1.6-08 [Luồng thay thế 2c] Bài đang quét vẫn sửa được; sửa chữ mà không thêm tệp thì bài vẫn chờ quét")
    void aPostWaitingForItsScanCanBeEditedAndKeepsWaiting() throws Exception {
        when(imageModerator.check(any(byte[].class))).thenReturn(new Verdict("ERROR", "{\"error\":\"down\"}"));
        long author = member(30);
        String t = token();
        long id = createdId(create(author, post("Đang quét " + t, "Nội dung", List.of(topic("Kinh nghiệm")), List.of()),
                photo()));
        long media = mediaOf(id).get(0);

        JsonNode form = data(getAs(author, "/api/blogs/{id}/edit", id).andExpect(status().isOk()));
        assertThat(form.get("status").asText()).isEqualTo("PROCESSING");
        assertThat(form.get("media").get(0).get("scanStatus").asText()).isEqualTo("PROCESSING");

        update(author, id, edit("Đang quét (sửa) " + t, "Nội dung", List.of(topic("Kinh nghiệm")), List.of(),
                List.of(media)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("PROCESSING"))
                .andExpect(jsonPath("$.message").value(SAVED_FOR_SCAN));
        assertThat(mediaOf(id)).containsExactly(media);
        assertThat(points(author)).isZero();
    }

    // ================================================================ 5a, 6a: the checks of Create Post

    @Test
    @DisplayName("TC-1.6-09 [Luồng thay thế 5a] Thông tin không hợp lệ (thiếu chữ, quá dài, quá 3 chủ đề): báo lỗi như Tạo bài, bài giữ nguyên")
    void invalidInformationIsRefusedAndThePostStaysAsItWas() throws Exception {
        long author = member(30);
        String t = token();
        long id = writePost(author, "Bài sạch " + t);
        update(author, id, edit(" ", "", List.of(), List.of(), List.of()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(MSG37))
                .andExpect(jsonPath("$.data.title").value("Hãy nhập tiêu đề."))
                .andExpect(jsonPath("$.data.content").value("Hãy nhập nội dung bài viết."))
                .andExpect(jsonPath("$.data.topicIds").value(TOPIC_REQUIRED));
        update(author, id, edit("x".repeat(151), "Nội dung", List.of(topic("Kinh nghiệm")), List.of(), List.of()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.data.title").value("Tiêu đề tối đa 150 ký tự."));
        update(author, id, edit("Bài " + t, chars(5001), List.of(topic("Kinh nghiệm")), List.of(), List.of()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.data.content").value("Nội dung tối đa 5.000 ký tự."));
        update(author, id, edit("Bài " + t, "Nội dung", activeTopics(4), List.of(), List.of()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.data.topicIds").value("Chọn tối đa 3 chủ đề."));

        assertThat(jdbc.queryForMap("SELECT title, is_edited FROM blogs WHERE id = ?", id))
                .containsEntry("title", "Bài sạch " + t).containsEntry("is_edited", false);
        assertThat(topicsOf(id)).containsExactly(topic("Kinh nghiệm"));
    }

    @Test
    @DisplayName("TC-1.6-10 [Biên] Sửa với tiêu đề đúng 150 ký tự và nội dung đúng 5.000 ký tự được lưu")
    void editAtTheExactLimitsIsSaved() throws Exception {
        long author = member(30);
        String t = token();
        long id = writePost(author, "Bài " + t);
        String title150 = t + "x".repeat(150 - t.length());
        update(author, id, edit(title150, chars(5000), List.of(topic("Kinh nghiệm")), List.of(), List.of()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value(SAVED));
        assertThat(jdbc.queryForMap("SELECT CHAR_LENGTH(title) AS t, CHAR_LENGTH(content) AS c FROM blogs WHERE id = ?",
                id)).containsEntry("t", 150L).containsEntry("c", 5000L);
    }

    @Test
    @DisplayName("TC-1.6-11 [Luồng thay thế 6a] Thêm link khi chưa có tag, hoặc từ khóa cấm (MSG36): không lưu gì")
    void linksAndBannedKeywordsAddedByAnEditAreRefused() throws Exception {
        long author = member(30);
        String t = token();
        long id = writePost(author, "Bài sạch " + t);
        update(author, id, edit("Bài sạch " + t, "Xem https://www.city.arakawa.tokyo.jp nhé",
                List.of(topic("Kinh nghiệm")), List.of(), List.of()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(LINK_NEEDS_TAG));
        update(author, id, edit("Bài sạch " + t, "Liên hệ zalo 0901 234 567", List.of(topic("Kinh nghiệm")),
                List.of(), List.of()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(CONTACT_NEEDS_TAG));
        update(author, id, edit("Tuyển gấp " + t, "Việc nhẹ, lương-cao cho du học sinh", List.of(topic("Kinh nghiệm")),
                List.of(), List.of()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(msg36("việc nhẹ lương cao")));
        assertThat(jdbc.queryForMap("SELECT title, content, is_edited FROM blogs WHERE id = ?", id))
                .containsEntry("title", "Bài sạch " + t).containsEntry("is_edited", false);
    }

    @Test
    @DisplayName("TC-1.6-12 [Luồng thay thế 6a] Tài khoản mới: thêm video vào bài chưa có video bị tính hạn mức (đã đủ 1 video thì bị từ chối); thay video của bài đã có video thì không tính")
    void onlyAVideoAddedToAPostWithoutOneCountsTowardTheDailyVideoLimit() throws Exception {
        long newbie = member(2);
        String t = token();
        long withVideo = createdId(create(newbie, post("Có video " + t, "Nội dung", List.of(topic("Kinh nghiệm")),
                List.of()), video(10)));
        awaitStatus(withVideo, "PUBLISHED");
        long textOnly = writePost(newbie, "Chỉ chữ " + t);
        assertThat(usage(newbie, "videos")).isEqualTo(1);

        update(newbie, textOnly, edit("Chỉ chữ + video " + t, "Nội dung", List.of(topic("Kinh nghiệm")), List.of(),
                List.of()), video(10))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.message").value(VIDEO_LIMIT));
        assertThat(mediaOf(textOnly)).isEmpty();

        // Replacing the video of a post that had one is not a new video
        update(newbie, withVideo, edit("Đổi video " + t, "Nội dung", List.of(topic("Kinh nghiệm")), List.of(),
                List.of()), video(12))
                .andExpect(status().isOk());
        assertThat(usage(newbie, "videos")).isEqualTo(1);
        assertThat(usage(newbie, "posts")).isEqualTo(2);
        assertThat(mediaOf(withVideo)).hasSize(1);
        awaitStatus(withVideo, "PUBLISHED");
    }

    @Test
    @DisplayName("TC-1.6-13 [Biên] Ảnh giữ lại + ảnh mới tối đa 5: 1 + 5 bị từ chối, 1 + 4 được nhận")
    void keptAndNewPhotosTogetherAreAtMostFive() throws Exception {
        long author = member(30);
        String t = token();
        long id = createdId(create(author, post("Một ảnh " + t, "Nội dung", List.of(topic("Kinh nghiệm")), List.of()),
                photo()));
        awaitStatus(id, "PUBLISHED");
        long kept = mediaOf(id).get(0);

        update(author, id, edit("Sáu ảnh " + t, "Nội dung", List.of(topic("Kinh nghiệm")), List.of(), List.of(kept)),
                photo(), photo(), photo(), photo(), photo())
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.data.images").value(IMAGE_RULE));
        assertThat(mediaOf(id)).containsExactly(kept);

        update(author, id, edit("Năm ảnh " + t, "Nội dung", List.of(topic("Kinh nghiệm")), List.of(), List.of(kept)),
                photo(), photo(), photo(), photo())
                .andExpect(status().isOk());
        awaitStatus(id, "PUBLISHED");
        assertThat(mediaOf(id)).hasSize(5).startsWith(kept);
    }

    // ================================================================ 7: the files

    @Test
    @DisplayName("TC-1.6-14 [Luồng chính – bước 7] Ảnh tác giả bỏ ra bị xóa (cả tệp), ảnh giữ lại đứng đầu, bài vẫn đăng")
    void removedFilesAreDeletedAndKeptOnesStay() throws Exception {
        long author = member(30);
        String t = token();
        long id = createdId(create(author, post("Hai ảnh " + t, "Nội dung", List.of(topic("Kinh nghiệm")), List.of()),
                photo(), photo()));
        awaitStatus(id, "PUBLISHED");
        List<Long> before = mediaOf(id);
        String removedKey = storageKey(before.get(0));

        update(author, id, edit("Còn một ảnh " + t, "Nội dung", List.of(topic("Kinh nghiệm")), List.of(),
                List.of(before.get(1))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value(SAVED))
                .andExpect(jsonPath("$.data.status").value("PUBLISHED"));
        assertThat(mediaOf(id)).containsExactly(before.get(1));
        assertThat(mediaDeleted(before.get(0))).isTrue();
        assertThat(mediaDeleted(before.get(1))).isFalse();
        await().atMost(Duration.ofSeconds(5)).until(() -> !fileExists(removedKey));
        assertThat(blogStatus(id)).isEqualTo("PUBLISHED");
    }

    @Test
    @DisplayName("TC-1.6-15 [Luồng thay thế 7a] Thêm ảnh mới: bài về trạng thái chờ quét và ẩn với người khác cho đến khi quét xong")
    void aNewPhotoHidesThePostFromOthersUntilItIsScanned() throws Exception {
        long author = member(30);
        long other = member(30);
        String t = token();
        long id = writePost(author, "Bài chữ " + t);
        when(imageModerator.check(any(byte[].class))).thenReturn(new Verdict("ERROR", "{\"error\":\"down\"}"));

        update(author, id, edit("Bài thêm ảnh " + t, "Nội dung", List.of(topic("Kinh nghiệm")), List.of(), List.of()),
                photo())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value(SAVED_FOR_SCAN))
                .andExpect(jsonPath("$.data.status").value("PROCESSING"));
        assertThat(blogStatus(id)).isEqualTo("PROCESSING");
        getAs(other, "/api/blogs/{id}", id).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value(MSG19));
        getAs(null, "/api/blogs/{id}", id).andExpect(status().isNotFound());
        getAs(author, "/api/blogs/{id}", id).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("PROCESSING"));
    }

    @Test
    @DisplayName("TC-1.6-16 [Luồng thay thế 7a] Ảnh mới qua quét: bài được đăng lại, thông báo \"Bản sửa bài viết…\", không cộng thêm điểm")
    void aNewPhotoThatPassesRepublishesThePostWithoutNewPoints() throws Exception {
        long author = member(30);
        String t = token();
        long id = writePost(author, "Bài chữ " + t);
        assertThat(points(author)).isEqualTo(10);

        String title = "Bài thêm ảnh " + t;
        update(author, id, edit(title, "Nội dung", List.of(topic("Kinh nghiệm")), List.of(), List.of()), photo())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("PROCESSING"));
        awaitStatus(id, "PUBLISHED");
        await().atMost(Duration.ofSeconds(5)).until(() -> !notifications(author, "CONTENT_PUBLISHED", id).isEmpty());
        assertThat(notifications(author, "CONTENT_PUBLISHED", id))
                .containsExactly("Bản sửa bài viết “" + title + "” đã qua kiểm duyệt ảnh/video và đã được đăng.");
        assertThat(points(author)).isEqualTo(10);
        assertThat(awards(id)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT is_edited FROM blogs WHERE id = ?", Boolean.class, id)).isTrue();
    }

    @Test
    @DisplayName("TC-1.6-17 [Luồng thay thế 2c + 7b] Bài bị từ chối được sửa (bỏ ảnh vi phạm) thì được đăng và nhận +10 đúng một lần")
    void aRejectedPostFixedByItsAuthorIsPublishedAndPaidOnce() throws Exception {
        when(imageModerator.check(any(byte[].class))).thenReturn(new Verdict("FLAG", "{\"flagged\":true}"));
        long author = member(30);
        String t = token();
        long id = createdId(create(author, post("Ảnh vi phạm " + t, "Nội dung", List.of(topic("Kinh nghiệm")),
                List.of()), photo()));
        awaitStatus(id, "REJECTED");
        assertThat(points(author)).isZero();

        // 2c: the form opens for the rejected post; the rejected photo is gone already
        JsonNode form = data(getAs(author, "/api/blogs/{id}/edit", id).andExpect(status().isOk()));
        assertThat(form.get("status").asText()).isEqualTo("REJECTED");
        assertThat(form.get("media")).isEmpty();

        update(author, id, edit("Đã bỏ ảnh " + t, "Nội dung", List.of(topic("Kinh nghiệm")), List.of(), List.of()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value(SAVED))
                .andExpect(jsonPath("$.data.status").value("PUBLISHED"));
        assertThat(points(author)).isEqualTo(10);

        // Editing again never pays a second time
        update(author, id, edit("Sửa lần nữa " + t, "Nội dung", List.of(topic("Kinh nghiệm")), List.of(), List.of()))
                .andExpect(status().isOk());
        assertThat(points(author)).isEqualTo(10);
        assertThat(awards(id)).isEqualTo(1);
    }
}
