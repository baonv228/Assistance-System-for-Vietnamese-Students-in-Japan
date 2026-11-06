package com.sep490.vsa.feature.search;

import com.jayway.jsonpath.JsonPath;
import com.sep490.vsa.common.security.AppUserPrincipal;
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
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.web.context.HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.forwardedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * "Tìm kiếm" (GET /api/search and the page /search), end to end against MySQL ({@code asvsj_test}, like
 * HomeFeedFlowTest). Every fixture holds a word made for this run, so the shared database's other rows never match;
 * they are deleted afterwards.
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:mysql://localhost:3306/asvsj_test?createDatabaseIfNotExist=true&useUnicode=true&characterEncoding=utf8&connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true",
        "app.media.local-dir=target/test-uploads",
        "app.moderation.retry-initial-delay-ms=3600000",
        "app.places.osm.enabled=false"
})
@AutoConfigureMockMvc
@ActiveProfiles("local")
class SearchFlowTest {

    private static final LocalDate TODAY = LocalDate.now(ZoneId.of("Asia/Tokyo"));

    @Autowired
    private MockMvc mvc;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private SearchRepository repository;

    private final List<Long> users = new ArrayList<>();
    private final List<Long> places = new ArrayList<>();
    private final List<Long> events = new ArrayList<>();
    private final List<Long> phrases = new ArrayList<>();
    private final List<Long> guides = new ArrayList<>();
    private final List<Long> faqs = new ArrayList<>();
    /** In every fixture of this run and nowhere else. */
    private String tag;
    private long author;
    private long locked;
    private long viewer;

    @BeforeEach
    void setUp() {
        tag = "sx" + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        author = user("ACTIVE");
        locked = user("LOCKED");
        viewer = user("ACTIVE");
    }

    @AfterEach
    void cleanUp() {
        for (long id : users) {
            jdbc.update("DELETE FROM reviews WHERE author_id = ?", id);
            jdbc.update("DELETE FROM blogs WHERE author_id = ?", id);
        }
        places.forEach(id -> jdbc.update("DELETE FROM places WHERE id = ?", id));
        events.forEach(id -> jdbc.update("DELETE FROM events WHERE id = ?", id));
        phrases.forEach(id -> jdbc.update("DELETE FROM phrases WHERE id = ?", id));
        guides.forEach(id -> jdbc.update("DELETE FROM guide_items WHERE id = ?", id));
        faqs.forEach(id -> jdbc.update("DELETE FROM faq_entries WHERE id = ?", id));
        for (long id : users) {
            jdbc.update("DELETE FROM users WHERE id = ?", id);
        }
    }

    @Test
    void everyPartIsSearchedAtOnceWithWhatAViewerMaySee() throws Exception {
        Instant now = Instant.now();
        List<Long> posts = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            posts.add(blog(author, "POST", "PUBLISHED", "Chợ đêm " + tag + " số " + i, now.minusSeconds(60L * i)));
        }
        // Never shown: a locked author's post, a hidden or removed one; a question is listed apart
        blog(locked, "POST", "PUBLISHED", "Khóa " + tag, now);
        blog(author, "POST", "HIDDEN", "Ẩn " + tag, now);
        blog(author, "POST", "REMOVED", "Gỡ " + tag, now);
        long question = blog(author, "QA", "PUBLISHED", "Hỏi về " + tag + " ở đâu?", now);
        blog(locked, "QA", "PUBLISHED", "Hỏi khóa " + tag, now);

        long pho = place("Quán phở " + tag, "food", "ACTIVE", null);
        review(author, pho, 4);
        review(locked, pho, 1);                  // a locked account's review counts nowhere
        place("Đã gỡ " + tag, "food", "REMOVED", null);
        place("Đang chờ " + tag, "food", "PENDING", null);

        long past = event("Lễ hội cũ " + tag, TODAY.minusDays(20), TODAY.minusDays(19), false);
        long soon = event("Lễ hội sắp tới " + tag, TODAY.plusDays(5), TODAY.plusDays(6), false);
        long now1 = event("Lễ hội đang diễn ra " + tag, TODAY.minusDays(1), TODAY.plusDays(1), false);
        event("Lễ hội đã xóa " + tag, TODAY, TODAY, true);

