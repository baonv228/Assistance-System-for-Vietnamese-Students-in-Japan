package com.sep490.vsa.feature.place;

import com.sep490.vsa.feature.place.PlaceDtos.PlaceOption;
import jakarta.validation.constraints.NotNull;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/** JSON shapes of the Map (UC-U14 Browse Locations on Map, UC-U15 View Location Details). */
public final class MapDtos {

    private MapDtos() {
    }

    /** A place category with its Material Symbols icon and how many places of it are on the map. */
    public record MapCategory(String id, String name, String icon, int count) {
    }

    /**
     * One pin. {@code reviewCount} counts published reviews; the pin is bright when it is above 0.
     *
     * @param rating  average stars of the published reviews, one decimal, null without reviews
     * @param source  OSM, GOOGLE, TEAM, MODERATOR or USER (USER = community pin, drawn square)
     * @param status  ACTIVE, or PENDING for the viewer's own pin waiting for a Moderator
     * @param scam    a published "Cảnh báo lừa đảo" post links the place ("!" on the pin)
     * @param osmId   OpenStreetMap element ("N772389775"), so a click on that shop on the map opens this place
     */
    public record MapPlace(long id, String name, String nameJa, String nameEn, String categoryId, double lat,
                           double lng, String source, String status, boolean closed, int reviewCount, Double rating,
                           boolean scam, String osmId) {
    }

    /** Where the map opens: the user's area (the middle of its places) or Tokyo. */
    public record MapCenter(double lat, double lng, int zoom, String areaName) {
    }

    /**
     * @param devLocation the position "Vị trí của tôi" always gives in this environment ({@code app.map.dev-location},
     *                    for testing from outside Japan), or null: the browser's real location is used
     */
    public record MapOverview(List<MapCategory> categories, List<MapPlace> places, MapCenter center,
                              boolean signedIn, DevLocation devLocation) {
    }

    public record DevLocation(double lat, double lng, String label) {
    }

    /** A festival or event with a position, for the lantern pins. */
    public record MapEvent(long id, String title, LocalDate startDate, LocalDate endDate, String locationName,
                           double lat, double lng, long daysUntil) {
    }

    /**
     * Map search: matching categories, then places. ASVSJ places have an id; OpenStreetMap places not yet on
     * Senpai ơi have {@code id: null} and an {@code externalId}.
     *
     * @param external ON, OFF, SHORT, UNAVAILABLE, BUSY as in {@link PlaceDtos.PlaceSearch}, or LOGIN for a guest
     *                 (guests search ASVSJ places only)
     */
    public record MapSearch(List<MapCategory> categories, List<PlaceOption> places, String external,
                            String externalReason) {
    }

    public record ScamWarningRef(long blogId, String title) {
    }

    public record ReportReason(long id, String label) {
    }

    /**
     * Place details.
     *
     * @param closedReports    "đã đóng cửa" reports not yet handled by a Moderator
     * @param closedThreshold  reports that send the place to the Moderator queue (5)
     * @param canAct           the viewer is a signed-in User (staff and guests only read)
     * @param myReviewId       the viewer's own review of the place, if any
     * @param mergedFrom       the merged place the viewer asked for, when this is the place it was merged into
     */
    public record PlaceDetail(long id, String name, String nameJa, String nameEn, String address, String osmId,
                              Double lat, Double lng, String categoryId, String categoryName, String categoryIcon,
                              String source, String status, boolean closed, int closedReports, int closedThreshold,
                              boolean reportedClosed, int reviewCount, Double rating,
                              List<ScamWarningRef> scamWarnings, boolean saved, boolean canAct, Long myReviewId,
                              Long mergedFrom, List<ReportReason> reportReasons) {
    }

    public record Tag(String name, String color) {
    }

    public record Author(Long id, String displayName, String avatarUrl, List<Tag> tags, String rank,
                         String roleLabel) {
    }

    public record ReviewMedia(long id, String type, String url, Integer width, Integer height) {
    }

    /**
     * One review on the place.
     *
     * @param status  PUBLISHED, or PROCESSING for the viewer's own review whose photos are still being scanned
     * @param helpful more than 10 likes ("Hữu ích")
     */
    public record ReviewItem(long id, Author author, int rating, String content, List<ReviewMedia> media,
                             Instant createdAt, boolean edited, String status, int likeCount, boolean liked,
                             boolean mine, boolean helpful) {
    }

    public record ReviewPage(List<ReviewItem> items, int page, int size, long total) {
    }

    public record PhraseItem(long id, String kind, String japanese, String romaji, String meaningVi) {
    }

    public record LikeState(boolean active, int count) {
    }

    public record SaveState(boolean saved) {
    }

    /** @param queued the place reached the threshold and waits in the Moderator's "Báo đóng cửa" queue */
    public record ClosedState(int count, int threshold, boolean queued) {
    }

    public record ReportRequest(@NotNull(message = "Hãy chọn lý do báo cáo.") Long reasonId, String note) {
    }
}
