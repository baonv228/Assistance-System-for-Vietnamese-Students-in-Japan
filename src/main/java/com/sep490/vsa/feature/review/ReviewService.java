package com.sep490.vsa.feature.review;

import com.sep490.vsa.common.exception.ApiException;
import com.sep490.vsa.common.security.AppUserPrincipal;
import com.sep490.vsa.common.settings.SystemSettings;
import com.sep490.vsa.feature.media.InvalidMediaException;
import com.sep490.vsa.feature.media.MediaService;
import com.sep490.vsa.feature.media.MediaService.Prepared;
import com.sep490.vsa.feature.moderation.ContentFilter;
import com.sep490.vsa.feature.place.PlaceService;
import com.sep490.vsa.feature.place.PlaceService.PlacePicks;
import com.sep490.vsa.feature.report.ContentReportService;
import com.sep490.vsa.feature.review.ReviewDtos.Compose;
import com.sep490.vsa.feature.review.ReviewDtos.CreateRequest;
import com.sep490.vsa.feature.review.ReviewDtos.EditData;
import com.sep490.vsa.feature.review.ReviewDtos.EditMedia;
import com.sep490.vsa.feature.review.ReviewDtos.Limits;
import com.sep490.vsa.feature.review.ReviewDtos.Quota;
import com.sep490.vsa.feature.review.ReviewDtos.Saved;
import com.sep490.vsa.feature.review.ReviewDtos.UpdateRequest;
import com.sep490.vsa.feature.review.ReviewRepository.Account;
import com.sep490.vsa.feature.review.ReviewRepository.AttachedMedia;
import com.sep490.vsa.feature.review.ReviewRepository.PlaceRef;
import com.sep490.vsa.feature.review.ReviewRepository.ReviewRow;
import com.sep490.vsa.feature.review.ReviewRepository.Usage;
import com.sep490.vsa.feature.user.PointService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Write, edit and delete a place review (UC-U16, UC-U17, UC-U18).
 * <ul>
 *   <li>One review per user per place (also counted when the place is an OpenStreetMap place added by this review).
 *       Stars 1–5, text 10–2,000 characters, at most 5 photos (JPG/PNG/HEIC ≤ 20 MB) and 1 video (≤ 30 s).</li>
 *   <li>Checks in the order of Write Post: fields and files, links (identity tag needed), new-account daily limit
 *       (posts and reviews share it; edits do not count, only a newly added video), banned keywords.</li>
 *   <li>Without media the review is published at once (+10 points); with media it waits for the scan
 *       ({@link ReviewPublishService}), and an edit with new media hides it again until the scan passes.</li>
 *   <li>Deleting one's own review deletes it for good, takes back its points and closes its open reports.</li>
 * </ul>
 */
@Service
public class ReviewService {

    static final int CONTENT_MIN = 10;
    static final int CONTENT_MAX = 2000;
    static final int IMAGES_MAX = 5;
    static final int VIDEOS_MAX = 1;
    static final String NOT_FOUND = "Review này không còn tồn tại.";
    static final String NO_PERMISSION = "Bạn không có quyền thực hiện thao tác này.";
    static final String PLACE_GONE = "Địa điểm này không còn trên Senpai ơi.";
    static final String ALREADY = "Bạn đã viết review cho địa điểm này rồi. Hãy sửa review của bạn.";
    private static final Set<String> EDITABLE = Set.of("PUBLISHED", "PROCESSING", "REJECTED");
    private static final ZoneId JAPAN = ZoneId.of("Asia/Tokyo");

    private final ReviewRepository repository;
    private final ReviewPublishService publisher;
    private final MediaService media;
    private final ContentFilter filter;
    private final PlaceService places;
    private final PointService points;
    private final ContentReportService reports;
    private final SystemSettings settings;
    private final TransactionTemplate tx;
    private final Clock clock;
    private final String mediaBaseUrl;

