package com.sep490.vsa.usecase.feed;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * UC 1.1 Browse Personalized Feed – SRS Report 3, II.1.1 (normal flow 1–6, alternative flows 2a, 4a, 5a) and the
 * later team decisions in CLAUDE.md ("Personalized feed = the member Home": posts already liked, saved or commented on
 * only fill the list; same prefecture after the area; "Chủ đề quan tâm" between the area and the stage; the page
 * "Dành cho bạn" with its search, scopes and paging).
 * <p>
 * API: {@code GET /api/me/home} (card "Dành cho bạn", 5 posts) and {@code GET /api/me/for-you} (the whole ranked feed,
 * same SQL). Guests read {@code GET /api/home}.
 */
@DisplayName("UC 1.1 – Browse Personalized Feed (Duyệt bảng tin cá nhân hóa)")
class BrowsePersonalizedFeedTest extends FeedTestSupport {

    @Test
    @DisplayName("TC-1.1-01 [Luồng chính] Thành viên mở Trang chủ: thẻ \"Dành cho bạn\" hiện bài hợp khu vực kèm đủ thông tin và các thẻ khác của Trang chủ")
    void memberHomeShowsPersonalFeedWithEveryCard() throws Exception {
        String t = token();
        long area = newArea("Khu " + t, true);
        long place = placeIn(area, "Quán " + t);
        long viewer = member();
        setAreaAndStage(viewer, area, "ARR");
        long author = member();
        String longContent = ("Kinh nghiệm thuê nhà ở Naha " + t + " ").repeat(10);
        long postId = insertBlog(author, "POST", "Bài " + t, longContent, "PUBLISHED", hoursAgo(1), topic("Mua sắm"));
        link(postId, place);
        score(postId, 3, 2);

        JsonNode home = home(viewer);

        // Step 5: up to 5 posts, the area post first with title, excerpt, topic, badge, author, time, likes, comments
        JsonNode forYou = home.get("forYou");
        assertThat(forYou.size()).isBetween(1, 5);
        JsonNode first = forYou.get(0);
        assertThat(first.get("id").asLong()).isEqualTo(postId);
        assertThat(first.get("title").asText()).isEqualTo("Bài " + t);
        String excerpt = first.get("excerpt").asText();                                  // at most 140 characters + "…"
        assertThat(excerpt).startsWith("Kinh nghiệm thuê nhà ở Naha " + t).endsWith("…");
        assertThat(excerpt.length()).isLessThanOrEqualTo(141);
        assertThat(first.get("topicName").asText()).isEqualTo("Mua sắm");
        assertThat(first.get("reason").asText()).isEqualTo(REASON_AREA);
        assertThat(first.get("authorName").asText()).isEqualTo(displayName(author));
        assertThat(first.get("likeCount").asInt()).isEqualTo(3);
        assertThat(first.get("commentCount").asInt()).isEqualTo(2);
        assertThat(first.get("createdAt").isNull()).isFalse();
        assertThat(first.get("engaged").asBoolean()).isFalse();
        // The other cards of the member Home: greeting with stage, points and rank, phrase, next guide, events,
        // reviews and featured post
        assertThat(home.get("displayName").asText()).isEqualTo(displayName(viewer));
        assertThat(home.get("stage").asText()).isEqualTo("ARR");
        assertThat(home.get("areaName").asText()).isEqualTo("Khu " + t);
        assertThat(home.get("points").asInt()).isZero();
        assertThat(home.get("rankName").asText()).isEqualTo("Người mới");
        assertThat(home.get("guide").get("stageCode").asText()).isEqualTo("ARR");
        assertThat(home.get("events").isArray()).isTrue();
        assertThat(home.get("reviews").isArray()).isTrue();
        assertThat(home.has("phrase")).isTrue();
        assertThat(home.has("featured")).isTrue();
    }

