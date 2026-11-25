package com.sep490.vsa.usecase.blog;

import com.fasterxml.jackson.databind.JsonNode;
import com.sep490.vsa.feature.moderation.ImageModerator.Verdict;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * UC 1.5 Create Post – SRS Report 3, "II. Use Case Specifications" 1.5 (normal flow 1–9, alternative flows 1a, 6a,
 * 6b, 7a–7c, 8a) and CLAUDE.md "Write Post (UC-11)": the checks run in the order fields/files → links → QR codes of
 * the photos → new-account daily limit → banned keywords, a post earns +10 the first time it is published, and the
 * day's count lives in {@code user_daily_usage} (Japan dates).
 * <p>
 * Not testable on the server (pages only): 3a "Hủy" with MSG15, "Everything typed is kept", 8b session message.
 */
@DisplayName("UC 1.5 – Create Post (Tạo bài viết)")
class CreatePostTest extends PostWriteTestSupport {

    // ================================================================ normal flow

    @Test
    @DisplayName("TC-1.5-01 [Luồng chính – bước 2] Form \"Viết bài\" có chủ đề đang hoạt động, quy tắc tệp và hạn mức hôm nay của tài khoản mới")
    void composeShowsTopicsRulesAndTodaysUsageOfANewAccount() throws Exception {
        long newbie = member(2);
        long veteran = member(30);
        grantMentorTag(veteran);
        writePost(newbie, "Bài đầu " + token());

        JsonNode form = data(getAs(newbie, "/api/blogs/compose").andExpect(status().isOk()));
        assertThat(form.get("topics").findValuesAsText("name")).contains("Kinh nghiệm", "Cảnh báo lừa đảo", "Khác");
        for (JsonNode t : form.get("topics")) {
            assertThat(t.get("other").asBoolean()).isEqualTo("Khác".equals(t.get("name").asText()));
            assertThat(t.get("scamWarning").asBoolean()).isEqualTo("Cảnh báo lừa đảo".equals(t.get("name").asText()));
        }
        JsonNode limits = form.get("limits");
        assertThat(limits.get("titleMax").asInt()).isEqualTo(150);
        assertThat(limits.get("contentMax").asInt()).isEqualTo(5000);
        assertThat(limits.get("topicsMax").asInt()).isEqualTo(3);
        assertThat(limits.get("placesMax").asInt()).isEqualTo(5);
        assertThat(limits.get("imagesMax").asInt()).isEqualTo(5);
        assertThat(limits.get("imageMaxMb").asInt()).isEqualTo(20);
        assertThat(limits.get("videosMax").asInt()).isEqualTo(1);
        assertThat(limits.get("videoMaxSeconds").asInt()).isEqualTo(30);
        assertThat(limits.get("videoMaxMb").asInt()).isEqualTo(50);
        // "Tài khoản mới, hôm nay: X/3 bài viết và review · Y/2 ghim · Z/1 video."
        JsonNode quota = form.get("quota");
        assertThat(quota.get("newAccount").asBoolean()).isTrue();
        assertThat(quota.get("postsUsed").asInt()).isEqualTo(1);
        assertThat(quota.get("postsLimit").asInt()).isEqualTo(3);
        assertThat(quota.get("pinsLimit").asInt()).isEqualTo(2);
        assertThat(quota.get("videosUsed").asInt()).isZero();
        assertThat(quota.get("videosLimit").asInt()).isEqualTo(1);
        assertThat(form.get("canPostLinks").asBoolean()).isFalse();

        JsonNode old = data(getAs(veteran, "/api/blogs/compose").andExpect(status().isOk()));
        assertThat(old.get("quota").get("newAccount").asBoolean()).isFalse();
        assertThat(old.get("canPostLinks").asBoolean()).isTrue();
    }