    public ReviewService(ReviewRepository repository, ReviewPublishService publisher, MediaService media,
                         ContentFilter filter, PlaceService places, PointService points, ContentReportService reports,
                         SystemSettings settings, PlatformTransactionManager transactionManager, Clock clock,
                         @Value("${app.media.public-base-url:/media/}") String mediaBaseUrl) {
        this.repository = repository;
        this.publisher = publisher;
        this.media = media;
        this.filter = filter;
        this.places = places;
        this.points = points;
        this.reports = reports;
        this.settings = settings;
        this.tx = new TransactionTemplate(transactionManager);
        this.clock = clock;
        this.mediaBaseUrl = mediaBaseUrl;
    }

    // ---------------------------------------------------------------- form data

    /** Limits, today's quota, and the user's review of the place if any (then the page edits it). */
    @Transactional(readOnly = true)
    public Compose compose(AppUserPrincipal me, Long placeId) {
        Account account = member(me);
        Long mine = placeId == null ? null : repository.place(placeId)
                .map(p -> "MERGED".equals(p.status()) && p.mergedIntoId() != null ? p.mergedIntoId() : p.id())
                .flatMap(id -> repository.reviewOf(id, account.id()))
                .orElse(null);
        return new Compose(limits(), quota(account), mine);
    }

    @Transactional(readOnly = true)
    public EditData editData(AppUserPrincipal me, long reviewId) {
        Account account = member(me);
        ReviewRow review = editable(account, reviewId, false);
        PlaceRef place = repository.place(review.placeId()).orElseThrow(() -> notFound(PLACE_GONE));
        List<EditMedia> attached = repository.media(reviewId).stream()
                .map(m -> new EditMedia(m.id(), m.type(), mediaBaseUrl + m.storageKey(), m.scanStatus()))
                .toList();
        return new EditData(review.id(), place.id(), place.name(), review.rating(), review.content(),
                review.status(), attached, limits(), quota(account));
    }

    // ---------------------------------------------------------------- create (UC-U16)

    /** Step 1 (fields, files, OpenStreetMap lookup) runs before the transaction; the rest inside it. */
    public Saved create(AppUserPrincipal me, CreateRequest request, List<MultipartFile> images, MultipartFile video) {
        Account account = member(me);
        CreateRequest r = request == null ? new CreateRequest(null, null, null, null) : request;
        Map<String, String> errors = new LinkedHashMap<>();
        if (r.placeId() == null && (r.externalPlaceId() == null || r.externalPlaceId().isBlank())) {
            errors.put("place", "Hãy chọn địa điểm.");
        }
        String content = clean(r.rating(), r.content(), errors);
        List<MultipartFile> imageFiles = nonEmpty(images);
        MultipartFile videoFile = video == null || video.isEmpty() ? null : video;
        List<Prepared> files = prepare(imageFiles, videoFile, imageFiles.size(), videoFile == null ? 0 : 1, errors);
        PlacePicks picks = PlacePicks.NONE;
        if (errors.isEmpty() && r.placeId() == null) {
            picks = places.resolve(account.id(), List.of(r.externalPlaceId().strip()));
            if (picks.error() != null) {
                errors.put("place", picks.error());
            }
        }
        throwIf(errors);
        PlacePicks resolved = picks;
        boolean withVideo = videoFile != null;
        return tx.execute(s -> saveNew(account, r.placeId(), resolved, r.rating(), content, files, withVideo));
    }

