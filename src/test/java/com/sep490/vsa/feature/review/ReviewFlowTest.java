package com.sep490.vsa.feature.review;

import com.sep490.vsa.common.security.AppUserPrincipal;
import com.sep490.vsa.feature.moderation.ImageModerator;
import com.sep490.vsa.feature.moderation.ImageModerator.Verdict;
import com.sep490.vsa.feature.place.ExternalPlaces;
import com.sep490.vsa.feature.place.ExternalPlaces.Hit;
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
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.when;
import static org.springframework.security.web.context.HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Write / Edit / Delete Review (UC-U16..18) end to end against MySQL ({@code asvsj_test}); map services mocked. */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:mysql://localhost:3306/asvsj_test?createDatabaseIfNotExist=true&useUnicode=true&characterEncoding=utf8&connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true",
        "app.media.local-dir=target/test-uploads",
        "app.moderation.retry-initial-delay-ms=3600000"
})
@AutoConfigureMockMvc
@ActiveProfiles("local")
class ReviewFlowTest {

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
    private long author;
    private long place;
    private double lat;
    private double lng;

    @BeforeEach
    void setUp() {
        word = "rv" + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        author = user("USER", 30);
        lat = 33 + ThreadLocalRandom.current().nextDouble(3);
        lng = 131 + ThreadLocalRandom.current().nextDouble(8);
        jdbc.update("""
                INSERT INTO places (name_vi, category_id, lat, lng, source, status, created_by)
                VALUES (?, 'food', ?, ?, 'TEAM', 'ACTIVE', ?)""", "Quán " + word, lat, lng, author);
        place = jdbc.queryForObject("SELECT id FROM places WHERE name_vi = ?", Long.class, "Quán " + word);
        when(map.engine()).thenReturn("OSM");
        when(map.enabled()).thenReturn(true);
    }

    @Test
    void writeEditAndDeleteAReview() throws Exception {
        int before = points(author);
        long id = created(send(post("/api/reviews"), author,
                "{\"placeId\":" + place + ",\"rating\":4,\"content\":\"Quán ngon, chủ quán dễ thương.\"}")
                .andExpect(jsonPath("$.message").value("Đã đăng review. +10 điểm."))
                .andExpect(jsonPath("$.data.status").value("PUBLISHED")));
        assertThat(points(author)).isEqualTo(before + 10);
        mvc.perform(get("/api/map/places/" + place)).andExpect(jsonPath("$.data.reviewCount").value(1))
                .andExpect(jsonPath("$.data.rating").value(4.0));

        // One review per place: the form opens the existing one, a second one is refused
        mvc.perform(get("/api/reviews/compose").param("placeId", String.valueOf(place)).session(session(author)))
                .andExpect(jsonPath("$.data.myReviewId").value((int) id));
        send(post("/api/reviews"), author, "{\"placeId\":" + place + ",\"rating\":5,\"content\":\"Lần hai thử xem sao.\"}")
                .andExpect(status().isConflict()).andExpect(jsonPath("$.message").value(ReviewService.ALREADY));

        // Edit: stars and text change, "đã sửa", no new points
        send(put("/api/reviews/" + id), author, "{\"rating\":2,\"content\":\"Đổi ý rồi, hôm nay phục vụ chậm.\"}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("PUBLISHED"));
        assertThat(jdbc.queryForObject("SELECT is_edited FROM reviews WHERE id = ?", Boolean.class, id)).isTrue();
        assertThat(points(author)).isEqualTo(before + 10);

        // Someone else can neither edit nor delete it
        long other = user("USER", 30);
        send(put("/api/reviews/" + id), other, "{\"rating\":1,\"content\":\"Không phải review của tôi.\"}")
                .andExpect(status().isForbidden());
        mvc.perform(withCsrf(delete("/api/reviews/" + id)).session(session(other))).andExpect(status().isForbidden());

        // Delete: gone for good, points taken back
        mvc.perform(withCsrf(delete("/api/reviews/" + id)).session(session(author))).andExpect(status().isOk());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM reviews WHERE id = ?", Integer.class, id)).isZero();
        assertThat(points(author)).isEqualTo(before);
    }

    @Test
    void checksStarsTextPlaceAndRole() throws Exception {
        send(post("/api/reviews"), author, "{\"placeId\":" + place + ",\"content\":\"ngắn\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.data.rating").value("Hãy chọn số sao."))
                .andExpect(jsonPath("$.data.content").value("Nội dung cần ít nhất 10 ký tự."));
        send(post("/api/reviews"), author, "{\"placeId\":" + place + ",\"rating\":5,\"content\":\"" + "a".repeat(2001) + "\"}")
                .andExpect(jsonPath("$.data.content").value("Nội dung tối đa 2.000 ký tự."));
        jdbc.update("UPDATE places SET status = 'REMOVED' WHERE id = ?", place);
        send(post("/api/reviews"), author, "{\"placeId\":" + place + ",\"rating\":5,\"content\":\"Địa điểm đã bị gỡ rồi.\"}")
                .andExpect(status().isNotFound());
        jdbc.update("UPDATE places SET status = 'ACTIVE' WHERE id = ?", place);

        long moderator = user("MODERATOR", 30);
        send(post("/api/reviews"), moderator, "{\"placeId\":" + place + ",\"rating\":5,\"content\":\"Nhân viên không viết review.\"}")
                .andExpect(status().isForbidden());
        mvc.perform(withCsrf(post("/api/reviews")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"placeId\":" + place + ",\"rating\":5,\"content\":\"Khách chưa đăng nhập.\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void newAccountsShareTheDailyLimitWithPosts() throws Exception {
        long fresh = user("USER", 1);
        jdbc.update("INSERT INTO user_daily_usage (user_id, usage_date, posts) VALUES (?, ?, 3) "
                + "ON DUPLICATE KEY UPDATE posts = 3", fresh, java.sql.Date.valueOf(java.time.LocalDate.now(java.time.ZoneId.of("Asia/Tokyo"))));
        send(post("/api/reviews"), fresh, "{\"placeId\":" + place + ",\"rating\":5,\"content\":\"Hôm nay đăng nhiều quá rồi.\"}")
                .andExpect(status().isTooManyRequests());
    }

    @Test
    void reviewingAnOpenStreetMapPlaceAddsThePlace() throws Exception {
        String osmId = "N" + ThreadLocalRandom.current().nextLong(100_000_000_000L, 900_000_000_000L);
        Hit hit = new Hit(osmId, "Cafe " + word, "Cafe " + word, "カフェ", "Cafe " + word, "Ueno 5, Taito, Tokyo",
                "amenity=cafe", "jp", lat + 0.05, lng);
        when(map.lookup(anyCollection())).thenReturn(Map.of(osmId, hit));
        long id = created(send(post("/api/reviews"), author,
                "{\"externalPlaceId\":\"" + osmId + "\",\"rating\":5,\"content\":\"Cà phê ngon, có ổ cắm sạc.\"}")
                .andExpect(status().isOk()));
        Long placeId = jdbc.queryForObject("SELECT place_id FROM reviews WHERE id = ?", Long.class, id);
        assertThat(jdbc.queryForObject("SELECT osm_id FROM places WHERE id = ?", String.class, placeId)).isEqualTo(osmId);
        assertThat(jdbc.queryForObject("SELECT category_id FROM places WHERE id = ?", String.class, placeId)).isEqualTo("food");
    }

    @Test
    void aReviewPhotoIsServedToEveryoneOnceTheReviewIsPublished() throws Exception {
        when(moderator.engine()).thenReturn("OPENAI");
        when(moderator.check(any(byte[].class))).thenReturn(new Verdict("PASS", "{}"));
        MockMultipartFile json = new MockMultipartFile("review", "", "application/json",
                ("{\"placeId\":" + place + ",\"rating\":5,\"content\":\"Quán sạch, có ảnh thực đơn.\"}")
                        .getBytes(StandardCharsets.UTF_8));
        long id = created(mvc.perform(withCsrf(multipart("/api/reviews").file(json).file(photo()))
                .session(session(author))).andExpect(jsonPath("$.data.status").value("PROCESSING")));
        await().atMost(Duration.ofSeconds(15)).until(() -> "PUBLISHED".equals(
                jdbc.queryForObject("SELECT status FROM reviews WHERE id = ?", String.class, id)));
        String key = jdbc.queryForObject("""
                SELECT m.storage_key FROM review_media rm JOIN media m ON m.id = rm.media_id WHERE rm.review_id = ?""",
                String.class, id);
        assertThat(key).startsWith("review/");

        // The Map gives everyone the photo's URL, and guests and other members get the photo there
        mvc.perform(get("/api/map/places/" + place + "/reviews"))
                .andExpect(jsonPath("$.data.items[0].media[0].url").value("/media/" + key));
        mvc.perform(get("/media/" + key)).andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "image/jpeg"))
                .andExpect(header().string("Cache-Control", "max-age=86400, public"));
        mvc.perform(get("/media/" + key).session(session(user("USER", 30)))).andExpect(status().isOk());

        // Hidden by reports: only its author still sees it
        jdbc.update("UPDATE reviews SET status = 'HIDDEN' WHERE id = ?", id);
        mvc.perform(get("/media/" + key)).andExpect(status().isNotFound());
        mvc.perform(get("/media/" + key).session(session(author))).andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store, private"));
    }

