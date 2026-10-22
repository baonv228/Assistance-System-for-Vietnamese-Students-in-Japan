package com.sep490.vsa.feature.review;

import com.sep490.vsa.feature.media.MediaService;
import com.sep490.vsa.feature.moderation.MediaScanService;
import com.sep490.vsa.feature.notification.NotificationService;
import com.sep490.vsa.feature.review.ReviewRepository.AttachedMedia;
import com.sep490.vsa.feature.review.ReviewRepository.ReviewRow;
import com.sep490.vsa.feature.user.PointService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Second half of UC-U16 / UC-U17 for reviews with photos or a video, like posts (BlogPublishService). The review waits
 * in PROCESSING (only its author sees it) while each file is scanned. Then every file passed → PUBLISHED and the
 * author earns the review's points (once); a file failed → REJECTED, the failed files are deleted and the author is
 * notified to edit the review; the scanner could not answer → it stays PROCESSING and the retry job tries again.
 */
@Service
public class ReviewPublishService {

    private static final Logger log = LoggerFactory.getLogger(ReviewPublishService.class);
    private static final int RETRY_BATCH = 20;

    private final ReviewRepository repository;
    private final MediaScanService scanner;
    private final MediaService media;
    private final PointService points;
    private final NotificationService notifications;
    private final TransactionTemplate tx;
    private final Clock clock;
    private final Set<Long> inFlight = ConcurrentHashMap.newKeySet();

    public ReviewPublishService(ReviewRepository repository, MediaScanService scanner, MediaService media,
                                PointService points, NotificationService notifications,
                                PlatformTransactionManager transactionManager, Clock clock) {
        this.repository = repository;
        this.scanner = scanner;
        this.media = media;
        this.points = points;
        this.notifications = notifications;
        this.tx = new TransactionTemplate(transactionManager);
        this.clock = clock;
    }

    /** Called after a review with new media is committed; runs on a background thread. */
    @Async
    public void scanSoon(long reviewId) {
        process(reviewId);
    }

    /** Every 2 minutes: reviews still PROCESSING one minute after they were sent. */
    @Scheduled(fixedDelayString = "${app.moderation.retry-delay-ms:120000}",
            initialDelayString = "${app.moderation.retry-initial-delay-ms:30000}")
    public void retryPending() {
        for (Long id : repository.processingIds(clock.instant().minus(Duration.ofMinutes(1)), RETRY_BATCH)) {
            try {
                process(id);
            } catch (RuntimeException e) {
                log.warn("Scan of review {} failed: {}", id, e.getMessage());
            }
        }
    }

    public void process(long reviewId) {
        if (!inFlight.add(reviewId)) {
            return;
        }
        try {
            if (!"PROCESSING".equals(repository.status(reviewId))) {
                return;
            }
            for (AttachedMedia m : repository.media(reviewId)) {
                if ("PROCESSING".equals(m.scanStatus())) {
                    scanner.scan(m.id());
                }
            }
            tx.executeWithoutResult(s -> decide(reviewId));
        } finally {
            inFlight.remove(reviewId);
        }
    }

    private void decide(long reviewId) {
        ReviewRow review = repository.review(reviewId, true).orElse(null);
        if (review == null || !"PROCESSING".equals(review.status())) {
            return;
        }
        List<AttachedMedia> files = repository.media(reviewId);
        List<Long> rejected = files.stream().filter(m -> "REJECTED".equals(m.scanStatus()))
                .map(AttachedMedia::id).toList();
        if (!rejected.isEmpty()) {
            repository.setStatus(reviewId, "PROCESSING", "REJECTED");
            repository.unlinkMedia(reviewId, rejected);
            media.discard(rejected);
            String place = review.placeId() == null ? "" : repository.place(review.placeId())
                    .map(p -> " về “" + p.name() + "”").orElse("");
            notifications.send(review.authorId(), NotificationService.CONTENT_REJECTED,
                    "Review" + place + " chưa được đăng vì ảnh hoặc video vi phạm Quy tắc cộng đồng. "
                            + "Bạn có thể sửa review rồi gửi lại.", "REVIEW", reviewId);
            return;
        }
        if (files.stream().allMatch(m -> "PASSED".equals(m.scanStatus()))
                && repository.setStatus(reviewId, "PROCESSING", "PUBLISHED")) {
            awardOnce(review.authorId(), reviewId);
        }
    }

    /** +10 the first time the review is published, never again (an edited or re-sent review earns nothing more). */
    void awardOnce(long authorId, long reviewId) {
        if (!repository.awarded(reviewId)) {
            points.add(authorId, PointService.REVIEW, "REVIEW_CREATED", "REVIEW", reviewId);
        }
    }
}
