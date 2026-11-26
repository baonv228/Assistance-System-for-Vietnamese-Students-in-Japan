package com.sep490.vsa.feature.blog;

import com.jayway.jsonpath.JsonPath;
import com.sep490.vsa.common.security.AppUserPrincipal;
import jakarta.servlet.http.Cookie;
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

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.web.context.HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The Facebook-like cards of the Blog and Hỏi-Đáp lists ({@code GET /api/blogs}, {@code GET /api/questions}) carry the
 * photos and videos of each post by the rule of the post page (scan PASSED, not deleted; files still scanned only for
 * the post's own author, in the post's order) and whether the signed-in viewer likes it; so do the posts of "Dành cho
 * bạn" ({@code /api/me/home}, {@code /api/me/for-you}), drawn with the same cards. Runs against MySQL
 * ({@code asvsj_test}); every row is found through a word only this test uses.
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:mysql://localhost:3306/asvsj_test?createDatabaseIfNotExist=true&useUnicode=true&characterEncoding=utf8&connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true",
        "app.moderation.retry-initial-delay-ms=3600000",
        "app.places.osm.enabled=false"
})
@AutoConfigureMockMvc
@ActiveProfiles("local")
class FeedCardsFlowTest {

    private static final String CSRF = "test-csrf-token";

    @Autowired
    private MockMvc mvc;
    @Autowired
    private JdbcTemplate jdbc;

    private String word;
    private long author;
    private long viewer;
    private long staff;
    private final List<Long> blogs = new ArrayList<>();
    private final List<Long> media = new ArrayList<>();

    @BeforeEach
    void setUp() {
        // letters only, so the accent-folding search matches it exactly
        word = "fbcard" + UUID.randomUUID().toString().replaceAll("[^a-f]", "");
        author = user("USER");
        viewer = user("USER");
        staff = user("MODERATOR");
    }

    @AfterEach
    void cleanUp() {
        blogs.forEach(id -> jdbc.update("DELETE FROM blogs WHERE id = ?", id));        // links and likes cascade
        media.forEach(id -> jdbc.update("DELETE FROM media WHERE id = ?", id));
    }

    @Test
    void postCardsCarryTheVisiblePhotosInTheirOrderAndTheViewersLike() throws Exception {
        long published = blog("POST", "PUBLISHED");
        long second = media(published, "IMAGE", "PASSED", 1, false, 1200, 800);
        long first = media(published, "IMAGE", "PASSED", 0, false, 800, 1200);
        media(published, "IMAGE", "REJECTED", 2, false, 800, 800);
        media(published, "IMAGE", "PASSED", 3, true, 800, 800);           // deleted
        long scanned = media(published, "IMAGE", "PROCESSING", 4, false, 640, 480);
        long waiting = blog("POST", "PROCESSING");
        long waitingPhoto = media(waiting, "IMAGE", "PROCESSING", 0, false, 640, 480);
        long plain = blog("POST", "PUBLISHED");

        // A guest: passed files only, in the post's order, with their address and size; nothing liked
        String guest = body(get("/api/blogs?size=30&q=" + word));
        assertThat(ids(guest)).containsExactlyInAnyOrder(published, plain);
        assertThat(mediaIds(guest, published)).containsExactly(first, second);
        Map<String, Object> photo = card(guest, published).get("media") instanceof List<?> list
                ? cast(list.get(0)) : Map.of();
        assertThat(photo).containsEntry("type", "IMAGE").containsEntry("mimeType", "image/jpeg")
                .containsEntry("width", 800).containsEntry("height", 1200);
        assertThat((String) photo.get("url")).startsWith("/media/blog/").endsWith(".jpg");
        assertThat(mediaIds(guest, plain)).isEmpty();
        assertThat(card(guest, published)).containsEntry("liked", false);

        // The author also sees the file still being scanned and their own post waiting for its scan
        String own = body(get("/api/blogs?size=30&q=" + word).session(session(author, "USER")));
        assertThat(ids(own)).containsExactlyInAnyOrder(published, plain, waiting);
        assertThat(mediaIds(own, published)).containsExactly(first, second, scanned);
        assertThat(mediaIds(own, waiting)).containsExactly(waitingPhoto);

        // Another member: what a guest sees; "liked" follows their like and unlike
        like("/api/blogs/" + published, true);
        String liked = body(get("/api/blogs?size=30&q=" + word).session(session(viewer, "USER")));
        assertThat(mediaIds(liked, published)).containsExactly(first, second);
        assertThat(card(liked, published)).containsEntry("liked", true).containsEntry("likeCount", 1);
        assertThat(card(liked, plain)).containsEntry("liked", false);
        assertThat(card(body(get("/api/blogs?size=30&q=" + word)), published)).containsEntry("liked", false);
        assertThat(card(body(get("/api/blogs?size=30&q=" + word).session(session(author, "USER"))), published))
                .containsEntry("liked", false);
        assertThat(card(body(get("/api/blogs?size=30&q=" + word).session(session(staff, "MODERATOR"))), published))
                .containsEntry("liked", false);

        like("/api/blogs/" + published, false);
        assertThat(card(body(get("/api/blogs?size=30&q=" + word).session(session(viewer, "USER"))), published))
                .containsEntry("liked", false).containsEntry("likeCount", 0);
    }

