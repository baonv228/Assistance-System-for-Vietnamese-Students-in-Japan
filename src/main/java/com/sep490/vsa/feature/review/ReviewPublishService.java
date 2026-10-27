package com.sep490.vsa.feature.review;

import com.sep490.vsa.feature.media.MediaService;
import com.sep490.vsa.feature.moderation.ContentFilter;
import com.sep490.vsa.feature.moderation.MediaScanService;
import com.sep490.vsa.feature.notification.NotificationService;
import com.sep490.vsa.feature.review.ReviewRepository.AttachedMedia;
import com.sep490.vsa.feature.review.ReviewRepository.PlaceRef;
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
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Second half of UC-U16 / UC-U17 for reviews with photos or a video, like posts (BlogPublishService). The review waits
 * in PROCESSING (only its author sees it) while each file is scanned. Then every file passed → PUBLISHED, the author
 * earns the review's points (once) and is notified; a file failed → REJECTED, the failed files are deleted and the
 * author is notified to edit the review; the scanner could not answer → it stays PROCESSING and the retry job tries
 * again. The text is checked against the banned keywords as they are now before the files are scanned and again right
 * before publishing (a keyword may be added while the review waits), like posts and event reviews; a hit → REJECTED
 * without scanning. Only place reviews: event reviews share the table and have their own job
 * (EventReviewPublishService).
 */
@Service
public class ReviewPublishService {

    private static final Logger log = LoggerFactory.getLogger(ReviewPublishService.class);
    private static final int RETRY_BATCH = 20;

    private final ReviewRepository repository;
    private final MediaScanService scanner;
    private final MediaService media;
    private final ContentFilter filter;
    private final PointService points;
    private final NotificationService notifications;
    private final TransactionTemplate tx;
    private final Clock clock;
    private final Set<Long> inFlight = ConcurrentHashMap.newKeySet();

    public ReviewPublishService(ReviewRepository repository, MediaScanService scanner, MediaService media,
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

    /** Called after a review with new media is committed; runs on a background thread. */
    @Async
    public void scanSoon(long reviewId) {
        process(reviewId);
    }

    /** Every 2 minutes: place reviews still PROCESSING one minute after they were sent (not event reviews). */
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
            ReviewRow review = repository.review(reviewId, false).orElse(null);
            if (review == null || review.placeId() == null || !"PROCESSING".equals(review.status())) {
                return;
            }
            if (filter.bannedKeyword(review.content()).isEmpty()) {
                for (AttachedMedia m : repository.media(reviewId)) {
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
        ReviewRow review = repository.review(reviewId, true).orElse(null);
        if (review == null || review.placeId() == null || !"PROCESSING".equals(review.status())) {
            return;
        }
        Optional<String> keyword = filter.bannedKeyword(review.content());
        if (keyword.isPresent()) {
            repository.setStatus(reviewId, "PROCESSING", "REJECTED");
            tellRejected(review, "có từ khóa không được phép “" + keyword.get() + "”");
            return;
        }
        List<AttachedMedia> files = repository.media(reviewId);
        List<Long> rejected = files.stream().filter(m -> "REJECTED".equals(m.scanStatus()))
                .map(AttachedMedia::id).toList();
        Optional<PlaceRef> place = review.placeId() == null ? Optional.empty() : repository.place(review.placeId());
        String about = place.map(p -> " về “" + p.name() + "”").orElse("");
        if (!rejected.isEmpty()) {
            String reason = scanner.rejectionReason(rejected).orElse("ảnh hoặc video vi phạm Quy tắc cộng đồng");
            repository.setStatus(reviewId, "PROCESSING", "REJECTED");
            repository.unlinkMedia(reviewId, rejected);
            media.discard(rejected);
            tellRejected(review, reason);
            return;
        }
        if (files.stream().allMatch(m -> "PASSED".equals(m.scanStatus()))
                && repository.setStatus(reviewId, "PROCESSING", "PUBLISHED")) {
            boolean paid = awardOnce(review.authorId(), reviewId);
            boolean pendingPin = place.map(p -> "PENDING".equals(p.status())).orElse(false);
            notifications.send(review.authorId(), NotificationService.CONTENT_PUBLISHED,
                    publishedText(about, paid, pendingPin), "REVIEW", reviewId);
        }
    }

    private void tellRejected(ReviewRow review, String reason) {
        String place = repository.place(review.placeId()).map(p -> " về “" + p.name() + "”").orElse("");
        notifications.send(review.authorId(), NotificationService.CONTENT_REJECTED,
                "Review" + place + " chưa được đăng vì " + reason + ". Bạn có thể sửa review rồi gửi lại.",
                "REVIEW", review.id());
    }

    /**
     * "Review về “X” đã qua kiểm duyệt ảnh/video và đã được đăng. +10 điểm." Not paid now: an edit of a review that was
     * published before. A review of the author's own pin waiting for a Moderator is shown to others only once the pin
     * is approved.
     */
    static String publishedText(String about, boolean paid, boolean pendingPin) {
        String review = (paid ? "Review" : "Bản sửa review") + (pendingPin ? " đầu tiên" : "") + about;
        String shown = pendingPin
                ? " đã qua kiểm duyệt ảnh/video. Review sẽ hiển thị cho mọi người khi ghim được Moderator duyệt."
                : " đã qua kiểm duyệt ảnh/video và đã được đăng.";
        return review + shown + (paid ? " +" + PointService.REVIEW + " điểm." : "");
    }

    /**
     * +10 the first time the review is published, never again (an edited or re-sent review earns nothing more).
     *
     * @return true when the points were paid now
     */
    boolean awardOnce(long authorId, long reviewId) {
        if (repository.awarded(reviewId)) {
            return false;
        }
        points.add(authorId, PointService.REVIEW, "REVIEW_CREATED", "REVIEW", reviewId);
        return true;
    }
}
