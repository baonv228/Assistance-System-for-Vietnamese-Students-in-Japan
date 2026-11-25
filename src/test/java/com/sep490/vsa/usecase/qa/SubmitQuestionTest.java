package com.sep490.vsa.usecase.qa;

import com.fasterxml.jackson.databind.JsonNode;
import com.sep490.vsa.feature.moderation.ImageModerator.Verdict;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

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
 * UC 1.14 Submit Question ("Đặt câu hỏi") – SRS Report 3, II.1.11 (Submit Question), which reuses the rules of
 * II.1.5 Create Post (file errors 6a, place errors 6b, links 7a, daily limit 7b, banned keyword 7c, media scan 8a).
 * Later team decisions from CLAUDE.md ("Hỏi-Đáp", "Write Post"): the description is optional, questions count
 * together with posts in {@code user_daily_usage}, MSG34 reads "Câu hỏi của bạn đã được đăng.", +10 points
 * ({@code PointService.BLOG_POST}) the first time the question is published.
 * <p>
 * Not testable on the server (UI only): 3a Cancel with MSG15, 8b "Phiên đăng nhập đã hết…" (the page keeps the
 * text), "Everything typed is kept" of 6a.
 */
@DisplayName("UC 1.14 – Submit Question (Đặt câu hỏi)")
class SubmitQuestionTest extends QuestionTestSupport {

    @Test
    @DisplayName("TC-1.14-01 [Luồng chính 1-2] – Form \"Đặt câu hỏi\" có chủ đề đang dùng, luật link, giới hạn ảnh/video và hạn mức hôm nay của tài khoản mới")
    void composeShowsTopicsRulesAndTodayUsageOfANewAccount() throws Exception {
        long newbie = member(2);

        JsonNode data = data(getAs(newbie, "/api/questions/compose").andExpect(status().isOk()));

        String t = token();
        long switchedOff = inactiveTopic(t);
        data = data(getAs(newbie, "/api/questions/compose").andExpect(status().isOk()));
        assertThat(data.get("topics").findValuesAsText("name")).contains("Kinh nghiệm", "Khác");
        assertThat(data.get("topics").findValues("id")).extracting(JsonNode::asLong).doesNotContain(switchedOff);
        assertThat(data.at("/limits/titleMax").asInt()).isEqualTo(150);
        assertThat(data.at("/limits/contentMax").asInt()).isEqualTo(5000);
        assertThat(data.at("/limits/topicsMax").asInt()).isEqualTo(3);
        assertThat(data.at("/limits/placesMax").asInt()).isEqualTo(5);
        assertThat(data.at("/limits/imagesMax").asInt()).isEqualTo(5);
        assertThat(data.at("/limits/imageMaxMb").asInt()).isEqualTo(20);
        assertThat(data.at("/limits/videosMax").asInt()).isEqualTo(1);
        assertThat(data.at("/limits/videoMaxSeconds").asInt()).isEqualTo(30);
        assertThat(data.at("/limits/questionContentRequired").asBoolean()).isFalse();
        // "Tài khoản mới, hôm nay: X/3 bài viết và review · Y/2 ghim · Z/1 video."
        assertThat(data.at("/quota/newAccount").asBoolean()).isTrue();
        assertThat(data.at("/quota/postsUsed").asInt()).isZero();
        assertThat(data.at("/quota/postsLimit").asInt()).isEqualTo(3);
        assertThat(data.at("/quota/pinsLimit").asInt()).isEqualTo(2);
        assertThat(data.at("/quota/videosLimit").asInt()).isEqualTo(1);
        assertThat(data.get("canPostLinks").asBoolean()).isFalse();

        long veteran = member(30);
        grantMentorTag(veteran);
        JsonNode old = data(getAs(veteran, "/api/questions/compose").andExpect(status().isOk()));
        assertThat(old.at("/quota/newAccount").asBoolean()).isFalse();
        assertThat(old.get("canPostLinks").asBoolean()).isTrue();
    }

