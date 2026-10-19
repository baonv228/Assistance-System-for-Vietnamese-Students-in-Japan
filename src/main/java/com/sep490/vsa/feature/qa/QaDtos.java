package com.sep490.vsa.feature.qa;

import com.sep490.vsa.feature.blog.BlogDtos.Author;
import com.sep490.vsa.feature.blog.BlogDtos.PlaceRef;
import com.sep490.vsa.feature.blog.BlogDtos.TopicCount;
import com.sep490.vsa.feature.blog.BlogDtos.TopicRef;
import jakarta.validation.constraints.NotNull;

import java.time.Instant;
import java.util.List;

/** JSON shapes of Hỏi-Đáp: the question list and its side statistics. Detail and actions reuse the blog shapes. */
public final class QaDtos {

    private QaDtos() {
    }

    /**
     * One question in the list.
     *
     * @param status      PUBLISHED, or PROCESSING when the asker views their own question while its media is scanned
     * @param solved      the asker chose a best answer ("Đã giải quyết")
     * @param answerCount published answers and replies
     */
    public record QuestionCard(Long id, String status, boolean pinned, boolean solved, String title, String excerpt,
                               Author author, List<TopicRef> topics, List<PlaceRef> places, int likeCount,
                               int answerCount, Instant createdAt) {
    }

    /**
     * @param filtered true when a keyword, topic or status filter is applied; the page uses it to choose between
     *                 "no questions yet" and "no questions match"
     */
    public record QuestionPage(List<QuestionCard> items, int page, int size, long totalItems, int totalPages,
                               boolean filtered) {
    }

    /** Published questions: all, nobody answered yet, solved. */
    public record Stats(long total, long unanswered, long solved) {
    }

    /** A member who answers questions: answers in the period and how many were chosen as best. */
    public record Answerer(Author author, long answerCount, long bestCount) {
    }

    /** Everything on the screen that does not depend on the filters. */
    public record Overview(List<TopicRef> topics, Stats stats, List<TopicCount> popularTopics,
                           List<Answerer> topAnswerers, int topAnswerersDays) {
    }

    public record BestAnswerRequest(@NotNull(message = "Hãy chọn câu trả lời.") Long answerId) {
    }
}
