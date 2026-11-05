package com.sep490.vsa.feature.home;

import com.jayway.jsonpath.JsonPath;
import com.sep490.vsa.common.security.AppUserPrincipal;
import com.sep490.vsa.feature.blog.FeaturedSnapshotService;
import com.sep490.vsa.feature.home.HomeDtos.Featured;
import com.sep490.vsa.feature.home.HomeDtos.FeedPost;
import com.sep490.vsa.feature.home.HomeDtos.FeedReason;
import com.sep490.vsa.feature.home.HomeDtos.GuestHome;
import com.sep490.vsa.feature.home.HomeDtos.Quote;
import com.sep490.vsa.feature.home.HomeDtos.ReviewItem;
import com.sep490.vsa.feature.home.HomeDtos.Stats;
import com.sep490.vsa.feature.home.HomeDtos.Tag;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.web.context.HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * "Dành cho bạn" on the signed-in Home page, end to end against MySQL ({@code asvsj_test}, like QaFlowTest). Every
 * test creates its own area, so posts linked to it rank first whatever else the shared database holds; posts of
 * other runs are deleted afterwards.
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:mysql://localhost:3306/asvsj_test?createDatabaseIfNotExist=true&useUnicode=true&characterEncoding=utf8&connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true",
        "app.media.local-dir=target/test-uploads",
        "app.moderation.retry-initial-delay-ms=3600000",
        "app.places.osm.enabled=false"
})
@AutoConfigureMockMvc
@ActiveProfiles("local")
class HomeFeedFlowTest {

    private static final String CSRF = "test-csrf-token";
    private static final String NEAR = HomeService.REASON_AREA;
    private static final String FITS = HomeService.REASON_STAGE;
    /** Far above any real post, so a fixture with it leads its group in the shared database. */
    private static final int HUGE = 1_000_000;

    @Autowired
    private MockMvc mvc;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private FeaturedSnapshotService featuredPosts;
    @Autowired
    private HomeService home;

    private final List<Long> users = new ArrayList<>();
    private long area;
    private long place;        // ACTIVE place in this test's area
    private long viewer;
    private long author;
    private String word;

    @BeforeEach
    void setUp() {
        word = UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        // In Okinawa (47), where no other test puts places, for the same-prefecture order
        jdbc.update("""
                INSERT INTO areas (prefecture_code, ward, station, display_name, is_active)
                VALUES ('47', 'Test', 'Test', ?, TRUE)""", "Feed test – " + word);
        area = jdbc.queryForObject("SELECT id FROM areas WHERE display_name = ?", Long.class, "Feed test – " + word);
        viewer = user("ACTIVE");
        author = user("ACTIVE");
        jdbc.update("""
                INSERT INTO places (name_vi, category_id, area_id, lat, lng, source, status, created_by)
                VALUES (?, (SELECT MIN(id) FROM place_categories), ?, 35.7, 139.7, 'TEAM', 'ACTIVE', ?)""",
                "Feed place " + word, area, author);
        place = jdbc.queryForObject("SELECT id FROM places WHERE name_vi = ?", Long.class, "Feed place " + word);
    }

    @AfterEach
    void cleanUp() {
        for (long id : users) {
            jdbc.update("DELETE FROM blogs WHERE author_id = ?", id);
            jdbc.update("DELETE FROM reviews WHERE author_id = ?", id);
        }
        jdbc.update("DELETE FROM places WHERE id = ?", place);
        for (long id : users) {
            jdbc.update("DELETE FROM users WHERE id = ?", id);
        }
        jdbc.update("DELETE FROM areas WHERE id = ?", area);
    }

