package com.sep490.vsa.feature.event;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/** Requests and answers of the "Lễ hội và sự kiện" feature (list, detail, event reviews, Moderator page). */
public final class EventDtos {

    private EventDtos() {
    }

    // ---------------------------------------------------------------- list and detail

    /**
     * One event as the list, the detail page and the Moderator table show it.
     *
     * @param coverUrl    the cover photo the Moderator uploaded, or null (the page then draws an illustration)
     * @param state       NOW (đang diễn ra), UPCOMING or PAST, by Japan date
     * @param daysUntil   days until the start; 0 while it is happening or after it ended
     * @param reviewYear  the year of this occurrence (year of its start date): reviews written now count for it
     * @param canReview   the event has started, so whoever went may review it (signed-in Users, see Viewer)
     * @param avgRating   average of the published reviews of every year (one decimal), null without reviews
     * @param reviewCount published reviews of every year, of active accounts
     */
    public record EventCard(Long id, String title, LocalDate startDate, LocalDate endDate, String locationName,
                            BigDecimal lat, BigDecimal lng, String costNote, String coverUrl, String state,
                            long daysUntil, int reviewYear, boolean canReview, Double avgRating, long reviewCount) {
    }

    /** A photo of the album ("Album từ review"): every photo of the published reviews, newest year first. */
    public record AlbumPhoto(String url, String authorName, int year, Integer width, Integer height) {
    }

    public record Tag(String name, String color) {
    }

    /** @param rankName Người mới, Người chỉ đường or Senpai tin cậy */
    public record Author(Long id, String displayName, String avatarUrl, List<Tag> tags, String rankName) {
    }

    /** @param type IMAGE or VIDEO; {@code scanStatus} matters only to the author (PROCESSING, PASSED, REJECTED) */
    public record Media(Long id, String type, String url, Integer width, Integer height, String scanStatus) {
    }

    /**
     * One review of the event.
     *
     * @param status PUBLISHED for everyone; the author also sees their own PROCESSING, REJECTED or HIDDEN review
     * @param mine   written by the viewer
     * @param liked  the viewer pressed "Thích"
     */
    public record ReviewItem(Long id, Author author, int rating, String content, int year, String status,
                             boolean edited, Instant createdAt, int likeCount, List<Media> media, boolean mine,
                             boolean liked) {
    }

    /**
     * What the viewer may do on the detail page.
     *
     * @param member       signed in as a User (Member): may write, like and report reviews
     * @param myReviewId   the viewer's review of this year's occurrence, if any ("Sửa review" instead of "Viết review")
     */
    public record Viewer(boolean signedIn, boolean member, Long myReviewId) {
    }

    public record Reason(long id, String label) {
    }

    /** @param albumCount every photo; {@code album} holds at most {@link EventService#ALBUM_MAX} of them */
    public record EventDetail(EventCard event, String tip, List<AlbumPhoto> album, int albumCount,
                              List<ReviewItem> reviews, Viewer viewer, List<Reason> reportReasons) {
    }

    // ---------------------------------------------------------------- write review

    /** @param rating 1 to 5 stars */
    public record ReviewRequest(Integer rating, String content) {
    }

    /** An edit: the fields plus the attached photos/video to keep, in order. */
    public record ReviewUpdateRequest(Integer rating, String content, List<Long> keepMediaIds) {
    }

    public record Limits(int contentMax, int imagesMax, int imageMaxMb, int videosMax, int videoMaxSeconds,
                         int videoMaxMb) {
    }

    /** Today's new-account usage ("Tài khoản mới, hôm nay: 1/3 bài viết và review · 0/2 ghim · 0/1 video"). */
    public record Quota(boolean newAccount, int newAccountDays, int postsUsed, int postsLimit, int pinsUsed,
                        int pinsLimit, int videosUsed, int videosLimit) {
    }

    /** The viewer's own review of this year, for the edit form. */
    public record OwnReview(Long id, int rating, String content, String status, List<Media> media) {
    }

    /** "Viết review" form: the event, the limits, today's usage and the viewer's review of this year (to edit it). */
    public record ReviewForm(EventCard event, Limits limits, Quota quota, boolean canPostLinks, OwnReview review) {
    }

    /** @param status PUBLISHED, or PROCESSING while its photos/video are scanned */
    public record ReviewSaved(Long id, Long eventId, String status) {
    }

    public record LikeState(boolean liked, int likeCount) {
    }

    public record ReportRequest(Long reasonId, String note) {
    }

    // ---------------------------------------------------------------- Moderator

    /**
     * "Đăng sự kiện" / "Sửa sự kiện". {@code lat}/{@code lng} are both set (pin on the map) or both empty (list only).
     *
     * @param removeCover on an edit: drop the current cover photo (a new "cover" file replaces it anyway)
     */
    public record EventRequest(String title, LocalDate startDate, LocalDate endDate, String locationName,
                               BigDecimal lat, BigDecimal lng, String costNote, String tip, Boolean removeCover) {
    }

    /** A row of the Moderator table. */
    public record ModEvent(EventCard event, String tip, String createdBy) {
    }

    /** @param message tells the Moderator whether the pin is on the map yet, as in the prototype */
    public record ModSaved(EventCard event, String message) {
    }

    /** "Tìm nhanh địa điểm có sẵn": an approved ASVSJ place with its position. */
    public record PlacePick(Long id, String name, String address, BigDecimal lat, BigDecimal lng) {
    }
}
