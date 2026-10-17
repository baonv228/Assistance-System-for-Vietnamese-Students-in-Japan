package com.sep490.vsa.feature.blog;

import java.time.Instant;
import java.util.List;

/** JSON shapes of the Blog Feed screen (UC-09 View Blog List). */
public final class BlogDtos {

    private BlogDtos() {
    }

    public record Tag(String name, String color) {
    }

    public record Author(Long id, String displayName, String avatarUrl, List<Tag> tags) {
    }

    public record TopicRef(Long id, String name) {
    }

    public record PlaceRef(Long id, String name) {
    }

    /**
     * One card in the list.
     *
     * @param type   POST (Bài viết) or QA (Hỏi đáp)
     * @param status PUBLISHED, or PROCESSING when the author views their own post while its media is scanned
     */
    public record BlogCard(Long id, String type, String status, boolean pinned, boolean answered, String title,
                           String excerpt, Author author, List<TopicRef> topics, List<PlaceRef> places,
                           int likeCount, int commentCount, Instant createdAt) {
    }

    /**
     * @param filtered true when a keyword, topic or type filter is applied; the page uses it to choose
     *                 between "no posts yet" (MSG32) and "no posts match" (MSG33)
     */
    public record BlogPage(List<BlogCard> items, int page, int size, long totalItems, int totalPages,
                           boolean filtered) {
    }

    /** @param period WEEK or MONTH; null when no post qualifies */
    public record Featured(String period, List<BlogCard> posts) {
    }

    public record TopicCount(Long id, String name, long postCount) {
    }

    public record Writer(Author author, long postCount) {
    }

    public record OpenQuestion(Long id, String title, Instant createdAt) {
    }

    /** Everything on the screen that does not depend on the filters. */
    public record Overview(Featured featured, List<TopicRef> topics, List<TopicCount> popularTopics,
                           List<Writer> topWriters, int topWritersDays, List<OpenQuestion> openQuestions,
                           long openQuestionCount) {
    }
}