    @Test
    void areaFirstThenStageTopicThenScoreThenNewest() throws Exception {
        setProfile(viewer, area, "ARR");
        Instant now = Instant.now();
        long nearFits = blog(author, "POST", "PUBLISHED", now.minusSeconds(5 * 3600), 0, 0, true, "Nhà ở");
        long nearOnly = blog(author, "POST", "PUBLISHED", now.minusSeconds(4 * 3600), 50, 0, true, "Học tiếng Nhật");
        long fitsOnly = blog(author, "POST", "PUBLISHED", now.minusSeconds(3 * 3600), HUGE, 0, false, "Nhà ở");
        long neither = blog(author, "POST", "PUBLISHED", now.minusSeconds(2 * 3600), 2 * HUGE, 0, false, "Học tiếng Nhật");

        String body = home(viewer);
        List<Map<String, Object>> feed = JsonPath.read(body, "$.data.forYou");
        assertThat(ids(feed)).startsWith(nearFits, nearOnly, fitsOnly);
        assertThat(feed.get(0).get("reason")).isEqualTo(NEAR);       // the area badge wins over the stage badge
        assertThat(feed.get(1).get("reason")).isEqualTo(NEAR);
        assertThat(feed.get(2).get("reason")).isEqualTo(FITS);
        int neitherAt = ids(feed).indexOf(neither);
        assertThat(neitherAt == -1 || neitherAt > 2).isTrue();
        assertThat(feed.get(0)).containsEntry("topicName", "Nhà ở").containsEntry("title", "Feed " + word);
        // Every reason, with what matched: the place in the area, the topic that suits the stage
        assertThat(feed.get(0).get("reasons")).isEqualTo(List.of(
                Map.of("code", "AREA", "label", NEAR, "detail", "Feed place " + word),
                Map.of("code", "STAGE", "label", FITS, "detail", "Nhà ở")));
        assertThat(feed.get(1).get("reasons")).isEqualTo(List.of(
                Map.of("code", "AREA", "label", NEAR, "detail", "Feed place " + word)));
        assertThat(feed.get(2).get("reasons")).isEqualTo(List.of(Map.of("code", "STAGE", "label", FITS, "detail", "Nhà ở")));
        // A new member has done nothing yet: no interests
        assertThat((List<?>) JsonPath.read(body, "$.data.interests")).isEmpty();
    }

    @Test
    void sameAreaThenSamePrefectureThenElsewhere() throws Exception {
        setProfile(viewer, area, null);
        long naha = jdbc.queryForObject("SELECT id FROM areas WHERE code = '472018'", Long.class);   // Naha-shi
        jdbc.update("""
                INSERT INTO places (name_vi, category_id, area_id, lat, lng, source, status, created_by)
                VALUES (?, (SELECT MIN(id) FROM place_categories), ?, 26.2, 127.7, 'TEAM', 'ACTIVE', ?)""",
                "Feed place 2 " + word, naha, author);
        long other = jdbc.queryForObject("SELECT id FROM places WHERE name_vi = ?", Long.class, "Feed place 2 " + word);
        try {
            Instant now = Instant.now();
            long inArea = blog(author, "POST", "PUBLISHED", now.minusSeconds(3 * 3600), 0, 0, true, null);
            long inPrefecture = blog(author, "POST", "PUBLISHED", now.minusSeconds(2 * 3600), 0, 0, false, null);
            jdbc.update("INSERT INTO blog_place_links (blog_id, place_id) VALUES (?, ?)", inPrefecture, other);
            long elsewhere = blog(author, "POST", "PUBLISHED", now.minusSeconds(3600), HUGE, 0, false, null);
            // Linked to both: one reason, naming the place in the area
            long both = blog(author, "POST", "PUBLISHED", now.minusSeconds(4 * 3600), 0, 0, true, null);
            jdbc.update("INSERT INTO blog_place_links (blog_id, place_id) VALUES (?, ?)", both, other);

            List<Map<String, Object>> feed = forYou(viewer);
            assertThat(ids(feed)).startsWith(inArea, both, inPrefecture);
            assertThat(feed.get(0).get("reason")).isEqualTo(NEAR);
            assertThat(feed.get(1).get("reasons")).isEqualTo(List.of(
                    Map.of("code", "AREA", "label", NEAR, "detail", "Feed place " + word)));
            assertThat(feed.get(2).get("reason")).isEqualTo(HomeService.REASON_PREFECTURE);
            assertThat(feed.get(2).get("reasons")).isEqualTo(List.of(
                    Map.of("code", "PREFECTURE", "label", HomeService.REASON_PREFECTURE, "detail", "Feed place 2 " + word)));
            assertThat(ids(feed).indexOf(elsewhere)).isNotIn(0, 1, 2);
        } finally {
            jdbc.update("DELETE FROM blogs WHERE author_id = ?", author);
            jdbc.update("DELETE FROM places WHERE id = ?", other);
        }
    }

