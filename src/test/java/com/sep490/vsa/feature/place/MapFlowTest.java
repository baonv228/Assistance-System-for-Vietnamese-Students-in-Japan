package com.sep490.vsa.feature.place;

import com.sep490.vsa.common.security.AppUserPrincipal;
import com.sep490.vsa.feature.moderation.ImageModerator;
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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.web.context.HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The Map (UC-U14, UC-U15) end to end against MySQL ({@code asvsj_test}), with the map services replaced by a mock.
 * Names carry a random word so earlier runs never interfere.
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:mysql://localhost:3306/asvsj_test?createDatabaseIfNotExist=true&useUnicode=true&characterEncoding=utf8&connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true",
        "app.media.local-dir=target/test-uploads",
        "app.moderation.retry-initial-delay-ms=3600000"
})
@AutoConfigureMockMvc
@ActiveProfiles("local")
class MapFlowTest {

    private static final String CSRF = "test-csrf-token";

    @Autowired
    private MockMvc mvc;
    @Autowired
    private JdbcTemplate jdbc;
    @MockBean
    private ExternalPlaces map;
    @MockBean
    private ImageModerator moderator;

    private String word;
    private long owner;          // writes the reviews and the pins
    private long viewer;         // another User
    private long place;          // active, two published reviews
    private long merged;         // merged into place
    private long removed;
    private long pending;        // owner's pin waiting for a Moderator
    private long review1;
    private long review2;
    private long hiddenReview;

    @BeforeEach
    void setUp() {
        word = "mp" + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        owner = user("USER");
        viewer = user("USER");
        double lat = 33 + ThreadLocalRandom.current().nextDouble(3);
        double lng = 131 + ThreadLocalRandom.current().nextDouble(8);
        place = insertPlace("Quán " + word, "ACTIVE", lat, lng, "OSM", null);
        merged = insertPlace("Quán cũ " + word, "MERGED", lat, lng, "USER", place);
        removed = insertPlace("Quán đã gỡ " + word, "REMOVED", lat, lng, "TEAM", null);
        pending = insertPlace("Quán chờ duyệt " + word, "PENDING", lat, lng, "USER", null);
        review1 = insertReview(owner, place, 5, "PUBLISHED", Instant.now().minus(Duration.ofDays(2)));
        review2 = insertReview(viewer, place, 4, "PUBLISHED", Instant.now().minus(Duration.ofDays(1)));
        long third = user("USER");
        hiddenReview = insertReview(third, place, 1, "HIDDEN", Instant.now());
        when(map.engine()).thenReturn("OSM");
        when(map.enabled()).thenReturn(true);
        when(map.search(anyString())).thenReturn(List.of());
    }

    @Test
    void overviewShowsActivePlacesAndOnlyTheViewersOwnPendingPin() throws Exception {
        String guest = mvc.perform(get("/api/map")).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.signedIn").value(false))
                .andReturn().getResponse().getContentAsString();
        assertThat(guest).contains("\"id\":" + place + ",").doesNotContain("\"id\":" + pending + ",")
                .doesNotContain("\"id\":" + removed + ",").doesNotContain("\"id\":" + merged + ",");

        mvc.perform(get("/api/map").session(session(owner)))
                .andExpect(jsonPath("$.data.places[?(@.id == " + place + ")].reviewCount", contains(2)))
                .andExpect(jsonPath("$.data.places[?(@.id == " + place + ")].rating", contains(4.5)))
                .andExpect(jsonPath("$.data.places[?(@.id == " + pending + ")].status", contains("PENDING")));
        mvc.perform(get("/api/map").session(session(viewer)))
                .andExpect(jsonPath("$.data.places[*].id", not(hasItem((int) pending))));
    }