    @Test
    @DisplayName("TC-1.1-02 [Luồng chính – bước 3] Chỉ bài viết đã đăng của thành viên khác đang hoạt động: bỏ bài của chính mình, câu hỏi, bài chưa đăng/bị ẩn/bị gỡ và bài của tài khoản bị khóa/đã xóa")
    void onlyPublishedPostsOfOtherActiveMembers() throws Exception {
        String t = token();
        long area = newArea("Khu " + t, true);
        long place = placeIn(area, "Quán " + t);
        long viewer = member();
        setAreaAndStage(viewer, area, null);
        long author = member();
        long locked = member();
        long deleted = member();

        long shown = post(author, "Hiện " + t, "Mua sắm", hoursAgo(5));
        long own = post(viewer, "Của tôi " + t, "Mua sắm", hoursAgo(1));
        long question = insertBlog(author, "QA", "Hỏi " + t, "Câu hỏi", "PUBLISHED", hoursAgo(1), topic("Mua sắm"));
        long processing = insertBlog(author, "POST", "Quét " + t, "x", "PROCESSING", hoursAgo(1), topic("Mua sắm"));
        long hidden = insertBlog(author, "POST", "Ẩn " + t, "x", "HIDDEN", hoursAgo(1), topic("Mua sắm"));
        long rejected = insertBlog(author, "POST", "Từ chối " + t, "x", "REJECTED", hoursAgo(1), topic("Mua sắm"));
        long removed = insertBlog(author, "POST", "Gỡ " + t, "x", "REMOVED", hoursAgo(1), topic("Mua sắm"));
        long ofLocked = post(locked, "Khóa " + t, "Mua sắm", hoursAgo(1));
        long ofDeleted = post(deleted, "Xóa " + t, "Mua sắm", hoursAgo(1));
        List<Long> neverShown = List.of(own, question, processing, hidden, rejected, removed, ofLocked, ofDeleted);
        for (long id : neverShown) {
            link(id, place);
            score(id, 1000, 1000);          // they would lead the card if they could be shown
        }
        link(shown, place);
        setStatus(locked, "LOCKED");
        setStatus(deleted, "DELETED");

        JsonNode card = home(viewer).get("forYou");
        assertThat(ids(card)).first().isEqualTo(shown);
        assertThat(ids(card)).doesNotContainAnyElementsOf(neverShown);

        JsonNode all = forYou(viewer, t);
        assertThat(ids(all.get("items"))).containsExactly(shown);
        assertThat(scopeCount(all, "ALL")).isEqualTo(1);
    }

    @Test
    @DisplayName("TC-1.1-03 [Luồng chính – bước 4] Bài trong 30 ngày gần đây xếp trước, bài cũ hơn chỉ lấp chỗ trống (kể cả bài cũ gần khu vực)")
    void postsOfTheLast30DaysComeFirst() throws Exception {
        String t = token();
        long area = newArea("Khu " + t, true);
        long place = placeIn(area, "Quán " + t);
        long viewer = member();
        setAreaAndStage(viewer, area, "ARR");
        long author = member();
        long oldNear = post(author, "Cũ gần " + t, "Nhà ở", daysAgo(31));
        link(oldNear, place);
        score(oldNear, 500, 500);
        long recentPlain = post(author, "Mới " + t, "Mua sắm", daysAgo(29));

        JsonNode items = forYou(viewer, t).get("items");

        assertThat(ids(items)).containsExactly(recentPlain, oldNear);
    }

