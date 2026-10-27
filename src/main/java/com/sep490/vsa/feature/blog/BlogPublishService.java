package com.sep490.vsa.feature.blog;

import com.sep490.vsa.feature.blog.BlogWriteRepository.MediaState;
import com.sep490.vsa.feature.blog.BlogWriteRepository.PendingBlog;
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
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Second half of UC-11 for posts with photos or a video. The post waits in PROCESSING (only its author sees
 * it) while each file is scanned ({@link MediaScanService}). Then:
 * <ul>
 *   <li>every file passed → the post becomes PUBLISHED, the author earns the post's points now and is notified
 *       ({@code CONTENT_PUBLISHED});</li>
 *   <li>a file failed → the post becomes REJECTED, the failed files are deleted and the author is notified
 *       ({@code CONTENT_REJECTED}, with the reason);</li>
 *   <li>the scanner could not answer → the post stays PROCESSING and the retry job tries again.</li>
 * </ul>
 * Before the files are scanned and again right before the post is published, its title and text are checked against
 * the banned keywords as they are now (prostitution, gambling…: an Admin may have added one, or a new release a whole
 * list, since the post was sent). A post holding one becomes REJECTED without scanning its files, and the author is
 * told which keyword.
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
    private final ContentFilter filter;
    private final PointService points;
    private final NotificationService notifications;
    private final TransactionTemplate tx;
    private final Clock clock;
    /** Posts being scanned right now, so the job and the after-commit scan do not work on the same post. */
    private final Set<Long> inFlight = ConcurrentHashMap.newKeySet();
    /** The retry job's place in the waiting posts: the last id of its previous page, 0 to start from the oldest. */
    private volatile long retryAfter;

    public BlogPublishService(BlogWriteRepository repository, MediaScanService scanner, MediaService media,
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

    /** Called after a new post with media is committed; runs on a background thread. */
    @Async
    public void scanSoon(long blogId) {
        process(blogId);
    }

    /**
     * Every 2 minutes: the next {@value #RETRY_BATCH} posts still PROCESSING one minute after they were sent. Each run
     * goes on after the last post of the previous one and starts again from the oldest after a short page, so every
     * waiting post is tried within a few runs however many others stay stuck.
     */
    @Scheduled(fixedDelayString = "${app.moderation.retry-delay-ms:120000}",
            initialDelayString = "${app.moderation.retry-initial-delay-ms:30000}")
    public void retryPending() {
        List<Long> ids = repository.processingBlogIds(clock.instant().minus(Duration.ofMinutes(1)), retryAfter,
                RETRY_BATCH);
        retryAfter = ids.size() < RETRY_BATCH ? 0 : ids.get(ids.size() - 1);
        for (Long id : ids) {
            try {
                process(id);
            } catch (RuntimeException e) {
                log.warn("Scan of post {} failed: {}", id, e.getMessage());
            }
        }
    }

    /**
     * Scans the post's files that still wait, then publishes or rejects the post when all have an answer. A post
     * holding a banned keyword is rejected without scanning its files.
     */
    public void process(long blogId) {
        if (!inFlight.add(blogId)) {
            return;
        }
        try {
            PendingBlog blog = repository.pendingBlog(blogId).orElse(null);
            if (blog == null || !"PROCESSING".equals(blog.status())) {
                return;
            }
            if (bannedKeyword(blog).isEmpty()) {
                for (MediaState m : repository.mediaStates(blogId)) {
                    if ("PROCESSING".equals(m.scanStatus())) {
                        scanner.scan(m.id());
                    }
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
        // The text once more under the lock: a keyword the Admin added during the scan still keeps the post back
        Optional<String> keyword = bannedKeyword(blog);
        if (keyword.isPresent()) {
            repository.setStatus(blogId, "PROCESSING", "REJECTED");
            tellRejected(blog, "có từ khóa không được phép “" + keyword.get() + "”");
            return;
        }
        List<MediaState> files = repository.mediaStates(blogId);
        List<Long> rejected = files.stream().filter(m -> "REJECTED".equals(m.scanStatus())).map(MediaState::id).toList();
        if (!rejected.isEmpty()) {
            String reason = scanner.rejectionReason(rejected).orElse("ảnh hoặc video vi phạm Quy tắc cộng đồng");
            repository.setStatus(blogId, "PROCESSING", "REJECTED");
            media.discard(rejected);
            tellRejected(blog, reason);
            return;
        }
        boolean allPassed = files.stream().allMatch(m -> "PASSED".equals(m.scanStatus()));
        if (allPassed && repository.setStatus(blogId, "PROCESSING", "PUBLISHED")) {
            tellPublished(blog, awardOnce(blog.authorId(), blogId));
        }
    }

    /** The first banned keyword in the post's title or text (title first, like a new post). */
    private Optional<String> bannedKeyword(PendingBlog blog) {
        return filter.bannedKeyword(blog.title()).or(() -> filter.bannedKeyword(blog.content()));
    }

    private void tellRejected(PendingBlog blog, String reason) {
        notifications.send(blog.authorId(), NotificationService.CONTENT_REJECTED,
                what(blog) + " “" + blog.title() + "” chưa được đăng vì " + reason + ". Bạn có thể sửa bài rồi gửi lại.",
                refType(blog), blog.id());
    }

    /**
     * The scan passed: the post is public now. {@code paid}: it earned its points now, so this is its first
     * publication; otherwise an edit of a post that was published before (with new photos or a video) went through.
     */
    private void tellPublished(PendingBlog blog, boolean paid) {
        String text = paid
                ? what(blog) + " “" + blog.title() + "” đã qua kiểm duyệt ảnh/video và đã được đăng. +"
                        + PointService.BLOG_POST + " điểm."
                : "Bản sửa " + what(blog).toLowerCase(Locale.ROOT) + " “" + blog.title()
                        + "” đã qua kiểm duyệt ảnh/video và đã được đăng.";
        notifications.send(blog.authorId(), NotificationService.CONTENT_PUBLISHED, text, refType(blog), blog.id());
    }

    private static String what(PendingBlog blog) {
        return "QA".equals(blog.type()) ? "Câu hỏi" : "Bài viết";
    }

    private static String refType(PendingBlog blog) {
        return "QA".equals(blog.type()) ? "QUESTION" : "BLOG";
    }

    /**
     * The post's points ({@link PointService#BLOG_POST}), paid the first time it is published and never again:
     * an edited post that is scanned and published a second time, or a rejected post fixed later, earns nothing more.
     *
     * @return true when the points were paid now
     */
    boolean awardOnce(long authorId, long blogId) {
        if (repository.postAwarded(blogId)) {
            return false;
        }
        points.add(authorId, PointService.BLOG_POST, "BLOG_CREATED", "BLOG", blogId);
        return true;
    }
}