    @Test
    void detailsFollowAMergeAndHideRemovedPlaces() throws Exception {
        mvc.perform(get("/api/map/places/" + merged))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value((int) place))
                .andExpect(jsonPath("$.data.mergedFrom").value((int) merged))
                .andExpect(jsonPath("$.data.reviewCount").value(2))
                .andExpect(jsonPath("$.data.canAct").value(false))
                .andExpect(jsonPath("$.data.closedThreshold").value(5));
        mvc.perform(get("/api/map/places/" + removed)).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value(MapService.NOT_ON_MAP));
        mvc.perform(get("/api/map/places/" + pending).session(session(viewer))).andExpect(status().isNotFound());
        mvc.perform(get("/api/map/places/" + pending).session(session(owner))).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("PENDING"));
        mvc.perform(get("/api/map/places/" + place).session(session(owner)))
                .andExpect(jsonPath("$.data.canAct").value(true))
                .andExpect(jsonPath("$.data.myReviewId").value((int) review1));
    }

    @Test
    void reviewsAreNewestFirstAndHiddenOnesStayHidden() throws Exception {
        mvc.perform(get("/api/map/places/" + place + "/reviews"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[*].id", contains((int) review2, (int) review1)))
                .andExpect(jsonPath("$.data.total").value(2))
                .andExpect(jsonPath("$.data.items[0].author.displayName").value("Map Test"))
                .andExpect(jsonPath("$.data.items[*].id", not(hasItem((int) hiddenReview))));
    }

    @Test
    void likingSavingAndReportingNeedASignedInUser() throws Exception {
        mvc.perform(withCsrf(post("/api/reviews/" + review1 + "/like"))).andExpect(status().isUnauthorized());
        mvc.perform(withCsrf(post("/api/reviews/" + review1 + "/like").session(session(owner))))
                .andExpect(status().isBadRequest());                               // own review

        mvc.perform(withCsrf(post("/api/reviews/" + review1 + "/like").session(session(viewer))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.count").value(1));
        mvc.perform(withCsrf(post("/api/reviews/" + review1 + "/like").session(session(viewer))))
                .andExpect(jsonPath("$.data.count").value(1));                     // liking twice counts once
        mvc.perform(get("/api/map/places/" + place + "/reviews").session(session(viewer)))
                .andExpect(jsonPath("$.data.items[?(@.id == " + review1 + ")].liked", contains(true)));
        mvc.perform(withCsrf(delete("/api/reviews/" + review1 + "/like").session(session(viewer))))
                .andExpect(jsonPath("$.data.count").value(0));

        mvc.perform(withCsrf(post("/api/map/places/" + merged + "/save").session(session(viewer))))
                .andExpect(status().isOk());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM saved_places WHERE user_id = ? AND place_id = ?",
                Integer.class, viewer, place)).isEqualTo(1);                       // the merged place saves its target
        mvc.perform(get("/api/map/places/" + place).session(session(viewer)))
                .andExpect(jsonPath("$.data.saved").value(true));
        mvc.perform(withCsrf(delete("/api/map/places/" + place + "/save").session(session(viewer))))
                .andExpect(jsonPath("$.data.saved").value(false));

        long reason = jdbc.queryForObject("SELECT MIN(id) FROM reason_options WHERE scope = 'REPORT'", Long.class);
        mvc.perform(withCsrf(post("/api/reviews/" + review1 + "/report").session(session(viewer)))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reasonId\":" + reason + "}"))
                .andExpect(status().isOk());
        mvc.perform(withCsrf(post("/api/reviews/" + review1 + "/report").session(session(viewer)))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reasonId\":" + reason + "}"))
                .andExpect(status().isConflict());
        mvc.perform(withCsrf(post("/api/reviews/" + hiddenReview + "/report").session(session(viewer)))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reasonId\":" + reason + "}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void guestsSearchSenpaiOiPlacesOnly() throws Exception {
        mvc.perform(get("/api/map/search").param("q", word))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.external").value("LOGIN"))
                .andExpect(jsonPath("$.data.places[*].id", contains((int) place)));
        verify(map, never()).search(anyString());

        mvc.perform(get("/api/map/search").param("q", word).session(session(viewer)))
                .andExpect(jsonPath("$.data.external").value("ON"))
                .andExpect(jsonPath("$.data.places[0].id").value((int) place))
                .andExpect(jsonPath("$.data.places[0].lat").isNumber());
        // A category matches at the start of a word: "ga" is "Ga tàu", not "ngân hàng"
        mvc.perform(get("/api/map/search").param("q", "ga"))
                .andExpect(jsonPath("$.data.categories[*].id", not(hasItem("bank"))));
    }

    // ---------------------------------------------------------------- helpers

    private long insertPlace(String name, String status, double lat, double lng, String source, Long mergedInto) {
        jdbc.update("""
                INSERT INTO places (name_vi, category_id, lat, lng, source, status, merged_into_id, created_by)
                VALUES (?, 'food', ?, ?, ?, ?, ?, ?)""", name, lat, lng, source, status, mergedInto, owner);
        return jdbc.queryForObject("SELECT id FROM places WHERE name_vi = ?", Long.class, name);
    }

    private long insertReview(long author, long placeId, int rating, String status, Instant at) {
        jdbc.update("""
                INSERT INTO reviews (author_id, place_id, rating, content, status, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?)""", author, placeId, rating, "Review " + word, status,
                Timestamp.from(at), Timestamp.from(at));
        return jdbc.queryForObject("SELECT id FROM reviews WHERE author_id = ? AND place_id = ?", Long.class,
                author, placeId);
    }

    private long user(String role) {
        String email = "map-" + UUID.randomUUID() + "@test.local";
        jdbc.update("""
                INSERT INTO users (email, display_name, role, status, is_adult_confirmed, created_at)
                VALUES (?, 'Map Test', ?, 'ACTIVE', TRUE, ?)""",
                email, role, Timestamp.from(Instant.now().minus(Duration.ofDays(30))));
        return jdbc.queryForObject("SELECT id FROM users WHERE email = ?", Long.class, email);
    }

    private static MockHttpServletRequestBuilder withCsrf(MockHttpServletRequestBuilder request) {
        return request.cookie(new Cookie("XSRF-TOKEN", CSRF)).header("X-XSRF-TOKEN", CSRF);
    }

    private static MockHttpSession session(long userId) {
        AppUserPrincipal me = new AppUserPrincipal(userId, "u" + userId + "@test.local", "Map Test", "USER", "/");
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(SPRING_SECURITY_CONTEXT_KEY, new SecurityContextImpl(
                new UsernamePasswordAuthenticationToken(me, null, List.of(new SimpleGrantedAuthority("ROLE_USER")))));
        return session;
    }
}
