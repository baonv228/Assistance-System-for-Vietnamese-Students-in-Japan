package com.sep490.vsa.usecase.feed;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * UC 1.2 Update Feed Preferences – SRS Report 3, II.1.2 (normal flow 1–6, alternative flows 3b, 4a, 5a, postconditions)
 * and the later team decisions in CLAUDE.md: "Điều chỉnh" on the Home saves area, stage and "Chủ đề quan tâm" with one
 * {@code PUT /api/me/feed-preferences} {@code {areaId, stage, topicIds}}, all or nothing ({@code topicIds} null = topics
 * unchanged); "Tôi" saves area and stage with {@code PUT /api/me/profile}. Living areas are those of V10 (only active
 * ones can be chosen).
 */
@DisplayName("UC 1.2 – Update Feed Preferences (Cập nhật tùy chọn bảng tin)")
class UpdateFeedPreferencesTest extends FeedTestSupport {

    private static final String INVALID_STAGE = "Giai đoạn không hợp lệ";
    private static final String INVALID_AREA = "Khu vực không hợp lệ";

    @Test
    @DisplayName("TC-1.2-01 [Luồng chính – trang Tôi] Chọn khu vực và giai đoạn rồi bấm \"Lưu\": lưu vào tài khoản, báo \"Đã lưu khu vực và giai đoạn.\"")
    void saveAreaAndStageOnProfile() throws Exception {
        String t = token();
        long area = newArea("Khu " + t, true);
        long me = member();

        JsonNode body = body(putAs(me, prefs(area, "ARR"), "/api/me/profile")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true)));

        assertThat(body.get("message").asText()).isEqualTo("Đã lưu khu vực và giai đoạn.");
        assertThat(body.get("data").get("areaId").asLong()).isEqualTo(area);
        assertThat(body.get("data").get("areaName").asText()).isEqualTo("Khu " + t);
        assertThat(body.get("data").get("stage").asText()).isEqualTo("ARR");
        assertThat(storedArea(me)).isEqualTo(area);
        assertThat(storedStage(me)).isEqualTo("ARR");
    }

    @Test
    @DisplayName("TC-1.2-02 [Luồng chính – Trang chủ \"Điều chỉnh\"] Khu vực, giai đoạn và chủ đề quan tâm được lưu cùng một lần, báo \"Đã lưu.\"")
    void saveEverythingFromTheHomeCard() throws Exception {
        String t = token();
        long area = newArea("Khu " + t, true);
        long me = member();
        long housing = topic("Nhà ở");
        long japanese = topic("Học tiếng Nhật");

        JsonNode body = body(putAs(me, prefs(area, "PRE", List.of(japanese, housing)), "/api/me/feed-preferences")
                .andExpect(status().isOk()));

        assertThat(body.get("message").asText()).isEqualTo("Đã lưu.");
        JsonNode data = body.get("data");
        assertThat(data.get("areaId").asLong()).isEqualTo(area);
        assertThat(data.get("areaName").asText()).isEqualTo("Khu " + t);
        assertThat(data.get("stage").asText()).isEqualTo("PRE");
        assertThat(longs(data.get("topicIds"))).containsExactly(housing, japanese);   // the Blog's topic order
        assertThat(storedArea(me)).isEqualTo(area);
        assertThat(storedStage(me)).isEqualTo("PRE");
        assertThat(storedTopics(me)).containsExactlyInAnyOrder(housing, japanese);
    }

    @Test
    @DisplayName("TC-1.2-03 [Luồng chính – bước 2] Mở lại trang Tôi: khu vực và giai đoạn hiện tại được chọn sẵn")
    void profileShowsTheCurrentValues() throws Exception {
        String t = token();
        long area = newArea("Khu " + t, true);
        long me = member();
        putAs(me, prefs(area, "LEAVE"), "/api/me/profile").andExpect(status().isOk());

        JsonNode profile = data(getAs(me, "/api/me/profile").andExpect(status().isOk()));

        assertThat(profile.get("areaId").asLong()).isEqualTo(area);
        assertThat(profile.get("areaName").asText()).isEqualTo("Khu " + t);
        assertThat(profile.get("stage").asText()).isEqualTo("LEAVE");
    }

    @Test
    @DisplayName("TC-1.2-04 [Luồng chính – bước 2] Danh sách \"Khu vực sinh sống\" chỉ có khu vực đang bật")
    void onlyActiveAreasAreOffered() throws Exception {
        String t = token();
        long active = newArea("Bật " + t, true);
        long off = newArea("Tắt " + t, false);

        JsonNode areas = data(getAs(null, "/api/areas/prefectures/47").andExpect(status().isOk()));

        List<Long> ids = new ArrayList<>();
        areas.forEach(a -> ids.add(a.get("id").asLong()));
        assertThat(ids).contains(active).doesNotContain(off);
    }

    @Test
    @DisplayName("TC-1.2-05 [Luồng chính – bước 4] Bốn giai đoạn \"Sắp sang Nhật\", \"Mới đến\", \"Sắp về nước\", \"Đã về nước\" (PRE, ARR, LEAVE, HOME) đều hợp lệ")
    void theFourStagesAreAccepted() throws Exception {
        long me = member();
        for (String stage : List.of("PRE", "ARR", "LEAVE", "HOME")) {
            putAs(me, prefs(null, stage), "/api/me/profile").andExpect(status().isOk());
            assertThat(storedStage(me)).isEqualTo(stage);
            putAs(me, prefs(null, stage, null), "/api/me/feed-preferences").andExpect(status().isOk());
            assertThat(storedStage(me)).isEqualTo(stage);
        }
    }

    @Test
    @DisplayName("TC-1.2-06 [Hậu điều kiện] Sau khi lưu, Trang chủ xếp bài gần khu vực và hợp giai đoạn lên trước, \"Việc tiếp theo\" theo giai đoạn mới")
    void homeFollowsTheNewPreferences() throws Exception {
        String t = token();
        long area = newArea("Khu " + t, true);
        long place = placeIn(area, "Quán " + t);
        long me = member();
        long author = member();
        long near = post(author, "Gần " + t, "Mua sắm", hoursAgo(3));
        link(near, place);
        long stage = post(author, "Giai đoạn " + t, "Thủ tục giấy tờ", hoursAgo(2));   // a PRE topic
        long plain = post(author, "Khác " + t, "Mua sắm", hoursAgo(1));
        assertThat(ids(forYou(me, t).get("items"))).containsExactly(plain, stage, near);  // before: newest first

        putAs(me, prefs(area, "PRE", null), "/api/me/feed-preferences").andExpect(status().isOk());

        JsonNode items = forYou(me, t).get("items");
        assertThat(ids(items)).containsExactly(near, stage, plain);
        assertThat(byId(items, near).get("reason").asText()).isEqualTo(REASON_AREA);
        assertThat(byId(items, stage).get("reason").asText()).isEqualTo(REASON_STAGE);
        JsonNode home = home(me);
        assertThat(ids(home.get("forYou"))).first().isEqualTo(near);
        assertThat(home.get("stage").asText()).isEqualTo("PRE");
        assertThat(home.get("areaId").asLong()).isEqualTo(area);
        assertThat(home.get("guide").get("stageCode").asText()).isEqualTo("PRE");
    }

    @Test
    @DisplayName("TC-1.2-07 [Luồng thay thế 3b] Chọn \"Chưa chọn\": không lưu giá trị nào, bảng tin thôi dùng khu vực và giai đoạn")
    void clearingThePreferences() throws Exception {
        String t = token();
        long area = newArea("Khu " + t, true);
        long place = placeIn(area, "Quán " + t);
        long me = member();
        setAreaAndStage(me, area, "PRE");
        long author = member();
        long near = post(author, "Gần " + t, "Thủ tục giấy tờ", hoursAgo(2));
        link(near, place);

        JsonNode data = data(putAs(me, prefs(null, null), "/api/me/profile").andExpect(status().isOk()));

        assertThat(data.get("areaId").isNull()).isTrue();
        assertThat(data.get("stage").isNull()).isTrue();
        assertThat(storedArea(me)).isNull();
        assertThat(storedStage(me)).isNull();
        JsonNode post = forYou(me, t).get("items").get(0);
        assertThat(post.get("reasons")).isEmpty();
        assertThat(home(me).get("guide").get("stageCode").asText()).isEqualTo("ARR");      // default until chosen
    }

    @Test
    @DisplayName("TC-1.2-08 [Luồng thay thế 4a] Giai đoạn ngoài 4 giá trị: từ chối \"Giai đoạn không hợp lệ\", không lưu gì")
    void unknownStageIsRefused() throws Exception {
        String t = token();
        long area = newArea("Khu " + t, true);
        long other = newArea("Khu khác " + t, true);
        long me = member();
        setAreaAndStage(me, area, "ARR");

        putAs(me, prefs(other, "SOON"), "/api/me/profile")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value(INVALID_STAGE))
                .andExpect(jsonPath("$.data.stage").value(INVALID_STAGE));
        putAs(me, prefs(other, "arr", List.of()), "/api/me/feed-preferences")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(INVALID_STAGE));

        assertThat(storedArea(me)).isEqualTo(area);
        assertThat(storedStage(me)).isEqualTo("ARR");
    }

    @Test
    @DisplayName("TC-1.2-09 [Luồng thay thế 4a] Khu vực không tồn tại: từ chối \"Khu vực không hợp lệ\", không lưu gì")
    void unknownAreaIsRefused() throws Exception {
        long me = member();
        long missing = jdbc.queryForObject("SELECT MAX(id) + 1000 FROM areas", Long.class);

        putAs(me, prefs(missing, "ARR"), "/api/me/profile")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(INVALID_AREA));
        putAs(me, prefs(missing, "ARR", null), "/api/me/feed-preferences")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(INVALID_AREA));

        assertThat(storedArea(me)).isNull();
        assertThat(storedStage(me)).isNull();
    }

    @Test
    @DisplayName("TC-1.2-10 [Luồng thay thế 4a] Khu vực đã bị tắt: từ chối \"Khu vực không hợp lệ\", không lưu gì")
    void switchedOffAreaIsRefused() throws Exception {
        String t = token();
        long off = newArea("Tắt " + t, false);
        long me = member();

        putAs(me, prefs(off, "HOME"), "/api/me/profile")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(INVALID_AREA));
        putAs(me, prefs(off, "HOME", List.of(topic("Nhà ở"))), "/api/me/feed-preferences")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(INVALID_AREA));

        assertThat(storedArea(me)).isNull();
        assertThat(storedStage(me)).isNull();
        assertThat(storedTopics(me)).isEmpty();
    }

    @Test
    @DisplayName("TC-1.2-11 [Quyết định sau SRS – CLAUDE.md] \"Điều chỉnh\" lưu tất cả hoặc không lưu gì: một chủ đề không hợp lệ thì khu vực và giai đoạn cũng không đổi")
    void homeCardSavesAllOrNothing() throws Exception {
        String t = token();
        long area = newArea("Khu " + t, true);
        long other = newArea("Khu khác " + t, true);
        long me = member();
        setAreaAndStage(me, area, "ARR");
        long housing = topic("Nhà ở");
        putAs(me, Map.of("topicIds", List.of(housing)), "/api/me/interests").andExpect(status().isOk());

        // "Khác" is never offered as an interest
        putAs(me, prefs(other, "HOME", List.of(topic("Khác"))), "/api/me/feed-preferences")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Có chủ đề không còn để chọn. Hãy tải lại trang rồi chọn lại."));

        assertThat(storedArea(me)).isEqualTo(area);
        assertThat(storedStage(me)).isEqualTo("ARR");
        assertThat(storedTopics(me)).containsExactly(housing);
    }

    @Test
    @DisplayName("TC-1.2-12 [Quyết định sau SRS – CLAUDE.md] topicIds = null giữ nguyên chủ đề quan tâm, danh sách rỗng thì bỏ hết")
    void topicsKeptWhenNullAndClearedWhenEmpty() throws Exception {
        long me = member();
        long housing = topic("Nhà ở");
        putAs(me, prefs(null, "ARR", List.of(housing)), "/api/me/feed-preferences").andExpect(status().isOk());

        JsonNode kept = data(putAs(me, prefs(null, "LEAVE", null), "/api/me/feed-preferences")
                .andExpect(status().isOk()));
        assertThat(longs(kept.get("topicIds"))).containsExactly(housing);
        assertThat(storedTopics(me)).containsExactly(housing);

        JsonNode cleared = data(putAs(me, prefs(null, "LEAVE", List.of()), "/api/me/feed-preferences")
                .andExpect(status().isOk()));
        assertThat(cleared.get("topicIds")).isEmpty();
        assertThat(storedTopics(me)).isEmpty();
    }

    @Test
    @DisplayName("TC-1.2-13 [Biên – CLAUDE.md] Chọn đúng 5 chủ đề được lưu; 6 chủ đề bị từ chối \"Bạn chọn được tối đa 5 chủ đề.\"")
    void atMostFiveInterestTopics() throws Exception {
        long me = member();
        List<Long> six = jdbc.queryForList("""
                SELECT id FROM blog_topics WHERE is_active AND NOT is_other ORDER BY sort_order LIMIT 6""", Long.class);

        putAs(me, prefs(null, "ARR", six.subList(0, 5)), "/api/me/feed-preferences").andExpect(status().isOk());
        assertThat(storedTopics(me)).hasSize(5);

        putAs(me, prefs(null, "HOME", six), "/api/me/feed-preferences")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Bạn chọn được tối đa 5 chủ đề."));
        assertThat(storedTopics(me)).containsExactlyInAnyOrderElementsOf(six.subList(0, 5));
        assertThat(storedStage(me)).isEqualTo("ARR");
    }

    @Test
    @DisplayName("TC-1.2-14 [Luồng thay thế 5a] Phiên đăng nhập đã hết (Khách): 401, không lưu gì")
    void guestCannotSave() throws Exception {
        String t = token();
        long area = newArea("Khu " + t, true);

        putAs(null, prefs(area, "ARR"), "/api/me/profile").andExpect(status().isUnauthorized());
        putAs(null, prefs(area, "ARR", List.of()), "/api/me/feed-preferences").andExpect(status().isUnauthorized());

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM users WHERE area_id = ?", Integer.class, area)).isZero();
    }

    @Test
    @DisplayName("TC-1.2-15 [Luồng thay thế 5a] Tài khoản bị khóa trong lúc đang mở trang: phiên kết thúc (401), không lưu gì")
    void lockedAccountCannotSave() throws Exception {
        String t = token();
        long area = newArea("Khu " + t, true);
        long me = member();
        setStatus(me, "LOCKED");

        putAs(me, prefs(area, "ARR", null), "/api/me/feed-preferences").andExpect(status().isUnauthorized());

        assertThat(storedArea(me)).isNull();
        assertThat(storedStage(me)).isNull();
    }

    @Test
    @DisplayName("TC-1.2-16 [Phân quyền – CLAUDE.md] Tài khoản nhân viên không chọn được chủ đề quan tâm (MSG18), không lưu gì")
    void staffCannotChooseInterests() throws Exception {
        String t = token();
        long area = newArea("Khu " + t, true);
        long moderator = account("MODERATOR", 30);

        putAs(moderator, prefs(area, "ARR", List.of(topic("Nhà ở"))), "/api/me/feed-preferences")
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value(MSG18));

        assertThat(storedArea(moderator)).isNull();
        assertThat(storedTopics(moderator)).isEmpty();
    }

    // ---------------------------------------------------------------- helpers

    /** Body of PUT /api/me/profile; nulls are sent as JSON null ("Chưa chọn"). */
    private static Map<String, Object> prefs(Long areaId, String stage) {
        Map<String, Object> body = new HashMap<>();
        body.put("areaId", areaId);
        body.put("stage", stage);
        return body;
    }

    /** Body of PUT /api/me/feed-preferences; {@code topicIds} null = keep the topics. */
    private static Map<String, Object> prefs(Long areaId, String stage, List<Long> topicIds) {
        Map<String, Object> body = prefs(areaId, stage);
        body.put("topicIds", topicIds);
        return body;
    }

    private Long storedArea(long userId) {
        return jdbc.queryForObject("SELECT area_id FROM users WHERE id = ?", Long.class, userId);
    }

    private String storedStage(long userId) {
        return jdbc.queryForObject("SELECT stage FROM users WHERE id = ?", String.class, userId);
    }

    private List<Long> storedTopics(long userId) {
        return jdbc.queryForList("SELECT topic_id FROM user_interest_topics WHERE user_id = ?", Long.class, userId);
    }

    private static List<Long> longs(JsonNode array) {
        List<Long> out = new ArrayList<>();
        array.forEach(n -> out.add(n.asLong()));
        return out;
    }
}
