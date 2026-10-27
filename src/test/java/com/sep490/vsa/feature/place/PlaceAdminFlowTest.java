package com.sep490.vsa.feature.place;

import com.sep490.vsa.common.security.AppUserPrincipal;
import com.sep490.vsa.feature.moderation.ImageModerator;
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
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.security.web.context.HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** "Địa điểm" of the Moderator (UC-M07 create, UC-M08 update / remove / restore) against MySQL ({@code asvsj_test}). */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:mysql://localhost:3306/asvsj_test?createDatabaseIfNotExist=true&useUnicode=true&characterEncoding=utf8&connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true",
        "app.media.local-dir=target/test-uploads",
        "app.moderation.retry-initial-delay-ms=3600000"
})
@AutoConfigureMockMvc
@ActiveProfiles("local")
class PlaceAdminFlowTest {

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
    private long mod;
    private double lat;
    private double lng;

    @BeforeEach
    void setUp() {
        word = "pa" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        mod = user("MODERATOR");
        lat = 33 + ThreadLocalRandom.current().nextDouble(3);
        lng = 131 + ThreadLocalRandom.current().nextDouble(8);
        when(map.engine()).thenReturn("OSM");
        when(map.enabled()).thenReturn(true);
        when(map.search(anyString())).thenReturn(List.of());
    }

    @Test
    void aPlacePinnedByHandNeedsANameACategoryAndAPosition() throws Exception {
        act(post("/api/moderator/places"), "{\"nameVi\":\" \"}").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.data.nameVi").value("Hãy nhập tên hiển thị."))
                .andExpect(jsonPath("$.data.categoryId").value("Hãy chọn danh mục."))
                .andExpect(jsonPath("$.data.position").value("Hãy bấm lên bản đồ để đặt vị trí."));

        long id = saved(act(post("/api/moderator/places"), form(null, "Quán Sen " + word, "food", lat, lng, false))
                .andExpect(jsonPath("$.data.duplicates").isEmpty()));
        assertThat(jdbc.queryForObject("SELECT CONCAT(source, '/', status) FROM places WHERE id = ?", String.class, id))
                .isEqualTo("MODERATOR/ACTIVE");
        mvc.perform(get("/api/map/places/" + id)).andExpect(status().isOk());
        mvc.perform(get("/api/moderator/places/" + id).session(session(mod, "MODERATOR")))
                .andExpect(jsonPath("$.data.history[0].text").value("Moderator cắm ghim tay"));

        // The same name 30 m away: "Có thể trùng", nothing saved until "Vẫn lưu"
        act(post("/api/moderator/places"), form(null, "Sen " + word, "food", lat + 0.0003, lng, false))
                .andExpect(jsonPath("$.data.placeId").doesNotExist())
                .andExpect(jsonPath("$.data.duplicates[0].id").value((int) id))
                .andExpect(jsonPath("$.message").value("Có thể trùng với địa điểm đã có."));
        saved(act(post("/api/moderator/places"), form(null, "Sen " + word, "food", lat + 0.0003, lng, true)));
    }

