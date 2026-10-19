package com.sep490.vsa.feature.blog;

import com.sep490.vsa.common.exception.ApiException;
import com.sep490.vsa.common.text.VietnameseText;

import java.util.List;
import java.util.Locale;

/**
 * Validated filters of the Blog Feed (SRS 6.1). The feed lists posts only (blog_type POST); questions have
 * their own list in feature/qa.
 *
 * @param keyword  search text already folded by {@link #fold(String)}; null when there is no search
 * @param topicIds selected topics (a post matches when it has at least one of them); empty = all topics
 * @param viewerId signed-in user, so they also see their own posts still in PROCESSING; null for a guest
 */
public record BlogFeedQuery(String keyword, List<Long> topicIds, Sort sort, int page, int size, Long viewerId) {

    public static final int KEYWORD_MAX = 100;
    public static final int DEFAULT_SIZE = 10;
    public static final int MAX_SIZE = 30;

    public enum Sort { NEWEST, LIKES, COMMENTS }

    public static BlogFeedQuery of(String q, List<Long> topicIds, String sort, Integer page, Integer size,
                                   Long viewerId) {
        String raw = q == null ? "" : q.strip();
        if (raw.length() > KEYWORD_MAX) {
            throw ApiException.badRequest("Từ khóa tìm kiếm tối đa " + KEYWORD_MAX + " ký tự.");
        }
        String folded = fold(raw);
        return new BlogFeedQuery(
                folded.isEmpty() ? null : folded,
                topicIds == null ? List.of() : topicIds.stream().filter(id -> id != null && id > 0).distinct().toList(),
                parse(Sort.class, sort, Sort.NEWEST),
                page == null ? 0 : Math.max(0, page),
                size == null ? DEFAULT_SIZE : Math.min(MAX_SIZE, Math.max(1, size)),
                viewerId);
    }

    /** True when the list is narrowed by a keyword or topic (sorting alone does not count). */
    public boolean filtered() {
        return keyword != null || !topicIds.isEmpty();
    }

    public BlogFeedQuery withTopicIds(List<Long> ids) {
        return new BlogFeedQuery(keyword, ids, sort, page, size, viewerId);
    }

    public BlogFeedQuery withPage(int newPage) {
        return new BlogFeedQuery(keyword, topicIds, sort, newPage, size, viewerId);
    }

    /**
     * Lower case without Vietnamese diacritics, single spaces: "Phân loại RÁC  ở Đà" → "phan loai rac o da".
     * The database side gets the same folding (see {@link BlogFeedRepository}), so search ignores accents and case.
     */
    public static String fold(String text) {
        return VietnameseText.fold(text);
    }

    static <E extends Enum<E>> E parse(Class<E> type, String value, E fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            return Enum.valueOf(type, value.strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest("Giá trị lọc không hợp lệ: " + value);
        }
    }
}
