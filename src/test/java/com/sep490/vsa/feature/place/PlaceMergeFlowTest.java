package com.sep490.vsa.feature.place;

import com.sep490.vsa.common.security.AppUserPrincipal;
import com.sep490.vsa.feature.moderation.ImageModerator;
import com.sep490.vsa.feature.place.PlaceModerationDtos.MergePair;
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
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.web.context.HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** "Gộp trùng lặp" (UC-M09) against MySQL ({@code asvsj_test}). */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:mysql://localhost:3306/asvsj_test?createDatabaseIfNotExist=true&useUnicode=true&characterEncoding=utf8&connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true",
        "app.media.local-dir=target/test-uploads",
        "app.moderation.retry-initial-delay-ms=3600000",
        "app.places.osm.enabled=false"
})
@AutoConfigureMockMvc
@ActiveProfiles("local")
class PlaceMergeFlowTest {

    private static final String CSRF = "test-csrf-token";

    @Autowired
    private MockMvc mvc;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private PlaceMergeService merges;
    @MockBean
    private ImageModerator moderator;

    private String word;
    private long mod;
    private double lat;
    private double lng;

    @BeforeEach
    void setUp() {
        word = "mg" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        mod = user("MODERATOR");
        lat = 33 + ThreadLocalRandom.current().nextDouble(3);
        lng = 131 + ThreadLocalRandom.current().nextDouble(8);
    }