    @Test
    @DisplayName("TC-1.1-04 [Luồng chính – bước 4] Trong nhóm bài mới: gần khu vực → hợp giai đoạn → lượt thích + 2 × bình luận → mới nhất")
    void areaThenStageThenScoreThenNewest() throws Exception {
        String t = token();
        long area = newArea("Khu " + t, true);
        long place = placeIn(area, "Quán " + t);
        long viewer = member();
        setAreaAndStage(viewer, area, "ARR");
        long author = member();
        long near = post(author, "A " + t, "Mua sắm", hoursAgo(6));
        link(near, place);
        long stage = post(author, "B " + t, "Nhà ở", hoursAgo(5));                  // "Nhà ở" suits ARR
        long moreComments = post(author, "C " + t, "Mua sắm", hoursAgo(4));
        score(moreComments, 0, 3);                                                       // 0 + 2 × 3 = 6
        long moreLikes = post(author, "D " + t, "Mua sắm", hoursAgo(2));
        score(moreLikes, 5, 0);                                                          // 5
        long newest = post(author, "E " + t, "Mua sắm", hoursAgo(1));
        long older = post(author, "F " + t, "Mua sắm", hoursAgo(3));

        JsonNode items = forYou(viewer, t).get("items");

        assertThat(ids(items)).containsExactly(near, stage, moreComments, moreLikes, newest, older);
        // The badge of each reason, with what matched
        assertThat(byId(items, near).get("reason").asText()).isEqualTo(REASON_AREA);
        assertThat(byId(items, near).get("reasons").get(0).get("detail").asText()).isEqualTo("Quán " + t);
        assertThat(byId(items, stage).get("reason").asText()).isEqualTo(REASON_STAGE);
        assertThat(byId(items, stage).get("reasons").get(0).get("detail").asText()).isEqualTo("Nhà ở");
        assertThat(byId(items, moreComments).get("reason").isNull()).isTrue();
        assertThat(byId(items, moreComments).get("reasons")).isEmpty();
    }

    @Test
    @DisplayName("TC-1.1-05 [Luồng chính – bước 5] Bài vừa gần khu vực vừa hợp giai đoạn mang cả hai lý do, huy hiệu đầu tiên là \"Gần khu vực của bạn\"")
    void postMatchingAreaAndStageCarriesBothReasons() throws Exception {
        String t = token();
        long area = newArea("Khu " + t, true);
        long place = placeIn(area, "Quán " + t);
        long viewer = member();
        setAreaAndStage(viewer, area, "LEAVE");
        long author = member();
        long both = post(author, "Cả hai " + t, "Visa và cư trú", hoursAgo(2));       // a LEAVE topic
        link(both, place);

        JsonNode post = forYou(viewer, t).get("items").get(0);

        assertThat(post.get("reason").asText()).isEqualTo(REASON_AREA);
        assertThat(reasonCodes(post)).containsExactly("AREA", "STAGE");
        assertThat(post.get("reasons").get(1).get("label").asText()).isEqualTo(REASON_STAGE);
        assertThat(post.get("reasons").get(1).get("detail").asText()).isEqualTo("Visa và cư trú");
    }

    @Test
    @DisplayName("TC-1.1-06 [Luồng thay thế 4a] Chưa chọn khu vực và giai đoạn: bỏ qua bước xếp hạng đó, không có huy hiệu, chỉ có phạm vi \"Tất cả\"")
    void withoutAreaAndStageNoBadgeAndScoreOrder() throws Exception {
        String t = token();
        long otherArea = newArea("Khu " + t, true);
        long place = placeIn(otherArea, "Quán " + t);
        long viewer = member();                                                          // no area, no stage
        long author = member();
        long linked = post(author, "Gắn nơi " + t, "Nhà ở", hoursAgo(3));
        link(linked, place);
        long popular = post(author, "Nhiều thích " + t, "Mua sắm", hoursAgo(5));
        score(popular, 4, 0);
        long newest = post(author, "Mới nhất " + t, "Kinh nghiệm", hoursAgo(1));

        JsonNode feed = forYou(viewer, t);

        assertThat(ids(feed.get("items"))).containsExactly(popular, newest, linked);
        feed.get("items").forEach(p -> {
            assertThat(p.get("reason").isNull()).isTrue();
            assertThat(p.get("reasons")).isEmpty();
        });
        assertThat(scopeCodes(feed)).containsExactly("ALL");
        assertThat(feed.get("areaName").isNull()).isTrue();
        assertThat(feed.get("stage").isNull()).isTrue();
        // The member Home says the same: no area and no stage, so the page keeps the card "Chọn khu vực và giai đoạn"
        JsonNode home = home(viewer);
        assertThat(home.get("areaId").isNull()).isTrue();
        assertThat(home.get("stage").isNull()).isTrue();
    }

