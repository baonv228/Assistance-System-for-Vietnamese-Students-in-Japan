package com.sep490.vsa.feature.qa;

import com.sep490.vsa.feature.blog.BlogDtos.Author;
import com.sep490.vsa.feature.blog.BlogDtos.PlaceRef;
import com.sep490.vsa.feature.blog.BlogDtos.Tag;
import com.sep490.vsa.feature.blog.BlogDtos.TopicRef;
import com.sep490.vsa.feature.blog.BlogFeedRepository;
import com.sep490.vsa.feature.qa.QaDtos.Answerer;
import com.sep490.vsa.feature.qa.QaDtos.Overview;
import com.sep490.vsa.feature.qa.QaDtos.QuestionCard;
import com.sep490.vsa.feature.qa.QaDtos.QuestionPage;
import com.sep490.vsa.feature.qa.QaRepository.AnswererRow;
import com.sep490.vsa.feature.qa.QaRepository.Row;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Hỏi-Đáp list: questions with search, topic and status filters, sorting and paging, plus the side statistics.
 * Topics, linked places and identity tags of a page of cards are read with the same helpers as the Blog feed.
 */
@Service
@Transactional(readOnly = true)
public class QaService {

    static final int EXCERPT_LENGTH = 180;
    static final int SIDE_LIMIT = 5;
    static final int TOP_ANSWERER_DAYS = 30;

    private final QaRepository repository;
    private final BlogFeedRepository cards;
    private final Clock clock;

    public QaService(QaRepository repository, BlogFeedRepository cards, Clock clock) {
        this.repository = repository;
        this.cards = cards;
        this.clock = clock;
    }

    public QuestionPage list(QaListQuery query) {
        query = onlyActiveTopics(query);
        long total = repository.count(query);
        int totalPages = (int) Math.max(1, (total + query.size() - 1) / query.size());
        // A page past the end (e.g. after a filter change) falls back to the last page
        if (query.page() >= totalPages) {
            query = query.withPage(totalPages - 1);
        }
        List<QuestionCard> items = total == 0 ? List.of() : toCards(repository.find(query));
        return new QuestionPage(items, query.page(), query.size(), total, totalPages, query.filtered());
    }

    public Overview overview() {
        List<AnswererRow> rows = repository.topAnswerers(clock.instant().minus(Duration.ofDays(TOP_ANSWERER_DAYS)),
                SIDE_LIMIT);
        Map<Long, List<Tag>> tags = cards.tagsOf(rows.stream().map(r -> r.author().id()).toList());
        List<Answerer> answerers = rows.stream().map(r -> new Answerer(
                new Author(r.author().id(), r.author().displayName(), r.author().avatarUrl(),
                        tags.getOrDefault(r.author().id(), List.of())),
                r.answers(), r.best())).toList();
        return new Overview(cards.activeTopics(), repository.stats(), repository.popularTopics(SIDE_LIMIT), answerers,
                TOP_ANSWERER_DAYS);
    }

    /** The topic filter only uses topics that are still active; unknown or inactive ids are ignored. */
    private QaListQuery onlyActiveTopics(QaListQuery query) {
        if (query.topicIds().isEmpty()) {
            return query;
        }
        Set<Long> active = new HashSet<>(cards.activeTopics().stream().map(TopicRef::id).toList());
        return query.withTopicIds(query.topicIds().stream().filter(active::contains).toList());
    }

    private List<QuestionCard> toCards(List<Row> rows) {
        List<Long> ids = rows.stream().map(Row::id).toList();
        Set<Long> authorIds = new LinkedHashSet<>(rows.stream().map(Row::authorId).toList());
        Map<Long, List<TopicRef>> topics = cards.topicsOf(ids);
        Map<Long, List<PlaceRef>> places = cards.placesOf(ids);
        Map<Long, List<Tag>> tags = cards.tagsOf(authorIds);
        return rows.stream().map(r -> new QuestionCard(r.id(), r.status(), r.pinned(), r.solved(), r.title(),
                excerpt(r.contentStart(), EXCERPT_LENGTH),
                new Author(r.authorId(), r.authorName(), r.avatarUrl(), tags.getOrDefault(r.authorId(), List.of())),
                topics.getOrDefault(r.id(), List.of()), places.getOrDefault(r.id(), List.of()),
                r.likeCount(), r.answerCount(), r.createdAt())).toList();
    }

    /** Plain one-line excerpt cut at a word boundary. */
    static String excerpt(String text, int max) {
        if (text == null) {
            return "";
        }
        String flat = text.replaceAll("\\s+", " ").strip();
        if (flat.length() <= max) {
            return flat;
        }
        String cut = flat.substring(0, max);
        int space = cut.lastIndexOf(' ');
        return (space > max * 0.6 ? cut.substring(0, space) : cut).strip() + "…";
    }
}
