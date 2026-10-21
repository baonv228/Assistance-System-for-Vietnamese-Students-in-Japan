package com.sep490.vsa.feature.blog;

import com.sep490.vsa.common.settings.SystemSettings;
import com.sep490.vsa.feature.blog.BlogFeedRepository.Candidate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Computes "Bài nổi bật" automatically (no manual featuring): top 3 published posts (not questions) of the last 7 days (WEEK)
 * and of the last 30 days (MONTH) by likes + 2 × comments by others + the author's rank bonus, minimum score 10,
 * one post per author. Runs at startup, then every hour, and after an account is locked, unlocked or deleted (its posts
 * and comments are hidden, {@link ActiveAuthors}); each run replaces the previous snapshot.
 * The number of posts and the minimum score are the Admin settings featured_top_n and featured_min_score.
 */
@Service
public class FeaturedSnapshotService {

    public static final String WEEK = "WEEK";
    public static final String MONTH = "MONTH";
    public static final int DEFAULT_TOP_N = 3;
    /** {@code featured_snapshots.rank_no} is 1 to 3, so at most 3 posts are featured whatever the setting says. */
    public static final int MAX_TOP_N = 3;
    public static final int DEFAULT_MIN_SCORE = 10;

    private static final Logger log = LoggerFactory.getLogger(FeaturedSnapshotService.class);

    private final BlogFeedRepository repository;
    private final SystemSettings settings;
    private final Clock clock;
    private final TransactionTemplate transaction;

    public FeaturedSnapshotService(BlogFeedRepository repository, SystemSettings settings, Clock clock,
                                   PlatformTransactionManager txManager) {
        this.repository = repository;
        this.settings = settings;
        this.clock = clock;
        // Its own transaction, also when called after another transaction has committed (refreshAfterCommit)
        this.transaction = new TransactionTemplate(txManager);
        this.transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onStartup() {
        refreshSafely();
    }

    @Scheduled(cron = "0 0 * * * *")
    public void hourly() {
        refreshSafely();
    }

    /**
     * Recomputes the snapshot once the current transaction has committed (an account was locked, unlocked or
     * deleted, see {@link AuthorStatusSync}); outside a transaction, right away. A failure keeps the old snapshot.
     */
    public void refreshAfterCommit() {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            refreshSafely();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                refreshSafely();
            }
        });
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
        int topN = topN();
        int minScore = minScore();
        repository.replaceFeatured(WEEK, now,
                top(repository.featuredCandidates(now.minus(Duration.ofDays(7)), minScore), topN, minScore));
        repository.replaceFeatured(MONTH, now,
                top(repository.featuredCandidates(now.minus(Duration.ofDays(30)), minScore), topN, minScore));
    }

    /** featured_top_n (default 3), kept within 1 to {@link #MAX_TOP_N}. */
    public int topN() {
        return Math.max(1, Math.min(MAX_TOP_N, settings.getInt(SystemSettings.FEATURED_TOP_N, DEFAULT_TOP_N)));
    }

    /** featured_min_score (default 10), never below 0. */
    public int minScore() {
        return Math.max(0, settings.getInt(SystemSettings.FEATURED_MIN_SCORE, DEFAULT_MIN_SCORE));
    }

    /**
     * The best post published since {@code since}, computed now with the same score and minimum as the snapshot
     * (Home shows it); empty when no post reaches the minimum.
     */
    public Optional<Candidate> best(Instant since) {
        return repository.featuredCandidates(since, minScore()).stream().findFirst();
    }

    /** Candidates come sorted by score; keep the best post of each author until there are {@code topN}. */
    static List<Candidate> top(List<Candidate> sorted, int topN, int minScore) {
        Set<Long> authors = new HashSet<>();
        List<Candidate> top = new ArrayList<>();
        for (Candidate c : sorted) {
            if (c.score() >= minScore && authors.add(c.authorId())) {
                top.add(c);
                if (top.size() == topN) {
                    break;
                }
            }
        }
        return top;
    }
}
