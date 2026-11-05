package com.sep490.vsa.feature.home;

import com.jayway.jsonpath.JsonPath;
import com.sep490.vsa.common.security.AppUserPrincipal;
import com.sep490.vsa.feature.home.HomeDtos.GuideNext;
import com.sep490.vsa.feature.home.HomeDtos.ReviewItem;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.web.context.HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.forwardedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * "Review mới quanh …" on the member Home and the page "Review mới" ({@code GET /api/home/reviews}), end to end
 * against MySQL ({@code asvsj_test}, like HomeFeedFlowTest). Each test makes its own area in Okinawa, where no other
 * test puts places, and dates its reviews ten days ahead, so they lead every newest-first list of the shared database.
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:mysql://localhost:3306/asvsj_test?createDatabaseIfNotExist=true&useUnicode=true&characterEncoding=utf8&connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true",
        "app.media.local-dir=target/test-uploads",
        "app.moderation.retry-initial-delay-ms=3600000",
        "app.places.osm.enabled=false"
})
@AutoConfigureMockMvc
@ActiveProfiles("local")
class HomeReviewsFlowTest {

    @Autowired
    private MockMvc mvc;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private HomeService home;

    private final List<Long> users = new ArrayList<>();
    private final List<Long> places = new ArrayList<>();
    private final List<Long> media = new ArrayList<>();
    private String word;
    private long area;
    private long naha;              // a real area of the same prefecture (Okinawa)
    private long viewer;
    private long author;
    private Instant base;           // ten days ahead

    @BeforeEach
    void setUp() {
        word = UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        jdbc.update("""
                INSERT INTO areas (prefecture_code, ward, station, display_name, is_active)
                VALUES ('47', 'Test', 'Test', ?, TRUE)""", "Review test – " + word);
        area = jdbc.queryForObject("SELECT id FROM areas WHERE display_name = ?", Long.class, "Review test – " + word);
        naha = jdbc.queryForObject("SELECT id FROM areas WHERE code = '472018'", Long.class);
        viewer = user("ACTIVE");
        author = user("ACTIVE");
        jdbc.update("UPDATE users SET area_id = ?, stage = 'ARR' WHERE id = ?", area, viewer);
        base = Instant.now().plus(Duration.ofDays(10)).truncatedTo(ChronoUnit.SECONDS);   // DATETIME keeps seconds
    }

    @AfterEach
    void cleanUp() {
        for (long id : users) {
            jdbc.update("DELETE FROM reviews WHERE author_id = ?", id);
        }
        for (long id : media) {
            jdbc.update("DELETE FROM media WHERE id = ?", id);
        }
        for (long id : places) {
            jdbc.update("DELETE FROM places WHERE id = ?", id);
        }
        for (long id : users) {
            jdbc.update("DELETE FROM user_tags WHERE user_id = ?", id);
            jdbc.update("DELETE FROM users WHERE id = ?", id);
        }
        jdbc.update("DELETE FROM areas WHERE id = ?", area);
    }

    // ---------------------------------------------------------------- the Home card

    @Test
    void homeCardOpensEachPlaceAndSaysWhereItIs() {
        long inArea = place("Quán " + word, area, "ACTIVE");
        long inNaha = place("Naha " + word, naha, "ACTIVE");
        long r1 = review(author, inArea, base.minusSeconds(7200));
        long r2 = review(author, inNaha, base.minusSeconds(3600));     // newer, but the area comes first

        List<ReviewItem> card = home.member(viewer).reviews();
        assertThat(card).extracting(ReviewItem::id).startsWith(r1, r2);
        assertThat(card.get(0)).isEqualTo(new ReviewItem(r1, inArea, "Quán " + word, card.get(0).categoryIcon(),
                "Review test – " + word, "AREA", 4, "Review Test", "Review " + word, base.minusSeconds(7200)));
        assertThat(card.get(0).categoryIcon()).isNotBlank();
        assertThat(card.get(1).placeId()).isEqualTo(inNaha);
        assertThat(card.get(1).near()).isEqualTo("PREFECTURE");
        assertThat(card.get(1).placeArea()).startsWith("Naha-shi, Okinawa · ");
        assertThat(card.get(1).createdAt()).isEqualTo(base.minusSeconds(3600));
        // The third one is elsewhere (the newest of the shared database): not near
        assertThat(card).hasSize(3);
        assertThat(card.get(2).near()).isNull();
    }

