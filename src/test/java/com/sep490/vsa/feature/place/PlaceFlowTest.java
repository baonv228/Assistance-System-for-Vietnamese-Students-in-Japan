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

import java.math.BigDecimal;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.web.context.HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * "Địa điểm liên quan" from ASVSJ and OpenStreetMap, end to end against MySQL ({@code asvsj_test}), with the map
 * services replaced by a mock. Names carry a random word and OSM ids a random number, so earlier runs never interfere.
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:mysql://localhost:3306/asvsj_test?createDatabaseIfNotExist=true&useUnicode=true&characterEncoding=utf8&connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true",
        "app.media.local-dir=target/test-uploads",
        "app.moderation.retry-initial-delay-ms=3600000"
})
@AutoConfigureMockMvc
@ActiveProfiles("local")
class PlaceFlowTest {

    private static final String CSRF = "test-csrf-token";
    private static final ZoneId JAPAN = ZoneId.of("Asia/Tokyo");

    @Autowired
    private MockMvc mvc;
    @Autowired
    private JdbcTemplate jdbc;
    @MockBean
    private ExternalPlaces map;
    @MockBean
    private ImageModerator moderator;

    private String word;
    private String pinWord;          // in the pin's English name only, so a search for "word" does not find the pin
    private long author;
    private long topic;
    private double lat;              // a random spot in Japan, so places of earlier runs are never close by
    private double lng;
    private long knownPlace;         // an OSM place ASVSJ already has
    private String knownId;
    private String removedId;        // an OSM place a Moderator removed from the map
    private long pin;                // a community pin added without an OSM id
    private String pinTwinId;        // the same place on OpenStreetMap
    private String towerId;          // new OSM places
    private String towerNodeId;      // a second point of the tower (same name, 33 m away)
    private String cafeId;
    private Map<String, Hit> lookups;

    @BeforeEach
    void setUp() {
        word = "pl" + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        pinWord = "hn" + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        author = user();
        topic = jdbc.queryForObject("SELECT id FROM blog_topics WHERE name = 'Kinh nghiệm'", Long.class);
        long n = ThreadLocalRandom.current().nextLong(100_000_000_000L, 900_000_000_000L);
        knownId = "N" + n;
        removedId = "W" + n;
        pinTwinId = "N" + (n + 1);
        towerId = "R" + n;
        towerNodeId = "N" + (n + 2);
        cafeId = "N" + (n + 3);
        lat = 33 + ThreadLocalRandom.current().nextDouble(3);
        lng = 131 + ThreadLocalRandom.current().nextDouble(8);

        jdbc.update("""
                INSERT INTO places (osm_id, name_vi, category_id, lat, lng, source, status, created_by)
                VALUES (?, ?, 'station', ?, ?, 'OSM', 'ACTIVE', ?), (?, ?, 'food', ?, ?, 'OSM', 'REMOVED', ?)""",
                knownId, "Ga " + word, lat, lng, author, removedId, "Quán đã gỡ " + word, lat, lng, author);
        knownPlace = jdbc.queryForObject("SELECT id FROM places WHERE osm_id = ?", Long.class, knownId);
        // A pin whose names do not contain the typed text: only the map finds it, by its English name
        jdbc.update("""
                INSERT INTO places (name_vi, name_ja, name_en, category_id, lat, lng, source, status, created_by)
                VALUES ('Quán cơm Hana', 'はな食堂', ?, 'food', ?, ?, 'USER', 'ACTIVE', ?)""",
                "Hana Shokudo " + pinWord, lat + 0.02, lng, author);
        pin = jdbc.queryForObject("SELECT id FROM places WHERE name_en = ?", Long.class, "Hana Shokudo " + pinWord);

        Hit known = hit(knownId, word + " Station", "railway=station", 0.0003, 0);
        Hit tower = new Hit(towerId, "Tower " + word, "Tháp " + word, "タワー" + word, "Tower " + word,
                "Shibakōen 4, Minato, Tokyo, Nhật Bản", "man_made=tower", "jp", lat + 0.01, lng);
        Hit towerNode = hit(towerNodeId, "Tower " + word, "tourism=attraction", 0.0103, 0);
        Hit cafe = new Hit(cafeId, "Cafe " + word, "Cafe " + word, "カフェ " + word, "Cafe " + word,
                "Ueno 5, Taito, Tokyo, Nhật Bản", "amenity=cafe", "jp", lat + 0.03, lng);
        Hit removed = hit(removedId, "Removed " + word, "amenity=restaurant", 0, 0);
        Hit pinTwin = new Hit(pinTwinId, "Hana Shokudo " + pinWord, "Hana Shokudo " + pinWord, null, "Hana Shokudo " + pinWord,
                "Yanaka, Taito, Tokyo, Nhật Bản", "amenity=restaurant", "jp", lat + 0.0203, lng + 0.0003);

        when(map.engine()).thenReturn("OSM");
        when(map.enabled()).thenReturn(true);
        when(map.search(anyString())).thenReturn(List.of(known, tower, towerNode, cafe, removed));
        when(map.search("hanashokudo" + pinWord)).thenReturn(List.of(pinTwin));
        lookups = new LinkedHashMap<>();
        List.of(tower, cafe, pinTwin).forEach(h -> lookups.put(h.id(), h));
        when(map.lookup(anyCollection())).thenAnswer(call -> {
            Map<String, Hit> found = new LinkedHashMap<>();
            for (Object id : (Collection<?>) call.getArgument(0)) {
                if (lookups.containsKey(id)) {
                    found.put((String) id, lookups.get(id));
                }
            }
            return found;
        });
    }