    @Test
    void onlyPublishedPostsOfOthersFiveAtMostScoreThenNewest() throws Exception {
        setProfile(viewer, area, "PRE");
        Instant now = Instant.now();
        long deleted = user("DELETED");
        // Never shown, although linked to the area and liked a lot
        List<Long> hidden = List.of(
                blog(author, "QA", "PUBLISHED", now, HUGE, 0, true, null),
                blog(author, "POST", "PROCESSING", now, HUGE, 0, true, null),
                blog(author, "POST", "HIDDEN", now, HUGE, 0, true, null),
                blog(author, "POST", "REMOVED", now, HUGE, 0, true, null),
                blog(viewer, "POST", "PUBLISHED", now, HUGE, 0, true, null),
                blog(deleted, "POST", "PUBLISHED", now, HUGE, 0, true, null),
                blog(author, "POST", "PUBLISHED", now.minus(Duration.ofDays(40)), HUGE, 0, true, null));
        // Same area, none suits PRE: likes + 2 × comments, then newest
        long top = blog(author, "POST", "PUBLISHED", now.minusSeconds(6000), 50, 0, true, "Ăn uống");
        long tenNew = blog(author, "POST", "PUBLISHED", now.minusSeconds(1000), 0, 5, true, null);
        long tenOld = blog(author, "POST", "PUBLISHED", now.minusSeconds(2000), 10, 0, true, "Khác");
        long one = blog(author, "POST", "PUBLISHED", now.minusSeconds(3000), 1, 0, true, null);
        long zero = blog(author, "POST", "PUBLISHED", now.minusSeconds(4000), 0, 0, true, null);
        long sixth = blog(author, "POST", "PUBLISHED", now.minusSeconds(5000), 0, 0, true, null);

        List<Map<String, Object>> feed = forYou(viewer);
        assertThat(ids(feed)).containsExactly(top, tenNew, tenOld, one, zero);
        assertThat(ids(feed)).doesNotContainAnyElementsOf(hidden).doesNotContain(sixth);
        assertThat(feed).allSatisfy(item -> assertThat(item.get("reason")).isEqualTo(NEAR));
        assertThat(feed.get(1)).containsEntry("topicName", null).containsEntry("commentCount", 5);
        String excerpt = (String) feed.get(0).get("excerpt");
        assertThat(excerpt).endsWith("…").hasSizeLessThanOrEqualTo(141).doesNotContain("\n");
    }

    @Test
    void withoutAreaOrStageNoBadgesAndScoreThenNewest() throws Exception {
        blog(author, "POST", "PUBLISHED", Instant.now(), HUGE, 0, true, "Nhà ở");
        List<Map<String, Object>> feed = forYou(viewer);
        assertThat(feed).hasSizeLessThanOrEqualTo(5).allSatisfy(item -> assertThat(item.get("reason")).isNull());
        Instant since = Instant.now().minus(Duration.ofDays(30));
        int previous = Integer.MAX_VALUE;
        for (Map<String, Object> item : feed) {
            if (Instant.parse((String) item.get("createdAt")).isBefore(since)) {
                break;     // older posts only fill the list
            }
            int score = (int) item.get("likeCount") + 2 * (int) item.get("commentCount");
            assertThat(score).isLessThanOrEqualTo(previous);
            previous = score;
        }
    }

