package com.sep490.vsa.feature.home;

import com.jayway.jsonpath.JsonPath;
import com.sep490.vsa.common.security.AppUserPrincipal;
import com.sep490.vsa.feature.home.HomeDtos.FeedPost;
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

    private final List<Long> users = new ArrayList<>();
    private long area;
    private long place;        // ACTIVE place in this test's area
    private long viewer;
    private long author;
    private String word;

    @BeforeEach
    void setUp() {
        word = UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        jdbc.update("INSERT INTO areas (ward, station, display_name, is_active) VALUES ('Test', 'Test', ?, TRUE)",
                "Feed test – " + word);
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

        List<Map<String, Object>> feed = forYou(viewer);
        assertThat(ids(feed)).startsWith(nearFits, nearOnly, fitsOnly);
        assertThat(feed.get(0).get("reason")).isEqualTo(NEAR);       // the area badge wins over the stage badge
        assertThat(feed.get(1).get("reason")).isEqualTo(NEAR);
        assertThat(feed.get(2).get("reason")).isEqualTo(FITS);
        int neitherAt = ids(feed).indexOf(neither);
        assertThat(neitherAt == -1 || neitherAt > 2).isTrue();
        assertThat(feed.get(0)).containsEntry("topicName", "Nhà ở").containsEntry("title", "Feed " + word);
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

        List<FeedPost> feed = new HomeService(jdbc, future).member(viewer).forYou();
        assertThat(feed).extracting(FeedPost::id).startsWith(fresh, oldNear);
        assertThat(feed.get(0).reason()).isNull();
        assertThat(feed.get(1).reason()).isEqualTo(NEAR);
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
            jdbc.update("INSERT INTO blog_topic_links (blog_id, topic_id) SELECT ?, id FROM blog_topics WHERE name = ?",
                    id, topicName);
        }
        return id;
    }

    private static MockHttpSession session(long userId) {
        AppUserPrincipal me = new AppUserPrincipal(userId, "u" + userId + "@test.local", "Feed Test", "USER", "/");
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(SPRING_SECURITY_CONTEXT_KEY, new SecurityContextImpl(
                new UsernamePasswordAuthenticationToken(me, null, List.of(new SimpleGrantedAuthority("ROLE_USER")))));
        return session;
    }

    private List<Map<String, Object>> forYou(long userId) throws Exception {
        String body = mvc.perform(get("/api/me/home").session(session(userId)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        return JsonPath.read(body, "$.data.forYou");
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