    // ---------------------------------------------------------------- helpers

    private static MockMultipartFile photo() throws Exception {
        BufferedImage img = new BufferedImage(64, 48, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, "png", out);
        return new MockMultipartFile("images", "anh.png", "image/png", out.toByteArray());
    }

    private ResultActions send(MockHttpServletRequestBuilder request, long userId, String json) throws Exception {
        return mvc.perform(withCsrf(request).session(session(userId)).contentType(MediaType.APPLICATION_JSON).content(json));
    }

    private static long created(ResultActions result) throws Exception {
        String body = result.andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return Long.parseLong(body.replaceAll(".*\"data\":\\{\"id\":(\\d+).*", "$1"));
    }

    private int points(long userId) {
        return jdbc.queryForObject("SELECT points FROM users WHERE id = ?", Integer.class, userId);
    }

    private long user(String role, int ageDays) {
        String email = "review-" + UUID.randomUUID() + "@test.local";
        jdbc.update("""
                INSERT INTO users (email, display_name, role, status, is_adult_confirmed, created_at)
                VALUES (?, 'Review Test', ?, 'ACTIVE', TRUE, ?)""",
                email, role, Timestamp.from(Instant.now().minus(Duration.ofDays(ageDays))));
        return jdbc.queryForObject("SELECT id FROM users WHERE email = ?", Long.class, email);
    }

    private static MockHttpServletRequestBuilder withCsrf(MockHttpServletRequestBuilder request) {
        return request.cookie(new Cookie("XSRF-TOKEN", CSRF)).header("X-XSRF-TOKEN", CSRF);
    }

    private static MockHttpSession session(long userId) {
        AppUserPrincipal me = new AppUserPrincipal(userId, "u" + userId + "@test.local", "Review Test", "USER", "/");
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(SPRING_SECURITY_CONTEXT_KEY, new SecurityContextImpl(
                new UsernamePasswordAuthenticationToken(me, null, List.of(new SimpleGrantedAuthority("ROLE_USER")))));
        return session;
    }
}