    @Test
    @DisplayName("TC-1.5-02 [Luồng chính – bước 5–9] Bài chỉ có chữ được đăng ngay: MSG34, lưu chủ đề + địa điểm, +10 điểm, tăng số bài trong ngày")
    void textPostIsPublishedAtOnceWithTopicsPlacesPointsAndDailyCount() throws Exception {
        String t = token();
        long author = member(30);
        long place = place("Ga " + t);
        List<Long> topics = List.of(topic("Kinh nghiệm"), topic("Khác"));

        var result = create(author, post("Năm điều mình ước biết trước " + t, "Mua thẻ IC ngay ở ga đầu tiên.",
                topics, List.of(place)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.message").value(MSG34))
                .andExpect(jsonPath("$.data.type").value("POST"))
                .andExpect(jsonPath("$.data.status").value("PUBLISHED"));
        long id = createdId(result);

        Map<String, Object> row = jdbc.queryForMap(
                "SELECT author_id, blog_type, title, content, status, is_edited FROM blogs WHERE id = ?", id);
        assertThat(row).containsEntry("author_id", author).containsEntry("blog_type", "POST")
                .containsEntry("title", "Năm điều mình ước biết trước " + t)
                .containsEntry("content", "Mua thẻ IC ngay ở ga đầu tiên.")
                .containsEntry("status", "PUBLISHED").containsEntry("is_edited", false);
        assertThat(topicsOf(id)).containsExactlyInAnyOrderElementsOf(topics);
        assertThat(placesOf(id)).containsExactly(place);
        assertThat(points(author)).isEqualTo(10);
        assertThat(awards(id)).isEqualTo(1);
        assertThat(usage(author, "posts")).isEqualTo(1);
        assertThat(usage(author, "videos")).isZero();
        // Step 9: the new post opens for everyone, Guests included
        getAs(null, "/api/blogs/{id}", id).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.title").value("Năm điều mình ước biết trước " + t))
                .andExpect(jsonPath("$.data.status").value("PUBLISHED"));
    }

    @Test
    @DisplayName("TC-1.5-03 [Luồng chính] Gửi dạng JSON thuần (không tệp) cũng được đăng; tiêu đề nhiều dòng/khoảng trắng được gộp thành một dòng")
    void plainJsonPostWithoutFilesIsAcceptedAndTheTitleIsTrimmed() throws Exception {
        String t = token();
        long author = member(30);
        var result = perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/blogs").contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(post("  Kinh nghiệm   thuê nhà\n" + t + "  ",
                        "  Nội dung  ", List.of(topic("Kinh nghiệm")), List.of()))), author)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value(MSG34));
        long id = createdId(result);
        assertThat(jdbc.queryForMap("SELECT title, content FROM blogs WHERE id = ?", id))
                .containsEntry("title", "Kinh nghiệm thuê nhà " + t)
                .containsEntry("content", "Nội dung");
    }

    @Test
    @DisplayName("TC-1.5-04 [Luồng chính – bước 3] Chọn \"Cảnh báo lừa đảo\" kèm địa điểm: bài đặt dấu \"!\" lên ghim của địa điểm trên bản đồ")
    void scamWarningPostMarksItsPlacesOnTheMap() throws Exception {
        String t = token();
        long author = member(30);
        long place = place("Tiệm " + t);
        long id = createdId(create(author, post("Cẩn thận tiệm này " + t, "Họ đòi đặt cọc trước rồi biến mất.",
                List.of(topic("Cảnh báo lừa đảo")), List.of(place))));

        getAs(null, "/api/blogs/scam-warnings?placeId={p}", place).andExpect(status().isOk())
                .andExpect(jsonPath("$.data[*].blogId", hasItem((int) id)))
                .andExpect(jsonPath("$.data[0].placeId").value((int) place));
    }

    // ================================================================ 1a: who may post

    @Test
    @DisplayName("TC-1.5-05 [Luồng thay thế 1a – Phân quyền] Khách bị yêu cầu đăng nhập (401); tài khoản nhân viên nhận MSG18 (403); không có gì được lưu")
    void guestsMustSignInAndStaffGetMsg18() throws Exception {
        String t = token();
        Map<String, Object> body = post("Bài " + t, "Nội dung", List.of(topic("Kinh nghiệm")), List.of());

        multipartAs(null, "POST", "/api/blogs", "post", body).andExpect(status().isUnauthorized());
        getAs(null, "/api/blogs/compose").andExpect(status().isUnauthorized());

        for (String role : List.of("MODERATOR", "ACADEMIC_MANAGER", "ADMIN")) {
            long staff = account(role, 30);
            create(staff, body).andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.success").value(false))
                    .andExpect(jsonPath("$.message").value(MSG18));
            getAs(staff, "/api/blogs/compose").andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.message").value(MSG18));
            assertThat(postsBy(staff)).isZero();
        }
        // A locked account's session ends at once: it is a Guest again
        long locked = member(30);
        setStatus(locked, "LOCKED");
        create(locked, body).andExpect(status().isUnauthorized());
        assertThat(postsBy(locked)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM blogs WHERE title = ?", Integer.class, "Bài " + t)).isZero();
    }

    // ================================================================ 6a: invalid post information

    @Test
    @DisplayName("TC-1.5-06 [Luồng thay thế 6a] Thiếu tiêu đề, nội dung và chủ đề: lỗi dưới từng ô, dòng trên cùng là MSG37, không lưu gì")
    void missingTitleContentAndTopicGiveOneErrorPerFieldAndMsg37() throws Exception {
        long author = member(30);
        create(author, post("   ", "", List.of(), List.of()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value(MSG37))
                .andExpect(jsonPath("$.data.title").value("Hãy nhập tiêu đề."))
                .andExpect(jsonPath("$.data.content").value("Hãy nhập nội dung bài viết."))
                .andExpect(jsonPath("$.data.topicIds").value(TOPIC_REQUIRED));
        // Only the content missing: the top line is still MSG37
        create(author, post("Có tiêu đề " + token(), "  \n ", List.of(topic("Kinh nghiệm")), List.of()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(MSG37))
                .andExpect(jsonPath("$.data.content").value("Hãy nhập nội dung bài viết."))
                .andExpect(jsonPath("$.data.title").doesNotExist());
        assertThat(postsBy(author)).isZero();
        assertThat(usage(author, "posts")).isZero();
        assertThat(points(author)).isZero();
    }

    @Test
    @DisplayName("TC-1.5-07 [Biên] Tiêu đề 150 ký tự được đăng; 151 ký tự bị từ chối \"Tiêu đề tối đa 150 ký tự.\"")
    void titleOf150CharactersPassesAnd151IsRefused() throws Exception {
        long author = member(30);
        String t = token();
        String title150 = t + "x".repeat(150 - t.length());
        long id = createdId(create(author, post(title150, "Nội dung", List.of(topic("Kinh nghiệm")), List.of())));
        assertThat(jdbc.queryForObject("SELECT CHAR_LENGTH(title) FROM blogs WHERE id = ?", Integer.class, id))
                .isEqualTo(150);

        create(author, post(title150 + "y", "Nội dung", List.of(topic("Kinh nghiệm")), List.of()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Tiêu đề tối đa 150 ký tự."))
                .andExpect(jsonPath("$.data.title").value("Tiêu đề tối đa 150 ký tự."));
        assertThat(postsBy(author)).isEqualTo(1);
    }

    @Test
    @DisplayName("TC-1.5-08 [Biên] Nội dung 5.000 ký tự được đăng; 5.001 ký tự bị từ chối \"Nội dung tối đa 5.000 ký tự.\"")
    void contentOf5000CharactersPassesAnd5001IsRefused() throws Exception {
        long author = member(30);
        long id = createdId(create(author, post("Dài " + token(), chars(5000), List.of(topic("Kinh nghiệm")), List.of())));
        assertThat(jdbc.queryForObject("SELECT CHAR_LENGTH(content) FROM blogs WHERE id = ?", Integer.class, id))
                .isEqualTo(5000);

        create(author, post("Quá dài " + token(), chars(5001), List.of(topic("Kinh nghiệm")), List.of()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Nội dung tối đa 5.000 ký tự."))
                .andExpect(jsonPath("$.data.content").value("Nội dung tối đa 5.000 ký tự."));
        assertThat(postsBy(author)).isEqualTo(1);
    }

    @Test
    @DisplayName("TC-1.5-09 [Biên] Chủ đề: 1 và 3 chủ đề hợp lệ; 0 hoặc 4 chủ đề bị từ chối; chủ đề đã tắt hoặc không tồn tại phải chọn lại")
    void oneToThreeActiveTopicsAreAccepted() throws Exception {
        long author = member(30);
        List<Long> four = activeTopics(4);
        assertThat(four).hasSize(4);

        long one = createdId(create(author, post("Một chủ đề " + token(), "Nội dung", four.subList(0, 1), List.of())));
        assertThat(topicsOf(one)).hasSize(1);
        long three = createdId(create(author, post("Ba chủ đề " + token(), "Nội dung", four.subList(0, 3), List.of())));
        assertThat(topicsOf(three)).containsExactlyInAnyOrderElementsOf(four.subList(0, 3));

        create(author, post("Bốn chủ đề " + token(), "Nội dung", four, List.of()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.data.topicIds").value("Chọn tối đa 3 chủ đề."));
        create(author, post("Không chủ đề " + token(), "Nội dung", List.of(), List.of()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(TOPIC_REQUIRED));
        String stale = "Có chủ đề không còn trong danh sách. Hãy chọn lại chủ đề.";
        create(author, post("Chủ đề tắt " + token(), "Nội dung", List.of(inactiveTopic()), List.of()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.data.topicIds").value(stale));
        create(author, post("Chủ đề lạ " + token(), "Nội dung", List.of(Long.MAX_VALUE), List.of()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.data.topicIds").value(stale));
        assertThat(postsBy(author)).isEqualTo(2);
    }

    @Test
    @DisplayName("TC-1.5-10 [Biên + Luồng thay thế 6b] 5 địa điểm hợp lệ, 6 địa điểm bị từ chối; địa điểm đã bị gỡ phải chọn lại")
    void upToFivePlacesAndAPlaceNoLongerOnTheMapMustBeChosenAgain() throws Exception {
        String t = token();
        long author = member(30);
        List<Long> places = new ArrayList<>();
        for (int i = 1; i <= 6; i++) {
            places.add(place("Nơi " + i + " " + t));
        }
        long id = createdId(create(author, post("Năm nơi " + t, "Nội dung", List.of(topic("Kinh nghiệm")),
                places.subList(0, 5))));
        assertThat(placesOf(id)).containsExactlyInAnyOrderElementsOf(places.subList(0, 5));

        create(author, post("Sáu nơi " + t, "Nội dung", List.of(topic("Kinh nghiệm")), places))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.data.placeIds").value("Chọn tối đa 5 địa điểm."));

        // 6b: a Moderator removed the place after the user picked it
        jdbc.update("UPDATE places SET status = 'REMOVED' WHERE id = ?", places.get(5));
        create(author, post("Nơi đã gỡ " + t, "Nội dung", List.of(topic("Kinh nghiệm")), List.of(places.get(5))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(PLACE_GONE))
                .andExpect(jsonPath("$.data.placeIds").value(PLACE_GONE));
        assertThat(postsBy(author)).isEqualTo(1);
    }

    @Test
    @DisplayName("TC-1.5-11 [Luồng thay thế 6b] Địa điểm OpenStreetMap: nơi Senpai ơi đã có được gắn luôn; nơi mới khi dịch vụ bản đồ không dùng được hoặc mã sai phải chọn lại")
    void openStreetMapPlacesAreLinkedOrMustBeChosenAgain() throws Exception {
        String t = token();
        long author = member(30);
        // A map place Senpai ơi already stores (same osm_id): linked without asking OpenStreetMap
        String known = osmPlace("Lawson " + t, "ACTIVE");
        Map<String, Object> body = post("Konbini " + t, "Nội dung", List.of(topic("Kinh nghiệm")), List.of());
        body.put("externalPlaceIds", List.of(known));
        long id = createdId(create(author, body));
        assertThat(placesOf(id)).containsExactly(placeIdOfOsm(known));

        // A new map place while OpenStreetMap cannot be asked (off in the tests)
        Map<String, Object> unknown = post("Nơi mới " + t, "Nội dung", List.of(topic("Kinh nghiệm")), List.of());
        unknown.put("externalPlaceIds", List.of("N8" + System.nanoTime() % 1_000_000_000L));
        create(author, unknown).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.data.placeIds").value(
                        "Hiện chưa thêm được địa điểm mới từ OpenStreetMap. Hãy chọn địa điểm đã có trên Senpai ơi."));

        // A map place Senpai ơi removed
        Map<String, Object> removed = post("Nơi đã gỡ " + t, "Nội dung", List.of(topic("Kinh nghiệm")), List.of());
        removed.put("externalPlaceIds", List.of(osmPlace("Quán đã gỡ " + t, "REMOVED")));
        create(author, removed).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.data.placeIds").value(PLACE_GONE));

        // Not a map id at all
        Map<String, Object> invalid = post("Mã sai " + t, "Nội dung", List.of(topic("Kinh nghiệm")), List.of());
        invalid.put("externalPlaceIds", List.of("abc"));
        create(author, invalid).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.data.placeIds").value("Có địa điểm không hợp lệ. Hãy chọn lại địa điểm."));
        assertThat(postsBy(author)).isEqualTo(1);
    }

    @Test
    @DisplayName("TC-1.5-12 [Biên] 5 ảnh được nhận và quét; 6 ảnh hoặc tệp không phải ảnh bị từ chối với quy tắc ảnh")
    void fivePhotosAreAcceptedSixOrAFakePhotoAreRefused() throws Exception {
        long author = member(30);
        String t = token();
        create(author, post("Sáu ảnh " + t, "Nội dung", List.of(topic("Kinh nghiệm")), List.of()),
                photo(), photo(), photo(), photo(), photo(), photo())
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.data.images").value(IMAGE_RULE));
        create(author, post("Ảnh giả " + t, "Nội dung", List.of(topic("Kinh nghiệm")), List.of()), notAPhoto())
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.data.images").value(IMAGE_RULE));
        assertThat(postsBy(author)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM media WHERE owner_id = ?", Integer.class, author)).isZero();

        long id = createdId(create(author, post("Năm ảnh " + t, "Nội dung", List.of(topic("Kinh nghiệm")), List.of()),
                photo(), photo(), photo(), photo(), photo())
                .andExpect(jsonPath("$.data.status").value("PROCESSING")));
        awaitStatus(id, "PUBLISHED");
        assertThat(mediaOf(id)).hasSize(5);
        // Photos are re-encoded to JPG
        assertThat(jdbc.queryForList("SELECT DISTINCT m.mime_type FROM blog_media bm JOIN media m ON m.id = bm.media_id "
                + "WHERE bm.blog_id = ?", String.class, id)).containsExactly("image/jpeg");
    }

    @Test
    @DisplayName("TC-1.5-13 [Biên] Video 30 giây được nhận; video 31 giây bị từ chối \"Video dài 31 giây. Video tối đa 30 giây.\"")
    void videoOf30SecondsPassesAnd31IsRefused() throws Exception {
        long author = member(30);
        String t = token();
        create(author, post("Video dài " + t, "Nội dung", List.of(topic("Kinh nghiệm")), List.of()), video(31))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Video dài 31 giây. Video tối đa 30 giây."))
                .andExpect(jsonPath("$.data.video").value("Video dài 31 giây. Video tối đa 30 giây."));
        assertThat(postsBy(author)).isZero();

        long id = createdId(create(author, post("Video 30s " + t, "Nội dung", List.of(topic("Kinh nghiệm")), List.of()),
                video(30)).andExpect(jsonPath("$.data.status").value("PROCESSING")));
        awaitStatus(id, "PUBLISHED");
        assertThat(jdbc.queryForObject("SELECT m.duration_sec FROM blog_media bm JOIN media m ON m.id = bm.media_id "
                + "WHERE bm.blog_id = ?", Integer.class, id)).isEqualTo(30);
        assertThat(usage(author, "videos")).isEqualTo(1);
    }

    // ================================================================ 7a: links and contact details (BR-22)

    @Test
    @DisplayName("TC-1.5-14 [Luồng thay thế 7a] Chưa có tag định danh: không được chèn link, số điện thoại, Zalo/LINE (cả trong tiêu đề)")
    void untaggedMembersCannotPostLinksOrContactDetails() throws Exception {
        long author = member(30);
        String t = token();
        List<Long> topics = List.of(topic("Kinh nghiệm"));
        create(author, post("Link " + t, "Xem https://www.city.arakawa.tokyo.jp nhé", topics, List.of()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(LINK_NEEDS_TAG));
        create(author, post("Xem www.example.com " + t, "Nội dung", topics, List.of()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(LINK_NEEDS_TAG));
        create(author, post("Tuyển gấp " + t, "ib zalo 0901 234 567", topics, List.of()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(CONTACT_NEEDS_TAG));
        create(author, post("Liên hệ " + t, "LINE ID: senpai123", topics, List.of()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(CONTACT_NEEDS_TAG));
        assertThat(postsBy(author)).isZero();
        assertThat(usage(author, "posts")).isZero();

        // Landlines are allowed for everyone
        long id = createdId(create(author, post("Văn phòng quận " + t, "Gọi 03-3802-3111 giờ hành chính.", topics,
                List.of())));
        assertThat(blogStatus(id)).isEqualTo("PUBLISHED");
    }

    @Test
    @DisplayName("TC-1.5-15 [Luồng thay thế 7a] Có tag định danh: được chèn link đầy đủ và số điện thoại, nhưng không ai được dùng link rút gọn")
    void taggedMembersPostLinksButNobodyPostsShortLinks() throws Exception {
        long tagged = member(30);
        grantMentorTag(tagged);
        String t = token();
        List<Long> topics = List.of(topic("Kinh nghiệm"));
        create(tagged, post("Link rút gọn " + t, "Đăng ký ở https://bit.ly/abc123", topics, List.of()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(SHORT_LINK));
        create(tagged, post("Tinyurl " + t, "Xem tinyurl.com/xyz", topics, List.of()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(SHORT_LINK));
        long id = createdId(create(tagged, post("Link đầy đủ " + t,
                "Xem https://www.city.arakawa.tokyo.jp hoặc gọi 090-1234-5678", topics, List.of())));
        assertThat(jdbc.queryForObject("SELECT content FROM blogs WHERE id = ?", String.class, id))
                .isEqualTo("Xem https://www.city.arakawa.tokyo.jp hoặc gọi 090-1234-5678");
    }

    @Test
    @DisplayName("TC-1.5-16 [Luồng thay thế 7a – QR] Ảnh có mã QR được tính như link: tài khoản chưa có tag bị từ chối, nêu rõ ảnh thứ mấy")
    void aPhotoWithAQrCodeCountsAsALink() throws Exception {
        long author = member(30);
        String t = token();
        String expected = "Ảnh thứ 2 bạn vừa chọn có mã QR. Chỉ tài khoản đã có tag định danh mới được đăng ảnh có "
                + "mã QR (mã QR được tính như link). Hãy cắt hoặc che mã QR rồi chọn lại ảnh.";
        create(author, post("Có QR " + t, "Nội dung", List.of(topic("Kinh nghiệm")), List.of()),
                photo(), qrPhoto("https://line.me/ti/p/abc"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(expected))
                .andExpect(jsonPath("$.data.images").value(expected));
        assertThat(postsBy(author)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM media WHERE owner_id = ?", Integer.class, author)).isZero();
    }

    // ================================================================ 7b: new-account daily limit (MSG44)

    @Test
    @DisplayName("TC-1.5-17 [Luồng thay thế 7b – Biên] Tài khoản dưới 7 ngày đăng tối đa 3 bài/ngày (bài thứ 4 nhận MSG44); tài khoản đủ 7 ngày không bị giới hạn")
    void newAccountsPostThreeTimesADayAndOlderAccountsAreNotLimited() throws Exception {
        long newbie = memberAged(Duration.ofDays(7).minusHours(1));
        for (int i = 1; i <= 3; i++) {
            writePost(newbie, "Bài " + i + " " + token());
        }
        create(newbie, post("Bài 4 " + token(), "Nội dung", List.of(topic("Kinh nghiệm")), List.of()))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value(MSG44_POSTS));
        assertThat(postsBy(newbie)).isEqualTo(3);
        assertThat(usage(newbie, "posts")).isEqualTo(3);
        assertThat(points(newbie)).isEqualTo(30);

        long sevenDaysOld = memberAged(Duration.ofDays(7).plusMinutes(5));
        for (int i = 1; i <= 4; i++) {
            writePost(sevenDaysOld, "Bài " + i + " " + token());
        }
        assertThat(usage(sevenDaysOld, "posts")).isEqualTo(4);
    }

    @Test
    @DisplayName("TC-1.5-18 [Luồng thay thế 7b] Tài khoản mới chỉ được 1 video mỗi ngày; bài thứ hai có video bị từ chối, bỏ video thì đăng được")
    void newAccountsAddOneVideoADay() throws Exception {
        long newbie = member(2);
        String t = token();
        long first = createdId(create(newbie, post("Video 1 " + t, "Nội dung", List.of(topic("Kinh nghiệm")), List.of()),
                video(10)));
        assertThat(usage(newbie, "posts")).isEqualTo(1);
        assertThat(usage(newbie, "videos")).isEqualTo(1);

        create(newbie, post("Video 2 " + t, "Nội dung", List.of(topic("Kinh nghiệm")), List.of()), video(10))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.message").value(VIDEO_LIMIT));
        assertThat(postsBy(newbie)).isEqualTo(1);

        writePost(newbie, "Không video " + t);
        assertThat(usage(newbie, "posts")).isEqualTo(2);
        assertThat(usage(newbie, "videos")).isEqualTo(1);
        awaitStatus(first, "PUBLISHED");
    }

    // ================================================================ 7c: banned keywords (MSG36)

    @Test
    @DisplayName("TC-1.5-19 [Luồng thay thế 7c] Từ khóa cấm trong nội dung hoặc tiêu đề: MSG36 nêu từ khóa, không lưu gì, không tính vào hạn mức ngày")
    void bannedKeywordsAreRefusedWithMsg36AndNothingIsStored() throws Exception {
        long author = member(2);
        String t = token();
        create(author, post("Tuyển gấp " + t, "Việc nhẹ, lương-cao cho du học sinh", List.of(topic("Kinh nghiệm")),
                List.of()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(msg36("việc nhẹ lương cao")));
        create(author, post("Kèo fb88 hôm nay " + t, "Nội dung", List.of(topic("Kinh nghiệm")), List.of()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(msg36("fb88")));
        assertThat(postsBy(author)).isZero();
        assertThat(usage(author, "posts")).isZero();
        assertThat(points(author)).isZero();
    }

    @Test
    @DisplayName("TC-1.5-20 [Thứ tự kiểm tra] Thông tin bài → link → hạn mức tài khoản mới → từ khóa cấm (dừng ở bước sai đầu tiên)")
    void checksRunInTheAgreedOrder() throws Exception {
        long newbie = member(2);
        List<Long> topics = List.of(topic("Kinh nghiệm"));
        // Fields before links
        create(newbie, post("", "Xem https://example.com", topics, List.of()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(MSG37));
        // Links before keywords
        create(newbie, post("Vay nóng " + token(), "Xem https://example.com", topics, List.of()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(LINK_NEEDS_TAG));
        for (int i = 1; i <= 3; i++) {
            writePost(newbie, "Bài " + i + " " + token());
        }
        // Links before the daily limit
        create(newbie, post("Bài 4 " + token(), "Xem https://example.com", topics, List.of()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(LINK_NEEDS_TAG));
        // The daily limit before banned keywords
        create(newbie, post("Bài 4 " + token(), "Cần vay nóng gấp", topics, List.of()))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.message").value(MSG44_POSTS));
        assertThat(postsBy(newbie)).isEqualTo(3);
    }

    // ================================================================ 8a: photos or video are scanned first

    @Test
    @DisplayName("TC-1.5-21 [Luồng thay thế 8a] Bài có ảnh chờ quét (PROCESSING), qua quét thì được đăng, +10 điểm và tác giả được thông báo")
    void postWithAPhotoWaitsForTheScanThenIsPublishedWithPoints() throws Exception {
        long author = member(30);
        String title = "Ảnh hanami " + token();
        long id = createdId(create(author, post(title, "Hoa nở rồi", List.of(topic("Kinh nghiệm")), List.of()), photo())
                .andExpect(jsonPath("$.message").value(SENT_FOR_SCAN))
                .andExpect(jsonPath("$.data.status").value("PROCESSING")));
        assertThat(usage(author, "posts")).isEqualTo(1);

        awaitStatus(id, "PUBLISHED");
        assertThat(points(author)).isEqualTo(10);
        assertThat(awards(id)).isEqualTo(1);
        await().atMost(Duration.ofSeconds(5)).until(() -> !notifications(author, "CONTENT_PUBLISHED", id).isEmpty());
        assertThat(notifications(author, "CONTENT_PUBLISHED", id))
                .containsExactly("Bài viết “" + title + "” đã qua kiểm duyệt ảnh/video và đã được đăng. +10 điểm.");
        long media = mediaOf(id).get(0);
        assertThat(jdbc.queryForObject("SELECT scan_status FROM media WHERE id = ?", String.class, media))
                .isEqualTo("PASSED");
        assertThat(fileExists(storageKey(media))).isTrue();
    }

    @Test
    @DisplayName("TC-1.5-22 [Luồng thay thế 8a] Trong lúc quét chỉ tác giả thấy bài (người khác và khách nhận MSG19), chưa cộng điểm")
    void whileTheScanWaitsOnlyTheAuthorSeesThePost() throws Exception {
        when(imageModerator.check(any(byte[].class))).thenReturn(new Verdict("ERROR", "{\"error\":\"down\"}"));
        long author = member(30);
        long other = member(30);
        String t = token();
        long id = createdId(create(author, post("Đang quét " + t, "Nội dung", List.of(topic("Kinh nghiệm")), List.of()),
                photo()));
        await().atMost(Duration.ofSeconds(10)).until(() -> jdbc.queryForObject("""
                SELECT COUNT(*) FROM moderation_scans WHERE target_type = 'MEDIA'
                AND target_id = (SELECT media_id FROM blog_media WHERE blog_id = ?)""", Integer.class, id) > 0);
        assertThat(blogStatus(id)).isEqualTo("PROCESSING");

        getAs(author, "/api/blogs/{id}", id).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("PROCESSING"));
        getAs(other, "/api/blogs/{id}", id).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value(MSG19));
        getAs(null, "/api/blogs/{id}", id).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value(MSG19));
        getAs(author, "/api/blogs?q={q}", t).andExpect(jsonPath("$.data.items[*].id", hasItem((int) id)));
        getAs(other, "/api/blogs?q={q}", t).andExpect(jsonPath("$.data.items[*].id", not(hasItem((int) id))));
        assertThat(points(author)).isZero();
    }

    @Test
    @DisplayName("TC-1.5-23 [Luồng thay thế 8a] Ảnh vi phạm: bài bị từ chối (REJECTED), tệp vi phạm bị xóa, không cộng điểm, tác giả nhận thông báo đúng nội dung")
    void flaggedPhotoRejectsThePostRemovesTheFileAndTellsTheAuthor() throws Exception {
        when(imageModerator.check(any(byte[].class))).thenReturn(new Verdict("FLAG", "{\"flagged\":true}"));
        long author = member(30);
        String title = "Ảnh vi phạm " + token();
        long id = createdId(create(author, post(title, "Nội dung", List.of(topic("Kinh nghiệm")), List.of()), photo()));
        long media = mediaOf(id).get(0);
        String key = storageKey(media);

        awaitStatus(id, "REJECTED");
        assertThat(points(author)).isZero();
        assertThat(awards(id)).isZero();
        assertThat(jdbc.queryForObject("SELECT scan_status FROM media WHERE id = ?", String.class, media))
                .isEqualTo("REJECTED");
        assertThat(mediaDeleted(media)).isTrue();
        await().atMost(Duration.ofSeconds(5)).until(() -> !fileExists(key));
        await().atMost(Duration.ofSeconds(5)).until(() -> !notifications(author, "CONTENT_REJECTED", id).isEmpty());
        assertThat(notifications(author, "CONTENT_REJECTED", id)).containsExactly("Bài viết “" + title
                + "” chưa được đăng vì ảnh hoặc video vi phạm Quy tắc cộng đồng. Bạn có thể sửa bài rồi gửi lại.");
        getAs(null, "/api/blogs/{id}", id).andExpect(status().isNotFound());
    }
}