    private Saved saveNew(Account account, Long placeId, PlacePicks picks, int rating, String content,
                          List<Prepared> files, boolean withVideo) {
        long authorId = account.id();
        filter.checkLinks(content, authorId);
        filter.checkQrCodes(MediaService.qrCodesOfPhotos(files), authorId);
        Instant now = clock.instant();
        LocalDate today = LocalDate.ofInstant(now, JAPAN);
        checkDailyLimit(account, today, true, withVideo, now);
        filter.checkKeywords(content);
        filter.checkQrKeywords(MediaService.qrCodesOfPhotos(files));

        long target = placeId != null ? activePlace(placeId) : activePlace(places.save(picks, authorId).get(0));
        if (repository.anyReviewOf(target, authorId)) {
            throw ApiException.conflict(ALREADY);
        }
        boolean withMedia = !files.isEmpty();
        long reviewId;
        try {
            reviewId = repository.insert(authorId, target, rating, content, withMedia ? "PROCESSING" : "PUBLISHED", now);
        } catch (DuplicateKeyException e) {
            throw ApiException.conflict(ALREADY);
        }
        int order = 0;
        for (Prepared file : files) {
            repository.linkMedia(reviewId, media.save(authorId, "review", file), order++);
        }
        repository.addUsage(authorId, today, 1, withVideo ? 1 : 0);
        afterSave(authorId, reviewId, withMedia);
        return new Saved(reviewId, target, withMedia ? "PROCESSING" : "PUBLISHED");
    }

    // ---------------------------------------------------------------- edit (UC-U17)

    public Saved update(AppUserPrincipal me, long reviewId, UpdateRequest request, List<MultipartFile> images,
                        MultipartFile video) {
        Account account = member(me);
        editable(account, reviewId, false);
        UpdateRequest r = request == null ? new UpdateRequest(null, null, null) : request;
        Map<String, String> errors = new LinkedHashMap<>();
        String content = clean(r.rating(), r.content(), errors);
        List<AttachedMedia> attached = repository.media(reviewId);
        List<AttachedMedia> keep = kept(attached, r.keepMediaIds());
        List<MultipartFile> imageFiles = nonEmpty(images);
        MultipartFile videoFile = video == null || video.isEmpty() ? null : video;
        long keptImages = keep.stream().filter(m -> "IMAGE".equals(m.type())).count();
        long keptVideos = keep.size() - keptImages;
        List<Prepared> files = prepare(imageFiles, videoFile, (int) keptImages + imageFiles.size(),
                (int) keptVideos + (videoFile == null ? 0 : 1), errors);
        throwIf(errors);
        boolean countVideo = videoFile != null && attached.stream().noneMatch(m -> "VIDEO".equals(m.type()));
        return tx.execute(s -> saveEdit(account, reviewId, r.rating(), content, keep, files, countVideo));
    }

    private Saved saveEdit(Account account, long reviewId, int rating, String content, List<AttachedMedia> keep,
                           List<Prepared> files, boolean countVideo) {
        long authorId = account.id();
        ReviewRow review = editable(account, reviewId, true);
        filter.checkLinks(content, authorId);
        filter.checkQrCodes(MediaService.qrCodesOfPhotos(files), authorId);
        Instant now = clock.instant();
        LocalDate today = LocalDate.ofInstant(now, JAPAN);
        if (countVideo) {
            checkDailyLimit(account, today, false, true, now);
        }
        filter.checkKeywords(content);
        filter.checkQrKeywords(MediaService.qrCodesOfPhotos(files));
        // The first review of the author's own pin waiting for approval can be edited too
        PlaceRef place = repository.place(review.placeId()).orElseThrow(() -> notFound(PLACE_GONE));
        if (!"PENDING".equals(place.status())) {
            activePlace(review.placeId());
        }

        // Files the author removed go; the kept ones stay in the chosen order, new ones after them
        List<AttachedMedia> attached = repository.media(reviewId);
        Set<Long> keepIds = new LinkedHashSet<>(keep.stream().map(AttachedMedia::id).toList());
        List<Long> removed = attached.stream().map(AttachedMedia::id).filter(id -> !keepIds.contains(id)).toList();
        repository.unlinkMedia(reviewId, removed);
        media.discard(removed);
        List<AttachedMedia> remaining = keep.stream()
                .filter(k -> attached.stream().anyMatch(a -> a.id() == k.id())).toList();
        int order = 0;
        for (AttachedMedia m : remaining) {
            repository.setMediaOrder(reviewId, m.id(), order++);
        }
        for (Prepared file : files) {
            repository.linkMedia(reviewId, media.save(authorId, "review", file), order++);
        }
        boolean waiting = !files.isEmpty() || remaining.stream().anyMatch(m -> !"PASSED".equals(m.scanStatus()));
        String status = waiting ? "PROCESSING" : "PUBLISHED";
        repository.update(reviewId, rating, content, status, now);
        if (countVideo) {
            repository.addUsage(authorId, today, 0, 1);
        }
        afterSave(authorId, reviewId, waiting);
        return new Saved(reviewId, review.placeId(), status);
    }

