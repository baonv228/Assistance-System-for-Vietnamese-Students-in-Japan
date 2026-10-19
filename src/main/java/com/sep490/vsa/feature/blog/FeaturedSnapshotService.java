package com.sep490.vsa.feature.blog;

import com.sep490.vsa.feature.blog.BlogFeedRepository.Candidate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Computes "Bài nổi bật" automatically (no manual featuring): top 3 published posts (not questions) of the last 7 days (WEEK)
 * and of the last 30 days (MONTH) by likes + 2 × comments + the author's rank bonus, minimum score 10,
 * one post per author. Runs at startup and then every hour; each run replaces the previous snapshot.
 */
@Service
public class FeaturedSnapshotService {

    public static final String WEEK = "WEEK";
    public static final String MONTH = "MONTH";
    public static final int TOP_N = 3;
    public static final int MIN_SCORE = 10;

    private static final Logger log = LoggerFactory.getLogger(FeaturedSnapshotService.class);

    private final BlogFeedRepository repository;
    private final Clock clock;
    private final TransactionTemplate transaction;

    public FeaturedSnapshotService(BlogFeedRepository repository, Clock clock, PlatformTransactionManager txManager) {
        this.repository = repository;
        this.clock = clock;
        this.transaction = new TransactionTemplate(txManager);
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onStartup() {
        refreshSafely();
    }

    @Scheduled(cron = "0 0 * * * *")
    public void hourly() {
        refreshSafely();
    }

    private void refreshSafely() {
        try {
            transaction.executeWithoutResult(status -> refresh());
        } catch (RuntimeException e) {
            // A failed run keeps the previous snapshot; the next run tries again
            log.warn("Could not refresh featured posts: {}", e.getMessage());
        }
    }

    /** Recomputes both periods. Call inside a transaction so readers never see a half-written snapshot. */
    void refresh() {
        Instant now = clock.instant().truncatedTo(ChronoUnit.SECONDS);
        repository.replaceFeatured(WEEK, now, top(repository.featuredCandidates(now.minus(Duration.ofDays(7)), MIN_SCORE)));
        repository.replaceFeatured(MONTH, now, top(repository.featuredCandidates(now.minus(Duration.ofDays(30)), MIN_SCORE)));
    }

    /** Candidates come sorted by score; keep the best post of each author until there are three. */
    static List<Candidate> top(List<Candidate> sorted) {
        Set<Long> authors = new HashSet<>();
        List<Candidate> top = new ArrayList<>();
        for (Candidate c : sorted) {
            if (c.score() >= MIN_SCORE && authors.add(c.authorId())) {
                top.add(c);
                if (top.size() == TOP_N) {
                    break;
                }
            }
        }
        return top;
    }
}
