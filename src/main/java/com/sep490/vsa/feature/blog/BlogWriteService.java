package com.sep490.vsa.feature.blog;

import com.sep490.vsa.common.exception.ApiException;
import com.sep490.vsa.common.security.AppUserPrincipal;
import com.sep490.vsa.common.settings.SystemSettings;
import com.sep490.vsa.feature.blog.BlogWriteDtos.ComposeOptions;
import com.sep490.vsa.feature.blog.BlogWriteDtos.CreateRequest;
import com.sep490.vsa.feature.blog.BlogWriteDtos.Created;
import com.sep490.vsa.feature.blog.BlogWriteDtos.Limits;
import com.sep490.vsa.feature.blog.BlogWriteDtos.Quota;
import com.sep490.vsa.feature.blog.BlogWriteDtos.ScamWarning;
import com.sep490.vsa.feature.blog.BlogWriteRepository.Account;
import com.sep490.vsa.feature.blog.BlogWriteRepository.Usage;
import com.sep490.vsa.feature.blog.BlogWriteRules.Draft;
import com.sep490.vsa.feature.media.InvalidMediaException;
import com.sep490.vsa.feature.media.MediaService;
import com.sep490.vsa.feature.media.MediaService.Prepared;
import com.sep490.vsa.feature.moderation.ContentFilter;
import com.sep490.vsa.feature.place.PlaceService;
import com.sep490.vsa.feature.place.PlaceService.PlacePicks;
import com.sep490.vsa.feature.user.PointService;
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
 * UC-11 Create Blog Post ("Viết bài", kind POST) and "Đặt câu hỏi" in Hỏi-Đáp (kind QA, called by feature/qa):
 * questions are stored in the same table with the same rules.
 * <p>
 * When the author presses "Đăng", the checks run in this order and stop at the first failing step:
 * <ol>
 *   <li>Required fields and lengths, plus the photo/video files themselves (MSG02, MSG08, MSG37).</li>
 *   <li>Links (BR-22): only accounts with an identity tag may post links; nobody may use short links.</li>
 *   <li>New-account limit (BR-07): under 7 days old, at most 3 posts or reviews and 1 video a day (MSG44).</li>
 *   <li>Banned keywords (MSG36); the page keeps what was typed.</li>
 * </ol>
 * Places come from ASVSJ ({@code placeIds}) or straight from OpenStreetMap ({@code externalPlaceIds}): step 1 also
 * checks that the map places exist (Nominatim lookup, before the transaction) and the save adds them to ASVSJ
 * ({@link PlaceService}), so a post that fails a later check adds no place.
 * <p>
 * Then the post is saved. Without media it is PUBLISHED at once and earns its points (MSG34). With media it is
 * PROCESSING (only the author sees it) until {@link BlogPublishService} has scanned the files.
 * {@code user_daily_usage} counts every new post (editing does not count).
 */
@Service
public class BlogWriteService {

    static final String NO_PERMISSION = "Bạn không có quyền thực hiện thao tác này.";         // MSG18
    /** Days are counted in Japan time, where the users live. */
    static final ZoneId JAPAN = ZoneId.of("Asia/Tokyo");

    private final BlogWriteRepository repository;
    private final ContentFilter filter;
    private final MediaService media;
    private final PointService points;
    private final SystemSettings settings;
    private final BlogPublishService publisher;
    private final PlaceService places;
    private final TransactionTemplate tx;
    private final Clock clock;

    public BlogWriteService(BlogWriteRepository repository, ContentFilter filter, MediaService media,
                            PointService points, SystemSettings settings, BlogPublishService publisher,
                            PlaceService places, PlatformTransactionManager transactionManager, Clock clock) {
        this.repository = repository;
        this.filter = filter;
        this.media = media;
        this.points = points;
        this.settings = settings;
        this.publisher = publisher;
        this.places = places;
        this.tx = new TransactionTemplate(transactionManager);
        this.clock = clock;
    }

    // ---------------------------------------------------------------- screen data

