package com.sep490.vsa.feature.place;

import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * Places from the open map database OpenStreetMap, for "Địa điểm liên quan". Three implementations, chosen by
 * {@link ExternalPlacesConfig}: {@link OsmPlaces} (Photon search and Nominatim lookup, free, no key),
 * {@link DevOsmPlaces} (a few made-up places, works offline) and {@link #DISABLED} (ASVSJ places only).
 * <p>
 * Callers go through {@link PlaceService}, which limits how often one user may ask, so the free public services are
 * used fairly. OpenStreetMap data may be stored (ODbL licence, credit "© OpenStreetMap contributors"), so a place
 * linked to a post is kept with its names, address and coordinates.
 */
public interface ExternalPlaces {

    /** OSM (real calls to the OpenStreetMap services), DEV (made-up data) or OFF. */
    String engine();

    default boolean enabled() {
        return !"OFF".equals(engine());
    }

    /**
     * Places whose name matches the text, best match first, in the configured countries (Japan and Vietnam), without
     * streets, bus stops, platforms and similar map features.
     *
     * @throws ExternalPlacesException when the service cannot be reached or answers with an error
     */
    List<Hit> search(String text);

    /**
     * Like {@link #search} but among railway stations only, for "ga Ueno" (Vietnamese for Ueno station): the plain
     * search does not know the word "ga" and finds the zoo, hotels and places in Spain.
     *
     * @param name the station name without "ga" ("Ueno")
     * @throws ExternalPlacesException when the service cannot be reached or answers with an error
     */
    List<Hit> searchStations(String name);

    /**
     * Like {@link #search} but among places with one of these OSM tags ("leisure:park"), for "công viên Ueno" (the
     * plain search does not know Vietnamese words and lists bike rentals and guest houses named "Ueno Park" first).
     *
     * @throws ExternalPlacesException when the service cannot be reached or answers with an error
     */
    default List<Hit> searchTagged(String text, List<String> osmTags) {
        return search(text);
    }

    /**
     * Where the Map searches: the box shown on screen. {@code inside} = only places in the box ("7-Eleven" around the
     * user, not all of Tokyo); otherwise anywhere, places nearer the box's centre ranked higher (nothing in the box).
     */
    record Where(double south, double west, double north, double east, boolean inside) {

        public double centerLat() {
            return (south + north) / 2;
        }

        public double centerLng() {
            return (west + east) / 2;
        }

        public boolean contains(double lat, double lng) {
            return lat >= south && lat <= north && lng >= west && lng <= east;
        }

        public Where anywhere() {
            return new Where(south, west, north, east, false);
        }
    }

    /**
     * {@link #search} in or around the Map's view. The default ignores the view; {@link PlaceService} keeps only the
     * places in the box when {@code where.inside()}.
     */
    default List<Hit> search(String text, Where where) {
        return search(text);
    }

    /** {@link #searchStations} in or around the Map's view (see {@link #search(String, Where)}). */
    default List<Hit> searchStations(String name, Where where) {
        return searchStations(name);
    }

    /** {@link #searchTagged} in or around the Map's view (see {@link #search(String, Where)}). */
    default List<Hit> searchTagged(String text, List<String> osmTags, Where where) {
        return searchTagged(text, osmTags);
    }

    /**
     * Named places around a point the user clicked on the map, nearest first (shops, stations, parks… not streets).
     *
     * @throws ExternalPlacesException when the service cannot be reached or answers with an error
     */
    default List<Hit> nearby(double lat, double lng) {
        return List.of();
    }

    /**
     * The places with these ids ({@link Hit#id()}), keyed by id. An id the map does not know (deleted since, or
     * outside the configured countries) is missing from the answer, and so is map noise the search leaves out (a
     * street, a boundary, a bus stop) unless the search offered that id.
     *
     * @throws ExternalPlacesException when the service cannot be reached or answers with an error
     */
    Map<String, Hit> lookup(Collection<String> ids);

    /**
     * One place.
     *
     * @param id          OSM element: N (node), W (way) or R (relation) and its number, e.g. "N772389775" (Nippori
     *                    station); "DEV-…" for made-up places
     * @param name        the name to show: English or romaji where the map has it ("Nippori"), else the local name
     * @param nameVi      Vietnamese name for ASVSJ ({@code places.name_vi}); only filled by {@link #lookup}
     * @param nameJa      Japanese name, when the map has one
     * @param nameEn      English name, when the map has one
     * @param address     short address, country in Vietnamese ("Ueno 5, Taito, Tokyo, Nhật Bản")
     * @param tag         the main OSM tag, "key=value" ("railway=station", "shop=convenience")
     * @param countryCode two-letter country code, lower case ("jp", "vn")
     */
    record Hit(String id, String name, String nameVi, String nameJa, String nameEn, String address, String tag,
               String countryCode, double lat, double lng) {
    }

    /** Used when OpenStreetMap is switched off: the search shows ASVSJ places only. */
    ExternalPlaces DISABLED = new ExternalPlaces() {
        @Override
        public String engine() {
            return "OFF";
        }

        @Override
        public List<Hit> search(String text) {
            return List.of();
        }

        @Override
        public List<Hit> searchStations(String name) {
            return List.of();
        }

        @Override
        public Map<String, Hit> lookup(Collection<String> ids) {
            return Map.of();
        }
    };
}
