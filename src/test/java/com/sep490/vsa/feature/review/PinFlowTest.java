package com.sep490.vsa.feature.review;

import com.sep490.vsa.common.security.AppUserPrincipal;
import com.sep490.vsa.feature.moderation.ImageModerator;
import com.sep490.vsa.feature.place.ExternalPlaces;
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
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.mockito.Mockito.when;
import static org.springframework.security.web.context.HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** "Cắm ghim" (UC-U19) and "Báo đã đóng cửa" (UC-U20) against MySQL ({@code asvsj_test}). */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:mysql://localhost:3306/asvsj_test?createDatabaseIfNotExist=true&useUnicode=true&characterEncoding=utf8&connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true",
        "app.media.local-dir=target/test-uploads",
        "app.moderation.retry-initial-delay-ms=3600000"
})
@AutoConfigureMockMvc
@ActiveProfiles("local")
class PinFlowTest {

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
    private double lat;
    private double lng;

    @BeforeEach
    void setUp() {
        word = "pn" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        author = user(30);
        lat = 33 + ThreadLocalRandom.current().nextDouble(3);
        lng = 131 + ThreadLocalRandom.current().nextDouble(8);
        when(map.engine()).thenReturn("OSM");
        when(map.enabled()).thenReturn(true);
    }

