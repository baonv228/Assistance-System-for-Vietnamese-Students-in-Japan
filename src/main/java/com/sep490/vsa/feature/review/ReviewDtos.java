package com.sep490.vsa.feature.review;

import java.util.List;

/** JSON shapes of Write / Edit / Delete Review (UC-U16, UC-U17, UC-U18). */
public final class ReviewDtos {

    private ReviewDtos() {
    }

    /**
     * A new review. The place is {@code placeId} (a Senpai ơi place) or {@code externalPlaceId} (an OpenStreetMap place
     * not on Senpai ơi yet, "N772389775"; it is added when the review is saved).
     */
    public record CreateRequest(Long placeId, String externalPlaceId, Integer rating, String content) {
    }

    /** An edit; {@code keepMediaIds} are the attached photos/video to keep, in order. */
    public record UpdateRequest(Integer rating, String content, List<Long> keepMediaIds) {
    }

    /** @param status PUBLISHED, or PROCESSING while photos/video are scanned (only the author sees it) */
    public record Saved(long id, long placeId, String status) {
    }

    public record Limits(int contentMin, int contentMax, int imagesMax, int imageMaxMb, int videosMax,
                         int videoMaxSeconds, int videoMaxMb) {
    }

    /** Today's usage of a new account (younger than {@code newAccountDays}); edits do not count. */
    public record Quota(boolean newAccount, int newAccountDays, int postsUsed, int postsLimit, int videosUsed,
                        int videosLimit) {
    }

    /** The form options; {@code myReviewId} when the user already reviewed this place (the form edits it). */
    public record Compose(Limits limits, Quota quota, Long myReviewId) {
    }

    public record EditMedia(long id, String type, String url, String scanStatus) {
    }

    public record EditData(long id, long placeId, String placeName, int rating, String content, String status,
                           List<EditMedia> media, Limits limits, Quota quota) {
    }

    // ---------------------------------------------------------------- new pin (UC-U19)

    /**
     * A pin with its first review. {@code confirmDuplicate}: the user saw the "Có thể trùng" warning and chose
     * "Vẫn gửi".
     */
    public record PinRequest(Double lat, Double lng, String nameJa, String nameEn, String nameVi, String categoryId,
                             String address, Integer rating, String content, Boolean confirmDuplicate) {
    }

    public record PinSaved(long placeId, long reviewId, String reviewStatus) {
    }

    /** An approved place within 200 m with a similar name. */
    public record Duplicate(long id, String name, String nameJa, String categoryName, int distance) {
    }

    public record PinCategory(String id, String name, String icon) {
    }

    /** Form options: categories, review limits, today's pins of a new account. */
    public record PinCompose(List<PinCategory> categories, Limits limits, boolean newAccount, int pinsUsed,
                             int pinsLimit, int newAccountDays) {
    }
}
