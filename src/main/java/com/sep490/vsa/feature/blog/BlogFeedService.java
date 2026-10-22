package com.sep490.vsa.feature.blog;

import com.sep490.vsa.feature.blog.BlogDtos.Author;
import com.sep490.vsa.feature.blog.BlogDtos.BlogCard;
import com.sep490.vsa.feature.blog.BlogDtos.BlogPage;
import com.sep490.vsa.feature.blog.BlogDtos.Featured;
import com.sep490.vsa.feature.blog.BlogDtos.Overview;
import com.sep490.vsa.feature.blog.BlogDtos.PlaceRef;
import com.sep490.vsa.feature.blog.BlogDtos.Tag;
import com.sep490.vsa.feature.blog.BlogDtos.TopicRef;
import com.sep490.vsa.feature.blog.BlogDtos.Writer;
import com.sep490.vsa.feature.blog.BlogFeedRepository.Row;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** UC-09 View Blog List: posts with search, filters and paging, plus the featured and statistics blocks. */
@Service
@Transactional(readOnly = true)
public class BlogFeedService {

    static final int EXCERPT_LENGTH = 180;
    static final int FEATURED_EXCERPT_LENGTH = 150;
    static final int SIDE_LIMIT = 5;
    static final int TOP_WRITER_DAYS = 30;

    private final BlogFeedRepository repository;
    private final FeaturedSnapshotService featuredPosts;
    private final Clock clock;

    public BlogFeedService(BlogFeedRepository repository, FeaturedSnapshotService featuredPosts, Clock clock) {
        this.repository = repository;
        this.featuredPosts = featuredPosts;
        this.clock = clock;
    }

    public BlogPage list(BlogFeedQuery query) {
        query = onlyActiveTopics(query);
        long total = repository.count(query);
        int totalPages = (int) Math.max(1, (total + query.size() - 1) / query.size());
        // A page past the end (e.g. after a filter change) falls back to the last page
        if (query.page() >= totalPages) {
            query = query.withPage(totalPages - 1);
        }
        List<BlogCard> items = total == 0 ? List.of() : cards(repository.find(query), EXCERPT_LENGTH);
        return new BlogPage(items, query.page(), query.size(), total, totalPages, query.filtered());
    }

    public Overview overview() {
        List<Writer> writers = repository.topWriters(clock.instant().minus(Duration.ofDays(TOP_WRITER_DAYS)), SIDE_LIMIT)
                .stream().map(e -> new Writer(e.getKey(), e.getValue())).toList();
        Map<Long, List<Tag>> tags = repository.tagsOf(writers.stream().map(w -> w.author().id()).toList());
        writers = writers.stream().map(w -> new Writer(withTags(w.author(), tags), w.postCount())).toList();

        return new Overview(featured(), repository.activeTopics(), repository.popularTopics(SIDE_LIMIT), writers,
                TOP_WRITER_DAYS);
    }

    /**
     * Latest weekly snapshot; the monthly one when the week has no qualifying post. One post per author, at most
     * featured_top_n posts, each still reaching featured_min_score (Admin settings, read on every call).
     */
    Featured featured() {
        int topN = featuredPosts.topN();
        int minScore = featuredPosts.minScore();
        for (String period : List.of(FeaturedSnapshotService.WEEK, FeaturedSnapshotService.MONTH)) {
            List<Row> rows = repository.findPublished(repository.latestFeatured(period, minScore));
            Set<Long> authors = new HashSet<>();
            List<Row> onePerAuthor = rows.stream().filter(r -> authors.add(r.authorId()))
                    .limit(topN).toList();
            if (!onePerAuthor.isEmpty()) {
                return new Featured(period, cards(onePerAuthor, FEATURED_EXCERPT_LENGTH));
            }
        }
        return new Featured(null, List.of());
    }

    /** The topic filter only uses topics that are still active; unknown or inactive ids are ignored. */
    private BlogFeedQuery onlyActiveTopics(BlogFeedQuery query) {
        if (query.topicIds().isEmpty()) {
            return query;
        }
        Set<Long> active = new HashSet<>(repository.activeTopics().stream().map(TopicRef::id).toList());
        return query.withTopicIds(query.topicIds().stream().filter(active::contains).toList());
    }

    private List<BlogCard> cards(List<Row> rows, int excerptLength) {
        List<Long> ids = rows.stream().map(Row::id).toList();
        Set<Long> authorIds = new LinkedHashSet<>(rows.stream().map(Row::authorId).toList());
        Map<Long, List<TopicRef>> topics = repository.topicsOf(ids);
        Map<Long, List<PlaceRef>> places = repository.placesOf(ids);
        Map<Long, List<Tag>> tags = repository.tagsOf(authorIds);
        return rows.stream().map(r -> new BlogCard(r.id(), r.type(), r.status(), r.pinned(), r.answered(), r.title(),
                excerpt(r.contentStart(), excerptLength),
                new Author(r.authorId(), r.authorName(), r.avatarUrl(), tags.getOrDefault(r.authorId(), List.of())),
                topics.getOrDefault(r.id(), List.of()), places.getOrDefault(r.id(), List.of()),
                r.likeCount(), r.commentCount(), r.createdAt())).toList();
    }

    private static Author withTags(Author a, Map<Long, List<Tag>> tags) {
        return new Author(a.id(), a.displayName(), a.avatarUrl(), tags.getOrDefault(a.id(), List.of()));
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
