package com.sep490.vsa.feature.user;

import com.sep490.vsa.common.security.AppUserPrincipal;
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

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.security.web.context.HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.forwardedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Public profile (UC-U07) against MySQL ({@code asvsj_test}). */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:mysql://localhost:3306/asvsj_test?createDatabaseIfNotExist=true&useUnicode=true&characterEncoding=utf8&connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true",
        "app.moderation.retry-initial-delay-ms=3600000"
})
@AutoConfigureMockMvc
@ActiveProfiles("local")
class PublicProfileFlowTest {

    @Autowired
    private MockMvc mvc;
    @Autowired
    private JdbcTemplate jdbc;

    private String word;
    private long member;
    private long other;
    private long place;

    @BeforeEach
    void setUp() {
        word = UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        member = user("USER", 150);
        other = user("USER", 0);
        jdbc.update("""
                INSERT INTO places (name_vi, category_id, lat, lng, source, status, created_by)
                VALUES (?, 'food', 35.7, 139.7, 'TEAM', 'ACTIVE', ?)""", "Quán " + word, member);
        place = jdbc.queryForObject("SELECT id FROM places WHERE name_vi = ?", Long.class, "Quán " + word);
    }

    @Test
    void showsOnlyWhatEveryoneMaySee() throws Exception {
        long shown = review(member, place, "PUBLISHED", 4);
        jdbc.update("""
                INSERT INTO places (name_vi, category_id, lat, lng, source, status, created_by)
                VALUES (?, 'food', 35.7, 139.7, 'TEAM', 'REMOVED', ?)""", "Quán gỡ " + word, member);
        long removedPlace = jdbc.queryForObject("SELECT id FROM places WHERE name_vi = ?", Long.class, "Quán gỡ " + word);
        review(member, removedPlace, "PUBLISHED", 100);
        long post = blog(member, "POST", "PUBLISHED", 3);
        blog(member, "POST", "HIDDEN", 100);
        long answerer = blog(other, "QA", "PUBLISHED", 0);
        jdbc.update("INSERT INTO comments (blog_id, author_id, content, status, like_count) VALUES (?, ?, 'Trả lời', 'PUBLISHED', 2)",
                answerer, member);
        long answer = jdbc.queryForObject("SELECT id FROM comments WHERE blog_id = ?", Long.class, answerer);
        jdbc.update("UPDATE blogs SET best_comment_id = ? WHERE id = ?", answer, answerer);
        long question = blog(member, "QA", "PUBLISHED", 1);

        String body = mvc.perform(get("/api/users/" + member)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.reviewCount").value(1))
                .andExpect(jsonPath("$.data.postCount").value(1))
                .andExpect(jsonPath("$.data.questionCount").value(1))
                .andExpect(jsonPath("$.data.bestAnswerCount").value(1))
                .andExpect(jsonPath("$.data.likesReceived").value(4 + 3 + 1 + 2))
                .andExpect(jsonPath("$.data.rankName").isNotEmpty())
                .andExpect(jsonPath("$.data.roleLabel").isEmpty())
                .andReturn().getResponse().getContentAsString();
        assertThat(body).doesNotContain("@test.local", "points", "area", "stage");

        mvc.perform(get("/api/users/" + member + "/reviews"))
                .andExpect(jsonPath("$.data.items[*].id", contains((int) shown)))
                .andExpect(jsonPath("$.data.items[0].placeId").value((int) place))
                .andExpect(jsonPath("$.data.hasMore").value(false));
        mvc.perform(get("/api/users/" + member + "/posts"))
                .andExpect(jsonPath("$.data.items[*].id", contains((int) post)));
        mvc.perform(get("/api/users/" + member + "/questions"))
                .andExpect(jsonPath("$.data.items[*].id", contains((int) question)));
        // Own page: the same lists, nothing hidden is added
        mvc.perform(get("/api/users/" + member + "/posts").session(session(member, "USER")))
                .andExpect(jsonPath("$.data.items", hasSize(1)));
    }

    @Test
    void theMessageButtonFollowsWhoIsLooking() throws Exception {
        long moderator = user("MODERATOR", 0);
        mvc.perform(get("/api/users/" + member)).andExpect(jsonPath("$.data.canMessage").value(true))
                .andExpect(jsonPath("$.data.self").value(false));
        mvc.perform(get("/api/users/" + member).session(session(other, "USER")))
                .andExpect(jsonPath("$.data.canMessage").value(true));
        mvc.perform(get("/api/users/" + member).session(session(member, "USER")))
                .andExpect(jsonPath("$.data.canMessage").value(false))
                .andExpect(jsonPath("$.data.self").value(true));
        mvc.perform(get("/api/users/" + member).session(session(moderator, "MODERATOR")))
                .andExpect(jsonPath("$.data.canMessage").value(false));
        // A staff profile: its role instead of a rank, and no messages
        mvc.perform(get("/api/users/" + moderator).session(session(other, "USER")))
                .andExpect(jsonPath("$.data.canMessage").value(false))
                .andExpect(jsonPath("$.data.rankName").isEmpty())
                .andExpect(jsonPath("$.data.roleLabel").value("Moderator"));
    }

    @Test
    void lockedDeletedAndUnknownAccountsAreNotFound() throws Exception {
        mvc.perform(get("/api/users/" + member)).andExpect(status().isOk());
        for (String gone : List.of("LOCKED", "DELETED")) {
            jdbc.update("UPDATE users SET status = ? WHERE id = ?", gone, member);
            mvc.perform(get("/api/users/" + member)).andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.message").value(PublicProfileService.NOT_FOUND));
            mvc.perform(get("/api/users/" + member + "/reviews")).andExpect(status().isNotFound());
        }
        mvc.perform(get("/api/users/999999999")).andExpect(status().isNotFound());
    }

    @Test
    void listsComeInPagesAndThePageHasItsAddress() throws Exception {
        for (int i = 0; i <= PublicProfileService.PAGE_SIZE; i++) {
            blog(member, "POST", "PUBLISHED", 0);
        }
        mvc.perform(get("/api/users/" + member + "/posts"))
                .andExpect(jsonPath("$.data.items", hasSize(PublicProfileService.PAGE_SIZE)))
                .andExpect(jsonPath("$.data.hasMore").value(true));
        mvc.perform(get("/api/users/" + member + "/posts").param("page", "1"))
                .andExpect(jsonPath("$.data.items", hasSize(1)))
                .andExpect(jsonPath("$.data.hasMore").value(false));
        mvc.perform(get("/users/" + member)).andExpect(forwardedUrl("/pages/user-profile.html"));
    }

    // ---------------------------------------------------------------- helpers

    private long user(String role, int points) {
        String email = "profile-" + UUID.randomUUID() + "@test.local";
        jdbc.update("""
                INSERT INTO users (email, display_name, role, status, is_adult_confirmed, points)
                VALUES (?, 'Hồ Sơ Test', ?, 'ACTIVE', TRUE, ?)""", email, role, points);
        return jdbc.queryForObject("SELECT id FROM users WHERE email = ?", Long.class, email);
    }

    private long review(long author, long placeId, String reviewStatus, int likes) {
        jdbc.update("""
                INSERT INTO reviews (author_id, place_id, rating, content, status, like_count)
                VALUES (?, ?, 5, 'Quán ngon, giá sinh viên.', ?, ?)""", author, placeId, reviewStatus, likes);
        return jdbc.queryForObject("SELECT id FROM reviews WHERE author_id = ? AND place_id = ?",
                Long.class, author, placeId);
    }

    private long blog(long author, String type, String blogStatus, int likes) {
        String title = "Bài " + UUID.randomUUID();
        jdbc.update("""
                INSERT INTO blogs (author_id, blog_type, title, content, status, like_count)
                VALUES (?, ?, ?, 'Nội dung bài viết.', ?, ?)""", author, type, title, blogStatus, likes);
        return jdbc.queryForObject("SELECT id FROM blogs WHERE title = ?", Long.class, title);
    }

    private MockHttpSession session(long id, String role) {
        AppUserPrincipal me = new AppUserPrincipal(id, "u@test.local", "Test", role, "/");
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(SPRING_SECURITY_CONTEXT_KEY, new SecurityContextImpl(
                new UsernamePasswordAuthenticationToken(me, null, List.of(new SimpleGrantedAuthority("ROLE_" + role)))));
        return session;
    }
}