    /** A place {@code north} and {@code east} degrees from the test spot. */
    private Hit hit(String id, String name, String tag, double north, double east) {
        return new Hit(id, name, null, null, null, "Arakawa, Tokyo, Nhật Bản", tag, "jp", lat + north, lng + east);
    }

    @Test
    void searchListsAsvsjPlacesFirstThenNewMapPlaces() throws Exception {
        long before = usage(PlaceService.SEARCH_USAGE);
        mvc.perform(get("/api/places/search").param("q", word).session(session(author)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.external").value("ON"))
                // The known place is listed once, as the ASVSJ place; the removed one not at all; the tower once
                .andExpect(jsonPath("$.data.items[*].externalId", contains(knownId, towerId, cafeId)))
                .andExpect(jsonPath("$.data.items[0].id").value((int) knownPlace))
                .andExpect(jsonPath("$.data.items[0].name").value("Ga " + word))
                .andExpect(jsonPath("$.data.items[0].source").value("OSM"))
                .andExpect(jsonPath("$.data.items[1].id").isEmpty())
                .andExpect(jsonPath("$.data.items[1].name").value("Tower " + word))
                .andExpect(jsonPath("$.data.items[1].category").value("Địa điểm khác"))
                .andExpect(jsonPath("$.data.items[1].address").value("Shibakōen 4, Minato, Tokyo, Nhật Bản"))
                .andExpect(jsonPath("$.data.items[1].source").value("OSM"))
                .andExpect(jsonPath("$.data.items[2].category").value("Quán ăn"));
        assertThat(usage(PlaceService.SEARCH_USAGE)).isEqualTo(before + 1);

        // The same text again within minutes is answered from memory
        mvc.perform(get("/api/places/search").param("q", " " + word.toUpperCase() + " ").session(session(author)))
                .andExpect(jsonPath("$.data.items", hasSize(3)));
        verify(map, times(1)).search(anyString());
        assertThat(usage(PlaceService.SEARCH_USAGE)).isEqualTo(before + 1);

        // A pin added without its OSM id is recognised by its name close by and shown as the ASVSJ pin
        mvc.perform(get("/api/places/search").param("q", "hanashokudo" + pinWord).session(session(author)))
                .andExpect(jsonPath("$.data.items[*].id", contains((int) pin)))
                .andExpect(jsonPath("$.data.items[0].source").value("USER"));

        // Approved community pins are found by their names too
        mvc.perform(get("/api/places/search").param("q", "hana shokudo " + pinWord).session(session(author)))
                .andExpect(jsonPath("$.data.items[0].id").value((int) pin));
        jdbc.update("""
                INSERT INTO places (name_vi, category_id, lat, lng, source, status, created_by)
                VALUES (?, 'food', 35.7, 139.7, 'USER', 'PENDING', ?)""", "Quán chờ duyệt " + word, author);
        mvc.perform(get("/api/places/search").param("q", "cho duyet " + word).session(session(author)))
                .andExpect(jsonPath("$.data.items[*].source", not(hasItem("USER"))));

        // "ga X" is asked among stations, stations first, and also as typed ("ga ran" may be "gà rán")
        Hit station = hit("N" + (Long.parseLong(knownId.substring(1)) + 7), "Shin-" + word, "railway=station", 0.2, 0);
        when(map.searchStations("Shin-" + word)).thenReturn(List.of(station));
        mvc.perform(get("/api/places/search").param("q", "ga Shin-" + word).session(session(author)))
                // the known place found by the typed text is an ASVSJ place, listed before the new ones
                .andExpect(jsonPath("$.data.items[*].externalId", contains(knownId, station.id(), towerId, cafeId)))
                .andExpect(jsonPath("$.data.items[1].category").value("Ga tàu"));
        verify(map).search("ga Shin-" + word);
        // "ga U": one letter is too short for a station search, only the text as typed is asked
        mvc.perform(get("/api/places/search").param("q", "ga U").session(session(author)));
        verify(map, never()).searchStations("U");
        verify(map).search("ga U");

        // The map cannot be reached: the answer says why, so the page can say what to do
        when(map.search("sập " + word)).thenThrow(new ExternalPlacesException("SSL", "PKIX path building failed", null));
        mvc.perform(get("/api/places/search").param("q", "sập " + word).session(session(author)))
                .andExpect(jsonPath("$.data.external").value("UNAVAILABLE"))
                .andExpect(jsonPath("$.data.externalReason").value("SSL"));

        // One character: ASVSJ only, the map is not asked
        mvc.perform(get("/api/places/search").param("q", "g").session(session(author)))
                .andExpect(jsonPath("$.data.external").value("SHORT"));
        mvc.perform(get("/api/places/search").param("q", word)).andExpect(status().isUnauthorized());
    }

    @Test
    void oneUserMaySearchTheMapFortyTimesAMinute() throws Exception {
        MockHttpSession me = session(author);
        for (int i = 0; i < PlaceService.SEARCHES_PER_MINUTE; i++) {
            mvc.perform(get("/api/places/search").param("q", word + " " + i).session(me))
                    .andExpect(jsonPath("$.data.external").value("ON"));
        }
        mvc.perform(get("/api/places/search").param("q", word + " more").session(me))
                .andExpect(jsonPath("$.data.external").value("BUSY"))
                .andExpect(jsonPath("$.data.items").isEmpty());     // ASVSJ places only, and none has that name
        // A text asked before is still answered (from memory), and other users are not affected
        mvc.perform(get("/api/places/search").param("q", word + " 3").session(me))
                .andExpect(jsonPath("$.data.external").value("ON"));
        mvc.perform(get("/api/places/search").param("q", word + " more").session(session(user())))
                .andExpect(jsonPath("$.data.external").value("ON"));
    }

    @Test
    void aPostSavesItsNewMapPlacesAndReusesThemNextTime() throws Exception {
        // Refused for a banned keyword, then sent again fixed: the map is asked only the first time
        send(body("Visa nhanh tháp " + word, List.of(), List.of(towerId, cafeId))).andExpect(status().isBadRequest());
        long id = created(send(body("Tháp và quán cà phê " + word, List.of(), List.of(towerId, cafeId, knownId))));
        verify(map, times(1)).lookup(anyCollection());

        List<Long> linked = jdbc.queryForList("SELECT place_id FROM blog_place_links WHERE blog_id = ? ORDER BY place_id",
                Long.class, id);
        assertThat(linked).hasSize(3).contains(knownPlace);
        var tower = jdbc.queryForMap("SELECT * FROM places WHERE osm_id = ?", towerId);
        assertThat(tower.get("source")).isEqualTo("OSM");
        assertThat(tower.get("status")).isEqualTo("ACTIVE");
        assertThat(tower.get("category_id")).isEqualTo("other");
        assertThat(tower.get("name_vi")).isEqualTo("Tháp " + word);
        assertThat(tower.get("name_ja")).isEqualTo("タワー" + word);
        assertThat(tower.get("name_en")).isEqualTo("Tower " + word);
        assertThat(tower.get("address")).isEqualTo("Shibakōen 4, Minato, Tokyo, Nhật Bản");
        assertThat(((BigDecimal) tower.get("lat")).doubleValue()).isCloseTo(lat + 0.01, org.assertj.core.data.Offset.offset(1e-6));
        assertThat(tower.get("google_place_id")).isNull();
        assertThat(((Number) tower.get("created_by")).longValue()).isEqualTo(author);
        var cafe = jdbc.queryForMap("SELECT category_id, name_vi, name_ja FROM places WHERE osm_id = ?", cafeId);
        assertThat(cafe.get("category_id")).isEqualTo("food");
        assertThat(cafe.get("name_ja")).isEqualTo("カフェ " + word);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM place_history WHERE place_id = ? AND action = 'CREATED'",
                Integer.class, tower.get("id"))).isEqualTo(1);

        // The post shows the places by their ASVSJ names, with their position for the map
        mvc.perform(get("/api/blogs/" + id))
                .andExpect(jsonPath("$.data.places[*].name", hasItem("Tháp " + word)))
                .andExpect(jsonPath("$.data.places[?(@.name == 'Tháp " + word + "')].lat").value(hasItem(closeTo(lat + 0.01))));

        // Now ASVSJ has them: the search lists them as ASVSJ places and a new post does not ask the map again
        mvc.perform(get("/api/places/search").param("q", word).session(session(author)))
                .andExpect(jsonPath("$.data.items", hasSize(3)))
                .andExpect(jsonPath("$.data.items[?(@.id == null)]").isEmpty());
        long second = created(send(body("Lần hai " + word, List.of(), List.of(towerId))));
        assertThat(jdbc.queryForObject("SELECT place_id FROM blog_place_links WHERE blog_id = ?", Long.class, second))
                .isEqualTo(((Number) tower.get("id")).longValue());
        verify(map, times(1)).lookup(anyCollection());

        // The OSM twin of a pin links the pin itself: no second place for the same restaurant
        long third = created(send(body("Quán Hana " + word, List.of(), List.of(pinTwinId))));
        assertThat(jdbc.queryForObject("SELECT place_id FROM blog_place_links WHERE blog_id = ?", Long.class, third))
                .isEqualTo(pin);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM places WHERE osm_id = ?", Integer.class, pinTwinId)).isZero();
    }