    @Test
    void aPlaceTakenFromOpenStreetMapKeepsItsId() throws Exception {
        String osmId = "N" + ThreadLocalRandom.current().nextLong(100_000_000_000L, 900_000_000_000L);
        Hit hit = new Hit(osmId, "Ueno Park " + word, "Công viên Ueno " + word, "上野公園", "Ueno Park " + word,
                "Ueno, Taito, Tokyo", "leisure=park", "jp", lat, lng);
        when(map.search("ueno " + word)).thenReturn(List.of(hit));
        when(map.lookup(anyCollection())).thenReturn(Map.of(osmId, hit));

        mvc.perform(get("/api/moderator/places/osm-search").param("q", "ueno " + word).session(session(mod, "MODERATOR")))
                .andExpect(jsonPath("$.data.items[0].externalId").value(osmId))
                .andExpect(jsonPath("$.data.items[0].id").doesNotExist());
        mvc.perform(get("/api/moderator/places/osm/" + osmId).session(session(mod, "MODERATOR")))
                .andExpect(jsonPath("$.data.existingId").doesNotExist())
                .andExpect(jsonPath("$.data.prefill.nameVi").value("Công viên Ueno " + word))
                .andExpect(jsonPath("$.data.prefill.nameJa").value("上野公園"))
                .andExpect(jsonPath("$.data.prefill.categoryId").value("park"));

        long id = saved(act(post("/api/moderator/places"), form(osmId, "Công viên Ueno " + word, "park", lat, lng, false)));
        assertThat(jdbc.queryForObject("SELECT CONCAT(source, '/', osm_id) FROM places WHERE id = ?", String.class, id))
                .isEqualTo("OSM/" + osmId);
        // Picked again: Senpai ơi has it now ("Mở"), and adding it twice opens the first one
        mvc.perform(get("/api/moderator/places/osm/" + osmId).session(session(mod, "MODERATOR")))
                .andExpect(jsonPath("$.data.existingId").value((int) id));
        act(post("/api/moderator/places"), form(osmId, "Lần hai", "park", lat, lng, true))
                .andExpect(jsonPath("$.data.placeId").value((int) id))
                .andExpect(jsonPath("$.message").value("Địa điểm này đã có trên Senpai ơi."));
    }

    @Test
    void editingWritesEachChangedFieldToTheHistory() throws Exception {
        long author = user("USER");
        long id = place("Konbini " + word, "konbini", "USER", author);
        act(put("/api/moderator/places/" + id), form(null, "Konbini " + word, "konbini", lat, lng, false))
                .andExpect(jsonPath("$.message").value("Không có thay đổi nào."));
        act(put("/api/moderator/places/" + id), form(null, "Siêu thị " + word, "super", lat, lng, true))
                .andExpect(jsonPath("$.message").value("Đã lưu thay đổi."));
        mvc.perform(get("/api/moderator/places/" + id).session(session(mod, "MODERATOR")))
                .andExpect(jsonPath("$.data.history[0].text").value("Sửa thông tin · Tên tiếng Việt: Konbini " + word
                        + " → Siêu thị " + word + "; Danh mục: Cửa hàng tiện lợi → Siêu thị"))
                .andExpect(jsonPath("$.data.category.id").value("super"));
        assertThat(jdbc.queryForObject("SELECT content FROM notifications WHERE user_id = ? ORDER BY id DESC LIMIT 1",
                String.class, author)).isEqualTo("Moderator đã cập nhật thông tin địa điểm “Siêu thị " + word
                + "” bạn cắm ghim.");
    }

    @Test
    void aRemovedPlaceLeavesTheMapAndComesBackWhole() throws Exception {
        long id = place("Hiệu thuốc " + word, "pharmacy", "TEAM", mod);
        act(post("/api/moderator/places/" + id + "/remove"), "{}").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.data.reasonId").value("Hãy chọn lý do gỡ."));
        long reason = jdbc.queryForObject("SELECT id FROM reason_options WHERE scope = 'PLACE_REMOVE' AND sort_order = 1",
                Long.class);
        act(post("/api/moderator/places/" + id + "/remove"), "{\"reasonId\":" + reason + ",\"note\":\"Đã thành bãi xe\"}")
                .andExpect(status().isOk());
        mvc.perform(get("/api/map/places/" + id)).andExpect(status().isNotFound());
        mvc.perform(get("/api/moderator/places").param("status", "REMOVED").param("q", word)
                        .session(session(mod, "MODERATOR")))
                .andExpect(jsonPath("$.data.items[*].id", contains((int) id)));
        mvc.perform(get("/api/moderator/places/" + id).session(session(mod, "MODERATOR")))
                .andExpect(jsonPath("$.data.removedReason").value("Không còn tồn tại: Đã thành bãi xe"))
                .andExpect(jsonPath("$.data.history[0].text").value("Gỡ khỏi bản đồ: Không còn tồn tại (Đã thành bãi xe)"));

        act(post("/api/moderator/places/" + id + "/restore"), "").andExpect(status().isOk());
        mvc.perform(get("/api/map/places/" + id)).andExpect(status().isOk());
        act(post("/api/moderator/places/" + id + "/restore"), "").andExpect(status().isConflict());
    }

