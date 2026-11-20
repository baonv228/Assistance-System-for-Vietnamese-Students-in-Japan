package com.sep490.vsa.usecase.qa;

import com.fasterxml.jackson.databind.JsonNode;
import com.sep490.vsa.feature.moderation.ImageModerator.Verdict;
import com.sep490.vsa.usecase.UseCaseTestBase;
import org.springframework.test.web.servlet.ResultActions;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Helpers shared by the Hỏi-Đáp use case tests (UC 1.14 – 1.18): asking, answering and listing questions through
 * the real API, plus the messages of the SRS that these use cases show.
 */
abstract class QuestionTestSupport extends UseCaseTestBase {

    // SRS 1.11 (Submit Question) and the shared rules of Create Post
    static final String MSG34_QUESTION = "Câu hỏi của bạn đã được đăng.";
    static final String SENT_FOR_SCAN = "Đã gửi. Ảnh và video đang được quét, trong lúc này chỉ bạn thấy câu hỏi.";
    static final String SAVED_CHANGES = "Đã lưu thay đổi.";
    static final String SAVED_CHANGES_SCANNING =
            "Đã lưu thay đổi. Ảnh và video đang được quét, trong lúc này chỉ bạn thấy câu hỏi.";
    static final String DELETED = "Đã xóa câu hỏi.";
    static final String TITLE_REQUIRED = "Hãy nhập câu hỏi.";
    static final String TITLE_TOO_LONG = "Câu hỏi tối đa 150 ký tự.";
    static final String TOPIC_REQUIRED = "Hãy chọn ít nhất 1 chủ đề. Không có chủ đề phù hợp thì chọn “Khác”.";
    static final String TOPICS_TOO_MANY = "Chọn tối đa 3 chủ đề.";
    static final String TOPIC_GONE = "Có chủ đề không còn trong danh sách. Hãy chọn lại chủ đề.";
    static final String DESCRIPTION_TOO_LONG = "Mô tả chi tiết tối đa 5.000 ký tự.";
    static final String PLACES_TOO_MANY = "Chọn tối đa 5 địa điểm.";
    static final String PLACE_GONE = "Có địa điểm không còn trên bản đồ. Hãy chọn lại địa điểm.";
    static final String IMAGE_RULE = "Tối đa 5 ảnh, định dạng JPG, PNG hoặc HEIC, mỗi ảnh không quá 20 MB.";
    static final String LINK_NEEDS_TAG = "Chỉ tài khoản đã có tag định danh mới được chèn link.";
    static final String PHONE_NEEDS_TAG =
            "Chỉ tài khoản đã có tag định danh mới được ghi số điện thoại hoặc tài khoản Zalo, LINE, Telegram.";
    static final String SHORT_LINK = "Không dùng link rút gọn (bit.ly, tinyurl…). Hãy dán link đầy đủ.";
    static final String MSG44 = "Bạn đã đạt giới hạn đăng bài trong ngày của tài khoản mới (dưới 7 ngày: tối đa 3 bài "
            + "viết hoặc review mỗi ngày). Vui lòng thử lại vào ngày mai.";
    static final String VIDEO_LIMIT = "Bạn đã đạt giới hạn video trong ngày của tài khoản mới (tối đa 1 video mỗi "
            + "ngày). Hãy bỏ video hoặc thử lại vào ngày mai.";
    static final String BANNED = "vay nóng";
    static final String MSG36 = "Chưa đăng được: nội dung chứa từ khóa không được phép “vay nóng”. Hãy sửa rồi gửi lại.";

    /** "Hãy nhập …" and friends are 400 with one message per field in {@code data}. */
    static final String KEYWORD_TOO_LONG = "Từ khóa tìm kiếm tối đa 100 ký tự.";

    // ================================================================ request bodies

