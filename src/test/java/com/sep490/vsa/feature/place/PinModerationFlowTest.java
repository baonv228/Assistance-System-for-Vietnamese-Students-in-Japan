package com.sep490.vsa.feature.place;

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
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.security.web.context.HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** "Ghim mới" (UC-M05, UC-M06) and "Báo đóng cửa" (UC-M04 AF4/AF5) against MySQL ({@code asvsj_test}). */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:mysql://localhost:3306/asvsj_test?createDatabaseIfNotExist=true&useUnicode=true&characterEncoding=utf8&connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true",
        "app.media.local-dir=target/test-uploads",
        "app.moderation.retry-initial-delay-ms=3600000",
        "app.places.osm.enabled=false"
})
@AutoConfigureMockMvc
@ActiveProfiles("local")
class PinModerationFlowTest {

    private static final String CSRF = "test-csrf-token";

    @Autowired
    private MockMvc mvc;
    @Autowired
    private JdbcTemplate jdbc;
    @MockBean
    private ImageModerator moderator;

    private String word;
    private long author;
    private long mod;
    private double lat;
    private double lng;

    @BeforeEach
    void setUp() {
        word = "pm" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        author = user("USER");
        mod = user("MODERATOR");
        lat = 33 + ThreadLocalRandom.current().nextDouble(3);
        lng = 131 + ThreadLocalRandom.current().nextDouble(8);
        when(moderator.engine()).thenReturn("OPENAI");
        when(moderator.check(any(byte[].class))).thenReturn(new Verdict("PASS", "{}"));
    }

