package com.sep490.vsa.feature.area;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Living areas of all Japan (V10) and the picker API, against MySQL ({@code asvsj_test}). */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:mysql://localhost:3306/asvsj_test?createDatabaseIfNotExist=true&useUnicode=true&characterEncoding=utf8&connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true",
        "app.moderation.retry-initial-delay-ms=3600000",
        "app.map.dev-location="
})
@AutoConfigureMockMvc
@ActiveProfiles("local")
class AreaFlowTest {

    private static final String ARAKAWA = "131181";

    @Autowired
    private MockMvc mvc;
    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void everyPrefectureAndMunicipalityIsLoadedAndTheOldSampleAreasAreGone() {
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM prefectures", Integer.class)).isEqualTo(47);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM areas WHERE code IS NOT NULL", Integer.class))
                .isEqualTo(1892);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM areas WHERE station = 'Nippori'", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT display_name FROM areas WHERE code = ?", String.class, ARAKAWA))
                .isEqualTo("Arakawa-ku, Tokyo · 東京都荒川区");
        // Designated cities are split into wards
        assertThat(jdbc.queryForObject("SELECT display_name FROM areas WHERE code = '271276'", String.class))
                .isEqualTo("Kita-ku, Osaka-shi, Osaka · 大阪府大阪市北区");
    }

    @Test
    void thePickerListsPrefecturesThenTheAreasOfOne() throws Exception {
        mvc.perform(get("/api/areas")).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.prefectures", hasSize(47)))
                .andExpect(jsonPath("$.data.prefectures[12].code").value("13"))
                .andExpect(jsonPath("$.data.prefectures[12].nameEn").value("Tokyo"))
                .andExpect(jsonPath("$.data.devLocation").isEmpty());
        mvc.perform(get("/api/areas/prefectures/13")).andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].nameJa").value("千代田区"))
                .andExpect(jsonPath("$.data[?(@.nameEn == 'Arakawa-ku')].prefectureCode").value("13"));
    }

    @Test
    void searchFindsRomajiKanjiAndKanaNamesStartsFirst() throws Exception {
        mvc.perform(get("/api/areas/search").param("q", "arakawa ku"))
                .andExpect(jsonPath("$.data[0].nameEn").value("Arakawa-ku"));
        mvc.perform(get("/api/areas/search").param("q", "荒川区"))
                .andExpect(jsonPath("$.data[0].nameJa").value("荒川区"));
        mvc.perform(get("/api/areas/search").param("q", "アラカワ"))
                .andExpect(jsonPath("$.data[0].nameJa").value("荒川区"));
        mvc.perform(get("/api/areas/search").param("q", "Kita-ku, Osaka"))
                .andExpect(jsonPath("$.data[0].nameJa").value("大阪市北区"));
        mvc.perform(get("/api/areas/search").param("q", "  ")).andExpect(jsonPath("$.data", hasSize(0)));
        mvc.perform(get("/api/areas/search").param("q", "%")).andExpect(status().isOk());
    }

    @Test
    void aPointOnTheMapSuggestsTheNearestAreaOnlyInJapan() throws Exception {
        // Ga Nippori
        mvc.perform(get("/api/areas/nearest").param("lat", "35.7281").param("lng", "139.7707"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.nameEn").value("Arakawa-ku"));
        // Near Kita-ku ward office, Osaka. (Near a border the nearest point may be the neighbour, e.g. Dotonbori in
        // Chuo-ku gives Naniwa-ku: the user confirms or corrects the suggestion.)
        mvc.perform(get("/api/areas/nearest").param("lat", "34.7050").param("lng", "135.5095"))
                .andExpect(jsonPath("$.data.nameJa").value("大阪市北区"));
        // Hanoi
        mvc.perform(get("/api/areas/nearest").param("lat", "21.0278").param("lng", "105.8342"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(AreaService.NOT_IN_JAPAN));
    }

    @Test
    void newPlacesInJapanGetTheirAreaPlacesInVietnamNone() {
        long user = jdbc.queryForObject("SELECT MIN(id) FROM users", Long.class);
        String word = UUID.randomUUID().toString().substring(0, 8);
        jdbc.update("""
                INSERT INTO places (name_vi, category_id, lat, lng, source, status, created_by)
                VALUES (?, 'food', 35.7290, 139.7714, 'TEAM', 'ACTIVE', ?), (?, 'food', 21.0278, 105.8342, 'TEAM', 'ACTIVE', ?)""",
                "Area JP " + word, user, "Area VN " + word, user);
        long jp = jdbc.queryForObject("SELECT id FROM places WHERE name_vi = ?", Long.class, "Area JP " + word);
        long vn = jdbc.queryForObject("SELECT id FROM places WHERE name_vi = ?", Long.class, "Area VN " + word);
        try {
            AreaService.assignPlace(jdbc, jp);
            AreaService.assignPlace(jdbc, vn);
            assertThat(jdbc.queryForObject("""
                    SELECT a.code FROM places p JOIN areas a ON a.id = p.area_id WHERE p.id = ?""", String.class, jp))
                    .isEqualTo(ARAKAWA);
            assertThat(jdbc.queryForObject("SELECT area_id FROM places WHERE id = ?", Long.class, vn)).isNull();
        } finally {
            jdbc.update("DELETE FROM places WHERE id IN (?, ?)", jp, vn);
        }
    }
}
