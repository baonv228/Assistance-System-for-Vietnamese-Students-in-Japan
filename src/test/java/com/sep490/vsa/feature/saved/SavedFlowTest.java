package com.sep490.vsa.feature.saved;

import com.sep490.vsa.common.security.AppUserPrincipal;
import com.sep490.vsa.feature.moderation.ImageModerator;
import com.sep490.vsa.feature.moderation.ImageModerator.Verdict;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.empty;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.security.web.context.HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.forwardedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrlPattern;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * "Đã lưu" end to end against MySQL (own database {@code asvsj_test}, like QaFlowTest). Every test uses fresh users,
 * so saved lists of earlier runs or other test classes never show up.
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:mysql://localhost:3306/asvsj_test?createDatabaseIfNotExist=true&useUnicode=true&characterEncoding=utf8&connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true",
        "app.media.local-dir=target/test-uploads",
        "app.moderation.retry-initial-delay-ms=3600000",
        "app.places.osm.enabled=false"              // ASVSJ places only: no calls to OpenStreetMap from the tests
})
@AutoConfigureMockMvc
@ActiveProfiles("local")
class SavedFlowTest {

    private static final String CSRF = "test-csrf-token";

    @Autowired
    private MockMvc mvc;
    @Autowired
    private JdbcTemplate jdbc;
    @MockBean
    private ImageModerator moderator;

    private String word;
    private long member;
    private long author;
    private long topic;

    @BeforeEach
    void setUp() {
        when(moderator.engine()).thenReturn("OPENAI");
        when(moderator.check(any(byte[].class))).thenReturn(new Verdict("PASS", "{}"));
        word = "sv" + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        member = user("USER");
        author = user("USER");
        topic = jdbc.queryForObject("SELECT id FROM blog_topics WHERE name = 'Visa và cư trú'", Long.class);
    }

