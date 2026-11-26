package com.sep490.vsa.feature.blog;

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
import com.sep490.vsa.feature.blog.BlogWriteRepository.Usage;
import com.sep490.vsa.feature.media.InvalidMediaException;
import com.sep490.vsa.feature.media.MediaService;
import com.sep490.vsa.feature.media.MediaService.Prepared;
import com.sep490.vsa.feature.moderation.ContentFilter;
import com.sep490.vsa.feature.place.PlaceRepository.NewOsmPlace;
import com.sep490.vsa.feature.place.PlaceService;
import com.sep490.vsa.feature.place.PlaceService.PlacePicks;
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
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.InOrder;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;

import java.sql.Date;
import java.sql.Timestamp;
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
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit tests of "Tạo bài viết" (UC 1.5: the "Viết bài" form, {@link BlogWriteService#compose} and
 * {@link BlogWriteService#create}) and "Cập nhật bài viết" (UC 1.6: {@link BlogWriteService#editData} and
 * {@link BlogWriteService#update}) for Blog posts (kind POST), written from the use case specification of Report 3
 * and the rules in CLAUDE.md ("Write Post (UC-11, feature/blog + feature/media)": checks in the agreed order, stopping
 * at the first failure: fields and files → links → QR codes of the photos → new-account daily limit → banned keywords
 * → QR keywords; no media → PUBLISHED with +10 points; media → PROCESSING, scanned after commit).
 * <p>
 * The database is not used: {@link BlogWriteRepository}, {@link BlogFeedRepository}, {@link ContentFilter},
 * {@link MediaService}, {@link SystemSettings}, {@link BlogPublishService}, {@link PlaceService} and the transaction
 * manager are mocks (the transaction template runs the save around the mocked manager, so commit and rollback can be
 * checked). Transaction synchronisation is switched on by hand so the "scan after commit" hook can be fired. The
 * clock is fixed at 20/11/2026 12:00 Japan time. Two tests run the real {@link BlogWriteRepository} SQL against a
 * mocked {@link JdbcTemplate} (the usage row lock and the "· đã sửa" flag).
 */
@TestClassOrder(ClassOrderer.DisplayName.class)
@DisplayName("BlogWriteService – Tạo và cập nhật bài viết (UC 1.5, UC 1.6)")
class BlogWriteServicePostTest {

    private static final long MEMBER_ID = 10L;
    private static final long OTHER_ID = 11L;
    private static final long STAFF_ID = 20L;
    private static final long BLOG_ID = 500L;
    private static final String KIND = "POST";
    /** 12:00 in Japan: the Japan date is 20/11/2026. */
    private static final Instant NOW = Instant.parse("2026-11-20T03:00:00Z");
    private static final LocalDate TODAY = LocalDate.of(2026, 11, 20);

    private static final AppUserPrincipal MEMBER = principal(MEMBER_ID, "USER");

    private static final String TITLE = "Kinh nghiệm thuê nhà ở Arakawa";
    private static final String CONTENT = "Nên đi xem nhà vào buổi sáng và hỏi kỹ phí đầu vào.";
    private static final String NEW_TITLE = "Kinh nghiệm thuê nhà ở Arakawa (cập nhật)";
    private static final String NEW_CONTENT = "Bổ sung: phí bảo lãnh thường bằng nửa tháng tiền nhà.";

    private static final String MSG18 = "Bạn không có quyền thực hiện thao tác này.";
    private static final String MSG19 = "Không tìm thấy nội dung bạn yêu cầu. Bài viết có thể đã bị gỡ hoặc đã bị xóa.";
    private static final String MSG37 = "Hãy nhập tiêu đề và nội dung cho bài viết.";
    private static final String SIGN_IN = "Bạn cần đăng nhập để viết bài.";
    private static final String NO_SESSION = "Bạn chưa đăng nhập.";
    private static final String TITLE_REQUIRED = "Hãy nhập tiêu đề.";
    private static final String CONTENT_REQUIRED = "Hãy nhập nội dung bài viết.";
    private static final String TOPIC_REQUIRED = "Hãy chọn ít nhất 1 chủ đề. Không có chủ đề phù hợp thì chọn “Khác”.";
    private static final String TITLE_TOO_LONG = "Tiêu đề tối đa 150 ký tự.";
    private static final String CONTENT_TOO_LONG = "Nội dung tối đa 5.000 ký tự.";
    private static final String TOO_MANY_TOPICS = "Chọn tối đa 3 chủ đề.";
    private static final String TOPIC_GONE = "Có chủ đề không còn trong danh sách. Hãy chọn lại chủ đề.";
    private static final String TOO_MANY_PLACES = "Chọn tối đa 5 địa điểm.";
    private static final String PLACE_INVALID = "Có địa điểm không hợp lệ. Hãy chọn lại địa điểm.";
    private static final String PLACE_GONE = "Có địa điểm không còn trên bản đồ. Hãy chọn lại địa điểm.";
    private static final String OSM_UNAVAILABLE = "Chưa lấy được thông tin địa điểm từ OpenStreetMap. "
            + "Hãy thử lại sau hoặc bỏ địa điểm đó.";
    private static final String IMAGE_RULE = "Tối đa 5 ảnh, định dạng JPG, PNG hoặc HEIC, mỗi ảnh không quá 20 MB.";
    private static final String BUSY = "Máy chủ đang xử lý nhiều ảnh, vui lòng thử lại sau ít phút.";
    private static final String ONE_VIDEO = "Chỉ đính kèm được 1 video.";
    private static final String VIDEO_TOO_LONG = "Video dài 31 giây. Video tối đa 30 giây.";
    private static final String VIDEO_UNREADABLE = "Không đọc được video. Hãy dùng video MP4 hoặc MOV.";
    private static final String LINK_NEEDS_TAG = "Chỉ tài khoản đã có tag định danh mới được chèn link.";
    private static final String SHORT_LINK = "Không dùng link rút gọn (bit.ly, tinyurl…). Hãy dán link đầy đủ.";
    private static final String QR_NEEDS_TAG = "Ảnh thứ 2 bạn vừa chọn có mã QR. Chỉ tài khoản đã có tag định danh mới "
            + "được đăng ảnh có mã QR (mã QR được tính như link). Hãy cắt hoặc che mã QR rồi chọn lại ảnh.";
    private static final String MSG44_POSTS = "Bạn đã đạt giới hạn đăng bài trong ngày của tài khoản mới (dưới 7 ngày: "
            + "tối đa 3 bài viết hoặc review mỗi ngày). Vui lòng thử lại vào ngày mai.";
    private static final String VIDEO_LIMIT = "Bạn đã đạt giới hạn video trong ngày của tài khoản mới (tối đa 1 video "
            + "mỗi ngày). Hãy bỏ video hoặc thử lại vào ngày mai.";

    private static final List<TopicOption> TOPICS = List.of(
            new TopicOption(1L, "Kinh nghiệm", false, false),
            new TopicOption(2L, "Nhà ở", false, false),
            new TopicOption(9L, "Cảnh báo lừa đảo", false, true),
            new TopicOption(99L, "Khác", true, false));

    private static final Prepared PHOTO = new Prepared("IMAGE", new byte[] {1}, "image/jpeg", "jpg", 1600, 1200, null,
            List.of());
    private static final Prepared VIDEO = new Prepared("VIDEO", new byte[] {2}, "video/mp4", "mp4", null, null, 12,
            List.of());
    private static final PlaceRef NIPPORI = new PlaceRef(30L, "Ga Nippori", "Nhà ga", "Arakawa, Tokyo", 35.7281,
            139.7710, "N772389775");

    private BlogWriteRepository repository;
    private BlogFeedRepository feedRepository;
    private ContentFilter filter;
    private MediaService media;
    private SystemSettings settings;
    private BlogPublishService publisher;
    private PlaceService places;
    private PlatformTransactionManager txManager;
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
        service = serviceAt(NOW);

        memberAged(Duration.ofDays(30));
        when(settings.getInt(anyString(), anyInt())).thenAnswer(inv -> inv.getArgument(1));
        when(repository.activeTopics()).thenReturn(TOPICS);
        when(repository.countActiveTopics(anyList())).thenAnswer(inv -> ((List<?>) inv.getArgument(0)).size());
        when(repository.countActivePlaces(anyList())).thenAnswer(inv -> ((List<?>) inv.getArgument(0)).size());
        when(repository.usage(anyLong(), any())).thenReturn(new Usage(0, 0, 0));
        when(repository.lockUsage(anyLong(), any())).thenReturn(new Usage(0, 0, 0));
        when(repository.insertBlog(anyLong(), anyString(), anyString(), anyString(), anyString(), any()))
                .thenReturn(BLOG_ID);
        when(repository.editableBlog(eq(BLOG_ID), anyBoolean())).thenReturn(Optional.of(editable("PUBLISHED")));
        when(places.resolve(anyLong(), anyList())).thenReturn(PlacePicks.NONE);
        when(places.save(any(), anyLong())).thenReturn(List.of());
        when(media.prepareImage(any())).thenReturn(PHOTO);
        when(media.prepareVideo(any())).thenReturn(VIDEO);
        AtomicLong nextMediaId = new AtomicLong(900);
        when(media.save(anyLong(), anyString(), any())).thenAnswer(inv -> nextMediaId.getAndIncrement());
        when(media.videoMaxMb()).thenReturn(50);
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
        return new BlogWriteService(repository, feedRepository, filter, media, settings, publisher, places, txManager,
                Clock.fixed(instant, ZoneOffset.UTC), "/media/");
    }

    private static AppUserPrincipal principal(long id, String role) {
        return new AppUserPrincipal(id, "user" + id + "@example.org", "User " + id, role, "/");
    }

    /** The signed-in Member's account row, created {@code age} before now. */
    private void memberAged(Duration age) {
        when(repository.account(MEMBER_ID)).thenReturn(Optional.of(new Account(MEMBER_ID, "USER", "ACTIVE",
                NOW.minus(age))));
    }

    /** A staff account (role from the database row) signed in as {@code STAFF_ID}. */
    private AppUserPrincipal staff(String role) {
        when(repository.account(STAFF_ID)).thenReturn(Optional.of(new Account(STAFF_ID, role, "ACTIVE",
                NOW.minus(Duration.ofDays(300)))));
        return principal(STAFF_ID, role);
    }

    private void lockedMember() {
        when(repository.account(MEMBER_ID)).thenReturn(Optional.of(new Account(MEMBER_ID, "USER", "LOCKED",
                NOW.minus(Duration.ofDays(30)))));
    }

    private void settingsOf(int newDays, int posts, int pins, int videos) {
        when(settings.getInt(eq(SystemSettings.NEW_ACCOUNT_DAYS), anyInt())).thenReturn(newDays);
        when(settings.getInt(eq(SystemSettings.POSTS_PER_DAY), anyInt())).thenReturn(posts);
        when(settings.getInt(eq(SystemSettings.PINS_PER_DAY), anyInt())).thenReturn(pins);
        when(settings.getInt(eq(SystemSettings.VIDEOS_PER_DAY), anyInt())).thenReturn(videos);
    }

    private static CreateRequest post(String title, String content, List<Long> topicIds, List<Long> placeIds,
                                      List<String> externalPlaceIds) {
        return new CreateRequest(title, content, topicIds, placeIds, externalPlaceIds);
    }

    /** A valid "Viết bài" form: 2 topics and 1 place of Senpai ơi. */
    private static CreateRequest validPost() {
        return post(TITLE, CONTENT, List.of(1L, 2L), List.of(30L), List.of());
    }

    private static UpdateRequest edit(String title, String content, List<Long> topicIds, List<Long> placeIds,
                                      List<String> externalPlaceIds, List<Long> keepMediaIds) {
        return new UpdateRequest(title, content, topicIds, placeIds, externalPlaceIds, keepMediaIds);
    }

    /** A valid "Sửa bài" form: new title and content, topic 3, place 31, keeps the listed media. */
    private static UpdateRequest validEdit(List<Long> keepMediaIds) {
        return edit(NEW_TITLE, NEW_CONTENT, List.of(3L), List.of(31L), List.of(), keepMediaIds);
    }

    private Created create(CreateRequest request) {
        return service.create(MEMBER, KIND, request, null, null);
    }

    private Created create(CreateRequest request, List<MultipartFile> images, MultipartFile video) {
        return service.create(MEMBER, KIND, request, images, video);
    }

    private Created update(UpdateRequest request) {
        return service.update(MEMBER, KIND, BLOG_ID, request, null, null);
    }

    private Created update(UpdateRequest request, List<MultipartFile> images, MultipartFile video) {
        return service.update(MEMBER, KIND, BLOG_ID, request, images, video);
    }

    private static EditableBlog editable(String status) {
        return editable(MEMBER_ID, "POST", status);
    }

    private static EditableBlog editable(long authorId, String type, String status) {
        return new EditableBlog(BLOG_ID, authorId, type, status, "Tiêu đề cũ", "Nội dung cũ");
    }

    private static AttachedMedia image(long id, String scanStatus, int order) {
        return new AttachedMedia(id, "IMAGE", "blog/2026/11/photo-" + id + ".jpg", scanStatus, order);
    }

    private static AttachedMedia video(long id, String scanStatus, int order) {
        return new AttachedMedia(id, "VIDEO", "blog/2026/11/clip-" + id + ".mp4", scanStatus, order);
    }

    private static MockMultipartFile photoFile(int n) {
        return new MockMultipartFile("images", "hanami-" + n + ".jpg", "image/jpeg", new byte[] {1, 2, 3});
    }

    private static List<MultipartFile> photos(int count) {
        List<MultipartFile> files = new ArrayList<>();
        for (int i = 1; i <= count; i++) {
            files.add(photoFile(i));
        }
        return files;
    }

    private static MockMultipartFile videoFile() {
        return new MockMultipartFile("video", "ueno.mp4", "video/mp4", new byte[] {4, 5, 6});
    }

    private static MockMultipartFile emptyFile(String part) {
        return new MockMultipartFile(part, "empty.bin", "application/octet-stream", new byte[0]);
    }

    private static Prepared photoWithQr(String... codes) {
        return new Prepared("IMAGE", new byte[] {3}, "image/jpeg", "jpg", 1200, 900, null, List.of(codes));
    }

    private static NewOsmPlace osmPlace(String osmId) {
        return new NewOsmPlace(osmId, "Công viên Ueno", "上野公園", "Ueno Park", "park", "Taito, Tokyo", 35.7156,
                139.7745);
    }

    private static String msg36(String keyword) {
        return "Chưa đăng được: nội dung chứa từ khóa không được phép “" + keyword + "”. Hãy sửa rồi gửi lại.";
    }

    private static void afterCommit() {
        TransactionSynchronizationManager.getSynchronizations().forEach(TransactionSynchronization::afterCommit);
    }

    private static ApiException apiError(Runnable call) {
        Throwable thrown = catchThrowable(call::run);
        assertThat(thrown).isInstanceOf(ApiException.class);
        return (ApiException) thrown;
    }

    private static void assertApiError(Runnable call, HttpStatus status, String message) {
        ApiException e = apiError(call);
        assertThat(e.getStatus()).isEqualTo(status);
        assertThat(e.getMessage()).isEqualTo(message);
    }

    /** 400 with {@code summary} above the form and {@code fieldMessage} under {@code field}. */
    private static ApiException assertInvalid(Runnable call, String summary, String field, String fieldMessage) {
        ApiException e = apiError(call);
        assertThat(e.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(e.getMessage()).isEqualTo(summary);
        assertThat(e.getFields()).containsEntry(field, fieldMessage);
        return e;
    }

    private void verifyNothingSaved() {
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

    private void verifyNothingChanged() {
        verify(repository, never()).updateBlog(anyLong(), any(), any(), any(), any());
        verify(repository, never()).unlinkTopics(anyLong());
        verify(repository, never()).linkTopics(anyLong(), anyList());
        verify(repository, never()).unlinkPlaces(anyLong());
        verify(repository, never()).linkPlaces(anyLong(), anyList());
        verify(repository, never()).unlinkMedia(anyLong(), anyList());
        verify(repository, never()).setMediaOrder(anyLong(), anyLong(), anyInt());
        verify(repository, never()).linkMedia(anyLong(), anyLong(), anyInt());
        verify(repository, never()).addUsage(anyLong(), any(), anyInt(), anyInt());
        verify(media, never()).save(anyLong(), any(), any());
        verify(media, never()).discard(any());
        verify(publisher, never()).awardOnce(anyLong(), anyLong());
        verify(publisher, never()).scanSoon(anyLong());
        assertThat(TransactionSynchronizationManager.getSynchronizations()).isEmpty();
    }

    private void verifyNoTransaction() {
        verify(txManager, never()).getTransaction(any());
    }

    private void verifyRolledBack() {
        verify(txManager).rollback(any());
        verify(txManager, never()).commit(any());
    }

    // =================================================================================================================
    @Nested
    @TestMethodOrder(MethodOrderer.DisplayName.class)
    @DisplayName("01. compose – UC 1.5 Tạo bài viết: mở form \"Viết bài\" (UTC-123 → UTC-138)")
    class ComposeForm {

        @Test
        @DisplayName("UTC-123 Form có các chủ đề đang hoạt động theo đúng thứ tự, đánh dấu \"Khác\" và \"Cảnh báo lừa đảo\"")
        void utc123_topicsInOrderWithOtherAndScamFlags() {
            ComposeOptions options = service.compose(MEMBER);

            assertThat(options.topics()).containsExactlyElementsOf(TOPICS);
            assertThat(options.topics()).filteredOn(TopicOption::other).extracting(TopicOption::name)
                    .containsExactly("Khác");
            assertThat(options.topics()).filteredOn(TopicOption::scamWarning).extracting(TopicOption::name)
                    .containsExactly("Cảnh báo lừa đảo");
            assertThat(options.canPostLinks()).isFalse();
        }

        @Test
        @DisplayName("UTC-124 Form gửi kèm quy tắc: tiêu đề 150, nội dung 5.000, 3 chủ đề, 5 địa điểm, 5 ảnh 20 MB, 1 video 30 giây 50 MB")
        void utc124_limitsOfTheForm() {
            Limits limits = service.compose(MEMBER).limits();

            assertThat(limits).isEqualTo(new Limits(150, 5000, 3, 5, 5, 20, 1, 30, 50, false));
        }

        @Test
        @DisplayName("UTC-125 Dung lượng video tối đa lấy theo cấu hình máy chủ (80 MB)")
        void utc125_videoMaxMbFollowsServerSetting() {
            when(media.videoMaxMb()).thenReturn(80);

            assertThat(service.compose(MEMBER).limits().videoMaxMb()).isEqualTo(80);
        }

        @Test
        @DisplayName("UTC-126 Tài khoản mới 2 ngày tuổi: hạn mức hôm nay 1/3 bài, 1/2 ghim, 0/1 video")
        void utc126_newAccountQuotaShowsTodaysUsage() {
            memberAged(Duration.ofDays(2));
            when(repository.usage(MEMBER_ID, TODAY)).thenReturn(new Usage(1, 1, 0));

            Quota quota = service.compose(MEMBER).quota();

            assertThat(quota).isEqualTo(new Quota(true, 7, 2, 1, 3, 1, 2, 0, 1));
        }

        @Test
        @DisplayName("UTC-127 Tài khoản tròn 7 ngày tuổi không còn là tài khoản mới")
        void utc127_accountExactlySevenDaysOld_notNew() {
            memberAged(Duration.ofDays(7));

            Quota quota = service.compose(MEMBER).quota();

            assertThat(quota.newAccount()).isFalse();
            assertThat(quota.accountAgeDays()).isEqualTo(7);
        }

        @Test
        @DisplayName("UTC-128 Tài khoản 7 ngày thiếu 1 giây vẫn là tài khoản mới (6 ngày tuổi)")
        void utc128_accountOneSecondShortOfSevenDays_stillNew() {
            memberAged(Duration.ofDays(7).minusSeconds(1));

            Quota quota = service.compose(MEMBER).quota();

            assertThat(quota.newAccount()).isTrue();
            assertThat(quota.accountAgeDays()).isEqualTo(6);
        }

        @Test
        @DisplayName("UTC-129 Hạn mức theo cài đặt của Admin: 10 ngày, 5 bài, 4 ghim, 2 video")
        void utc129_quotaFollowsAdminSettings() {
            settingsOf(10, 5, 4, 2);
            memberAged(Duration.ofDays(8));
            when(repository.usage(MEMBER_ID, TODAY)).thenReturn(new Usage(4, 3, 1));

            Quota quota = service.compose(MEMBER).quota();

            assertThat(quota).isEqualTo(new Quota(true, 10, 8, 4, 5, 3, 4, 1, 2));
        }

        @Test
        @DisplayName("UTC-130 Ngày tính theo giờ Nhật: 00:00 ngày 20/11 ở Nhật đọc hạn mức của ngày 20/11")
        void utc130_usageOfTheJapanDay_atMidnight() {
            serviceAt(Instant.parse("2026-11-19T15:00:00Z")).compose(MEMBER);

            verify(repository).usage(MEMBER_ID, LocalDate.of(2026, 11, 20));
        }

        @Test
        @DisplayName("UTC-131 23:59:59 ngày 19/11 ở Nhật vẫn đọc hạn mức của ngày 19/11")
        void utc131_usageOfTheJapanDay_oneSecondBeforeMidnight() {
            serviceAt(Instant.parse("2026-11-19T14:59:59Z")).compose(MEMBER);

            verify(repository).usage(MEMBER_ID, LocalDate.of(2026, 11, 19));
        }

        @Test
        @DisplayName("UTC-132 Thành viên có tag định danh được chèn link (canPostLinks = true)")
        void utc132_identityTag_canPostLinks() {
            when(filter.hasIdentityTag(MEMBER_ID)).thenReturn(true);

            assertThat(service.compose(MEMBER).canPostLinks()).isTrue();
        }

        @Test
        @DisplayName("UTC-133 Ngày tạo tài khoản lệch giờ (sau thời điểm hiện tại): tuổi tài khoản là 0, không âm")
        void utc133_creationTimeInTheFuture_ageIsZero() {
            memberAged(Duration.ofMinutes(-5));

            Quota quota = service.compose(MEMBER).quota();

            assertThat(quota.accountAgeDays()).isZero();
            assertThat(quota.newAccount()).isTrue();
        }

        @Test
        @DisplayName("UTC-134 Mở form chỉ đọc: không khóa hàng hạn mức, không ghi gì, không mở giao dịch ghi")
        void utc134_composeOnlyReads() {
            service.compose(MEMBER);

            verify(repository, never()).lockUsage(anyLong(), any());
            verifyNothingSaved();
            verifyNoTransaction();
        }

        @Test
        @DisplayName("UTC-135 Khách chưa đăng nhập: 401 \"Bạn cần đăng nhập để viết bài.\"")
        void utc135_guest_unauthorized() {
            assertApiError(() -> service.compose(null), HttpStatus.UNAUTHORIZED, SIGN_IN);
            verifyNoInteractions(repository);
        }

        @Test
        @DisplayName("UTC-136 Phiên của tài khoản không còn trong hệ thống (đã xóa): 401 \"Bạn chưa đăng nhập.\"")
        void utc136_accountRowMissing_unauthorized() {
            when(repository.account(MEMBER_ID)).thenReturn(Optional.empty());

            assertApiError(() -> service.compose(MEMBER), HttpStatus.UNAUTHORIZED, NO_SESSION);
            verify(repository, never()).activeTopics();
        }

        @ParameterizedTest(name = "[{index}] role = {0}")
        @ValueSource(strings = {"MODERATOR", "ACADEMIC_MANAGER", "ADMIN"})
        @DisplayName("UTC-137 Tài khoản nhân viên (Moderator, BTV tiếng Nhật, Admin): 403 MSG18")
        void utc137_staff_forbidden(String role) {
            AppUserPrincipal staff = staff(role);

            assertApiError(() -> service.compose(staff), HttpStatus.FORBIDDEN, MSG18);
            verify(repository, never()).activeTopics();
        }

        @Test
        @DisplayName("UTC-138 Thành viên bị khóa: 403 MSG18")
        void utc138_lockedMember_forbidden() {
            lockedMember();

            assertApiError(() -> service.compose(MEMBER), HttpStatus.FORBIDDEN, MSG18);
        }
    }

    // =================================================================================================================
    @Nested
    @TestMethodOrder(MethodOrderer.DisplayName.class)
    @DisplayName("02. create – UC 1.5 Tạo bài viết: quyền và luồng chính (UTC-139 → UTC-154)")
    class CreateMainFlow {

        @Test
        @DisplayName("UTC-139 Bài chỉ có chữ được đăng ngay (PUBLISHED) với đúng tác giả, loại, tiêu đề, nội dung, giờ đăng")
        void utc139_textPost_publishedAtOnce() {
            Created created = create(validPost());

            assertThat(created).isEqualTo(new Created(BLOG_ID, "POST", "PUBLISHED"));
            verify(repository).insertBlog(MEMBER_ID, "POST", TITLE, CONTENT, "PUBLISHED", NOW);
        }

        @Test
        @DisplayName("UTC-140 Bài được gắn chủ đề và địa điểm đã chọn")
        void utc140_topicsAndPlacesLinked() {
            create(validPost());

            verify(repository).linkTopics(BLOG_ID, List.of(1L, 2L));
            verify(repository).linkPlaces(BLOG_ID, List.of(30L));
        }

        @Test
        @DisplayName("UTC-141 Bài không có tệp được cộng điểm ngay (+10, trả một lần) và không phải chờ quét")
        void utc141_textPost_pointsAwardedNoScan() {
            create(validPost());

            verify(publisher).awardOnce(MEMBER_ID, BLOG_ID);
            verify(publisher, never()).scanSoon(anyLong());
            verify(media, never()).save(anyLong(), any(), any());
            verify(repository, never()).linkMedia(anyLong(), anyLong(), anyInt());
        }

        @Test
        @DisplayName("UTC-142 Số bài trong ngày tăng 1, số video giữ nguyên")
        void utc142_dailyUsageCountsThePost() {
            create(validPost());

            verify(repository).addUsage(MEMBER_ID, TODAY, 1, 0);
        }

        @Test
        @DisplayName("UTC-143 Tiêu đề nhiều dòng/khoảng trắng được gộp thành một dòng; nội dung bỏ khoảng trắng đầu cuối, xuống dòng Windows thành \\n")
        void utc143_titleAndContentAreCleaned() {
            create(post("  Kinh nghiệm   thuê nhà\n ở   Arakawa  ", "\r\n  Dòng 1\r\nDòng 2  \r\n", List.of(1L),
                    List.of(), List.of()));

            verify(repository).insertBlog(MEMBER_ID, "POST", "Kinh nghiệm thuê nhà ở Arakawa", "Dòng 1\nDòng 2",
                    "PUBLISHED", NOW);
        }

        @Test
        @DisplayName("UTC-144 Mã chủ đề, địa điểm trùng hoặc rỗng bị bỏ: [1, null, 1, 2] → [1, 2]; [30, 30, null] → [30]")
        void utc144_repeatedAndNullIdsDropped() {
            create(post(TITLE, CONTENT, Arrays.asList(1L, null, 1L, 2L), Arrays.asList(30L, 30L, null), List.of()));

            verify(repository).countActiveTopics(List.of(1L, 2L));
            verify(repository).linkTopics(BLOG_ID, List.of(1L, 2L));
            verify(repository).linkPlaces(BLOG_ID, List.of(30L));
        }

        @Test
        @DisplayName("UTC-145 Thứ tự kiểm tra: link → mã QR → hạn mức ngày → từ khóa cấm → mã QR (từ khóa) → lưu → cộng điểm")
        void utc145_checksRunInTheAgreedOrder() {
            create(validPost());

            InOrder order = inOrder(filter, repository, places, publisher);
            order.verify(filter).checkLinks(TITLE, MEMBER_ID);
            order.verify(filter).checkLinks(CONTENT, MEMBER_ID);
            order.verify(filter).checkQrCodes(List.of(), MEMBER_ID);
            order.verify(repository).lockUsage(MEMBER_ID, TODAY);
            order.verify(filter).checkKeywords(TITLE);
            order.verify(filter).checkKeywords(CONTENT);
            order.verify(filter).checkQrKeywords(List.of());
            order.verify(places).save(PlacePicks.NONE, MEMBER_ID);
            order.verify(repository).insertBlog(MEMBER_ID, "POST", TITLE, CONTENT, "PUBLISHED", NOW);
            order.verify(repository).linkTopics(BLOG_ID, List.of(1L, 2L));
            order.verify(repository).linkPlaces(BLOG_ID, List.of(30L));
            order.verify(repository).addUsage(MEMBER_ID, TODAY, 1, 0);
            order.verify(publisher).awardOnce(MEMBER_ID, BLOG_ID);
        }

        @Test
        @DisplayName("UTC-146 Bước 1 (thông tin, tệp) chạy trước khi mở giao dịch; phần lưu chạy trong một giao dịch và được commit")
        void utc146_fieldChecksBeforeTheTransaction_saveInOneTransaction() {
            create(validPost());

            InOrder order = inOrder(repository, places, txManager);
            order.verify(repository).countActiveTopics(List.of(1L, 2L));
            order.verify(places).resolve(MEMBER_ID, List.of());
            order.verify(txManager).getTransaction(any());
            order.verify(repository).lockUsage(MEMBER_ID, TODAY);
            order.verify(repository).insertBlog(MEMBER_ID, "POST", TITLE, CONTENT, "PUBLISHED", NOW);
            order.verify(txManager).commit(any());
            verify(txManager, never()).rollback(any());
        }

        @Test
        @DisplayName("UTC-147 Đăng lúc 00:00 ngày 20/11 giờ Nhật: tính vào hạn mức ngày 20/11")
        void utc147_usageDayIsTheJapanDay_atMidnight() {
            serviceAt(Instant.parse("2026-11-19T15:00:00Z")).create(MEMBER, KIND, validPost(), null, null);

            verify(repository).lockUsage(MEMBER_ID, LocalDate.of(2026, 11, 20));
            verify(repository).addUsage(MEMBER_ID, LocalDate.of(2026, 11, 20), 1, 0);
        }

        @Test
        @DisplayName("UTC-148 Đăng lúc 23:59:59 ngày 19/11 giờ Nhật: tính vào hạn mức ngày 19/11")
        void utc148_usageDayIsTheJapanDay_oneSecondBeforeMidnight() {
            serviceAt(Instant.parse("2026-11-19T14:59:59Z")).create(MEMBER, KIND, validPost(), null, null);

            verify(repository).lockUsage(MEMBER_ID, LocalDate.of(2026, 11, 19));
            verify(repository).addUsage(MEMBER_ID, LocalDate.of(2026, 11, 19), 1, 0);
        }

        @Test
        @DisplayName("UTC-149 Bài không gắn địa điểm: không kiểm tra địa điểm, danh sách địa điểm rỗng")
        void utc149_postWithoutPlace() {
            create(post(TITLE, CONTENT, List.of(1L), List.of(), List.of()));

            verify(repository, never()).countActivePlaces(anyList());
            verify(repository).linkPlaces(BLOG_ID, List.of());
        }

        @Test
        @DisplayName("UTC-150 Khách chưa đăng nhập: 401 \"Bạn cần đăng nhập để viết bài.\", không đọc tệp, không lưu gì")
        void utc150_guest_unauthorized() {
            assertApiError(() -> service.create(null, KIND, validPost(), photos(1), null), HttpStatus.UNAUTHORIZED,
                    SIGN_IN);
            verifyNoInteractions(repository, media, places, filter);
            verifyNoTransaction();
        }

        @Test
        @DisplayName("UTC-151 Phiên của tài khoản không còn trong hệ thống: 401 \"Bạn chưa đăng nhập.\"")
        void utc151_accountRowMissing_unauthorized() {
            when(repository.account(MEMBER_ID)).thenReturn(Optional.empty());

            assertApiError(() -> create(validPost()), HttpStatus.UNAUTHORIZED, NO_SESSION);
            verifyNothingSaved();
        }

        @ParameterizedTest(name = "[{index}] role = {0}")
        @ValueSource(strings = {"MODERATOR", "ACADEMIC_MANAGER", "ADMIN"})
        @DisplayName("UTC-152 Tài khoản nhân viên đăng bài: 403 MSG18, không lưu gì")
        void utc152_staff_forbidden(String role) {
            AppUserPrincipal staff = staff(role);

            assertApiError(() -> service.create(staff, KIND, validPost(), null, null), HttpStatus.FORBIDDEN, MSG18);
            verifyNothingSaved();
            verifyNoTransaction();
        }

        @Test
        @DisplayName("UTC-153 Thành viên bị khóa: 403 MSG18, không lưu gì")
        void utc153_lockedMember_forbidden() {
            lockedMember();

            assertApiError(() -> create(validPost()), HttpStatus.FORBIDDEN, MSG18);
            verifyNothingSaved();
        }

        @Test
        @DisplayName("UTC-154 Loại bài không phải POST/QA: 400 \"Loại bài không hợp lệ.\"")
        void utc154_unknownKind_invalid() {
            String message = "Loại bài không hợp lệ.";

            assertInvalid(() -> service.create(MEMBER, "BLOG", validPost(), null, null), message, "type", message);
            verifyNothingSaved();
        }
    }

    // =================================================================================================================
    @Nested
    @TestMethodOrder(MethodOrderer.DisplayName.class)
    @DisplayName("03. create – UC 1.5 Tạo bài viết: thông tin bài, chủ đề, địa điểm (AF 6a, 6b) (UTC-155 → UTC-181)")
    class CreateFieldChecks {

        @ParameterizedTest(name = "[{index}] title = \"{0}\"")
        @NullAndEmptySource
        @ValueSource(strings = {"   \n\t"})
        @DisplayName("UTC-155 Thiếu tiêu đề (rỗng, khoảng trắng, null): MSG37, lỗi dưới ô tiêu đề, không lưu gì")
        void utc155_missingTitle_msg37(String title) {
            assertInvalid(() -> create(post(title, CONTENT, List.of(1L), List.of(), List.of())), MSG37, "title",
                    TITLE_REQUIRED);
            verifyNothingSaved();
            verifyNoTransaction();
        }

        @ParameterizedTest(name = "[{index}] content = \"{0}\"")
        @NullAndEmptySource
        @ValueSource(strings = {" \r\n  "})
        @DisplayName("UTC-156 Thiếu nội dung (rỗng, khoảng trắng, null): MSG37, lỗi dưới ô nội dung, không lưu gì")
        void utc156_missingContent_msg37(String content) {
            assertInvalid(() -> create(post(TITLE, content, List.of(1L), List.of(), List.of())), MSG37, "content",
                    CONTENT_REQUIRED);
            verifyNothingSaved();
        }

        @Test
        @DisplayName("UTC-157 Thiếu tiêu đề, nội dung và chủ đề: MSG37 và một lỗi dưới mỗi ô")
        void utc157_everythingMissing_oneErrorPerField() {
            ApiException e = assertInvalid(() -> create(post("", "", List.of(), List.of(), List.of())), MSG37,
                    "title", TITLE_REQUIRED);

            assertThat(e.getFields()).containsOnlyKeys("title", "topicIds", "content")
                    .containsEntry("topicIds", TOPIC_REQUIRED)
                    .containsEntry("content", CONTENT_REQUIRED);
            verifyNothingSaved();
        }

        @Test
        @DisplayName("UTC-158 Không gửi phần nội dung bài (request = null): MSG37 với lỗi tiêu đề, chủ đề, nội dung")
        void utc158_nullRequest_msg37() {
            ApiException e = assertInvalid(() -> create(null), MSG37, "title", TITLE_REQUIRED);

            assertThat(e.getFields()).containsOnlyKeys("title", "topicIds", "content");
            verifyNothingSaved();
        }

        @Test
        @DisplayName("UTC-159 Tiêu đề đúng 150 ký tự được đăng")
        void utc159_title150_accepted() {
            String title = "a".repeat(150);

            assertThat(create(post(title, CONTENT, List.of(1L), List.of(), List.of())).status()).isEqualTo("PUBLISHED");
            verify(repository).insertBlog(MEMBER_ID, "POST", title, CONTENT, "PUBLISHED", NOW);
        }

        @Test
        @DisplayName("UTC-160 Tiêu đề 151 ký tự: \"Tiêu đề tối đa 150 ký tự.\"")
        void utc160_title151_refused() {
            assertInvalid(() -> create(post("a".repeat(151), CONTENT, List.of(1L), List.of(), List.of())),
                    TITLE_TOO_LONG, "title", TITLE_TOO_LONG);
            verifyNothingSaved();
        }

        @Test
        @DisplayName("UTC-161 Tiêu đề 156 ký tự nhưng còn 150 ký tự sau khi gộp khoảng trắng: được đăng")
        void utc161_title150AfterCollapsingSpaces_accepted() {
            String raw = "  " + "a".repeat(74) + "      " + "b".repeat(75) + " \n ";
            String expected = "a".repeat(74) + " " + "b".repeat(75);

            create(post(raw, CONTENT, List.of(1L), List.of(), List.of()));

            assertThat(expected).hasSize(150);
            verify(repository).insertBlog(MEMBER_ID, "POST", expected, CONTENT, "PUBLISHED", NOW);
        }

        @Test
        @DisplayName("UTC-162 Nội dung đúng 5.000 ký tự được đăng")
        void utc162_content5000_accepted() {
            String content = "c".repeat(5000);

            create(post(TITLE, content, List.of(1L), List.of(), List.of()));

            verify(repository).insertBlog(MEMBER_ID, "POST", TITLE, content, "PUBLISHED", NOW);
        }

        @Test
        @DisplayName("UTC-163 Nội dung 5.001 ký tự: \"Nội dung tối đa 5.000 ký tự.\"")
        void utc163_content5001_refused() {
            assertInvalid(() -> create(post(TITLE, "c".repeat(5001), List.of(1L), List.of(), List.of())),
                    CONTENT_TOO_LONG, "content", CONTENT_TOO_LONG);
            verifyNothingSaved();
        }

        @Test
        @DisplayName("UTC-164 Nội dung 5.000 ký tự kèm khoảng trắng, xuống dòng ở hai đầu: được đăng sau khi cắt")
        void utc164_content5000PlusSurroundingSpaces_accepted() {
            String content = "c".repeat(5000);

            create(post(TITLE, "\n\n  " + content + "  \r\n", List.of(1L), List.of(), List.of()));

            verify(repository).insertBlog(MEMBER_ID, "POST", TITLE, content, "PUBLISHED", NOW);
        }

        @ParameterizedTest(name = "[{index}] topicIds = {0}")
        @NullAndEmptySource
        @DisplayName("UTC-165 Không chọn chủ đề (rỗng hoặc null): \"Hãy chọn ít nhất 1 chủ đề…\"")
        void utc165_noTopic_refused(List<Long> topicIds) {
            assertInvalid(() -> create(post(TITLE, CONTENT, topicIds, List.of(), List.of())), TOPIC_REQUIRED,
                    "topicIds", TOPIC_REQUIRED);
            verify(repository, never()).countActiveTopics(anyList());
            verifyNothingSaved();
        }

        @Test
        @DisplayName("UTC-166 Chọn đúng 1 chủ đề: được đăng")
        void utc166_oneTopic_accepted() {
            create(post(TITLE, CONTENT, List.of(99L), List.of(), List.of()));

            verify(repository).linkTopics(BLOG_ID, List.of(99L));
        }

        @Test
        @DisplayName("UTC-167 Chọn đúng 3 chủ đề: được đăng")
        void utc167_threeTopics_accepted() {
            create(post(TITLE, CONTENT, List.of(1L, 2L, 9L), List.of(), List.of()));

            verify(repository).linkTopics(BLOG_ID, List.of(1L, 2L, 9L));
        }

        @Test
        @DisplayName("UTC-168 Chọn 4 chủ đề: \"Chọn tối đa 3 chủ đề.\", không cần hỏi kho dữ liệu")
        void utc168_fourTopics_refused() {
            assertInvalid(() -> create(post(TITLE, CONTENT, List.of(1L, 2L, 9L, 99L), List.of(), List.of())),
                    TOO_MANY_TOPICS, "topicIds", TOO_MANY_TOPICS);
            verify(repository, never()).countActiveTopics(anyList());
            verifyNothingSaved();
        }

        @Test
        @DisplayName("UTC-169 Gửi 5 mã chủ đề nhưng chỉ 3 chủ đề khác nhau: được đăng với 3 chủ đề")
        void utc169_fiveIdsThreeDistinctTopics_accepted() {
            create(post(TITLE, CONTENT, List.of(1L, 1L, 2L, 2L, 9L), List.of(), List.of()));

            verify(repository).linkTopics(BLOG_ID, List.of(1L, 2L, 9L));
        }

        @Test
        @DisplayName("UTC-170 Chủ đề đã bị tắt hoặc không tồn tại: \"Có chủ đề không còn trong danh sách…\"")
        void utc170_inactiveTopic_refused() {
            when(repository.countActiveTopics(List.of(1L, 77L))).thenReturn(1);

            assertInvalid(() -> create(post(TITLE, CONTENT, List.of(1L, 77L), List.of(), List.of())), TOPIC_GONE,
                    "topicIds", TOPIC_GONE);
            verifyNothingSaved();
        }

        @Test
        @DisplayName("UTC-171 Đúng 5 địa điểm (3 của Senpai ơi + 2 từ OpenStreetMap): được đăng, gắn đủ 5 nơi")
        void utc171_fivePlaces_accepted() {
            PlacePicks picks = new PlacePicks(List.of(), List.of(osmPlace("N772389775"), osmPlace("W123456")), null);
            when(places.resolve(MEMBER_ID, List.of("N772389775", "W123456"))).thenReturn(picks);
            when(places.save(picks, MEMBER_ID)).thenReturn(List.of(41L, 42L));

            create(post(TITLE, CONTENT, List.of(1L), List.of(30L, 31L, 32L), List.of("N772389775", "W123456")));

            verify(repository).linkPlaces(BLOG_ID, List.of(30L, 31L, 32L, 41L, 42L));
        }

        @Test
        @DisplayName("UTC-172 6 địa điểm (4 + 2): \"Chọn tối đa 5 địa điểm.\", không hỏi OpenStreetMap")
        void utc172_sixPlaces_refused() {
            assertInvalid(() -> create(post(TITLE, CONTENT, List.of(1L), List.of(30L, 31L, 32L, 33L),
                    List.of("N772389775", "W123456"))), TOO_MANY_PLACES, "placeIds", TOO_MANY_PLACES);
            verify(places, never()).resolve(anyLong(), anyList());
            verify(repository, never()).countActivePlaces(anyList());
            verifyNothingSaved();
        }

        @Test
        @DisplayName("UTC-173 Địa điểm của Senpai ơi đã bị gỡ khỏi bản đồ: \"Có địa điểm không còn trên bản đồ…\"")
        void utc173_placeNoLongerOnTheMap_refused() {
            when(repository.countActivePlaces(List.of(30L, 31L))).thenReturn(1);

            assertInvalid(() -> create(post(TITLE, CONTENT, List.of(1L), List.of(30L, 31L), List.of())), PLACE_GONE,
                    "placeIds", PLACE_GONE);
            verifyNothingSaved();
        }

        @ParameterizedTest(name = "[{index}] externalPlaceId = \"{0}\"")
        @ValueSource(strings = {"X123", "N0", "node/123"})
        @DisplayName("UTC-174 Mã địa điểm OpenStreetMap sai dạng: \"Có địa điểm không hợp lệ…\", không hỏi OpenStreetMap")
        void utc174_malformedOsmId_refused(String externalId) {
            assertInvalid(() -> create(post(TITLE, CONTENT, List.of(1L), List.of(), List.of(externalId))),
                    PLACE_INVALID, "placeIds", PLACE_INVALID);
            verify(places, never()).resolve(anyLong(), anyList());
            verifyNothingSaved();
        }

        @Test
        @DisplayName("UTC-175 Mã OpenStreetMap rỗng hoặc null bị bỏ, mã có khoảng trắng được cắt")
        void utc175_blankOsmIdsDroppedAndTrimmed() {
            create(post(TITLE, CONTENT, List.of(1L), List.of(), Arrays.asList(" ", null, " N772389775 ")));

            verify(places).resolve(MEMBER_ID, List.of("N772389775"));
        }

        @Test
        @DisplayName("UTC-176 OpenStreetMap không trả lời: lỗi dưới ô địa điểm, chưa mở giao dịch, không lưu gì")
        void utc176_osmUnavailable_refused() {
            when(places.resolve(MEMBER_ID, List.of("N772389775")))
                    .thenReturn(new PlacePicks(List.of(), List.of(), OSM_UNAVAILABLE));

            assertInvalid(() -> create(post(TITLE, CONTENT, List.of(1L), List.of(), List.of("N772389775"))),
                    OSM_UNAVAILABLE, "placeIds", OSM_UNAVAILABLE);
            verifyNoTransaction();
            verifyNothingSaved();
        }

        @Test
        @DisplayName("UTC-177 Địa điểm OpenStreetMap: nơi đã có được gắn luôn, nơi mới được thêm vào Senpai ơi rồi gắn")
        void utc177_osmPlacesResolvedAndSaved() {
            PlacePicks picks = new PlacePicks(List.of(40L), List.of(osmPlace("W123456")), null);
            when(places.resolve(MEMBER_ID, List.of("N772389775", "W123456"))).thenReturn(picks);
            when(places.save(picks, MEMBER_ID)).thenReturn(List.of(40L, 41L));

            create(post(TITLE, CONTENT, List.of(1L), List.of(30L), List.of("N772389775", "W123456")));

            verify(places).save(picks, MEMBER_ID);
            verify(repository).countActivePlaces(List.of(30L, 40L, 41L));
            verify(repository).linkPlaces(BLOG_ID, List.of(30L, 40L, 41L));
        }

        @Test
        @DisplayName("UTC-178 Nơi OpenStreetMap trùng với địa điểm đã chọn của Senpai ơi: chỉ gắn một lần")
        void utc178_osmTwinOfChosenPlace_linkedOnce() {
            PlacePicks picks = new PlacePicks(List.of(30L), List.of(osmPlace("W123456")), null);
            when(places.resolve(MEMBER_ID, List.of("N772389775", "W123456"))).thenReturn(picks);
            when(places.save(picks, MEMBER_ID)).thenReturn(List.of(30L, 41L));

            create(post(TITLE, CONTENT, List.of(1L), List.of(30L), List.of("N772389775", "W123456")));

            verify(repository).linkPlaces(BLOG_ID, List.of(30L, 41L));
        }

        @Test
        @DisplayName("UTC-179 Moderator gỡ địa điểm giữa lúc kiểm tra và lúc lưu: \"Có địa điểm không còn trên bản đồ…\", giao dịch hoàn tác")
        void utc179_placeRemovedBeforeTheSave_rolledBack() {
            when(repository.countActivePlaces(List.of(30L))).thenReturn(1, 0);

            assertInvalid(() -> create(post(TITLE, CONTENT, List.of(1L), List.of(30L), List.of())), PLACE_GONE,
                    "placeIds", PLACE_GONE);
            verify(repository, never()).insertBlog(anyLong(), any(), any(), any(), any(), any());
            verify(repository, never()).addUsage(anyLong(), any(), anyInt(), anyInt());
            verifyRolledBack();
        }

        @Test
        @DisplayName("UTC-180 Có ô khác sai thì không hỏi OpenStreetMap")
        void utc180_otherFieldWrong_osmNotAsked() {
            assertInvalid(() -> create(post("", CONTENT, List.of(1L), List.of(), List.of("N772389775"))), MSG37,
                    "title", TITLE_REQUIRED);
            verify(places, never()).resolve(anyLong(), anyList());
        }

        @Test
        @DisplayName("UTC-181 Nhiều ô sai cùng lúc: một lỗi dưới mỗi ô, dòng trên cùng là lỗi đầu tiên theo thứ tự màn hình")
        void utc181_allFieldErrorsAtOnce() {
            ApiException e = apiError(() -> create(post("a".repeat(151), "c".repeat(5001), List.of(1L, 2L, 9L, 99L),
                    List.of(30L, 31L, 32L, 33L), List.of("N1", "N2")), photos(6), null));

            assertThat(e.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(e.getMessage()).isEqualTo(TITLE_TOO_LONG);
            assertThat(e.getFields()).containsOnlyKeys("title", "topicIds", "placeIds", "content", "images")
                    .containsEntry("topicIds", TOO_MANY_TOPICS)
                    .containsEntry("placeIds", TOO_MANY_PLACES)
                    .containsEntry("content", CONTENT_TOO_LONG)
                    .containsEntry("images", IMAGE_RULE);
            verify(media, never()).prepareImage(any());
            verify(places, never()).resolve(anyLong(), anyList());
            verifyNothingSaved();
        }
    }

    // =================================================================================================================
    @Nested
    @TestMethodOrder(MethodOrderer.DisplayName.class)
    @DisplayName("04. create – UC 1.5 Tạo bài viết: ảnh và video (AF 8a) (UTC-182 → UTC-197)")
    class CreateFiles {

        @Test
        @DisplayName("UTC-182 Bài có 1 ảnh: chờ quét (PROCESSING), ảnh được lưu vào thư mục blog và gắn vào bài, chưa cộng điểm")
        void utc182_onePhoto_processing() {
            Created created = create(validPost(), photos(1), null);

            assertThat(created).isEqualTo(new Created(BLOG_ID, "POST", "PROCESSING"));
            verify(repository).insertBlog(MEMBER_ID, "POST", TITLE, CONTENT, "PROCESSING", NOW);
            verify(media).save(MEMBER_ID, "blog", PHOTO);
            verify(repository).linkMedia(BLOG_ID, 900L, 0);
            verify(repository).addUsage(MEMBER_ID, TODAY, 1, 0);
            verify(publisher, never()).awardOnce(anyLong(), anyLong());
        }

        @Test
        @DisplayName("UTC-183 Đúng 5 ảnh: cả 5 được kiểm tra, lưu và gắn theo thứ tự đã chọn")
        void utc183_fivePhotos_allSavedInOrder() {
            create(validPost(), photos(5), null);

            verify(media, times(5)).prepareImage(any());
            InOrder order = inOrder(repository);
            for (int i = 0; i < 5; i++) {
                order.verify(repository).linkMedia(BLOG_ID, 900L + i, i);
            }
        }

        @Test
        @DisplayName("UTC-184 6 ảnh: quy tắc ảnh hiện dưới ô ảnh, không ảnh nào được giải mã, không lưu gì")
        void utc184_sixPhotos_refused() {
            assertInvalid(() -> create(validPost(), photos(6), null), IMAGE_RULE, "images", IMAGE_RULE);
            verify(media, never()).prepareImage(any());
            verifyNothingSaved();
        }

        @Test
        @DisplayName("UTC-185 6 phần ảnh nhưng một phần rỗng: tính là 5 ảnh, được nhận")
        void utc185_sixPartsOneEmpty_countsFive() {
            List<MultipartFile> files = photos(5);
            files.add(2, emptyFile("images"));

            create(validPost(), files, null);

            verify(media, times(5)).prepareImage(any());
            verify(repository, times(5)).linkMedia(eq(BLOG_ID), anyLong(), anyInt());
        }

        @Test
        @DisplayName("UTC-186 Phần ảnh rỗng hoặc null bị bỏ qua: bài chỉ có chữ, được đăng ngay")
        void utc186_emptyAndNullPhotoParts_ignored() {
            Created created = create(validPost(), Arrays.<MultipartFile>asList(emptyFile("images"), null), null);

            assertThat(created.status()).isEqualTo("PUBLISHED");
            verify(media, never()).prepareImage(any());
        }

        @Test
        @DisplayName("UTC-187 Tệp không phải ảnh (hoặc ảnh quá 20 MB): quy tắc ảnh dưới ô ảnh, không lưu gì")
        void utc187_notAPhoto_refused() {
            when(media.prepareImage(any())).thenThrow(new InvalidMediaException(IMAGE_RULE));

            assertInvalid(() -> create(validPost(), photos(1), null), IMAGE_RULE, "images", IMAGE_RULE);
            verifyNothingSaved();
            verifyNoTransaction();
        }

        @Test
        @DisplayName("UTC-188 Ảnh thứ 2 hỏng: dừng ở ảnh đó, ảnh thứ 3 không được giải mã")
        void utc188_secondPhotoBad_stopsThere() {
            when(media.prepareImage(any())).thenReturn(PHOTO).thenThrow(new InvalidMediaException(IMAGE_RULE));

            assertInvalid(() -> create(validPost(), photos(3), null), IMAGE_RULE, "images", IMAGE_RULE);
            verify(media, times(2)).prepareImage(any());
        }

        @Test
        @DisplayName("UTC-189 Máy chủ đang xử lý nhiều ảnh: \"Máy chủ đang xử lý nhiều ảnh…\" dưới ô ảnh")
        void utc189_serverBusy_refused() {
            when(media.prepareImage(any())).thenThrow(new InvalidMediaException(BUSY));

            assertInvalid(() -> create(validPost(), photos(1), null), BUSY, "images", BUSY);
            verifyNothingSaved();
        }

        @Test
        @DisplayName("UTC-190 Bài có 1 video: chờ quét, video được lưu, số video trong ngày tăng 1")
        void utc190_oneVideo_processingAndCounted() {
            Created created = create(validPost(), null, videoFile());

            assertThat(created.status()).isEqualTo("PROCESSING");
            verify(media).prepareVideo(any());
            verify(media).save(MEMBER_ID, "blog", VIDEO);
            verify(repository).linkMedia(BLOG_ID, 900L, 0);
            verify(repository).addUsage(MEMBER_ID, TODAY, 1, 1);
        }

        @Test
        @DisplayName("UTC-191 Video 31 giây: \"Video dài 31 giây. Video tối đa 30 giây.\" dưới ô video")
        void utc191_video31Seconds_refused() {
            when(media.prepareVideo(any())).thenThrow(new InvalidMediaException(VIDEO_TOO_LONG));

            assertInvalid(() -> create(validPost(), null, videoFile()), VIDEO_TOO_LONG, "video", VIDEO_TOO_LONG);
            verifyNothingSaved();
        }

        @Test
        @DisplayName("UTC-192 Video không đọc được: \"Không đọc được video. Hãy dùng video MP4 hoặc MOV.\"")
        void utc192_unreadableVideo_refused() {
            when(media.prepareVideo(any())).thenThrow(new InvalidMediaException(VIDEO_UNREADABLE));

            assertInvalid(() -> create(validPost(), null, videoFile()), VIDEO_UNREADABLE, "video", VIDEO_UNREADABLE);
            verifyNothingSaved();
        }

        @Test
        @DisplayName("UTC-193 Phần video rỗng bị bỏ qua: bài được đăng ngay, không tính video")
        void utc193_emptyVideoPart_ignored() {
            Created created = create(validPost(), null, emptyFile("video"));

            assertThat(created.status()).isEqualTo("PUBLISHED");
            verify(media, never()).prepareVideo(any());
            verify(repository).addUsage(MEMBER_ID, TODAY, 1, 0);
        }

        @Test
        @DisplayName("UTC-194 2 ảnh + 1 video: ảnh lưu trước, video sau, thứ tự 0, 1, 2")
        void utc194_photosThenVideo_inOrder() {
            create(validPost(), photos(2), videoFile());

            InOrder order = inOrder(media, repository);
            order.verify(media).save(MEMBER_ID, "blog", PHOTO);
            order.verify(repository).linkMedia(BLOG_ID, 900L, 0);
            order.verify(media).save(MEMBER_ID, "blog", PHOTO);
            order.verify(repository).linkMedia(BLOG_ID, 901L, 1);
            order.verify(media).save(MEMBER_ID, "blog", VIDEO);
            order.verify(repository).linkMedia(BLOG_ID, 902L, 2);
        }

        @Test
        @DisplayName("UTC-195 Việc quét chỉ bắt đầu sau khi giao dịch commit")
        void utc195_scanStartsOnlyAfterCommit() {
            create(validPost(), photos(1), null);

            verify(publisher, never()).scanSoon(anyLong());
            afterCommit();
            verify(publisher).scanSoon(BLOG_ID);
            verify(publisher, never()).awardOnce(anyLong(), anyLong());
        }

        @Test
        @DisplayName("UTC-196 Bài có ảnh bị từ chối ở bước sau: không lưu tệp, không hẹn quét")
        void utc196_refusedPostWithPhoto_noFileNoScan() {
            doThrow(ApiException.badRequest(msg36("fb88"))).when(filter).checkKeywords(TITLE);

            assertApiError(() -> create(validPost(), photos(1), null), HttpStatus.BAD_REQUEST, msg36("fb88"));
            verify(media, never()).save(anyLong(), any(), any());
            assertThat(TransactionSynchronizationManager.getSynchronizations()).isEmpty();
            verify(publisher, never()).scanSoon(anyLong());
        }

        @Test
        @DisplayName("UTC-197 6 ảnh và video hỏng: lỗi dưới cả ô ảnh và ô video")
        void utc197_photoAndVideoErrorsTogether() {
            when(media.prepareVideo(any())).thenThrow(new InvalidMediaException(VIDEO_TOO_LONG));

            ApiException e = assertInvalid(() -> create(validPost(), photos(6), videoFile()), IMAGE_RULE, "images",
                    IMAGE_RULE);

            assertThat(e.getFields()).containsOnlyKeys("images", "video").containsEntry("video", VIDEO_TOO_LONG);
            verifyNothingSaved();
        }
    }

    // =================================================================================================================
    @Nested
    @TestMethodOrder(MethodOrderer.DisplayName.class)
    @DisplayName("05. create – UC 1.5 Tạo bài viết: link, hạn mức tài khoản mới, từ khóa cấm (AF 7a, 7b, 7c) (UTC-198 → UTC-221)")
    class CreateContentChecks {

        @Test
        @DisplayName("UTC-198 Link trong tiêu đề khi chưa có tag định danh: bị từ chối, chưa xét hạn mức và từ khóa, giao dịch hoàn tác")
        void utc198_linkInTitleWithoutTag_refused() {
            String title = "Thuê nhà qua https://example.com";
            doThrow(ApiException.badRequest(LINK_NEEDS_TAG)).when(filter).checkLinks(title, MEMBER_ID);

            assertApiError(() -> create(post(title, CONTENT, List.of(1L), List.of(), List.of())),
                    HttpStatus.BAD_REQUEST, LINK_NEEDS_TAG);
            verify(repository, never()).lockUsage(anyLong(), any());
            verify(filter, never()).checkKeywords(any());
            verifyNothingSaved();
            verifyRolledBack();
        }

        @Test
        @DisplayName("UTC-199 Link rút gọn trong nội dung: \"Không dùng link rút gọn…\"")
        void utc199_shortLinkInContent_refused() {
            String content = "Đăng ký ở bit.ly/abc123";
            doThrow(ApiException.badRequest(SHORT_LINK)).when(filter).checkLinks(content, MEMBER_ID);

            assertApiError(() -> create(post(TITLE, content, List.of(1L), List.of(), List.of())),
                    HttpStatus.BAD_REQUEST, SHORT_LINK);
            verifyNothingSaved();
        }

        @Test
        @DisplayName("UTC-200 Thành viên có tag định danh: link đầy đủ và số điện thoại được đăng như đã viết")
        void utc200_taggedMember_linkAccepted() {
            String content = "Xem https://www.city.arakawa.tokyo.jp hoặc gọi 090-1234-5678";

            create(post(TITLE, content, List.of(1L), List.of(), List.of()));

            verify(filter).checkLinks(content, MEMBER_ID);
            verify(repository).insertBlog(MEMBER_ID, "POST", TITLE, content, "PUBLISHED", NOW);
        }

        @Test
        @DisplayName("UTC-201 Ảnh thứ 2 có mã QR, chưa có tag định danh: bị từ chối dưới ô ảnh, không lưu tệp nào")
        void utc201_qrCodeWithoutTag_refused() {
            when(media.prepareImage(any())).thenReturn(PHOTO, photoWithQr("https://line.me/ti/p/abc"));
            doThrow(ApiException.invalid(QR_NEEDS_TAG, Map.of("images", QR_NEEDS_TAG))).when(filter)
                    .checkQrCodes(List.of(List.of(), List.of("https://line.me/ti/p/abc")), MEMBER_ID);

            assertInvalid(() -> create(validPost(), photos(2), null), QR_NEEDS_TAG, "images", QR_NEEDS_TAG);
            verify(repository, never()).lockUsage(anyLong(), any());
            verifyNothingSaved();
        }

        @Test
        @DisplayName("UTC-202 Kiểm tra mã QR theo thứ tự ảnh đã chọn, bỏ qua video")
        void utc202_qrCodesOfPhotosOnly_inOrder() {
            when(media.prepareImage(any())).thenReturn(photoWithQr("https://senpaioi.com/a"), PHOTO);

            create(validPost(), photos(2), videoFile());

            verify(filter).checkQrCodes(List.of(List.of("https://senpaioi.com/a"), List.of()), MEMBER_ID);
            verify(filter).checkQrKeywords(List.of(List.of("https://senpaioi.com/a"), List.of()));
        }

        @Test
        @DisplayName("UTC-203 Tài khoản mới (2 ngày) đăng bài thứ 3 trong ngày: được đăng")
        void utc203_newAccountThirdPost_accepted() {
            memberAged(Duration.ofDays(2));
            when(repository.lockUsage(MEMBER_ID, TODAY)).thenReturn(new Usage(2, 0, 0));

            assertThat(create(validPost()).status()).isEqualTo("PUBLISHED");
            verify(repository).addUsage(MEMBER_ID, TODAY, 1, 0);
        }

        @Test
        @DisplayName("UTC-204 Tài khoản mới đăng bài thứ 4 trong ngày: 429 MSG44, chưa xét từ khóa, không lưu gì")
        void utc204_newAccountFourthPost_msg44() {
            memberAged(Duration.ofDays(2));
            when(repository.lockUsage(MEMBER_ID, TODAY)).thenReturn(new Usage(3, 0, 0));

            assertApiError(() -> create(validPost()), HttpStatus.TOO_MANY_REQUESTS, MSG44_POSTS);
            verify(filter, never()).checkKeywords(any());
            verifyNothingSaved();
            verifyRolledBack();
        }

        @Test
        @DisplayName("UTC-205 Tài khoản tròn 7 ngày tuổi, đã đăng 10 bài hôm nay: không bị giới hạn")
        void utc205_accountExactlySevenDaysOld_notLimited() {
            memberAged(Duration.ofDays(7));
            when(repository.lockUsage(MEMBER_ID, TODAY)).thenReturn(new Usage(10, 0, 0));

            assertThat(create(validPost()).status()).isEqualTo("PUBLISHED");
        }

        @Test
        @DisplayName("UTC-206 Tài khoản 7 ngày thiếu 1 phút, đã đăng 3 bài: vẫn bị giới hạn (MSG44)")
        void utc206_accountOneMinuteShortOfSevenDays_limited() {
            memberAged(Duration.ofDays(7).minusMinutes(1));
            when(repository.lockUsage(MEMBER_ID, TODAY)).thenReturn(new Usage(3, 0, 0));

            assertApiError(() -> create(validPost()), HttpStatus.TOO_MANY_REQUESTS, MSG44_POSTS);
        }

        @Test
        @DisplayName("UTC-207 Admin đặt 10 ngày, 5 bài/ngày: tài khoản 8 ngày tuổi đăng bài thứ 5 được nhận")
        void utc207_adminSettings_fifthPostAccepted() {
            settingsOf(10, 5, 2, 1);
            memberAged(Duration.ofDays(8));
            when(repository.lockUsage(MEMBER_ID, TODAY)).thenReturn(new Usage(4, 0, 0));

            assertThat(create(validPost()).status()).isEqualTo("PUBLISHED");
        }

        @Test
        @DisplayName("UTC-208 Admin đặt 10 ngày, 5 bài/ngày: bài thứ 6 bị từ chối, thông báo nêu đúng 10 ngày và 5 bài")
        void utc208_adminSettings_sixthPostRefused() {
            settingsOf(10, 5, 2, 1);
            memberAged(Duration.ofDays(8));
            when(repository.lockUsage(MEMBER_ID, TODAY)).thenReturn(new Usage(5, 0, 0));

            assertApiError(() -> create(validPost()), HttpStatus.TOO_MANY_REQUESTS,
                    "Bạn đã đạt giới hạn đăng bài trong ngày của tài khoản mới (dưới 10 ngày: tối đa 5 bài viết hoặc "
                            + "review mỗi ngày). Vui lòng thử lại vào ngày mai.");
        }

        @Test
        @DisplayName("UTC-209 Tài khoản mới đã có 1 video hôm nay, đăng thêm bài có video: 429 giới hạn video")
        void utc209_newAccountSecondVideo_refused() {
            memberAged(Duration.ofDays(2));
            when(repository.lockUsage(MEMBER_ID, TODAY)).thenReturn(new Usage(1, 0, 1));

            assertApiError(() -> create(validPost(), null, videoFile()), HttpStatus.TOO_MANY_REQUESTS, VIDEO_LIMIT);
            verifyNothingSaved();
        }

        @Test
        @DisplayName("UTC-210 Tài khoản mới đã có 1 video hôm nay, bỏ video: bài chỉ có chữ được đăng")
        void utc210_newAccountWithoutVideo_accepted() {
            memberAged(Duration.ofDays(2));
            when(repository.lockUsage(MEMBER_ID, TODAY)).thenReturn(new Usage(1, 0, 1));

            assertThat(create(validPost()).status()).isEqualTo("PUBLISHED");
            verify(repository).addUsage(MEMBER_ID, TODAY, 1, 0);
        }

        @Test
        @DisplayName("UTC-211 Tài khoản mới chưa có video hôm nay: video đầu tiên được nhận và được tính")
        void utc211_newAccountFirstVideo_acceptedAndCounted() {
            memberAged(Duration.ofDays(2));

            assertThat(create(validPost(), null, videoFile()).status()).isEqualTo("PROCESSING");
            verify(repository).addUsage(MEMBER_ID, TODAY, 1, 1);
        }

        @Test
        @DisplayName("UTC-212 Tài khoản đủ tuổi đã có 5 video hôm nay: video vẫn được nhận")
        void utc212_oldAccountManyVideos_notLimited() {
            when(repository.lockUsage(MEMBER_ID, TODAY)).thenReturn(new Usage(6, 0, 5));

            assertThat(create(validPost(), null, videoFile()).status()).isEqualTo("PROCESSING");
            verify(repository).addUsage(MEMBER_ID, TODAY, 1, 1);
        }

        @Test
        @DisplayName("UTC-213 Đã đủ cả bài và video: báo giới hạn bài trước (MSG44)")
        void utc213_postsLimitBeforeVideoLimit() {
            memberAged(Duration.ofDays(2));
            when(repository.lockUsage(MEMBER_ID, TODAY)).thenReturn(new Usage(3, 0, 1));

            assertApiError(() -> create(validPost(), null, videoFile()), HttpStatus.TOO_MANY_REQUESTS, MSG44_POSTS);
        }

        @Test
        @DisplayName("UTC-214 Hạn mức đọc từ hàng đã khóa của ngày hôm nay (không đọc bản không khóa)")
        void utc214_limitReadFromTheLockedRow() {
            memberAged(Duration.ofDays(2));
            when(repository.usage(MEMBER_ID, TODAY)).thenReturn(new Usage(3, 0, 0));

            assertThat(create(validPost()).status()).isEqualTo("PUBLISHED");
            verify(repository).lockUsage(MEMBER_ID, TODAY);
            verify(repository, never()).usage(anyLong(), any());
        }

        @Test
        @DisplayName("UTC-215 Từ khóa cấm trong tiêu đề: MSG36 nêu từ khóa, không lưu gì, không tính hạn mức, hoàn tác")
        void utc215_bannedKeywordInTitle_msg36() {
            String title = "Kèo fb88 hôm nay";
            doThrow(ApiException.badRequest(msg36("fb88"))).when(filter).checkKeywords(title);

            assertApiError(() -> create(post(title, CONTENT, List.of(1L), List.of(), List.of())),
                    HttpStatus.BAD_REQUEST, msg36("fb88"));
            verifyNothingSaved();
            verifyRolledBack();
        }

        @Test
        @DisplayName("UTC-216 Từ khóa cấm trong nội dung: MSG36 \"việc nhẹ lương cao\", không lưu gì")
        void utc216_bannedKeywordInContent_msg36() {
            String content = "Tuyển gấp: việc nhẹ, lương-cao cho du học sinh";
            doThrow(ApiException.badRequest(msg36("việc nhẹ lương cao"))).when(filter).checkKeywords(content);

            assertApiError(() -> create(post(TITLE, content, List.of(1L), List.of(), List.of())),
                    HttpStatus.BAD_REQUEST, msg36("việc nhẹ lương cao"));
            verifyNothingSaved();
        }

        @Test
        @DisplayName("UTC-217 Mã QR trong ảnh chứa từ khóa cấm: bị từ chối dưới ô ảnh, không lưu tệp")
        void utc217_bannedKeywordInQrCode_refused() {
            String message = "Chưa đăng được: mã QR trong ảnh thứ 1 bạn vừa chọn chứa từ khóa không được phép “fb88”. "
                    + "Hãy dùng ảnh khác.";
            when(media.prepareImage(any())).thenReturn(photoWithQr("https://fb88.example"));
            doThrow(ApiException.invalid(message, Map.of("images", message))).when(filter)
                    .checkQrKeywords(List.of(List.of("https://fb88.example")));

            assertInvalid(() -> create(validPost(), photos(1), null), message, "images", message);
            verifyNothingSaved();
        }

        @Test
        @DisplayName("UTC-218 Thứ tự: thiếu tiêu đề và có link → báo MSG37 trước, chưa xét link")
        void utc218_fieldsBeforeLinks() {
            assertInvalid(() -> create(post("", "Xem https://example.com", List.of(1L), List.of(), List.of())), MSG37,
                    "title", TITLE_REQUIRED);
            verify(filter, never()).checkLinks(any(), anyLong());
        }

        @Test
        @DisplayName("UTC-219 Thứ tự: đã hết hạn mức và có link → báo link trước, chưa xét hạn mức")
        void utc219_linksBeforeDailyLimit() {
            memberAged(Duration.ofDays(2));
            when(repository.lockUsage(MEMBER_ID, TODAY)).thenReturn(new Usage(3, 0, 0));
            String content = "Xem https://example.com";
            doThrow(ApiException.badRequest(LINK_NEEDS_TAG)).when(filter).checkLinks(content, MEMBER_ID);

            assertApiError(() -> create(post(TITLE, content, List.of(1L), List.of(), List.of())),
                    HttpStatus.BAD_REQUEST, LINK_NEEDS_TAG);
            verify(repository, never()).lockUsage(anyLong(), any());
        }

        @Test
        @DisplayName("UTC-220 Thứ tự: đã hết hạn mức và có từ khóa cấm → báo hạn mức (429) trước, chưa xét từ khóa")
        void utc220_dailyLimitBeforeKeywords() {
            memberAged(Duration.ofDays(2));
            when(repository.lockUsage(MEMBER_ID, TODAY)).thenReturn(new Usage(3, 0, 0));
            String content = "Cần vay nóng gấp";
            doThrow(ApiException.badRequest(msg36("vay nóng"))).when(filter).checkKeywords(content);

            assertApiError(() -> create(post(TITLE, content, List.of(1L), List.of(), List.of())),
                    HttpStatus.TOO_MANY_REQUESTS, MSG44_POSTS);
            verify(filter, never()).checkKeywords(any());
        }

        @Test
        @DisplayName("UTC-221 Thứ tự: có link và từ khóa cấm → báo link trước, chưa xét từ khóa")
        void utc221_linksBeforeKeywords() {
            String title = "Vay nóng";
            String content = "Xem https://example.com";
            doThrow(ApiException.badRequest(LINK_NEEDS_TAG)).when(filter).checkLinks(content, MEMBER_ID);
            doThrow(ApiException.badRequest(msg36("vay nóng"))).when(filter).checkKeywords(title);

            assertApiError(() -> create(post(title, content, List.of(1L), List.of(), List.of())),
                    HttpStatus.BAD_REQUEST, LINK_NEEDS_TAG);
            verify(filter, never()).checkKeywords(any());
        }
    }

    // =================================================================================================================
    @Nested
    @TestMethodOrder(MethodOrderer.DisplayName.class)
    @DisplayName("06. BlogWriteRepository.lockUsage – UC 1.5: khóa hàng hạn mức trong ngày (UTC-222 → UTC-222)")
    class UsageLockSql {

        @Test
        @SuppressWarnings("unchecked")
        @DisplayName("UTC-222 Hàng hạn mức của ngày được tạo nếu chưa có rồi đọc với FOR UPDATE (khóa đến hết giao dịch)")
        void utc222_usageRowCreatedThenReadForUpdate() {
            JdbcTemplate jdbc = mock(JdbcTemplate.class);
            BlogWriteRepository real = new BlogWriteRepository(jdbc, mock(NamedParameterJdbcTemplate.class));
            when(jdbc.queryForObject(contains("FOR UPDATE"), any(RowMapper.class), eq(MEMBER_ID),
                    eq(Date.valueOf(TODAY)))).thenReturn(new Usage(2, 1, 0));

            Usage usage = real.lockUsage(MEMBER_ID, TODAY);

            assertThat(usage).isEqualTo(new Usage(2, 1, 0));
            InOrder order = inOrder(jdbc);
            order.verify(jdbc).update(contains("INSERT INTO user_daily_usage (user_id, usage_date) VALUES (?, ?)"),
                    eq(MEMBER_ID), eq(Date.valueOf(TODAY)));
            order.verify(jdbc).queryForObject(
                    eq("SELECT posts, pins, videos FROM user_daily_usage WHERE user_id = ? AND usage_date = ? FOR UPDATE"),
                    any(RowMapper.class), eq(MEMBER_ID), eq(Date.valueOf(TODAY)));
        }
    }

    // =================================================================================================================
    @Nested
    @TestMethodOrder(MethodOrderer.DisplayName.class)
    @DisplayName("07. editData – UC 1.6 Cập nhật bài viết: mở form \"Sửa bài\" (UTC-254 → UTC-266)")
    class EditForm {

        @Test
        @DisplayName("UTC-254 Tác giả mở form sửa bài đang đăng: điền sẵn tiêu đề, nội dung, chủ đề, địa điểm")
        void utc254_authorOpensPublishedPost() {
            when(repository.topicIds(BLOG_ID)).thenReturn(List.of(1L, 2L));
            when(feedRepository.placesOf(List.of(BLOG_ID))).thenReturn(Map.of(BLOG_ID, List.of(NIPPORI)));

            EditData data = service.editData(MEMBER, KIND, BLOG_ID);

            assertThat(data.id()).isEqualTo(BLOG_ID);
            assertThat(data.type()).isEqualTo("POST");
            assertThat(data.status()).isEqualTo("PUBLISHED");
            assertThat(data.title()).isEqualTo("Tiêu đề cũ");
            assertThat(data.content()).isEqualTo("Nội dung cũ");
            assertThat(data.topicIds()).containsExactly(1L, 2L);
            assertThat(data.places()).containsExactly(NIPPORI);
        }

        @Test
        @DisplayName("UTC-255 Ảnh, video đang gắn có đường dẫn /media/…, trạng thái quét và thứ tự")
        void utc255_attachedMediaWithUrlsAndScanStatus() {
            when(repository.attachedMedia(BLOG_ID)).thenReturn(List.of(image(701L, "PASSED", 0),
                    video(702L, "PROCESSING", 1)));

            List<EditMedia> mediaList = service.editData(MEMBER, KIND, BLOG_ID).media();

            assertThat(mediaList).containsExactly(
                    new EditMedia(701L, "IMAGE", "/media/blog/2026/11/photo-701.jpg", "PASSED", 0),
                    new EditMedia(702L, "VIDEO", "/media/blog/2026/11/clip-702.mp4", "PROCESSING", 1));
        }

        @Test
        @DisplayName("UTC-256 Form sửa có cùng lựa chọn như form viết bài: chủ đề, quy tắc, hạn mức, quyền chèn link")
        void utc256_editFormHasComposeOptions() {
            memberAged(Duration.ofDays(2));
            when(repository.usage(MEMBER_ID, TODAY)).thenReturn(new Usage(1, 0, 0));
            when(filter.hasIdentityTag(MEMBER_ID)).thenReturn(true);

            ComposeOptions options = service.editData(MEMBER, KIND, BLOG_ID).options();

            assertThat(options.topics()).containsExactlyElementsOf(TOPICS);
            assertThat(options.limits()).isEqualTo(new Limits(150, 5000, 3, 5, 5, 20, 1, 30, 50, false));
            assertThat(options.quota()).isEqualTo(new Quota(true, 7, 2, 1, 3, 0, 2, 0, 1));
            assertThat(options.canPostLinks()).isTrue();
        }

        @ParameterizedTest(name = "[{index}] status = {0}")
        @ValueSource(strings = {"PROCESSING", "REJECTED"})
        @DisplayName("UTC-257 Bài đang chờ quét hoặc bị từ chối vẫn mở được form sửa")
        void utc257_processingOrRejectedPostOpens(String status) {
            when(repository.editableBlog(eq(BLOG_ID), anyBoolean())).thenReturn(Optional.of(editable(status)));

            assertThat(service.editData(MEMBER, KIND, BLOG_ID).status()).isEqualTo(status);
        }

        @Test
        @DisplayName("UTC-258 Bài không gắn địa điểm: danh sách địa điểm rỗng")
        void utc258_postWithoutPlace_emptyPlaces() {
            when(feedRepository.placesOf(List.of(BLOG_ID))).thenReturn(Map.of());

            assertThat(service.editData(MEMBER, KIND, BLOG_ID).places()).isEmpty();
        }

        @Test
        @DisplayName("UTC-259 Mở form chỉ đọc bài, không khóa hàng, không thay đổi gì")
        void utc259_readsWithoutLockAndChangesNothing() {
            service.editData(MEMBER, KIND, BLOG_ID);

            verify(repository).editableBlog(BLOG_ID, false);
            verify(repository, never()).editableBlog(BLOG_ID, true);
            verifyNothingChanged();
            verifyNoTransaction();
        }

        @Test
        @DisplayName("UTC-260 Đường dẫn ảnh theo địa chỉ media cấu hình (https://cdn.senpaioi.com/media/)")
        void utc260_mediaUrlFollowsConfiguredBase() {
            BlogWriteService cdn = new BlogWriteService(repository, feedRepository, filter, media, settings, publisher,
                    places, txManager, Clock.fixed(NOW, ZoneOffset.UTC), "https://cdn.senpaioi.com/media/");
            when(repository.attachedMedia(BLOG_ID)).thenReturn(List.of(image(701L, "PASSED", 0)));

            assertThat(cdn.editData(MEMBER, KIND, BLOG_ID).media()).extracting(EditMedia::url)
                    .containsExactly("https://cdn.senpaioi.com/media/blog/2026/11/photo-701.jpg");
        }

        @ParameterizedTest(name = "[{index}] status = {0}")
        @ValueSource(strings = {"HIDDEN", "REMOVED"})
        @DisplayName("UTC-261 Bài bị ẩn do báo cáo hoặc bị Moderator gỡ: 404 MSG19")
        void utc261_hiddenOrRemovedPost_notFound(String status) {
            when(repository.editableBlog(eq(BLOG_ID), anyBoolean())).thenReturn(Optional.of(editable(status)));

            assertApiError(() -> service.editData(MEMBER, KIND, BLOG_ID), HttpStatus.NOT_FOUND, MSG19);
        }

        @Test
        @DisplayName("UTC-262 Bài không tồn tại hoặc đã xóa: 404 MSG19")
        void utc262_missingPost_notFound() {
            when(repository.editableBlog(eq(BLOG_ID), anyBoolean())).thenReturn(Optional.empty());

            assertApiError(() -> service.editData(MEMBER, KIND, BLOG_ID), HttpStatus.NOT_FOUND, MSG19);
        }

        @Test
        @DisplayName("UTC-263 Mã của một câu hỏi (QA) mở qua trang Blog: 404 MSG19")
        void utc263_questionIdOnBlog_notFound() {
            when(repository.editableBlog(eq(BLOG_ID), anyBoolean()))
                    .thenReturn(Optional.of(editable(MEMBER_ID, "QA", "PUBLISHED")));

            assertApiError(() -> service.editData(MEMBER, KIND, BLOG_ID), HttpStatus.NOT_FOUND, MSG19);
        }

        @Test
        @DisplayName("UTC-264 Bài của người khác: 403 MSG18")
        void utc264_someoneElsesPost_forbidden() {
            when(repository.editableBlog(eq(BLOG_ID), anyBoolean()))
                    .thenReturn(Optional.of(editable(OTHER_ID, "POST", "PUBLISHED")));

            assertApiError(() -> service.editData(MEMBER, KIND, BLOG_ID), HttpStatus.FORBIDDEN, MSG18);
            verify(repository, never()).attachedMedia(anyLong());
        }

        @Test
        @DisplayName("UTC-265 Khách chưa đăng nhập: 401 \"Bạn cần đăng nhập để viết bài.\"")
        void utc265_guest_unauthorized() {
            assertApiError(() -> service.editData(null, KIND, BLOG_ID), HttpStatus.UNAUTHORIZED, SIGN_IN);
            verifyNoInteractions(repository);
        }

        @ParameterizedTest(name = "[{index}] role = {0}")
        @ValueSource(strings = {"MODERATOR", "ACADEMIC_MANAGER", "ADMIN"})
        @DisplayName("UTC-266 Tài khoản nhân viên: 403 MSG18, không đọc bài")
        void utc266_staff_forbidden(String role) {
            AppUserPrincipal staff = staff(role);

            assertApiError(() -> service.editData(staff, KIND, BLOG_ID), HttpStatus.FORBIDDEN, MSG18);
            verify(repository, never()).editableBlog(anyLong(), anyBoolean());
        }
    }

    // =================================================================================================================
    @Nested
    @TestMethodOrder(MethodOrderer.DisplayName.class)
    @DisplayName("08. update – UC 1.6 Cập nhật bài viết: quyền và luồng chính (UTC-267 → UTC-282)")
    class UpdateMainFlow {

        @Test
        @DisplayName("UTC-267 Sửa tiêu đề, nội dung của bài đang đăng: lưu, bài vẫn PUBLISHED (\"· đã sửa\")")
        void utc267_editPublishedPost_staysPublished() {
            Created created = update(validEdit(List.of()));

            assertThat(created).isEqualTo(new Created(BLOG_ID, "POST", "PUBLISHED"));
            verify(repository).updateBlog(BLOG_ID, NEW_TITLE, NEW_CONTENT, "PUBLISHED", NOW);
        }

        @Test
        @DisplayName("UTC-268 Chủ đề và địa điểm được thay bằng lựa chọn mới")
        void utc268_topicsAndPlacesReplaced() {
            update(validEdit(List.of()));

            InOrder order = inOrder(repository);
            order.verify(repository).unlinkTopics(BLOG_ID);
            order.verify(repository).linkTopics(BLOG_ID, List.of(3L));
            order.verify(repository).unlinkPlaces(BLOG_ID);
            order.verify(repository).linkPlaces(BLOG_ID, List.of(31L));
        }

        @Test
        @DisplayName("UTC-269 Bài vẫn đăng: chỉ hỏi trả điểm một lần (không cộng lại), không quét lại")
        void utc269_publishedPost_pointsOnceNoScan() {
            update(validEdit(List.of()));

            verify(publisher).awardOnce(MEMBER_ID, BLOG_ID);
            verify(publisher, never()).scanSoon(anyLong());
            assertThat(TransactionSynchronizationManager.getSynchronizations()).isEmpty();
        }

        @Test
        @DisplayName("UTC-270 Sửa bài không tính là bài mới: tài khoản mới đã đủ 3 bài hôm nay vẫn sửa được, không tính hạn mức")
        void utc270_editIsNotANewPost() {
            memberAged(Duration.ofDays(2));
            when(repository.lockUsage(MEMBER_ID, TODAY)).thenReturn(new Usage(3, 0, 0));

            assertThat(update(validEdit(List.of())).status()).isEqualTo("PUBLISHED");
            verify(repository, never()).lockUsage(anyLong(), any());
            verify(repository, never()).addUsage(anyLong(), any(), anyInt(), anyInt());
        }

        @Test
        @DisplayName("UTC-271 Bài được kiểm tra trước giao dịch (không khóa) rồi khóa lại trong giao dịch trước khi lưu")
        void utc271_postCheckedThenLockedInTheTransaction() {
            update(validEdit(List.of()));

            InOrder order = inOrder(repository, txManager);
            order.verify(repository).editableBlog(BLOG_ID, false);
            order.verify(txManager).getTransaction(any());
            order.verify(repository).editableBlog(BLOG_ID, true);
            order.verify(repository).updateBlog(BLOG_ID, NEW_TITLE, NEW_CONTENT, "PUBLISHED", NOW);
            order.verify(txManager).commit(any());
        }

        @Test
        @DisplayName("UTC-272 Thứ tự kiểm tra khi sửa giống khi tạo: link → mã QR → từ khóa → mã QR (từ khóa) → lưu")
        void utc272_checksRunInTheAgreedOrder() {
            update(validEdit(List.of()));

            InOrder order = inOrder(filter, places, repository);
            order.verify(filter).checkLinks(NEW_TITLE, MEMBER_ID);
            order.verify(filter).checkLinks(NEW_CONTENT, MEMBER_ID);
            order.verify(filter).checkQrCodes(List.of(), MEMBER_ID);
            order.verify(filter).checkKeywords(NEW_TITLE);
            order.verify(filter).checkKeywords(NEW_CONTENT);
            order.verify(filter).checkQrKeywords(List.of());
            order.verify(places).save(PlacePicks.NONE, MEMBER_ID);
            order.verify(repository).updateBlog(BLOG_ID, NEW_TITLE, NEW_CONTENT, "PUBLISHED", NOW);
        }

        @Test
        @DisplayName("UTC-273 Bỏ hết địa điểm: bài không còn liên kết địa điểm nào")
        void utc273_allPlacesRemoved() {
            update(edit(NEW_TITLE, NEW_CONTENT, List.of(3L), List.of(), List.of(), List.of()));

            verify(repository).unlinkPlaces(BLOG_ID);
            verify(repository).linkPlaces(BLOG_ID, List.of());
            verify(repository, never()).countActivePlaces(anyList());
        }

        @Test
        @DisplayName("UTC-274 Thêm địa điểm từ OpenStreetMap khi sửa: được thêm vào Senpai ơi và gắn vào bài")
        void utc274_osmPlaceAddedOnEdit() {
            PlacePicks picks = new PlacePicks(List.of(), List.of(osmPlace("N772389775")), null);
            when(places.resolve(MEMBER_ID, List.of("N772389775"))).thenReturn(picks);
            when(places.save(picks, MEMBER_ID)).thenReturn(List.of(41L));

            update(edit(NEW_TITLE, NEW_CONTENT, List.of(3L), List.of(30L), List.of("N772389775"), List.of()));

            verify(repository).linkPlaces(BLOG_ID, List.of(30L, 41L));
        }

        @Test
        @DisplayName("UTC-275 Bài bị ẩn giữa lúc mở và lúc lưu (đọc lại có khóa): 404 MSG19, không thay đổi gì, hoàn tác")
        void utc275_postHiddenBeforeTheSave_notFound() {
            when(repository.editableBlog(BLOG_ID, false)).thenReturn(Optional.of(editable("PUBLISHED")));
            when(repository.editableBlog(BLOG_ID, true)).thenReturn(Optional.of(editable("HIDDEN")));

            assertApiError(() -> update(validEdit(List.of())), HttpStatus.NOT_FOUND, MSG19);
            verify(filter, never()).checkLinks(any(), anyLong());
            verifyNothingChanged();
            verifyRolledBack();
        }

        @ParameterizedTest(name = "[{index}] status = {0}")
        @ValueSource(strings = {"HIDDEN", "REMOVED"})
        @DisplayName("UTC-276 Bài bị ẩn do báo cáo hoặc bị gỡ: 404 MSG19, không thay đổi gì")
        void utc276_hiddenOrRemovedPost_notFound(String status) {
            when(repository.editableBlog(eq(BLOG_ID), anyBoolean())).thenReturn(Optional.of(editable(status)));

            assertApiError(() -> update(validEdit(List.of())), HttpStatus.NOT_FOUND, MSG19);
            verify(repository, never()).attachedMedia(anyLong());
            verifyNothingChanged();
            verifyNoTransaction();
        }

        @Test
        @DisplayName("UTC-277 Bài không tồn tại hoặc đã xóa: 404 MSG19")
        void utc277_missingPost_notFound() {
            when(repository.editableBlog(eq(BLOG_ID), anyBoolean())).thenReturn(Optional.empty());

            assertApiError(() -> update(validEdit(List.of())), HttpStatus.NOT_FOUND, MSG19);
            verifyNothingChanged();
        }

        @Test
        @DisplayName("UTC-278 Mã của một câu hỏi sửa qua trang Blog: 404 MSG19")
        void utc278_questionIdOnBlog_notFound() {
            when(repository.editableBlog(eq(BLOG_ID), anyBoolean()))
                    .thenReturn(Optional.of(editable(MEMBER_ID, "QA", "PUBLISHED")));

            assertApiError(() -> update(validEdit(List.of())), HttpStatus.NOT_FOUND, MSG19);
            verifyNothingChanged();
        }

        @Test
        @DisplayName("UTC-279 Sửa bài của người khác: 403 MSG18, không thay đổi gì")
        void utc279_someoneElsesPost_forbidden() {
            when(repository.editableBlog(eq(BLOG_ID), anyBoolean()))
                    .thenReturn(Optional.of(editable(OTHER_ID, "POST", "PUBLISHED")));

            assertApiError(() -> update(validEdit(List.of())), HttpStatus.FORBIDDEN, MSG18);
            verifyNothingChanged();
            verifyNoTransaction();
        }

        @Test
        @DisplayName("UTC-280 Khách chưa đăng nhập: 401, không đọc bài")
        void utc280_guest_unauthorized() {
            assertApiError(() -> service.update(null, KIND, BLOG_ID, validEdit(List.of()), null, null),
                    HttpStatus.UNAUTHORIZED, SIGN_IN);
            verifyNoInteractions(repository);
        }

        @ParameterizedTest(name = "[{index}] role = {0}")
        @ValueSource(strings = {"MODERATOR", "ACADEMIC_MANAGER", "ADMIN"})
        @DisplayName("UTC-281 Tài khoản nhân viên: 403 MSG18, không thay đổi gì")
        void utc281_staff_forbidden(String role) {
            AppUserPrincipal staff = staff(role);

            assertApiError(() -> service.update(staff, KIND, BLOG_ID, validEdit(List.of()), null, null),
                    HttpStatus.FORBIDDEN, MSG18);
            verify(repository, never()).editableBlog(anyLong(), anyBoolean());
            verifyNothingChanged();
        }

        @Test
        @DisplayName("UTC-282 Thành viên bị khóa: 403 MSG18, không thay đổi gì")
        void utc282_lockedMember_forbidden() {
            lockedMember();

            assertApiError(() -> update(validEdit(List.of())), HttpStatus.FORBIDDEN, MSG18);
            verifyNothingChanged();
        }
    }

    // =================================================================================================================
    @Nested
    @TestMethodOrder(MethodOrderer.DisplayName.class)
    @DisplayName("09. update – UC 1.6 Cập nhật bài viết: thông tin, link, từ khóa (AF 5a, 6a) (UTC-283 → UTC-294)")
    class UpdateChecks {

        @Test
        @DisplayName("UTC-283 Không gửi phần nội dung (request = null): MSG37, lỗi tiêu đề, chủ đề, nội dung; bài giữ nguyên")
        void utc283_nullRequest_msg37() {
            ApiException e = assertInvalid(() -> update(null), MSG37, "title", TITLE_REQUIRED);

            assertThat(e.getFields()).containsOnlyKeys("title", "topicIds", "content");
            verifyNothingChanged();
            verifyNoTransaction();
        }

        @Test
        @DisplayName("UTC-284 Xóa trắng tiêu đề: MSG37, lỗi dưới ô tiêu đề, bài giữ nguyên")
        void utc284_blankTitle_msg37() {
            assertInvalid(() -> update(edit("   ", NEW_CONTENT, List.of(3L), List.of(), List.of(), List.of())), MSG37,
                    "title", TITLE_REQUIRED);
            verifyNothingChanged();
        }

        @Test
        @DisplayName("UTC-285 Tiêu đề mới 151 ký tự: \"Tiêu đề tối đa 150 ký tự.\"")
        void utc285_title151_refused() {
            assertInvalid(() -> update(edit("a".repeat(151), NEW_CONTENT, List.of(3L), List.of(), List.of(),
                    List.of())), TITLE_TOO_LONG, "title", TITLE_TOO_LONG);
            verifyNothingChanged();
        }

        @Test
        @DisplayName("UTC-286 Tiêu đề đúng 150 ký tự và nội dung đúng 5.000 ký tự: được lưu")
        void utc286_title150AndContent5000_accepted() {
            String title = "a".repeat(150);
            String content = "c".repeat(5000);

            update(edit(title, content, List.of(3L), List.of(), List.of(), List.of()));

            verify(repository).updateBlog(BLOG_ID, title, content, "PUBLISHED", NOW);
        }

        @Test
        @DisplayName("UTC-287 Nội dung mới 5.001 ký tự: \"Nội dung tối đa 5.000 ký tự.\"")
        void utc287_content5001_refused() {
            assertInvalid(() -> update(edit(NEW_TITLE, "c".repeat(5001), List.of(3L), List.of(), List.of(),
                    List.of())), CONTENT_TOO_LONG, "content", CONTENT_TOO_LONG);
            verifyNothingChanged();
        }

        @Test
        @DisplayName("UTC-288 Chọn 4 chủ đề khi sửa: \"Chọn tối đa 3 chủ đề.\"")
        void utc288_fourTopics_refused() {
            assertInvalid(() -> update(edit(NEW_TITLE, NEW_CONTENT, List.of(1L, 2L, 3L, 9L), List.of(), List.of(),
                    List.of())), TOO_MANY_TOPICS, "topicIds", TOO_MANY_TOPICS);
            verifyNothingChanged();
        }

        @Test
        @DisplayName("UTC-289 Địa điểm bị gỡ giữa lúc kiểm tra và lúc lưu: \"Có địa điểm không còn trên bản đồ…\", bài giữ nguyên")
        void utc289_placeRemovedBeforeTheSave_refused() {
            when(repository.countActivePlaces(List.of(31L))).thenReturn(1, 0);

            assertInvalid(() -> update(validEdit(List.of())), PLACE_GONE, "placeIds", PLACE_GONE);
            verifyNothingChanged();
            verifyRolledBack();
        }

        @Test
        @DisplayName("UTC-290 Thêm link khi chưa có tag định danh: bị từ chối, bài giữ nguyên")
        void utc290_linkWithoutTag_refused() {
            doThrow(ApiException.badRequest(LINK_NEEDS_TAG)).when(filter).checkLinks(NEW_CONTENT, MEMBER_ID);

            assertApiError(() -> update(validEdit(List.of())), HttpStatus.BAD_REQUEST, LINK_NEEDS_TAG);
            verify(filter, never()).checkKeywords(any());
            verifyNothingChanged();
        }

        @Test
        @DisplayName("UTC-291 Tiêu đề mới chứa từ khóa cấm: MSG36, bài giữ nguyên")
        void utc291_bannedKeywordInTitle_msg36() {
            doThrow(ApiException.badRequest(msg36("cá cược"))).when(filter).checkKeywords(NEW_TITLE);

            assertApiError(() -> update(validEdit(List.of())), HttpStatus.BAD_REQUEST, msg36("cá cược"));
            verifyNothingChanged();
            verifyRolledBack();
        }

        @Test
        @DisplayName("UTC-292 Chỉ ảnh mới được đọc mã QR, ảnh giữ lại không đọc lại")
        void utc292_onlyNewPhotosAreCheckedForQrCodes() {
            when(repository.attachedMedia(BLOG_ID)).thenReturn(List.of(image(701L, "PASSED", 0)));
            when(media.prepareImage(any())).thenReturn(photoWithQr("https://senpaioi.com/b"));

            update(validEdit(List.of(701L)), photos(1), null);

            verify(filter).checkQrCodes(List.of(List.of("https://senpaioi.com/b")), MEMBER_ID);
            verify(media, times(1)).prepareImage(any());
        }

        @Test
        @DisplayName("UTC-293 Ảnh mới có mã QR khi chưa có tag định danh: bị từ chối dưới ô ảnh, không lưu tệp, bài giữ nguyên")
        void utc293_newPhotoWithQrCodeWithoutTag_refused() {
            String message = "Ảnh thứ 1 bạn vừa chọn có mã QR. Chỉ tài khoản đã có tag định danh mới được đăng ảnh có "
                    + "mã QR (mã QR được tính như link). Hãy cắt hoặc che mã QR rồi chọn lại ảnh.";
            when(media.prepareImage(any())).thenReturn(photoWithQr("https://line.me/ti/p/abc"));
            doThrow(ApiException.invalid(message, Map.of("images", message))).when(filter)
                    .checkQrCodes(List.of(List.of("https://line.me/ti/p/abc")), MEMBER_ID);

            assertInvalid(() -> update(validEdit(List.of()), photos(1), null), message, "images", message);
            verifyNothingChanged();
        }

        @Test
        @DisplayName("UTC-294 Mã QR trong ảnh mới chứa từ khóa cấm: bị từ chối, bài giữ nguyên")
        void utc294_bannedKeywordInNewPhotoQrCode_refused() {
            String message = "Chưa đăng được: mã QR trong ảnh thứ 1 bạn vừa chọn chứa từ khóa không được phép “fb88”. "
                    + "Hãy dùng ảnh khác.";
            when(media.prepareImage(any())).thenReturn(photoWithQr("fb88 khuyến mãi"));
            doThrow(ApiException.invalid(message, Map.of("images", message))).when(filter)
                    .checkQrKeywords(List.of(List.of("fb88 khuyến mãi")));

            assertInvalid(() -> update(validEdit(List.of()), photos(1), null), message, "images", message);
            verifyNothingChanged();
        }
    }

    // =================================================================================================================
    @Nested
    @TestMethodOrder(MethodOrderer.DisplayName.class)
    @DisplayName("10. update – UC 1.6 Cập nhật bài viết: ảnh, video, trạng thái (AF 7a, 7b) (UTC-295 → UTC-311)")
    class UpdateMedia {

        @BeforeEach
        void threePhotos() {
            when(repository.attachedMedia(BLOG_ID)).thenReturn(List.of(image(701L, "PASSED", 0),
                    image(702L, "PASSED", 1), image(703L, "PASSED", 2)));
        }

        @Test
        @DisplayName("UTC-295 Giữ ảnh 3 rồi ảnh 1, bỏ ảnh 2: ảnh 2 bị gỡ và xóa tệp, hai ảnh còn lại theo thứ tự mới")
        void utc295_keepTwoPhotosInNewOrder() {
            Created created = update(validEdit(List.of(703L, 701L)));

            assertThat(created.status()).isEqualTo("PUBLISHED");
            verify(repository).unlinkMedia(BLOG_ID, List.of(702L));
            verify(media).discard(List.of(702L));
            InOrder order = inOrder(repository);
            order.verify(repository).setMediaOrder(BLOG_ID, 703L, 0);
            order.verify(repository).setMediaOrder(BLOG_ID, 701L, 1);
            verify(repository, never()).linkMedia(anyLong(), anyLong(), anyInt());
        }

        @Test
        @DisplayName("UTC-296 Không gửi danh sách giữ lại (null): mọi ảnh cũ bị gỡ, bài vẫn đăng")
        void utc296_noKeepList_allMediaRemoved() {
            update(validEdit(null));

            verify(repository).unlinkMedia(BLOG_ID, List.of(701L, 702L, 703L));
            verify(media).discard(List.of(701L, 702L, 703L));
            verify(repository).updateBlog(BLOG_ID, NEW_TITLE, NEW_CONTENT, "PUBLISHED", NOW);
        }

        @Test
        @DisplayName("UTC-297 Mã giữ lại lặp lại hoặc không thuộc bài bị bỏ qua: [701, 701, 999] → chỉ giữ 701")
        void utc297_repeatedAndUnknownKeepIdsIgnored() {
            update(validEdit(List.of(701L, 701L, 999L)));

            verify(repository).unlinkMedia(BLOG_ID, List.of(702L, 703L));
            verify(repository).setMediaOrder(BLOG_ID, 701L, 0);
            verify(repository, times(1)).setMediaOrder(anyLong(), anyLong(), anyInt());
        }

        @Test
        @DisplayName("UTC-298 Giữ 1 ảnh + thêm 4 ảnh mới = 5: được nhận, ảnh mới đứng sau ảnh cũ")
        void utc298_oneKeptPlusFourNew_accepted() {
            Created created = update(validEdit(List.of(701L)), photos(4), null);

            assertThat(created.status()).isEqualTo("PROCESSING");
            verify(repository).setMediaOrder(BLOG_ID, 701L, 0);
            InOrder order = inOrder(repository);
            for (int i = 0; i < 4; i++) {
                order.verify(repository).linkMedia(BLOG_ID, 900L + i, i + 1);
            }
        }

        @Test
        @DisplayName("UTC-299 Giữ 1 ảnh + thêm 5 ảnh mới = 6: quy tắc ảnh, không giải mã ảnh nào, bài giữ nguyên")
        void utc299_oneKeptPlusFiveNew_refused() {
            assertInvalid(() -> update(validEdit(List.of(701L)), photos(5), null), IMAGE_RULE, "images", IMAGE_RULE);
            verify(media, never()).prepareImage(any());
            verifyNothingChanged();
        }

        @Test
        @DisplayName("UTC-300 Giữ video cũ và thêm video mới: \"Chỉ đính kèm được 1 video.\"")
        void utc300_keptVideoPlusNewVideo_refused() {
            when(repository.attachedMedia(BLOG_ID)).thenReturn(List.of(image(701L, "PASSED", 0),
                    video(710L, "PASSED", 1)));

            assertInvalid(() -> update(validEdit(List.of(701L, 710L)), null, videoFile()), ONE_VIDEO, "video",
                    ONE_VIDEO);
            verify(media, never()).prepareVideo(any());
            verifyNothingChanged();
        }

        @Test
        @DisplayName("UTC-301 Thêm ảnh mới vào bài đang đăng: bài về chờ quét (PROCESSING), quét sau commit, không cộng điểm")
        void utc301_newPhoto_backToProcessing() {
            Created created = update(validEdit(List.of(701L, 702L, 703L)), photos(1), null);

            assertThat(created.status()).isEqualTo("PROCESSING");
            verify(repository).updateBlog(BLOG_ID, NEW_TITLE, NEW_CONTENT, "PROCESSING", NOW);
            verify(repository).linkMedia(BLOG_ID, 900L, 3);
            verify(publisher, never()).scanSoon(anyLong());
            afterCommit();
            verify(publisher).scanSoon(BLOG_ID);
            verify(publisher, never()).awardOnce(anyLong(), anyLong());
        }

        @Test
        @DisplayName("UTC-302 Bài bị từ chối, các ảnh còn lại đã qua quét, không thêm tệp: được đăng (PUBLISHED) và trả điểm lần đầu")
        void utc302_rejectedPostWithPassedFiles_published() {
            when(repository.editableBlog(eq(BLOG_ID), anyBoolean())).thenReturn(Optional.of(editable("REJECTED")));

            Created created = update(validEdit(List.of(701L, 702L, 703L)));

            assertThat(created.status()).isEqualTo("PUBLISHED");
            verify(publisher).awardOnce(MEMBER_ID, BLOG_ID);
            verify(publisher, never()).scanSoon(anyLong());
        }

        @Test
        @DisplayName("UTC-303 Bài bị từ chối không còn tệp nào: được đăng (PUBLISHED)")
        void utc303_rejectedPostWithoutFiles_published() {
            when(repository.editableBlog(eq(BLOG_ID), anyBoolean())).thenReturn(Optional.of(editable("REJECTED")));
            when(repository.attachedMedia(BLOG_ID)).thenReturn(List.of());

            assertThat(update(validEdit(List.of())).status()).isEqualTo("PUBLISHED");
            verify(publisher).awardOnce(MEMBER_ID, BLOG_ID);
        }

        @Test
        @DisplayName("UTC-304 Bài đang chờ quét, giữ ảnh chưa quét xong, không thêm tệp: vẫn PROCESSING, quét lại sau commit, chưa cộng điểm")
        void utc304_processingPostWithWaitingFile_staysProcessing() {
            when(repository.editableBlog(eq(BLOG_ID), anyBoolean())).thenReturn(Optional.of(editable("PROCESSING")));
            when(repository.attachedMedia(BLOG_ID)).thenReturn(List.of(image(701L, "PROCESSING", 0)));

            Created created = update(validEdit(List.of(701L)));

            assertThat(created.status()).isEqualTo("PROCESSING");
            verify(publisher, never()).awardOnce(anyLong(), anyLong());
            afterCommit();
            verify(publisher).scanSoon(BLOG_ID);
        }

        @Test
        @DisplayName("UTC-305 Bài đang chờ quét, tác giả bỏ ảnh chưa quét, ảnh còn lại đã qua: được đăng ngay")
        void utc305_processingPostWaitingFileRemoved_published() {
            when(repository.editableBlog(eq(BLOG_ID), anyBoolean())).thenReturn(Optional.of(editable("PROCESSING")));
            when(repository.attachedMedia(BLOG_ID)).thenReturn(List.of(image(701L, "PASSED", 0),
                    image(702L, "PROCESSING", 1)));

            Created created = update(validEdit(List.of(701L)));

            assertThat(created.status()).isEqualTo("PUBLISHED");
            verify(repository).unlinkMedia(BLOG_ID, List.of(702L));
            verify(media).discard(List.of(702L));
            verify(publisher).awardOnce(MEMBER_ID, BLOG_ID);
        }

        @Test
        @DisplayName("UTC-306 Ảnh giữ lại đã bị bộ quét xóa trong lúc sửa: bỏ qua ảnh đó, không gỡ hay xóa lại")
        void utc306_keptFileDeletedByTheScanMeanwhile_skipped() {
            when(repository.attachedMedia(BLOG_ID)).thenReturn(
                    List.of(image(701L, "PASSED", 0), image(702L, "PASSED", 1)),
                    List.of(image(701L, "PASSED", 0)));

            update(validEdit(List.of(701L, 702L)));

            verify(repository).setMediaOrder(BLOG_ID, 701L, 0);
            verify(repository, never()).setMediaOrder(BLOG_ID, 702L, 1);
            verify(repository).unlinkMedia(BLOG_ID, List.of());
            verify(media).discard(List.of());
        }

        @Test
        @DisplayName("UTC-307 Tài khoản mới thêm video vào bài chưa có video (0 video hôm nay): được nhận, tính 1 video, không tính bài")
        void utc307_newAccountAddsFirstVideo_counted() {
            memberAged(Duration.ofDays(2));

            Created created = update(validEdit(List.of(701L)), null, videoFile());

            assertThat(created.status()).isEqualTo("PROCESSING");
            verify(repository).lockUsage(MEMBER_ID, TODAY);
            verify(repository).addUsage(MEMBER_ID, TODAY, 0, 1);
        }

        @Test
        @DisplayName("UTC-308 Tài khoản mới đã có 1 video hôm nay thêm video vào bài chưa có video: 429 giới hạn video, bài giữ nguyên")
        void utc308_newAccountSecondVideoOfTheDay_refused() {
            memberAged(Duration.ofDays(2));
            when(repository.lockUsage(MEMBER_ID, TODAY)).thenReturn(new Usage(0, 0, 1));

            assertApiError(() -> update(validEdit(List.of(701L)), null, videoFile()), HttpStatus.TOO_MANY_REQUESTS,
                    VIDEO_LIMIT);
            verifyNothingChanged();
            verifyRolledBack();
        }

        @Test
        @DisplayName("UTC-309 Thay video của bài đã có video: không tính hạn mức (tài khoản mới đã có 1 video hôm nay vẫn được)")
        void utc309_replacingVideo_notCounted() {
            memberAged(Duration.ofDays(2));
            when(repository.attachedMedia(BLOG_ID)).thenReturn(List.of(video(710L, "PASSED", 0)));
            when(repository.lockUsage(MEMBER_ID, TODAY)).thenReturn(new Usage(0, 0, 1));

            Created created = update(validEdit(List.of()), null, videoFile());

            assertThat(created.status()).isEqualTo("PROCESSING");
            verify(repository).unlinkMedia(BLOG_ID, List.of(710L));
            verify(repository, never()).lockUsage(anyLong(), any());
            verify(repository, never()).addUsage(anyLong(), any(), anyInt(), anyInt());
        }

        @Test
        @DisplayName("UTC-310 Tài khoản đủ tuổi thêm video (đã có 3 video hôm nay): không bị giới hạn nhưng vẫn được tính")
        void utc310_oldAccountAddsVideo_notLimitedButCounted() {
            when(repository.lockUsage(MEMBER_ID, TODAY)).thenReturn(new Usage(2, 0, 3));

            assertThat(update(validEdit(List.of(701L)), null, videoFile()).status()).isEqualTo("PROCESSING");
            verify(repository).addUsage(MEMBER_ID, TODAY, 0, 1);
        }

        @Test
        @DisplayName("UTC-311 Lưu bản sửa ghi is_edited = TRUE (bài hiện \"· đã sửa\") cùng tiêu đề, nội dung, trạng thái, giờ sửa")
        void utc311_updateBlogSetsEditedFlag() {
            JdbcTemplate jdbc = mock(JdbcTemplate.class);
            BlogWriteRepository real = new BlogWriteRepository(jdbc, mock(NamedParameterJdbcTemplate.class));

            real.updateBlog(BLOG_ID, NEW_TITLE, NEW_CONTENT, "PUBLISHED", NOW);

            verify(jdbc).update(eq("UPDATE blogs SET title = ?, content = ?, status = ?, is_edited = TRUE, "
                            + "updated_at = ? WHERE id = ?"), eq(NEW_TITLE), eq(NEW_CONTENT), eq("PUBLISHED"),
                    eq(Timestamp.from(NOW)), eq(BLOG_ID));
        }
    }
}
