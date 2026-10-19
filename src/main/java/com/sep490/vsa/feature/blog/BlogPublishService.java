package com.sep490.vsa.feature.blog;

import com.sep490.vsa.feature.blog.BlogWriteRepository.MediaState;
import com.sep490.vsa.feature.blog.BlogWriteRepository.PendingBlog;
import com.sep490.vsa.feature.media.MediaService;
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
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Second half of UC-11 for posts with photos or a video. The post waits in PROCESSING (only its author sees
 * it) while each file is scanned ({@link MediaScanService}). Then:
 * <ul>
 *   <li>every file passed → the post becomes PUBLISHED and the author earns the post's points now;</li>
 *   <li>a file failed → the post becomes REJECTED, the failed files are deleted and the author is notified;</li>
 *   <li>the scanner could not answer → the post stays PROCESSING and the retry job tries again.</li>
 * </ul>
 * The scan starts right after the post is saved ({@link #scanSoon}); {@link #retryPending} picks up anything
 * left over (scanner down, server restarted in the middle).
 */
@Service
public class BlogPublishService {

    private static final Logger log = LoggerFactory.getLogger(BlogPublishService.class);
    private static final int RETRY_BATCH = 20;

    private final BlogWriteRepository repository;
    private final MediaScanService scanner;
    private final MediaService media;
    private final PointService points;
    private final NotificationService notifications;
    private final TransactionTemplate tx;
    private final Clock clock;
    /** Posts being scanned right now, so the job and the after-commit scan do not work on the same post. */
    private final Set<Long> inFlight = ConcurrentHashMap.newKeySet();

    public BlogPublishService(BlogWriteRepository repository, MediaScanService scanner, MediaService media,
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

    /** Called after a new post with media is committed; runs on a background thread. */
    @Async
    public void scanSoon(long blogId) {
        process(blogId);
    }

    /** Every 2 minutes: posts still PROCESSING one minute after they were sent. */
    @Scheduled(fixedDelayString = "${app.moderation.retry-delay-ms:120000}",
            initialDelayString = "${app.moderation.retry-initial-delay-ms:30000}")
    public void retryPending() {
        List<Long> ids = repository.processingBlogIds(clock.instant().minus(Duration.ofMinutes(1)), RETRY_BATCH);
        for (Long id : ids) {
            try {
                process(id);
            } catch (RuntimeException e) {
                log.warn("Scan of post {} failed: {}", id, e.getMessage());
            }
        }
    }

    /** Scans the post's files that still wait, then publishes or rejects the post when all have an answer. */
    public void process(long blogId) {
        if (!inFlight.add(blogId)) {
            return;
        }
        try {
            if (!"PROCESSING".equals(repository.status(blogId))) {
                return;
            }
            for (MediaState m : repository.mediaStates(blogId)) {
                if ("PROCESSING".equals(m.scanStatus())) {
                    scanner.scan(m.id());
                }
            }
            tx.executeWithoutResult(s -> decide(blogId));
        } finally {
            inFlight.remove(blogId);
        }
    }

    private void decide(long blogId) {
        PendingBlog blog = repository.lockBlog(blogId).orElse(null);
        if (blog == null || !"PROCESSING".equals(blog.status())) {
            return;
        }
        List<MediaState> files = repository.mediaStates(blogId);
        List<Long> rejected = files.stream().filter(m -> "REJECTED".equals(m.scanStatus())).map(MediaState::id).toList();
        if (!rejected.isEmpty()) {
            repository.setStatus(blogId, "PROCESSING", "REJECTED");
            media.discard(rejected);
            String what = "QA".equals(blog.type()) ? "Câu hỏi" : "Bài viết";
            notifications.send(blog.authorId(), NotificationService.CONTENT_REJECTED,
                    what + " “" + blog.title() + "” chưa được đăng vì ảnh hoặc video vi phạm Quy tắc cộng đồng. "
                            + "Bạn có thể sửa bài rồi gửi lại.", "QA".equals(blog.type()) ? "QUESTION" : "BLOG", blogId);
            return;
        }
        boolean allPassed = files.stream().allMatch(m -> "PASSED".equals(m.scanStatus()));
        if (allPassed && repository.setStatus(blogId, "PROCESSING", "PUBLISHED")) {
            awardOnce(blog.authorId(), blogId);
        }
    }

    /**
     * The post's points ({@link PointService#BLOG_POST}), paid the first time it is published and never again:
     * an edited post that is scanned and published a second time, or a rejected post fixed later, earns nothing more.
     */
    void awardOnce(long authorId, long blogId) {
        if (!repository.postAwarded(blogId)) {
            points.add(authorId, PointService.BLOG_POST, "BLOG_CREATED", "BLOG", blogId);
        }
    }
}