    @Test
    void theQueuePairsSimilarNamesCloseByUntilMarkedNotTheSame() throws Exception {
        long a = place("Lawson " + word, "Lawson " + word, "konbini", lat, lng, "OSM");
        long b = place("Cửa hàng Lawson " + word, "Lawson " + word, "konbini", lat + 0.0002, lng, "USER");
        long c = place("Lawson " + word + " Nippori", null, "konbini", lat + 0.0013, lng, "TEAM");
        place("Lawson " + word, null, "konbini", lat + 0.01, lng, "TEAM");         // 1.1 km: not a pair

        assertThat(pair(a, b)).hasValueSatisfying(p -> {
            assertThat(p.level()).isEqualTo("HIGH");
            assertThat(p.distance()).isEqualTo(22);
        });
        assertThat(pair(a, c)).hasValueSatisfying(p -> assertThat(p.level()).isEqualTo("MEDIUM"));
        assertThat(merges.pairs().stream().filter(p -> involves(p, a) && p.distance() > 200)).isEmpty();
        // "Cao" pairs come first
        List<MergePair> all = merges.pairs();
        assertThat(all.indexOf(pair(a, b).orElseThrow())).isLessThan(all.indexOf(pair(a, c).orElseThrow()));

        act(post("/api/moderator/merges/dismiss"), "{\"aId\":" + b + ",\"bId\":" + a + "}").andExpect(status().isOk());
        assertThat(pair(a, b)).isEmpty();
        act(post("/api/moderator/merges/dismiss"), "{\"aId\":" + a + ",\"bId\":" + b + "}").andExpect(status().isConflict());
        mvc.perform(get("/api/moderator/merges").session(session(mod, "MODERATOR"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.size").value(10));
    }

    @Test
    void mergingMovesEverythingAndKeepsTheNewerReviewOfOnePerson() throws Exception {
        long kept = place("Phở Hà Nội " + word, null, "food", lat, lng, "TEAM");
        long other = place("Pho Hanoi " + word, "Pho Hanoi " + word, "food", lat + 0.0001, lng, "USER");
        long older = place("Phở cũ " + word, null, "food", lat, lng, "TEAM");
        jdbc.update("UPDATE places SET status = 'MERGED', merged_into_id = ? WHERE id = ?", other, older);
        long both = user("USER");
        long onlyKept = user("USER");
        long saver = user("USER");
        long oldReview = review(both, kept, Instant.now().minus(Duration.ofDays(9)));
        long newReview = review(both, other, Instant.now().minus(Duration.ofDays(1)));
        long keptReview = review(onlyKept, kept, Instant.now().minus(Duration.ofDays(3)));
        jdbc.update("INSERT INTO saved_places (user_id, place_id) VALUES (?, ?)", saver, other);
        jdbc.update("INSERT INTO guide_items (stage_code, title, place_id) VALUES ('ARR', ?, ?)", "Ăn phở " + word, other);

        mvc.perform(get("/api/moderator/merges/compare").param("a", String.valueOf(kept)).param("b", String.valueOf(other))
                        .session(session(mod, "MODERATOR")))
                .andExpect(jsonPath("$.data.b.counts.reviews").value(1))
                .andExpect(jsonPath("$.data.b.counts.saves").value(1))
                .andExpect(jsonPath("$.data.b.counts.guideItems").value(1))
                .andExpect(jsonPath("$.data.sameAuthors").value(1));

        String decision = "{\"keptId\":" + kept + ",\"mergedId\":" + other + ",\"fields\":{\"nameEn\":\"MERGED\"},"
                + "\"confirmed\":%s}";
        act(post("/api/moderator/merges"), decision.formatted("false")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Hãy tick ô xác nhận trước khi gộp."));
        act(post("/api/moderator/merges"), decision.formatted("true")).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.placeId").value((int) kept));

        assertThat(jdbc.queryForObject("SELECT CONCAT(status, '/', merged_into_id) FROM places WHERE id = ?", String.class, other))
                .isEqualTo("MERGED/" + kept);
        assertThat(jdbc.queryForObject("SELECT CONCAT(name_vi, '/', name_en) FROM places WHERE id = ?", String.class, kept))
                .isEqualTo("Phở Hà Nội " + word + "/Pho Hanoi " + word);
        assertThat(jdbc.queryForList("SELECT id FROM reviews WHERE place_id = ? ORDER BY id", Long.class, kept))
                .containsExactly(newReview, keptReview);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM reviews WHERE id = ?", Integer.class, oldReview)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM saved_places WHERE place_id = ?", Integer.class, kept)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT place_id FROM guide_items WHERE title = ?", Long.class, "Ăn phở " + word))
                .isEqualTo(kept);
        assertThat(jdbc.queryForObject("SELECT merged_into_id FROM places WHERE id = ?", Long.class, older)).isEqualTo(kept);
        mvc.perform(get("/api/map/places/" + other)).andExpect(jsonPath("$.data.id").value((int) kept))
                .andExpect(jsonPath("$.data.reviewCount").value(2));
        mvc.perform(get("/api/moderator/places/" + kept).session(session(mod, "MODERATOR")))
                .andExpect(jsonPath("$.data.history[0].text").value("Nhận gộp từ địa điểm #" + other + " (1 review) · "
                        + "Tên tiếng Anh: (trống) → Pho Hanoi " + word));

        // Final: the merged place cannot be merged or compared again
        act(post("/api/moderator/merges"), decision.formatted("true")).andExpect(status().isConflict());
    }

    @Test
    void placesCanBeChosenByHandWithTheirDistance() throws Exception {
        long a = place("Ga Tabata " + word, null, "station", lat, lng, "OSM");
        long b = place("Tabata Station " + word, null, "station", lat + 0.003, lng, "TEAM");
        mvc.perform(get("/api/moderator/merges/search").param("q", "tabata " + word).param("near", String.valueOf(a))
                        .session(session(mod, "MODERATOR")))
                .andExpect(jsonPath("$.data[0].id").value((int) b))
                .andExpect(jsonPath("$.data[0].distance").value(334));
        mvc.perform(get("/api/moderator/merges/compare").param("a", String.valueOf(a)).param("b", String.valueOf(a))
                .session(session(mod, "MODERATOR"))).andExpect(status().isBadRequest());
    }

    // ---------------------------------------------------------------- helpers

    private Optional<MergePair> pair(long x, long y) {
        return merges.pairs().stream().filter(p -> involves(p, x) && involves(p, y)).findFirst();
    }

    private static boolean involves(MergePair p, long id) {
        return p.a().id() == id || p.b().id() == id;
    }

    private long place(String nameVi, String nameEn, String category, double atLat, double atLng, String source) {
        jdbc.update("""
                INSERT INTO places (name_vi, name_en, category_id, lat, lng, source, status, created_by)
                VALUES (?, ?, ?, ?, ?, ?, 'ACTIVE', ?)""", nameVi, nameEn, category, atLat, atLng, source, mod);
        return jdbc.queryForObject("SELECT MAX(id) FROM places WHERE name_vi = ?", Long.class, nameVi);
    }

    private long review(long author, long placeId, Instant at) {
        jdbc.update("""
                INSERT INTO reviews (author_id, place_id, rating, content, status, created_at, updated_at)
                VALUES (?, ?, 4, 'Phở ngon, nước dùng trong.', 'PUBLISHED', ?, ?)""",
                author, placeId, Timestamp.from(at), Timestamp.from(at));
        return jdbc.queryForObject("SELECT id FROM reviews WHERE author_id = ? AND place_id = ?", Long.class, author, placeId);
    }

    private ResultActions act(MockHttpServletRequestBuilder request, String json) throws Exception {
        return mvc.perform(request.cookie(new Cookie("XSRF-TOKEN", CSRF)).header("X-XSRF-TOKEN", CSRF)
                .session(session(mod, "MODERATOR")).contentType(MediaType.APPLICATION_JSON).content(json));
    }

    private long user(String role) {
        String email = "merge-" + UUID.randomUUID() + "@test.local";
        jdbc.update("""
                INSERT INTO users (email, display_name, role, status, is_adult_confirmed, created_at)
                VALUES (?, 'Merge Test', ?, 'ACTIVE', TRUE, ?)""",
                email, role, Timestamp.from(Instant.now().minus(Duration.ofDays(30))));
        return jdbc.queryForObject("SELECT id FROM users WHERE email = ?", Long.class, email);
    }

    private static MockHttpSession session(long userId, String role) {
        AppUserPrincipal me = new AppUserPrincipal(userId, "u" + userId + "@test.local", "Merge Test", role, "/");
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(SPRING_SECURITY_CONTEXT_KEY, new SecurityContextImpl(
                new UsernamePasswordAuthenticationToken(me, null, List.of(new SimpleGrantedAuthority("ROLE_" + role)))));
        return session;
    }
}