    @Test
    void oneUserCannotAddMoreThanTwentyFiveNewMapPlacesADay() throws Exception {
        // Unknown ids (the map says "not found"): each one counts
        String base = towerId.substring(1);
        for (int i = 0; i < PlaceService.LOOKUPS_PER_USER_DAY; i++) {
            send(body("Thử " + i + " " + word, List.of(), List.of("W" + base + i)))
                    .andExpect(jsonPath("$.data.placeIds").value(PlaceService.NOT_FOUND));
        }
        // An id already looked up is remembered: no new call, still "not found"
        send(body("Lại " + word, List.of(), List.of("W" + base + "0")))
                .andExpect(jsonPath("$.data.placeIds").value(PlaceService.NOT_FOUND));
        send(body("Hết lượt " + word, List.of(), List.of(towerId)))
                .andExpect(jsonPath("$.data.placeIds").value(PlaceService.DAILY));
        verify(map, never()).lookup(argThat(ids -> ids != null && ids.contains(towerId)));
        verify(map, times(1)).lookup(argThat(ids -> ids != null && ids.contains("W" + base + "0")));
        // Places ASVSJ already has still work
        created(send(body("Ga quen " + word, List.of(), List.of(knownId))));
    }

    @Test
    void unusableMapPlacesStopThePostAndSaveNothing() throws Exception {
        send(body("Đã gỡ " + word, List.of(), List.of(removedId)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.data.placeIds").value(PlaceService.GONE));

        String gone = "N" + (Long.parseLong(knownId.substring(1)) + 9);
        send(body("Không còn " + word, List.of(), List.of(gone)))
                .andExpect(jsonPath("$.data.placeIds").value(PlaceService.NOT_FOUND));

        String down = "N" + (Long.parseLong(knownId.substring(1)) + 10);
        when(map.lookup(argThat(ids -> ids != null && ids.contains(down))))
                .thenThrow(new ExternalPlacesException("OSM_ERROR", "503", null));
        send(body("Bản đồ lỗi " + word, List.of(), List.of(down)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(PlaceService.UNAVAILABLE));
        String slow = "N" + (Long.parseLong(knownId.substring(1)) + 11);
        when(map.lookup(argThat(ids -> ids != null && ids.contains(slow))))
                .thenThrow(new ExternalPlacesException("TIMEOUT", "Read timed out", null));
        send(body("Bản đồ chậm " + word, List.of(), List.of(slow)))
                .andExpect(jsonPath("$.data.placeIds").value(PlaceService.unavailable("TIMEOUT")));

        send(body("Sai mã " + word, List.of(), List.of("bad id!")))
                .andExpect(jsonPath("$.data.placeIds").value(PlaceService.INVALID));

        // A later check fails (banned keyword): the map was asked, but the place is not added
        send(body("Visa nhanh " + word, List.of(), List.of(towerId))).andExpect(status().isBadRequest());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM places WHERE osm_id = ?", Integer.class, towerId)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM blogs WHERE title LIKE ?", Integer.class, "%" + word + "%"))
                .isZero();
        // Field errors come first: the map is not asked for a post that lacks its content
        send("{\"title\":\"t " + word + "\",\"content\":\"\",\"topicIds\":[" + topic + "],\"externalPlaceIds\":[\""
                + cafeId + "\"]}").andExpect(jsonPath("$.data.content").exists());
        verify(map, never()).lookup(argThat(ids -> ids != null && ids.contains(cafeId)));
    }

    @Test
    void withoutTheMapTheSearchShowsAsvsjOnlyAndNewMapPlacesAreRefused() throws Exception {
        when(map.engine()).thenReturn("OFF");
        when(map.enabled()).thenReturn(false);
        mvc.perform(get("/api/places/search").param("q", word).session(session(author)))
                .andExpect(jsonPath("$.data.external").value("OFF"))
                .andExpect(jsonPath("$.data.items[*].id", contains((int) knownPlace)));
        send(body("Không bản đồ " + word, List.of(), List.of(towerId)))
                .andExpect(jsonPath("$.data.placeIds").value(PlaceService.NO_EXTERNAL));
        verify(map, never()).search(any());
        verify(map, never()).lookup(any());
        // An OSM place ASVSJ already has still works
        created(send(body("Ga quen " + word, List.of(), List.of(knownId))));
    }

    // ---------------------------------------------------------------- helpers

    private static org.hamcrest.Matcher<Double> closeTo(double value) {
        return org.hamcrest.Matchers.closeTo(value, 1e-6);
    }

    private long usage(String service) {
        return jdbc.queryForObject("SELECT COALESCE(SUM(amount), 0) FROM api_usage WHERE service = ? AND usage_date = ?",
                Long.class, service, Date.valueOf(LocalDate.now(JAPAN)));
    }

    private String body(String title, List<Long> placeIds, List<String> externalPlaceIds) {
        return "{\"title\":\"" + title + "\",\"content\":\"Nội dung\",\"topicIds\":[" + topic + "],\"placeIds\":"
                + placeIds + ",\"externalPlaceIds\":["
                + String.join(",", externalPlaceIds.stream().map(g -> "\"" + g + "\"").toList()) + "]}";
    }

    private ResultActions send(String json) throws Exception {
        return mvc.perform(post("/api/blogs").session(session(author))
                .cookie(new Cookie("XSRF-TOKEN", CSRF)).header("X-XSRF-TOKEN", CSRF)
                .contentType(MediaType.APPLICATION_JSON).content(json));
    }

    private static long created(ResultActions result) throws Exception {
        String body = result.andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return Long.parseLong(body.replaceAll(".*\"data\":\\{\"id\":(\\d+).*", "$1"));
    }

    private long user() {
        String email = "place-" + UUID.randomUUID() + "@test.local";
        jdbc.update("""
                INSERT INTO users (email, display_name, role, status, is_adult_confirmed, created_at)
                VALUES (?, 'Place Test', 'USER', 'ACTIVE', TRUE, ?)""",
                email, Timestamp.from(Instant.now().minus(Duration.ofDays(30))));
        return jdbc.queryForObject("SELECT id FROM users WHERE email = ?", Long.class, email);
    }

    private static MockHttpSession session(long userId) {
        AppUserPrincipal me = new AppUserPrincipal(userId, "u" + userId + "@test.local", "Place Test", "USER", "/");
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(SPRING_SECURITY_CONTEXT_KEY, new SecurityContextImpl(
                new UsernamePasswordAuthenticationToken(me, null, List.of(new SimpleGrantedAuthority("ROLE_USER")))));
        return session;
    }
}
