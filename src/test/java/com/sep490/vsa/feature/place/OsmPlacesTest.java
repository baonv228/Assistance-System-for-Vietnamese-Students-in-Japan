package com.sep490.vsa.feature.place;

import com.sep490.vsa.feature.place.ExternalPlaces.Hit;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/** The real HTTP client against local stand-ins for Photon and Nominatim: what it sends and how it reads answers. */
class OsmPlacesTest {

    private final Map<String, String> last = new ConcurrentHashMap<>();
    private final AtomicInteger calls = new AtomicInteger();
    /** What the client counted in api_usage, in order. */
    private final List<String> counted = new CopyOnWriteArrayList<>();
    private HttpServer server;
    private OsmPlaces places;
    private String base;
    private volatile int status = 200;
    private volatile String answer = "{}";

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::handle);
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort();
        places = new OsmPlaces(base + "/api/", base + "/lookup", "SenpaiOi-test/1.0", List.of("jp", " VN "), 0,
                counted::add);
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private void handle(HttpExchange exchange) throws IOException {
        calls.incrementAndGet();
        last.put("path", exchange.getRequestURI().getPath());
        last.put("raw", String.valueOf(exchange.getRequestURI().getRawQuery()));
        last.put("query", String.valueOf(exchange.getRequestURI().getQuery()));
        last.put("agent", String.valueOf(exchange.getRequestHeaders().getFirst("User-Agent")));
        byte[] out = answer.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, out.length);
        exchange.getResponseBody().write(out);
        exchange.close();
    }

    private static String feature(String type, long id, String key, String value, String name, String cc, double lon,
                                  double lat, String extra) {
        return """
                {"type": "Feature", "geometry": {"type": "Point", "coordinates": [%s, %s]},
                 "properties": {"osm_type": "%s", "osm_id": %d, "osm_key": "%s", "osm_value": "%s", "type": "house",
                                "name": "%s", "countrycode": "%s"%s}}""".formatted(lon, lat, type, id, key, value, name,
                cc, extra);
    }

    @Test
    void searchAsksPhotonInEnglishNearTokyoWithoutMapNoiseAndKeepsJapanAndVietnam() {
        answer = "{\"type\": \"FeatureCollection\", \"features\": [" + String.join(",",
                feature("N", 772389775, "railway", "station", "Nippori", "JP", 139.7713635, 35.7289204,
                        ", \"locality\": \"Higashi-Nippori 6-chome\", \"district\": \"Arakawa\", \"city\": \"Tokyo\","
                                + " \"street\": \"Ayame-dori\", \"country\": \"Japan\""),
                feature("N", 1910813740, "railway", "stop", "Nippori", "JP", 139.77, 35.72, ""),
                feature("W", 55441040, "tourism", "hotel", "Cerulean Tower Tokyo Hotel", "JP", 139.699, 35.656,
                        ", \"district\": \"Shibuya\", \"city\": \"Tokyo\", \"state\": \"東京都\""),
                feature("W", 55441040, "building", "hotel", "Cerulean Tower Tokyo Hotel", "JP", 139.699, 35.656, ""),
                feature("N", 427540552, "amenity", "restaurant", "Ho Guom", "DE", 11.56, 48.16, ""),
                feature("W", 1189686007, "amenity", "theatre", "Ho Guom Theatre", "VN", 105.8517801, 21.02217,
                        ", \"housenumber\": \"40\", \"street\": \"Phố Hàng Bài\", \"district\": \"Cua Nam Ward\","
                                + " \"city\": \"Hanoi\", \"state\": \"Hà Nội\""),
                feature("N", 1, "shop", "convenience", "", "JP", 139.7, 35.7, "")) + "]}";
        List<Hit> found = places.search("Hồ Gươm & co+1");

        assertThat(last.get("path")).isEqualTo("/api/");
        assertThat(last.get("agent")).isEqualTo("SenpaiOi-test/1.0");
        // The typed text is fully encoded, so "&" and "+" stay part of it
        assertThat(last.get("raw")).startsWith("q=H%E1%BB%93%20G%C6%B0%C6%A1m%20%26%20co%2B1&");
        assertThat(last.get("query")).contains("&limit=15&lang=en&lat=35.68&lon=139.76&layer=house&layer=other")
                .contains("&osm_tag=!highway").contains("&osm_tag=!railway:platform").contains("&osm_tag=!amenity:parking")
                .doesNotContain("osm_tag=railway:station");

        assertThat(found).containsExactly(
                new Hit("N772389775", "Nippori", null, null, null, "Higashi-Nippori 6-chome, Arakawa, Tokyo, Nhật Bản",
                        "railway=station", "jp", 35.7289204, 139.7713635),
                new Hit("W55441040", "Cerulean Tower Tokyo Hotel", null, null, null, "Shibuya, Tokyo, Nhật Bản",
                        "tourism=hotel", "jp", 35.656, 139.699),
                new Hit("W1189686007", "Ho Guom Theatre", null, null, null,
                        "40 Phố Hàng Bài, Cua Nam Ward, Hanoi, Việt Nam", "amenity=theatre", "vn", 21.02217, 105.8517801));
    }

    @Test
    void stationSearchLooksAmongStationsOnly() {
        answer = "{\"features\": [" + feature("N", 7786715957L, "railway", "station", "Ueno", "JP", 139.776, 35.711, "")
                + "]}";
        assertThat(places.searchStations("Ueno")).extracting(Hit::id).containsExactly("N7786715957");
        assertThat(last.get("query")).startsWith("q=Ueno&")
                .contains("&osm_tag=railway:station&osm_tag=railway:halt&osm_tag=building:train_station")
                .doesNotContain("layer=").doesNotContain("!highway");
    }

    @Test
    void lookupReadsNamesAddressAndPositionFromNominatim() {
        // Real Nominatim answers (format jsonv2, accept-language=en, namedetails, addressdetails), shortened
        answer = """
                [{"osm_type": "node", "osm_id": 772389775, "lat": "35.7289204", "lon": "139.7713635",
                  "category": "railway", "type": "station", "name": "Nippori",
                  "address": {"railway": "Nippori", "road": "あやめ通り", "neighbourhood": "Higashi Nippori 6",
                              "quarter": "Higashi-Nippori", "city": "Arakawa", "postcode": "116-0014",
                              "country": "Japan", "country_code": "jp"},
                  "namedetails": {"name": "日暮里", "name:en": "Nippori", "name:ja": "日暮里", "name:ko": "닛포리"}},
                 {"osm_type": "way", "osm_id": 1189686007, "lat": "21.0221700", "lon": "105.8517801",
                  "category": "amenity", "type": "theatre", "name": "Ho Guom Theatre",
                  "address": {"house_number": "40", "road": "Phố Hàng Bài", "suburb": "Cua Nam Ward", "city": "Hà Nội",
                              "country": "Vietnam", "country_code": "vn"},
                  "namedetails": {"name": "Nhà hát Hồ Gươm", "name:en": "Ho Guom Theatre"}},
                 {"osm_type": "relation", "osm_id": 10758384, "lat": "35.7139483", "lon": "139.7775579",
                  "category": "building", "type": "train_station", "name": "Ueno Railway Station",
                  "address": {"neighbourhood": "Ueno 7", "quarter": "Ueno", "city": "Taito", "province": "Tokyo",
                              "country": "Japan", "country_code": "jp"},
                  "namedetails": {"name:en": "Ueno Railway Station"}},
                 {"osm_type": "node", "osm_id": 427540552, "lat": "48.16", "lon": "11.56", "category": "amenity",
                  "type": "restaurant", "name": "Ho Guom", "address": {"country_code": "de"},
                  "namedetails": {"name": "Ho Guom"}}]""";
        Map<String, Hit> found = places.lookup(List.of("N772389775", "W1189686007", "R10758384", "N427540552",
                "DEV-tokyo-tower", "bad"));

        assertThat(last.get("path")).isEqualTo("/lookup");
        assertThat(last.get("query")).isEqualTo("osm_ids=N772389775,W1189686007,R10758384,N427540552"
                + "&format=jsonv2&accept-language=en&namedetails=1&addressdetails=1");
        assertThat(last.get("agent")).isEqualTo("SenpaiOi-test/1.0");
        assertThat(found).containsOnlyKeys("N772389775", "W1189686007", "R10758384");    // Germany is not served
        assertThat(found.get("N772389775")).isEqualTo(new Hit("N772389775", "Nippori", "Nippori", "日暮里", "Nippori",
                "Higashi Nippori 6, Higashi-Nippori, Arakawa, Nhật Bản", "railway=station", "jp", 35.7289204,
                139.7713635));
        // In Vietnam the local name is the Vietnamese one
        assertThat(found.get("W1189686007")).isEqualTo(new Hit("W1189686007", "Ho Guom Theatre", "Nhà hát Hồ Gươm",
                null, "Ho Guom Theatre", "40 Phố Hàng Bài, Cua Nam Ward, Hà Nội, Việt Nam", "amenity=theatre", "vn",
                21.02217, 105.8517801));
        assertThat(found.get("R10758384").nameVi()).isEqualTo("Ueno Railway Station");
        assertThat(found.get("R10758384").address()).isEqualTo("Ueno 7, Taito, Tokyo, Nhật Bản");

        // Only made-up or malformed ids: nothing to ask
        int before = calls.get();
        assertThat(places.lookup(List.of("DEV-x", "W0"))).isEmpty();
        assertThat(calls.get()).isEqualTo(before);
    }

    @Test
    void refusalsAndOutagesAreErrorsWithTheirReason() {
        status = 429;
        assertThat(reasonOf(() -> places.search("ueno"))).isEqualTo("LIMITED");
        status = 403;
        assertThat(reasonOf(() -> places.lookup(List.of("N1")))).isEqualTo("LIMITED");
        status = 500;
        answer = "{\"message\": \"internal error\"}";
        assertThat(reasonOf(() -> places.search("ueno"))).isEqualTo("OSM_ERROR");
        status = 400;
        assertThat(reasonOf(() -> places.lookup(List.of("N1")))).isEqualTo("OSM_ERROR");

        server.stop(0);                                    // nothing listens on the port any more
        assertThat(reasonOf(() -> places.search("ueno"))).isEqualTo("NETWORK");
    }

    @Test
    void unreachableNominatimFallsBackToRecentPhotonResults() throws IOException {
        int closed;
        try (java.net.ServerSocket probe = new java.net.ServerSocket(0)) {
            closed = probe.getLocalPort();                 // nothing listens there once the probe is closed
        }
        OsmPlaces cut = new OsmPlaces(base + "/api/", "http://127.0.0.1:" + closed + "/lookup", "SenpaiOi-test/1.0",
                List.of("jp"), 0, counted::add);
        answer = "{\"features\": [" + feature("N", 772389775, "railway", "station", "Nippori", "JP", 139.7713635,
                35.7289204, ", \"city\": \"Tokyo\"") + "]}";
        cut.search("nippori");

        assertThat(cut.lookup(List.of("N772389775"))).containsOnlyKeys("N772389775")
                .extractingByKey("N772389775").isEqualTo(new Hit("N772389775", "Nippori", null, null, null,
                        "Tokyo, Nhật Bản", "railway=station", "jp", 35.7289204, 139.7713635));
        // A place nobody searched cannot be checked: the outage is reported
        assertThat(reasonOf(() -> cut.lookup(List.of("N1")))).isEqualTo("NETWORK");
    }

    @Test
    void nominatimIsAskedAtMostOnceASecond() {
        answer = "[]";
        OsmPlaces paced = new OsmPlaces(base + "/api/", base + "/lookup", "SenpaiOi-test/1.0", List.of(), 400,
                counted::add);
        long start = System.nanoTime();
        paced.lookup(List.of("N1"));
        paced.lookup(List.of("N2"));
        paced.lookup(List.of("N3"));
        assertThat((System.nanoTime() - start) / 1_000_000).isGreaterThanOrEqualTo(800);
        assertThat(calls.get()).isEqualTo(3);
    }

    @Test
    void lookupLeavesOutMapNoiseUnlessTheSearchOfferedIt() {
        String station = """
                {"osm_type": "node", "osm_id": 772389775, "lat": "35.7289204", "lon": "139.7713635",
                 "category": "railway", "type": "station", "name": "Nippori",
                 "address": {"country_code": "jp"}, "namedetails": {"name": "日暮里"}}""";
        String boundary = """
                {"osm_type": "relation", "osm_id": 1758858, "lat": "35.7126", "lon": "139.78",
                 "category": "boundary", "type": "administrative", "name": "Taito",
                 "address": {"country_code": "jp"}, "namedetails": {"name": "台東区"}}""";
        String street = """
                {"osm_type": "way", "osm_id": 25485829, "lat": "35.6717", "lon": "139.7036",
                 "category": "highway", "type": "pedestrian", "name": "Takeshita Street",
                 "address": {"country_code": "jp"}, "namedetails": {"name": "竹下通り"}}""";
        String lookupAnswer = "[" + station + "," + boundary + "," + street + "]";
        List<String> ids = List.of("N772389775", "R1758858", "W25485829");

        // Ids sent by hand: a district boundary and a street are not places to link
        answer = lookupAnswer;
        assertThat(places.lookup(ids)).containsOnlyKeys("N772389775");

        // The search offered the street (Photon lists it as a tourist attraction): picked there, it can be linked
        answer = "{\"features\": [" + feature("W", 25485829, "tourism", "attraction", "Takeshita Street", "JP", 139.7036,
                35.6717, "") + "]}";
        assertThat(places.search("takeshita")).extracting(Hit::id).containsExactly("W25485829");
        answer = lookupAnswer;
        assertThat(places.lookup(ids)).containsOnlyKeys("N772389775", "W25485829");
    }

    @Test
    void everyRequestSentIsCountedOnceAnsweredOrNot() throws IOException {
        String search = OsmPlaces.SEARCH_USAGE;
        String lookup = OsmPlaces.LOOKUP_USAGE;
        answer = "{\"features\": []}";
        places.search("ueno");
        places.searchStations("Ueno");
        answer = "[]";
        places.lookup(List.of("N1"));
        assertThat(counted).containsExactly(search, search, lookup);

        // Failed requests count too: a refusal (Photon, then the Nominatim search it falls back to), an error answer
        status = 429;
        assertThat(reasonOf(() -> places.search("ueno"))).isEqualTo("LIMITED");
        status = 500;
        assertThat(reasonOf(() -> places.lookup(List.of("N2")))).isEqualTo("OSM_ERROR");
        assertThat(counted).containsExactly(search, search, lookup, search, lookup, lookup);
        assertThat(calls.get()).isEqualTo(6);

        // Nothing sent, nothing counted: only made-up or malformed ids
        status = 200;
        assertThat(places.lookup(List.of("DEV-tokyo-tower", "bad"))).isEmpty();
        assertThat(counted).hasSize(6);

        // Nominatim cannot be reached: the attempt counts; during the pause after it the fallback sends nothing
        int closed;
        try (java.net.ServerSocket probe = new java.net.ServerSocket(0)) {
            closed = probe.getLocalPort();
        }
        OsmPlaces cut = new OsmPlaces(base + "/api/", "http://127.0.0.1:" + closed + "/lookup", "SenpaiOi-test/1.0",
                List.of("jp"), 0, counted::add);
        answer = "{\"features\": [" + feature("N", 772389775, "railway", "station", "Nippori", "JP", 139.7713635,
                35.7289204, "") + "]}";
        cut.search("nippori");
        assertThat(cut.lookup(List.of("N772389775"))).containsOnlyKeys("N772389775");
        assertThat(cut.lookup(List.of("N772389775"))).containsOnlyKeys("N772389775");
        assertThat(counted.subList(6, counted.size())).containsExactly(search, lookup);

        // A counter that fails never breaks the search
        OsmPlaces broken = new OsmPlaces(base + "/api/", base + "/lookup", "SenpaiOi-test/1.0", List.of("jp"), 0,
                service -> {
                    throw new IllegalStateException("database down");
                });
        assertThat(broken.search("nippori")).extracting(Hit::id).containsExactly("N772389775");
    }

    private static String reasonOf(org.assertj.core.api.ThrowableAssert.ThrowingCallable call) {
        try {
            call.call();
        } catch (ExternalPlacesException e) {
            return e.reason();
        } catch (Throwable other) {
            throw new AssertionError("unexpected " + other, other);
        }
        throw new AssertionError("no ExternalPlacesException");
    }
}