    // ---------------------------------------------------------------- delete (UC-U18)

    @Transactional
    public void delete(AppUserPrincipal me, long reviewId) {
        Account account = member(me);
        ReviewRow review = repository.review(reviewId, true)
                .filter(rv -> !"REMOVED".equals(rv.status()) && rv.placeId() != null)
                .orElseThrow(() -> notFound(NOT_FOUND));
        if (review.authorId() != account.id()) {
            throw new ApiException(HttpStatus.FORBIDDEN, NO_PERMISSION);
        }
        if (repository.place(review.placeId()).filter(p -> "PENDING".equals(p.status())).isPresent()) {
            throw ApiException.badRequest("Đây là review đầu tiên của ghim đang chờ duyệt, chưa xóa riêng được. "
                    + "Bạn có thể sửa review trong lúc chờ Moderator.");
        }
        reports.closeCasesOf("REVIEW", List.of(reviewId));
        points.withdrawAll(account.id(), "REVIEW", reviewId, "CONTENT_DELETED");
        media.discard(repository.allMediaIds(reviewId));
        repository.delete(reviewId);
    }

    // ---------------------------------------------------------------- checks

    /** Stars and text; returns the stripped text. */
    static String clean(Integer rating, String content, Map<String, String> errors) {
        if (rating == null || rating < 1 || rating > 5) {
            errors.put("rating", "Hãy chọn số sao.");
        }
        String text = content == null ? "" : content.strip();
        if (text.length() < CONTENT_MIN) {
            errors.put("content", "Nội dung cần ít nhất " + CONTENT_MIN + " ký tự.");
        } else if (text.length() > CONTENT_MAX) {
            errors.put("content", "Nội dung tối đa 2.000 ký tự.");
        }
        return text;
    }

    List<Prepared> prepare(List<MultipartFile> imageFiles, MultipartFile videoFile, int imageCount,
                                   int videoCount, Map<String, String> errors) {
        List<Prepared> files = new ArrayList<>();
        if (imageCount > IMAGES_MAX) {
            errors.put("images", "Tối đa " + IMAGES_MAX + " ảnh.");
        } else {
            try {
                for (MultipartFile image : imageFiles) {
                    files.add(media.prepareImage(image));
                }
            } catch (InvalidMediaException e) {
                errors.put("images", e.getMessage());
            }
        }
        if (videoCount > VIDEOS_MAX) {
            errors.put("video", "Tối đa 1 video.");
        } else if (videoFile != null) {
            try {
                files.add(media.prepareVideo(videoFile));
            } catch (InvalidMediaException e) {
                errors.put("video", e.getMessage());
            }
        }
        return files;
    }

    static void throwIf(Map<String, String> errors) {
        if (!errors.isEmpty()) {
            throw ApiException.invalid(errors.values().iterator().next(), errors);
        }
    }

    /** The place id to review (a merged place gives the place it was merged into); it must be on the map. */
    private long activePlace(long placeId) {
        PlaceRef place = repository.place(placeId).orElseThrow(() -> notFound(PLACE_GONE));
        if ("MERGED".equals(place.status()) && place.mergedIntoId() != null) {
            place = repository.place(place.mergedIntoId()).orElseThrow(() -> notFound(PLACE_GONE));
        }
        if (!"ACTIVE".equals(place.status())) {
            throw notFound(PLACE_GONE);
        }
        return place.id();
    }

