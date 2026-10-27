package com.sep490.vsa.feature.event;

import com.sep490.vsa.feature.event.EventRepository.MediaState;
import com.sep490.vsa.feature.event.EventRepository.ReviewState;
import com.sep490.vsa.feature.media.MediaService;
import com.sep490.vsa.feature.moderation.ContentFilter;
import com.sep490.vsa.feature.moderation.MediaScanService;
import com.sep490.vsa.feature.notification.NotificationService;
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
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Second half of writing an event review with photos or a video, like {@code BlogPublishService} for posts. The review
 * waits in PROCESSING (only its author sees it) while each file is scanned ({@link MediaScanService}). Then:
 * <ul>
 *   <li>every file passed → PUBLISHED, the author earns the review's points now and is notified;</li>
 *   <li>a file failed → REJECTED, the failed files are deleted and the author is notified;</li>
 *   <li>the scanner could not answer → it stays PROCESSING and the retry job tries again.</li>
 * </ul>
 * The text is checked against the banned keywords as they are now before the files are scanned and again right before
 * publishing; a review holding one becomes REJECTED without scanning.
 */
@Service
public class EventReviewPublishService {

    private static final Logger log = LoggerFactory.getLogger(EventReviewPublishService.class);
    private static final int RETRY_BATCH = 20;
    /** {@code point_transactions.reason} of a review's points. */
    static final String AWARD_REASON = "REVIEW_CREATED";

    private final EventRepository repository;
    private final MediaScanService scanner;
    private final MediaService media;
    private final ContentFilter filter;
    private final PointService points;
    private final NotificationService notifications;
    private final TransactionTemplate tx;
    private final Clock clock;
    private final Set<Long> inFlight = ConcurrentHashMap.newKeySet();
    /** The retry job's place among the waiting reviews: the last id of its previous page, 0 from the oldest. */
    private volatile long retryAfter;

    public EventReviewPublishService(EventRepository repository, MediaScanService scanner, MediaService media,
                                     ContentFilter filter, PointService points, NotificationService notifications,
                                     PlatformTransactionManager transactionManager, Clock clock) {
        this.repository = repository;
        this.scanner = scanner;
        this.media = media;
        this.filter = filter;
        this.points = points;
        this.notifications = notifications;
        this.tx = new TransactionTemplate(transactionManager);
        this.clock = clock;
    }

    /** Called after a review with media is committed; runs on a background thread. */
    @Async
    public void scanSoon(long reviewId) {
        process(reviewId);
    }

    /** Every 2 minutes: the next waiting reviews sent more than a minute ago (same paging as the blog job). */
    @Scheduled(fixedDelayString = "${app.moderation.retry-delay-ms:120000}",
            initialDelayString = "${app.moderation.retry-initial-delay-ms:30000}")
    public void retryPending() {
        List<Long> ids = repository.processingReviewIds(clock.instant().minus(Duration.ofMinutes(1)), retryAfter,
                RETRY_BATCH);
        retryAfter = ids.size() < RETRY_BATCH ? 0 : ids.get(ids.size() - 1);
        for (Long id : ids) {
            try {
                process(id);
            } catch (RuntimeException e) {
                log.warn("Scan of event review {} failed: {}", id, e.getMessage());
            }
        }
    }

    public void process(long reviewId) {
        if (!inFlight.add(reviewId)) {
            return;
        }
        try {
            ReviewState review = repository.review(reviewId, false).orElse(null);
            if (review == null || !"PROCESSING".equals(review.status())) {
                return;
            }
            if (filter.bannedKeyword(review.content()).isEmpty()) {
                for (MediaState m : repository.mediaStates(reviewId)) {
                    if ("PROCESSING".equals(m.scanStatus())) {
                        scanner.scan(m.id());
                    }
                }
            }
            tx.executeWithoutResult(s -> decide(reviewId));
        } finally {
            inFlight.remove(reviewId);
        }
    }

    private void decide(long reviewId) {
        ReviewState review = repository.review(reviewId, true).orElse(null);
        if (review == null || !"PROCESSING".equals(review.status())) {
            return;
        }
        Optional<String> keyword = filter.bannedKeyword(review.content());
        if (keyword.isPresent()) {
            repository.setStatus(reviewId, "PROCESSING", "REJECTED");
            tellRejected(review, "có từ khóa không được phép “" + keyword.get() + "”");
            return;
        }
        List<MediaState> files = repository.mediaStates(reviewId);
        List<Long> rejected = files.stream().filter(m -> "REJECTED".equals(m.scanStatus())).map(MediaState::id).toList();
        if (!rejected.isEmpty()) {
            String reason = scanner.rejectionReason(rejected).orElse("ảnh hoặc video vi phạm Quy tắc cộng đồng");
            repository.setStatus(reviewId, "PROCESSING", "REJECTED");
            media.discard(rejected);
            tellRejected(review, reason);
            return;
        }
        if (files.stream().allMatch(m -> "PASSED".equals(m.scanStatus()))
                && repository.setStatus(reviewId, "PROCESSING", "PUBLISHED")) {
            tellPublished(review, awardOnce(review.authorId(), reviewId));
        }
    }

    private void tellRejected(ReviewState review, String reason) {
        notifications.send(review.authorId(), NotificationService.CONTENT_REJECTED,
                "Review “" + eventTitle(review) + "” chưa được đăng vì " + reason + ". Bạn có thể sửa review rồi gửi lại.",
                "REVIEW", review.id());
    }

    /** {@code paid}: first publication; otherwise an edit of a review that was published before went through. */
    private void tellPublished(ReviewState review, boolean paid) {
        String text = paid
                ? "Review “" + eventTitle(review) + "” đã qua kiểm duyệt ảnh/video và đã được đăng. +"
                        + PointService.REVIEW + " điểm."
                : "Bản sửa review “" + eventTitle(review) + "” đã qua kiểm duyệt ảnh/video và đã được đăng.";
        notifications.send(review.authorId(), NotificationService.CONTENT_PUBLISHED, text, "REVIEW", review.id());
    }

    private String eventTitle(ReviewState review) {
        return repository.event(review.eventId()).map(EventRepository.EventRow::title).orElse("sự kiện");
    }

    /**
     * The review's points ({@link PointService#REVIEW}), paid the first time it is published and never again.
     *
     * @return true when the points were paid now
     */
    boolean awardOnce(long authorId, long reviewId) {
        if (repository.reviewAwarded(reviewId)) {
            return false;
        }
        points.add(authorId, PointService.REVIEW, AWARD_REASON, "REVIEW", reviewId);
        return true;
    }
}