    @Test
    @DisplayName("TC-1.1-07 [Quyết định sau SRS – CLAUDE.md] Cùng tỉnh xếp sau cùng khu vực và trước bài khác, huy hiệu \"Cùng tỉnh với bạn\"")
    void samePrefectureAfterSameArea() throws Exception {
        String t = token();
        long area = newArea("Khu " + t, true);
        long neighbour = newArea("Khu bên " + t, true);                                 // same prefecture (47)
        long place = placeIn(area, "Quán " + t);
        long place2 = placeIn(neighbour, "Chợ " + t);
        long viewer = member();
        setAreaAndStage(viewer, area, null);
        long author = member();
        long inPrefecture = post(author, "Tỉnh " + t, "Mua sắm", hoursAgo(1));
        link(inPrefecture, place2);
        long inArea = post(author, "Khu " + t, "Mua sắm", hoursAgo(3));
        link(inArea, place);
        long elsewhere = post(author, "Nơi khác " + t, "Mua sắm", hoursAgo(1));
        score(elsewhere, 900, 900);

        JsonNode items = forYou(viewer, t).get("items");

        assertThat(ids(items)).containsExactly(inArea, inPrefecture, elsewhere);
        JsonNode prefecture = byId(items, inPrefecture);
        assertThat(prefecture.get("reason").asText()).isEqualTo(REASON_PREFECTURE);
        assertThat(prefecture.get("reasons").get(0).get("detail").asText()).isEqualTo("Chợ " + t);
    }

    @Test
    @DisplayName("TC-1.1-08 [Quyết định sau SRS – CLAUDE.md] \"Chủ đề quan tâm\" đã chọn xếp sau khu vực và trước giai đoạn")
    void chosenInterestBetweenAreaAndStage() throws Exception {
        String t = token();
        long area = newArea("Khu " + t, true);
        long place = placeIn(area, "Quán " + t);
        long viewer = member();
        setAreaAndStage(viewer, area, "ARR");
        putAs(viewer, Map.of("topicIds", List.of(topic("Học tiếng Nhật"))), "/api/me/interests")
                .andExpect(status().isOk());
        long author = member();
        long stage = post(author, "Giai đoạn " + t, "Nhà ở", hoursAgo(1));
        long interest = post(author, "Quan tâm " + t, "Học tiếng Nhật", hoursAgo(2));
        long near = post(author, "Gần " + t, "Mua sắm", hoursAgo(3));
        link(near, place);

        JsonNode feed = forYou(viewer, t);
        JsonNode items = feed.get("items");

        assertThat(ids(items)).containsExactly(near, interest, stage);
        assertThat(byId(items, interest).get("reason").asText()).isEqualTo(REASON_INTEREST);
        assertThat(byId(items, interest).get("reasons").get(0).get("detail").asText()).isEqualTo("Học tiếng Nhật");
        assertThat(feed.get("chosenInterests").toString()).contains("Học tiếng Nhật");
        assertThat(scopeCodes(feed)).containsExactly("ALL", "NEAR", "STAGE", "INTEREST");
    }