    @Test
    @DisplayName("TC-1.14-02 [Luồng chính 3-9] – Câu hỏi không có ảnh/video được đăng ngay: MSG34, +10 điểm, lưu chủ đề, địa điểm, tính vào hạn mức ngày, nằm ở \"Chưa ai trả lời\"")
    void questionWithoutFilesIsPublishedAtOnce() throws Exception {
        String t = token();
        long asker = member();
        long place = place("Ga Ueno " + t);
        List<Long> topicIds = topics("Visa và cư trú", "Thủ tục giấy tờ");

        JsonNode body = body(sendQuestion(asker, question("Gia hạn visa du học " + t + " cần giấy gì?",
                "Mình ở Arakawa, visa còn 2 tháng.", topicIds, List.of(place)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value(MSG34_QUESTION))
                .andExpect(jsonPath("$.data.type").value("QA"))
                .andExpect(jsonPath("$.data.status").value("PUBLISHED")));
        long id = body.at("/data/id").asLong();

        Map<String, Object> row = jdbc.queryForMap("SELECT blog_type, title, content, status, is_edited FROM blogs WHERE id = ?", id);
        assertThat(row.get("blog_type")).isEqualTo("QA");
        assertThat(row.get("title")).isEqualTo("Gia hạn visa du học " + t + " cần giấy gì?");
        assertThat(row.get("status")).isEqualTo("PUBLISHED");
        assertThat(row.get("is_edited")).isEqualTo(false);
        assertThat(topicLinks(id)).containsExactlyElementsOf(topicIds);
        assertThat(placeLinks(id)).containsExactly(place);
        assertThat(points(asker)).isEqualTo(10);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM point_transactions WHERE user_id = ? AND reason = 'BLOG_CREATED' "
                + "AND ref_type = 'BLOG' AND ref_id = ? AND delta = 10", Integer.class, asker, id)).isEqualTo(1);
        assertThat(usagePosts(asker)).isEqualTo(1);
        // Postcondition: published and listed under "Chưa ai trả lời"
        assertThat(listIds(null, t, "UNANSWERED", null)).containsExactly(id);
        getAs(null, "/api/questions/{id}", id).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.type").value("QA"));
    }

    @Test
    @DisplayName("TC-1.14-03 [Luồng chính 3 – CLAUDE.md] – \"Mô tả chi tiết\" không bắt buộc: câu hỏi chỉ có tiêu đề vẫn được đăng")
    void descriptionIsOptional() throws Exception {
        String t = token();
        long asker = member();

        sendQuestion(asker, question("Chỉ hỏi ngắn " + t + "?", "", List.of(topic("Khác")), List.of()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value(MSG34_QUESTION));
        sendQuestion(asker, question("Không gửi mô tả " + t + "?", null, List.of(topic("Khác")), null))
                .andExpect(status().isOk());

        assertThat(listIds(asker, t, null, null)).hasSize(2);
    }

    @Test
    @DisplayName("TC-1.14-04 [Biên] – \"Câu hỏi\" đúng 150 ký tự được nhận; 151 ký tự báo \"Câu hỏi tối đa 150 ký tự.\" và không lưu gì")
    void questionTitleBoundary() throws Exception {
        String t = token();
        long asker = member();
        String title150 = (t + " ").concat(chars(150 - t.length() - 1));
        assertThat(title150).hasSize(150);

        sendQuestion(asker, question(title150, "", List.of(topic("Khác")), List.of())).andExpect(status().isOk());

        String title151 = title150 + "b";
        sendQuestion(asker, question(title151, "", List.of(topic("Khác")), List.of()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value(TITLE_TOO_LONG))
                .andExpect(jsonPath("$.data.title").value(TITLE_TOO_LONG));
        assertThat(questionsBy(asker)).isEqualTo(1);
    }

    @Test
    @DisplayName("TC-1.14-05 [Luồng thay thế 6a] – Thiếu câu hỏi (rỗng hoặc chỉ có khoảng trắng) báo \"Hãy nhập câu hỏi.\"")
    void missingQuestionIsRefused() throws Exception {
        long asker = member();

        for (String title : new String[]{"", "   ", null}) {
            sendQuestion(asker, question(title, "Mô tả", List.of(topic("Khác")), List.of()))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.message").value(TITLE_REQUIRED))
                    .andExpect(jsonPath("$.data.title").value(TITLE_REQUIRED));
        }
        assertThat(questionsBy(asker)).isZero();
        assertThat(points(asker)).isZero();
    }

    @Test
    @DisplayName("TC-1.14-06 [Biên] – \"Mô tả chi tiết\" đúng 5.000 ký tự được nhận; 5.001 ký tự báo \"Mô tả chi tiết tối đa 5.000 ký tự.\"")
    void descriptionBoundary() throws Exception {
        String t = token();
        long asker = member();

        sendQuestion(asker, question("Mô tả dài " + t, chars(5000), List.of(topic("Khác")), List.of()))
                .andExpect(status().isOk());
        sendQuestion(asker, question("Mô tả quá dài " + t, chars(5001), List.of(topic("Khác")), List.of()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(DESCRIPTION_TOO_LONG))
                .andExpect(jsonPath("$.data.content").value(DESCRIPTION_TOO_LONG));
        assertThat(questionsBy(asker)).isEqualTo(1);
    }

    @Test
    @DisplayName("TC-1.14-07 [Biên] – Chủ đề: 0 chủ đề bị từ chối, 1 và 3 chủ đề được nhận, 4 chủ đề hoặc chủ đề đã tắt bị từ chối")
    void topicBoundaries() throws Exception {
        String t = token();
        long asker = member();

        sendQuestion(asker, question("Không chủ đề " + t, "", List.of(), List.of()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.data.topicIds").value(TOPIC_REQUIRED));
        sendQuestion(asker, question("Một chủ đề " + t, "", topics("Nhà ở"), List.of()))
                .andExpect(status().isOk());
        sendQuestion(asker, question("Ba chủ đề " + t, "", topics("Nhà ở", "Đi lại", "Y tế"), List.of()))
                .andExpect(status().isOk());
        sendQuestion(asker, question("Bốn chủ đề " + t, "", topics("Nhà ở", "Đi lại", "Y tế", "Ăn uống"), List.of()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.data.topicIds").value(TOPICS_TOO_MANY));
        sendQuestion(asker, question("Chủ đề đã tắt " + t, "", List.of(inactiveTopic(t)), List.of()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.data.topicIds").value(TOPIC_GONE));

        assertThat(questionsBy(asker)).isEqualTo(2);
    }

    @Test
    @DisplayName("TC-1.14-08 [Luồng thay thế 6a] – Nhiều trường sai cùng lúc: mỗi trường có lỗi riêng, dòng đầu là lỗi của trường đầu tiên (không phải MSG37)")
    void everyInvalidFieldIsReportedAtOnce() throws Exception {
        long asker = member();

        JsonNode body = body(sendQuestion(asker, question("", chars(5001), List.of(), List.of()))
                .andExpect(status().isBadRequest()));

        assertThat(body.get("message").asText()).isEqualTo(TITLE_REQUIRED);
        assertThat(body.at("/data/title").asText()).isEqualTo(TITLE_REQUIRED);
        assertThat(body.at("/data/topicIds").asText()).isEqualTo(TOPIC_REQUIRED);
        assertThat(body.at("/data/content").asText()).isEqualTo(DESCRIPTION_TOO_LONG);
        assertThat(questionsBy(asker)).isZero();
    }

    @Test
    @DisplayName("TC-1.14-09 [Luồng thay thế 6a – tệp] – Quá 5 ảnh hoặc video dài hơn 30 giây bị từ chối với thông báo của Create Post")
    void fileRulesOfCreatePost() throws Exception {
        String t = token();
        long asker = member();
        Map<String, Object> body = question("Ảnh quá nhiều " + t, "", List.of(topic("Khác")), List.of());

        multipartAs(asker, "POST", "/api/questions", "question", body,
                photo(), photo(), photo(), photo(), photo(), photo())
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.data.images").value(IMAGE_RULE));
        multipartAs(asker, "POST", "/api/questions", "question", body, video(40))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.data.video").value("Video dài 40 giây. Video tối đa 30 giây."));

        assertThat(questionsBy(asker)).isZero();
    }

    @Test
    @DisplayName("TC-1.14-10 [Luồng thay thế 6b] – Địa điểm: tối đa 5; địa điểm đã bị gỡ khỏi bản đồ phải chọn lại")
    void placeRules() throws Exception {
        String t = token();
        long asker = member();
        List<Long> six = List.of(place("P1 " + t), place("P2 " + t), place("P3 " + t), place("P4 " + t),
                place("P5 " + t), place("P6 " + t));

        sendQuestion(asker, question("Sáu địa điểm " + t, "", List.of(topic("Khác")), six))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.data.placeIds").value(PLACES_TOO_MANY));
        sendQuestion(asker, question("Năm địa điểm " + t, "", List.of(topic("Khác")), six.subList(0, 5)))
                .andExpect(status().isOk());

        long removed = place("Đã gỡ " + t);
        jdbc.update("UPDATE places SET status = 'REMOVED', removed_reason = 'test' WHERE id = ?", removed);
        sendQuestion(asker, question("Địa điểm đã gỡ " + t, "", List.of(topic("Khác")), List.of(removed)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.data.placeIds").value(PLACE_GONE));

        assertThat(questionsBy(asker)).isEqualTo(1);
    }

    @Test
    @DisplayName("TC-1.14-11 [Luồng thay thế 7a] – Link, số điện thoại chỉ cho tài khoản có tag định danh; link rút gọn bị cấm với mọi người")
    void linksAndContactsNeedAnIdentityTag() throws Exception {
        String t = token();
        long plain = member();
        long tagged = member();
        grantMentorTag(tagged);

        sendQuestion(plain, question("Xem hướng dẫn " + t, "Tại https://www.moj.go.jp/isa/ nhé", List.of(topic("Khác")), List.of()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(LINK_NEEDS_TAG));
        sendQuestion(plain, question("Gọi mình " + t, "Số mình là 0901234567", List.of(topic("Khác")), List.of()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(PHONE_NEEDS_TAG));
        assertThat(questionsBy(plain)).isZero();

        sendQuestion(tagged, question("Xem hướng dẫn " + t, "Tại https://www.moj.go.jp/isa/ nhé", List.of(topic("Khác")), List.of()))
                .andExpect(status().isOk());
        sendQuestion(tagged, question("Link rút gọn " + t, "Bấm bit.ly/abc123 nhé", List.of(topic("Khác")), List.of()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(SHORT_LINK));
        assertThat(questionsBy(tagged)).isEqualTo(1);
    }

    @Test
    @DisplayName("TC-1.14-12 [Luồng thay thế 7a – MSG44] – Tài khoản dưới 7 ngày: câu hỏi tính chung với bài viết, tối đa 3 mỗi ngày; tài khoản cũ không bị giới hạn")
    void newAccountDailyLimitCountsQuestionsWithPosts() throws Exception {
        String t = token();
        long newbie = member(2);
        long kinhNghiem = topic("Kinh nghiệm");

        for (int i = 1; i <= 2; i++) {
            postAs(newbie, Map.of("title", "Bài " + i + " " + t, "content", "Nội dung", "topicIds", List.of(kinhNghiem)),
                    "/api/blogs").andExpect(status().isOk());
        }
        sendQuestion(newbie, question("Câu hỏi thứ ba " + t, "", List.of(kinhNghiem), List.of()))
                .andExpect(status().isOk());
        assertThat(usagePosts(newbie)).isEqualTo(3);

        sendQuestion(newbie, question("Câu hỏi thứ tư " + t, "", List.of(kinhNghiem), List.of()))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.message").value(MSG44));
        assertThat(questionsBy(newbie)).isEqualTo(1);
        assertThat(usagePosts(newbie)).isEqualTo(3);

        long veteran = member(30);
        for (int i = 1; i <= 4; i++) {
            sendQuestion(veteran, question("Câu hỏi " + i + " " + t, "", List.of(kinhNghiem), List.of()))
                    .andExpect(status().isOk());
        }
        assertThat(questionsBy(veteran)).isEqualTo(4);
    }

    @Test
    @DisplayName("TC-1.14-13 [Luồng thay thế 7a – video] – Tài khoản dưới 7 ngày chỉ đăng 1 video mỗi ngày")
    void newAccountMayAddOneVideoADay() throws Exception {
        String t = token();
        long newbie = member(3);

        multipartAs(newbie, "POST", "/api/questions", "question",
                question("Video thứ nhất " + t, "", List.of(topic("Khác")), List.of()), video(10))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("PROCESSING"));
        assertThat(usageVideos(newbie)).isEqualTo(1);

        multipartAs(newbie, "POST", "/api/questions", "question",
                question("Video thứ hai " + t, "", List.of(topic("Khác")), List.of()), video(10))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.message").value(VIDEO_LIMIT));
        assertThat(questionsBy(newbie)).isEqualTo(1);
    }

    @Test
    @DisplayName("TC-1.14-14 [Luồng thay thế 7a – MSG36] – Từ khóa bị cấm trong câu hỏi hoặc mô tả: báo MSG36, không lưu, không cộng điểm")
    void bannedKeywordStoresNothing() throws Exception {
        String t = token();
        long asker = member();

        sendQuestion(asker, question("Ai biết chỗ " + BANNED + " " + t + "?", "", List.of(topic("Khác")), List.of()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(MSG36));
        sendQuestion(asker, question("Hỏi chuyện tiền " + t, "Chỗ nào " + BANNED + " uy tín?", List.of(topic("Khác")), List.of()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(MSG36));

        assertThat(questionsBy(asker)).isZero();
        assertThat(points(asker)).isZero();
        assertThat(usagePosts(asker)).isZero();
    }

    @Test
    @DisplayName("TC-1.14-15 [Luồng chính 7 – thứ tự] – Kiểm tra theo thứ tự: link → giới hạn trong ngày → từ khóa cấm")
    void communityRulesRunInTheDocumentedOrder() throws Exception {
        String t = token();
        long newbie = member(2);
        jdbc.update("INSERT INTO user_daily_usage (user_id, usage_date, posts) VALUES (?, ?, 3)",
                newbie, Date.valueOf(japanToday()));

        // Link and keyword and limit all broken: the link is reported first
        sendQuestion(newbie, question("Hỏi " + BANNED + " " + t, "Xem https://example.com/a", List.of(topic("Khác")), List.of()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(LINK_NEEDS_TAG));
        // Limit and keyword broken: the daily limit comes before the keyword
        sendQuestion(newbie, question("Hỏi " + BANNED + " " + t, "", List.of(topic("Khác")), List.of()))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.message").value(MSG44));
        // Field errors come before every community rule
        sendQuestion(newbie, question("", "Xem https://example.com/a " + BANNED, List.of(topic("Khác")), List.of()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(TITLE_REQUIRED));
        assertThat(questionsBy(newbie)).isZero();
    }

    @Test
    @DisplayName("TC-1.14-16 [Luồng thay thế 8a] – Câu hỏi có ảnh chờ quét (chỉ người hỏi thấy), quét đạt thì được đăng, +10 điểm và có thông báo")
    void questionWithAPhotoWaitsForTheScanThenIsPublished() throws Exception {
        String t = token();
        long asker = member();
        long other = member();
        CountDownLatch release = new CountDownLatch(1);
        when(imageModerator.check(any(byte[].class))).thenAnswer(inv -> {
            release.await(15, TimeUnit.SECONDS);
            return new Verdict("PASS", "{\"flagged\":false}");
        });
        String title = "Ảnh thẻ cư trú " + t + " có đúng không?";

        JsonNode body = body(multipartAs(asker, "POST", "/api/questions", "question",
                question(title, "Mình gửi ảnh mẫu.", List.of(topic("Visa và cư trú")), List.of()), photo())
                .andExpect(status().isOk()));
        long id = body.at("/data/id").asLong();
        // SRS 8a writes "… chỉ bạn thấy bài."; the system says "câu hỏi" (same wording as Update Question 7a)
        assertThat(body.get("message").asText()).isEqualTo(SENT_FOR_SCAN);
        assertThat(body.at("/data/status").asText()).isEqualTo("PROCESSING");

        // While the scan runs: only the asker sees the question, with its status
        assertThat(listIds(asker, t, null, null)).containsExactly(id);
        assertThat(listIds(other, t, null, null)).isEmpty();
        assertThat(listIds(null, t, null, null)).isEmpty();
        getAs(asker, "/api/questions/{id}", id).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("PROCESSING"));
        getAs(other, "/api/questions/{id}", id).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value(MSG19));
        assertThat(points(asker)).isZero();

        release.countDown();
        awaitStatus(id, "PUBLISHED");

        assertThat(points(asker)).isEqualTo(10);
        assertThat(notificationsOf(asker))
                .contains("Câu hỏi “" + title + "” đã qua kiểm duyệt ảnh/video và đã được đăng. +10 điểm.");
        assertThat(listIds(other, t, null, null)).containsExactly(id);
    }

    @Test
    @DisplayName("TC-1.14-17 [Luồng thay thế 8a] – Ảnh không qua kiểm duyệt: câu hỏi bị từ chối, ảnh bị gỡ, người hỏi được báo, không cộng điểm")
    void questionWithAFlaggedPhotoIsRejected() throws Exception {
        String t = token();
        long asker = member();
        photosFailTheScan();
        String title = "Ảnh vi phạm " + t;

        long id = body(multipartAs(asker, "POST", "/api/questions", "question",
                question(title, "", List.of(topic("Khác")), List.of()), photo())
                .andExpect(status().isOk())).at("/data/id").asLong();
        awaitStatus(id, "REJECTED");

        assertThat(notificationsOf(asker)).contains("Câu hỏi “" + title
                + "” chưa được đăng vì ảnh hoặc video vi phạm Quy tắc cộng đồng. Bạn có thể sửa bài rồi gửi lại.");
        assertThat(jdbc.queryForList("""
                SELECT m.deleted_at FROM media m JOIN blog_media bm ON bm.media_id = m.id WHERE bm.blog_id = ?""",
                Object.class, id)).hasSize(1).doesNotContainNull();
        assertThat(points(asker)).isZero();
        assertThat(listIds(null, t, null, null)).isEmpty();
    }

    @Test
    @DisplayName("TC-1.14-18 [Phân quyền 1a] – Khách phải đăng nhập (401); tài khoản nhân viên nhận MSG18 \"Bạn không có quyền thực hiện thao tác này.\"")
    void guestsAndStaffCannotAsk() throws Exception {
        String t = token();
        Map<String, Object> body = question("Câu hỏi " + t, "", List.of(topic("Khác")), List.of());

        getAs(null, "/api/questions/compose").andExpect(status().isUnauthorized());
        sendQuestion(null, body).andExpect(status().isUnauthorized());

        for (String role : new String[]{"MODERATOR", "ACADEMIC_MANAGER", "ADMIN"}) {
            long staff = account(role, 30);
            getAs(staff, "/api/questions/compose").andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.message").value(MSG18));
            sendQuestion(staff, body).andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.message").value(MSG18));
            assertThat(questionsBy(staff)).isZero();
        }
    }
}
