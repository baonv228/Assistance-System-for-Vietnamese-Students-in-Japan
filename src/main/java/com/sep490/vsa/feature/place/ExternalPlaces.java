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
     * The places with these ids ({@link Hit#id()}), keyed by id. An id the map does not know (deleted since, or
     * outside the configured countries) is missing from the answer.
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