    /** The JSON part of "Đặt câu hỏi" / "Sửa câu hỏi" (keepMediaIds only on an edit). */
    static Map<String, Object> question(String title, String content, List<Long> topicIds, List<Long> placeIds) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("title", title);
        body.put("content", content);
        body.put("topicIds", topicIds);
        body.put("placeIds", placeIds);
        return body;
    }

    static Map<String, Object> edit(String title, String content, List<Long> topicIds, List<Long> placeIds,
                                    List<Long> keepMediaIds) {
        Map<String, Object> body = question(title, content, topicIds, placeIds);
        body.put("keepMediaIds", keepMediaIds);
        return body;
    }

    static Map<String, Object> answer(String content, Long parentId) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("content", content);
        body.put("parentId", parentId);
        return body;
    }

    // ================================================================ API calls

    /** "Đặt câu hỏi" without files (JSON), as the page sends it when no photo or video is chosen. */
    ResultActions sendQuestion(Long asUserId, Map<String, Object> body) throws Exception {
        return postAs(asUserId, body, "/api/questions");
    }

    /** A published question on "Kinh nghiệm" asked through the API (+10 points to the asker). */
    long ask(long askerId, String title, String content) throws Exception {
        return ask(askerId, title, content, List.of(topic("Kinh nghiệm")), List.of());
    }

    long ask(long askerId, String title, String content, List<Long> topicIds, List<Long> placeIds) throws Exception {
        JsonNode body = body(sendQuestion(askerId, question(title, content, topicIds, placeIds)));
        assertThat(body.get("success").asBoolean()).as("ask: %s", body).isTrue();
        assertThat(body.at("/data/status").asText()).isEqualTo("PUBLISHED");
        return body.at("/data/id").asLong();
    }

    /** A direct answer to the question; returns the new comment id. */
    long answerQuestion(long writerId, long questionId, String content) throws Exception {
        JsonNode body = body(postAs(writerId, answer(content, null), "/api/questions/{id}/answers", questionId));
        assertThat(body.get("success").asBoolean()).as("answer: %s", body).isTrue();
        long id = lastCommentId(questionId, writerId);
        // The next answer of the same member must not meet the 15-second spacing of Submit Answer
        ageCommentsOf(writerId, Duration.ofSeconds(30));
        return id;
    }

    /** A reply under an answer; returns the new comment id. */
    long reply(long writerId, long questionId, long parentId, String content) throws Exception {
        JsonNode body = body(postAs(writerId, answer(content, parentId), "/api/questions/{id}/answers", questionId));
        assertThat(body.get("success").asBoolean()).as("reply: %s", body).isTrue();
        long id = lastCommentId(questionId, writerId);
        ageCommentsOf(writerId, Duration.ofSeconds(30));
        return id;
    }

    ResultActions chooseBest(Long asUserId, long questionId, Long answerId) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("answerId", answerId);
        return putAs(asUserId, body, "/api/questions/{id}/best-answer", questionId);
    }

    /** Reports with the reason of this label (Lừa đảo, Sai sự thật, Xúc phạm, Spam). */
    ResultActions reportQuestion(long reporterId, long questionId, String reasonLabel) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("reasonId", reasonId(reasonLabel));
        body.put("note", null);
        return postAs(reporterId, body, "/api/questions/{id}/report", questionId);
    }

    ResultActions reportComment(long reporterId, long commentId, String reasonLabel) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("reasonId", reasonId(reasonLabel));
        return postAs(reporterId, body, "/api/comments/{id}/report", commentId);
    }

    long reasonId(String label) {
        return jdbc.queryForObject("SELECT id FROM reason_options WHERE scope = 'REPORT' AND label = ?", Long.class,
                label);
    }

    // ================================================================ list

    /** Ids of the questions the list shows for this keyword and filters (paging: the first page of 30). */
    List<Long> listIds(Long viewerId, String keyword, String status, String sort) throws Exception {
        StringBuilder url = new StringBuilder("/api/questions?size=30&q=").append(keyword);
        if (status != null) {
            url.append("&status=").append(status);
        }
        if (sort != null) {
            url.append("&sort=").append(sort);
        }
        return ids(data(getAs(viewerId, url.toString())).get("items"));
    }

    static List<Long> ids(JsonNode items) {
        List<Long> ids = new ArrayList<>();
        items.forEach(item -> ids.add(item.get("id").asLong()));
        return ids;
    }

    /** The card of one question in the list searched by the keyword. */
    JsonNode card(Long viewerId, String keyword, long questionId) throws Exception {
        for (JsonNode item : data(getAs(viewerId, "/api/questions?size=30&q=" + keyword)).get("items")) {
            if (item.get("id").asLong() == questionId) {
                return item;
            }
        }
        return null;
    }

    // ================================================================ scan

    /** The OpenAI photo scan flags every photo from now on (the base resets it to PASS before each test). */
    void photosFailTheScan() {
        when(imageModerator.check(any(byte[].class))).thenReturn(new Verdict("FLAG", "{\"flagged\":true}"));
    }

    void awaitStatus(long blogId, String status) {
        await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofMillis(200))
                .until(() -> status.equals(blogStatus(blogId)));
    }

    // ================================================================ rows

    List<String> notificationsOf(long userId) {
        return jdbc.queryForList("SELECT content FROM notifications WHERE user_id = ? ORDER BY id", String.class, userId);
    }

    int usagePosts(long userId) {
        List<Integer> rows = jdbc.queryForList(
                "SELECT posts FROM user_daily_usage WHERE user_id = ? AND usage_date = ?", Integer.class, userId,
                java.sql.Date.valueOf(japanToday()));
        return rows.isEmpty() ? 0 : rows.get(0);
    }

    int usageVideos(long userId) {
        List<Integer> rows = jdbc.queryForList(
                "SELECT videos FROM user_daily_usage WHERE user_id = ? AND usage_date = ?", Integer.class, userId,
                java.sql.Date.valueOf(japanToday()));
        return rows.isEmpty() ? 0 : rows.get(0);
    }

    List<Long> topicLinks(long blogId) {
        return jdbc.queryForList("SELECT topic_id FROM blog_topic_links WHERE blog_id = ? ORDER BY topic_id",
                Long.class, blogId);
    }

    List<Long> placeLinks(long blogId) {
        return jdbc.queryForList("SELECT place_id FROM blog_place_links WHERE blog_id = ? ORDER BY place_id",
                Long.class, blogId);
    }

    long questionsBy(long userId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM blogs WHERE author_id = ? AND blog_type = 'QA'", Long.class,
                userId);
    }

    /** Topic ids in ascending order. */
    List<Long> topics(String... names) {
        return Arrays.stream(names).map(this::topic).sorted().toList();
    }

    /** A Blog topic switched off by the Admin (not offered any more), named after the token. */
    long inactiveTopic(String token) {
        String name = "UC tắt " + token;
        jdbc.update("INSERT INTO blog_topics (name, is_other, sort_order, is_active) VALUES (?, FALSE, 500, FALSE)", name);
        return jdbc.queryForObject("SELECT id FROM blog_topics WHERE name = ?", Long.class, name);
    }

    /** Points needed for the highest rank, "Senpai tin cậy". */
    int senpaiPoints() {
        return jdbc.queryForObject("SELECT MAX(min_points) FROM ranks", Integer.class);
    }
}