    /** The review its author may edit: PUBLISHED, PROCESSING or REJECTED (hidden by reports or removed: 404). */
    private ReviewRow editable(Account account, long reviewId, boolean lock) {
        ReviewRow review = repository.review(reviewId, lock)
                .filter(rv -> EDITABLE.contains(rv.status()) && rv.placeId() != null)
                .orElseThrow(() -> notFound(NOT_FOUND));
        if (review.authorId() != account.id()) {
            throw new ApiException(HttpStatus.FORBIDDEN, NO_PERMISSION);
        }
        return review;
    }

    void afterSave(long authorId, long reviewId, boolean waiting) {
        if (waiting) {
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

    private void checkDailyLimit(Account account, LocalDate today, boolean newReview, boolean withVideo, Instant now) {
        Usage usage = repository.lockUsage(account.id(), today);
        int newDays = settings.getInt(SystemSettings.NEW_ACCOUNT_DAYS, 7);
        if (!account.createdAt().isAfter(now.minus(Duration.ofDays(newDays)))) {
            return;
        }
        int postsLimit = settings.getInt(SystemSettings.POSTS_PER_DAY, 3);
        if (newReview && usage.posts() >= postsLimit) {
            throw ApiException.tooManyRequests("Tài khoản mới chỉ được đăng " + postsLimit
                    + " bài viết hoặc review mỗi ngày. Hãy quay lại vào ngày mai.");
        }
        int videosLimit = settings.getInt(SystemSettings.VIDEOS_PER_DAY, 1);
        if (withVideo && usage.videos() >= videosLimit) {
            throw ApiException.tooManyRequests("Tài khoản mới chỉ được đăng " + videosLimit
                    + " video mỗi ngày. Hãy bỏ video hoặc thử lại vào ngày mai.");
        }
    }

    private Quota quota(Account account) {
        Instant now = clock.instant();
        int newDays = settings.getInt(SystemSettings.NEW_ACCOUNT_DAYS, 7);
        Usage usage = repository.usage(account.id(), LocalDate.ofInstant(now, JAPAN));
        return new Quota(account.createdAt().isAfter(now.minus(Duration.ofDays(newDays))), newDays,
                usage.posts(), settings.getInt(SystemSettings.POSTS_PER_DAY, 3),
                usage.videos(), settings.getInt(SystemSettings.VIDEOS_PER_DAY, 1));
    }

    Limits limits() {
        return new Limits(CONTENT_MIN, CONTENT_MAX, IMAGES_MAX, MediaService.IMAGE_MAX_MB, VIDEOS_MAX,
                MediaService.VIDEO_MAX_SECONDS, media.videoMaxMb());
    }

    private static List<AttachedMedia> kept(List<AttachedMedia> attached, List<Long> keepIds) {
        if (keepIds == null) {
            return List.of();
        }
        List<AttachedMedia> kept = new ArrayList<>();
        for (Long id : new LinkedHashSet<>(keepIds)) {
            attached.stream().filter(m -> id != null && m.id() == id).findFirst().ifPresent(kept::add);
        }
        return kept;
    }

    private static List<MultipartFile> nonEmpty(List<MultipartFile> files) {
        return files == null ? List.of() : files.stream().filter(f -> f != null && !f.isEmpty()).toList();
    }

    private static ApiException notFound(String message) {
        return new ApiException(HttpStatus.NOT_FOUND, message);
    }

    /** Only signed-in Users write reviews; staff roles and locked accounts get 403. */
    Account member(AppUserPrincipal me) {
        if (me == null) {
            throw ApiException.unauthorized("Bạn cần đăng nhập để viết review.");
        }
        Account account = repository.account(me.id())
                .orElseThrow(() -> ApiException.unauthorized("Bạn chưa đăng nhập."));
        if (!"USER".equals(account.role()) || !"ACTIVE".equals(account.status())) {
            throw new ApiException(HttpStatus.FORBIDDEN, NO_PERMISSION);
        }
        return account;
    }
}