        long bag = phrase("袋", "Túi " + tag, false);
        phrase("箱", "Hộp " + tag, true);
        long guide = guide("Cẩm nang " + tag, true, false);
        guide("Bản nháp " + tag, false, false);
        guide("Đã xóa " + tag, true, true);
        long faq = faq("Làm sao để " + tag + "?", true);
        faq("Ẩn " + tag + "?", false);

        String body = search(null, tag);
        assertThat((String) JsonPath.read(body, "$.data.q")).isEqualTo(tag);
        assertThat((Boolean) JsonPath.read(body, "$.data.tooShort")).isFalse();
        // The first 5 posts, newest first, the same as the Blog's own search ("Xem tất cả")
        assertThat(total(body, "posts")).isEqualTo(6);
        assertThat(ids(body, "posts")).isEqualTo(posts.subList(0, 5)).isEqualTo(blogIds(tag));
        assertThat(total(body, "questions")).isEqualTo(1);
        assertThat(ids(body, "questions")).containsExactly(question);
        assertThat(total(body, "places")).isEqualTo(1);
        Map<String, Object> place = JsonPath.read(body, "$.data.places.items[0]");
        assertThat(place).containsEntry("name", "Quán phở " + tag).containsEntry("reviewCount", 1)
                .containsEntry("rating", 4.0).containsEntry("closed", false).containsEntry("categoryIcon", icon("food"));
        // Happening now and coming first, soonest first, then past ones
        assertThat(ids(body, "events")).containsExactly(now1, soon, past);
        assertThat(ids(body, "phrases")).containsExactly(bag);
        assertThat(ids(body, "guides")).containsExactly(guide);
        assertThat(ids(body, "faqs")).containsExactly(faq);
        assertThat(((Number) JsonPath.read(body, "$.data.total")).longValue()).isEqualTo(6 + 1 + 1 + 3 + 1 + 1 + 1);
    }

    @Test
    void accentsCaseAndSpacesAroundTheTextAreIgnored() throws Exception {
        long post = blog(author, "POST", "PUBLISHED", "Chợ đêm Ueno " + tag, Instant.now());
        long pho = place("Phở Hà Nội " + tag, "food", "ACTIVE", null);
        for (String q : List.of("cho dem ueno " + tag, "CHỢ ĐÊM UENO " + tag.toUpperCase(), "  chợ đêm ueno " + tag + "  ")) {
            String body = search(null, q);
            assertThat((String) JsonPath.read(body, "$.data.q")).as(q).isEqualTo(q.strip());
            assertThat(ids(body, "posts")).as(q).containsExactly(post);
        }
        assertThat(ids(search(null, "pho ha noi " + tag), "places")).containsExactly(pho);
        // LIKE's own signs are plain text
        String body = search(null, "%" + tag);
        assertThat(total(body, "places")).isZero();
        assertThat(total(body, "posts")).isZero();
        assertThat(total(search(null, "_" + tag), "places")).isZero();
    }

    @Test
    void ownPostsBeingScannedAreFoundByTheirAuthorOnly() throws Exception {
        blog(author, "POST", "PUBLISHED", "Đã đăng " + tag, Instant.now());
        long mine = blog(viewer, "POST", "PROCESSING", "Đang quét " + tag, Instant.now());
        assertThat(ids(search(null, tag), "posts")).doesNotContain(mine).hasSize(1);
        assertThat(ids(search(author, tag), "posts")).doesNotContain(mine).hasSize(1);
        assertThat(ids(search(viewer, tag), "posts")).contains(mine).hasSize(2);
    }

    @Test
    void placesByNameFirstThenAddressOrCategoryAtTheStartOfAWord() throws Exception {
        long byAddress = place("Chỗ A", "food", "ACTIVE", "1-2-3 " + tag + " Arakawa");
        long laterName = place("Tiệm " + tag, "food", "ACTIVE", null);
        long startsWith = place(tag + " Mart", "super", "ACTIVE", null);
        assertThat(ids(search(null, tag), "places")).containsExactly(startsWith, laterName, byAddress);

        // Category names match at the start of a word, as the Map: "ngân hàng" finds the bank category, "an hang" not
        long before = repository.countPlaces("ngan hang");
        long beforeInside = repository.countPlaces("an hang");
        place("Fixture " + tag, "bank", "ACTIVE", null);
        assertThat(repository.countPlaces("ngan hang")).isEqualTo(before + 1);
        assertThat(repository.countPlaces("an hang")).isEqualTo(beforeInside);
    }

    @Test
    void eachPartShowsFiveAndCountsTheRest() throws Exception {
        Instant now = Instant.now();
        for (int i = 0; i < 7; i++) {
            place("Nơi " + tag + " " + i, "park", "ACTIVE", null);
            event("Sự kiện " + tag + " " + i, TODAY.plusDays(i), TODAY.plusDays(i), false);
            phrase("言葉" + i, "Từ " + tag + " " + i, false);
            blog(author, "QA", "PUBLISHED", "Câu hỏi " + tag + " " + i, now.minusSeconds(i));
        }
        String body = search(null, tag);
        for (String part : List.of("places", "events", "phrases", "questions")) {
            assertThat(total(body, part)).as(part).isEqualTo(7);
            assertThat(ids(body, part)).as(part).hasSize(SearchService.SECTION_SIZE);
        }
    }

    @Test
    void tooShortBlankAndTooLongTexts() throws Exception {
        blog(author, "POST", "PUBLISHED", "Ga " + tag, Instant.now());
        for (String q : List.of("a", "ở", " x ", "!!", "-")) {
            String body = search(null, q);
            assertThat((Boolean) JsonPath.read(body, "$.data.tooShort")).as(q).isTrue();
            assertThat(((Number) JsonPath.read(body, "$.data.total")).longValue()).as(q).isZero();
            assertThat(total(body, "posts")).as(q).isZero();
        }
        for (String q : List.of("", "   ")) {
            String body = search(null, q);
            assertThat((Boolean) JsonPath.read(body, "$.data.tooShort")).as(q).isFalse();
            assertThat((String) JsonPath.read(body, "$.data.q")).isEmpty();
            assertThat(((Number) JsonPath.read(body, "$.data.total")).longValue()).isZero();
        }
        String noParameter = mvc.perform(get("/api/search")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat((String) JsonPath.read(noParameter, "$.data.q")).isEmpty();
        // Two letters, or one Japanese character, are enough
        assertThat((Boolean) JsonPath.read(search(null, "ga"), "$.data.tooShort")).isFalse();
        assertThat((Boolean) JsonPath.read(search(null, "駅"), "$.data.tooShort")).isFalse();
        assertThat((Boolean) JsonPath.read(search(null, "ねこ"), "$.data.tooShort")).isFalse();

        assertThat(total(search(null, "x".repeat(100)), "posts")).isZero();
        String tooLong = mvc.perform(get("/api/search").param("q", "x".repeat(101)))
                .andExpect(status().isBadRequest())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat((String) JsonPath.read(tooLong, "$.message")).isEqualTo("Từ khóa tìm kiếm tối đa 100 ký tự.");
    }

    @Test
    void pageHasACleanAddressForEveryone() throws Exception {
        mvc.perform(get("/search")).andExpect(status().isOk()).andExpect(forwardedUrl("/pages/search.html"));
        mvc.perform(get("/search").param("q", "ga")).andExpect(status().isOk())
                .andExpect(forwardedUrl("/pages/search.html"));
        mvc.perform(get("/search").session(session(viewer))).andExpect(status().isOk());
    }

    // ---------------------------------------------------------------- fixtures

    private long user(String status) {
        String email = "search-" + UUID.randomUUID() + "@test.local";
        jdbc.update("""
                INSERT INTO users (email, display_name, role, status, is_adult_confirmed, created_at)
                VALUES (?, 'Search Test', 'USER', ?, TRUE, ?)""",
                email, status, Timestamp.from(Instant.now().minus(Duration.ofDays(30))));
        long id = jdbc.queryForObject("SELECT id FROM users WHERE email = ?", Long.class, email);
        users.add(id);
        return id;
    }

    private long blog(long authorId, String type, String status, String title, Instant createdAt) {
        jdbc.update("""
                INSERT INTO blogs (author_id, blog_type, title, content, status, created_at)
                VALUES (?, ?, ?, ?, ?, ?)""",
                authorId, type, title, "Nội dung của bài thử tìm kiếm.", status, Timestamp.from(createdAt));
        return jdbc.queryForObject("SELECT MAX(id) FROM blogs WHERE author_id = ? AND title = ?", Long.class,
                authorId, title);
    }

    private long place(String name, String category, String status, String address) {
        jdbc.update("""
                INSERT INTO places (name_vi, category_id, address, lat, lng, source, status, merged_into_id, created_by)
                VALUES (?, ?, ?, 35.7, 139.7, 'TEAM', ?, NULL, ?)""", name, category, address, status, author);
        long id = jdbc.queryForObject("SELECT MAX(id) FROM places WHERE name_vi = ?", Long.class, name);
        places.add(id);
        return id;
    }

    private void review(long authorId, long placeId, int rating) {
        jdbc.update("""
                INSERT INTO reviews (author_id, place_id, rating, content, status)
                VALUES (?, ?, ?, 'Review thử tìm kiếm', 'PUBLISHED')""", authorId, placeId, rating);
    }

    private long event(String title, LocalDate start, LocalDate end, boolean deleted) {
        jdbc.update("""
                INSERT INTO events (title, start_date, end_date, location_name, created_by, deleted_at)
                VALUES (?, ?, ?, 'Ueno', ?, ?)""", title, Date.valueOf(start), Date.valueOf(end), author,
                deleted ? Timestamp.from(Instant.now()) : null);
        long id = jdbc.queryForObject("SELECT MAX(id) FROM events WHERE title = ?", Long.class, title);
        events.add(id);
        return id;
    }

    private long phrase(String japanese, String meaning, boolean deleted) {
        jdbc.update("""
                INSERT INTO phrases (category_id, kind, japanese, romaji, meaning_vi, created_by, deleted_at)
                VALUES ('konbini', 'VOCAB', ?, '', ?, ?, ?)""", japanese, meaning, author,
                deleted ? Timestamp.from(Instant.now()) : null);
        long id = jdbc.queryForObject("SELECT MAX(id) FROM phrases WHERE meaning_vi = ?", Long.class, meaning);
        phrases.add(id);
        return id;
    }

    private long guide(String title, boolean published, boolean deleted) {
        jdbc.update("""
                INSERT INTO guide_items (stage_code, title, summary, published, created_by, deleted_at)
                VALUES ('ARR', ?, 'Tóm tắt thử tìm kiếm', ?, ?, ?)""", title, published, author,
                deleted ? Timestamp.from(Instant.now()) : null);
        long id = jdbc.queryForObject("SELECT MAX(id) FROM guide_items WHERE title = ?", Long.class, title);
        guides.add(id);
        return id;
    }

    private long faq(String question, boolean published) {
        jdbc.update("""
                INSERT INTO faq_entries (question, answer, topic_code, published, created_by, updated_by)
                VALUES (?, 'Trả lời thử tìm kiếm.', (SELECT MIN(code) FROM faq_topics), ?, ?, ?)""",
                question, published, author, author);
        long id = jdbc.queryForObject("SELECT MAX(id) FROM faq_entries WHERE question = ?", Long.class, question);
        faqs.add(id);
        return id;
    }

    private String icon(String category) {
        return jdbc.queryForObject("SELECT icon FROM place_categories WHERE id = ?", String.class, category);
    }

    // ---------------------------------------------------------------- calls

    private static MockHttpSession session(long userId) {
        AppUserPrincipal me = new AppUserPrincipal(userId, "u" + userId + "@test.local", "Search Test", "USER", "/");
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(SPRING_SECURITY_CONTEXT_KEY, new SecurityContextImpl(
                new UsernamePasswordAuthenticationToken(me, null, List.of(new SimpleGrantedAuthority("ROLE_USER")))));
        return session;
    }

    /** GET /api/search as a guest ({@code userId} null) or as that member. */
    private String search(Long userId, String q) throws Exception {
        MockHttpServletRequestBuilder request = get("/api/search").param("q", q);
        if (userId != null) {
            request.session(session(userId));
        }
        return mvc.perform(request).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    /** The Blog's own search, first page of 5, as "Xem tất cả" opens it. */
    private List<Long> blogIds(String q) throws Exception {
        String body = mvc.perform(get("/api/blogs").param("q", q).param("size", "5")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        List<Number> ids = JsonPath.read(body, "$.data.items[*].id");
        return ids.stream().map(Number::longValue).toList();
    }

    private static long total(String body, String part) {
        return ((Number) JsonPath.read(body, "$.data." + part + ".total")).longValue();
    }

    private static List<Long> ids(String body, String part) {
        List<Number> ids = JsonPath.read(body, "$.data." + part + ".items[*].id");
        return ids.stream().map(Number::longValue).toList();
    }
}