    @Test
    @DisplayName("TC-1.1-09 [Quyết định sau SRS – CLAUDE.md] Bài đã thích, đã lưu hoặc đã bình luận chỉ lấp chỗ trống ở cuối và được đánh dấu engaged")
    void engagedPostsOnlyFillTheList() throws Exception {
        String t = token();
        long viewer = member();
        long author = member();
        long liked = post(author, "Đã thích " + t, "Khác", hoursAgo(1));
        score(liked, 999, 0);
        like(liked, viewer);
        long saved = post(author, "Đã lưu " + t, "Khác", hoursAgo(1));
        score(saved, 999, 0);
        jdbc.update("INSERT INTO saved_blogs (blog_id, user_id) VALUES (?, ?)", saved, viewer);
        long fresh = post(author, "Chưa xem " + t, "Khác", hoursAgo(20));

        JsonNode items = forYou(viewer, t).get("items");

        assertThat(ids(items)).first().isEqualTo(fresh);
        assertThat(ids(items)).containsExactlyInAnyOrder(fresh, liked, saved);
        assertThat(byId(items, fresh).get("engaged").asBoolean()).isFalse();
        assertThat(byId(items, liked).get("engaged").asBoolean()).isTrue();
        assertThat(byId(items, saved).get("engaged").asBoolean()).isTrue();
    }

    @Test
    @DisplayName("TC-1.1-10 [Biên] Thẻ \"Dành cho bạn\" trên Trang chủ hiện tối đa 5 bài dù có 6 bài phù hợp")
    void homeCardShowsAtMostFivePosts() throws Exception {
        String t = token();
        long area = newArea("Khu " + t, true);
        long place = placeIn(area, "Quán " + t);
        long viewer = member();
        setAreaAndStage(viewer, area, null);
        long author = member();
        List<Long> mine = new java.util.ArrayList<>();
        for (int i = 1; i <= 6; i++) {
            long id = post(author, "Bài " + i + " " + t, "Mua sắm", hoursAgo(i));
            link(id, place);
            mine.add(id);
        }

        JsonNode card = home(viewer).get("forYou");

        // Same score: newest first, so the oldest of the six is the one left out
        assertThat(ids(card)).containsExactlyElementsOf(mine.subList(0, 5));
        assertThat(forYou(viewer, t).get("items")).hasSize(6);
    }

    @Test
    @DisplayName("TC-1.1-11 [Luồng thay thế 5a] Không có bài phù hợp: danh sách rỗng (trang hiện \"Chưa có bài viết phù hợp. Hãy xem thêm ở Blog.\")")
    void noMatchingPostsGivesAnEmptyList() throws Exception {
        long viewer = member();

        JsonNode feed = forYou(viewer, token());

        assertThat(feed.get("items")).isEmpty();
        assertThat(feed.get("hasMore").asBoolean()).isFalse();
        assertThat(scopeCount(feed, "ALL")).isZero();
    }

