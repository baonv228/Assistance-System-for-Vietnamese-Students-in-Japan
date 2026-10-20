package com.sep490.vsa.feature.place;

import java.util.List;

/** JSON shapes of the place search used by "Địa điểm liên quan" (Write Post, Đặt câu hỏi). */
public final class PlaceDtos {

    private PlaceDtos() {
    }

    /**
     * One search result.
     *
     * @param id         the ASVSJ place, or null for an OpenStreetMap place ASVSJ does not know yet; such a place is
     *                   sent back as {@code externalId} and saved (source OSM) when the post is saved
     * @param externalId OpenStreetMap id ("N772389775"), when the place comes from OpenStreetMap
     * @param category   category name; for a new OpenStreetMap place, the category its OSM tag maps to
     * @param source     OSM ("OpenStreetMap"), GOOGLE (places saved before the switch to OpenStreetMap), TEAM or
     *                   MODERATOR ("Nhóm ASVSJ thêm"), USER ("Ghim cộng đồng")
     * @param lat        position, so the Map can show the place (null for old Google places saved without one)
     */
    public record PlaceOption(Long id, String externalId, String name, String nameJa, String category,
                              String address, String source, Double lat, Double lng) {

        public PlaceOption(Long id, String externalId, String name, String nameJa, String category,
                           String address, String source) {
            this(id, externalId, name, nameJa, category, address, source, null, null);
        }
    }

    /**
     * @param items          ASVSJ places first (approved places matching the text, then those OpenStreetMap found),
     *                       then OpenStreetMap places that are not in ASVSJ yet
     * @param external       ON (OpenStreetMap searched), OFF (switched off: ASVSJ only), SHORT (text too short),
     *                       UNAVAILABLE (OpenStreetMap did not answer), BUSY (too many searches in the last minute)
     * @param externalReason with UNAVAILABLE, why: LIMITED, OSM_ERROR, SSL, TIMEOUT, DNS, NETWORK
     */
    public record PlaceSearch(List<PlaceOption> items, String external, String externalReason) {

        public PlaceSearch(List<PlaceOption> items, String external) {
            this(items, external, null);
        }
    }
}