    @Test
    void questionCardsCarryTheirPhotosAndVideoAndTheViewersLike() throws Exception {
        long question = blog("QA", "PUBLISHED");
        long video = media(question, "VIDEO", "PASSED", 0, false, null, null);
        long photo = media(question, "IMAGE", "PASSED", 1, false, 1024, 768);
        media(question, "IMAGE", "PROCESSING", 2, false, 1024, 768);
        long bare = blog("QA", "PUBLISHED");

        String guest = body(get("/api/questions?size=30&q=" + word));
        assertThat(ids(guest)).containsExactlyInAnyOrder(question, bare);
        assertThat(mediaIds(guest, question)).containsExactly(video, photo);
        Map<String, Object> first = cast(((List<?>) card(guest, question).get("media")).get(0));
        assertThat(first).containsEntry("type", "VIDEO").containsEntry("mimeType", "video/mp4");
        assertThat(first.get("width")).isNull();
        assertThat(mediaIds(guest, bare)).isEmpty();
        assertThat(card(guest, question)).containsEntry("liked", false);

        like("/api/questions/" + question, true);
        String member = body(get("/api/questions?size=30&q=" + word).session(session(viewer, "USER")));
        assertThat(card(member, question)).containsEntry("liked", true).containsEntry("likeCount", 1);
        assertThat(card(member, bare)).containsEntry("liked", false);
        assertThat(mediaIds(body(get("/api/questions?size=30&q=" + word).session(session(author, "USER"))), question))
                .hasSize(3);
    }

    @Test
    void forYouPostsCarryWhatTheCardsShow() throws Exception {
        long post = blog("POST", "PUBLISHED");
        long second = media(post, "IMAGE", "PASSED", 1, false, 1200, 800);
        long first = media(post, "VIDEO", "PASSED", 0, false, null, null);
        media(post, "IMAGE", "REJECTED", 2, false, 800, 800);
        media(post, "IMAGE", "PROCESSING", 3, false, 800, 800);
        long topic = jdbc.queryForObject("SELECT id FROM blog_topics WHERE is_active ORDER BY sort_order LIMIT 1", Long.class);
        jdbc.update("INSERT INTO blog_topic_links (blog_id, topic_id) VALUES (?, ?)", post, topic);
        jdbc.update("UPDATE users SET avatar_url = 'https://example.test/a.png' WHERE id = ?", author);
        long plain = blog("POST", "PUBLISHED");

        // "Xem tất cả" (/for-you), found by its word: the Blog card's author, topics, media (passed only, in order)
        String page = body(get("/api/me/for-you?q=" + word).session(session(viewer, "USER")));
        assertThat(ids(page)).containsExactlyInAnyOrder(post, plain);
        Map<String, Object> card = card(page, post);
        assertThat(mediaIds(page, post)).containsExactly(first, second);
        assertThat(cast(card.get("author"))).containsEntry("id", (int) author)
                .containsEntry("displayName", "Feed Card Test")
                .containsEntry("avatarUrl", "https://example.test/a.png");
        assertThat((List<?>) cast(card.get("author")).get("tags")).isEmpty();
        assertThat(((List<?>) card.get("topics")).stream().map(t -> ((Number) cast(t).get("id")).longValue()))
                .containsExactly(topic);
        assertThat((List<?>) card.get("places")).isEmpty();
        assertThat(card).containsEntry("liked", false).containsEntry("engaged", false);
        assertThat(mediaIds(page, plain)).isEmpty();

        // A like shows as "liked" (and "engaged": such posts go to the end of the feed)
        like("/api/blogs/" + post, true);
        card = card(body(get("/api/me/for-you?q=" + word).session(session(viewer, "USER"))), post);
        assertThat(card).containsEntry("liked", true).containsEntry("engaged", true).containsEntry("likeCount", 1);

        // The Home's five posts have the same parts
        String home = body(get("/api/me/home").session(session(viewer, "USER")));
        List<Map<String, Object>> forYou = JsonPath.read(home, "$.data.forYou");
        assertThat(forYou).isNotEmpty().allSatisfy(p -> assertThat(p)
                .containsKeys("author", "topics", "places", "media", "liked", "reasons", "authorName"));
        assertThat(forYou).allSatisfy(p -> assertThat(((Number) cast(p.get("author")).get("id")).longValue())
                .isNotEqualTo(viewer));
    }

