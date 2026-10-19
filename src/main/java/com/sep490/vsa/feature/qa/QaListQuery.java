package com.sep490.vsa.feature.qa;

import com.sep490.vsa.common.exception.ApiException;
import com.sep490.vsa.common.text.VietnameseText;

import java.util.List;
import java.util.Locale;

/**
 * Validated filters of the Hỏi-Đáp list.
 *
 * @param keyword  search text folded like {@link VietnameseText#fold}; null when there is no search
 * @param topicIds a question matches when it has at least one of them; empty = all topics
 * @param viewerId signed-in user, so they also see their own questions still in PROCESSING; null for a guest
 */
public record QaListQuery(String keyword, List<Long> topicIds, Status status, Sort sort, int page, int size,
                          Long viewerId) {

    public static final int KEYWORD_MAX = 100;
    public static final int DEFAULT_SIZE = 10;
    public static final int MAX_SIZE = 30;

    /**
     * ALL; UNANSWERED = nobody answered yet ("Chưa ai trả lời"); UNSOLVED = no best answer chosen yet
     * ("Chưa giải quyết", answered or not); SOLVED = a best answer was chosen ("Đã giải quyết").
     */
    public enum Status { ALL, UNANSWERED, UNSOLVED, SOLVED }

    public enum Sort { NEWEST, LIKES, ANSWERS }

    public static QaListQuery of(String q, List<Long> topicIds, String status, String sort, Integer page, Integer size,
                                 Long viewerId) {
        String raw = q == null ? "" : q.strip();
        if (raw.length() > KEYWORD_MAX) {
            throw ApiException.badRequest("Từ khóa tìm kiếm tối đa " + KEYWORD_MAX + " ký tự.");
        }
        String folded = VietnameseText.fold(raw);
        return new QaListQuery(
                folded.isEmpty() ? null : folded,
                topicIds == null ? List.of() : topicIds.stream().filter(id -> id != null && id > 0).distinct().toList(),
                parse(Status.class, status, Status.ALL),
                parse(Sort.class, sort, Sort.NEWEST),
                page == null ? 0 : Math.max(0, page),
                size == null ? DEFAULT_SIZE : Math.min(MAX_SIZE, Math.max(1, size)),
                viewerId);
    }

    /** True when the list is narrowed by a keyword, topic or status (sorting alone does not count). */
    public boolean filtered() {
        return keyword != null || !topicIds.isEmpty() || status != Status.ALL;
    }

    public QaListQuery withTopicIds(List<Long> ids) {
        return new QaListQuery(keyword, ids, status, sort, page, size, viewerId);
    }

    public QaListQuery withPage(int newPage) {
        return new QaListQuery(keyword, topicIds, status, sort, newPage, size, viewerId);
    }

    private static <E extends Enum<E>> E parse(Class<E> type, String value, E fallback) {
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