    @Test
    void approvingAPinPutsItOnTheMapAndPaysItsAuthor() throws Exception {
        long pin = pin("Hana " + word, lat, lng);
        mvc.perform(get("/api/moderator/pins").session(session(mod, "MODERATOR")))
                .andExpect(jsonPath("$.data.items[*].id", hasItem((int) pin)));
        mvc.perform(get("/api/moderator/pins/" + pin).session(session(mod, "MODERATOR")))
                .andExpect(jsonPath("$.data.review.rating").value(5))
                .andExpect(jsonPath("$.data.rejectReasons[0].label").value("Không có thật hoặc sai vị trí"))
                .andExpect(jsonPath("$.data.scanning").value(false));
        mvc.perform(get("/api/moderator/pins").session(session(author, "USER"))).andExpect(status().isForbidden());

        int before = points(author);
        act(post("/api/moderator/pins/" + pin + "/approve"), "{\"nameVi\":\"Quán Hana " + word + "\",\"categoryId\":\"super\"}")
                .andExpect(status().isOk());
        assertThat(jdbc.queryForObject("SELECT CONCAT(status, '/', name_vi, '/', category_id) FROM places WHERE id = ?",
                String.class, pin)).isEqualTo("ACTIVE/Quán Hana " + word + "/super");
        assertThat(points(author)).isEqualTo(before + 15);
        assertThat(notification(author)).isEqualTo("Ghim “Quán Hana " + word + "” đã được duyệt. +15 điểm.");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM place_history WHERE place_id = ? AND action = 'APPROVED'",
                Integer.class, pin)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM activity_logs WHERE action = 'PIN_APPROVED' AND target_id = ?",
                Integer.class, pin)).isEqualTo(1);
        mvc.perform(get("/api/map/places/" + pin)).andExpect(status().isOk()).andExpect(jsonPath("$.data.reviewCount").value(1));

        // Another Moderator a moment later
        act(post("/api/moderator/pins/" + pin + "/approve"), "{}").andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("Ghim này đã được xử lý."));
    }

    @Test
    void rejectingAPinNeedsAReasonAndDeletesItsFirstReview() throws Exception {
        long pin = pin("Fake " + word, lat, lng);
        int withReview = points(author);
        act(post("/api/moderator/pins/" + pin + "/reject"), "{}").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.data.reasonId").value("Hãy chọn lý do từ chối."));
        long reason = jdbc.queryForObject("SELECT id FROM reason_options WHERE scope = 'PIN_REJECT' AND sort_order = 3",
                Long.class);
        act(post("/api/moderator/pins/" + pin + "/reject"), "{\"reasonId\":" + reason + "}").andExpect(status().isOk());
        assertThat(jdbc.queryForObject("SELECT CONCAT(status, '/', reject_reason) FROM places WHERE id = ?", String.class, pin))
                .isEqualTo("REJECTED/Tên không rõ nghĩa hoặc spam");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM reviews WHERE place_id = ?", Integer.class, pin)).isZero();
        assertThat(points(author)).isEqualTo(withReview - 10);
        assertThat(notification(author)).isEqualTo("Ghim “Fake " + word + "” bị từ chối: Tên không rõ nghĩa hoặc spam.");
        mvc.perform(get("/api/map/places/" + pin).session(session(author, "USER"))).andExpect(status().isNotFound());
    }

    @Test
    void mergingAPinMovesItsReviewToThePlaceNearby() throws Exception {
        long target = place("Hana Shokudo " + word, lat + 0.0005, lng);
        long pin = pin("Hana Shokudo " + word, lat, lng);
        mvc.perform(get("/api/moderator/pins").param("duplicates", "true").session(session(mod, "MODERATOR")))
                .andExpect(jsonPath("$.data.items[?(@.id == " + pin + ")].possibleDuplicate", contains(true)));
        mvc.perform(get("/api/moderator/pins/" + pin).session(session(mod, "MODERATOR")))
                .andExpect(jsonPath("$.data.nearby[0].id").value((int) target))
                .andExpect(jsonPath("$.data.nearby[0].distance").value(56))
                .andExpect(jsonPath("$.data.nearby[0].similar").value(true));

        long far = place("Far " + word, lat + 0.01, lng);
        act(post("/api/moderator/pins/" + pin + "/merge"), "{\"targetId\":" + far + "}").andExpect(status().isBadRequest());
        act(post("/api/moderator/pins/" + pin + "/merge"), "{\"targetId\":" + target + "}").andExpect(status().isOk())
                .andExpect(jsonPath("$.data.placeId").value((int) target));
        assertThat(jdbc.queryForObject("SELECT CONCAT(status, '/', merged_into_id) FROM places WHERE id = ?", String.class, pin))
                .isEqualTo("MERGED/" + target);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM reviews WHERE place_id = ? AND author_id = ?",
                Integer.class, target, author)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT moved_reviews FROM place_merges WHERE merged_place_id = ?", Integer.class, pin))
                .isEqualTo(1);
        mvc.perform(get("/api/map/places/" + pin)).andExpect(jsonPath("$.data.id").value((int) target));
    }

    @Test
    void aModeratorSeesThePhotosOfAPendingPinAndWaitsForTheirScan() throws Exception {
        MockMultipartFile json = new MockMultipartFile("pin", "", "application/json", ("{\"lat\":" + lat + ",\"lng\":" + lng
                + ",\"nameEn\":\"Photo " + word + "\",\"categoryId\":\"food\",\"rating\":4,"
                + "\"content\":\"Quán có ảnh thực đơn rõ ràng.\"}").getBytes(StandardCharsets.UTF_8));
        String body = mvc.perform(withCsrf(multipart("/api/map/pins").file(json).file(photo()))
                .session(session(author, "USER"))).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        long pin = Long.parseLong(body.replaceAll(".*\"placeId\":(\\d+).*", "$1"));
        await().atMost(Duration.ofSeconds(15)).until(() -> "PUBLISHED".equals(jdbc.queryForObject(
                "SELECT status FROM reviews WHERE place_id = ?", String.class, pin)));
        String key = jdbc.queryForObject("""
                SELECT m.storage_key FROM reviews r JOIN review_media rm ON rm.review_id = r.id
                JOIN media m ON m.id = rm.media_id WHERE r.place_id = ?""", String.class, pin);

        mvc.perform(get("/api/moderator/pins/" + pin).session(session(mod, "MODERATOR")))
                .andExpect(jsonPath("$.data.review.media[0].url").value("/media/" + key));
        mvc.perform(get("/media/" + key).session(session(mod, "MODERATOR"))).andExpect(status().isOk());
        mvc.perform(get("/media/" + key).session(session(user("USER"), "USER"))).andExpect(status().isNotFound());
        mvc.perform(get("/media/" + key)).andExpect(status().isNotFound());

        // A file still being scanned: "Duyệt" waits
        jdbc.update("""
                UPDATE media SET scan_status = 'PROCESSING' WHERE storage_key = ?""", key);
        act(post("/api/moderator/pins/" + pin + "/approve"), "{}").andExpect(status().isConflict());
    }

    @Test
    void closedReportsWaitForTheModerator() throws Exception {
        long place = place("Konbini " + word, lat, lng);
        for (int i = 0; i < 5; i++) {
            jdbc.update("INSERT INTO place_closed_reports (place_id, user_id) VALUES (?, ?)", place, user("USER"));
        }
        mvc.perform(get("/api/moderator/closed").session(session(mod, "MODERATOR")))
                .andExpect(jsonPath("$.data.threshold").value(5))
                .andExpect(jsonPath("$.data.waiting[?(@.id == " + place + ")].reports", contains(5)));

        // "Vẫn mở cửa": the reports go
        act(delete("/api/moderator/places/" + place + "/closed-reports"), "").andExpect(status().isOk());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM place_closed_reports WHERE place_id = ?", Integer.class, place))
                .isZero();
        mvc.perform(get("/api/moderator/closed").session(session(mod, "MODERATOR")))
                .andExpect(jsonPath("$.data.waiting[*].id", not(hasItem((int) place))));

        // Reported again, confirmed: the label is set, the reports are kept as handled
        long reporter = user("USER");
        jdbc.update("INSERT INTO place_closed_reports (place_id, user_id) VALUES (?, ?)", place, reporter);
        act(put("/api/moderator/places/" + place + "/closed"), "{\"closed\":true}").andExpect(status().isOk());
        mvc.perform(get("/api/map/places/" + place).session(session(reporter, "USER")))
                .andExpect(jsonPath("$.data.closed").value(true));
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM place_closed_reports WHERE place_id = ? AND resolved_at IS NOT NULL""",
                Integer.class, place)).isEqualTo(1);
        mvc.perform(get("/api/moderator/closed").session(session(mod, "MODERATOR")))
                .andExpect(jsonPath("$.data.closed[*].id", hasItem((int) place)));
        act(put("/api/moderator/places/" + place + "/closed"), "{\"closed\":true}").andExpect(status().isConflict());

        // Reopened: the label goes and so do the old reports
        act(put("/api/moderator/places/" + place + "/closed"), "{\"closed\":false}").andExpect(status().isOk());
        assertThat(jdbc.queryForObject("SELECT is_closed FROM places WHERE id = ?", Boolean.class, place)).isFalse();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM place_closed_reports WHERE place_id = ?", Integer.class, place))
                .isZero();
        assertThat(jdbc.queryForList("SELECT action FROM place_history WHERE place_id = ? ORDER BY id", String.class, place))
                .containsExactly("CLOSED", "REOPENED");
    }

    // ---------------------------------------------------------------- helpers

    /** A community pin sent through the Map ("Cắm ghim"), with its first review (published at once, +10). */
    private long pin(String nameEn, double atLat, double atLng) throws Exception {
        String body = mvc.perform(withCsrf(post("/api/map/pins")).session(session(author, "USER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"lat\":" + atLat + ",\"lng\":" + atLng + ",\"nameEn\":\"" + nameEn
                                + "\",\"categoryId\":\"food\",\"rating\":5,\"content\":\"Cơm nhà làm, chủ quán dễ thương.\","
                                + "\"confirmDuplicate\":true}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return Long.parseLong(body.replaceAll(".*\"placeId\":(\\d+).*", "$1"));
    }

    private long place(String name, double atLat, double atLng) {
        jdbc.update("""
                INSERT INTO places (name_vi, name_en, category_id, lat, lng, source, status, created_by)
                VALUES (?, ?, 'food', ?, ?, 'TEAM', 'ACTIVE', ?)""", name, name, atLat, atLng, mod);
        return jdbc.queryForObject("SELECT MAX(id) FROM places WHERE name_vi = ?", Long.class, name);
    }

    private ResultActions act(MockHttpServletRequestBuilder request, String json) throws Exception {
        return mvc.perform(withCsrf(request).session(session(mod, "MODERATOR"))
                .contentType(MediaType.APPLICATION_JSON).content(json));
    }

    private String notification(long userId) {
        return jdbc.queryForObject("SELECT content FROM notifications WHERE user_id = ? ORDER BY id DESC LIMIT 1",
                String.class, userId);
    }

    private int points(long userId) {
        return jdbc.queryForObject("SELECT points FROM users WHERE id = ?", Integer.class, userId);
    }

    private long user(String role) {
        String email = "pinmod-" + UUID.randomUUID() + "@test.local";
        jdbc.update("""
                INSERT INTO users (email, display_name, role, status, is_adult_confirmed, created_at)
                VALUES (?, 'Pin Mod Test', ?, 'ACTIVE', TRUE, ?)""",
                email, role, Timestamp.from(Instant.now().minus(Duration.ofDays(30))));
        return jdbc.queryForObject("SELECT id FROM users WHERE email = ?", Long.class, email);
    }

    private static MockMultipartFile photo() throws Exception {
        BufferedImage img = new BufferedImage(64, 48, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, "png", out);
        return new MockMultipartFile("images", "anh.png", "image/png", out.toByteArray());
    }

    private static MockHttpServletRequestBuilder withCsrf(MockHttpServletRequestBuilder request) {
        return request.cookie(new Cookie("XSRF-TOKEN", CSRF)).header("X-XSRF-TOKEN", CSRF);
    }

    private static MockHttpSession session(long userId, String role) {
        AppUserPrincipal me = new AppUserPrincipal(userId, "u" + userId + "@test.local", "Pin Mod Test", role, "/");
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(SPRING_SECURITY_CONTEXT_KEY, new SecurityContextImpl(
                new UsernamePasswordAuthenticationToken(me, null, List.of(new SimpleGrantedAuthority("ROLE_" + role)))));
        return session;
    }
}