    @Test
    @DisplayName("TC-1.1-12 [Luồng thay thế 2a] Khách: không có bảng tin cá nhân (401), Trang chủ khách vẫn hiện số liệu và câu tiếng Nhật hôm nay")
    void guestGetsTheGuestHomeOnly() throws Exception {
        getAs(null, "/api/me/home").andExpect(status().isUnauthorized());
        getAs(null, "/api/me/for-you").andExpect(status().isUnauthorized());

        JsonNode guest = data(getAs(null, "/api/home")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true)));
        assertThat(guest.has("stats")).isTrue();
        assertThat(guest.get("stats").has("reviews")).isTrue();
        assertThat(guest.has("quotes")).isTrue();
        assertThat(guest.has("phrase")).isTrue();
        assertThat(guest.has("forYou")).isFalse();
    }

    @Test
    @DisplayName("TC-1.1-13 [Luồng thay thế 2a] Tài khoản bị khóa hoặc đã xóa bị đăng xuất và không đọc được bảng tin (401)")
    void lockedOrDeletedAccountIsSignedOut() throws Exception {
        long locked = member();
        long deleted = member();
        setStatus(locked, "LOCKED");
        setStatus(deleted, "DELETED");

        getAs(locked, "/api/me/home").andExpect(status().isUnauthorized());
        getAs(deleted, "/api/me/home").andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("TC-1.1-14 [Hậu điều kiện] Xem bảng tin không thay đổi dữ liệu (lượt thích, bình luận, lưu không đổi)")
    void browsingChangesNoData() throws Exception {
        String t = token();
        long viewer = member();
        long author = member();
        long id = post(author, "Bài " + t, "Mua sắm", hoursAgo(1));
        score(id, 7, 3);

        home(viewer);
        forYou(viewer, t);

        Map<String, Object> row = jdbc.queryForMap("SELECT like_count, comment_count FROM blogs WHERE id = ?", id);
        assertThat(((Number) row.get("like_count")).intValue()).isEqualTo(7);
        assertThat(((Number) row.get("comment_count")).intValue()).isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM blog_likes WHERE user_id = ?", Integer.class, viewer)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM saved_blogs WHERE user_id = ?", Integer.class, viewer)).isZero();
    }

    @Test
    @DisplayName("TC-1.1-15 [Quyết định sau SRS – CLAUDE.md] Trang \"Dành cho bạn\": 10 bài một trang, \"Xem thêm\" khi còn bài")
    void forYouPageHasTenPostsAPage() throws Exception {
        String t = token();
        long viewer = member();
        long author = member();
        for (int i = 1; i <= 11; i++) {
            post(author, "Bài " + i + " " + t, "Mua sắm", hoursAgo(i));
        }

        JsonNode first = forYou(viewer, t, "ALL", 0);
        JsonNode second = forYou(viewer, t, "ALL", 1);

        assertThat(first.get("items")).hasSize(10);
        assertThat(first.get("hasMore").asBoolean()).isTrue();
        assertThat(second.get("items")).hasSize(1);
        assertThat(second.get("hasMore").asBoolean()).isFalse();
        assertThat(second.get("page").asInt()).isEqualTo(1);
        assertThat(scopeCount(first, "ALL")).isEqualTo(11);
    }

    @Test
    @DisplayName("TC-1.1-16 [Quyết định sau SRS – CLAUDE.md] Phạm vi \"Gần bạn\" chỉ giữ bài gần khu vực/tỉnh, kèm số bài của từng phạm vi")
    void nearScopeKeepsOnlyNearPosts() throws Exception {
        String t = token();
        long area = newArea("Khu " + t, true);
        long place = placeIn(area, "Quán " + t);
        long viewer = member();
        setAreaAndStage(viewer, area, "ARR");
        long author = member();
        long near = post(author, "Gần " + t, "Mua sắm", hoursAgo(2));
        link(near, place);
        long stage = post(author, "Giai đoạn " + t, "Nhà ở", hoursAgo(1));

        JsonNode feed = forYou(viewer, t, "NEAR", 0);

        assertThat(feed.get("scope").asText()).isEqualTo("NEAR");
        assertThat(ids(feed.get("items"))).containsExactly(near);
        assertThat(scopeCount(feed, "ALL")).isEqualTo(2);
        assertThat(scopeCount(feed, "NEAR")).isEqualTo(1);
        assertThat(scopeCount(feed, "STAGE")).isEqualTo(1);
        // A scope this member cannot use falls back to "Tất cả"
        JsonNode interest = forYou(viewer, t, "INTEREST", 0);
        assertThat(interest.get("scope").asText()).isEqualTo("ALL");
        assertThat(ids(interest.get("items"))).containsExactlyInAnyOrder(near, stage);
    }

    @Test
    @DisplayName("TC-1.1-17 [Biên] Từ khóa \"Tìm trong bài dành cho bạn\": 100 ký tự được chấp nhận, 101 ký tự bị từ chối")
    void searchKeywordBoundary() throws Exception {
        long viewer = member();

        getAs(viewer, "/api/me/for-you?q={q}", chars(100)).andExpect(status().isOk());
        getAs(viewer, "/api/me/for-you?q={q}", chars(101))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Từ khóa tìm kiếm tối đa 100 ký tự."));
    }
}