    @Test
    void olderPostsFillTheListRankedTheSameWay() {
        // Seen from ten years ahead every post is old, except one written "yesterday" in that future
        Clock future = Clock.offset(Clock.systemUTC(), Duration.ofDays(3650));
        setProfile(viewer, area, null);
        long fresh = blog(author, "POST", "PUBLISHED", future.instant().minus(Duration.ofDays(1)), 0, 0, false, null);
        long oldNear = blog(author, "POST", "PUBLISHED", Instant.now().minus(Duration.ofDays(60)), 0, 0, true, null);

        List<FeedPost> feed = new HomeService(jdbc, future, featuredPosts).member(viewer).forYou();
        assertThat(feed).extracting(FeedPost::id).startsWith(fresh, oldNear);
        assertThat(feed.get(0).reason()).isNull();
        assertThat(feed.get(1).reason()).isEqualTo(NEAR);
    }

    @Test
    void interestsAreLearntFromLikesSavesCommentsAndOwnPostsOfTheLast90Days() {
        // Seen from ten years ahead, so only what this test dates in that future is recent
        Clock future = Clock.offset(Clock.systemUTC(), Duration.ofDays(3650));
        Instant day = future.instant().minus(Duration.ofDays(1));
        Instant tooOld = future.instant().minus(Duration.ofDays(HomeService.INTEREST_DAYS + 5));
        // "Ăn uống": an own post (3) and a save (2) = 5
        blog(viewer, "POST", "PUBLISHED", day, 0, 0, false, "Ăn uống");
        save(viewer, blog(author, "POST", "PUBLISHED", day, 0, 0, false, "Ăn uống"), day);
        // "Y tế": five comments on one post count once (2), and a like of it (1) = 3
        long health = blog(author, "POST", "PUBLISHED", day, 0, 0, false, "Y tế");
        for (int i = 0; i < 5; i++) {
            comment(viewer, health, "PUBLISHED", day);
        }
        like(viewer, health, day);
        // "Đi lại": two likes, one of them on a question = 2
        like(viewer, blog(author, "POST", "PUBLISHED", day, 0, 0, false, "Đi lại"), day);
        like(viewer, blog(author, "QA", "PUBLISHED", day, 0, 0, false, "Đi lại"), day);
        // Not interests: "Khác", and what was done more than 90 days ago
        for (int i = 0; i < 3; i++) {
            blog(viewer, "POST", "PUBLISHED", day, 0, 0, false, "Khác");
        }
        long old = blog(author, "POST", "PUBLISHED", tooOld, 0, 0, false, "Nhà ở");
        save(viewer, old, tooOld);
        like(viewer, old, tooOld);
        blog(viewer, "POST", "PUBLISHED", tooOld, 0, 0, false, "Nhà ở");

        assertThat(new HomeService(jdbc, future, featuredPosts).member(viewer).interests())
                .containsExactly("Ăn uống", "Y tế", "Đi lại");
    }

    @Test
    void oneLikeUnpublishedCommentsOrPostsAndSwitchedOffTopicsMakeNoInterest() {
        Instant now = Instant.now();
        like(viewer, blog(author, "POST", "PUBLISHED", now, 0, 0, false, "Mua sắm"), now);    // 1 point
        long health = blog(author, "POST", "PUBLISHED", now, 0, 0, false, "Y tế");
        comment(viewer, health, "REMOVED", now);
        comment(viewer, health, "PROCESSING", now);
        blog(viewer, "POST", "REJECTED", now, 0, 0, false, "Đi lại");
        blog(viewer, "POST", "PROCESSING", now, 0, 0, false, "Đi lại");
        assertThat(home.member(viewer).interests()).isEmpty();

        save(viewer, blog(author, "POST", "PUBLISHED", now, 0, 0, false, "Nhà ở"), now);
        assertThat(home.member(viewer).interests()).containsExactly("Nhà ở");
        jdbc.update("UPDATE blog_topics SET is_active = FALSE WHERE name = 'Nhà ở'");
        try {
            assertThat(home.member(viewer).interests()).isEmpty();
        } finally {
            jdbc.update("UPDATE blog_topics SET is_active = TRUE WHERE name = 'Nhà ở'");
        }
    }

