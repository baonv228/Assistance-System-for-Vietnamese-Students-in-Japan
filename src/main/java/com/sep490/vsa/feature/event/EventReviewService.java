package com.sep490.vsa.feature.event;

import com.sep490.vsa.common.exception.ApiException;
import com.sep490.vsa.common.security.AppUserPrincipal;
import com.sep490.vsa.common.settings.SystemSettings;
import com.sep490.vsa.feature.blog.BlogWriteRepository;
import com.sep490.vsa.feature.blog.BlogWriteRepository.Account;
import com.sep490.vsa.feature.blog.BlogWriteRepository.Usage;
import com.sep490.vsa.feature.event.EventDtos.EventCard;
import com.sep490.vsa.feature.event.EventDtos.LikeState;
import com.sep490.vsa.feature.event.EventDtos.Limits;
import com.sep490.vsa.feature.event.EventDtos.Media;
import com.sep490.vsa.feature.event.EventDtos.OwnReview;
import com.sep490.vsa.feature.event.EventDtos.Quota;
import com.sep490.vsa.feature.event.EventDtos.ReportRequest;
import com.sep490.vsa.feature.event.EventDtos.ReviewForm;
import com.sep490.vsa.feature.event.EventDtos.ReviewRequest;
import com.sep490.vsa.feature.event.EventDtos.ReviewSaved;
import com.sep490.vsa.feature.event.EventDtos.ReviewUpdateRequest;
import com.sep490.vsa.feature.event.EventRepository.EventRow;
import com.sep490.vsa.feature.event.EventRepository.ReviewState;
import com.sep490.vsa.feature.media.InvalidMediaException;
import com.sep490.vsa.feature.media.MediaService;
import com.sep490.vsa.feature.media.MediaService.Prepared;
import com.sep490.vsa.feature.moderation.ContentFilter;
import com.sep490.vsa.feature.report.ContentReportService;
import com.sep490.vsa.feature.report.ContentReportService.Outcome;
import com.sep490.vsa.feature.report.ContentReportService.Target;
import com.sep490.vsa.feature.user.PointService;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Reviews of an event ("Viết review năm 2026"): one per user per year the event is held, once it has started. Stored in
 * the shared {@code reviews} table with {@code event_id} and {@code event_year}.
 * <p>
 * When the author presses "Đăng review", the checks run in the Write Post order and stop at the first failing step:
 * <ol>
 *   <li>Stars, text (≤ 2,000 characters) and the photo/video files themselves (≤ 5 photos ≤ 20 MB, one video ≤ 30 s).</li>
 *   <li>Links (BR-22): only accounts with an identity tag may post links; nobody may use short links.</li>
 *   <li>New-account limit (BR-07): under {@code new_account_days}, at most {@code posts_per_day} posts or reviews and
 *       {@code videos_per_day} videos a day (counted together with blog posts in {@code user_daily_usage}).</li>
 *   <li>Banned keywords (MSG36).</li>
 * </ol>
 * Without files the review is PUBLISHED at once and earns {@link PointService#REVIEW} points. With files it is
 * PROCESSING (only its author sees it) until {@link EventReviewPublishService} has scanned them.
 * <p>
 * Members (role USER) only; never on their own review for like and report. Actions that like, report or delete lock
 * the review row first and run READ COMMITTED, like the blog actions.
 */
@Service
public class EventReviewService {

    static final String REVIEW_NOT_FOUND = "Không tìm thấy review. Review có thể đã bị gỡ hoặc đã bị xóa.";
    static final String NO_PERMISSION = "Bạn không có quyền thực hiện thao tác này.";          // MSG18
    /** An own review can be edited while it is published, waiting for its scan or rejected by it. */
    static final Set<String> EDITABLE = Set.of("PUBLISHED", "PROCESSING", "REJECTED");

    private final EventRepository repository;
    private final EventService events;
    private final BlogWriteRepository accounts;
    private final ContentFilter filter;
    private final MediaService media;
    private final SystemSettings settings;
    private final EventReviewPublishService publisher;
    private final PointService points;
    private final ContentReportService reports;
    private final TransactionTemplate tx;
    private final Clock clock;

    public EventReviewService(EventRepository repository, EventService events, BlogWriteRepository accounts,
                              ContentFilter filter, MediaService media, SystemSettings settings,
                              EventReviewPublishService publisher, PointService points, ContentReportService reports,
                              PlatformTransactionManager transactionManager, Clock clock) {
        this.repository = repository;
        this.events = events;
        this.accounts = accounts;
        this.filter = filter;
        this.media = media;
        this.settings = settings;
        this.publisher = publisher;
        this.points = points;
        this.reports = reports;
        this.tx = new TransactionTemplate(transactionManager);
        this.clock = clock;
    }

    // ---------------------------------------------------------------- form

    /** The "Viết review" form: the event, limits, today's usage, and the viewer's review of this year to edit. */
    @Transactional(readOnly = true)
    public ReviewForm form(AppUserPrincipal me, long eventId) {
        Account account = member(me);
        EventCard event = startedEvent(eventId);
        OwnReview own = repository.ownReviewId(eventId, event.reviewYear(), account.id())
                .flatMap(repository::ownReview)
                .filter(r -> !"REMOVED".equals(r.status()))
                .map(r -> new OwnReview(r.id(), r.rating(), r.content(), r.status(), repository.attachedMedia(r.id())))
                .orElse(null);
        return new ReviewForm(event, limits(), quota(account), filter.hasIdentityTag(account.id()), own);
    }

    // ---------------------------------------------------------------- create

    /** Step 1 runs before the transaction (decoding photos takes a moment); steps 2 to 4 and the save run in one. */
    public ReviewSaved create(AppUserPrincipal me, long eventId, ReviewRequest request, List<MultipartFile> images,
                              MultipartFile video) {
        Account account = member(me);
        EventCard event = startedEvent(eventId);
        if (repository.ownReviewId(eventId, event.reviewYear(), account.id()).isPresent()) {
            throw alreadyReviewed(event.reviewYear());
        }
        Integer rating = request == null ? null : request.rating();
        String content = request == null ? null : EventRules.strip(request.content());
        List<MultipartFile> imageFiles = nonEmpty(images);
        MultipartFile videoFile = video == null || video.isEmpty() ? null : video;
        List<Prepared> files = checkFields(rating, content, imageFiles, videoFile, imageFiles.size(),
                videoFile == null ? 0 : 1);
        boolean withVideo = videoFile != null;
        try {
            return tx.execute(s -> save(account, eventId, rating, content, files, withVideo));
        } catch (DuplicateKeyException e) {
            // The same user sent the form twice at the same moment: the unique key keeps one review per year
            throw alreadyReviewed(event.reviewYear());
        }
    }

    private ReviewSaved save(Account account, long eventId, int rating, String content, List<Prepared> files,
                             boolean withVideo) {
        long authorId = account.id();
        // The event may have been deleted, or moved to a later date, while the files were read
        EventCard event = startedEvent(eventId);

        // 2. Links (BR-22)
        filter.checkLinks(content, authorId);

        // 3. New-account daily limit (BR-07, MSG44); the usage row stays locked until the review is saved
        Instant now = clock.instant();
        LocalDate today = LocalDate.ofInstant(now, EventService.JAPAN);
        checkDailyLimit(account, today, true, withVideo, now);

        // 4. Banned keywords (MSG36)
        filter.checkKeywords(content);

        // Save
        boolean withMedia = !files.isEmpty();
        String status = withMedia ? "PROCESSING" : "PUBLISHED";
        long reviewId = repository.insertReview(authorId, eventId, event.reviewYear(), rating, content, status, now);
        int order = 0;
        for (Prepared file : files) {
            repository.linkMedia(reviewId, media.save(authorId, "review", file), order++);
        }
        accounts.addUsage(authorId, today, 1, withVideo ? 1 : 0);
        afterSave(authorId, reviewId, status);
        return new ReviewSaved(reviewId, eventId, status);
    }

    // ---------------------------------------------------------------- edit

    /**
     * Saves an edited review with the checks of {@link #create}; an edit is not a new review, so only a video added to
     * a review that had none counts toward the new-account limit. New files send it back to PROCESSING; without new
     * files a published review stays published and a rejected or waiting one whose files all passed is published.
     * Its points are paid the first time it is published, never again. Likes are kept; it shows "· đã sửa".
     */
    public ReviewSaved update(AppUserPrincipal me, long reviewId, ReviewUpdateRequest request,
                              List<MultipartFile> images, MultipartFile video) {
        Account account = member(me);
        ReviewState review = editable(account, reviewId, false);
        Integer rating = request == null ? null : request.rating();
        String content = request == null ? null : EventRules.strip(request.content());
        List<Media> attached = repository.attachedMedia(reviewId);
        List<Media> kept = kept(attached, request == null ? null : request.keepMediaIds());
        List<MultipartFile> imageFiles = nonEmpty(images);
        MultipartFile videoFile = video == null || video.isEmpty() ? null : video;
        long keptImages = kept.stream().filter(m -> "IMAGE".equals(m.type())).count();
        long keptVideos = kept.size() - keptImages;
        List<Prepared> files = checkFields(rating, content, imageFiles, videoFile,
                (int) keptImages + imageFiles.size(), (int) keptVideos + (videoFile == null ? 0 : 1));
        boolean hadVideo = attached.stream().anyMatch(m -> "VIDEO".equals(m.type()));
        boolean countVideo = videoFile != null && !hadVideo;
        return tx.execute(s -> saveEdit(account, review.id(), rating, content, kept, files, countVideo));
    }

    private ReviewSaved saveEdit(Account account, long reviewId, int rating, String content, List<Media> keep,
                                 List<Prepared> files, boolean countVideo) {
        long authorId = account.id();
        ReviewState review = editable(account, reviewId, true);

        filter.checkLinks(content, authorId);
        Instant now = clock.instant();
        LocalDate today = LocalDate.ofInstant(now, EventService.JAPAN);
        if (countVideo) {
            checkDailyLimit(account, today, false, true, now);
        }
        filter.checkKeywords(content);

        // Files the author removed (or the scan deleted meanwhile) go; the kept ones stay in the chosen order
        List<Media> attached = repository.attachedMedia(reviewId);
        Set<Long> keepIds = new LinkedHashSet<>(keep.stream().map(Media::id).toList());
        List<Long> removed = attached.stream().map(Media::id).filter(id -> !keepIds.contains(id)).toList();
        repository.unlinkMedia(reviewId, removed);
        media.discard(removed);
        List<Media> remaining = keep.stream().filter(k -> attached.stream().anyMatch(a -> a.id().equals(k.id())))
                .toList();
        int order = 0;
        for (Media m : remaining) {
            repository.setMediaOrder(reviewId, m.id(), order++);
        }
        for (Prepared file : files) {
            repository.linkMedia(reviewId, media.save(authorId, "review", file), order++);
        }
        boolean waiting = !files.isEmpty() || !"PUBLISHED".equals(review.status())
                && remaining.stream().anyMatch(m -> !"PASSED".equals(m.scanStatus()));
        String status = waiting ? "PROCESSING" : "PUBLISHED";
        repository.updateReview(reviewId, rating, content, status, now);
        if (countVideo) {
            accounts.addUsage(authorId, today, 0, 1);
        }
        afterSave(authorId, reviewId, status);
        return new ReviewSaved(reviewId, review.eventId(), status);
    }

    /** A published review earns its points now; one with files is scanned once the transaction has committed. */
    private void afterSave(long authorId, long reviewId, String status) {
        if ("PROCESSING".equals(status)) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    publisher.scanSoon(reviewId);
                }
            });
        } else {
            publisher.awardOnce(authorId, reviewId);
        }
    }

    // ---------------------------------------------------------------- delete, like, report

    /**
     * The author deletes their own review for good (any state but REMOVED, which stays for appeals). Its files are
     * discarded, the points it earned are taken back and open report cases are closed.
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void delete(AppUserPrincipal me, long reviewId) {
        Account account = member(me);
        ReviewState review = repository.review(reviewId, true)
                .filter(r -> !"REMOVED".equals(r.status()))
                .orElseThrow(EventReviewService::reviewNotFound);
        if (!review.authorId().equals(account.id())) {
            throw new ApiException(HttpStatus.FORBIDDEN, NO_PERMISSION);
        }
        points.withdrawAll(account.id(), "REVIEW", reviewId, "CONTENT_DELETED");
        List<Long> files = repository.allMediaIds(reviewId);
        repository.deleteReview(reviewId);
        media.discard(files);
        reports.closeCasesOf("REVIEW", List.of(reviewId));
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public LikeState like(AppUserPrincipal me, long reviewId, boolean on) {
        Account account = member(me);
        ReviewState review = visibleReview(reviewId);
        if (review.authorId().equals(account.id())) {
            throw ApiException.badRequest("Bạn không thể thích review của chính mình.");
        }
        if (on) {
            repository.addLike(reviewId, account.id(), clock.instant());
        } else {
            repository.removeLike(reviewId, account.id());
        }
        return new LikeState(on, repository.likeCount(reviewId));
    }

    /**
     * Report Content (UC-16) for a review. More than {@code report_hide_threshold} reports hide it (HIDDEN) until a
     * Moderator decides.
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void report(AppUserPrincipal me, long reviewId, ReportRequest body) {
        Account account = member(me);
        ReviewState review = visibleReview(reviewId);
        Outcome outcome = reports.report(new Target("REVIEW", reviewId, review.authorId()), account.id(),
                body == null ? null : body.reasonId(), body == null ? null : body.note());
        if (outcome.hide()) {
            repository.setStatus(reviewId, "PUBLISHED", "HIDDEN");
        }
    }

    // ---------------------------------------------------------------- checks

    /**
     * Step 1: stars, text, number of files, then the files themselves. Throws with one message per field; the first
     * one is the summary.
     */
    private List<Prepared> checkFields(Integer rating, String content, List<MultipartFile> imageFiles,
                                       MultipartFile videoFile, int imageCount, int videoCount) {
        Map<String, String> errors = new LinkedHashMap<>(EventRules.checkReview(rating, content, imageCount, videoCount));
        List<Prepared> files = new ArrayList<>();
        if (!errors.containsKey("images")) {
            try {
                for (MultipartFile image : imageFiles) {
                    files.add(media.prepareImage(image));
                }
            } catch (InvalidMediaException e) {
                errors.put("images", e.getMessage());
            }
        }
        if (videoFile != null && !errors.containsKey("video")) {
            try {
                files.add(media.prepareVideo(videoFile));
            } catch (InvalidMediaException e) {
                errors.put("video", e.getMessage());
            }
        }
        if (!errors.isEmpty()) {
            throw ApiException.invalid(errors.values().iterator().next(), errors);
        }
        return files;
    }

    /** The event exists and has started (a review is about having gone). */
    private EventCard startedEvent(long eventId) {
        EventRow row = repository.event(eventId).orElseThrow(EventService::notFound);
        EventCard event = events.card(row, null, events.today());
        if (!event.canReview()) {
            throw ApiException.badRequest("Viết review được khi sự kiện bắt đầu.");
        }
        return event;
    }

    /** The author's own review, still open to edits, of an event that was not deleted. */
    private ReviewState editable(Account account, long reviewId, boolean lock) {
        ReviewState review = repository.review(reviewId, lock)
                .filter(r -> EDITABLE.contains(r.status()))
                .filter(r -> repository.event(r.eventId()).isPresent())
                .orElseThrow(EventReviewService::reviewNotFound);
        if (!review.authorId().equals(account.id())) {
            throw new ApiException(HttpStatus.FORBIDDEN, NO_PERMISSION);
        }
        return review;
    }

    /** A review others can act on: published, author active, event not deleted. Locked until the transaction ends. */
    private ReviewState visibleReview(long reviewId) {
        return repository.review(reviewId, true)
                .filter(r -> "PUBLISHED".equals(r.status()))
                .filter(r -> repository.authorActive(reviewId))
                .filter(r -> repository.event(r.eventId()).isPresent())
                .orElseThrow(EventReviewService::reviewNotFound);
    }

    /**
     * @param newReview a new review counts toward the posts limit; an edit does not
     * @param withVideo the review adds a video that counts toward the videos limit
     */
    private void checkDailyLimit(Account account, LocalDate today, boolean newReview, boolean withVideo, Instant now) {
        Usage usage = accounts.lockUsage(account.id(), today);
        int newDays = settings.getInt(SystemSettings.NEW_ACCOUNT_DAYS, 7);
        if (!isNewAccount(account, newDays, now)) {
            return;
        }
        int postsLimit = settings.getInt(SystemSettings.POSTS_PER_DAY, 3);
        if (newReview && usage.posts() >= postsLimit) {
            throw ApiException.tooManyRequests("Tài khoản mới (dưới " + newDays + " ngày) chỉ được đăng " + postsLimit
                    + " bài viết hoặc review mỗi ngày. Hôm nay bạn đã dùng hết " + usage.posts() + "/" + postsLimit
                    + ", hãy quay lại vào ngày mai.");
        }
        int videosLimit = settings.getInt(SystemSettings.VIDEOS_PER_DAY, 1);
        if (withVideo && usage.videos() >= videosLimit) {
            throw ApiException.tooManyRequests("Tài khoản mới chỉ được đăng " + videosLimit
                    + " video mỗi ngày. Hãy bỏ video hoặc thử lại vào ngày mai.");
        }
    }

    private static boolean isNewAccount(Account account, int newDays, Instant now) {
        return account.createdAt().isAfter(now.minus(Duration.ofDays(newDays)));
    }

    private Quota quota(Account account) {
        Instant now = clock.instant();
        int newDays = settings.getInt(SystemSettings.NEW_ACCOUNT_DAYS, 7);
        Usage usage = accounts.usage(account.id(), LocalDate.ofInstant(now, EventService.JAPAN));
        return new Quota(isNewAccount(account, newDays, now), newDays,
                usage.posts(), settings.getInt(SystemSettings.POSTS_PER_DAY, 3),
                usage.pins(), settings.getInt(SystemSettings.PINS_PER_DAY, 2),
                usage.videos(), settings.getInt(SystemSettings.VIDEOS_PER_DAY, 1));
    }

    private Limits limits() {
        return new Limits(EventRules.CONTENT_MAX, EventRules.IMAGES_MAX, MediaService.IMAGE_MAX_MB,
                EventRules.VIDEOS_MAX, MediaService.VIDEO_MAX_SECONDS, media.videoMaxMb());
    }

    /** Only signed-in Users (Members) write, like and report reviews. Guests get 401; staff and locked accounts MSG18. */
    private Account member(AppUserPrincipal me) {
        if (me == null) {
            throw ApiException.unauthorized("Bạn cần đăng nhập để viết review.");
        }
        Account account = accounts.account(me.id())
                .orElseThrow(() -> ApiException.unauthorized("Bạn chưa đăng nhập."));
        if (!"USER".equals(account.role()) || !"ACTIVE".equals(account.status())) {
            throw new ApiException(HttpStatus.FORBIDDEN, NO_PERMISSION);
        }
        return account;
    }

    private static List<MultipartFile> nonEmpty(List<MultipartFile> files) {
        return files == null ? List.of() : files.stream().filter(f -> f != null && !f.isEmpty()).toList();
    }

    /** The attached media listed in {@code keepIds}, in that order; unknown ids are ignored. */
    private static List<Media> kept(List<Media> attached, List<Long> keepIds) {
        if (keepIds == null) {
            return List.of();
        }
        List<Media> kept = new ArrayList<>();
        for (Long id : new LinkedHashSet<>(keepIds)) {
            attached.stream().filter(m -> m.id().equals(id)).findFirst().ifPresent(kept::add);
        }
        return kept;
    }

    private static ApiException alreadyReviewed(int year) {
        return ApiException.conflict("Bạn đã viết review cho lần tổ chức năm " + year + ". Hãy sửa review đó.");
    }

    static ApiException reviewNotFound() {
        return new ApiException(HttpStatus.NOT_FOUND, REVIEW_NOT_FOUND);
    }
}
