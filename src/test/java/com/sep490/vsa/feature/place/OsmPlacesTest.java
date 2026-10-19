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
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/** The real HTTP client against local stand-ins for Photon and Nominatim: what it sends and how it reads answers. */
class OsmPlacesTest {

    private final Map<String, String> last = new ConcurrentHashMap<>();
    private final AtomicInteger calls = new AtomicInteger();
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
        places = new OsmPlaces(base + "/api/", base + "/lookup", "SenpaiOi-test/1.0", List.of("jp", " VN "), 0);
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
                List.of("jp"), 0);
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
        OsmPlaces paced = new OsmPlaces(base + "/api/", base + "/lookup", "SenpaiOi-test/1.0", List.of(), 400);
        long start = System.nanoTime();
        paced.lookup(List.of("N1"));
        paced.lookup(List.of("N2"));
        paced.lookup(List.of("N3"));
        assertThat((System.nanoTime() - start) / 1_000_000).isGreaterThanOrEqualTo(800);
        assertThat(calls.get()).isEqualTo(3);
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
