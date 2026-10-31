package com.sep490.vsa.feature.home;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

public final class HomeDtos {

    private HomeDtos() {
    }

    public record Tag(String name, String color) {
    }

    public record Phrase(String japanese, String romaji, String meaningVi, String categoryName, String categoryIcon) {
    }

    public record Stats(long placesWithReviews, long reviews, long phrases) {
    }

    public record Quote(String text, String authorName, List<Tag> authorTags, String placeName) {
    }

    public record GuestHome(Stats stats, List<Quote> quotes, Phrase phrase) {
    }

    public record GuideNext(String title, String dueNote, String placeName) {
    }

    public record GuideProgress(String stageCode, String stageTitle, int done, int total, GuideNext next) {
    }

    /** {@code daysUntil} is 0 when the event is happening today. */
    public record EventItem(Long id, String title, LocalDate startDate, LocalDate endDate, String locationName, long daysUntil) {
    }

    public record ReviewItem(String placeName, String categoryIcon, int rating, String authorName, String excerpt) {
    }

    /**
     * Page "Review mới" ({@code /reviews}): one page of {@code items} in {@code scope} (AREA, PREFECTURE or ALL), the
     * scopes this viewer can pick with how many reviews each holds, and the names of the viewer's area and prefecture.
     */
    public record ReviewFeed(String scope, String areaName, String prefectureName, List<ScopeCount> scopes, int page,
                             boolean hasMore, List<FeedReview> items) {
    }

    public record ScopeCount(String code, long count) {
    }

    /**
     * A review on the "Review mới" page; {@code photoUrl} is its first photo that passed the scan (null without one),
     * {@code mediaCount} counts its photos and videos that passed.
     */
    public record FeedReview(Long id, Long placeId, String placeName, String categoryName, String categoryIcon,
                             String placeArea, boolean placeClosed, String near, int rating, String authorName,
                             List<Tag> authorTags, String excerpt, int likeCount, Instant createdAt, String photoUrl,
                             int mediaCount) {
    }

    public record Featured(Long id, String title, String excerpt, String authorName, List<Tag> authorTags,
                           int readMinutes, String period) {
    }

    /** {@code interests}: the topics "Dành cho bạn" learnt from what the user liked, saved, commented on or wrote. */
    public record MemberHome(String displayName, String stage, Long areaId, String areaName, int points, String rankName,
                             Phrase phrase, GuideProgress guide, List<EventItem> events, List<ReviewItem> reviews,
                             Featured featured, List<FeedPost> forYou, List<String> interests) {
    }

    /**
     * A post chosen for the user; {@code reason} is the first badge text ("Gần khu vực của bạn"…) or null, {@code reasons}
     * every reason that matched, in ranking order.
     */
    public record FeedPost(Long id, String title, String excerpt, String authorName, String topicName, int likeCount,
                           int commentCount, Instant createdAt, String reason, List<FeedReason> reasons) {
    }

    /**
     * Why a post is in "Dành cho bạn": {@code code} AREA, PREFECTURE, STAGE or INTEREST, {@code label} the badge text,
     * {@code detail} what matched (the linked place for AREA and PREFECTURE, the topic for STAGE and INTEREST).
     */
    public record FeedReason(String code, String label, String detail) {
    }
}