    @Test
    void interestsRankAfterAreaAndStageAndPostsAlreadyReadComeLast() {
        Clock future = Clock.offset(Clock.systemUTC(), Duration.ofDays(3650));
        HomeService home = new HomeService(jdbc, future, featuredPosts);
        Instant day = future.instant().minus(Duration.ofDays(1));
        setProfile(viewer, area, "PRE");
        // Saving a "Việc làm thêm" post makes it an interest (2), and that post one the viewer already read
        long saved = blog(author, "POST", "PUBLISHED", day, 3 * HUGE, 0, true, "Việc làm thêm");
        save(viewer, saved, day);
        long liked = blog(author, "POST", "PUBLISHED", day, 3 * HUGE, 0, true, null);
        like(viewer, liked, day);
        long commented = blog(author, "POST", "PUBLISHED", day, 3 * HUGE, 0, true, null);
        comment(viewer, commented, "PUBLISHED", day);

        long near = blog(author, "POST", "PUBLISHED", day, 0, 0, true, null);
        long both = blog(author, "POST", "PUBLISHED", day, 0, 0, false, "Nhà ở");          // suits PRE
        topic(both, "Việc làm thêm");                                                    // and the interest
        long stageOnly = blog(author, "POST", "PUBLISHED", day, 0, 0, false, "Visa và cư trú");
        long interestOnly = blog(author, "POST", "PUBLISHED", day, HUGE, 0, false, "Việc làm thêm");
        long plain = blog(author, "POST", "PUBLISHED", day, 2 * HUGE, 0, false, null);

        var member = home.member(viewer);
        assertThat(member.interests()).containsExactly("Việc làm thêm");
        List<FeedPost> feed = member.forYou();
        assertThat(feed).extracting(FeedPost::id).containsExactly(near, both, stageOnly, interestOnly, plain);
        assertThat(feed.get(0).reasons()).containsExactly(new FeedReason("AREA", NEAR, "Feed place " + word));
        assertThat(feed.get(1).reasons()).containsExactly(new FeedReason("STAGE", FITS, "Nhà ở"),
                new FeedReason("INTEREST", HomeService.REASON_INTEREST, "Việc làm thêm"));
        assertThat(feed.get(1).reason()).isEqualTo(FITS);
        assertThat(feed.get(2).reasons()).containsExactly(new FeedReason("STAGE", FITS, "Visa và cư trú"));
        assertThat(feed.get(3).reasons()).containsExactly(
                new FeedReason("INTEREST", HomeService.REASON_INTEREST, "Việc làm thêm"));
        assertThat(feed.get(3).reason()).isEqualTo(HomeService.REASON_INTEREST);
        assertThat(feed.get(4).reasons()).isEmpty();
        assertThat(feed.get(4).reason()).isNull();
        // Saved, liked or commented on: only fillers, after every post not read yet (old ones included)
        assertThat(feed).extracting(FeedPost::id).doesNotContain(saved, liked, commented);
    }

    @Test
    void inactiveTopicsAndTagTypesAreLeftOut() {
        // Seen from ten years ahead only this test's posts are recent, so they lead the feed and are featured
        Clock future = Clock.offset(Clock.systemUTC(), Duration.ofDays(3650));
        HomeService home = new HomeService(jdbc, future, featuredPosts);
        setProfile(viewer, null, "ARR");
        long post = blog(author, "POST", "PUBLISHED", future.instant().minus(Duration.ofDays(1)), 50, 0, false, "Nhà ở");
        long staff = user("ACTIVE");
        String hiddenTag = "Tag tắt " + word;
        jdbc.update("INSERT INTO tag_types (name, description, color, is_active) VALUES (?, 'test', 'gray', FALSE)",
                hiddenTag);
        jdbc.update("""
                INSERT INTO user_tags (user_id, tag_type_id, granted_by)
                SELECT ?, id, ? FROM tag_types WHERE name IN ('Mentor', ?)""", author, staff, hiddenTag);
        try {
            FeedPost first = home.member(viewer).forYou().get(0);
            assertThat(first.id()).isEqualTo(post);
            assertThat(first.reason()).isEqualTo(FITS);
            assertThat(first.topicName()).isEqualTo("Nhà ở");
            Featured featured = home.member(viewer).featured();
            assertThat(featured.id()).isEqualTo(post);
            assertThat(featured.authorTags()).extracting(Tag::name).containsExactly("Mentor");

            // "Nhà ở" switched off by the Admin: no stage badge and no topic label any more
            jdbc.update("UPDATE blog_topics SET is_active = FALSE WHERE name = 'Nhà ở'");
            first = home.member(viewer).forYou().get(0);
            assertThat(first.id()).isEqualTo(post);
            assertThat(first.reason()).isNull();
            assertThat(first.topicName()).isNull();
        } finally {
            jdbc.update("UPDATE blog_topics SET is_active = TRUE WHERE name = 'Nhà ở'");
            jdbc.update("DELETE FROM user_tags WHERE user_id = ?", author);
            jdbc.update("DELETE FROM tag_types WHERE name = ?", hiddenTag);
        }
    }