    @Test
    void homeCardMarksNothingNearWhenTheAreaAndPrefectureHaveNoReview() {
        long far = place("Xa " + word, null, "ACTIVE");
        long r = review(author, far, base);
        List<ReviewItem> card = home.member(viewer).reviews();
        assertThat(card.get(0).id()).isEqualTo(r);
        assertThat(card).allSatisfy(item -> assertThat(item.near()).isNull());
        assertThat(card.get(0).placeArea()).isNull();
    }

    @Test
    void nextGuideItemCarriesItsPlace() {
        long inArea = place("Văn phòng " + word, area, "ACTIVE");
        jdbc.update("""
                INSERT INTO guide_items (stage_code, title, place_id, sort_order, published)
                VALUES ('ARR', ?, ?, -1000000000, TRUE)""", "Đăng ký " + word, inArea);
        try {
            GuideNext next = home.member(viewer).guide().next();
            assertThat(next).isEqualTo(new GuideNext("Đăng ký " + word, null, "Văn phòng " + word, inArea));
        } finally {
            jdbc.update("DELETE FROM guide_items WHERE title = ?", "Đăng ký " + word);
        }
    }

    // ---------------------------------------------------------------- the page "Review mới"

    @Test
    void pageListsNewestFirstInEachScopeWithItsCount() throws Exception {
        long all = count(null, "ALL");
        long a1 = review(author, place("A1 " + word, area, "ACTIVE"), base.minusSeconds(3 * 3600));
        long a2 = review(author, place("A2 " + word, area, "ACTIVE"), base.minusSeconds(3600));
        long p1 = review(author, place("P1 " + word, naha, "ACTIVE"), base.minusSeconds(2 * 3600));
        // Never listed: the viewer's own review, a locked author, reviews not published, places not on the Map
        long inArea = place("Khác " + word, area, "ACTIVE");
        review(viewer, inArea, base);
        review(user("LOCKED"), inArea, base);
        reviewWithStatus(user("ACTIVE"), inArea, "PROCESSING", base);
        reviewWithStatus(user("ACTIVE"), inArea, "HIDDEN", base);
        reviewWithStatus(user("ACTIVE"), inArea, "REMOVED", base);
        review(author, place("Ghim " + word, area, "PENDING"), base);
        review(author, place("Bỏ " + word, area, "REMOVED"), base);

        // No scope asked: the first one holding reviews, the area
        String body = reviews(viewer, null, null);
        assertThat((String) JsonPath.read(body, "$.data.scope")).isEqualTo("AREA");
        assertThat((String) JsonPath.read(body, "$.data.areaName")).isEqualTo("Review test – " + word);
        assertThat((String) JsonPath.read(body, "$.data.prefectureName")).isEqualTo("Okinawa");
        assertThat(scopes(body)).containsExactly(Map.entry("AREA", 2L), Map.entry("PREFECTURE", 3L),
                Map.entry("ALL", all + 3));
        assertThat(ids(body)).containsExactly(a2, a1);
        assertThat((Boolean) JsonPath.read(body, "$.data.hasMore")).isFalse();
        Map<String, Object> first = JsonPath.read(body, "$.data.items[0]");
        assertThat(first).containsEntry("placeName", "A2 " + word).containsEntry("near", "AREA")
                .containsEntry("placeArea", "Review test – " + word).containsEntry("placeClosed", false)
                .containsEntry("rating", 4).containsEntry("authorName", "Review Test")
                .containsEntry("excerpt", "Review " + word).containsEntry("likeCount", 0)
                .containsEntry("photoUrl", null).containsEntry("mediaCount", 0);
        assertThat(first.get("placeId")).isNotNull();

        body = reviews(viewer, "PREFECTURE", null);
        assertThat((String) JsonPath.read(body, "$.data.scope")).isEqualTo("PREFECTURE");
        assertThat(ids(body)).containsExactly(a2, p1, a1);
        assertThat((String) JsonPath.read(body, "$.data.items[1].near")).isEqualTo("PREFECTURE");

        body = reviews(viewer, "ALL", null);
        assertThat(ids(body)).startsWith(a2, p1, a1);
    }