    /** Topics, limits, today's new-account usage and whether the author may post links. */
    @Transactional(readOnly = true)
    public ComposeOptions compose(AppUserPrincipal me) {
        Account account = member(me);
        return new ComposeOptions(repository.activeTopics(), limits(), quota(account),
                filter.hasIdentityTag(account.id()));
    }

    @Transactional(readOnly = true)
    public List<ScamWarning> scamWarnings(Long placeId) {
        return repository.scamWarnings(placeId);
    }

    // ---------------------------------------------------------------- create

    /**
     * Step 1 runs before the database transaction (decoding photos can take a moment and must not hold a
     * connection); steps 2 to 5 run in one transaction so the daily-limit lock covers the save.
     */
    public Created create(AppUserPrincipal me, String kind, CreateRequest request, List<MultipartFile> images,
                          MultipartFile video) {
        Account account = member(me);
        Draft d = BlogWriteRules.clean(request, kind);
        List<MultipartFile> imageFiles = images == null ? List.of()
                : images.stream().filter(f -> f != null && !f.isEmpty()).toList();
        MultipartFile videoFile = video == null || video.isEmpty() ? null : video;

        // 1. Required fields and lengths (and the files: type, size, video length)
        Map<String, String> errors = new LinkedHashMap<>(
                BlogWriteRules.check(d, imageFiles.size(), videoFile == null ? 0 : 1));
        if (!errors.containsKey("topicIds") && !d.topicIds().isEmpty()
                && repository.countActiveTopics(d.topicIds()) != d.topicIds().size()) {
            errors.put("topicIds", "Có chủ đề không còn trong danh sách. Hãy chọn lại chủ đề.");
        }
        if (!errors.containsKey("placeIds") && !d.placeIds().isEmpty()
                && repository.countActivePlaces(d.placeIds()) != d.placeIds().size()) {
            errors.put("placeIds", "Có địa điểm không còn trên bản đồ. Hãy chọn lại địa điểm.");
        }
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
        // OpenStreetMap places that ASVSJ does not have yet: ask the map now, outside the transaction
        PlacePicks picks = errors.isEmpty() ? places.resolve(account.id(), d.externalPlaceIds()) : PlacePicks.NONE;
        if (picks.error() != null) {
            errors.put("placeIds", picks.error());
        }
        if (!errors.isEmpty()) {
            throw ApiException.invalid(BlogWriteRules.summary(d, errors), errors);
        }
        boolean withVideo = videoFile != null;
        return tx.execute(status -> save(account, d, files, withVideo, picks));
    }

