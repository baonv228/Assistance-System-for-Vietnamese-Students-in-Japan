package com.sep490.vsa.feature.home;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

public final class HomeDtos {

    private HomeDtos() {
    }

    public record Tag(String name, String color) {
    }

    /** {@code topicId}: one learning topic of the phrase (null when it has none), for "Học thêm chủ đề này". */
    public record Phrase(String japanese, String romaji, String meaningVi, String categoryId, String categoryName,
                         String categoryIcon, String topicId) {
    }

    public record Stats(long placesWithReviews, long reviews, long phrases) {
    }

    public record Quote(String text, String authorName, List<Tag> authorTags, String placeName) {
    }

    public record GuestHome(Stats stats, List<Quote> quotes, Phrase phrase) {
    }

    /**
     * The guide "Việc tiếp theo" opens ({@code /guides/{guideId}}, its checklist tab when {@code checklist}).
     * {@code placeName} / {@code placeId}: the guide's place while it is ACTIVE, opened on the Map
     * ({@code /map?place=}). {@code tasksDone} / {@code tasksTotal} / {@code nextTask} (the first task not ticked) only
     * for a checklist ("Hướng dẫn chuẩn bị"), else 0, 0 and null.
     */
    public record GuideNext(Long guideId, String title, String dueNote, String placeName, Long placeId,
                            boolean checklist, int tasksDone, int tasksTotal, String nextTask) {
    }

    /**
     * "Việc tiếp theo": the published guides of one stage ({@code stageCode}, the user's or ARR), {@code done} of
     * {@code total} finished by the user ({@code GuideProgressService}), and the guide to open next (null when every
     * guide is done or the stage has none).
     */
    public record GuideProgress(String stageCode, String stageTitle, int done, int total, GuideNext next) {
    }

    /**
     * A guide of the user's stage shown in "Dành cho bạn". {@code excerpt}: the start of its summary as plain text
     * (null without one); {@code percent} 0–100 of its checklist; {@code tasksDone} / {@code tasksTotal} /
     * {@code nextTask} only for a checklist; {@code inProgress} = a checklist with some, not all, tasks ticked.
     */
    public record GuideCard(Long id, String title, String topicName, String excerpt, boolean checklist, int tasksDone,
                            int tasksTotal, int percent, String nextTask, boolean inProgress) {
    }

    /** {@code daysUntil} is 0 when the event is happening today. */
    public record EventItem(Long id, String title, LocalDate startDate, LocalDate endDate, String locationName, long daysUntil) {
    }

    /**
     * A review of "Review mới quanh …": {@code placeId} opens the place on the Map, {@code placeArea} is the area the
     * place is in ("Arakawa-ku, Tokyo · 東京都荒川区", null outside Japan), {@code near} AREA or PREFECTURE when it is the
     * user's area or prefecture, else null.
     */
    public record ReviewItem(Long id, Long placeId, String placeName, String categoryIcon, String placeArea, String near,
                             int rating, String authorName, String excerpt, Instant createdAt) {
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

    /**
     * {@code interests}: the topics "Dành cho bạn" learnt from what the user liked, saved, commented on or wrote;
     * {@code stageGuides}: up to 3 guides of the user's own stage not done yet, in progress first, never the one
     * "Việc tiếp theo" shows (empty without a stage).
     */
    public record MemberHome(String displayName, String stage, Long areaId, String areaName, int points, String rankName,
                             Phrase phrase, GuideProgress guide, List<EventItem> events, List<ReviewItem> reviews,
                             Featured featured, List<FeedPost> forYou, List<String> interests,
                             List<GuideCard> stageGuides) {
    }

    /**
     * A topic of "Chủ đề quan tâm": {@code chosen} by the Member, or {@code learnt} from what the Member did (never
     * both: a suggestion to choose it).
     */
    public record InterestTopic(long id, String name, boolean chosen, boolean learnt) {
    }

    /** {@code /api/me/interests}: every topic a Member can choose, in the Blog's order, and how many at most. */
    public record InterestSettings(int max, List<InterestTopic> topics) {
    }

    /** {@code PUT /api/me/interests}: the whole new choice, empty for none. */
    public record UpdateInterestsRequest(List<Long> topicIds) {
    }

    /**
     * A post chosen for the user; {@code reason} is the first badge text ("Gần khu vực của bạn"…) or null, {@code reasons}
     * every reason that matched, in ranking order; {@code engaged} when the user already liked, saved or commented on
     * it (such posts come last).
     */
    public record FeedPost(Long id, String title, String excerpt, String authorName, String topicName, int likeCount,
                           int commentCount, Instant createdAt, String reason, List<FeedReason> reasons,
                           boolean engaged) {
    }

    /**
     * Page "Dành cho bạn" ({@code /for-you}): one page of the whole personal feed in {@code scope} (ALL, or only the
     * posts that matched one reason: NEAR = area or prefecture, STAGE, INTEREST), the scopes this member can pick with
     * how many posts each holds, and what the feed is based on (area, stage code, interests). {@code q} is the search
     * text the posts and counts were narrowed by (null without one).
     */
    public record ForYouFeed(String scope, String q, String areaName, String stage, List<String> interests,
                             List<ScopeCount> scopes, int page, boolean hasMore, List<FeedPost> items) {
    }

    /**
     * Why a post is in "Dành cho bạn": {@code code} AREA, PREFECTURE, STAGE or INTEREST, {@code label} the badge text,
     * {@code detail} what matched (the linked place for AREA and PREFECTURE, the topic for STAGE and INTEREST).
     */
    public record FeedReason(String code, String label, String detail) {
    }
}
