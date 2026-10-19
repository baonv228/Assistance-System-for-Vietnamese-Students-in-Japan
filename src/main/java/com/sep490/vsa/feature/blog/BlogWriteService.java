package com.sep490.vsa.feature.blog;

import com.sep490.vsa.common.exception.ApiException;
import com.sep490.vsa.common.security.AppUserPrincipal;
import com.sep490.vsa.common.settings.SystemSettings;
import com.sep490.vsa.feature.blog.BlogWriteDtos.ComposeOptions;
import com.sep490.vsa.feature.blog.BlogWriteDtos.CreateRequest;
import com.sep490.vsa.feature.blog.BlogWriteDtos.Created;
import com.sep490.vsa.feature.blog.BlogWriteDtos.EditData;
import com.sep490.vsa.feature.blog.BlogWriteDtos.EditMedia;
import com.sep490.vsa.feature.blog.BlogWriteDtos.Limits;
import com.sep490.vsa.feature.blog.BlogWriteDtos.Quota;
import com.sep490.vsa.feature.blog.BlogWriteDtos.ScamWarning;
import com.sep490.vsa.feature.blog.BlogWriteDtos.UpdateRequest;
import com.sep490.vsa.feature.blog.BlogWriteRepository.Account;
import com.sep490.vsa.feature.blog.BlogWriteRepository.AttachedMedia;
import com.sep490.vsa.feature.blog.BlogWriteRepository.EditableBlog;
import com.sep490.vsa.feature.blog.BlogWriteRepository.Usage;
import com.sep490.vsa.feature.blog.BlogWriteRules.Draft;
import com.sep490.vsa.feature.media.InvalidMediaException;
import com.sep490.vsa.feature.media.MediaService;
import com.sep490.vsa.feature.media.MediaService.Prepared;
import com.sep490.vsa.feature.moderation.ContentFilter;
import com.sep490.vsa.feature.place.PlaceService;
import com.sep490.vsa.feature.place.PlaceService.PlacePicks;
import org.springframework.beans.factory.annotation.Value;
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
 * <p>
 * "Cập nhật bài viết" / "Cập nhật câu hỏi" ({@link #update}) runs the same checks on an existing post of the author.
 */
@Service
public class BlogWriteService {

    static final String NO_PERMISSION = "Bạn không có quyền thực hiện thao tác này.";         // MSG18
    /** Days are counted in Japan time, where the users live. */
    static final ZoneId JAPAN = ZoneId.of("Asia/Tokyo");

    /** A post can be edited while it is published, waiting for its scan or rejected by it. */
    static final Set<String> EDITABLE = Set.of("PUBLISHED", "PROCESSING", "REJECTED");

    private final BlogWriteRepository repository;
    private final BlogFeedRepository feedRepository;
    private final ContentFilter filter;
    private final MediaService media;
    private final SystemSettings settings;
    private final BlogPublishService publisher;
    private final PlaceService places;
    private final TransactionTemplate tx;
    private final Clock clock;
    private final String mediaBaseUrl;

    public BlogWriteService(BlogWriteRepository repository, BlogFeedRepository feedRepository, ContentFilter filter,
                            MediaService media, SystemSettings settings, BlogPublishService publisher,
                            PlaceService places, PlatformTransactionManager transactionManager, Clock clock,
                            @Value("${app.media.public-base-url:/media/}") String mediaBaseUrl) {
        this.repository = repository;
        this.feedRepository = feedRepository;
        this.filter = filter;
        this.media = media;
        this.settings = settings;
        this.publisher = publisher;
        this.places = places;
        this.tx = new TransactionTemplate(transactionManager);
        this.clock = clock;
        this.mediaBaseUrl = mediaBaseUrl;
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
        List<MultipartFile> imageFiles = nonEmpty(images);
        MultipartFile videoFile = video == null || video.isEmpty() ? null : video;
        Checked checked = checkFields(account, d, imageFiles, videoFile, imageFiles.size(), videoFile == null ? 0 : 1);
        boolean withVideo = videoFile != null;
        return tx.execute(status -> save(account, d, checked.files(), withVideo, checked.picks()));
    }

    /** What step 1 prepared for the save: the decoded files and the map places to add. */
    private record Checked(List<Prepared> files, PlacePicks picks) {
    }

    /**
     * Step 1: required fields and lengths, topics and places still active, the files themselves (type, size, video
     * length) and the OpenStreetMap places, asked outside the transaction. Throws with one message per field.
     *
     * @param imageCount photos the post will have (on an edit: the kept ones plus the new ones)
     * @param videoCount videos the post will have
     */
    private Checked checkFields(Account account, Draft d, List<MultipartFile> imageFiles, MultipartFile videoFile,
                                int imageCount, int videoCount) {
        Map<String, String> errors = new LinkedHashMap<>(BlogWriteRules.check(d, imageCount, videoCount));
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
        return new Checked(files, picks);
    }

    private static List<MultipartFile> nonEmpty(List<MultipartFile> files) {
        return files == null ? List.of() : files.stream().filter(f -> f != null && !f.isEmpty()).toList();
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
        checkDailyLimit(account, today, true, withVideo, now);

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
            publisher.awardOnce(authorId, blogId);
        }
        return new Created(blogId, d.type(), status);
    }

    // ---------------------------------------------------------------- edit ("Sửa bài", "Sửa câu hỏi")

    /** The edit form: the post as it is now plus the compose options. Only its author, while it can be edited. */
    @Transactional(readOnly = true)
    public EditData editData(AppUserPrincipal me, String kind, long blogId) {
        Account account = member(me);
        EditableBlog blog = editable(account, kind, blogId, false);
        List<EditMedia> attached = repository.attachedMedia(blogId).stream()
                .map(m -> new EditMedia(m.id(), m.type(), mediaBaseUrl + m.storageKey(), m.scanStatus(), m.sortOrder()))
                .toList();
        return new EditData(blog.id(), blog.type(), blog.status(), blog.title(), blog.content(),
                repository.topicIds(blogId),
                feedRepository.placesOf(List.of(blogId)).getOrDefault(blogId, List.of()), attached,
                new ComposeOptions(repository.activeTopics(), limits(), quota(account),
                        filter.hasIdentityTag(account.id())));
    }

    /**
     * Saves an edited post or question. The checks are the ones of {@link #create}, in the same order; the
     * new-account limit only counts a video that is added to a post that had none (an edit is not a new post).
     * <p>
     * The post shows "· đã sửa". New photos or a new video send it back to PROCESSING until they are scanned; without
     * new files a published post stays published, and a rejected or waiting post whose remaining files have all
     * passed is published now. The post's points are paid the first time it is published, never again. Likes,
     * comments and the best answer are kept.
     */
    public Created update(AppUserPrincipal me, String kind, long blogId, UpdateRequest request,
                          List<MultipartFile> images, MultipartFile video) {
        Account account = member(me);
        editable(account, kind, blogId, false);
        Draft d = BlogWriteRules.clean(request == null ? null : request.fields(), kind);
        List<AttachedMedia> attached = repository.attachedMedia(blogId);
        List<AttachedMedia> kept = kept(attached, request == null ? null : request.keepMediaIds());
        List<MultipartFile> imageFiles = nonEmpty(images);
        MultipartFile videoFile = video == null || video.isEmpty() ? null : video;
        long keptImages = kept.stream().filter(m -> "IMAGE".equals(m.type())).count();
        long keptVideos = kept.size() - keptImages;
        Checked checked = checkFields(account, d, imageFiles, videoFile, (int) keptImages + imageFiles.size(),
                (int) keptVideos + (videoFile == null ? 0 : 1));
        // Only a video added to a post that had none counts toward the new-account limit
        boolean hadVideo = attached.stream().anyMatch(m -> "VIDEO".equals(m.type()));
        boolean countVideo = videoFile != null && !hadVideo;
        return tx.execute(status -> saveEdit(account, kind, blogId, d, kept, checked, countVideo));
    }

    /** Steps 2 to 5 of an edit, inside one transaction (the post row stays locked until the save). */
    private Created saveEdit(Account account, String kind, long blogId, Draft d, List<AttachedMedia> keep,
                             Checked checked, boolean countVideo) {
        long authorId = account.id();
        EditableBlog blog = editable(account, kind, blogId, true);

        // 2. Links (BR-22)
        filter.checkLinks(d.title(), authorId);
        filter.checkLinks(d.content(), authorId);

        // 3. New-account limit: only a newly added video counts (BR-07, MSG44)
        Instant now = clock.instant();
        LocalDate today = LocalDate.ofInstant(now, JAPAN);
        if (countVideo) {
            checkDailyLimit(account, today, false, true, now);
        }

        // 4. Banned keywords (MSG36)
        filter.checkKeywords(d.title());
        filter.checkKeywords(d.content());

        // 5. Save
        Set<Long> placeSet = new LinkedHashSet<>(d.placeIds());
        placeSet.addAll(places.save(checked.picks(), authorId));
        List<Long> placeIds = List.copyOf(placeSet);
        if (!placeIds.isEmpty() && repository.countActivePlaces(placeIds) != placeIds.size()) {
            String gone = "Có địa điểm không còn trên bản đồ. Hãy chọn lại địa điểm.";
            throw ApiException.invalid(gone, Map.of("placeIds", gone));
        }
        // Files the author removed (or that the scan deleted meanwhile) go; the kept ones stay in the chosen order
        List<AttachedMedia> attached = repository.attachedMedia(blogId);
        Set<Long> keepIds = new LinkedHashSet<>(keep.stream().map(AttachedMedia::id).toList());
        List<Long> removed = attached.stream().map(AttachedMedia::id).filter(id -> !keepIds.contains(id)).toList();
        repository.unlinkMedia(blogId, removed);
        media.discard(removed);
        List<AttachedMedia> remaining = keep.stream()
                .filter(k -> attached.stream().anyMatch(a -> a.id().equals(k.id()))).toList();
        int order = 0;
        for (AttachedMedia m : remaining) {
            repository.setMediaOrder(blogId, m.id(), order++);
        }
        for (Prepared file : checked.files()) {
            repository.linkMedia(blogId, media.save(authorId, "blog", file), order++);
        }

        boolean newFiles = !checked.files().isEmpty();
        boolean waiting = newFiles || !"PUBLISHED".equals(blog.status())
                && remaining.stream().anyMatch(m -> !"PASSED".equals(m.scanStatus()));
        String status = waiting ? "PROCESSING" : "PUBLISHED";
        repository.updateBlog(blogId, d.title(), d.content(), status, now);
        repository.unlinkTopics(blogId);
        repository.linkTopics(blogId, d.topicIds());
        repository.unlinkPlaces(blogId);
        repository.linkPlaces(blogId, placeIds);
        if (countVideo) {
            repository.addUsage(authorId, today, 0, 1);
        }
        if ("PROCESSING".equals(status)) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    publisher.scanSoon(blogId);
                }
            });
        } else {
            publisher.awardOnce(authorId, blogId);
        }
        return new Created(blogId, d.type(), status);
    }

    /**
     * The post the author may edit: their own, of the page's kind, PUBLISHED, PROCESSING or REJECTED. A post hidden
     * by reports or removed by a Moderator answers MSG19 like a missing one; someone else's post answers MSG18.
     */
    private EditableBlog editable(Account account, String kind, long blogId, boolean lock) {
        EditableBlog blog = repository.editableBlog(blogId, lock)
                .filter(b -> b.type().equals(kind) && EDITABLE.contains(b.status()))
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, BlogDetailService.NOT_FOUND));
        if (!blog.authorId().equals(account.id())) {
            throw new ApiException(HttpStatus.FORBIDDEN, NO_PERMISSION);
        }
        return blog;
    }

    /** The attached media listed in {@code keepIds}, in that order; unknown ids are ignored. */
    private static List<AttachedMedia> kept(List<AttachedMedia> attached, List<Long> keepIds) {
        if (keepIds == null) {
            return List.of();
        }
        List<AttachedMedia> kept = new ArrayList<>();
        for (Long id : new LinkedHashSet<>(keepIds)) {
            attached.stream().filter(m -> m.id().equals(id)).findFirst().ifPresent(kept::add);
        }
        return kept;
    }

    // ---------------------------------------------------------------- checks

    /**
     * @param newPost   a new post counts toward the posts limit; an edit does not
     * @param withVideo the post adds a video that counts toward the videos limit
     */
    private void checkDailyLimit(Account account, LocalDate today, boolean newPost, boolean withVideo, Instant now) {
        Usage usage = repository.lockUsage(account.id(), today);
        int newDays = settings.getInt(SystemSettings.NEW_ACCOUNT_DAYS, 7);
        if (!isNewAccount(account, newDays, now)) {
            return;
        }
        int postsLimit = settings.getInt(SystemSettings.POSTS_PER_DAY, 3);
        if (newPost && usage.posts() >= postsLimit) {
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