    /** Steps 2 to 5, inside one transaction. */
    private Created save(Account account, Draft d, List<Prepared> files, boolean withVideo, PlacePicks picks) {
        long authorId = account.id();

        // 2. Links (BR-22)
        filter.checkLinks(d.title(), authorId);
        filter.checkLinks(d.content(), authorId);

        // 3. New-account daily limit (BR-07, MSG44). The usage row stays locked until the post is saved.
        Instant now = clock.instant();
        LocalDate today = LocalDate.ofInstant(now, JAPAN);
        checkDailyLimit(account, today, withVideo, now);

        // 4. Banned keywords (MSG36), title and content checked separately
        filter.checkKeywords(d.title());
        filter.checkKeywords(d.content());

        // 5. Save. New map places are added first; then every place must still be on the map (a Moderator may
        // have removed one since step 1)
        Set<Long> placeSet = new LinkedHashSet<>(d.placeIds());
        placeSet.addAll(places.save(picks, authorId));
        List<Long> placeIds = List.copyOf(placeSet);
        if (!placeIds.isEmpty() && repository.countActivePlaces(placeIds) != placeIds.size()) {
            String gone = "Có địa điểm không còn trên bản đồ. Hãy chọn lại địa điểm.";
            throw ApiException.invalid(gone, Map.of("placeIds", gone));
        }
        boolean withMedia = !files.isEmpty();
        String status = withMedia ? "PROCESSING" : "PUBLISHED";
        long blogId = repository.insertBlog(authorId, d.type(), d.title(), d.content(), status, now);
        repository.linkTopics(blogId, d.topicIds());
        repository.linkPlaces(blogId, placeIds);
        int order = 0;
        for (Prepared file : files) {
            repository.linkMedia(blogId, media.save(authorId, "blog", file), order++);
        }
        repository.addUsage(authorId, today, 1, withVideo ? 1 : 0);
        if (withMedia) {
            // Scan only after the post and its files are really saved
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    publisher.scanSoon(blogId);
                }
            });
        } else {
            points.add(authorId, PointService.BLOG_POST, "BLOG_CREATED", "BLOG", blogId);
        }
        return new Created(blogId, d.type(), status);
    }

    // ---------------------------------------------------------------- checks

    private void checkDailyLimit(Account account, LocalDate today, boolean withVideo, Instant now) {
        Usage usage = repository.lockUsage(account.id(), today);
        int newDays = settings.getInt(SystemSettings.NEW_ACCOUNT_DAYS, 7);
        if (!isNewAccount(account, newDays, now)) {
            return;
        }
        int postsLimit = settings.getInt(SystemSettings.POSTS_PER_DAY, 3);
        if (usage.posts() >= postsLimit) {
            throw ApiException.tooManyRequests("Bạn đã đạt giới hạn đăng bài trong ngày của tài khoản mới (dưới "
                    + newDays + " ngày: tối đa " + postsLimit + " bài viết hoặc review mỗi ngày). "
                    + "Vui lòng thử lại vào ngày mai.");
        }
        int videosLimit = settings.getInt(SystemSettings.VIDEOS_PER_DAY, 1);
        if (withVideo && usage.videos() >= videosLimit) {
            throw ApiException.tooManyRequests("Bạn đã đạt giới hạn video trong ngày của tài khoản mới (tối đa "
                    + videosLimit + " video mỗi ngày). Hãy bỏ video hoặc thử lại vào ngày mai.");
        }
    }

    static boolean isNewAccount(Account account, int newDays, Instant now) {
        return account.createdAt().isAfter(now.minus(Duration.ofDays(newDays)));
    }

    private Quota quota(Account account) {
        Instant now = clock.instant();
        int newDays = settings.getInt(SystemSettings.NEW_ACCOUNT_DAYS, 7);
        Usage usage = repository.usage(account.id(), LocalDate.ofInstant(now, JAPAN));
        int age = (int) Math.max(0, Duration.between(account.createdAt(), now).toDays());
        return new Quota(isNewAccount(account, newDays, now), newDays, age,
                usage.posts(), settings.getInt(SystemSettings.POSTS_PER_DAY, 3),
                usage.pins(), settings.getInt(SystemSettings.PINS_PER_DAY, 2),
                usage.videos(), settings.getInt(SystemSettings.VIDEOS_PER_DAY, 1));
    }

    private Limits limits() {
        return new Limits(BlogWriteRules.TITLE_MAX, BlogWriteRules.CONTENT_MAX, BlogWriteRules.TOPICS_MAX,
                BlogWriteRules.PLACES_MAX, BlogWriteRules.IMAGES_MAX, MediaService.IMAGE_MAX_MB,
                BlogWriteRules.VIDEOS_MAX, MediaService.VIDEO_MAX_SECONDS, media.videoMaxMb(),
                BlogWriteRules.QUESTION_CONTENT_REQUIRED);
    }

    /** Only signed-in Users (Members) write posts. Guests get 401; staff roles and locked accounts get MSG18. */
    private Account member(AppUserPrincipal me) {
        if (me == null) {
            throw ApiException.unauthorized("Bạn cần đăng nhập để viết bài.");
        }
        Account account = repository.account(me.id())
                .orElseThrow(() -> ApiException.unauthorized("Bạn chưa đăng nhập."));
        if (!"USER".equals(account.role()) || !"ACTIVE".equals(account.status())) {
            throw new ApiException(HttpStatus.FORBIDDEN, NO_PERMISSION);
        }
        return account;
    }
}