    @Test
    void tenAPageWithMoreAfterAndAPageNumberKeptInRange() throws Exception {
        List<Long> expected = new ArrayList<>();          // newest first
        for (int i = 0; i < 12; i++) {
            expected.add(review(author, place("Trang " + i + " " + word, area, "ACTIVE"), base.minusSeconds(60L * i)));
        }

        String page0 = reviews(viewer, "AREA", 0);
        assertThat(ids(page0)).containsExactlyElementsOf(expected.subList(0, 10));
        assertThat((Boolean) JsonPath.read(page0, "$.data.hasMore")).isTrue();
        String page1 = reviews(viewer, "AREA", 1);
        assertThat(ids(page1)).containsExactlyElementsOf(expected.subList(10, 12));
        assertThat((Boolean) JsonPath.read(page1, "$.data.hasMore")).isFalse();
        assertThat((Integer) JsonPath.read(page1, "$.data.page")).isEqualTo(1);

        assertThat(ids(reviews(viewer, "AREA", -5))).containsExactlyElementsOf(expected.subList(0, 10));
        String far = reviews(viewer, "AREA", 100_000);
        assertThat((Integer) JsonPath.read(far, "$.data.page")).isEqualTo(HomeReviewService.MAX_PAGE);
        assertThat(ids(far)).isEmpty();
        assertThat((Boolean) JsonPath.read(far, "$.data.hasMore")).isFalse();
    }

    @Test
    void anEmptyAreaOpensOnTheFirstScopeWithReviewsButAnAskedScopeIsKept() throws Exception {
        long p1 = review(author, place("P " + word, naha, "ACTIVE"), base);
        String body = reviews(viewer, null, null);
        assertThat((String) JsonPath.read(body, "$.data.scope")).isEqualTo("PREFECTURE");
        assertThat(ids(body)).containsExactly(p1);

        body = reviews(viewer, "AREA", null);
        assertThat((String) JsonPath.read(body, "$.data.scope")).isEqualTo("AREA");
        assertThat(ids(body)).isEmpty();
        // An unknown scope is the same as none
        assertThat((String) JsonPath.read(reviews(viewer, "PLANET", null), "$.data.scope")).isEqualTo("PREFECTURE");
    }

    @Test
    void guestsAndMembersWithoutAnAreaGetEverythingOnly() throws Exception {
        long r = review(author, place("G " + word, area, "ACTIVE"), base);
        for (Long who : new Long[]{null, author}) {
            String body = reviews(who, "AREA", null);
            assertThat((String) JsonPath.read(body, "$.data.scope")).isEqualTo("ALL");
            assertThat(scopes(body)).containsOnlyKeys("ALL");
            assertThat((Object) JsonPath.read(body, "$.data.areaName")).isNull();
            if (who == null) {
                assertThat(ids(body)).startsWith(r);
                assertThat((Object) JsonPath.read(body, "$.data.items[0].near")).isNull();
            } else {
                assertThat(ids(body)).doesNotContain(r);       // the author's own review
            }
        }
        // An area without a prefecture (made before V10): its area and everything
        jdbc.update("UPDATE areas SET prefecture_code = NULL WHERE id = ?", area);
        assertThat(scopes(reviews(viewer, null, null))).containsOnlyKeys("AREA", "ALL");
    }

    @Test
    void firstPhotoThatPassedTheScanTagsAndClosedLabelAreShown() throws Exception {
        long closed = place("Đóng " + word, area, "ACTIVE");
        jdbc.update("UPDATE places SET is_closed = TRUE WHERE id = ?", closed);
        long r = review(author, closed, base);
        jdbc.update("UPDATE reviews SET like_count = 11 WHERE id = ?", r);
        long rejected = file(r, "IMAGE", "REJECTED", false, 0);
        long deleted = file(r, "IMAGE", "PASSED", true, 1);
        long video = file(r, "VIDEO", "PASSED", false, 2);
        long photo = file(r, "IMAGE", "PASSED", false, 3);
        String photoKey = jdbc.queryForObject("SELECT storage_key FROM media WHERE id = ?", String.class, photo);
        long staff = user("ACTIVE");
        jdbc.update("""
                INSERT INTO user_tags (user_id, tag_type_id, granted_by)
                SELECT ?, id, ? FROM tag_types WHERE name = 'Mentor'""", author, staff);

        Map<String, Object> item = JsonPath.read(reviews(viewer, "AREA", null), "$.data.items[0]");
        assertThat(item).containsEntry("id", (int) r).containsEntry("placeClosed", true).containsEntry("likeCount", 11)
                .containsEntry("photoUrl", "/media/" + photoKey).containsEntry("mediaCount", 2);
        List<String> tagNames = JsonPath.read(reviews(viewer, "AREA", null), "$.data.items[0].authorTags[*].name");
        assertThat(tagNames).containsExactly("Mentor");
        assertThat(List.of(rejected, deleted, video)).doesNotContainNull();
    }

