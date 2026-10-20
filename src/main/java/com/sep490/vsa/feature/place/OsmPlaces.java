package com.sep490.vsa.feature.place;

import com.fasterxml.jackson.databind.JsonNode;
import com.sep490.vsa.common.text.VietnameseText;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.util.DefaultUriBuilderFactory;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLException;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;
import java.io.IOException;
import java.net.ConnectException;
import java.net.HttpURLConnection;
import java.net.NoRouteToHostException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * OpenStreetMap places through two free public services (no key, no billing):
 * <ul>
 *   <li><b>Search</b>: Photon ({@code GET https://photon.komoot.io/api/}), made for search-as-you-type. Names in English
 *       or romaji where the map has them, places near Tokyo ranked higher. Map features nobody links to a post
 *       (streets, bus stops, platforms, parking…) are filtered out by Photon itself, then only the configured
 *       countries are kept. Photon understands Japanese, English/romaji and Vietnamese (for places in Vietnam).</li>
 *   <li><b>Lookup</b>: Nominatim ({@code GET https://nominatim.openstreetmap.org/lookup}), when a post is sent, to check
 *       the places it links and read their names (Vietnamese, Japanese, English), address and coordinates. One request
 *       per post, and at most one request a second for the whole server, as the Nominatim usage policy asks. The policy
 *       does not allow search-as-you-type, which is why the search uses Photon.</li>
 * </ul>
 * Both services ask every app to name itself in the User-Agent ({@code app.places.osm.user-agent}).
 * On Windows the client also trusts the Windows certificate store, like browsers do: antivirus software that scans
 * HTTPS (Kaspersky, Avast, ESET…) re-signs the sites' certificates with its own root, which Java alone rejects.
 */
public class OsmPlaces implements ExternalPlaces {

    private static final Logger log = LoggerFactory.getLogger(OsmPlaces.class);

    /** Asked from Photon; places in other countries are dropped afterwards. */
    static final int SEARCH_ASKED = 15;
    /** Kept after dropping places in other countries. */
    static final int SEARCH_KEPT = 10;
    /** Tokyo: Photon ranks places near it higher (most users live in Japan, many around Tokyo). */
    static final double BIAS_LAT = 35.68;
    static final double BIAS_LON = 139.76;
    /** Nominatim usage policy: an absolute maximum of 1 request per second. */
    static final long LOOKUP_GAP_MS = 1_100;
    /** How long a lookup waits for its turn behind others before giving up (reason BUSY). */
    static final long LOOKUP_WAIT_MS = 6_000;
    /** Photon results are kept this long for the lookup fallback (a post is written within that time). */
    static final long SEEN_KEEP_MS = 3 * 3_600_000L;
    static final int SEEN_MAX = 5_000;
    /** After Nominatim could not be reached, the fallback answers without trying it again for this long. */
    static final long NOMINATIM_RETRY_MS = 10 * 60_000L;
    static final Pattern OSM_ID =Pattern.compile("[NWR][1-9][0-9]{0,14}");

    /**
     * OSM tags Photon leaves out of the search: streets, crossings, bus stops, platforms, entrances, rails, parking,
     * boundaries… ("key" or "key:value"). Stations stay (railway=station, building=train_station).
     */
    static final List<String> LEFT_OUT = List.of("highway", "junction", "emergency", "landuse", "power", "barrier",
            "boundary", "place", "waterway", "information", "entrance",
            "railway:stop", "railway:platform", "railway:rail", "railway:subway", "railway:light_rail",
            "railway:monorail", "railway:tram", "railway:tram_stop", "railway:subway_entrance",
            "railway:train_station_entrance", "railway:level_crossing", "railway:crossing", "railway:switch",
            "railway:buffer_stop", "public_transport:platform", "public_transport:stop_position",
            "public_transport:stop_area", "amenity:parking", "amenity:parking_entrance", "amenity:bicycle_parking",
            "amenity:motorcycle_parking", "amenity:taxi", "amenity:vending_machine", "amenity:bench",
            "amenity:waste_basket");
    /** Tags of a station, for "ga Ueno" (Photon then only looks among stations). */
    static final List<String> STATION_TAGS = List.of("railway:station", "railway:halt", "building:train_station");

    private final RestClient client;
    private final String searchTemplate;
    private final String stationTemplate;
    private final String taggedBase;
    private final String reverseTemplate;
    private final RestClient photon;
    private final String nominatimSearchTemplate;
    private final String nominatimReverseTemplate;
    /** After Photon failed or was too slow, searches go straight to Nominatim until then. */
    private volatile long photonDownUntil;
    private final String lookupTemplate;
    private final List<String> regions;
    private final long lookupGapMs;
    private final ReentrantLock lookupTurn = new ReentrantLock(true);
    private long lastLookup;
    /** Places Photon recently answered, for when Nominatim cannot be reached. */
    private final Map<String, Seen> seen = new ConcurrentHashMap<>();
    private volatile long nominatimDownUntil;

    private record Seen(Hit hit, long at) {
    }

    /**
     * @param searchUrl Photon's search address, e.g. https://photon.komoot.io/api/
     * @param lookupUrl Nominatim's lookup address, e.g. https://nominatim.openstreetmap.org/lookup
     * @param regions   two-letter country codes the places must be in; empty = the whole world
     */
    public OsmPlaces(String searchUrl, String lookupUrl, String userAgent, List<String> regions) {
        this(searchUrl, lookupUrl, userAgent, regions, LOOKUP_GAP_MS);
    }

    OsmPlaces(String searchUrl, String lookupUrl, String userAgent, List<String> regions, long lookupGapMs) {
        SSLSocketFactory ssl = windowsAwareSsl();
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory() {
            @Override
            protected void prepareConnection(HttpURLConnection connection, String httpMethod) throws IOException {
                super.prepareConnection(connection, httpMethod);
                if (ssl != null && connection instanceof HttpsURLConnection https) {
                    https.setSSLSocketFactory(ssl);
                }
            }
        };
        factory.setConnectTimeout(5_000);
        factory.setReadTimeout(8_000);
        // Photon is asked first but only waited for briefly: the public server is sometimes overloaded (10–20 s per
        // answer), and then Nominatim answers instead
        SimpleClientHttpRequestFactory quick = new SimpleClientHttpRequestFactory() {
            @Override
            protected void prepareConnection(HttpURLConnection connection, String httpMethod) throws IOException {
                super.prepareConnection(connection, httpMethod);
                if (ssl != null && connection instanceof HttpsURLConnection https) {
                    https.setSSLSocketFactory(ssl);
                }
            }
        };
        quick.setConnectTimeout(3_000);
        quick.setReadTimeout(PHOTON_WAIT_MS);
        // The typed text goes in as a URI variable and is fully encoded ("+" and "&" included)
        DefaultUriBuilderFactory uris = new DefaultUriBuilderFactory();
        uris.setEncodingMode(DefaultUriBuilderFactory.EncodingMode.TEMPLATE_AND_VALUES);
        this.client = RestClient.builder()
                .requestFactory(factory)
                .uriBuilderFactory(uris)
                .defaultHeader("User-Agent", userAgent)
                .defaultHeader("Accept", "application/json")
                .build();
        this.photon = RestClient.builder()
                .requestFactory(quick)
                .uriBuilderFactory(uris)
                .defaultHeader("User-Agent", userAgent)
                .defaultHeader("Accept", "application/json")
                .build();
        String nominatim = lookupUrl.replaceFirst("/lookup/?$", "");
        List<String> codes = regions.stream().map(r -> r.strip().toLowerCase(Locale.ROOT)).filter(r -> !r.isEmpty())
                .toList();
        String countries = codes.isEmpty() ? "" : "&countrycodes=" + String.join(",", codes);
        this.nominatimSearchTemplate = nominatim + "/search?q={q}&format=jsonv2&limit=10&accept-language=en"
                + "&namedetails=1&addressdetails=1" + countries;
        this.nominatimReverseTemplate = nominatim + "/reverse?lat={lat}&lon={lon}&zoom=18&format=jsonv2"
                + "&accept-language=en&namedetails=1&addressdetails=1";
        String search = searchUrl + "?q={q}&limit={limit}&lang=en&lat={lat}&lon={lon}";
        this.searchTemplate = search + "&layer=house&layer=other"
                + LEFT_OUT.stream().map(t -> "&osm_tag=!" + t).collect(Collectors.joining());
        this.stationTemplate = search + STATION_TAGS.stream().map(t -> "&osm_tag=" + t).collect(Collectors.joining());
        this.taggedBase = search;
        // Photon reverse: the places around a point the user clicked on the map (radius in km)
        this.reverseTemplate = searchUrl.replaceFirst("/api/?$", "/reverse")
                + "?lat={lat}&lon={lon}&limit={limit}&radius={radius}&lang=en&layer=house&layer=other"
                + LEFT_OUT.stream().map(t -> "&osm_tag=!" + t).collect(Collectors.joining());
        this.lookupTemplate = lookupUrl
                + "?osm_ids={ids}&format=jsonv2&accept-language=en&namedetails=1&addressdetails=1";
        this.regions = regions.stream().map(r -> r.strip().toLowerCase(Locale.ROOT)).filter(r -> !r.isEmpty()).toList();
        this.lookupGapMs = lookupGapMs;
    }

    @Override
    public String engine() {
        return "OSM";
    }

    // ---------------------------------------------------------------- search (Photon)

    @Override
    public List<Hit> search(String text) {
        return search(searchTemplate, text, text);
    }

    @Override
    public List<Hit> searchStations(String name) {
        return search(stationTemplate, name, name + " station");
    }

    /** Among places with one of these tags ("leisure:park"): "công viên Ueno" is "Ueno" among parks. */
    @Override
    public List<Hit> searchTagged(String text, List<String> osmTags) {
        List<String> tags = osmTags.stream().filter(t -> t.matches("[a-z_]+:[a-z_]+")).toList();
        String template = taggedBase + tags.stream().map(t -> "&osm_tag=" + t).collect(Collectors.joining());
        Set<String> wanted = tags.stream().map(t -> t.replace(':', '=')).collect(Collectors.toSet());
        // Nominatim (the fallback) has no tag filter: keep only places of the asked kind ("Ueno" finds the station too)
        return search(template, text, text).stream().filter(h -> wanted.contains(h.tag())).toList();
    }

    /**
     * Named places within about {@value #NEARBY_RADIUS_KM} km of a point, nearest first (Photon reverse). When Photon
     * is down or slow: the place Nominatim finds at that point (one, the nearest named building or shop).
     */
    @Override
    public List<Hit> nearby(double lat, double lng) {
        if (System.currentTimeMillis() >= photonDownUntil) {
            try {
                Map<String, Object> vars = Map.of("lat", lat, "lon", lng, "limit", SEARCH_ASKED,
                        "radius", NEARBY_RADIUS_KM);
                return hits(call("Photon reverse",
                        () -> photon.get().uri(reverseTemplate, vars).retrieve().body(JsonNode.class)));
            } catch (ExternalPlacesException e) {
                photonFailed(e);
            }
        }
        Map<String, Object> vars = Map.of("lat", lat, "lon", lng);
        JsonNode place = paced(() -> call("Nominatim reverse",
                () -> client.get().uri(nominatimReverseTemplate, vars).retrieve().body(JsonNode.class)));
        Hit hit = place == null ? null : nominatimHit(place);
        return hit == null || !inRegions(hit.countryCode()) || noise(hit.tag()) ? List.of() : List.of(hit);
    }

    static final double NEARBY_RADIUS_KM = 0.15;
    /** How long Photon is waited for before Nominatim is asked instead. */
    static final int PHOTON_WAIT_MS = 4_000;
    /** After a Photon failure, how long searches skip it. */
    static final long PHOTON_RETRY_MS = 2 * 60_000L;

    /**
     * Photon first ({@code template} with {@code text}); when it is down or slower than {@value #PHOTON_WAIT_MS} ms,
     * Nominatim's search with {@code fallbackText} (Nominatim has no tag filter, so "Ueno park" rather than "Ueno").
     */
    private List<Hit> search(String template, String text, String fallbackText) {
        if (System.currentTimeMillis() >= photonDownUntil) {
            try {
                Map<String, Object> vars = Map.of("q", text, "limit", SEARCH_ASKED, "lat", BIAS_LAT, "lon", BIAS_LON);
                return hits(call("Photon search", () -> photon.get().uri(template, vars).retrieve().body(JsonNode.class)));
            } catch (ExternalPlacesException e) {
                photonFailed(e);
            }
        }
        return nominatimSearch(fallbackText);
    }

    private void photonFailed(ExternalPlacesException e) {
        photonDownUntil = System.currentTimeMillis() + PHOTON_RETRY_MS;
        log.info("Photon {} ({}): Nominatim answers for the next {} minutes", e.reason(), e.getMessage(),
                PHOTON_RETRY_MS / 60_000);
    }

    /** Nominatim's search (1 request a second for the whole server, like the lookups), map noise left out. */
    private List<Hit> nominatimSearch(String text) {
        Map<String, Object> vars = Map.of("q", text);
        JsonNode response = paced(() -> call("Nominatim search",
                () -> client.get().uri(nominatimSearchTemplate, vars).retrieve().body(JsonNode.class)));
        List<Hit> hits = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        if (response == null) {
            return hits;
        }
        for (JsonNode place : response) {
            Hit hit = nominatimHit(place);
            if (hit != null && inRegions(hit.countryCode()) && !noise(hit.tag()) && ids.add(hit.id())) {
                hits.add(hit);
                remember(hit);
            }
        }
        return hits;
    }

    private static boolean noise(String tag) {
        int eq = tag.indexOf('=');
        return eq < 0 || noise(tag.substring(0, eq), tag.substring(eq + 1));
    }

    private List<Hit> hits(JsonNode response) {
        List<Hit> hits = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        if (response == null) {
            return hits;
        }
        for (JsonNode feature : response.path("features")) {
            Hit hit = photonHit(feature);
            // Photon lists a place once per tag (a hotel that is also an assembly point): keep the first
            if (hit != null && inRegions(hit.countryCode()) && ids.add(hit.id())) {
                hits.add(hit);
                remember(hit);
                if (hits.size() == SEARCH_KEPT) {
                    break;
                }
            }
        }
        return hits;
    }

    /** One Photon result (GeoJSON feature), or null when it lacks an id, a name or a position, or is map noise. */
    static Hit photonHit(JsonNode feature) {
        JsonNode p = feature.path("properties");
        String type = p.path("osm_type").asText("");
        long number = p.path("osm_id").asLong(0);
        String key = p.path("osm_key").asText("");
        String value = p.path("osm_value").asText("");
        String name = text(p, "name");
        JsonNode coordinates = feature.path("geometry").path("coordinates");
        if (!type.matches("[NWR]") || number <= 0 || name == null || coordinates.size() < 2 || noise(key, value)) {
            return null;
        }
        String cc = p.path("countrycode").asText("").toLowerCase(Locale.ROOT);
        return new Hit(type + number, name, null, null, null, photonAddress(p, cc), key + "=" + value, cc,
                coordinates.get(1).asDouble(), coordinates.get(0).asDouble());
    }

    /** Map features nobody links to a post. Photon already leaves most out ({@link #LEFT_OUT}); this is the check. */
    static boolean noise(String key, String value) {
        return switch (key) {
            case "highway", "junction", "emergency", "landuse", "power", "barrier", "boundary", "place", "waterway",
                 "information", "entrance", "route" -> true;
            case "railway" -> !"station".equals(value) && !"halt".equals(value);
            case "public_transport" -> !"station".equals(value);
            case "amenity" -> Set.of("parking", "parking_space", "parking_entrance", "bicycle_parking",
                    "motorcycle_parking", "taxi", "vending_machine", "bench", "waste_basket").contains(value);
            default -> false;
        };
    }

    /** "Ueno 5, Taito, Tokyo, Nhật Bản". Japanese addresses have no street names, so the street is left out there. */
    static String photonAddress(JsonNode p, String cc) {
        List<String> parts = new ArrayList<>();
        if (!"jp".equals(cc)) {
            parts.add(joined(" ", text(p, "housenumber"), text(p, "street")));
        }
        parts.add(text(p, "locality"));
        parts.add(text(p, "district"));
        parts.add(first(text(p, "city"), text(p, "county"), text(p, "state")));
        parts.add(countryName(cc, text(p, "country")));
        return address(parts);
    }

    // ---------------------------------------------------------------- lookup (Nominatim)

    /**
     * Nominatim first (Vietnamese and Japanese names). When it cannot be reached (some networks in Vietnam cut the
     * connection to nominatim.openstreetmap.org while Photon works), the places are taken from recent Photon results,
     * which is where the user picked them: name, address, tag and position, without the extra names.
     */
    @Override
    public Map<String, Hit> lookup(Collection<String> ids) {
        List<String> asked = ids.stream().filter(id -> id != null && OSM_ID.matcher(id).matches()).distinct()
                .limit(50).toList();
        if (asked.isEmpty()) {
            return new LinkedHashMap<>();
        }
        if (System.currentTimeMillis() < nominatimDownUntil) {
            return fromSearches(asked, null);
        }
        try {
            return nominatimLookup(asked);
        } catch (ExternalPlacesException e) {
            if (Set.of("SSL", "TIMEOUT", "DNS", "NETWORK").contains(e.reason())) {
                nominatimDownUntil = System.currentTimeMillis() + NOMINATIM_RETRY_MS;
            }
            return fromSearches(asked, e);
        }
    }

    /** The asked places from recent Photon results; when one is not there, the lookup failure (if any) is thrown. */
    private Map<String, Hit> fromSearches(List<String> asked, ExternalPlacesException failure) {
        long oldest = System.currentTimeMillis() - SEEN_KEEP_MS;
        Map<String, Hit> found = new LinkedHashMap<>();
        for (String id : asked) {
            Seen s = seen.get(id);
            if (s == null || s.at() < oldest) {
                throw failure != null ? failure
                        : failed("Nominatim lookup", "NETWORK", "unreachable a moment ago, " + id + " not searched", null);
            }
            found.put(id, s.hit());
        }
        log.info("Nominatim unreachable: took {} place(s) from recent Photon results", found.size());
        return found;
    }

    private Map<String, Hit> nominatimLookup(List<String> asked) {
        Map<String, Hit> found = new LinkedHashMap<>();
        Map<String, Object> vars = Map.of("ids", String.join(",", asked));
        JsonNode response = paced(() -> call("Nominatim lookup",
                () -> client.get().uri(lookupTemplate, vars).retrieve().body(JsonNode.class)));
        if (response == null) {
            return found;
        }
        for (JsonNode place : response) {
            Hit hit = nominatimHit(place);
            if (hit != null && asked.contains(hit.id()) && inRegions(hit.countryCode())) {
                found.put(hit.id(), hit);
            }
        }
        return found;
    }

    /**
     * One Nominatim result (format jsonv2 with namedetails and addressdetails), or null without an id, a name or a
     * position. Vietnamese name: name:vi, else the local name in Vietnam, else the English name, else the local name.
     */
    static Hit nominatimHit(JsonNode place) {
        String type = switch (place.path("osm_type").asText("")) {
            case "node" -> "N";
            case "way" -> "W";
            case "relation" -> "R";
            default -> null;
        };
        long number = place.path("osm_id").asLong(0);
        Double lat = decimal(place.path("lat"));
        Double lng = decimal(place.path("lon"));
        if (type == null || number <= 0 || lat == null || lng == null) {
            return null;
        }
        JsonNode names = place.path("namedetails");
        JsonNode address = place.path("address");
        String cc = address.path("country_code").asText("").toLowerCase(Locale.ROOT);
        String local = text(names, "name");
        String en = text(names, "name:en");
        String shown = text(place, "name");               // English where the map has it (accept-language=en)
        String nameVi = first(text(names, "name:vi"), "vn".equals(cc) ? local : null, en, shown, local);
        if (nameVi == null) {
            return null;
        }
        String nameJa = first(text(names, "name:ja"), "jp".equals(cc) ? local : null);
        String tag = place.path("category").asText("") + "=" + place.path("type").asText("");
        return new Hit(type + number, first(shown, nameVi), nameVi, nameJa, en, nominatimAddress(address, cc), tag, cc,
                lat, lng);
    }

    /** "Ueno 5, Ueno, Taito, Nhật Bản" or "40 Phố Hàng Bài, Cua Nam Ward, Hà Nội, Việt Nam". */
    static String nominatimAddress(JsonNode a, String cc) {
        List<String> parts = new ArrayList<>();
        if (!"jp".equals(cc)) {
            parts.add(joined(" ", text(a, "house_number"), text(a, "road")));
        }
        parts.add(text(a, "neighbourhood"));
        parts.add(first(text(a, "quarter"), text(a, "suburb")));
        parts.add(text(a, "city_district"));
        parts.add(first(text(a, "city"), text(a, "town"), text(a, "village"), text(a, "municipality"),
                text(a, "county")));
        parts.add(first(text(a, "state"), text(a, "province")));
        parts.add(countryName(cc, text(a, "country")));
        return address(parts);
    }

    /** At most one Nominatim request a second for the whole server; callers wait their turn (up to a few seconds). */
    private <T> T paced(Supplier<T> request) {
        boolean turn;
        try {
            turn = lookupTurn.tryLock(LOOKUP_WAIT_MS, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            turn = false;
        }
        if (!turn) {
            throw failed("Nominatim lookup", "BUSY", "other lookups are still waiting for their turn", null);
        }
        try {
            long wait = lastLookup + lookupGapMs - System.currentTimeMillis();
            if (wait > 0) {
                Thread.sleep(wait);
            }
            return request.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw failed("Nominatim lookup", "BUSY", "interrupted while waiting", e);
        } finally {
            lastLookup = System.currentTimeMillis();
            lookupTurn.unlock();
        }
    }

    // ---------------------------------------------------------------- helpers

    private void remember(Hit hit) {
        long now = System.currentTimeMillis();
        if (seen.size() >= SEEN_MAX) {
            seen.values().removeIf(s -> s.at() < now - SEEN_KEEP_MS);
            if (seen.size() >= SEEN_MAX) {
                seen.clear();
            }
        }
        seen.put(hit.id(), new Seen(hit, now));
    }

    private boolean inRegions(String countryCode) {
        return regions.isEmpty() || regions.contains(countryCode);
    }

    static String countryName(String cc, String given) {
        return switch (cc) {
            case "jp" -> "Nhật Bản";
            case "vn" -> "Việt Nam";
            default -> given;
        };
    }

    /** The parts that are there, without one already said by another ("Osaka Prefecture" after "Osaka"). */
    static String address(List<String> parts) {
        List<String> kept = new ArrayList<>();
        List<String> folded = new ArrayList<>();
        for (String part : parts) {
            if (part == null || part.isBlank()) {
                continue;
            }
            String f = VietnameseText.fold(part);
            if (folded.stream().noneMatch(k -> k.contains(f) || f.contains(k))) {
                kept.add(part.strip());
                folded.add(f);
            }
        }
        return kept.isEmpty() ? null : String.join(", ", kept);
    }

    private static String text(JsonNode node, String field) {
        String value = node.path(field).asText("").strip();
        return value.isEmpty() ? null : value;
    }

    private static String first(String... values) {
        return Arrays.stream(values).filter(v -> v != null && !v.isBlank()).findFirst().orElse(null);
    }

    private static String joined(String separator, String... values) {
        String out = Arrays.stream(values).filter(v -> v != null && !v.isBlank()).collect(Collectors.joining(separator));
        return out.isEmpty() ? null : out;
    }

    private static Double decimal(JsonNode node) {
        try {
            return node.isNumber() ? node.asDouble() : Double.valueOf(node.asText(""));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static JsonNode call(String service, Supplier<JsonNode> request) {
        try {
            return request.get();
        } catch (RestClientResponseException e) {
            int status = e.getStatusCode().value();
            // Nominatim answers 403 to an app it blocked and 429 when asked too often; Photon 429 under heavy load
            String reason = status == 429 || status == 403 || status == 509 ? "LIMITED" : "OSM_ERROR";
            String body = e.getResponseBodyAsString().replaceAll("\\s+", " ").strip();
            throw failed(service, reason, status + " " + (body.length() > 200 ? body.substring(0, 200) : body), e);
        } catch (RestClientException e) {
            throw failed(service, networkReason(e), rootMessage(e), e);
        }
    }

    /** No answer: the cause tells whether it was the certificate, a timeout, DNS or the network. */
    private static String networkReason(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof SSLException) {
                return "SSL";
            }
            if (t instanceof SocketTimeoutException) {
                return "TIMEOUT";
            }
            if (t instanceof UnknownHostException) {
                return "DNS";
            }
            if (t instanceof ConnectException || t instanceof NoRouteToHostException) {
                return "NETWORK";
            }
        }
        return "NETWORK";
    }

    private static String rootMessage(Throwable e) {
        Throwable root = e;
        while (root.getCause() != null) {
            root = root.getCause();
        }
        return root.getClass().getSimpleName() + ": " + root.getMessage();
    }

    private static ExternalPlacesException failed(String service, String reason, String detail, Exception cause) {
        String message = "OpenStreetMap " + service + " failed (" + reason + "): " + detail;
        log.warn(message.length() > 400 ? message.substring(0, 400) : message);
        return new ExternalPlacesException(reason, message, cause);
    }

    /**
     * On Windows: trust Java's own certificates and those of the Windows store (where antivirus HTTPS scanning and
     * company proxies put their root). Elsewhere, or if the store cannot be read: null, Java's default is used.
     */
    static SSLSocketFactory windowsAwareSsl() {
        if (!System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows")) {
            return null;
        }
        try {
            X509TrustManager java = trustManager(null);
            KeyStore windows = KeyStore.getInstance("Windows-ROOT");
            windows.load(null, null);
            X509TrustManager system = trustManager(windows);
            X509TrustManager both = new X509TrustManager() {
                @Override
                public void checkClientTrusted(X509Certificate[] chain, String authType) throws CertificateException {
                    java.checkClientTrusted(chain, authType);
                }

                @Override
                public void checkServerTrusted(X509Certificate[] chain, String authType) throws CertificateException {
                    try {
                        java.checkServerTrusted(chain, authType);
                    } catch (CertificateException notInJava) {
                        system.checkServerTrusted(chain, authType);
                    }
                }

                @Override
                public X509Certificate[] getAcceptedIssuers() {
                    return Stream.concat(Arrays.stream(java.getAcceptedIssuers()),
                            Arrays.stream(system.getAcceptedIssuers())).toArray(X509Certificate[]::new);
                }
            };
            SSLContext context = SSLContext.getInstance("TLS");
            context.init(null, new TrustManager[]{both}, null);
            return context.getSocketFactory();
        } catch (GeneralSecurityException | IOException e) {
            log.warn("Could not read the Windows certificate store, using Java's certificates only: {}", e.getMessage());
            return null;
        }
    }

    private static X509TrustManager trustManager(KeyStore store) throws GeneralSecurityException {
        TrustManagerFactory factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        factory.init(store);
        return Arrays.stream(factory.getTrustManagers()).filter(X509TrustManager.class::isInstance)
                .map(X509TrustManager.class::cast).findFirst()
                .orElseThrow(() -> new GeneralSecurityException("no X509TrustManager"));
    }
}
