package com.sep490.vsa.feature.home;

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
    public record EventItem(String title, LocalDate startDate, LocalDate endDate, String locationName, long daysUntil) {
    }

    public record ReviewItem(String placeName, String categoryIcon, int rating, String authorName, String excerpt) {
    }

    public record Featured(Long id, String title, String excerpt, String authorName, List<Tag> authorTags,
                           int readMinutes, String period) {
    }

    public record MemberHome(String displayName, String stage, String areaName, int points, String rankName,
                             Phrase phrase, GuideProgress guide, List<EventItem> events, List<ReviewItem> reviews,
                             Featured featured) {
    }
}
