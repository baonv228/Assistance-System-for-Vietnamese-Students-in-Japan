package com.sep490.vsa.feature.blog;

import com.sep490.vsa.common.dto.ApiResponse;
import com.sep490.vsa.common.exception.ApiException;
import com.sep490.vsa.common.security.AppUserPrincipal;
import com.sep490.vsa.common.settings.SystemSettings;
import com.sep490.vsa.feature.blog.BlogDtos.PlaceRef;
import com.sep490.vsa.feature.blog.BlogWriteDtos.ComposeOptions;
import com.sep490.vsa.feature.blog.BlogWriteDtos.CreateRequest;
import com.sep490.vsa.feature.blog.BlogWriteDtos.Created;
import com.sep490.vsa.feature.blog.BlogWriteDtos.EditData;
import com.sep490.vsa.feature.blog.BlogWriteDtos.EditMedia;
import com.sep490.vsa.feature.blog.BlogWriteDtos.Limits;
import com.sep490.vsa.feature.blog.BlogWriteDtos.Quota;
import com.sep490.vsa.feature.blog.BlogWriteDtos.TopicOption;
import com.sep490.vsa.feature.blog.BlogWriteDtos.UpdateRequest;
import com.sep490.vsa.feature.blog.BlogWriteRepository.Account;
import com.sep490.vsa.feature.blog.BlogWriteRepository.AttachedMedia;
import com.sep490.vsa.feature.blog.BlogWriteRepository.EditableBlog;
import com.sep490.vsa.feature.blog.BlogWriteRepository.MediaState;
import com.sep490.vsa.feature.blog.BlogWriteRepository.PendingBlog;
import com.sep490.vsa.feature.blog.BlogWriteRepository.Usage;
import com.sep490.vsa.feature.media.InvalidMediaException;
import com.sep490.vsa.feature.media.MediaService;
import com.sep490.vsa.feature.media.MediaService.Prepared;
import com.sep490.vsa.feature.moderation.ContentFilter;
import com.sep490.vsa.feature.moderation.MediaScanService;
import com.sep490.vsa.feature.notification.NotificationService;
import com.sep490.vsa.feature.place.PlaceService;
import com.sep490.vsa.feature.place.PlaceService.PlacePicks;
import com.sep490.vsa.feature.qa.QaController;
import com.sep490.vsa.feature.qa.QaService;
import com.sep490.vsa.feature.user.PointService;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.ClassOrderer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestClassOrder;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.InOrder;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit tests of "Đặt câu hỏi" and "Cập nhật câu hỏi" in Hỏi-Đáp: {@link BlogWriteService} called with kind QA (as
 * {@link QaController} does: questions are {@code blogs} rows with {@code blog_type = 'QA'}), the scan step of
 * {@link BlogPublishService} for a question with photos or a video, and the small adaptations of {@link QaController}
 * (the answer messages). Written from the use cases UC 1.14 Submit Question and UC 1.15 Update Question (SRS Report 3,
 * II.1.11 / II.1.12, which reuse the rules of II.1.5 Create Post) and the later team decisions recorded in CLAUDE.md
 * ("Write Post", "Hỏi-Đáp": the description is optional, questions count with posts in the new-account daily limit,
 * +10 points paid once, notifications about a question use {@code ref_type = 'QUESTION'}).
 * <p>
 * The database is not used: {@link BlogWriteRepository}, {@link BlogFeedRepository}, {@link ContentFilter},
 * {@link MediaService}, {@link SystemSettings}, {@link BlogPublishService}, {@link PlaceService},
 * {@link MediaScanService}, {@link PointService}, {@link NotificationService} and the transaction manager are mocks (the
 * service's transaction template is real and runs on the mocked manager). The clock is fixed at 20/11/2026 12:00 Japan
 * time. Transaction synchronization is switched on by hand, so the "scan after commit" hook can be checked.
 */
@TestClassOrder(ClassOrderer.DisplayName.class)
@DisplayName("BlogWriteService – Hỏi-Đáp: đặt câu hỏi, sửa câu hỏi (UC 1.14, UC 1.15)")
class BlogWriteServiceQuestionTest {

    private static final long MEMBER_ID = 10L;
    private static final long OTHER_ID = 11L;
    private static final long STAFF_ID = 20L;
    private static final long QUESTION_ID = 700L;
    private static final String QA = "QA";
    /** 12:00 in Japan: the Japan date is 20/11/2026. */
    private static final Instant NOW = Instant.parse("2026-11-20T03:00:00Z");
    private static final LocalDate TODAY = LocalDate.of(2026, 11, 20);
    private static final Instant VETERAN_SINCE = NOW.minus(Duration.ofDays(30));
    private static final Instant NEWBIE_SINCE = NOW.minus(Duration.ofDays(2));

    private static final AppUserPrincipal MEMBER = principal(MEMBER_ID, "USER");
    private static final AppUserPrincipal OTHER = principal(OTHER_ID, "USER");

    private static final String TITLE = "Gia hạn visa du học cần giấy tờ gì?";
    private static final String CONTENT = "Mình ở Arakawa, visa còn 2 tháng.";
    private static final String NEW_TITLE = "Gia hạn visa du học ở Arakawa cần giấy tờ gì?";
    private static final String NEW_CONTENT = "Visa của mình còn 1 tháng.";

    private static final long VISA = 3L;
    private static final long PAPERS = 5L;
    private static final long HOUSING = 7L;
    private static final long HEALTH = 9L;
    private static final long OTHER_TOPIC = 1L;
    private static final List<TopicOption> TOPICS = List.of(
            new TopicOption(VISA, "Visa và cư trú", false, false),
            new TopicOption(PAPERS, "Thủ tục giấy tờ", false, false),
            new TopicOption(HOUSING, "Nhà ở", false, false),
            new TopicOption(HEALTH, "Y tế", false, false),
            new TopicOption(12L, "Cảnh báo lừa đảo", false, true),
            new TopicOption(OTHER_TOPIC, "Khác", true, false));

    private static final String MSG18 = "Bạn không có quyền thực hiện thao tác này.";
    private static final String MSG19 =
            "Không tìm thấy nội dung bạn yêu cầu. Bài viết có thể đã bị gỡ hoặc đã bị xóa.";
    private static final String LOGIN_TO_WRITE = "Bạn cần đăng nhập để viết bài.";
    private static final String NOT_SIGNED_IN = "Bạn chưa đăng nhập.";
    private static final String TITLE_REQUIRED = "Hãy nhập câu hỏi.";
    private static final String TITLE_TOO_LONG = "Câu hỏi tối đa 150 ký tự.";
    private static final String TOPIC_REQUIRED = "Hãy chọn ít nhất 1 chủ đề. Không có chủ đề phù hợp thì chọn “Khác”.";
    private static final String TOPICS_TOO_MANY = "Chọn tối đa 3 chủ đề.";
    private static final String TOPIC_GONE = "Có chủ đề không còn trong danh sách. Hãy chọn lại chủ đề.";
    private static final String DESCRIPTION_TOO_LONG = "Mô tả chi tiết tối đa 5.000 ký tự.";
    private static final String MSG37 = "Hãy nhập tiêu đề và nội dung cho bài viết.";
    private static final String PLACES_TOO_MANY = "Chọn tối đa 5 địa điểm.";
    private static final String PLACE_INVALID = "Có địa điểm không hợp lệ. Hãy chọn lại địa điểm.";
    private static final String PLACE_GONE = "Có địa điểm không còn trên bản đồ. Hãy chọn lại địa điểm.";
    private static final String OSM_UNAVAILABLE =
            "Chưa lấy được thông tin địa điểm từ OpenStreetMap. Hãy thử lại sau hoặc bỏ địa điểm đó.";
    private static final String BAD_TYPE = "Loại bài không hợp lệ.";
    private static final String IMAGE_RULE = "Tối đa 5 ảnh, định dạng JPG, PNG hoặc HEIC, mỗi ảnh không quá 20 MB.";
    private static final String ONE_VIDEO = "Chỉ đính kèm được 1 video.";
    private static final String VIDEO_TOO_LONG = "Video dài 40 giây. Video tối đa 30 giây.";
    private static final String LINK_NEEDS_TAG = ContentFilter.LinkProblem.LINK_NEEDS_TAG.message();
    private static final String QR_NEEDS_TAG = "Ảnh thứ 1 bạn vừa chọn có mã QR. Chỉ tài khoản đã có tag định danh mới"
            + " được đăng ảnh có mã QR (mã QR được tính như link). Hãy cắt hoặc che mã QR rồi chọn lại ảnh.";
    private static final String QR_KEYWORD = "Chưa đăng được: mã QR trong ảnh thứ 1 bạn vừa chọn chứa từ khóa không"
            + " được phép “fb88”. Hãy dùng ảnh khác.";
    private static final String MSG44 = "Bạn đã đạt giới hạn đăng bài trong ngày của tài khoản mới (dưới 7 ngày: tối"
            + " đa 3 bài viết hoặc review mỗi ngày). Vui lòng thử lại vào ngày mai.";
    private static final String VIDEO_LIMIT = "Bạn đã đạt giới hạn video trong ngày của tài khoản mới (tối đa 1 video"
            + " mỗi ngày). Hãy bỏ video hoặc thử lại vào ngày mai.";
    private static final String MSG36 =
            "Chưa đăng được: nội dung chứa từ khóa không được phép “vay nóng”. Hãy sửa rồi gửi lại.";
    private static final String MSG34_QUESTION = "Câu hỏi của bạn đã được đăng.";
    private static final String SENT_FOR_SCAN =
            "Đã gửi. Ảnh và video đang được quét, trong lúc này chỉ bạn thấy câu hỏi.";
    private static final String SAVED_CHANGES = "Đã lưu thay đổi.";
    private static final String SAVED_CHANGES_SCANNING =
            "Đã lưu thay đổi. Ảnh và video đang được quét, trong lúc này chỉ bạn thấy câu hỏi.";

    private BlogWriteRepository repository;
    private BlogFeedRepository feedRepository;
    private ContentFilter filter;
    private MediaService media;
    private SystemSettings settings;
    private BlogPublishService publisher;
    private PlaceService places;
    private PlatformTransactionManager txManager;
    private MediaScanService scanner;
    private PointService points;
    private NotificationService notifications;
    private BlogWriteService service;

    @BeforeEach
    void setUp() {
        repository = mock(BlogWriteRepository.class);
        feedRepository = mock(BlogFeedRepository.class);
        filter = mock(ContentFilter.class);
        media = mock(MediaService.class);
        settings = mock(SystemSettings.class);
        publisher = mock(BlogPublishService.class);
        places = mock(PlaceService.class);
        txManager = mock(PlatformTransactionManager.class);
        scanner = mock(MediaScanService.class);
        points = mock(PointService.class);
        notifications = mock(NotificationService.class);
        service = serviceAt(NOW);

        account(MEMBER_ID, "USER", "ACTIVE", VETERAN_SINCE);
        account(OTHER_ID, "USER", "ACTIVE", VETERAN_SINCE);
        // Admin settings keep their defaults: 7 days, 3 posts, 2 pins, 1 video
        when(settings.getInt(anyString(), anyInt())).thenAnswer(inv -> inv.getArgument(1));
        when(repository.activeTopics()).thenReturn(TOPICS);
        when(repository.countActiveTopics(anyList())).thenAnswer(inv -> ((List<?>) inv.getArgument(0)).size());
        when(repository.countActivePlaces(anyList())).thenAnswer(inv -> ((List<?>) inv.getArgument(0)).size());
        when(repository.usage(anyLong(), any())).thenReturn(new Usage(0, 0, 0));
        when(repository.lockUsage(anyLong(), any())).thenReturn(new Usage(0, 0, 0));
        when(repository.insertBlog(anyLong(), anyString(), anyString(), anyString(), anyString(), any()))
                .thenReturn(QUESTION_ID);
        when(places.resolve(anyLong(), anyList())).thenReturn(PlacePicks.NONE);
        when(places.save(any(), anyLong())).thenReturn(List.of());
        when(media.videoMaxMb()).thenReturn(50);
        when(media.prepareImage(any())).thenReturn(photo());
        when(media.prepareVideo(any())).thenReturn(clip());
        AtomicLong nextMediaId = new AtomicLong(501);
        when(media.save(anyLong(), anyString(), any())).thenAnswer(inv -> nextMediaId.getAndIncrement());

        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
        TransactionSynchronizationManager.initSynchronization();
    }

    @AfterEach
    void tearDown() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    // ---------------------------------------------------------------- helpers

    private BlogWriteService serviceAt(Instant instant) {
        return serviceAt(instant, "/media/");
    }

    private BlogWriteService serviceAt(Instant instant, String mediaBaseUrl) {
        return new BlogWriteService(repository, feedRepository, filter, media, settings, publisher, places, txManager,
                Clock.fixed(instant, ZoneOffset.UTC), mediaBaseUrl);
    }

    private static AppUserPrincipal principal(long id, String role) {
        return new AppUserPrincipal(id, "user" + id + "@example.org", "User " + id, role, "/");
    }

    private void account(long id, String role, String status, Instant createdAt) {
        when(repository.account(id)).thenReturn(Optional.of(new Account(id, role, status, createdAt)));
    }

    /** Today's counters of the member, read and locked. */
    private void usageToday(int posts, int pins, int videos) {
        when(repository.lockUsage(anyLong(), any())).thenReturn(new Usage(posts, pins, videos));
        when(repository.usage(anyLong(), any())).thenReturn(new Usage(posts, pins, videos));
    }

    private static CreateRequest ask(String title, String content, List<Long> topicIds) {
        return new CreateRequest(title, content, topicIds, List.of(), List.of());
    }

    private static CreateRequest askAt(List<Long> placeIds, List<String> externalPlaceIds) {
        return new CreateRequest(TITLE, CONTENT, List.of(VISA), placeIds, externalPlaceIds);
    }

    private static UpdateRequest edit(String title, String content, List<Long> topicIds, List<Long> keepMediaIds) {
        return new UpdateRequest(title, content, topicIds, List.of(), List.of(), keepMediaIds);
    }

    private static UpdateRequest editKeeping(List<Long> keepMediaIds) {
        return edit(NEW_TITLE, NEW_CONTENT, List.of(VISA), keepMediaIds);
    }

    private static MockMultipartFile photoFile() {
        return new MockMultipartFile("images", "the-cu-tru.jpg", "image/jpeg", new byte[]{1, 2, 3});
    }

    private static MockMultipartFile videoFile() {
        return new MockMultipartFile("video", "clip.mp4", "video/mp4", new byte[]{4, 5, 6});
    }

    private static List<MultipartFile> photoFiles(int count) {
        List<MultipartFile> files = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            files.add(photoFile());
        }
        return files;
    }

    private static Prepared photo(String... qrCodes) {
        return new Prepared("IMAGE", new byte[]{1}, "image/jpeg", "jpg", 1200, 900, null, List.of(qrCodes));
    }

    private static Prepared clip() {
        return new Prepared("VIDEO", new byte[]{2}, "video/mp4", "mp4", null, null, 12, List.of());
    }

    private Created askQuestion(CreateRequest request) {
        return service.create(MEMBER, QA, request, List.of(), null);
    }

    private Created editQuestion(UpdateRequest request) {
        return service.update(MEMBER, QA, QUESTION_ID, request, List.of(), null);
    }

    private static ApiException apiError(ThrowingCallable call, HttpStatus status, String message) {
        Throwable thrown = catchThrowable(call);
        assertThat(thrown).isInstanceOf(ApiException.class).hasMessage(message);
        ApiException error = (ApiException) thrown;
        assertThat(error.getStatus()).isEqualTo(status);
        return error;
    }

    /** The after-commit hooks the save registered, run as Spring would run them once the transaction commits. */
    private static void commit() {
        for (TransactionSynchronization hook : TransactionSynchronizationManager.getSynchronizations()) {
            hook.afterCommit();
        }
    }

    /** No question row, link, file, usage, point or scan was written. */
    private void assertNothingSaved() {
        verify(repository, never()).insertBlog(anyLong(), any(), any(), any(), any(), any());
        verify(repository, never()).linkTopics(anyLong(), anyList());
        verify(repository, never()).linkPlaces(anyLong(), anyList());
        verify(repository, never()).linkMedia(anyLong(), anyLong(), anyInt());
        verify(repository, never()).addUsage(anyLong(), any(), anyInt(), anyInt());
        verify(media, never()).save(anyLong(), any(), any());
        verify(places, never()).save(any(), anyLong());
        verify(publisher, never()).awardOnce(anyLong(), anyLong());
        verify(publisher, never()).scanSoon(anyLong());
        assertThat(TransactionSynchronizationManager.getSynchronizations()).isEmpty();
    }

    /** The edited question was not changed: no text, link, file, usage, point or scan was written or removed. */
    private void assertEditNotSaved() {
        verify(repository, never()).updateBlog(anyLong(), any(), any(), any(), any());
        verify(repository, never()).unlinkTopics(anyLong());
        verify(repository, never()).linkTopics(anyLong(), anyList());
        verify(repository, never()).unlinkPlaces(anyLong());
        verify(repository, never()).linkPlaces(anyLong(), anyList());
        verify(repository, never()).unlinkMedia(anyLong(), anyList());
        verify(repository, never()).setMediaOrder(anyLong(), anyLong(), anyInt());
        verify(repository, never()).linkMedia(anyLong(), anyLong(), anyInt());
        verify(repository, never()).addUsage(anyLong(), any(), anyInt(), anyInt());
        verify(media, never()).discard(any());
        verify(media, never()).save(anyLong(), any(), any());
        verify(places, never()).save(any(), anyLong());
        verify(publisher, never()).awardOnce(anyLong(), anyLong());
        verify(publisher, never()).scanSoon(anyLong());
    }

    /** The checks failed before the transaction: no database connection was taken for the save. */
    private void assertNoTransaction() {
        verify(txManager, never()).getTransaction(any());
    }

    /** A check inside the transaction failed: the transaction is rolled back, never committed. */
    private void assertRolledBack() {
        verify(txManager).rollback(any());
        verify(txManager, never()).commit(any());
    }

    private void editable(String status) {
        editable(MEMBER_ID, QA, status);
    }

    private void editable(long authorId, String type, String status) {
        when(repository.editableBlog(eq(QUESTION_ID), anyBoolean())).thenReturn(Optional.of(
                new EditableBlog(QUESTION_ID, authorId, type, status, "Hỏi về nhà ở Arakawa", "Mô tả ban đầu")));
    }

    private void attached(AttachedMedia... files) {
        when(repository.attachedMedia(QUESTION_ID)).thenReturn(List.of(files));
    }

    private static AttachedMedia image(long id, String scanStatus, int order) {
        return new AttachedMedia(id, "IMAGE", "blog/2026/11/" + id + ".jpg", scanStatus, order);
    }

    private static AttachedMedia video(long id, String scanStatus, int order) {
        return new AttachedMedia(id, "VIDEO", "blog/2026/11/" + id + ".mp4", scanStatus, order);
    }

    // =================================================================================================================
    @Nested
    @TestMethodOrder(MethodOrderer.DisplayName.class)
    @DisplayName("01. compose – UC 1.14 Mở form \"Đặt câu hỏi\" (UTC-001 → UTC-016)")
    class ComposeForm {

        @Test
        @DisplayName("UTC-001 Form chỉ có các chủ đề đang dùng, đúng thứ tự, \"Khác\" có đánh dấu")
        void utc001_formListsActiveTopicsInOrder() {
            ComposeOptions options = service.compose(MEMBER);

            assertThat(options.topics()).containsExactlyElementsOf(TOPICS);
            assertThat(options.topics()).filteredOn(TopicOption::other).extracting(TopicOption::name)
                    .containsExactly("Khác");
            verify(repository).activeTopics();
        }

        @Test
        @DisplayName("UTC-002 Giới hạn của form: câu hỏi 150, mô tả 5.000 không bắt buộc, 3 chủ đề, 5 địa điểm, 5 ảnh 20 MB, 1 video 30 giây")
        void utc002_formGivesTheQuestionLimits() {
            Limits limits = service.compose(MEMBER).limits();

            assertThat(limits).isEqualTo(new Limits(150, 5000, 3, 5, 5, 20, 1, 30, 50, false));
            assertThat(limits.questionContentRequired()).isFalse();
        }

        @Test
        @DisplayName("UTC-003 Dung lượng video tối đa theo cấu hình máy chủ")
        void utc003_videoSizeLimitFollowsTheServerSetting() {
            when(media.videoMaxMb()).thenReturn(80);

            assertThat(service.compose(MEMBER).limits().videoMaxMb()).isEqualTo(80);
        }

        @Test
        @DisplayName("UTC-004 Tài khoản mới (2 ngày): thấy số đã dùng hôm nay trên hạn mức 3 bài · 2 ghim · 1 video")
        void utc004_newAccountSeesTodaysUsage() {
            account(MEMBER_ID, "USER", "ACTIVE", NEWBIE_SINCE);
            when(repository.usage(MEMBER_ID, TODAY)).thenReturn(new Usage(2, 1, 0));

            Quota quota = service.compose(MEMBER).quota();

            assertThat(quota).isEqualTo(new Quota(true, 7, 2, 2, 3, 1, 2, 0, 1));
        }

        @Test
        @DisplayName("UTC-005 Tài khoản 30 ngày: không phải tài khoản mới, vẫn thấy số đã dùng")
        void utc005_veteranIsNotANewAccount() {
            when(repository.usage(MEMBER_ID, TODAY)).thenReturn(new Usage(5, 0, 2));

            Quota quota = service.compose(MEMBER).quota();

            assertThat(quota.newAccount()).isFalse();
            assertThat(quota.accountAgeDays()).isEqualTo(30);
            assertThat(quota.postsUsed()).isEqualTo(5);
            assertThat(quota.videosUsed()).isEqualTo(2);
        }

        @ParameterizedTest(name = "[{index}] tạo cách đây {0} giây → tài khoản mới: {1}, {2} ngày tuổi")
        @CsvSource({"604800, false, 7", "604799, true, 6"})
        @DisplayName("UTC-006 Biên 7 ngày: đủ 7 ngày không còn là tài khoản mới, kém 1 giây vẫn là tài khoản mới")
        void utc006_sevenDayBoundaryOfANewAccount(long secondsAgo, boolean newAccount, int ageDays) {
            account(MEMBER_ID, "USER", "ACTIVE", NOW.minusSeconds(secondsAgo));

            Quota quota = service.compose(MEMBER).quota();

            assertThat(quota.newAccount()).isEqualTo(newAccount);
            assertThat(quota.accountAgeDays()).isEqualTo(ageDays);
        }

        @ParameterizedTest(name = "[{index}] {0} → ngày {1}")
        @CsvSource({"2026-11-20T14:59:59Z, 2026-11-20", "2026-11-20T15:00:00Z, 2026-11-21"})
        @DisplayName("UTC-007 Số đã dùng hôm nay đọc theo ngày Nhật Bản (đổi ngày lúc 0:00 giờ Nhật)")
        void utc007_usageIsReadForTheJapanDate(String instant, String japanDate) {
            service = serviceAt(Instant.parse(instant));

            service.compose(MEMBER);

            verify(repository).usage(MEMBER_ID, LocalDate.parse(japanDate));
        }

        @Test
        @DisplayName("UTC-008 Admin đổi cài đặt (14 ngày, 5 bài, 4 ghim, 2 video): hạn mức theo cài đặt mới")
        void utc008_adminSettingsChangeTheQuota() {
            account(MEMBER_ID, "USER", "ACTIVE", NOW.minus(Duration.ofDays(10)));
            when(settings.getInt(eq(SystemSettings.NEW_ACCOUNT_DAYS), anyInt())).thenReturn(14);
            when(settings.getInt(eq(SystemSettings.POSTS_PER_DAY), anyInt())).thenReturn(5);
            when(settings.getInt(eq(SystemSettings.PINS_PER_DAY), anyInt())).thenReturn(4);
            when(settings.getInt(eq(SystemSettings.VIDEOS_PER_DAY), anyInt())).thenReturn(2);

            Quota quota = service.compose(MEMBER).quota();

            assertThat(quota).isEqualTo(new Quota(true, 14, 10, 0, 5, 0, 4, 0, 2));
        }

        @ParameterizedTest(name = "[{index}] có tag định danh: {0}")
        @ValueSource(booleans = {true, false})
        @DisplayName("UTC-009 \"Được chèn link\" theo tag định danh của người hỏi")
        void utc009_canPostLinksFollowsTheIdentityTag(boolean tagged) {
            when(filter.hasIdentityTag(MEMBER_ID)).thenReturn(tagged);

            assertThat(service.compose(MEMBER).canPostLinks()).isEqualTo(tagged);
            verify(filter).hasIdentityTag(MEMBER_ID);
        }

        @Test
        @DisplayName("UTC-010 Ngày tạo tài khoản sau giờ máy chủ: tuổi tài khoản là 0 ngày")
        void utc010_createdAtAfterNowCountsAsAgeZero() {
            account(MEMBER_ID, "USER", "ACTIVE", NOW.plus(Duration.ofHours(1)));

            Quota quota = service.compose(MEMBER).quota();

            assertThat(quota.accountAgeDays()).isZero();
            assertThat(quota.newAccount()).isTrue();
        }

        @Test
        @DisplayName("UTC-011 Khách mở form: 401 \"Bạn cần đăng nhập để viết bài.\"")
        void utc011_guestMustSignIn() {
            apiError(() -> service.compose(null), HttpStatus.UNAUTHORIZED, LOGIN_TO_WRITE);

            verifyNoInteractions(repository, filter, media);
        }

        @ParameterizedTest(name = "[{index}] vai trò {0}")
        @ValueSource(strings = {"MODERATOR", "ACADEMIC_MANAGER", "ADMIN"})
        @DisplayName("UTC-012 Nhân viên (Moderator, BTV tiếng Nhật, Admin) mở form: 403 MSG18")
        void utc012_staffCannotOpenTheForm(String role) {
            account(STAFF_ID, role, "ACTIVE", VETERAN_SINCE);

            apiError(() -> service.compose(principal(STAFF_ID, role)), HttpStatus.FORBIDDEN, MSG18);

            verify(repository, never()).activeTopics();
            verify(repository, never()).usage(anyLong(), any());
        }

        @Test
        @DisplayName("UTC-013 Vai trò đọc lại từ CSDL: phiên ghi USER nhưng đã thành Moderator → 403 MSG18")
        void utc013_roleIsReadFromTheDatabase() {
            account(MEMBER_ID, "MODERATOR", "ACTIVE", VETERAN_SINCE);

            apiError(() -> service.compose(MEMBER), HttpStatus.FORBIDDEN, MSG18);
        }

        @Test
        @DisplayName("UTC-014 Tài khoản bị khóa mở form: 403 MSG18")
        void utc014_lockedAccountCannotOpenTheForm() {
            account(MEMBER_ID, "USER", "LOCKED", VETERAN_SINCE);

            apiError(() -> service.compose(MEMBER), HttpStatus.FORBIDDEN, MSG18);
            verify(repository, never()).activeTopics();
        }

        @Test
        @DisplayName("UTC-015 Không còn tài khoản (đã xóa): 401 \"Bạn chưa đăng nhập.\"")
        void utc015_missingAccountMustSignInAgain() {
            when(repository.account(MEMBER_ID)).thenReturn(Optional.empty());

            apiError(() -> service.compose(MEMBER), HttpStatus.UNAUTHORIZED, NOT_SIGNED_IN);
        }

        @Test
        @DisplayName("UTC-016 Mở form không ghi gì: không khóa hạn mức, không tạo câu hỏi, không mở giao dịch")
        void utc016_openingTheFormWritesNothing() {
            service.compose(MEMBER);

            verify(repository, never()).lockUsage(anyLong(), any());
            assertNothingSaved();
            assertNoTransaction();
        }
    }

    // =================================================================================================================
    @Nested
    @TestMethodOrder(MethodOrderer.DisplayName.class)
    @DisplayName("02. create – UC 1.14 Đặt câu hỏi: phân quyền (UTC-017 → UTC-021)")
    class CreatePermissions {

        @Test
        @DisplayName("UTC-017 Khách gửi câu hỏi: 401 \"Bạn cần đăng nhập để viết bài.\", không đọc, không lưu gì")
        void utc017_guestMustSignIn() {
            apiError(() -> service.create(null, QA, ask(TITLE, CONTENT, List.of(VISA)), List.of(photoFile()), null),
                    HttpStatus.UNAUTHORIZED, LOGIN_TO_WRITE);

            verifyNoInteractions(repository, filter, media, places, publisher, txManager);
        }

        @ParameterizedTest(name = "[{index}] vai trò {0}")
        @ValueSource(strings = {"MODERATOR", "ACADEMIC_MANAGER", "ADMIN"})
        @DisplayName("UTC-018 Nhân viên gửi câu hỏi: 403 MSG18, ảnh không được xử lý, không lưu gì")
        void utc018_staffCannotAsk(String role) {
            account(STAFF_ID, role, "ACTIVE", VETERAN_SINCE);

            apiError(() -> service.create(principal(STAFF_ID, role), QA, ask(TITLE, CONTENT, List.of(VISA)),
                    List.of(photoFile()), null), HttpStatus.FORBIDDEN, MSG18);

            verify(media, never()).prepareImage(any());
            assertNothingSaved();
            assertNoTransaction();
        }

        @Test
        @DisplayName("UTC-019 Tài khoản bị khóa gửi câu hỏi: 403 MSG18, không lưu gì")
        void utc019_lockedAccountCannotAsk() {
            account(MEMBER_ID, "USER", "LOCKED", VETERAN_SINCE);

            apiError(() -> askQuestion(ask(TITLE, CONTENT, List.of(VISA))), HttpStatus.FORBIDDEN, MSG18);

            assertNothingSaved();
            verifyNoInteractions(filter);
        }

        @Test
        @DisplayName("UTC-020 Không còn tài khoản (đã xóa): 401 \"Bạn chưa đăng nhập.\"")
        void utc020_missingAccountMustSignInAgain() {
            when(repository.account(MEMBER_ID)).thenReturn(Optional.empty());

            apiError(() -> askQuestion(ask(TITLE, CONTENT, List.of(VISA))), HttpStatus.UNAUTHORIZED, NOT_SIGNED_IN);

            assertNothingSaved();
        }

        @Test
        @DisplayName("UTC-021 Vai trò đọc lại từ CSDL: phiên ghi USER nhưng đã thành Admin → 403 MSG18")
        void utc021_roleIsReadFromTheDatabase() {
            account(MEMBER_ID, "ADMIN", "ACTIVE", VETERAN_SINCE);

            apiError(() -> askQuestion(ask(TITLE, CONTENT, List.of(VISA))), HttpStatus.FORBIDDEN, MSG18);

            assertNothingSaved();
        }
    }

    // =================================================================================================================
    @Nested
    @TestMethodOrder(MethodOrderer.DisplayName.class)
    @DisplayName("03. create – UC 1.14 Đặt câu hỏi: các trường thông tin (UTC-022 → UTC-043)")
    class CreateFields {

        @Test
        @DisplayName("UTC-022 Câu hỏi không có ảnh/video được đăng ngay (PUBLISHED), loại QA")
        void utc022_questionWithoutFilesIsPublishedAtOnce() {
            Created created = askQuestion(ask(TITLE, CONTENT, List.of(VISA, PAPERS)));

            assertThat(created).isEqualTo(new Created(QUESTION_ID, "QA", "PUBLISHED"));
            verify(repository).insertBlog(MEMBER_ID, "QA", TITLE, CONTENT, "PUBLISHED", NOW);
            verify(txManager).commit(any());
        }

        @Test
        @DisplayName("UTC-023 Câu hỏi được làm gọn: khoảng trắng, xuống dòng trong câu hỏi gộp thành một dấu cách; mô tả bỏ khoảng trắng hai đầu, CRLF thành LF")
        void utc023_questionTextIsTidied() {
            askQuestion(ask("  Gia hạn   visa\n du học\tcần giấy tờ gì?  ", "  Dòng 1\r\nDòng 2  ", List.of(VISA)));

            verify(repository).insertBlog(MEMBER_ID, "QA", TITLE, "Dòng 1\nDòng 2", "PUBLISHED", NOW);
        }

        @ParameterizedTest(name = "[{index}] kind \"{0}\"")
        @ValueSource(strings = {"qa", " QA ", "Qa"})
        @DisplayName("UTC-024 Loại bài không phân biệt hoa thường, bỏ khoảng trắng: vẫn lưu loại QA")
        void utc024_kindIsReadCaseInsensitively(String kind) {
            Created created = service.create(MEMBER, kind, ask(TITLE, CONTENT, List.of(VISA)), List.of(), null);

            assertThat(created.type()).isEqualTo("QA");
            verify(repository).insertBlog(MEMBER_ID, "QA", TITLE, CONTENT, "PUBLISHED", NOW);
        }

        @ParameterizedTest(name = "[{index}] mô tả = \"{0}\"")
        @NullSource
        @ValueSource(strings = {"", "   "})
        @DisplayName("UTC-025 \"Mô tả chi tiết\" không bắt buộc: rỗng, chỉ khoảng trắng hoặc không gửi vẫn được đăng")
        void utc025_descriptionIsOptional(String content) {
            Created created = askQuestion(ask(TITLE, content, List.of(OTHER_TOPIC)));

            assertThat(created.status()).isEqualTo("PUBLISHED");
            verify(repository).insertBlog(MEMBER_ID, "QA", TITLE, "", "PUBLISHED", NOW);
        }

        @Test
        @DisplayName("UTC-026 Câu hỏi đúng 150 ký tự được nhận")
        void utc026_titleOf150CharactersIsAccepted() {
            String title150 = "Hỏi " + "a".repeat(146);
            assertThat(title150).hasSize(150);

            Created created = askQuestion(ask(title150, "", List.of(OTHER_TOPIC)));

            assertThat(created.status()).isEqualTo("PUBLISHED");
            verify(repository).insertBlog(MEMBER_ID, "QA", title150, "", "PUBLISHED", NOW);
        }

        @Test
        @DisplayName("UTC-027 Câu hỏi 151 ký tự: 400 \"Câu hỏi tối đa 150 ký tự.\", không lưu gì")
        void utc027_titleOf151CharactersIsRefused() {
            String title151 = "Hỏi " + "a".repeat(147);

            ApiException error = apiError(() -> askQuestion(ask(title151, "", List.of(OTHER_TOPIC))),
                    HttpStatus.BAD_REQUEST, TITLE_TOO_LONG);

            assertThat(error.getFields()).containsExactlyEntriesOf(Map.of("title", TITLE_TOO_LONG));
            assertNothingSaved();
            assertNoTransaction();
        }

        @ParameterizedTest(name = "[{index}] câu hỏi = \"{0}\"")
        @NullSource
        @ValueSource(strings = {"", "   ", "\n\t "})
        @DisplayName("UTC-028 Thiếu câu hỏi (rỗng, khoảng trắng, không gửi): 400 \"Hãy nhập câu hỏi.\" (không phải MSG37)")
        void utc028_missingQuestionIsRefused(String title) {
            ApiException error = apiError(() -> askQuestion(ask(title, CONTENT, List.of(OTHER_TOPIC))),
                    HttpStatus.BAD_REQUEST, TITLE_REQUIRED);

            assertThat(error.getMessage()).isNotEqualTo(MSG37);
            assertThat(error.getFields()).containsExactlyEntriesOf(Map.of("title", TITLE_REQUIRED));
            assertNothingSaved();
        }

        @Test
        @DisplayName("UTC-029 Mô tả đúng 5.000 ký tự được nhận")
        void utc029_descriptionOf5000CharactersIsAccepted() {
            String content = "a".repeat(5000);

            Created created = askQuestion(ask(TITLE, content, List.of(OTHER_TOPIC)));

            assertThat(created.status()).isEqualTo("PUBLISHED");
            verify(repository).insertBlog(MEMBER_ID, "QA", TITLE, content, "PUBLISHED", NOW);
        }

        @Test
        @DisplayName("UTC-030 Mô tả 5.001 ký tự: 400 \"Mô tả chi tiết tối đa 5.000 ký tự.\"")
        void utc030_descriptionOf5001CharactersIsRefused() {
            ApiException error = apiError(() -> askQuestion(ask(TITLE, "a".repeat(5001), List.of(OTHER_TOPIC))),
                    HttpStatus.BAD_REQUEST, DESCRIPTION_TOO_LONG);

            assertThat(error.getFields()).containsExactlyEntriesOf(Map.of("content", DESCRIPTION_TOO_LONG));
            assertNothingSaved();
        }

        @ParameterizedTest(name = "[{index}] topicIds = {0}")
        @NullAndEmptySource
        @DisplayName("UTC-031 Không chọn chủ đề (danh sách rỗng hoặc không gửi): 400 \"Hãy chọn ít nhất 1 chủ đề…\"")
        void utc031_noTopicIsRefused(List<Long> topicIds) {
            ApiException error = apiError(() -> askQuestion(ask(TITLE, CONTENT, topicIds)),
                    HttpStatus.BAD_REQUEST, TOPIC_REQUIRED);

            assertThat(error.getFields()).containsExactlyEntriesOf(Map.of("topicIds", TOPIC_REQUIRED));
            verify(repository, never()).countActiveTopics(anyList());
            assertNothingSaved();
        }

        @ParameterizedTest(name = "[{index}] {0} chủ đề")
        @ValueSource(ints = {1, 3})
        @DisplayName("UTC-032 1 chủ đề và đúng 3 chủ đề được nhận, các chủ đề được gắn vào câu hỏi")
        void utc032_oneToThreeTopicsAreAccepted(int count) {
            List<Long> topicIds = List.of(VISA, PAPERS, HOUSING).subList(0, count);

            askQuestion(ask(TITLE, CONTENT, topicIds));

            verify(repository).countActiveTopics(topicIds);
            verify(repository).linkTopics(QUESTION_ID, topicIds);
        }

        @Test
        @DisplayName("UTC-033 4 chủ đề: 400 \"Chọn tối đa 3 chủ đề.\", không cần hỏi CSDL")
        void utc033_fourTopicsAreRefused() {
            ApiException error = apiError(() -> askQuestion(ask(TITLE, CONTENT, List.of(VISA, PAPERS, HOUSING, HEALTH))),
                    HttpStatus.BAD_REQUEST, TOPICS_TOO_MANY);

            assertThat(error.getFields()).containsExactlyEntriesOf(Map.of("topicIds", TOPICS_TOO_MANY));
            verify(repository, never()).countActiveTopics(anyList());
            assertNothingSaved();
        }

        @Test
        @DisplayName("UTC-034 Chủ đề trùng và null bị bỏ: [3, null, 3, 5, 5, 9] tính là 3 chủ đề")
        void utc034_repeatedTopicIdsCountOnce() {
            askQuestion(ask(TITLE, CONTENT, Arrays.asList(VISA, null, VISA, PAPERS, PAPERS, HEALTH)));

            verify(repository).countActiveTopics(List.of(VISA, PAPERS, HEALTH));
            verify(repository).linkTopics(QUESTION_ID, List.of(VISA, PAPERS, HEALTH));
        }

        @Test
        @DisplayName("UTC-035 Chủ đề đã bị Admin tắt: 400 \"Có chủ đề không còn trong danh sách…\"")
        void utc035_switchedOffTopicIsRefused() {
            when(repository.countActiveTopics(List.of(VISA, PAPERS))).thenReturn(1);

            ApiException error = apiError(() -> askQuestion(ask(TITLE, CONTENT, List.of(VISA, PAPERS))),
                    HttpStatus.BAD_REQUEST, TOPIC_GONE);

            assertThat(error.getFields()).containsExactlyEntriesOf(Map.of("topicIds", TOPIC_GONE));
            assertNothingSaved();
        }

        @Test
        @DisplayName("UTC-036 Đúng 5 địa điểm (3 của Senpai ơi + 2 từ OpenStreetMap) được nhận")
        void utc036_fivePlacesAreAccepted() {
            List<String> osm = List.of("N772389775", "W123456");
            when(places.resolve(MEMBER_ID, osm)).thenReturn(new PlacePicks(List.of(31L, 32L), List.of(), null));
            when(places.save(any(), eq(MEMBER_ID))).thenReturn(List.of(31L, 32L));

            Created created = askQuestion(askAt(List.of(21L, 22L, 23L), osm));

            assertThat(created.status()).isEqualTo("PUBLISHED");
            verify(repository).linkPlaces(QUESTION_ID, List.of(21L, 22L, 23L, 31L, 32L));
        }

        @Test
        @DisplayName("UTC-037 6 địa điểm (4 + 2): 400 \"Chọn tối đa 5 địa điểm.\", không hỏi OpenStreetMap")
        void utc037_sixPlacesAreRefused() {
            ApiException error = apiError(() -> askQuestion(askAt(List.of(21L, 22L, 23L, 24L),
                    List.of("N772389775", "W123456"))), HttpStatus.BAD_REQUEST, PLACES_TOO_MANY);

            assertThat(error.getFields()).containsExactlyEntriesOf(Map.of("placeIds", PLACES_TOO_MANY));
            verify(places, never()).resolve(anyLong(), anyList());
            assertNothingSaved();
        }

        @ParameterizedTest(name = "[{index}] externalPlaceId = \"{0}\"")
        @ValueSource(strings = {"abc", "N0", "X123"})
        @DisplayName("UTC-038 Mã địa điểm OpenStreetMap không hợp lệ: 400 \"Có địa điểm không hợp lệ…\"")
        void utc038_invalidMapPlaceIdIsRefused(String externalId) {
            ApiException error = apiError(() -> askQuestion(askAt(List.of(), List.of(externalId))),
                    HttpStatus.BAD_REQUEST, PLACE_INVALID);

            assertThat(error.getFields()).containsExactlyEntriesOf(Map.of("placeIds", PLACE_INVALID));
            verify(places, never()).resolve(anyLong(), anyList());
            assertNothingSaved();
        }

        @Test
        @DisplayName("UTC-039 Địa điểm đã bị gỡ khỏi bản đồ: 400 \"Có địa điểm không còn trên bản đồ…\"")
        void utc039_placeNoLongerOnTheMapIsRefused() {
            when(repository.countActivePlaces(List.of(21L, 22L))).thenReturn(1);

            ApiException error = apiError(() -> askQuestion(askAt(List.of(21L, 22L), List.of())),
                    HttpStatus.BAD_REQUEST, PLACE_GONE);

            assertThat(error.getFields()).containsExactlyEntriesOf(Map.of("placeIds", PLACE_GONE));
            assertNothingSaved();
        }

        @Test
        @DisplayName("UTC-040 OpenStreetMap không trả lời: lỗi hiện dưới \"Địa điểm liên quan\", không lưu gì")
        void utc040_mapLookupFailureIsShownUnderPlaces() {
            when(places.resolve(MEMBER_ID, List.of("N772389775")))
                    .thenReturn(new PlacePicks(List.of(), List.of(), OSM_UNAVAILABLE));

            ApiException error = apiError(() -> askQuestion(askAt(List.of(), List.of("N772389775"))),
                    HttpStatus.BAD_REQUEST, OSM_UNAVAILABLE);

            assertThat(error.getFields()).containsExactlyEntriesOf(Map.of("placeIds", OSM_UNAVAILABLE));
            assertNothingSaved();
            assertNoTransaction();
        }

        @Test
        @DisplayName("UTC-041 Nhiều trường sai cùng lúc: mỗi trường một lỗi, dòng trên form là lỗi của câu hỏi (không phải MSG37)")
        void utc041_everyInvalidFieldIsReportedAtOnce() {
            ApiException error = apiError(() -> askQuestion(ask("", "a".repeat(5001), List.of())),
                    HttpStatus.BAD_REQUEST, TITLE_REQUIRED);

            assertThat(error.getFields()).containsOnly(
                    Map.entry("title", TITLE_REQUIRED),
                    Map.entry("topicIds", TOPIC_REQUIRED),
                    Map.entry("content", DESCRIPTION_TOO_LONG));
            assertNothingSaved();
        }

        @Test
        @DisplayName("UTC-042 Không gửi nội dung câu hỏi (request = null): 400 \"Hãy nhập câu hỏi.\" và thiếu chủ đề")
        void utc042_missingRequestIsRefused() {
            ApiException error = apiError(() -> askQuestion(null), HttpStatus.BAD_REQUEST, TITLE_REQUIRED);

            assertThat(error.getFields()).containsOnly(
                    Map.entry("title", TITLE_REQUIRED),
                    Map.entry("topicIds", TOPIC_REQUIRED));
            assertNothingSaved();
        }

        @ParameterizedTest(name = "[{index}] kind = {0}")
        @NullSource
        @ValueSource(strings = {"NEWS"})
        @DisplayName("UTC-043 Loại bài không phải POST/QA: 400 \"Loại bài không hợp lệ.\", không kiểm tra gì thêm")
        void utc043_unknownKindIsRefused(String kind) {
            ApiException error = apiError(() -> service.create(MEMBER, kind, ask("", "", List.of()), List.of(), null),
                    HttpStatus.BAD_REQUEST, BAD_TYPE);

            assertThat(error.getFields()).containsExactlyEntriesOf(Map.of("type", BAD_TYPE));
            assertNothingSaved();
        }
    }

    // =================================================================================================================
    @Nested
    @TestMethodOrder(MethodOrderer.DisplayName.class)
    @DisplayName("04. create – UC 1.14 Đặt câu hỏi: ảnh và video (UTC-044 → UTC-053)")
    class CreateFiles {

        @Test
        @DisplayName("UTC-044 Câu hỏi có 1 ảnh: lưu ở trạng thái PROCESSING (chỉ người hỏi thấy), ảnh được gắn vào câu hỏi")
        void utc044_questionWithAPhotoWaitsForTheScan() {
            Prepared prepared = photo();
            when(media.prepareImage(any())).thenReturn(prepared);

            Created created = service.create(MEMBER, QA, ask(TITLE, CONTENT, List.of(VISA)), List.of(photoFile()), null);

            assertThat(created).isEqualTo(new Created(QUESTION_ID, "QA", "PROCESSING"));
            verify(repository).insertBlog(MEMBER_ID, "QA", TITLE, CONTENT, "PROCESSING", NOW);
            verify(media).save(MEMBER_ID, "blog", prepared);
            verify(repository).linkMedia(QUESTION_ID, 501L, 0);
            verify(publisher, never()).awardOnce(anyLong(), anyLong());
        }

        @Test
        @DisplayName("UTC-045 Việc quét ảnh chỉ bắt đầu sau khi giao dịch lưu câu hỏi đã commit")
        void utc045_scanStartsOnlyAfterCommit() {
            service.create(MEMBER, QA, ask(TITLE, CONTENT, List.of(VISA)), List.of(photoFile()), null);

            verify(publisher, never()).scanSoon(anyLong());
            assertThat(TransactionSynchronizationManager.getSynchronizations()).hasSize(1);

            commit();

            verify(publisher).scanSoon(QUESTION_ID);
        }

        @Test
        @DisplayName("UTC-046 2 ảnh và 1 video: lưu theo thứ tự ảnh trước, video sau; tính 1 bài và 1 video trong ngày")
        void utc046_photosThenVideoAreStoredInOrder() {
            Created created = service.create(MEMBER, QA, ask(TITLE, CONTENT, List.of(VISA)), photoFiles(2), videoFile());

            assertThat(created.status()).isEqualTo("PROCESSING");
            verify(media, times(2)).prepareImage(any());
            verify(media).prepareVideo(any());
            InOrder order = inOrder(repository);
            order.verify(repository).linkMedia(QUESTION_ID, 501L, 0);
            order.verify(repository).linkMedia(QUESTION_ID, 502L, 1);
            order.verify(repository).linkMedia(QUESTION_ID, 503L, 2);
            verify(repository).addUsage(MEMBER_ID, TODAY, 1, 1);
        }

        @Test
        @DisplayName("UTC-047 Đúng 5 ảnh được nhận")
        void utc047_fivePhotosAreAccepted() {
            Created created = service.create(MEMBER, QA, ask(TITLE, CONTENT, List.of(VISA)), photoFiles(5), null);

            assertThat(created.status()).isEqualTo("PROCESSING");
            verify(media, times(5)).prepareImage(any());
            verify(repository, times(5)).linkMedia(eq(QUESTION_ID), anyLong(), anyInt());
            verify(repository).linkMedia(QUESTION_ID, 505L, 4);
        }

        @Test
        @DisplayName("UTC-048 6 ảnh: 400 \"Tối đa 5 ảnh…\", không giải mã ảnh nào, không lưu gì")
        void utc048_sixPhotosAreRefused() {
            ApiException error = apiError(() -> service.create(MEMBER, QA, ask(TITLE, CONTENT, List.of(VISA)),
                    photoFiles(6), null), HttpStatus.BAD_REQUEST, IMAGE_RULE);

            assertThat(error.getFields()).containsExactlyEntriesOf(Map.of("images", IMAGE_RULE));
            verify(media, never()).prepareImage(any());
            assertNothingSaved();
        }

        @Test
        @DisplayName("UTC-049 Phần tệp rỗng hoặc null bị bỏ qua: câu hỏi được đăng như không có tệp")
        void utc049_emptyFilePartsAreIgnored() {
            List<MultipartFile> images = new ArrayList<>();
            images.add(new MockMultipartFile("images", new byte[0]));
            images.add(null);

            Created created = service.create(MEMBER, QA, ask(TITLE, CONTENT, List.of(VISA)), images,
                    new MockMultipartFile("video", new byte[0]));

            assertThat(created.status()).isEqualTo("PUBLISHED");
            verify(media, never()).prepareImage(any());
            verify(media, never()).prepareVideo(any());
            verify(repository).addUsage(MEMBER_ID, TODAY, 1, 0);
        }

        @Test
        @DisplayName("UTC-050 Ảnh sai định dạng hoặc quá 20 MB: 400 với quy tắc ảnh dưới \"Ảnh\", không lưu gì")
        void utc050_photoRefusedByTheMediaRules() {
            when(media.prepareImage(any())).thenReturn(photo()).thenThrow(new InvalidMediaException(IMAGE_RULE));

            ApiException error = apiError(() -> service.create(MEMBER, QA, ask(TITLE, CONTENT, List.of(VISA)),
                    photoFiles(2), null), HttpStatus.BAD_REQUEST, IMAGE_RULE);

            assertThat(error.getFields()).containsExactlyEntriesOf(Map.of("images", IMAGE_RULE));
            assertNothingSaved();
            assertNoTransaction();
        }

        @Test
        @DisplayName("UTC-051 Video dài 40 giây: 400 \"Video dài 40 giây. Video tối đa 30 giây.\" dưới \"Video\"")
        void utc051_videoLongerThan30SecondsIsRefused() {
            when(media.prepareVideo(any())).thenThrow(new InvalidMediaException(VIDEO_TOO_LONG));

            ApiException error = apiError(() -> service.create(MEMBER, QA, ask(TITLE, CONTENT, List.of(VISA)),
                    List.of(), videoFile()), HttpStatus.BAD_REQUEST, VIDEO_TOO_LONG);

            assertThat(error.getFields()).containsExactlyEntriesOf(Map.of("video", VIDEO_TOO_LONG));
            assertNothingSaved();
        }

        @Test
        @DisplayName("UTC-052 Thiếu câu hỏi và ảnh lỗi cùng lúc: báo cả hai trường, dòng trên form là lỗi của câu hỏi")
        void utc052_fileErrorAndFieldErrorAreShownTogether() {
            when(media.prepareImage(any())).thenThrow(new InvalidMediaException(IMAGE_RULE));

            ApiException error = apiError(() -> service.create(MEMBER, QA, ask("", CONTENT, List.of(VISA)),
                    List.of(photoFile()), null), HttpStatus.BAD_REQUEST, TITLE_REQUIRED);

            assertThat(error.getFields()).containsOnly(
                    Map.entry("title", TITLE_REQUIRED),
                    Map.entry("images", IMAGE_RULE));
            assertNothingSaved();
        }

        @Test
        @DisplayName("UTC-053 Ảnh lỗi: không hỏi OpenStreetMap về địa điểm mới (chỉ hỏi khi mọi trường đều đúng)")
        void utc053_mapIsNotAskedWhenAFileIsInvalid() {
            when(media.prepareImage(any())).thenThrow(new InvalidMediaException(IMAGE_RULE));

            apiError(() -> service.create(MEMBER, QA, askAt(List.of(), List.of("N772389775")), List.of(photoFile()),
                    null), HttpStatus.BAD_REQUEST, IMAGE_RULE);

            verify(places, never()).resolve(anyLong(), anyList());
        }
    }

    // =================================================================================================================
    @Nested
    @TestMethodOrder(MethodOrderer.DisplayName.class)
    @DisplayName("05. create – UC 1.14 Đặt câu hỏi: link, giới hạn trong ngày, từ khóa cấm (UTC-054 → UTC-075)")
    class CreateCommunityRules {

        @Test
        @DisplayName("UTC-054 Link trong mô tả khi chưa có tag định danh: 400, không lưu gì, không xét giới hạn và từ khóa")
        void utc054_linkWithoutIdentityTagIsRefused() {
            String withLink = "Xem tại https://www.moj.go.jp/isa/ nhé";
            doThrow(ApiException.badRequest(LINK_NEEDS_TAG)).when(filter).checkLinks(withLink, MEMBER_ID);

            apiError(() -> askQuestion(ask(TITLE, withLink, List.of(OTHER_TOPIC))), HttpStatus.BAD_REQUEST,
                    LINK_NEEDS_TAG);

            verify(repository, never()).lockUsage(anyLong(), any());
            verify(filter, never()).checkKeywords(anyString());
            assertNothingSaved();
            assertRolledBack();
        }

        @Test
        @DisplayName("UTC-055 Câu hỏi và mô tả đều được kiểm tra link với mã người hỏi")
        void utc055_titleAndDescriptionAreBothCheckedForLinks() {
            askQuestion(ask(TITLE, CONTENT, List.of(VISA)));

            verify(filter).checkLinks(TITLE, MEMBER_ID);
            verify(filter).checkLinks(CONTENT, MEMBER_ID);
            verify(filter).checkQrCodes(List.of(), MEMBER_ID);
        }

        @Test
        @DisplayName("UTC-056 Ảnh có mã QR khi chưa có tag định danh: 400 dưới \"Ảnh\", không lưu gì")
        void utc056_qrCodeInAPhotoWithoutTagIsRefused() {
            when(media.prepareImage(any())).thenReturn(photo("https://line.me/ti/p/abc123"));
            doThrow(ApiException.invalid(QR_NEEDS_TAG, Map.of("images", QR_NEEDS_TAG))).when(filter)
                    .checkQrCodes(List.of(List.of("https://line.me/ti/p/abc123")), MEMBER_ID);

            ApiException error = apiError(() -> service.create(MEMBER, QA, ask(TITLE, CONTENT, List.of(VISA)),
                    List.of(photoFile()), null), HttpStatus.BAD_REQUEST, QR_NEEDS_TAG);

            assertThat(error.getFields()).containsOnlyKeys("images");
            assertNothingSaved();
            assertRolledBack();
        }

        @Test
        @DisplayName("UTC-057 Chỉ mã QR của ảnh (không phải video) được kiểm tra link và từ khóa")
        void utc057_onlyPhotosAreSentToTheQrChecks() {
            when(media.prepareImage(any())).thenReturn(photo("https://www.moj.go.jp/isa/"));

            service.create(MEMBER, QA, ask(TITLE, CONTENT, List.of(VISA)), List.of(photoFile()), videoFile());

            verify(filter).checkQrCodes(List.of(List.of("https://www.moj.go.jp/isa/")), MEMBER_ID);
            verify(filter).checkQrKeywords(List.of(List.of("https://www.moj.go.jp/isa/")));
        }

        @Test
        @DisplayName("UTC-058 Tài khoản mới đã đăng 3 bài hôm nay, gửi câu hỏi thứ 4: 429 MSG44, không lưu gì")
        void utc058_newAccountFourthQuestionOfTheDayIsRefused() {
            account(MEMBER_ID, "USER", "ACTIVE", NEWBIE_SINCE);
            usageToday(3, 0, 0);

            apiError(() -> askQuestion(ask(TITLE, CONTENT, List.of(VISA))), HttpStatus.TOO_MANY_REQUESTS, MSG44);

            verify(filter, never()).checkKeywords(anyString());
            assertNothingSaved();
            assertRolledBack();
        }

        @Test
        @DisplayName("UTC-059 Tài khoản mới đã đăng 2 bài hôm nay: câu hỏi thứ 3 được nhận và được tính vào hạn mức")
        void utc059_newAccountThirdQuestionIsAccepted() {
            account(MEMBER_ID, "USER", "ACTIVE", NEWBIE_SINCE);
            usageToday(2, 0, 0);

            Created created = askQuestion(ask(TITLE, CONTENT, List.of(VISA)));

            assertThat(created.status()).isEqualTo("PUBLISHED");
            verify(repository).addUsage(MEMBER_ID, TODAY, 1, 0);
        }

        @Test
        @DisplayName("UTC-060 Tài khoản cũ đã đăng 10 bài và 3 video hôm nay vẫn gửi được câu hỏi có video")
        void utc060_veteranHasNoDailyLimit() {
            usageToday(10, 0, 3);

            Created created = service.create(MEMBER, QA, ask(TITLE, CONTENT, List.of(VISA)), List.of(), videoFile());

            assertThat(created.status()).isEqualTo("PROCESSING");
            verify(repository).addUsage(MEMBER_ID, TODAY, 1, 1);
        }

        @Test
        @DisplayName("UTC-061 Tài khoản tạo đúng 7 ngày trước, đã 3 bài hôm nay: không còn giới hạn, câu hỏi được nhận")
        void utc061_sevenDayOldAccountIsNoLongerLimited() {
            account(MEMBER_ID, "USER", "ACTIVE", NOW.minus(Duration.ofDays(7)));
            usageToday(3, 0, 0);

            Created created = askQuestion(ask(TITLE, CONTENT, List.of(VISA)));

            assertThat(created.status()).isEqualTo("PUBLISHED");
        }

        @Test
        @DisplayName("UTC-062 Tài khoản kém 1 giây đủ 7 ngày, đã 3 bài hôm nay: vẫn là tài khoản mới, 429 MSG44")
        void utc062_accountOneSecondShortOfSevenDaysIsLimited() {
            account(MEMBER_ID, "USER", "ACTIVE", NOW.minus(Duration.ofDays(7)).plusSeconds(1));
            usageToday(3, 0, 0);

            apiError(() -> askQuestion(ask(TITLE, CONTENT, List.of(VISA))), HttpStatus.TOO_MANY_REQUESTS, MSG44);
            assertNothingSaved();
        }

        @Test
        @DisplayName("UTC-063 Tài khoản mới đã đăng 1 video hôm nay, gửi câu hỏi có video: 429 giới hạn video")
        void utc063_newAccountSecondVideoOfTheDayIsRefused() {
            account(MEMBER_ID, "USER", "ACTIVE", NEWBIE_SINCE);
            usageToday(1, 0, 1);

            apiError(() -> service.create(MEMBER, QA, ask(TITLE, CONTENT, List.of(VISA)), List.of(), videoFile()),
                    HttpStatus.TOO_MANY_REQUESTS, VIDEO_LIMIT);

            assertNothingSaved();
        }

        @Test
        @DisplayName("UTC-064 Tài khoản mới chưa đăng video hôm nay: câu hỏi có 1 video được nhận, tính 1 video")
        void utc064_newAccountFirstVideoIsAccepted() {
            account(MEMBER_ID, "USER", "ACTIVE", NEWBIE_SINCE);
            usageToday(0, 0, 0);

            Created created = service.create(MEMBER, QA, ask(TITLE, CONTENT, List.of(VISA)), List.of(), videoFile());

            assertThat(created.status()).isEqualTo("PROCESSING");
            verify(repository).addUsage(MEMBER_ID, TODAY, 1, 1);
        }

        @Test
        @DisplayName("UTC-065 Tài khoản mới đã hết lượt video nhưng câu hỏi không có video: vẫn được đăng")
        void utc065_videoLimitIsIgnoredWithoutAVideo() {
            account(MEMBER_ID, "USER", "ACTIVE", NEWBIE_SINCE);
            usageToday(1, 0, 1);

            Created created = askQuestion(ask(TITLE, CONTENT, List.of(VISA)));

            assertThat(created.status()).isEqualTo("PUBLISHED");
        }

        @Test
        @DisplayName("UTC-066 Hết cả lượt bài và lượt video: báo MSG44 (giới hạn bài xét trước giới hạn video)")
        void utc066_postLimitIsCheckedBeforeVideoLimit() {
            account(MEMBER_ID, "USER", "ACTIVE", NEWBIE_SINCE);
            usageToday(3, 0, 1);

            apiError(() -> service.create(MEMBER, QA, ask(TITLE, CONTENT, List.of(VISA)), List.of(), videoFile()),
                    HttpStatus.TOO_MANY_REQUESTS, MSG44);
        }

        @Test
        @DisplayName("UTC-067 Admin đổi cài đặt (14 ngày, 5 bài/ngày): tài khoản 10 ngày đã 5 bài bị từ chối với số mới")
        void utc067_adminSettingsChangeTheDailyLimit() {
            account(MEMBER_ID, "USER", "ACTIVE", NOW.minus(Duration.ofDays(10)));
            when(settings.getInt(eq(SystemSettings.NEW_ACCOUNT_DAYS), anyInt())).thenReturn(14);
            when(settings.getInt(eq(SystemSettings.POSTS_PER_DAY), anyInt())).thenReturn(5);
            usageToday(5, 0, 0);

            apiError(() -> askQuestion(ask(TITLE, CONTENT, List.of(VISA))), HttpStatus.TOO_MANY_REQUESTS,
                    "Bạn đã đạt giới hạn đăng bài trong ngày của tài khoản mới (dưới 14 ngày: tối đa 5 bài viết hoặc"
                            + " review mỗi ngày). Vui lòng thử lại vào ngày mai.");
        }

        @ParameterizedTest(name = "[{index}] {0} → ngày {1}")
        @CsvSource({"2026-11-20T14:59:59Z, 2026-11-20", "2026-11-20T15:00:00Z, 2026-11-21"})
        @DisplayName("UTC-068 Hạn mức trong ngày khóa và cộng theo ngày Nhật Bản (đổi ngày lúc 0:00 giờ Nhật)")
        void utc068_dailyUsageUsesTheJapanDate(String instant, String japanDate) {
            service = serviceAt(Instant.parse(instant));

            askQuestion(ask(TITLE, CONTENT, List.of(VISA)));

            verify(repository).lockUsage(MEMBER_ID, LocalDate.parse(japanDate));
            verify(repository).addUsage(MEMBER_ID, LocalDate.parse(japanDate), 1, 0);
        }

        @Test
        @DisplayName("UTC-069 Từ khóa cấm trong câu hỏi: 400 MSG36 nêu từ khóa, không lưu, không tính hạn mức")
        void utc069_bannedKeywordInTheQuestionIsRefused() {
            String title = "Ai biết chỗ vay nóng uy tín?";
            doThrow(ApiException.badRequest(MSG36)).when(filter).checkKeywords(title);

            apiError(() -> askQuestion(ask(title, "", List.of(OTHER_TOPIC))), HttpStatus.BAD_REQUEST, MSG36);

            assertNothingSaved();
            assertRolledBack();
        }

        @Test
        @DisplayName("UTC-070 Từ khóa cấm trong mô tả: 400 MSG36, không lưu gì")
        void utc070_bannedKeywordInTheDescriptionIsRefused() {
            String content = "Chỗ nào vay nóng uy tín?";
            doThrow(ApiException.badRequest(MSG36)).when(filter).checkKeywords(content);

            apiError(() -> askQuestion(ask(TITLE, content, List.of(OTHER_TOPIC))), HttpStatus.BAD_REQUEST, MSG36);

            verify(filter).checkKeywords(TITLE);
            assertNothingSaved();
        }

        @Test
        @DisplayName("UTC-071 Mã QR trong ảnh chứa từ khóa cấm: 400 dưới \"Ảnh\", không lưu gì")
        void utc071_bannedKeywordInAQrCodeIsRefused() {
            when(media.prepareImage(any())).thenReturn(photo("fb88 khuyến mãi"));
            doThrow(ApiException.invalid(QR_KEYWORD, Map.of("images", QR_KEYWORD))).when(filter)
                    .checkQrKeywords(List.of(List.of("fb88 khuyến mãi")));

            ApiException error = apiError(() -> service.create(MEMBER, QA, ask(TITLE, CONTENT, List.of(VISA)),
                    List.of(photoFile()), null), HttpStatus.BAD_REQUEST, QR_KEYWORD);

            assertThat(error.getFields()).containsOnlyKeys("images");
            assertNothingSaved();
        }

        @Test
        @DisplayName("UTC-072 Thứ tự kiểm tra: link (câu hỏi, mô tả, mã QR) → giới hạn trong ngày → từ khóa cấm → lưu")
        void utc072_checksRunInTheDocumentedOrder() {
            askQuestion(ask(TITLE, CONTENT, List.of(VISA)));

            InOrder order = inOrder(filter, repository, places);
            order.verify(filter).checkLinks(TITLE, MEMBER_ID);
            order.verify(filter).checkLinks(CONTENT, MEMBER_ID);
            order.verify(filter).checkQrCodes(List.of(), MEMBER_ID);
            order.verify(repository).lockUsage(MEMBER_ID, TODAY);
            order.verify(filter).checkKeywords(TITLE);
            order.verify(filter).checkKeywords(CONTENT);
            order.verify(filter).checkQrKeywords(List.of());
            order.verify(places).save(any(), eq(MEMBER_ID));
            order.verify(repository).insertBlog(MEMBER_ID, "QA", TITLE, CONTENT, "PUBLISHED", NOW);
            order.verify(repository).addUsage(MEMBER_ID, TODAY, 1, 0);
        }

        @Test
        @DisplayName("UTC-073 Lỗi trường thông tin báo trước mọi quy tắc cộng đồng: thiếu câu hỏi + link + từ khóa → \"Hãy nhập câu hỏi.\"")
        void utc073_fieldErrorsComeBeforeEveryCommunityRule() {
            apiError(() -> askQuestion(ask("", "Xem https://example.com/a vay nóng", List.of(OTHER_TOPIC))),
                    HttpStatus.BAD_REQUEST, TITLE_REQUIRED);

            verifyNoInteractions(filter);
            assertNoTransaction();
        }

        @Test
        @DisplayName("UTC-074 Link báo trước giới hạn trong ngày và từ khóa cấm (cả ba cùng vi phạm)")
        void utc074_linkIsReportedBeforeLimitAndKeyword() {
            account(MEMBER_ID, "USER", "ACTIVE", NEWBIE_SINCE);
            usageToday(3, 0, 0);
            String content = "Xem https://example.com/a";
            doThrow(ApiException.badRequest(LINK_NEEDS_TAG)).when(filter).checkLinks(content, MEMBER_ID);
            doThrow(ApiException.badRequest(MSG36)).when(filter).checkKeywords(anyString());

            apiError(() -> askQuestion(ask("Hỏi vay nóng", content, List.of(OTHER_TOPIC))), HttpStatus.BAD_REQUEST,
                    LINK_NEEDS_TAG);

            verify(repository, never()).lockUsage(anyLong(), any());
            verify(filter, never()).checkKeywords(anyString());
        }

        @Test
        @DisplayName("UTC-075 Giới hạn trong ngày báo trước từ khóa cấm (cả hai cùng vi phạm): 429 MSG44")
        void utc075_dailyLimitIsReportedBeforeKeyword() {
            account(MEMBER_ID, "USER", "ACTIVE", NEWBIE_SINCE);
            usageToday(3, 0, 0);
            doThrow(ApiException.badRequest(MSG36)).when(filter).checkKeywords(anyString());

            apiError(() -> askQuestion(ask("Hỏi vay nóng", "", List.of(OTHER_TOPIC))), HttpStatus.TOO_MANY_REQUESTS,
                    MSG44);

            verify(filter, never()).checkKeywords(anyString());
        }
    }

    // =================================================================================================================
    @Nested
    @TestMethodOrder(MethodOrderer.DisplayName.class)
    @DisplayName("06. create – UC 1.14 Đặt câu hỏi: lưu câu hỏi, điểm, hạn mức (UTC-076 → UTC-083)")
    class CreateSave {

        @Test
        @DisplayName("UTC-076 Chủ đề và địa điểm được gắn vào câu hỏi theo thứ tự đã chọn")
        void utc076_topicsAndPlacesAreLinked() {
            service.create(MEMBER, QA, new CreateRequest(TITLE, CONTENT, List.of(PAPERS, VISA), List.of(22L, 21L),
                    List.of()), List.of(), null);

            verify(repository).linkTopics(QUESTION_ID, List.of(PAPERS, VISA));
            verify(repository).linkPlaces(QUESTION_ID, List.of(22L, 21L));
        }

        @Test
        @DisplayName("UTC-077 Địa điểm mới từ OpenStreetMap được thêm vào Senpai ơi và gắn sau các địa điểm đã có")
        void utc077_newMapPlaceIsAddedAndLinked() {
            PlacePicks picks = new PlacePicks(List.of(88L), List.of(), null);
            when(places.resolve(MEMBER_ID, List.of("N772389775"))).thenReturn(picks);
            when(places.save(picks, MEMBER_ID)).thenReturn(List.of(88L));

            askQuestion(askAt(List.of(21L), List.of("N772389775")));

            verify(places).resolve(MEMBER_ID, List.of("N772389775"));
            verify(places).save(picks, MEMBER_ID);
            verify(repository).linkPlaces(QUESTION_ID, List.of(21L, 88L));
        }

        @Test
        @DisplayName("UTC-078 Địa điểm OpenStreetMap trùng một địa điểm đã chọn chỉ được gắn một lần")
        void utc078_samePlaceChosenTwiceIsLinkedOnce() {
            PlacePicks picks = new PlacePicks(List.of(21L), List.of(), null);
            when(places.resolve(MEMBER_ID, List.of("N772389775"))).thenReturn(picks);
            when(places.save(picks, MEMBER_ID)).thenReturn(List.of(21L));

            askQuestion(askAt(List.of(21L), List.of("N772389775")));

            verify(repository).linkPlaces(QUESTION_ID, List.of(21L));
        }

        @Test
        @DisplayName("UTC-079 Địa điểm bị Moderator gỡ trong lúc gửi: 400 \"Có địa điểm không còn trên bản đồ…\", không lưu câu hỏi")
        void utc079_placeRemovedMeanwhileStopsTheSave() {
            when(repository.countActivePlaces(List.of(21L, 22L))).thenReturn(2, 1);

            ApiException error = apiError(() -> askQuestion(askAt(List.of(21L, 22L), List.of())),
                    HttpStatus.BAD_REQUEST, PLACE_GONE);

            assertThat(error.getFields()).containsExactlyEntriesOf(Map.of("placeIds", PLACE_GONE));
            verify(repository, never()).insertBlog(anyLong(), any(), any(), any(), any(), any());
            assertRolledBack();
        }

        @Test
        @DisplayName("UTC-080 Câu hỏi được đăng ngay thì được cộng điểm (+10, trả một lần) và không chờ quét")
        void utc080_publishedQuestionEarnsItsPoints() {
            askQuestion(ask(TITLE, CONTENT, List.of(VISA)));

            verify(publisher).awardOnce(MEMBER_ID, QUESTION_ID);
            verify(publisher, never()).scanSoon(anyLong());
            assertThat(TransactionSynchronizationManager.getSynchronizations()).isEmpty();
            assertThat(PointService.BLOG_POST).isEqualTo(10);
        }

        @Test
        @DisplayName("UTC-081 Câu hỏi có ảnh chưa được cộng điểm, kể cả sau khi commit (điểm trả khi quét xong)")
        void utc081_questionWithMediaEarnsNoPointsYet() {
            service.create(MEMBER, QA, ask(TITLE, CONTENT, List.of(VISA)), List.of(photoFile()), null);
            commit();

            verify(publisher, never()).awardOnce(anyLong(), anyLong());
        }

        @Test
        @DisplayName("UTC-082 Câu hỏi được tính chung với bài viết vào hạn mức hôm nay (kể cả tài khoản cũ)")
        void utc082_questionCountsTowardTheDailyUsage() {
            askQuestion(ask(TITLE, CONTENT, List.of(VISA)));

            verify(repository).lockUsage(MEMBER_ID, TODAY);
            verify(repository).addUsage(MEMBER_ID, TODAY, 1, 0);
        }

        @Test
        @DisplayName("UTC-083 Lưu thành công: giao dịch được commit đúng một lần")
        void utc083_successfulSaveCommitsOnce() {
            askQuestion(ask(TITLE, CONTENT, List.of(VISA)));

            verify(txManager).getTransaction(any());
            verify(txManager).commit(any());
            verify(txManager, never()).rollback(any());
        }
    }

    // =================================================================================================================
    @Nested
    @TestMethodOrder(MethodOrderer.DisplayName.class)
    @DisplayName("07. BlogPublishService.process – UC 1.14 Luồng 8a: câu hỏi có ảnh/video sau khi quét (UTC-084 → UTC-091)")
    class PublishQuestionAfterScan {

        private static final String SCAN_TITLE = "Ảnh thẻ cư trú này có đúng không?";
        private BlogPublishService publish;

        @BeforeEach
        void publishService() {
            publish = new BlogPublishService(repository, scanner, media, filter, points, notifications, txManager,
                    Clock.fixed(NOW, ZoneOffset.UTC));
            waiting(pending("PROCESSING"));
            when(repository.setStatus(QUESTION_ID, "PROCESSING", "PUBLISHED")).thenReturn(true);
        }

        private PendingBlog pending(String status) {
            return new PendingBlog(QUESTION_ID, MEMBER_ID, "QA", SCAN_TITLE, "Mình gửi ảnh mẫu.", status);
        }

        private void waiting(PendingBlog blog) {
            when(repository.pendingBlog(QUESTION_ID)).thenReturn(Optional.of(blog));
            when(repository.lockBlog(QUESTION_ID)).thenReturn(Optional.of(blog));
        }

        @Test
        @DisplayName("UTC-084 Mọi ảnh qua kiểm duyệt: câu hỏi được đăng, +10 điểm, thông báo \"Câu hỏi … đã được đăng. +10 điểm.\" (ref QUESTION)")
        void utc084_passedQuestionIsPublishedWithPointsAndNotice() {
            when(repository.mediaStates(QUESTION_ID)).thenReturn(List.of(new MediaState(501L, "PASSED")));

            publish.process(QUESTION_ID);

            verify(repository).setStatus(QUESTION_ID, "PROCESSING", "PUBLISHED");
            verify(points).add(MEMBER_ID, 10, "BLOG_CREATED", "BLOG", QUESTION_ID);
            verify(notifications).send(MEMBER_ID, NotificationService.CONTENT_PUBLISHED, "Câu hỏi “" + SCAN_TITLE
                    + "” đã qua kiểm duyệt ảnh/video và đã được đăng. +10 điểm.", "QUESTION", QUESTION_ID);
        }

        @Test
        @DisplayName("UTC-085 Chỉ các tệp còn chờ (PROCESSING) được gửi đi quét")
        void utc085_onlyWaitingFilesAreScanned() {
            when(repository.mediaStates(QUESTION_ID))
                    .thenReturn(List.of(new MediaState(501L, "PROCESSING"), new MediaState(502L, "PASSED")))
                    .thenReturn(List.of(new MediaState(501L, "PASSED"), new MediaState(502L, "PASSED")));

            publish.process(QUESTION_ID);

            verify(scanner).scan(501L);
            verify(scanner, never()).scan(502L);
            verify(repository).setStatus(QUESTION_ID, "PROCESSING", "PUBLISHED");
        }

        @Test
        @DisplayName("UTC-086 Ảnh không qua kiểm duyệt: câu hỏi bị từ chối, ảnh bị xóa, người hỏi được báo lý do, không cộng điểm")
        void utc086_flaggedPhotoRejectsTheQuestion() {
            when(repository.mediaStates(QUESTION_ID))
                    .thenReturn(List.of(new MediaState(501L, "REJECTED"), new MediaState(502L, "PASSED")));
            when(scanner.rejectionReason(List.of(501L))).thenReturn(Optional.of("ảnh có nội dung khiêu dâm"));

            publish.process(QUESTION_ID);

            verify(repository).setStatus(QUESTION_ID, "PROCESSING", "REJECTED");
            verify(media).discard(List.of(501L));
            verify(notifications).send(MEMBER_ID, NotificationService.CONTENT_REJECTED, "Câu hỏi “" + SCAN_TITLE
                    + "” chưa được đăng vì ảnh có nội dung khiêu dâm. Bạn có thể sửa bài rồi gửi lại.", "QUESTION",
                    QUESTION_ID);
            verify(points, never()).add(anyLong(), anyInt(), any(), any(), any());
        }

        @Test
        @DisplayName("UTC-087 Bị từ chối mà không có lý do lưu lại: thông báo nêu \"vi phạm Quy tắc cộng đồng\"")
        void utc087_rejectionWithoutReasonNamesTheCommunityRules() {
            when(repository.mediaStates(QUESTION_ID)).thenReturn(List.of(new MediaState(501L, "REJECTED")));

            publish.process(QUESTION_ID);

            verify(notifications).send(MEMBER_ID, NotificationService.CONTENT_REJECTED, "Câu hỏi “" + SCAN_TITLE
                    + "” chưa được đăng vì ảnh hoặc video vi phạm Quy tắc cộng đồng. Bạn có thể sửa bài rồi gửi lại.",
                    "QUESTION", QUESTION_ID);
        }

        @Test
        @DisplayName("UTC-088 Admin thêm từ khóa cấm sau khi gửi: câu hỏi bị từ chối, không quét ảnh, thông báo nêu từ khóa")
        void utc088_keywordAddedMeanwhileRejectsWithoutScanning() {
            when(filter.bannedKeyword(SCAN_TITLE)).thenReturn(Optional.of("cờ bạc"));
            when(repository.mediaStates(QUESTION_ID)).thenReturn(List.of(new MediaState(501L, "PROCESSING")));

            publish.process(QUESTION_ID);

            verify(scanner, never()).scan(anyLong());
            verify(repository).setStatus(QUESTION_ID, "PROCESSING", "REJECTED");
            verify(notifications).send(MEMBER_ID, NotificationService.CONTENT_REJECTED, "Câu hỏi “" + SCAN_TITLE
                    + "” chưa được đăng vì có từ khóa không được phép “cờ bạc”. Bạn có thể sửa bài rồi gửi lại.",
                    "QUESTION", QUESTION_ID);
            verify(media, never()).discard(any());
        }

        @Test
        @DisplayName("UTC-089 Máy quét chưa trả lời (ảnh vẫn PROCESSING): câu hỏi tiếp tục chờ, không thông báo, không cộng điểm")
        void utc089_scannerWithoutAnswerKeepsTheQuestionWaiting() {
            when(repository.mediaStates(QUESTION_ID)).thenReturn(List.of(new MediaState(501L, "PROCESSING")));

            publish.process(QUESTION_ID);

            verify(scanner).scan(501L);
            verify(repository, never()).setStatus(anyLong(), any(), any());
            verifyNoInteractions(notifications, points);
        }

        @ParameterizedTest(name = "[{index}] câu hỏi {0}")
        @ValueSource(strings = {"DELETED", "PUBLISHED", "REJECTED"})
        @DisplayName("UTC-090 Câu hỏi đã bị xóa hoặc không còn chờ quét: không quét, không đổi trạng thái, không thông báo")
        void utc090_questionNoLongerWaitingIsLeftAlone(String state) {
            when(repository.pendingBlog(QUESTION_ID))
                    .thenReturn("DELETED".equals(state) ? Optional.empty() : Optional.of(pending(state)));

            publish.process(QUESTION_ID);

            verifyNoInteractions(scanner, notifications, points);
            verify(repository, never()).setStatus(anyLong(), any(), any());
        }

        @Test
        @DisplayName("UTC-091 Câu hỏi bị xóa trong lúc quét (khóa dòng không còn thấy): không đổi gì, không thông báo")
        void utc091_questionDeletedDuringTheScanIsLeftAlone() {
            when(repository.mediaStates(QUESTION_ID)).thenReturn(List.of(new MediaState(501L, "PASSED")));
            when(repository.lockBlog(QUESTION_ID)).thenReturn(Optional.empty());

            publish.process(QUESTION_ID);

            verify(repository, never()).setStatus(anyLong(), any(), any());
            verifyNoInteractions(notifications, points);
        }
    }

    // =================================================================================================================
    @Nested
    @TestMethodOrder(MethodOrderer.DisplayName.class)
    @DisplayName("08. QaController.compose / ask / askJson – UC 1.14 câu trả lời của API (UTC-092 → UTC-096)")
    class AskController {

        private BlogWriteService writeService;
        private QaController controller;

        @BeforeEach
        void controller() {
            writeService = mock(BlogWriteService.class);
            controller = new QaController(mock(QaService.class), mock(BlogDetailService.class),
                    mock(BlogActionService.class), writeService);
        }

        @Test
        @DisplayName("UTC-092 Câu hỏi được đăng ngay: \"Câu hỏi của bạn đã được đăng.\" (MSG34), gửi với loại QA")
        void utc092_publishedQuestionAnswersMsg34() {
            CreateRequest request = ask(TITLE, CONTENT, List.of(VISA));
            Created created = new Created(QUESTION_ID, "QA", "PUBLISHED");
            when(writeService.create(eq(MEMBER), eq("QA"), eq(request), isNull(), isNull())).thenReturn(created);

            ApiResponse<Created> response = controller.ask(request, null, null, MEMBER);

            assertThat(response.success()).isTrue();
            assertThat(response.message()).isEqualTo(MSG34_QUESTION);
            assertThat(response.data()).isEqualTo(created);
        }

        @Test
        @DisplayName("UTC-093 Câu hỏi có ảnh chờ quét: \"Đã gửi. Ảnh và video đang được quét, trong lúc này chỉ bạn thấy câu hỏi.\"")
        void utc093_questionWithFilesAnswersScanning() {
            CreateRequest request = ask(TITLE, CONTENT, List.of(VISA));
            List<MultipartFile> images = List.of(photoFile());
            when(writeService.create(MEMBER, "QA", request, images, null))
                    .thenReturn(new Created(QUESTION_ID, "QA", "PROCESSING"));

            ApiResponse<Created> response = controller.ask(request, images, null, MEMBER);

            assertThat(response.message()).isEqualTo(SENT_FOR_SCAN);
            assertThat(response.data().status()).isEqualTo("PROCESSING");
        }

        @Test
        @DisplayName("UTC-094 Gửi bằng JSON (không có tệp): dịch vụ nhận danh sách ảnh rỗng và không có video")
        void utc094_jsonQuestionSendsNoFiles() {
            CreateRequest request = ask(TITLE, CONTENT, List.of(VISA));
            when(writeService.create(any(), any(), any(), any(), any()))
                    .thenReturn(new Created(QUESTION_ID, "QA", "PUBLISHED"));

            ApiResponse<Created> response = controller.askJson(request, MEMBER);

            verify(writeService).create(MEMBER, "QA", request, List.of(), null);
            assertThat(response.message()).isEqualTo(MSG34_QUESTION);
        }

        @Test
        @DisplayName("UTC-095 Form \"Đặt câu hỏi\" dùng chung dữ liệu form với \"Viết bài\"")
        void utc095_composeReturnsTheSharedForm() {
            ComposeOptions options = new ComposeOptions(TOPICS, null, null, false);
            when(writeService.compose(MEMBER)).thenReturn(options);

            ApiResponse<ComposeOptions> response = controller.compose(MEMBER);

            assertThat(response.message()).isEqualTo("OK");
            assertThat(response.data()).isSameAs(options);
        }

        @Test
        @DisplayName("UTC-096 Lỗi của dịch vụ (vd. 429 MSG44) được trả nguyên, không có câu trả lời thành công")
        void utc096_serviceErrorPassesThrough() {
            CreateRequest request = ask(TITLE, CONTENT, List.of(VISA));
            when(writeService.create(any(), any(), any(), any(), any()))
                    .thenThrow(ApiException.tooManyRequests(MSG44));

            apiError(() -> controller.askJson(request, MEMBER), HttpStatus.TOO_MANY_REQUESTS, MSG44);
        }
    }

    // =================================================================================================================
    @Nested
    @TestMethodOrder(MethodOrderer.DisplayName.class)
    @DisplayName("09. editData – UC 1.15 Mở form \"Sửa câu hỏi\" (UTC-097 → UTC-109)")
    class EditForm {

        @Test
        @DisplayName("UTC-097 Người hỏi mở câu hỏi của mình: form có sẵn câu hỏi, mô tả, chủ đề, địa điểm và ảnh hiện tại")
        void utc097_askerOpensOwnQuestion() {
            editable("PUBLISHED");
            when(repository.topicIds(QUESTION_ID)).thenReturn(List.of(VISA, HOUSING));
            PlaceRef nippori = new PlaceRef(21L, "Ga Nippori", "Nhà ga", "Arakawa-ku, Tokyo", 35.7281, 139.7707,
                    "N772389775");
            when(feedRepository.placesOf(List.of(QUESTION_ID))).thenReturn(Map.of(QUESTION_ID, List.of(nippori)));
            attached(image(401L, "PASSED", 0));

            EditData data = service.editData(MEMBER, QA, QUESTION_ID);

            assertThat(data.id()).isEqualTo(QUESTION_ID);
            assertThat(data.type()).isEqualTo("QA");
            assertThat(data.status()).isEqualTo("PUBLISHED");
            assertThat(data.title()).isEqualTo("Hỏi về nhà ở Arakawa");
            assertThat(data.content()).isEqualTo("Mô tả ban đầu");
            assertThat(data.topicIds()).containsExactly(VISA, HOUSING);
            assertThat(data.places()).containsExactly(nippori);
            assertThat(data.media()).containsExactly(
                    new EditMedia(401L, "IMAGE", "/media/blog/2026/11/401.jpg", "PASSED", 0));
        }

        @Test
        @DisplayName("UTC-098 Form sửa có cùng lựa chọn như form đặt câu hỏi: chủ đề, giới hạn, hạn mức, quyền chèn link")
        void utc098_editFormCarriesTheComposeOptions() {
            editable("PUBLISHED");
            account(MEMBER_ID, "USER", "ACTIVE", NEWBIE_SINCE);
            usageToday(1, 0, 0);
            when(filter.hasIdentityTag(MEMBER_ID)).thenReturn(true);

            ComposeOptions options = service.editData(MEMBER, QA, QUESTION_ID).options();

            assertThat(options.topics()).containsExactlyElementsOf(TOPICS);
            assertThat(options.limits()).isEqualTo(new Limits(150, 5000, 3, 5, 5, 20, 1, 30, 50, false));
            assertThat(options.quota()).isEqualTo(new Quota(true, 7, 2, 1, 3, 0, 2, 0, 1));
            assertThat(options.canPostLinks()).isTrue();
        }

        @ParameterizedTest(name = "[{index}] trạng thái {0}")
        @ValueSource(strings = {"PROCESSING", "REJECTED"})
        @DisplayName("UTC-099 Câu hỏi đang chờ quét hoặc bị từ chối vẫn mở được form, kèm trạng thái để trang chọn ghi chú")
        void utc099_waitingOrRejectedQuestionOpensWithItsStatus(String status) {
            editable(status);

            assertThat(service.editData(MEMBER, QA, QUESTION_ID).status()).isEqualTo(status);
        }

        @ParameterizedTest(name = "[{index}] trạng thái {0}")
        @ValueSource(strings = {"HIDDEN", "REMOVED"})
        @DisplayName("UTC-100 Câu hỏi bị ẩn do báo cáo hoặc bị Moderator gỡ: 404 MSG19")
        void utc100_hiddenOrRemovedQuestionIsNotFound(String status) {
            editable(status);

            apiError(() -> service.editData(MEMBER, QA, QUESTION_ID), HttpStatus.NOT_FOUND, MSG19);
            verify(repository, never()).attachedMedia(anyLong());
        }

        @Test
        @DisplayName("UTC-101 Câu hỏi không tồn tại (đã xóa): 404 MSG19")
        void utc101_missingQuestionIsNotFound() {
            when(repository.editableBlog(eq(QUESTION_ID), anyBoolean())).thenReturn(Optional.empty());

            apiError(() -> service.editData(MEMBER, QA, QUESTION_ID), HttpStatus.NOT_FOUND, MSG19);
        }

        @Test
        @DisplayName("UTC-102 Id của một bài viết Blog không mở được bằng \"Sửa câu hỏi\": 404 MSG19")
        void utc102_postIdIsNotAQuestion() {
            editable(MEMBER_ID, "POST", "PUBLISHED");

            apiError(() -> service.editData(MEMBER, QA, QUESTION_ID), HttpStatus.NOT_FOUND, MSG19);
        }

        @Test
        @DisplayName("UTC-103 Câu hỏi của người khác: 403 MSG18, không đọc ảnh, chủ đề")
        void utc103_someoneElsesQuestionIsForbidden() {
            editable(OTHER_ID, QA, "PUBLISHED");

            apiError(() -> service.editData(MEMBER, QA, QUESTION_ID), HttpStatus.FORBIDDEN, MSG18);

            verify(repository, never()).attachedMedia(anyLong());
            verify(repository, never()).topicIds(anyLong());
        }

        @Test
        @DisplayName("UTC-104 Câu hỏi của người khác đang bị ẩn: báo 404 MSG19 (không lộ là có câu hỏi)")
        void utc104_notFoundComesBeforeForbidden() {
            editable(OTHER_ID, QA, "HIDDEN");

            apiError(() -> service.editData(MEMBER, QA, QUESTION_ID), HttpStatus.NOT_FOUND, MSG19);
        }

        @Test
        @DisplayName("UTC-105 Mở form chỉ đọc câu hỏi, không khóa dòng, không ghi gì")
        void utc105_openingTheFormLocksAndWritesNothing() {
            editable("PUBLISHED");

            service.editData(MEMBER, QA, QUESTION_ID);

            verify(repository).editableBlog(QUESTION_ID, false);
            verify(repository, never()).editableBlog(QUESTION_ID, true);
            assertEditNotSaved();
            assertNoTransaction();
        }

        @Test
        @DisplayName("UTC-106 Câu hỏi không gắn địa điểm và không có ảnh: danh sách rỗng")
        void utc106_questionWithoutPlacesOrMedia() {
            editable("PUBLISHED");

            EditData data = service.editData(MEMBER, QA, QUESTION_ID);

            assertThat(data.places()).isEmpty();
            assertThat(data.media()).isEmpty();
        }

        @Test
        @DisplayName("UTC-107 Đường dẫn ảnh theo địa chỉ media đã cấu hình; ảnh còn đang quét giữ trạng thái PROCESSING")
        void utc107_mediaUrlUsesTheConfiguredBase() {
            service = serviceAt(NOW, "https://cdn.senpaioi.example/m/");
            editable("PROCESSING");
            attached(image(401L, "PASSED", 0), video(402L, "PROCESSING", 1));

            List<EditMedia> files = service.editData(MEMBER, QA, QUESTION_ID).media();

            assertThat(files).containsExactly(
                    new EditMedia(401L, "IMAGE", "https://cdn.senpaioi.example/m/blog/2026/11/401.jpg", "PASSED", 0),
                    new EditMedia(402L, "VIDEO", "https://cdn.senpaioi.example/m/blog/2026/11/402.mp4", "PROCESSING",
                            1));
        }

        @Test
        @DisplayName("UTC-108 Khách mở form sửa: 401 \"Bạn cần đăng nhập để viết bài.\"")
        void utc108_guestMustSignIn() {
            apiError(() -> service.editData(null, QA, QUESTION_ID), HttpStatus.UNAUTHORIZED, LOGIN_TO_WRITE);

            verifyNoInteractions(repository);
        }

        @ParameterizedTest(name = "[{index}] vai trò {0}, trạng thái {1}")
        @CsvSource({"MODERATOR, ACTIVE", "ACADEMIC_MANAGER, ACTIVE", "ADMIN, ACTIVE", "USER, LOCKED"})
        @DisplayName("UTC-109 Nhân viên hoặc tài khoản bị khóa mở form sửa: 403 MSG18")
        void utc109_staffOrLockedCannotOpenTheEditForm(String role, String status) {
            account(MEMBER_ID, role, status, VETERAN_SINCE);
            editable("PUBLISHED");

            apiError(() -> service.editData(principal(MEMBER_ID, role), QA, QUESTION_ID), HttpStatus.FORBIDDEN, MSG18);

            verify(repository, never()).editableBlog(anyLong(), anyBoolean());
        }
    }

    // =================================================================================================================
    @Nested
    @TestMethodOrder(MethodOrderer.DisplayName.class)
    @DisplayName("10. update – UC 1.15 Cập nhật câu hỏi: phân quyền và trạng thái (UTC-110 → UTC-117)")
    class UpdateAccess {

        @Test
        @DisplayName("UTC-110 Khách lưu câu hỏi: 401 \"Bạn cần đăng nhập để viết bài.\", không đọc, không lưu gì")
        void utc110_guestMustSignIn() {
            apiError(() -> service.update(null, QA, QUESTION_ID, editKeeping(List.of()), List.of(), null),
                    HttpStatus.UNAUTHORIZED, LOGIN_TO_WRITE);

            verifyNoInteractions(repository, filter, media, publisher, txManager);
        }

        @ParameterizedTest(name = "[{index}] vai trò {0}")
        @ValueSource(strings = {"MODERATOR", "ACADEMIC_MANAGER", "ADMIN"})
        @DisplayName("UTC-111 Nhân viên lưu câu hỏi: 403 MSG18, câu hỏi không đổi")
        void utc111_staffCannotEdit(String role) {
            account(STAFF_ID, role, "ACTIVE", VETERAN_SINCE);
            editable("PUBLISHED");

            apiError(() -> service.update(principal(STAFF_ID, role), QA, QUESTION_ID, editKeeping(List.of()), List.of(),
                    null), HttpStatus.FORBIDDEN, MSG18);

            assertEditNotSaved();
        }

        @Test
        @DisplayName("UTC-112 Tài khoản bị khóa lưu câu hỏi: 403 MSG18")
        void utc112_lockedAccountCannotEdit() {
            account(MEMBER_ID, "USER", "LOCKED", VETERAN_SINCE);
            editable("PUBLISHED");

            apiError(() -> editQuestion(editKeeping(List.of())), HttpStatus.FORBIDDEN, MSG18);

            assertEditNotSaved();
        }

        @Test
        @DisplayName("UTC-113 Người không phải người hỏi lưu: 403 MSG18 (trước cả lỗi trường), câu hỏi không đổi")
        void utc113_someoneElsesQuestionIsForbidden() {
            editable(OTHER_ID, QA, "PUBLISHED");

            apiError(() -> editQuestion(edit("", "", List.of(), List.of())), HttpStatus.FORBIDDEN, MSG18);

            verify(repository, never()).attachedMedia(anyLong());
            assertEditNotSaved();
            assertNoTransaction();
        }

        @ParameterizedTest(name = "[{index}] câu hỏi {0}")
        @ValueSource(strings = {"HIDDEN", "REMOVED", "DELETED"})
        @DisplayName("UTC-114 Câu hỏi bị ẩn, bị gỡ hoặc đã xóa: 404 MSG19, không lưu gì")
        void utc114_hiddenRemovedOrDeletedQuestionIsNotFound(String state) {
            if ("DELETED".equals(state)) {
                when(repository.editableBlog(eq(QUESTION_ID), anyBoolean())).thenReturn(Optional.empty());
            } else {
                editable(state);
            }

            apiError(() -> editQuestion(editKeeping(List.of())), HttpStatus.NOT_FOUND, MSG19);

            assertEditNotSaved();
        }

        @Test
        @DisplayName("UTC-115 Id của một bài viết Blog không sửa được qua \"Sửa câu hỏi\": 404 MSG19")
        void utc115_postIdIsNotFound() {
            editable(MEMBER_ID, "POST", "PUBLISHED");

            apiError(() -> editQuestion(editKeeping(List.of())), HttpStatus.NOT_FOUND, MSG19);

            assertEditNotSaved();
        }

        @Test
        @DisplayName("UTC-116 Câu hỏi bị ẩn do báo cáo đúng lúc đang lưu: 404 MSG19, giao dịch bị hủy, không lưu gì")
        void utc116_questionHiddenWhileSavingIsNotFound() {
            EditableBlog published = new EditableBlog(QUESTION_ID, MEMBER_ID, QA, "PUBLISHED", "Hỏi cũ", "Mô tả cũ");
            EditableBlog hidden = new EditableBlog(QUESTION_ID, MEMBER_ID, QA, "HIDDEN", "Hỏi cũ", "Mô tả cũ");
            when(repository.editableBlog(QUESTION_ID, false)).thenReturn(Optional.of(published));
            when(repository.editableBlog(QUESTION_ID, true)).thenReturn(Optional.of(hidden));

            apiError(() -> editQuestion(editKeeping(List.of())), HttpStatus.NOT_FOUND, MSG19);

            assertEditNotSaved();
            assertRolledBack();
        }

        @Test
        @DisplayName("UTC-117 Lưu khóa dòng câu hỏi (FOR UPDATE) trước mọi kiểm tra quy tắc cộng đồng")
        void utc117_questionRowIsLockedBeforeTheChecks() {
            editable("PUBLISHED");

            editQuestion(editKeeping(List.of()));

            InOrder order = inOrder(repository, filter);
            order.verify(repository).editableBlog(QUESTION_ID, false);
            order.verify(repository).editableBlog(QUESTION_ID, true);
            order.verify(filter).checkLinks(NEW_TITLE, MEMBER_ID);
            order.verify(repository).updateBlog(QUESTION_ID, NEW_TITLE, NEW_CONTENT, "PUBLISHED", NOW);
        }
    }

    // =================================================================================================================
    @Nested
    @TestMethodOrder(MethodOrderer.DisplayName.class)
    @DisplayName("11. update – UC 1.15 Cập nhật câu hỏi: trường thông tin và quy tắc cộng đồng (UTC-118 → UTC-133)")
    class UpdateFields {

        @BeforeEach
        void ownPublishedQuestion() {
            editable("PUBLISHED");
        }

        @Test
        @DisplayName("UTC-118 Sửa câu hỏi và mô tả: lưu, đánh dấu \"đã sửa\", vẫn PUBLISHED, không quét")
        void utc118_textChangeIsSaved() {
            Created saved = editQuestion(editKeeping(List.of()));

            assertThat(saved).isEqualTo(new Created(QUESTION_ID, "QA", "PUBLISHED"));
            verify(repository).updateBlog(QUESTION_ID, NEW_TITLE, NEW_CONTENT, "PUBLISHED", NOW);
            verify(publisher, never()).scanSoon(anyLong());
            assertThat(TransactionSynchronizationManager.getSynchronizations()).isEmpty();
        }

        @Test
        @DisplayName("UTC-119 Chủ đề và địa điểm được thay: gỡ liên kết cũ rồi gắn liên kết mới")
        void utc119_topicsAndPlacesAreReplaced() {
            editQuestion(new UpdateRequest(NEW_TITLE, NEW_CONTENT, List.of(PAPERS, HEALTH), List.of(22L), List.of(),
                    List.of()));

            InOrder order = inOrder(repository);
            order.verify(repository).unlinkTopics(QUESTION_ID);
            order.verify(repository).linkTopics(QUESTION_ID, List.of(PAPERS, HEALTH));
            order.verify(repository).unlinkPlaces(QUESTION_ID);
            order.verify(repository).linkPlaces(QUESTION_ID, List.of(22L));
        }

        @Test
        @DisplayName("UTC-120 Câu hỏi đã sửa vẫn gọi trả điểm một lần (không trả lần hai nếu đã trả)")
        void utc120_publishedEditCallsAwardOnce() {
            editQuestion(editKeeping(List.of()));

            verify(publisher).awardOnce(MEMBER_ID, QUESTION_ID);
        }

        @Test
        @DisplayName("UTC-121 Xóa hết mô tả khi sửa: được nhận (mô tả không bắt buộc)")
        void utc121_emptiedDescriptionIsAccepted() {
            editQuestion(edit(NEW_TITLE, "   ", List.of(VISA), List.of()));

            verify(repository).updateBlog(QUESTION_ID, NEW_TITLE, "", "PUBLISHED", NOW);
        }

        @Test
        @DisplayName("UTC-122 Nhiều trường sai (câu hỏi rỗng, 4 chủ đề, mô tả 5.001 ký tự): báo từng trường, không lưu, không mở giao dịch")
        void utc122_invalidFieldsAreRefused() {
            ApiException error = apiError(() -> editQuestion(edit("", "a".repeat(5001),
                    List.of(VISA, PAPERS, HOUSING, HEALTH), List.of())), HttpStatus.BAD_REQUEST, TITLE_REQUIRED);

            assertThat(error.getFields()).containsOnly(
                    Map.entry("title", TITLE_REQUIRED),
                    Map.entry("topicIds", TOPICS_TOO_MANY),
                    Map.entry("content", DESCRIPTION_TOO_LONG));
            assertEditNotSaved();
            assertNoTransaction();
        }

        @Test
        @DisplayName("UTC-123 Sửa câu hỏi thành đúng 150 ký tự: được nhận")
        void utc123_titleOf150CharactersIsAcceptedOnEdit() {
            String title150 = "Hỏi " + "a".repeat(146);

            Created saved = editQuestion(edit(title150, "", List.of(OTHER_TOPIC), List.of()));

            assertThat(saved.status()).isEqualTo("PUBLISHED");
            verify(repository).updateBlog(QUESTION_ID, title150, "", "PUBLISHED", NOW);
        }

        @Test
        @DisplayName("UTC-124 Sửa câu hỏi thành 151 ký tự: 400 \"Câu hỏi tối đa 150 ký tự.\"")
        void utc124_titleOf151CharactersIsRefusedOnEdit() {
            ApiException error = apiError(() -> editQuestion(edit("Hỏi " + "a".repeat(147), "", List.of(OTHER_TOPIC),
                    List.of())), HttpStatus.BAD_REQUEST, TITLE_TOO_LONG);

            assertThat(error.getFields()).containsExactlyEntriesOf(Map.of("title", TITLE_TOO_LONG));
            assertEditNotSaved();
        }

        @Test
        @DisplayName("UTC-125 Bỏ hết chủ đề khi sửa: 400 \"Hãy chọn ít nhất 1 chủ đề…\"")
        void utc125_noTopicIsRefusedOnEdit() {
            ApiException error = apiError(() -> editQuestion(edit(NEW_TITLE, "", List.of(), List.of())),
                    HttpStatus.BAD_REQUEST, TOPIC_REQUIRED);

            assertThat(error.getFields()).containsExactlyEntriesOf(Map.of("topicIds", TOPIC_REQUIRED));
            assertEditNotSaved();
        }

        @Test
        @DisplayName("UTC-126 Không gửi nội dung sửa (request = null): 400 \"Hãy nhập câu hỏi.\", ảnh hiện có không bị xóa")
        void utc126_missingRequestIsRefused() {
            attached(image(401L, "PASSED", 0));

            ApiException error = apiError(() -> editQuestion(null), HttpStatus.BAD_REQUEST, TITLE_REQUIRED);

            assertThat(error.getFields()).containsOnlyKeys("title", "topicIds");
            assertEditNotSaved();
        }

        @Test
        @DisplayName("UTC-127 Chèn link khi sửa (chưa có tag định danh): 400, không lưu, ảnh không bị gỡ")
        void utc127_linkOnEditIsRefused() {
            attached(image(401L, "PASSED", 0));
            String withLink = "Xem https://example.com/a";
            doThrow(ApiException.badRequest(LINK_NEEDS_TAG)).when(filter).checkLinks(withLink, MEMBER_ID);

            apiError(() -> editQuestion(edit(NEW_TITLE, withLink, List.of(OTHER_TOPIC), List.of())),
                    HttpStatus.BAD_REQUEST, LINK_NEEDS_TAG);

            assertEditNotSaved();
            assertRolledBack();
        }

        @Test
        @DisplayName("UTC-128 Từ khóa cấm khi sửa: 400 MSG36, câu hỏi, chủ đề, địa điểm không đổi")
        void utc128_bannedKeywordOnEditIsRefused() {
            String title = "Sửa có vay nóng";
            doThrow(ApiException.badRequest(MSG36)).when(filter).checkKeywords(title);

            apiError(() -> editQuestion(edit(title, "", List.of(OTHER_TOPIC), List.of())), HttpStatus.BAD_REQUEST,
                    MSG36);

            assertEditNotSaved();
            assertRolledBack();
        }

        @Test
        @DisplayName("UTC-129 Ảnh mới có mã QR khi chưa có tag định danh: 400 dưới \"Ảnh\", không lưu gì")
        void utc129_qrCodeInANewPhotoIsRefused() {
            when(media.prepareImage(any())).thenReturn(photo("https://line.me/ti/p/abc123"));
            doThrow(ApiException.invalid(QR_NEEDS_TAG, Map.of("images", QR_NEEDS_TAG))).when(filter)
                    .checkQrCodes(List.of(List.of("https://line.me/ti/p/abc123")), MEMBER_ID);

            apiError(() -> service.update(MEMBER, QA, QUESTION_ID, editKeeping(List.of()), List.of(photoFile()), null),
                    HttpStatus.BAD_REQUEST, QR_NEEDS_TAG);

            assertEditNotSaved();
        }

        @Test
        @DisplayName("UTC-130 Thứ tự kiểm tra khi sửa: link → giới hạn video (khi thêm video) → từ khóa cấm → lưu")
        void utc130_checksRunInOrderOnEdit() {
            service.update(MEMBER, QA, QUESTION_ID, editKeeping(List.of()), List.of(), videoFile());

            InOrder order = inOrder(filter, repository);
            order.verify(filter).checkLinks(NEW_TITLE, MEMBER_ID);
            order.verify(filter).checkLinks(NEW_CONTENT, MEMBER_ID);
            order.verify(filter).checkQrCodes(List.of(), MEMBER_ID);
            order.verify(repository).lockUsage(MEMBER_ID, TODAY);
            order.verify(filter).checkKeywords(NEW_TITLE);
            order.verify(filter).checkKeywords(NEW_CONTENT);
            order.verify(filter).checkQrKeywords(List.of());
            order.verify(repository).updateBlog(QUESTION_ID, NEW_TITLE, NEW_CONTENT, "PROCESSING", NOW);
        }

        @Test
        @DisplayName("UTC-131 Sửa không tính là bài mới: tài khoản mới đã 3 bài hôm nay vẫn sửa được, hạn mức không tăng")
        void utc131_anEditIsNotANewPost() {
            account(MEMBER_ID, "USER", "ACTIVE", NEWBIE_SINCE);
            usageToday(3, 0, 0);

            Created saved = editQuestion(editKeeping(List.of()));

            assertThat(saved.status()).isEqualTo("PUBLISHED");
            verify(repository, never()).lockUsage(anyLong(), any());
            verify(repository, never()).addUsage(anyLong(), any(), anyInt(), anyInt());
        }

        @Test
        @DisplayName("UTC-132 Thêm địa điểm mới từ OpenStreetMap khi sửa: địa điểm được thêm và gắn vào câu hỏi")
        void utc132_newMapPlaceIsAddedOnEdit() {
            PlacePicks picks = new PlacePicks(List.of(88L), List.of(), null);
            when(places.resolve(MEMBER_ID, List.of("N772389775"))).thenReturn(picks);
            when(places.save(picks, MEMBER_ID)).thenReturn(List.of(88L));

            editQuestion(new UpdateRequest(NEW_TITLE, NEW_CONTENT, List.of(VISA), List.of(21L),
                    List.of("N772389775"), List.of()));

            verify(repository).linkPlaces(QUESTION_ID, List.of(21L, 88L));
        }

        @Test
        @DisplayName("UTC-133 Địa điểm bị gỡ đúng lúc lưu bản sửa: 400 \"Có địa điểm không còn trên bản đồ…\", không lưu gì")
        void utc133_placeRemovedMeanwhileStopsTheEdit() {
            when(repository.countActivePlaces(List.of(21L))).thenReturn(1, 0);

            apiError(() -> editQuestion(new UpdateRequest(NEW_TITLE, NEW_CONTENT, List.of(VISA), List.of(21L),
                    List.of(), List.of())), HttpStatus.BAD_REQUEST, PLACE_GONE);

            verify(repository, never()).updateBlog(anyLong(), any(), any(), any(), any());
            assertRolledBack();
        }
    }

    // =================================================================================================================
    @Nested
    @TestMethodOrder(MethodOrderer.DisplayName.class)
    @DisplayName("12. update – UC 1.15 Cập nhật câu hỏi: ảnh/video và trạng thái sau khi lưu (UTC-134 → UTC-149)")
    class UpdateMediaAndStatus {

        @Test
        @DisplayName("UTC-134 Ảnh giữ lại theo thứ tự người hỏi chọn (keepMediaIds = [402, 401])")
        void utc134_keptFilesFollowTheChosenOrder() {
            editable("PUBLISHED");
            attached(image(401L, "PASSED", 0), image(402L, "PASSED", 1));

            Created saved = editQuestion(editKeeping(List.of(402L, 401L)));

            assertThat(saved.status()).isEqualTo("PUBLISHED");
            InOrder order = inOrder(repository);
            order.verify(repository).setMediaOrder(QUESTION_ID, 402L, 0);
            order.verify(repository).setMediaOrder(QUESTION_ID, 401L, 1);
            verify(repository).unlinkMedia(QUESTION_ID, List.of());
            verify(media).discard(List.of());
        }

        @Test
        @DisplayName("UTC-135 Ảnh không có trong keepMediaIds bị gỡ khỏi câu hỏi và xóa khỏi kho; câu hỏi vẫn PUBLISHED")
        void utc135_fileLeftOutIsRemoved() {
            editable("PUBLISHED");
            attached(image(401L, "PASSED", 0), image(402L, "PASSED", 1));

            Created saved = editQuestion(editKeeping(List.of(402L)));

            assertThat(saved.status()).isEqualTo("PUBLISHED");
            verify(repository).unlinkMedia(QUESTION_ID, List.of(401L));
            verify(media).discard(List.of(401L));
            verify(repository).setMediaOrder(QUESTION_ID, 402L, 0);
            verify(repository, never()).setMediaOrder(QUESTION_ID, 401L, 0);
        }

        @Test
        @DisplayName("UTC-136 Không gửi keepMediaIds (null): mọi ảnh/video đang gắn đều bị gỡ")
        void utc136_noKeepListRemovesEveryFile() {
            editable("PUBLISHED");
            attached(image(401L, "PASSED", 0), video(402L, "PASSED", 1));

            editQuestion(editKeeping(null));

            verify(repository).unlinkMedia(QUESTION_ID, List.of(401L, 402L));
            verify(media).discard(List.of(401L, 402L));
            verify(repository, never()).setMediaOrder(anyLong(), anyLong(), anyInt());
        }

        @Test
        @DisplayName("UTC-137 keepMediaIds có id lạ (999) và id lặp lại: id lạ bị bỏ qua, id lặp chỉ tính một lần")
        void utc137_unknownAndRepeatedKeepIdsAreIgnored() {
            editable("PUBLISHED");
            attached(image(401L, "PASSED", 0), image(402L, "PASSED", 1));

            editQuestion(editKeeping(List.of(999L, 402L, 402L)));

            verify(repository).setMediaOrder(QUESTION_ID, 402L, 0);
            verify(repository, times(1)).setMediaOrder(anyLong(), anyLong(), anyInt());
            verify(repository).unlinkMedia(QUESTION_ID, List.of(401L));
        }

        @Test
        @DisplayName("UTC-138 Thêm ảnh mới: câu hỏi về PROCESSING, ảnh mới nối sau ảnh giữ lại, quét sau khi commit, chưa trả điểm")
        void utc138_newPhotoSendsTheQuestionToTheScan() {
            editable("PUBLISHED");
            attached(image(401L, "PASSED", 0));

            Created saved = service.update(MEMBER, QA, QUESTION_ID, editKeeping(List.of(401L)), List.of(photoFile()),
                    null);

            assertThat(saved).isEqualTo(new Created(QUESTION_ID, "QA", "PROCESSING"));
            verify(repository).setMediaOrder(QUESTION_ID, 401L, 0);
            verify(repository).linkMedia(QUESTION_ID, 501L, 1);
            verify(repository).updateBlog(QUESTION_ID, NEW_TITLE, NEW_CONTENT, "PROCESSING", NOW);
            verify(publisher, never()).awardOnce(anyLong(), anyLong());
            verify(publisher, never()).scanSoon(anyLong());
            commit();
            verify(publisher).scanSoon(QUESTION_ID);
        }

        @Test
        @DisplayName("UTC-139 Câu hỏi bị từ chối, ảnh còn lại đều đã qua kiểm duyệt, sửa lại: được đăng ngay và trả điểm (một lần)")
        void utc139_rejectedQuestionWithPassedFilesIsPublished() {
            editable("REJECTED");
            attached(image(401L, "PASSED", 0));

            Created saved = editQuestion(editKeeping(List.of(401L)));

            assertThat(saved.status()).isEqualTo("PUBLISHED");
            verify(repository).updateBlog(QUESTION_ID, NEW_TITLE, NEW_CONTENT, "PUBLISHED", NOW);
            verify(publisher).awardOnce(MEMBER_ID, QUESTION_ID);
        }

        @Test
        @DisplayName("UTC-140 Câu hỏi bị từ chối không còn ảnh nào: sửa lại thì được đăng")
        void utc140_rejectedQuestionWithoutFilesIsPublished() {
            editable("REJECTED");

            Created saved = editQuestion(editKeeping(List.of()));

            assertThat(saved.status()).isEqualTo("PUBLISHED");
            verify(publisher).awardOnce(MEMBER_ID, QUESTION_ID);
        }

        @Test
        @DisplayName("UTC-141 Câu hỏi đang chờ quét, ảnh giữ lại chưa quét xong: vẫn PROCESSING, quét lại sau khi commit")
        void utc141_waitingQuestionWithAFileStillScanningStaysWaiting() {
            editable("PROCESSING");
            attached(image(401L, "PROCESSING", 0));

            Created saved = editQuestion(editKeeping(List.of(401L)));

            assertThat(saved.status()).isEqualTo("PROCESSING");
            verify(publisher, never()).awardOnce(anyLong(), anyLong());
            commit();
            verify(publisher).scanSoon(QUESTION_ID);
        }

        @Test
        @DisplayName("UTC-142 3 ảnh giữ lại + 3 ảnh mới = 6: 400 \"Tối đa 5 ảnh…\", không giải mã ảnh mới")
        void utc142_keptPlusNewPhotosOverFiveAreRefused() {
            editable("PUBLISHED");
            attached(image(401L, "PASSED", 0), image(402L, "PASSED", 1), image(403L, "PASSED", 2));

            ApiException error = apiError(() -> service.update(MEMBER, QA, QUESTION_ID,
                    editKeeping(List.of(401L, 402L, 403L)), photoFiles(3), null), HttpStatus.BAD_REQUEST, IMAGE_RULE);

            assertThat(error.getFields()).containsExactlyEntriesOf(Map.of("images", IMAGE_RULE));
            verify(media, never()).prepareImage(any());
            assertEditNotSaved();
        }

        @Test
        @DisplayName("UTC-143 2 ảnh giữ lại + 3 ảnh mới = 5: được nhận")
        void utc143_keptPlusNewPhotosOfFiveAreAccepted() {
            editable("PUBLISHED");
            attached(image(401L, "PASSED", 0), image(402L, "PASSED", 1), image(403L, "PASSED", 2));

            Created saved = service.update(MEMBER, QA, QUESTION_ID, editKeeping(List.of(401L, 402L)), photoFiles(3),
                    null);

            assertThat(saved.status()).isEqualTo("PROCESSING");
            verify(repository).unlinkMedia(QUESTION_ID, List.of(403L));
            verify(repository).linkMedia(QUESTION_ID, 503L, 4);
        }

        @Test
        @DisplayName("UTC-144 Giữ video cũ và thêm video mới: 400 \"Chỉ đính kèm được 1 video.\"")
        void utc144_secondVideoIsRefused() {
            editable("PUBLISHED");
            attached(video(401L, "PASSED", 0));

            ApiException error = apiError(() -> service.update(MEMBER, QA, QUESTION_ID, editKeeping(List.of(401L)),
                    List.of(), videoFile()), HttpStatus.BAD_REQUEST, ONE_VIDEO);

            assertThat(error.getFields()).containsExactlyEntriesOf(Map.of("video", ONE_VIDEO));
            assertEditNotSaved();
        }

        @Test
        @DisplayName("UTC-145 Thay video cũ bằng video mới: không tính lượt video trong ngày (tài khoản mới đã dùng 1 video vẫn được)")
        void utc145_replacingTheVideoIsNotCounted() {
            account(MEMBER_ID, "USER", "ACTIVE", NEWBIE_SINCE);
            usageToday(3, 0, 1);
            editable("PUBLISHED");
            attached(video(401L, "PASSED", 0));

            Created saved = service.update(MEMBER, QA, QUESTION_ID, editKeeping(List.of()), List.of(), videoFile());

            assertThat(saved.status()).isEqualTo("PROCESSING");
            verify(repository).unlinkMedia(QUESTION_ID, List.of(401L));
            verify(repository, never()).lockUsage(anyLong(), any());
            verify(repository, never()).addUsage(anyLong(), any(), anyInt(), anyInt());
        }

        @Test
        @DisplayName("UTC-146 Tài khoản mới đã dùng 1 video hôm nay thêm video vào câu hỏi chưa có video: 429 giới hạn video, không lưu gì")
        void utc146_newAccountAddingAVideoOverTheLimitIsRefused() {
            account(MEMBER_ID, "USER", "ACTIVE", NEWBIE_SINCE);
            usageToday(1, 0, 1);
            editable("PUBLISHED");

            apiError(() -> service.update(MEMBER, QA, QUESTION_ID, editKeeping(List.of()), List.of(), videoFile()),
                    HttpStatus.TOO_MANY_REQUESTS, VIDEO_LIMIT);

            assertEditNotSaved();
            assertRolledBack();
        }

        @Test
        @DisplayName("UTC-147 Thêm video vào câu hỏi chưa có video: được tính 1 video (không tính bài) trong ngày")
        void utc147_addedVideoIsCountedOnce() {
            account(MEMBER_ID, "USER", "ACTIVE", NEWBIE_SINCE);
            usageToday(0, 0, 0);
            editable("PUBLISHED");

            service.update(MEMBER, QA, QUESTION_ID, editKeeping(List.of()), List.of(), videoFile());

            verify(repository).lockUsage(MEMBER_ID, TODAY);
            verify(repository).addUsage(MEMBER_ID, TODAY, 0, 1);
        }

        @Test
        @DisplayName("UTC-148 Tài khoản mới đã 3 bài hôm nay vẫn thêm được video (giới hạn bài không áp dụng khi sửa)")
        void utc148_postLimitDoesNotApplyToAnEdit() {
            account(MEMBER_ID, "USER", "ACTIVE", NEWBIE_SINCE);
            usageToday(3, 0, 0);
            editable("PUBLISHED");

            Created saved = service.update(MEMBER, QA, QUESTION_ID, editKeeping(List.of()), List.of(), videoFile());

            assertThat(saved.status()).isEqualTo("PROCESSING");
            verify(repository).addUsage(MEMBER_ID, TODAY, 0, 1);
        }

        @Test
        @DisplayName("UTC-149 Ảnh giữ lại đã bị máy quét xóa trong lúc sửa: bị bỏ qua khi sắp xếp, không lỗi")
        void utc149_fileDeletedByTheScanMeanwhileIsSkipped() {
            editable("PROCESSING");
            when(repository.attachedMedia(QUESTION_ID))
                    .thenReturn(List.of(image(401L, "PASSED", 0), image(402L, "PASSED", 1)))
                    .thenReturn(List.of(image(402L, "PASSED", 1)));

            Created saved = editQuestion(editKeeping(List.of(401L, 402L)));

            assertThat(saved.status()).isEqualTo("PUBLISHED");
            verify(repository).setMediaOrder(QUESTION_ID, 402L, 0);
            verify(repository, never()).setMediaOrder(QUESTION_ID, 401L, 0);
            verify(repository).unlinkMedia(QUESTION_ID, List.of());
        }
    }

    // =================================================================================================================
    @Nested
    @TestMethodOrder(MethodOrderer.DisplayName.class)
    @DisplayName("13. BlogPublishService – UC 1.15 Luồng 7a/7b: câu hỏi đã sửa sau khi quét, điểm chỉ trả một lần (UTC-150 → UTC-152)")
    class PublishEditedQuestion {

        private BlogPublishService publish;

        @BeforeEach
        void publishService() {
            publish = new BlogPublishService(repository, scanner, media, filter, points, notifications, txManager,
                    Clock.fixed(NOW, ZoneOffset.UTC));
        }

        @Test
        @DisplayName("UTC-150 Câu hỏi đã sửa (đã nhận điểm) có ảnh mới qua kiểm duyệt: đăng lại, không cộng điểm, thông báo \"Bản sửa câu hỏi…\"")
        void utc150_editedQuestionPassingTheScanEarnsNothingMore() {
            PendingBlog edited = new PendingBlog(QUESTION_ID, MEMBER_ID, "QA", NEW_TITLE, NEW_CONTENT, "PROCESSING");
            when(repository.pendingBlog(QUESTION_ID)).thenReturn(Optional.of(edited));
            when(repository.lockBlog(QUESTION_ID)).thenReturn(Optional.of(edited));
            when(repository.mediaStates(QUESTION_ID)).thenReturn(List.of(new MediaState(501L, "PASSED")));
            when(repository.setStatus(QUESTION_ID, "PROCESSING", "PUBLISHED")).thenReturn(true);
            when(repository.postAwarded(QUESTION_ID)).thenReturn(true);

            publish.process(QUESTION_ID);

            verify(points, never()).add(anyLong(), anyInt(), any(), any(), any());
            verify(notifications).send(MEMBER_ID, NotificationService.CONTENT_PUBLISHED, "Bản sửa câu hỏi “" + NEW_TITLE
                    + "” đã qua kiểm duyệt ảnh/video và đã được đăng.", "QUESTION", QUESTION_ID);
        }

        @Test
        @DisplayName("UTC-151 Câu hỏi chưa từng được trả điểm: lần đăng đầu được +10 (BLOG_CREATED, ref BLOG)")
        void utc151_awardOncePaysTheFirstTime() {
            when(repository.postAwarded(QUESTION_ID)).thenReturn(false);

            assertThat(publish.awardOnce(MEMBER_ID, QUESTION_ID)).isTrue();

            verify(points).add(MEMBER_ID, PointService.BLOG_POST, "BLOG_CREATED", "BLOG", QUESTION_ID);
        }

        @Test
        @DisplayName("UTC-152 Câu hỏi đã được trả điểm trước đó: không trả lần hai")
        void utc152_awardOnceNeverPaysTwice() {
            when(repository.postAwarded(QUESTION_ID)).thenReturn(true);

            assertThat(publish.awardOnce(MEMBER_ID, QUESTION_ID)).isFalse();

            verifyNoInteractions(points);
        }
    }

    // =================================================================================================================
    @Nested
    @TestMethodOrder(MethodOrderer.DisplayName.class)
    @DisplayName("14. QaController.editData / update / updateJson – UC 1.15 câu trả lời của API (UTC-153 → UTC-156)")
    class UpdateController {

        private BlogWriteService writeService;
        private QaController controller;

        @BeforeEach
        void controller() {
            writeService = mock(BlogWriteService.class);
            controller = new QaController(mock(QaService.class), mock(BlogDetailService.class),
                    mock(BlogActionService.class), writeService);
        }

        @Test
        @DisplayName("UTC-153 Mở form sửa qua Hỏi-Đáp: dịch vụ được gọi với loại QA")
        void utc153_editDataAsksForAQuestion() {
            EditData data = new EditData(QUESTION_ID, "QA", "PUBLISHED", TITLE, CONTENT, List.of(VISA), List.of(),
                    List.of(), null);
            when(writeService.editData(MEMBER, "QA", QUESTION_ID)).thenReturn(data);

            ApiResponse<EditData> response = controller.editData(QUESTION_ID, MEMBER);

            assertThat(response.message()).isEqualTo("OK");
            assertThat(response.data()).isSameAs(data);
        }

        @Test
        @DisplayName("UTC-154 Lưu xong, câu hỏi vẫn được đăng: \"Đã lưu thay đổi.\"")
        void utc154_savedQuestionAnswersSavedChanges() {
            UpdateRequest request = editKeeping(List.of());
            when(writeService.update(eq(MEMBER), eq("QA"), eq(QUESTION_ID), eq(request), isNull(), isNull()))
                    .thenReturn(new Created(QUESTION_ID, "QA", "PUBLISHED"));

            ApiResponse<Created> response = controller.update(QUESTION_ID, request, null, null, MEMBER);

            assertThat(response.success()).isTrue();
            assertThat(response.message()).isEqualTo(SAVED_CHANGES);
        }

        @Test
        @DisplayName("UTC-155 Lưu xong, ảnh mới đang quét: \"Đã lưu thay đổi. Ảnh và video đang được quét, trong lúc này chỉ bạn thấy câu hỏi.\"")
        void utc155_savedWithNewFilesAnswersScanning() {
            UpdateRequest request = editKeeping(List.of());
            List<MultipartFile> images = List.of(photoFile());
            when(writeService.update(MEMBER, "QA", QUESTION_ID, request, images, null))
                    .thenReturn(new Created(QUESTION_ID, "QA", "PROCESSING"));

            ApiResponse<Created> response = controller.update(QUESTION_ID, request, images, null, MEMBER);

            assertThat(response.message()).isEqualTo(SAVED_CHANGES_SCANNING);
        }

        @Test
        @DisplayName("UTC-156 Lưu bằng JSON (không có tệp mới): dịch vụ nhận danh sách ảnh rỗng và không có video")
        void utc156_jsonEditSendsNoNewFiles() {
            UpdateRequest request = editKeeping(List.of(401L));
            when(writeService.update(any(), any(), anyLong(), any(), any(), any()))
                    .thenReturn(new Created(QUESTION_ID, "QA", "PUBLISHED"));

            ApiResponse<Created> response = controller.updateJson(QUESTION_ID, request, MEMBER);

            verify(writeService).update(MEMBER, "QA", QUESTION_ID, request, List.of(), null);
            assertThat(response.message()).isEqualTo(SAVED_CHANGES);
        }
    }
}