    @Test
    void onlySignedInMembersHaveASavedList() throws Exception {
        mvc.perform(get("/api/me/saved")).andExpect(status().isUnauthorized());

        long moderator = user("MODERATOR");
        mvc.perform(get("/api/me/saved").session(session(moderator, "MODERATOR")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value("Bạn không có quyền thực hiện thao tác này."));

        mvc.perform(get("/api/me/saved").session(session(member, "USER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.places", empty()))
                .andExpect(jsonPath("$.data.posts", empty()))
                .andExpect(jsonPath("$.data.questions", empty()))
                .andExpect(jsonPath("$.data.counts.total").value(0));
        mvc.perform(get("/api/me/profile").session(session(member, "USER")))
                .andExpect(jsonPath("$.data.savedCount").value(0));
    }

    @Test
    void savedPostsAndQuestionsAreListedSeparatelyNewestFirst() throws Exception {
        long older = write("Kinh nghiệm thuê nhà " + word);
        long newer = write("Đi làm thêm " + word);
        long question = ask("Gia hạn visa " + word + "?");
        save("blogs", older);
        save("blogs", newer);
        save("questions", question);
        // saved_blogs.created_at has whole seconds: make the order explicit
        savedAt(older, Duration.ofMinutes(5));
        savedAt(newer, Duration.ofMinutes(1));

        mvc.perform(get("/api/me/saved").session(session(member, "USER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.posts[*].id").value(contains((int) newer, (int) older)))
                .andExpect(jsonPath("$.data.posts[0].type").value("POST"))
                .andExpect(jsonPath("$.data.posts[0].title").value("Đi làm thêm " + word))
                .andExpect(jsonPath("$.data.posts[0].author.displayName").value("Saved Test"))
                .andExpect(jsonPath("$.data.posts[0].topics[0].name").value("Visa và cư trú"))
                .andExpect(jsonPath("$.data.questions[*].id").value(contains((int) question)))
                .andExpect(jsonPath("$.data.questions[0].type").value("QA"))
                .andExpect(jsonPath("$.data.questions[0].solved").value(false))
                .andExpect(jsonPath("$.data.counts.posts").value(2))
                .andExpect(jsonPath("$.data.counts.questions").value(1))
                .andExpect(jsonPath("$.data.counts.total").value(3));
        mvc.perform(get("/api/me/profile").session(session(member, "USER")))
                .andExpect(jsonPath("$.data.savedCount").value(3));

        // Another member's saves are not listed
        mvc.perform(get("/api/me/saved").session(session(author, "USER")))
                .andExpect(jsonPath("$.data.counts.total").value(0));

        // Bỏ lưu (the existing save API) takes it off the list
        mvc.perform(withCsrf(delete("/api/blogs/" + older + "/save"), member)).andExpect(status().isOk());
        mvc.perform(withCsrf(delete("/api/questions/" + question + "/save"), member)).andExpect(status().isOk());
        mvc.perform(get("/api/me/saved").session(session(member, "USER")))
                .andExpect(jsonPath("$.data.posts[*].id").value(contains((int) newer)))
                .andExpect(jsonPath("$.data.questions", empty()))
                .andExpect(jsonPath("$.data.counts.total").value(1));
    }

    @Test
    void postsThatAreNoLongerPublishedAreLeftOut() throws Exception {
        long hidden = write("Bài bị ẩn " + word);
        long removed = write("Bài bị gỡ " + word);
        long kept = write("Bài còn hiện " + word);
        save("blogs", hidden);
        save("blogs", removed);
        save("blogs", kept);
        jdbc.update("UPDATE blogs SET status = 'HIDDEN' WHERE id = ?", hidden);
        jdbc.update("UPDATE blogs SET status = 'REMOVED', removed_at = NOW() WHERE id = ?", removed);

        mvc.perform(get("/api/me/saved").session(session(member, "USER")))
                .andExpect(jsonPath("$.data.posts[*].id").value(contains((int) kept)))
                .andExpect(jsonPath("$.data.counts.total").value(1));
        mvc.perform(get("/api/me/profile").session(session(member, "USER")))
                .andExpect(jsonPath("$.data.savedCount").value(1));

        // Restored by a Moderator: it comes back, the save was kept
        jdbc.update("UPDATE blogs SET status = 'PUBLISHED' WHERE id = ?", hidden);
        mvc.perform(get("/api/me/saved").session(session(member, "USER")))
                .andExpect(jsonPath("$.data.counts.posts").value(2));
    }

    @Test
    void savedPlacesShowOnlyPlacesStillOnTheMap() throws Exception {
        long konbini = place("Lawson " + word, "konbini", "ACTIVE");
        long food = place("Quán phở " + word, "food", "ACTIVE");
        long removed = place("Quán đã gỡ " + word, "food", "ACTIVE");
        long merged = place("Phở trùng " + word, "food", "ACTIVE");
        savePlace(konbini, Duration.ofMinutes(10));
        savePlace(food, Duration.ofMinutes(5));
        savePlace(removed, Duration.ofMinutes(3));
        savePlace(merged, Duration.ofMinutes(1));
        jdbc.update("UPDATE places SET status = 'REMOVED', removed_reason = 'test' WHERE id = ?", removed);
        // Merged into the phở place: listed once, as the phở place
        jdbc.update("UPDATE places SET status = 'MERGED', merged_into_id = ? WHERE id = ?", food, merged);

        mvc.perform(get("/api/me/saved").session(session(member, "USER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.places[*].id").value(contains((int) food, (int) konbini)))
                .andExpect(jsonPath("$.data.places[0].name").value("Quán phở " + word))
                .andExpect(jsonPath("$.data.places[0].categoryId").value("food"))
                .andExpect(jsonPath("$.data.places[0].categoryName").value("Quán ăn"))
                .andExpect(jsonPath("$.data.places[0].icon").value("restaurant"))
                .andExpect(jsonPath("$.data.places[1].categoryName").value("Cửa hàng tiện lợi"))
                .andExpect(jsonPath("$.data.counts.places").value(2))
                .andExpect(jsonPath("$.data.counts.total").value(2));
        mvc.perform(get("/api/me/profile").session(session(member, "USER")))
                .andExpect(jsonPath("$.data.savedCount").value(2));
    }

    @Test
    void savedPageHasACleanAddressForSignedInUsers() throws Exception {
        mvc.perform(get("/saved")).andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/login?next=%2Fsaved"));
        mvc.perform(get("/saved").session(session(member, "USER")))
                .andExpect(status().isOk())
                .andExpect(forwardedUrl("/pages/saved.html"));
        mvc.perform(get("/pages/saved.html").session(session(member, "USER")))
                .andExpect(status().isMovedPermanently())
                .andExpect(redirectedUrlPattern("/saved*"));
        mvc.perform(get("/profile").session(session(member, "USER"))).andExpect(status().isOk());
        mvc.perform(get("/assets/js/pages/profile.js"))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content()
                        .string(containsString("href=\"/saved\"")));
    }

    // ---------------------------------------------------------------- helpers

    private long user(String role) {
        String email = "saved-" + UUID.randomUUID() + "@test.local";
        jdbc.update("""
                INSERT INTO users (email, display_name, role, status, is_adult_confirmed, created_at)
                VALUES (?, 'Saved Test', ?, 'ACTIVE', TRUE, ?)""",
                email, role, Timestamp.from(Instant.now().minus(Duration.ofDays(30))));
        return jdbc.queryForObject("SELECT id FROM users WHERE email = ?", Long.class, email);
    }

    private long place(String name, String category, String status) {
        double lat = 43 + ThreadLocalRandom.current().nextDouble(0.5);
        double lng = 141 + ThreadLocalRandom.current().nextDouble(0.5);
        jdbc.update("""
                INSERT INTO places (name_vi, category_id, lat, lng, source, status, created_by)
                VALUES (?, ?, ?, ?, 'TEAM', ?, ?)""", name, category, lat, lng, status, author);
        return jdbc.queryForObject("SELECT id FROM places WHERE name_vi = ?", Long.class, name);
    }

    private void savePlace(long placeId, Duration ago) {
        jdbc.update("INSERT INTO saved_places (user_id, place_id, saved_at) VALUES (?, ?, ?)",
                member, placeId, Timestamp.from(Instant.now().minus(ago)));
    }

    private void savedAt(long blogId, Duration ago) {
        jdbc.update("UPDATE saved_blogs SET created_at = ? WHERE blog_id = ? AND user_id = ?",
                Timestamp.from(Instant.now().minus(ago)), blogId, member);
    }

    private void save(String kind, long id) throws Exception {
        mvc.perform(withCsrf(post("/api/" + kind + "/" + id + "/save"), member)).andExpect(status().isOk());
    }

    private static MockHttpSession session(long userId, String role) {
        AppUserPrincipal me = new AppUserPrincipal(userId, "u" + userId + "@test.local", "Saved Test", role, "/");
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(SPRING_SECURITY_CONTEXT_KEY, new SecurityContextImpl(
                new UsernamePasswordAuthenticationToken(me, null, List.of(new SimpleGrantedAuthority("ROLE_" + role)))));
        return session;
    }

    private static MockHttpServletRequestBuilder withCsrf(MockHttpServletRequestBuilder request, long userId) {
        return request.session(session(userId, "USER")).cookie(new Cookie("XSRF-TOKEN", CSRF))
                .header("X-XSRF-TOKEN", CSRF);
    }

    private long write(String title) throws Exception {
        return created(mvc.perform(withCsrf(post("/api/blogs"), author).contentType(MediaType.APPLICATION_JSON)
                .content("{\"title\":\"" + title + "\",\"content\":\"nội dung\",\"topicIds\":[" + topic + "]}")));
    }

    private long ask(String title) throws Exception {
        return created(mvc.perform(withCsrf(post("/api/questions"), author).contentType(MediaType.APPLICATION_JSON)
                .content("{\"title\":\"" + title + "\",\"content\":\"\",\"topicIds\":[" + topic + "]}")));
    }

    private static long created(ResultActions result) throws Exception {
        String body = result.andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return Long.parseLong(body.replaceAll(".*\"data\":\\{\"id\":(\\d+).*", "$1"));
    }
}