    @Test
    void theBlogListLeavesQuestionsOutAndTheQuestionListLeavesPostsOut() throws Exception {
        long post = blog("POST", "PUBLISHED");
        long question = blog("QA", "PUBLISHED");
        media(post, "IMAGE", "PASSED", 0, false, 800, 600);
        media(question, "IMAGE", "PASSED", 0, false, 800, 600);
        assertThat(ids(body(get("/api/blogs?size=30&q=" + word)))).containsExactly(post);
        assertThat(ids(body(get("/api/questions?size=30&q=" + word)))).containsExactly(question);
    }

    // ---------------------------------------------------------------- helpers

    private long user(String role) {
        String email = "fbcard-" + UUID.randomUUID() + "@test.local";
        jdbc.update("""
                INSERT INTO users (email, display_name, role, status, is_adult_confirmed, created_at)
                VALUES (?, 'Feed Card Test', ?, 'ACTIVE', TRUE, ?)""",
                email, role, Timestamp.from(Instant.now().minus(Duration.ofDays(30))));
        return jdbc.queryForObject("SELECT id FROM users WHERE email = ?", Long.class, email);
    }

    private long blog(String type, String status) {
        jdbc.update("INSERT INTO blogs (author_id, blog_type, title, content, status) VALUES (?, ?, ?, ?, ?)",
                author, type, "Thẻ " + word + " " + blogs.size(), "Nội dung " + word, status);
        long id = jdbc.queryForObject("SELECT MAX(id) FROM blogs WHERE author_id = ?", Long.class, author);
        blogs.add(id);
        return id;
    }

    private long media(long blogId, String type, String scan, int order, boolean deleted, Integer width, Integer height) {
        boolean video = "VIDEO".equals(type);
        String key = "blog/2026/11/" + UUID.randomUUID() + (video ? ".mp4" : ".jpg");
        jdbc.update("""
                INSERT INTO media (owner_id, media_type, storage_key, mime_type, size_bytes, duration_sec, width, height,
                                   scan_status, deleted_at)
                VALUES (?, ?, ?, ?, 1000, ?, ?, ?, ?, ?)""",
                author, type, key, video ? "video/mp4" : "image/jpeg", video ? 12 : null, width, height, scan,
                deleted ? Timestamp.from(Instant.now()) : null);
        long id = jdbc.queryForObject("SELECT id FROM media WHERE storage_key = ?", Long.class, key);
        media.add(id);
        jdbc.update("INSERT INTO blog_media (blog_id, media_id, sort_order) VALUES (?, ?, ?)", blogId, id, order);
        return id;
    }

    private void like(String path, boolean on) throws Exception {
        MockHttpServletRequestBuilder request = on ? post(path + "/like") : delete(path + "/like");
        mvc.perform(request.session(session(viewer, "USER")).cookie(new Cookie("XSRF-TOKEN", CSRF))
                        .header("X-XSRF-TOKEN", CSRF))
                .andExpect(status().isOk());
    }

    private String body(MockHttpServletRequestBuilder request) throws Exception {
        return mvc.perform(request).andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
    }

    private static List<Long> ids(String json) {
        List<Number> ids = JsonPath.read(json, "$.data.items[*].id");
        return ids.stream().map(Number::longValue).toList();
    }

    private static Map<String, Object> card(String json, long id) {
        List<Map<String, Object>> cards = JsonPath.read(json, "$.data.items[?(@.id == " + id + ")]");
        assertThat(cards).as("card %d", id).hasSize(1);
        return cards.get(0);
    }

    private static List<Long> mediaIds(String json, long id) {
        List<?> list = (List<?>) card(json, id).get("media");
        return list.stream().map(m -> ((Number) cast(m).get("id")).longValue()).toList();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> cast(Object value) {
        return (Map<String, Object>) value;
    }

    private static MockHttpSession session(long userId, String role) {
        AppUserPrincipal me = new AppUserPrincipal(userId, "u" + userId + "@test.local", "Feed Card Test", role, "/");
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(SPRING_SECURITY_CONTEXT_KEY, new SecurityContextImpl(
                new UsernamePasswordAuthenticationToken(me, null, List.of(new SimpleGrantedAuthority("ROLE_" + role)))));
        return session;
    }
}