    @Test
    void savingAreaAndStageChangesTheFeedAtOnce() throws Exception {
        long near = blog(author, "POST", "PUBLISHED", Instant.now(), 0, 0, true, null);
        long afterReturn = blog(author, "POST", "PUBLISHED", Instant.now(), HUGE, 0, false, "Sau khi về nước");
        assertThat(forYou(viewer)).allSatisfy(item -> assertThat(item.get("reason")).isNull());

        putProfile(viewer, "{\"areaId\":" + area + ",\"stage\":\"HOME\"}");
        List<Map<String, Object>> feed = forYou(viewer);
        assertThat(ids(feed)).startsWith(near, afterReturn);
        assertThat(feed.get(0).get("reason")).isEqualTo(NEAR);
        assertThat(feed.get(1).get("reason")).isEqualTo(FITS);

        putProfile(viewer, "{\"areaId\":null,\"stage\":null}");
        assertThat(forYou(viewer)).allSatisfy(item -> assertThat(item.get("reason")).isNull());
    }

    @Test
    void reviewsOfLockedOrDeletedAccountsAreNeitherListedNorCounted() {
        setProfile(viewer, area, "ARR");
        Stats before = home.guest().stats();
        Instant now = Instant.now();
        // The newest reviews in the viewer's area and the most liked anywhere: they would lead every list
        long locked = user("LOCKED");
        review(locked, "Khóa " + word, HUGE, now);
        review(user("DELETED"), "Đã xóa " + word, HUGE, now.minusSeconds(1));

        assertThat(home.member(viewer).reviews()).extracting(ReviewItem::excerpt).noneMatch(t -> t.contains(word));
        GuestHome guest = home.guest();
        assertThat(guest.quotes()).extracting(Quote::text).noneMatch(t -> t.contains(word));
        assertThat(guest.stats().reviews()).isEqualTo(before.reviews());
        assertThat(guest.stats().placesWithReviews()).isEqualTo(before.placesWithReviews());

        // An active author's review is listed, quoted and counted
        review(author, "Hoạt động " + word, HUGE - 1, now.minusSeconds(2));
        assertThat(home.member(viewer).reviews().get(0).excerpt()).isEqualTo("Hoạt động " + word);
        guest = home.guest();
        assertThat(guest.quotes().get(0).text()).isEqualTo("Hoạt động " + word);
        assertThat(guest.stats().reviews()).isEqualTo(before.reviews() + 1);
        assertThat(guest.stats().placesWithReviews()).isEqualTo(before.placesWithReviews() + 1);

        // Nothing was deleted: unlocked, the account's review shows again
        jdbc.update("UPDATE users SET status = 'ACTIVE' WHERE id = ?", locked);
        assertThat(home.member(viewer).reviews().get(0).excerpt()).isEqualTo("Khóa " + word);
        assertThat(home.guest().stats().reviews()).isEqualTo(before.reviews() + 2);
    }

    @Test
    void guestsGetNoPersonalHome() throws Exception {
        mvc.perform(get("/api/me/home")).andExpect(status().isUnauthorized());
    }

    // ---------------------------------------------------------------- helpers

