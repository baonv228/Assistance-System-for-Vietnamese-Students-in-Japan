package com.sep490.vsa.feature.place;

import com.sep490.vsa.common.text.VietnameseText;
import com.sep490.vsa.feature.place.ExternalPlaces.Hit;
import com.sep490.vsa.feature.place.PlaceDtos.PlaceOption;
import com.sep490.vsa.feature.place.PlaceDtos.PlaceSearch;
import com.sep490.vsa.feature.place.PlaceRepository.Category;
import com.sep490.vsa.feature.place.PlaceRepository.Known;
import com.sep490.vsa.feature.place.PlaceRepository.Nearby;
import com.sep490.vsa.feature.place.PlaceRepository.NewOsmPlace;
import org.springframework.stereotype.Service;

import java.text.Normalizer;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * "Địa điểm liên quan" of Write Post and Đặt câu hỏi: search ASVSJ places and OpenStreetMap together, and turn the
 * OpenStreetMap places a post links into ASVSJ places.
 * <ul>
 *   <li><b>Search</b> (SRS Map 3.(1): "ASVSJ data first, then map suggestions"): approved places of any source
 *       (OpenStreetMap places already in ASVSJ, team places, approved community pins, places saved from Google Maps
 *       before), then OpenStreetMap places in Japan and Vietnam. A map place ASVSJ already has is shown once, as the
 *       ASVSJ place: by its OSM id, or by the same name close by (a team place or a pin added without the OSM id).
 *       A station appears once although the map has a point per railway line and a building.</li>
 *   <li><b>Linking a map place</b> that ASVSJ does not have yet: when the post is sent, Nominatim gives its names,
 *       address, position and tag; the place is stored with source OSM, ACTIVE at once (map places skip the
 *       Moderator), in the category its tag maps to ({@code category_osm_tags}, "Địa điểm khác" when none fits).</li>
 *   <li><b>Fair use</b> of the free public services: one user may search at most {@value #SEARCHES_PER_MINUTE} times a
 *       minute and add at most {@value #LOOKUPS_PER_USER_DAY} new map places a day. The same text typed again within
 *       10 minutes and a place looked up within the hour are answered from memory (sending a post again after fixing
 *       an error does not ask again). Real calls are counted in {@code api_usage} (OSM_PHOTON_SEARCH,
 *       OSM_NOMINATIM_LOOKUP).</li>
 * </ul>
 */
@Service
public class PlaceService {

    public static final String SEARCH_USAGE = "OSM_PHOTON_SEARCH";
    public static final String LOOKUP_USAGE = "OSM_NOMINATIM_LOOKUP";
    static final int SEARCHES_PER_MINUTE = 40;
    static final int LOOKUPS_PER_USER_DAY = 25;
    static final long LOOKUP_MEMORY_MS = 60 * 60_000L;
    static final long SEARCH_MEMORY_MS = 10 * 60_000L;
    static final int ASVSJ_LIMIT = 8;
    static final int EXTERNAL_LIMIT = 6;
    static final int QUERY_MAX = 100;
    /** The map is asked from 2 letters or digits on ("ga" yes, "g" no); ASVSJ from the first one. */
    static final int MIN_CHARS = 2;
    static final int NAME_MAX = 160;
    static final int ADDRESS_MAX = 255;
    /**
     * Two places with the same name this close are one place (a shop as a point and as its building). Kept small:
     * two branches of a chain ("Lawson") are often only 100–200 m apart, and the map gives them the same name.
     */
    static final double SAME_PLACE_M = 60;
    /** A big station spreads over several hundred metres (Shinjuku, Ueno): its points count as one station. */
    static final double SAME_STATION_M = 600;
    private static final Set<String> STATION_TAGS = Set.of("railway=station", "railway=halt",
            "public_transport=station", "building=train_station");
    private static final Pattern DEV_ID = Pattern.compile("DEV-[A-Za-z0-9-]{1,40}");
    /** Days are counted in Japan time, where the users live. */
    private static final ZoneId JAPAN = ZoneId.of("Asia/Tokyo");
    private static final int MEMORY_MAX = 2000;

    static final String INVALID = "Có địa điểm không hợp lệ. Hãy chọn lại địa điểm.";
    static final String GONE = "Có địa điểm không còn trên bản đồ. Hãy chọn lại địa điểm.";
    static final String NOT_FOUND = "OpenStreetMap không còn địa điểm này. Hãy chọn lại địa điểm.";
    static final String NO_EXTERNAL = "Hiện chưa thêm được địa điểm mới từ OpenStreetMap. "
            + "Hãy chọn địa điểm đã có trên Senpai ơi.";
    static final String UNAVAILABLE = "Chưa lấy được thông tin địa điểm từ OpenStreetMap. "
            + "Hãy thử lại sau hoặc bỏ địa điểm đó.";
    static final String DAILY = "Hôm nay bạn đã thêm nhiều địa điểm mới từ OpenStreetMap. "
            + "Hãy chọn địa điểm đã có trên Senpai ơi hoặc thử lại vào ngày mai.";

    /** UNAVAILABLE, with the reason when it tells the user something useful. */
    static String unavailable(String reason) {
        return switch (reason == null ? "" : reason) {
            case "BUSY", "LIMITED" -> "Dịch vụ bản đồ OpenStreetMap đang bận. Hãy thử lại sau ít phút "
                    + "hoặc chọn địa điểm đã có trên Senpai ơi.";
            case "SSL", "DNS", "NETWORK", "TIMEOUT" -> "Máy chủ chưa kết nối được tới OpenStreetMap. "
                    + "Hãy thử lại sau hoặc chọn địa điểm đã có trên Senpai ơi.";
            default -> UNAVAILABLE;
        };
    }

    private final PlaceRepository repository;
    private final ExternalPlaces external;
    private final Clock clock;
    /** Times of each user's recent map searches (last minute). Kept in memory: a restart simply resets it. */
    private final Map<Long, Deque<Long>> recentSearches = new ConcurrentHashMap<>();
    /** New map places each user looked up today, in memory like the above. */
    private final Map<Long, DayCount> lookups = new ConcurrentHashMap<>();
    /** Lookup answers of the last hour (also "not found"), so a post sent again does not ask again. */
    private final Map<String, Memo<Optional<Hit>>> remembered = new ConcurrentHashMap<>();
    /** Search answers of the last 10 minutes, keyed by the folded text. */
    private final Map<String, Memo<List<Hit>>> searches = new ConcurrentHashMap<>();

    private record DayCount(LocalDate day, int count) {
    }

    private record Memo<T>(T value, long until) {
    }

    public PlaceService(PlaceRepository repository, ExternalPlaces external, Clock clock) {
        this.repository = repository;
        this.external = external;
        this.clock = clock;
    }

    // ---------------------------------------------------------------- search

    public PlaceSearch search(long userId, String query) {
        String q = query == null ? "" : query.strip();
        if (q.length() > QUERY_MAX) {
            q = q.substring(0, QUERY_MAX);
        }
        String squashed = VietnameseText.squash(q);
        if (squashed.isEmpty()) {
            return new PlaceSearch(List.of(), external.enabled() ? "ON" : "OFF");
        }
        List<PlaceOption> items = new ArrayList<>(repository.search(q, ASVSJ_LIMIT));
        if (!external.enabled()) {
            return new PlaceSearch(items, "OFF");
        }
        if (squashed.length() < MIN_CHARS) {
            return new PlaceSearch(items, "SHORT");
        }
        List<Hit> hits;
        try {
            hits = mapSearch(userId, q);
        } catch (ExternalPlacesException e) {
            return new PlaceSearch(items, "UNAVAILABLE", e.reason());
        }
        if (hits == null) {
            return new PlaceSearch(items, "BUSY");
        }
        items.addAll(fromMap(hits, items));
        return new PlaceSearch(items, "ON");
    }

    /**
     * The map's answer, or null when this user searched too often in the last minute. "ga Ueno" is asked among
     * stations ("Ueno") and also as typed, stations first: typed without accents, "ga ran" may be "gà rán" (fried
     * chicken), and Photon finds some station for nearly any word.
     */
    private List<Hit> mapSearch(long userId, String q) {
        String station = stationName(q);
        if (station == null || VietnameseText.squash(station).length() < MIN_CHARS) {
            Optional<PlaceWords.Kind> kind = PlaceWords.parse(q);
            return kind.isPresent() ? kindSearch(userId, kind.get()) : ask(userId, q, false);
        }
        List<Hit> stations = ask(userId, station, true);
        if (stations == null) {
            return null;
        }
        List<Hit> typed = ask(userId, q, false);
        List<Hit> both = new ArrayList<>(stations);
        if (typed != null) {
            both.addAll(typed);
        }
        return both;
    }

    private List<Hit> ask(long userId, String text, boolean stations) {
        String key = (stations ? "station:" : "text:") + VietnameseText.fold(text);
        return ask(userId, key, () -> stations ? external.searchStations(text) : external.search(text));
    }

    /**
     * "công viên Ueno": "Ueno" among parks first, then "Ueno park" as English text (Photon does not know Vietnamese
     * words). Only the kind typed ("công viên") asks for the English word among those places.
     */
    private List<Hit> kindSearch(long userId, PlaceWords.Kind kind) {
        String name = kind.rest().isBlank() ? kind.english() : kind.rest();
        List<Hit> tagged = ask(userId, "tag:" + String.join(",", kind.tags()) + ":" + VietnameseText.fold(name),
                () -> external.searchTagged(name, kind.tags()));
        if (tagged == null) {
            return null;
        }
        List<Hit> both = new ArrayList<>(tagged);
        if (!kind.rest().isBlank()) {
            List<Hit> typed = ask(userId, kind.rest() + " " + kind.english(), false);
            if (typed != null) {
                both.addAll(typed);
            }
        }
        return both;
    }

    // ---------------------------------------------------------------- around a point (Map click)

    static final double NEARBY_M = 150;
    static final int NEARBY_LIMIT = 12;

    /**
     * The places around a point the user clicked on the Map, nearest first: Senpai ơi places within
     * {@value #NEARBY_M} m and the OpenStreetMap places there (Photon reverse), each place once, as in {@link #search}.
     * Counted like a search for the per-user limit.
     */
    public PlaceSearch nearby(long userId, double lat, double lng) {
        List<PlaceOption> items = new ArrayList<>();
        Set<Long> shown = new HashSet<>();
        for (Nearby n : repository.activeNear(List.<double[]>of(new double[]{lat, lng}), NEARBY_M)) {
            if (metres(lat, lng, n.lat(), n.lng()) <= NEARBY_M && shown.add(n.option().id())) {
                items.add(n.option());
            }
        }
        String external = "ON";
        String reason = null;
        if (!this.external.enabled()) {
            external = "OFF";
        } else {
            String key = String.format(Locale.ROOT, "near:%.4f,%.4f", lat, lng);
            try {
                List<Hit> hits = ask(userId, key, () -> this.external.nearby(lat, lng));
                if (hits == null) {
                    external = "BUSY";
                } else {
                    items.addAll(fromMap(hits, items, NEARBY_LIMIT));
                }
            } catch (ExternalPlacesException e) {
                external = "UNAVAILABLE";
                reason = e.reason();
            }
        }
        List<PlaceOption> unique = new ArrayList<>();
        Set<String> keys = new HashSet<>();
        for (PlaceOption o : items) {
            if (keys.add(o.id() != null ? "id:" + o.id() : "osm:" + o.externalId())) {
                unique.add(o);
            }
        }
        unique.sort(Comparator.comparingDouble(o -> o.lat() == null ? Double.MAX_VALUE
                : metres(lat, lng, o.lat(), o.lng())));
        return new PlaceSearch(unique.size() > NEARBY_LIMIT ? unique.subList(0, NEARBY_LIMIT) : unique, external, reason);
    }

    /** One map call, answered from memory when the same question was asked lately; null when the user is too fast. */
    private List<Hit> ask(long userId, String key, Supplier<List<Hit>> call) {
        long now = clock.millis();
        Memo<List<Hit>> memo = searches.get(key);
        if (memo != null && memo.until() >= now) {
            return memo.value();
        }
        boolean real = real();
        if (real && !allowSearch(userId)) {
            return null;
        }
        List<Hit> hits = call.get();
        if (real) {
            repository.addUsage(SEARCH_USAGE, today());
        }
        keep(searches, key, List.copyOf(hits), now + SEARCH_MEMORY_MS);
        return hits;
    }

    /** "ga Nippori" / "nhà ga Ueno" → "Nippori" / "Ueno"; null for any other text ("gà rán" keeps its accent). */
    static String stationName(String query) {
        String q = Normalizer.normalize(query == null ? "" : query.strip(), Normalizer.Form.NFC);
        String lower = q.toLowerCase(Locale.ROOT);
        for (String prefix : List.of("nhà ga ", "nha ga ", "ga ")) {
            if (lower.startsWith(prefix)) {
                String rest = q.substring(prefix.length()).strip();
                return rest.isEmpty() ? null : rest;
            }
        }
        return null;
    }

    /**
     * Map places as options. One ASVSJ already has is added to {@code asvsj} instead (once, even when the text did not
     * match its ASVSJ name, e.g. typed in Japanese); one removed by a Moderator is left out.
     */
    private List<PlaceOption> fromMap(List<Hit> hits, List<PlaceOption> asvsj) {
        return fromMap(hits, asvsj, EXTERNAL_LIMIT);
    }

    private List<PlaceOption> fromMap(List<Hit> hits, List<PlaceOption> asvsj, int limit) {
        // Known ids first: when the map has a place twice (a point and its building), the copy ASVSJ knows stands for
        // both, so a place a Moderator removed does not come back through its twin
        Map<String, Known> known = repository.byOsmIds(hits.stream().map(Hit::id).distinct().toList());
        List<Hit> places = distinctPlaces(hits, known.keySet());
        List<Nearby> near = repository.activeNear(points(places.stream()
                .filter(h -> !known.containsKey(h.id())).toList()), SAME_STATION_M);
        Map<String, Category> mapped = repository.categoriesOfTags(places.stream().map(Hit::tag)
                .collect(Collectors.toSet()));
        Category other = null;

        Set<Long> shown = new HashSet<>();
        asvsj.forEach(o -> shown.add(o.id()));
        List<PlaceOption> fresh = new ArrayList<>();
        for (Hit h : places) {
            PlaceOption same;
            Known k = known.get(h.id());
            if (k != null) {
                if (!"ACTIVE".equals(k.status())) {
                    continue;                                   // removed by a Moderator
                }
                same = k.shown();
            } else {
                same = sameAsvsjPlace(h, near).map(Nearby::option).orElse(null);
            }
            if (same != null) {
                if (shown.add(same.id())) {
                    asvsj.add(same);
                }
                continue;
            }
            if (fresh.size() == limit) {
                continue;
            }
            Category category = mapped.get(h.tag());
            if (category == null) {
                other = other == null ? repository.otherCategory() : other;
                category = other;
            }
            fresh.add(new PlaceOption(null, h.id(), h.name(), h.nameJa(), category.name(), h.address(), "OSM",
                    h.lat(), h.lng()));
        }
        return fresh;
    }

    // ---------------------------------------------------------------- same place

    /**
     * The search answer without repeats: the map has a point per railway line of a station plus its building
     * ("Nippori" three times and "Nippori Station"), and the same shop as a point and a building. Same id, or same
     * name close by (within {@value #SAME_STATION_M} m when both are stations, else {@value #SAME_PLACE_M} m): the
     * first one stays, unless a later copy is in {@code preferred} (ids ASVSJ already has) and the first is not.
     */
    static List<Hit> distinctPlaces(List<Hit> hits, Set<String> preferred) {
        List<Hit> kept = new ArrayList<>();
        for (Hit h : hits) {
            int same = -1;
            for (int i = 0; i < kept.size() && same < 0; i++) {
                if (samePlace(h, kept.get(i))) {
                    same = i;
                }
            }
            if (same < 0) {
                kept.add(h);
            } else if (preferred.contains(h.id()) && !preferred.contains(kept.get(same).id())) {
                kept.set(same, h);
            }
        }
        return kept;
    }

    private static boolean samePlace(Hit a, Hit b) {
        if (a.id().equals(b.id())) {
            return true;
        }
        return !Collections.disjoint(nameKeys(a.name(), a.nameVi(), a.nameJa(), a.nameEn()),
                nameKeys(b.name(), b.nameVi(), b.nameJa(), b.nameEn()))
                && metres(a.lat(), a.lng(), b.lat(), b.lng())
                <= (station(a.tag()) && station(b.tag()) ? SAME_STATION_M : SAME_PLACE_M);
    }

    /**
     * The ASVSJ place this map place already is: one of its names in any language, close by (a station within
     * {@value #SAME_STATION_M} m of an ASVSJ station, anything else within {@value #SAME_PLACE_M} m); the nearest wins.
     */
    static Optional<Nearby> sameAsvsjPlace(Hit hit, List<Nearby> near) {
        Set<String> keys = nameKeys(hit.name(), hit.nameVi(), hit.nameJa(), hit.nameEn());
        boolean station = station(hit.tag());
        return near.stream()
                .filter(n -> metres(hit.lat(), hit.lng(), n.lat(), n.lng())
                        <= (station && "station".equals(n.categoryId()) ? SAME_STATION_M : SAME_PLACE_M))
                .filter(n -> !Collections.disjoint(keys, nameKeys(n.option().name(), n.option().nameJa(), n.nameEn())))
                .min(Comparator.comparingDouble(n -> metres(hit.lat(), hit.lng(), n.lat(), n.lng())));
    }

    /**
     * A name as compared: no accents, case, spaces or punctuation, and without the word for station ("Ga Nippori",
     * "Nippori Station", "日暮里駅" → "nippori", "nippori", "日暮里").
     */
    static String nameKey(String name) {
        String f = VietnameseText.fold(name);
        for (String prefix : List.of("nha ga ", "ga ")) {
            if (f.startsWith(prefix)) {
                f = f.substring(prefix.length());
                break;
            }
        }
        for (String suffix : List.of(" railway station", " train station", " station", " eki", "駅")) {
            if (f.endsWith(suffix) && f.length() > suffix.length()) {
                f = f.substring(0, f.length() - suffix.length());
                break;
            }
        }
        return VietnameseText.squash(f);
    }

    private static Set<String> nameKeys(String... names) {
        Set<String> keys = new HashSet<>();
        for (String name : names) {
            if (name != null && !name.isBlank()) {
                String key = nameKey(name);
                if (!key.isEmpty()) {
                    keys.add(key);
                }
            }
        }
        return keys;
    }

    static boolean station(String tag) {
        return STATION_TAGS.contains(tag);
    }

    /** Distance in metres; flat-earth formula, exact enough for a few hundred metres. */
    static double metres(double lat1, double lng1, double lat2, double lng2) {
        double x = Math.toRadians(lng2 - lng1) * Math.cos(Math.toRadians((lat1 + lat2) / 2));
        double y = Math.toRadians(lat2 - lat1);
        return Math.sqrt(x * x + y * y) * 6_371_000;
    }

    private static List<double[]> points(List<Hit> hits) {
        return hits.stream().map(h -> new double[]{h.lat(), h.lng()}).toList();
    }

    // ---------------------------------------------------------------- linking map places to a post

    /**
     * The map places of one post, checked before its transaction.
     *
     * @param knownIds ASVSJ places for the map places ASVSJ already has
     * @param fresh    map places to save with the post
     * @param error    why the places cannot be used (field "placeIds"), or null
     */
    public record PlacePicks(List<Long> knownIds, List<NewOsmPlace> fresh, String error) {

        public static final PlacePicks NONE = new PlacePicks(List.of(), List.of(), null);

        static PlacePicks failed(String error) {
            return new PlacePicks(List.of(), List.of(), error);
        }
    }

    /** "N772389775" (OpenStreetMap node, W way, R relation), or "DEV-…" for the made-up places. */
    public static boolean validExternalId(String id) {
        return id != null && (OsmPlaces.OSM_ID.matcher(id).matches() || DEV_ID.matcher(id).matches());
    }

    /**
     * Looks up the map places a post wants to link. Known ones map to their ASVSJ place; unknown ones are read from
     * the map (one Nominatim request for all of them). Runs outside the database transaction because it waits for
     * the map service.
     */
    public PlacePicks resolve(long userId, List<String> externalIds) {
        if (externalIds == null || externalIds.isEmpty()) {
            return PlacePicks.NONE;
        }
        if (!externalIds.stream().allMatch(PlaceService::validExternalId)) {
            return PlacePicks.failed(INVALID);
        }
        Map<String, Known> known = repository.byOsmIds(externalIds);
        List<Long> knownIds = new ArrayList<>();
        List<String> unknown = new ArrayList<>();
        for (String id : externalIds) {
            Known k = known.get(id);
            if (k == null) {
                unknown.add(id);
            } else if ("ACTIVE".equals(k.status())) {
                knownIds.add(k.shown().id());
            } else {
                return PlacePicks.failed(GONE);
            }
        }
        if (unknown.isEmpty()) {
            return new PlacePicks(knownIds, List.of(), null);
        }
        if (!external.enabled()) {
            return PlacePicks.failed(NO_EXTERNAL);
        }
        long now = clock.millis();
        Map<String, Optional<Hit>> answers = new LinkedHashMap<>();
        List<String> toAsk = new ArrayList<>();
        for (String id : unknown) {
            Optional<Hit> recalled = recall(id, now);
            if (recalled == null) {
                toAsk.add(id);
            } else {
                answers.put(id, recalled);
            }
        }
        if (!toAsk.isEmpty()) {
            boolean real = real();
            if (real && lookupsToday(userId) + toAsk.size() > LOOKUPS_PER_USER_DAY) {
                return PlacePicks.failed(DAILY);
            }
            Map<String, Hit> found;
            try {
                found = external.lookup(toAsk);
            } catch (ExternalPlacesException e) {
                return PlacePicks.failed(unavailable(e.reason()));
            }
            if (real) {
                repository.addUsage(LOOKUP_USAGE, today());
                countLookups(userId, toAsk.size());
            }
            for (String id : toAsk) {
                Optional<Hit> hit = Optional.ofNullable(found.get(id));
                remember(id, hit, now);
                answers.put(id, hit);
            }
        }
        List<Hit> hits = new ArrayList<>();
        for (String id : unknown) {
            Optional<Hit> hit = answers.get(id);
            if (hit.isEmpty()) {
                return PlacePicks.failed(NOT_FOUND);
            }
            hits.add(hit.get());
        }
        List<Nearby> near = repository.activeNear(points(hits), SAME_STATION_M);
        Map<String, Category> mapped = repository.categoriesOfTags(hits.stream().map(Hit::tag)
                .collect(Collectors.toSet()));
        List<NewOsmPlace> fresh = new ArrayList<>();
        for (Hit h : hits) {
            Optional<Nearby> same = sameAsvsjPlace(h, near);
            if (same.isPresent()) {
                knownIds.add(same.get().option().id());     // ASVSJ has it already, without its OSM id
                continue;
            }
            Category category = mapped.get(h.tag());
            fresh.add(new NewOsmPlace(h.id(), cut(h.nameVi() == null ? h.name() : h.nameVi(), NAME_MAX),
                    cut(h.nameJa(), NAME_MAX), cut(h.nameEn(), NAME_MAX),
                    category == null ? PlaceRepository.OTHER_CATEGORY : category.id(),
                    cut(h.address(), ADDRESS_MAX), h.lat(), h.lng()));
        }
        return new PlacePicks(knownIds, fresh, null);
    }

    /**
     * Saves the new map places of {@link #resolve} and returns the ASVSJ place ids to link, without repeats.
     * Call it inside the transaction that saves the post, so a post that fails its checks adds no place.
     */
    public List<Long> save(PlacePicks picks, long createdBy) {
        Set<Long> ids = new LinkedHashSet<>(picks.knownIds());
        for (NewOsmPlace p : picks.fresh()) {
            ids.add(repository.insertOsmPlace(p, createdBy).id());
        }
        return List.copyOf(ids);
    }

    private static String cut(String text, int max) {
        if (text == null || text.isBlank()) {
            return null;
        }
        String t = text.strip();
        return t.length() > max ? t.substring(0, max) : t;
    }

    // ---------------------------------------------------------------- fair use

    private boolean real() {
        return "OSM".equals(external.engine());
    }

    private LocalDate today() {
        return LocalDate.now(clock.withZone(JAPAN));
    }

    private Optional<Hit> recall(String id, long now) {
        Memo<Optional<Hit>> memo = remembered.get(id);
        return memo == null || memo.until() < now ? null : memo.value();
    }

    private void remember(String id, Optional<Hit> hit, long now) {
        keep(remembered, id, hit, now + LOOKUP_MEMORY_MS);
    }

    private <T> void keep(Map<String, Memo<T>> memory, String key, T value, long until) {
        if (memory.size() > MEMORY_MAX) {
            long now = clock.millis();
            memory.values().removeIf(m -> m.until() < now);
            if (memory.size() > MEMORY_MAX) {
                memory.clear();
            }
        }
        memory.put(key, new Memo<>(value, until));
    }

    private int lookupsToday(long userId) {
        DayCount c = lookups.get(userId);
        return c == null || !c.day().equals(today()) ? 0 : c.count();
    }

    private void countLookups(long userId, int places) {
        LocalDate day = today();
        lookups.merge(userId, new DayCount(day, places),
                (old, now) -> old.day().equals(day) ? new DayCount(day, old.count() + places) : now);
    }

    private boolean allowSearch(long userId) {
        long now = clock.millis();
        Deque<Long> times = recentSearches.computeIfAbsent(userId, k -> new ArrayDeque<>());
        synchronized (times) {
            while (!times.isEmpty() && now - times.peekFirst() >= 60_000) {
                times.pollFirst();
            }
            if (times.size() >= SEARCHES_PER_MINUTE) {
                return false;
            }
            times.addLast(now);
            return true;
        }
    }
}