    @Test
    void theListIsSearchedWithoutAccentsAndFiltered() throws Exception {
        long food = place("Quán Phở " + word, "food", "TEAM", mod);
        long pin = place("Phở Pin " + word, "food", "USER", mod);
        mvc.perform(get("/api/moderator/places").param("q", "pho " + word).session(session(mod, "MODERATOR")))
                .andExpect(jsonPath("$.data.items[*].id", contains((int) food)))
                .andExpect(jsonPath("$.data.size").value(10));
        mvc.perform(get("/api/moderator/places").param("q", word).param("source", "USER")
                        .session(session(mod, "MODERATOR")))
                .andExpect(jsonPath("$.data.items[*].id", contains((int) pin)))
                .andExpect(jsonPath("$.data.total").value(1));
        mvc.perform(get("/api/moderator/places").param("q", word).param("source", "MODERATOR")
                        .session(session(mod, "MODERATOR")))
                .andExpect(jsonPath("$.data.items[*].id", hasItem((int) food)))
                .andExpect(jsonPath("$.data.items[*].id", not(hasItem((int) pin))));
        mvc.perform(get("/api/moderator/places").session(session(user("USER"), "USER"))).andExpect(status().isForbidden());
    }

    // ---------------------------------------------------------------- helpers

    private String form(String osmId, String nameVi, String category, double atLat, double atLng, boolean confirm) {
        return "{" + (osmId == null ? "" : "\"osmId\":\"" + osmId + "\",") + "\"nameVi\":\"" + nameVi
                + "\",\"categoryId\":\"" + category + "\",\"lat\":" + atLat + ",\"lng\":" + atLng
                + ",\"confirmDuplicate\":" + confirm + "}";
    }

    private static long saved(ResultActions result) throws Exception {
        String body = result.andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return Long.parseLong(body.replaceAll(".*\"placeId\":(\\d+).*", "$1"));
    }

    private long place(String name, String category, String source, long createdBy) {
        jdbc.update("""
                INSERT INTO places (name_vi, category_id, lat, lng, source, status, created_by)
                VALUES (?, ?, ?, ?, ?, 'ACTIVE', ?)""", name, category, lat, lng, source, createdBy);
        return jdbc.queryForObject("SELECT MAX(id) FROM places WHERE name_vi = ?", Long.class, name);
    }

    private ResultActions act(MockHttpServletRequestBuilder request, String json) throws Exception {
        return mvc.perform(withCsrf(request).session(session(mod, "MODERATOR"))
                .contentType(MediaType.APPLICATION_JSON).content(json));
    }

    private long user(String role) {
        String email = "placeadmin-" + UUID.randomUUID() + "@test.local";
        jdbc.update("""
                INSERT INTO users (email, display_name, role, status, is_adult_confirmed, created_at)
                VALUES (?, 'Place Admin Test', ?, 'ACTIVE', TRUE, ?)""",
                email, role, Timestamp.from(Instant.now().minus(Duration.ofDays(30))));
        return jdbc.queryForObject("SELECT id FROM users WHERE email = ?", Long.class, email);
    }

    private static MockHttpServletRequestBuilder withCsrf(MockHttpServletRequestBuilder request) {
        return request.cookie(new Cookie("XSRF-TOKEN", CSRF)).header("X-XSRF-TOKEN", CSRF);
    }

    private static MockHttpSession session(long userId, String role) {
        AppUserPrincipal me = new AppUserPrincipal(userId, "u" + userId + "@test.local", "Place Admin Test", role, "/");
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(SPRING_SECURITY_CONTEXT_KEY, new SecurityContextImpl(
                new UsernamePasswordAuthenticationToken(me, null, List.of(new SimpleGrantedAuthority("ROLE_" + role)))));
        return session;
    }
}