    private long user(String status) {
        String email = "feed-" + UUID.randomUUID() + "@test.local";
        jdbc.update("""
                INSERT INTO users (email, display_name, role, status, is_adult_confirmed, created_at, deleted_at)
                VALUES (?, 'Feed Test', 'USER', ?, TRUE, ?, ?)""",
                email, status, Timestamp.from(Instant.now().minus(Duration.ofDays(30))),
                "DELETED".equals(status) ? Timestamp.from(Instant.now()) : null);
        long id = jdbc.queryForObject("SELECT id FROM users WHERE email = ?", Long.class, email);
        users.add(id);
        return id;
    }

    /** A published review of this test's place, with its likes and date. */
    private void review(long authorId, String content, int likes, Instant createdAt) {
        jdbc.update("""
                INSERT INTO reviews (author_id, place_id, rating, content, status, like_count, created_at)
                VALUES (?, ?, 5, ?, 'PUBLISHED', ?, ?)""", authorId, place, content, likes, Timestamp.from(createdAt));
    }

    private void setProfile(long userId, Long areaId, String stage) {
        jdbc.update("UPDATE users SET area_id = ?, stage = ? WHERE id = ?", areaId, stage, userId);
    }

    /** Inserts a blog directly, so its date and counts are exactly what the test needs. */
    private long blog(long authorId, String type, String status, Instant createdAt, int likes, int comments,
                      boolean inArea, String topicName) {
        String title = "Feed " + word;
        jdbc.update("""
                INSERT INTO blogs (author_id, blog_type, title, content, status, like_count, comment_count, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)""",
                authorId, type, title, "Dòng đầu\n" + "nội dung dài ".repeat(30), status, likes, comments,
                Timestamp.from(createdAt));
        long id = jdbc.queryForObject("SELECT MAX(id) FROM blogs WHERE author_id = ? AND title = ?", Long.class,
                authorId, title);
        if (inArea) {
            jdbc.update("INSERT INTO blog_place_links (blog_id, place_id) VALUES (?, ?)", id, place);
        }
        if (topicName != null) {
            topic(id, topicName);
        }
        return id;
    }

    private void topic(long blogId, String topicName) {
        jdbc.update("INSERT INTO blog_topic_links (blog_id, topic_id) SELECT ?, id FROM blog_topics WHERE name = ?",
                blogId, topicName);
    }

    private void like(long userId, long blogId, Instant at) {
        jdbc.update("INSERT INTO blog_likes (blog_id, user_id, created_at) VALUES (?, ?, ?)", blogId, userId,
                Timestamp.from(at));
    }

    private void save(long userId, long blogId, Instant at) {
        jdbc.update("INSERT INTO saved_blogs (blog_id, user_id, created_at) VALUES (?, ?, ?)", blogId, userId,
                Timestamp.from(at));
    }

    private void comment(long userId, long blogId, String status, Instant at) {
        jdbc.update("INSERT INTO comments (blog_id, author_id, content, status, created_at) VALUES (?, ?, ?, ?, ?)",
                blogId, userId, "Bình luận " + word, status, Timestamp.from(at));
    }

    private static MockHttpSession session(long userId) {
        AppUserPrincipal me = new AppUserPrincipal(userId, "u" + userId + "@test.local", "Feed Test", "USER", "/");
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(SPRING_SECURITY_CONTEXT_KEY, new SecurityContextImpl(
                new UsernamePasswordAuthenticationToken(me, null, List.of(new SimpleGrantedAuthority("ROLE_USER")))));
        return session;
    }

    private String home(long userId) throws Exception {
        return mvc.perform(get("/api/me/home").session(session(userId)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    private List<Map<String, Object>> forYou(long userId) throws Exception {
        return JsonPath.read(home(userId), "$.data.forYou");
    }

    private void putProfile(long userId, String json) throws Exception {
        mvc.perform(put("/api/me/profile").session(session(userId))
                        .cookie(new Cookie("XSRF-TOKEN", CSRF)).header("X-XSRF-TOKEN", CSRF)
                        .contentType(MediaType.APPLICATION_JSON).content(json))
                .andExpect(status().isOk());
    }

    private static List<Long> ids(List<Map<String, Object>> feed) {
        return feed.stream().map(item -> ((Number) item.get("id")).longValue()).toList();
    }
}