    @Test
    void aPinIsAPendingPlaceWithItsFirstReview() throws Exception {
        int before = points(author);
        String body = send(author, pin("Hana " + word, false))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Đã gửi ghim kèm review. Chờ Moderator duyệt."))
                .andReturn().getResponse().getContentAsString();
        long placeId = Long.parseLong(body.replaceAll(".*\"placeId\":(\\d+).*", "$1"));
        assertThat(jdbc.queryForObject("SELECT CONCAT(source, '/', status, '/', name_vi) FROM places WHERE id = ?",
                String.class, placeId)).isEqualTo("USER/PENDING/Hana " + word);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM reviews WHERE place_id = ? AND status = 'PUBLISHED'",
                Integer.class, placeId)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM place_history WHERE place_id = ? AND action = 'CREATED'",
                Integer.class, placeId)).isEqualTo(1);
        assertThat(points(author)).isEqualTo(before + 10);

        // Only its author sees it; its first review cannot be deleted on its own
        mvc.perform(get("/api/map/places/" + placeId).session(session(author))).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("PENDING"));
        mvc.perform(get("/api/map/places/" + placeId).session(session(user(30)))).andExpect(status().isNotFound());
        long reviewId = jdbc.queryForObject("SELECT id FROM reviews WHERE place_id = ?", Long.class, placeId);
        mvc.perform(withCsrf(delete("/api/reviews/" + reviewId)).session(session(author)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void checksNamesCategoryAndFirstReview() throws Exception {
        send(author, "{\"lat\":" + lat + ",\"lng\":" + lng + ",\"content\":\"ngắn\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.data.names").exists())
                .andExpect(jsonPath("$.data.categoryId").value("Hãy chọn danh mục."))
                .andExpect(jsonPath("$.data.rating").value("Hãy chấm sao cho review đầu tiên."))
                .andExpect(jsonPath("$.data.content").value("Review đầu tiên cần ít nhất 10 ký tự."));
        send(author, "{\"nameEn\":\"No position\",\"categoryId\":\"food\",\"rating\":4,"
                + "\"content\":\"Quán nhỏ nhưng ngon.\"}")
                .andExpect(jsonPath("$.data.position").value("Hãy bấm lên bản đồ để đặt vị trí."));
    }

    @Test
    void aSimilarNameCloseByNeedsConfirmation() throws Exception {
        jdbc.update("""
                INSERT INTO places (name_vi, name_en, category_id, lat, lng, source, status, created_by)
                VALUES (?, ?, 'food', ?, ?, 'TEAM', 'ACTIVE', ?)""",
                "Quán Mori " + word, "Mori " + word, lat + 0.0005, lng, author);
        mvc.perform(get("/api/map/pins/duplicates").param("lat", String.valueOf(lat)).param("lng", String.valueOf(lng))
                        .param("names", "mori " + word.toUpperCase(Locale.ROOT)))
                .andExpect(jsonPath("$.data[*].name", hasItem("Quán Mori " + word)))
                .andExpect(jsonPath("$.data[0].distance").value(56));
        send(author, pin("Mori " + word, false)).andExpect(status().isConflict());
        send(author, pin("Mori " + word, true)).andExpect(status().isOk());
    }

    @Test
    void newAccountsPinTwiceADay() throws Exception {
        long fresh = user(1);
        jdbc.update("INSERT INTO user_daily_usage (user_id, usage_date, pins) VALUES (?, ?, 2) "
                + "ON DUPLICATE KEY UPDATE pins = 2", fresh, java.sql.Date.valueOf(LocalDate.now(ZoneId.of("Asia/Tokyo"))));
        send(fresh, pin("Third " + word, false)).andExpect(status().isTooManyRequests());
    }

    @Test
    void closedReportsCountOncePerUserUntilTheModerator() throws Exception {
        jdbc.update("""
                INSERT INTO places (name_vi, category_id, lat, lng, source, status, created_by)
                VALUES (?, 'konbini', ?, ?, 'OSM', 'ACTIVE', ?)""", "Konbini " + word, lat, lng, author);
        long place = jdbc.queryForObject("SELECT id FROM places WHERE name_vi = ?", Long.class, "Konbini " + word);
        for (int i = 1; i <= 4; i++) {
            mvc.perform(withCsrf(post("/api/map/places/" + place + "/closed-report")).session(session(user(30))))
                    .andExpect(jsonPath("$.data.count").value(i)).andExpect(jsonPath("$.data.queued").value(false));
        }
        long fifth = user(30);
        mvc.perform(withCsrf(post("/api/map/places/" + place + "/closed-report")).session(session(fifth)))
                .andExpect(jsonPath("$.data.count").value(5)).andExpect(jsonPath("$.data.queued").value(true));
        mvc.perform(withCsrf(post("/api/map/places/" + place + "/closed-report")).session(session(fifth)))
                .andExpect(status().isConflict());
        mvc.perform(get("/api/map/places/" + place).session(session(fifth)))
                .andExpect(jsonPath("$.data.reportedClosed").value(true))
                .andExpect(jsonPath("$.data.closed").value(false));      // only a Moderator sets the label
    }

    // ---------------------------------------------------------------- helpers

    private String pin(String nameEn, boolean confirm) {
        return "{\"lat\":" + lat + ",\"lng\":" + lng + ",\"nameEn\":\"" + nameEn + "\",\"categoryId\":\"food\","
                + "\"rating\":5,\"content\":\"Cơm nhà làm, chủ quán dễ thương.\",\"confirmDuplicate\":" + confirm + "}";
    }

    private ResultActions send(long userId, String json) throws Exception {
        return mvc.perform(withCsrf(post("/api/map/pins")).session(session(userId))
                .contentType(MediaType.APPLICATION_JSON).content(json));
    }

    private int points(long userId) {
        return jdbc.queryForObject("SELECT points FROM users WHERE id = ?", Integer.class, userId);
    }

    private long user(int ageDays) {
        String email = "pin-" + UUID.randomUUID() + "@test.local";
        jdbc.update("""
                INSERT INTO users (email, display_name, role, status, is_adult_confirmed, created_at)
                VALUES (?, 'Pin Test', 'USER', 'ACTIVE', TRUE, ?)""",
                email, Timestamp.from(Instant.now().minus(Duration.ofDays(ageDays))));
        return jdbc.queryForObject("SELECT id FROM users WHERE email = ?", Long.class, email);
    }

    private static MockHttpServletRequestBuilder withCsrf(MockHttpServletRequestBuilder request) {
        return request.cookie(new Cookie("XSRF-TOKEN", CSRF)).header("X-XSRF-TOKEN", CSRF);
    }

    private static MockHttpSession session(long userId) {
        AppUserPrincipal me = new AppUserPrincipal(userId, "u" + userId + "@test.local", "Pin Test", "USER", "/");
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(SPRING_SECURITY_CONTEXT_KEY, new SecurityContextImpl(
                new UsernamePasswordAuthenticationToken(me, null, List.of(new SimpleGrantedAuthority("ROLE_USER")))));
        return session;
    }
}