    @Test
    void thePageHasItsCleanAddress() throws Exception {
        mvc.perform(get("/reviews")).andExpect(status().isOk()).andExpect(forwardedUrl("/pages/reviews.html"));
    }

    // ---------------------------------------------------------------- helpers

    private long user(String status) {
        String email = "review-" + UUID.randomUUID() + "@test.local";
        jdbc.update("""
                INSERT INTO users (email, display_name, role, status, is_adult_confirmed, created_at)
                VALUES (?, 'Review Test', 'USER', ?, TRUE, ?)""",
                email, status, Timestamp.from(Instant.now().minus(Duration.ofDays(30))));
        long id = jdbc.queryForObject("SELECT id FROM users WHERE email = ?", Long.class, email);
        users.add(id);
        return id;
    }

    private long place(String name, Long areaId, String status) {
        jdbc.update("""
                INSERT INTO places (name_vi, category_id, area_id, lat, lng, source, status, created_by)
                VALUES (?, (SELECT MIN(id) FROM place_categories), ?, 26.2, 127.7, 'TEAM', ?, ?)""",
                name, areaId, status, author);
        long id = jdbc.queryForObject("SELECT id FROM places WHERE name_vi = ?", Long.class, name);
        places.add(id);
        return id;
    }

    private long review(long authorId, long placeId, Instant createdAt) {
        return reviewWithStatus(authorId, placeId, "PUBLISHED", createdAt);
    }

    private long reviewWithStatus(long authorId, long placeId, String status, Instant createdAt) {
        jdbc.update("""
                INSERT INTO reviews (author_id, place_id, rating, content, status, created_at)
                VALUES (?, ?, 4, ?, ?, ?)""", authorId, placeId, "Review " + word, status, Timestamp.from(createdAt));
        return jdbc.queryForObject("SELECT id FROM reviews WHERE author_id = ? AND place_id = ?", Long.class,
                authorId, placeId);
    }

    /** A photo or video of the review, with its scan result; {@code gone} = deleted. */
    private long file(long reviewId, String type, String scan, boolean gone, int order) {
        String key = "review/2026/10/" + UUID.randomUUID() + (type.equals("IMAGE") ? ".jpg" : ".mp4");
        jdbc.update("""
                INSERT INTO media (owner_id, media_type, storage_key, mime_type, size_bytes, scan_status, deleted_at)
                VALUES (?, ?, ?, ?, 100, ?, ?)""", author, type, key, type.equals("IMAGE") ? "image/jpeg" : "video/mp4",
                scan, gone ? Timestamp.from(Instant.now()) : null);
        long id = jdbc.queryForObject("SELECT id FROM media WHERE storage_key = ?", Long.class, key);
        media.add(id);
        jdbc.update("INSERT INTO review_media (review_id, media_id, sort_order) VALUES (?, ?, ?)", reviewId, id, order);
        return id;
    }

    private String reviews(Long userId, String scope, Integer page) throws Exception {
        MockHttpServletRequestBuilder request = get("/api/home/reviews");
        if (scope != null) {
            request.param("scope", scope);
        }
        if (page != null) {
            request.param("page", String.valueOf(page));
        }
        if (userId != null) {
            request.session(session(userId));
        }
        return mvc.perform(request).andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsString(StandardCharsets.UTF_8);
    }

    private long count(Long userId, String scope) throws Exception {
        String body = reviews(userId, scope, null);
        return scopes(body).get(scope);
    }

    private static Map<String, Long> scopes(String body) {
        List<Map<String, Object>> list = JsonPath.read(body, "$.data.scopes");
        Map<String, Long> out = new LinkedHashMap<>();
        list.forEach(s -> out.put((String) s.get("code"), ((Number) s.get("count")).longValue()));
        return out;
    }

    private static List<Long> ids(String body) {
        List<Number> ids = JsonPath.read(body, "$.data.items[*].id");
        return ids.stream().map(Number::longValue).toList();
    }

    private static MockHttpSession session(long userId) {
        AppUserPrincipal me = new AppUserPrincipal(userId, "u" + userId + "@test.local", "Review Test", "USER", "/");
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(SPRING_SECURITY_CONTEXT_KEY, new SecurityContextImpl(
                new UsernamePasswordAuthenticationToken(me, null, List.of(new SimpleGrantedAuthority("ROLE_USER")))));
        return session;
    }
}
