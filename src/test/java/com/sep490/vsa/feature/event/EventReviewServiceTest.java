package com.sep490.vsa.feature.event;

import com.sep490.vsa.common.exception.ApiException;
import com.sep490.vsa.common.security.AppUserPrincipal;
import com.sep490.vsa.common.settings.SystemSettings;
import com.sep490.vsa.feature.admin.ActivityLogService;
import com.sep490.vsa.feature.blog.BlogWriteRepository;
import com.sep490.vsa.feature.blog.BlogWriteRepository.Account;
import com.sep490.vsa.feature.blog.BlogWriteRepository.Usage;
import com.sep490.vsa.feature.event.EventDtos.LikeState;
import com.sep490.vsa.feature.event.EventDtos.Media;
import com.sep490.vsa.feature.event.EventDtos.ReportRequest;
import com.sep490.vsa.feature.event.EventDtos.ReviewForm;
import com.sep490.vsa.feature.event.EventDtos.ReviewRequest;
import com.sep490.vsa.feature.event.EventDtos.ReviewSaved;
import com.sep490.vsa.feature.event.EventDtos.ReviewUpdateRequest;
import com.sep490.vsa.feature.event.EventRepository.EventRow;
import com.sep490.vsa.feature.event.EventRepository.OwnRow;
import com.sep490.vsa.feature.event.EventRepository.ReviewState;
import com.sep490.vsa.feature.media.InvalidMediaException;
import com.sep490.vsa.feature.media.MediaService;
import com.sep490.vsa.feature.media.MediaService.Prepared;
import com.sep490.vsa.feature.moderation.ContentFilter;
import com.sep490.vsa.feature.report.ContentReportService;
import com.sep490.vsa.feature.report.ContentReportService.Outcome;
import com.sep490.vsa.feature.report.ContentReportService.Target;
import com.sep490.vsa.feature.user.PointService;
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
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.InOrder;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

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
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit tests of the event reviews ({@link EventReviewService}), written from the full use case specification
 * (docs/use-cases/dac-ta-uc-hoc-tieng-nhat-va-su-kien.md: UC 3 "Gửi đánh giá lễ hội &amp; sự kiện" and UC 2 "Xem chi tiết
 * lễ hội &amp; sự kiện" AF3 thích, AF4 báo cáo, AF5 xóa review của mình) and the business rules
 * (docs/use-cases/business-rules-vi.md: GBR-09, GBR-12, GBR-13, GBR-22, GBR-23, GBR-25, GBR-27, GBR-28, GBR-31, GBR-32,
 * GBR-33, GBR-34, GBR-36, GBR-37, GBR-40, GBR-41).
 * <p>
 * The database is not used: the repositories, the content filter, media, points, reports and the publish job are mocks;
 * {@link EventService} is real (on the same mocked repository) so "the event has started" follows the fixed clock
 * (20/11/2026 12:00 Japan time).
 */
@TestClassOrder(ClassOrderer.DisplayName.class)
@DisplayName("EventReviewService – Review lễ hội & sự kiện (UC 3, UC 2 AF3 – AF5)")
class EventReviewServiceTest {

    private static final long MEMBER_ID = 10L;
    private static final long OTHER_ID = 11L;
    private static final long EVENT_ID = 900L;
    private static final long REVIEW_ID = 31L;
    private static final Instant NOW = Instant.parse("2026-11-20T03:00:00Z");
    private static final LocalDate TODAY = LocalDate.of(2026, 11, 20);
    private static final String CONTENT = "Pháo hoa rất đẹp, nên đi sớm để có chỗ.";

    private static final String MSG_LOGIN = "Bạn cần đăng nhập để viết review.";
    private static final String MSG18 = "Bạn không có quyền thực hiện thao tác này.";
    private static final String MSG_EVENT_NOT_FOUND = "Không tìm thấy sự kiện. Sự kiện có thể đã bị xóa.";
    private static final String MSG_REVIEW_NOT_FOUND = "Không tìm thấy review. Review có thể đã bị gỡ hoặc đã bị xóa.";
    private static final String MSG_NOT_STARTED = "Viết review được khi sự kiện bắt đầu.";

    private static final AppUserPrincipal MEMBER = principal(MEMBER_ID, "USER");

    private EventRepository repository;
    private BlogWriteRepository accounts;
    private ContentFilter filter;
    private MediaService media;
    private SystemSettings settings;
    private EventReviewPublishService publisher;
    private PointService points;
    private ContentReportService reports;
    private PlatformTransactionManager txManager;
    private EventReviewService service;

    @BeforeEach
    void setUp() {
        repository = mock(EventRepository.class);
        accounts = mock(BlogWriteRepository.class);
        filter = mock(ContentFilter.class);
        media = mock(MediaService.class);
        settings = mock(SystemSettings.class);
        publisher = mock(EventReviewPublishService.class);
        points = mock(PointService.class);
        reports = mock(ContentReportService.class);
        txManager = mock(PlatformTransactionManager.class);
        service = serviceAt(NOW);
        TransactionSynchronizationManager.initSynchronization();

        account(MEMBER_ID, "USER", "ACTIVE", NOW.minus(Duration.ofDays(30)));
        account(OTHER_ID, "USER", "ACTIVE", NOW.minus(Duration.ofDays(30)));
        when(settings.getInt(anyString(), anyInt())).thenAnswer(i -> i.getArgument(1));
        when(accounts.lockUsage(anyLong(), any())).thenReturn(new Usage(0, 0, 0));
        when(accounts.usage(anyLong(), any())).thenReturn(new Usage(0, 0, 0));
        when(media.videoMaxMb()).thenReturn(50);
        when(repository.event(EVENT_ID)).thenReturn(Optional.of(event(TODAY.minusDays(1), TODAY.plusDays(1))));
        when(repository.insertReview(anyLong(), anyLong(), anyInt(), anyInt(), anyString(), anyString(), any()))
                .thenReturn(REVIEW_ID);
        when(repository.authorActive(REVIEW_ID)).thenReturn(true);
        when(reports.report(any(), anyLong(), any(), any())).thenReturn(new Outcome(1, false));
    }

    @AfterEach
    void tearDown() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    // ---------------------------------------------------------------- helpers

    private EventReviewService serviceAt(Instant instant) {
        Clock clock = Clock.fixed(instant, ZoneOffset.UTC);
        EventService events = new EventService(repository, reports, media, mock(ActivityLogService.class), txManager,
                clock);
        return new EventReviewService(repository, events, accounts, filter, media, settings, publisher, points,
                reports, txManager, clock);
    }

    private static AppUserPrincipal principal(long id, String role) {
        return new AppUserPrincipal(id, "user" + id + "@example.org", "User " + id, role, "/");
    }

    private void account(long id, String role, String status, Instant createdAt) {
        when(accounts.account(id)).thenReturn(Optional.of(new Account(id, role, status, createdAt)));
    }

    /** The member's account is younger than the 7 days of {@code new_account_days}. */
    private void newAccount() {
        account(MEMBER_ID, "USER", "ACTIVE", NOW.minus(Duration.ofDays(2)));
    }

    private static EventRow event(LocalDate start, LocalDate end) {
        return new EventRow(EVENT_ID, "Lễ hội pháo hoa Sumida", start, end, "Sông Sumida", new BigDecimal("35.71"),
                new BigDecimal("139.80"), "Miễn phí", null, null, null, "Moderator A");
    }

    private static ReviewRequest request(Integer rating, String content) {
        return new ReviewRequest(rating, content);
    }

    private static MockMultipartFile image(int i) {
        return new MockMultipartFile("images", "photo" + i + ".jpg", "image/jpeg", new byte[] {(byte) i, 1});
    }

    private static MockMultipartFile video() {
        return new MockMultipartFile("video", "clip.mp4", "video/mp4", new byte[] {9, 9});
    }

    private static Prepared preparedImage(List<String> qrCodes) {
        return new Prepared("IMAGE", new byte[] {1}, "image/jpeg", "jpg", 1600, 1200, null, qrCodes);
    }

    private static Prepared preparedVideo() {
        return new Prepared("VIDEO", new byte[] {2}, "video/mp4", "mp4", 1280, 720, 20, List.of());
    }

    private static Media file(long id, String type, String scanStatus) {
        return new Media(id, type, "/media/review/" + id, 800, 600, scanStatus);
    }

    private void ownReview(String status) {
        when(repository.review(eq(REVIEW_ID), anyBoolean())).thenReturn(Optional.of(new ReviewState(REVIEW_ID,
                EVENT_ID, MEMBER_ID, 2026, status, CONTENT)));
    }

    private void othersReview(String status) {
        when(repository.review(eq(REVIEW_ID), anyBoolean())).thenReturn(Optional.of(new ReviewState(REVIEW_ID,
                EVENT_ID, OTHER_ID, 2026, status, CONTENT)));
    }

    /** Runs what was registered to happen after the transaction commits (the scan of the files). */
    private void commit() {
        for (TransactionSynchronization s : TransactionSynchronizationManager.getSynchronizations()) {
            s.afterCommit();
        }
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

    private static void assertInvalid(Runnable call, String field, String message) {
        ApiException e = apiError(call);
        assertThat(e.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(e.getMessage()).isEqualTo(message);
        assertThat(e.getFields()).containsEntry(field, message);
    }

    private ReviewSaved create(ReviewRequest request) {
        return service.create(MEMBER, EVENT_ID, request, null, null);
    }

    private void verifyNoReviewSaved() {
        verify(repository, never()).insertReview(anyLong(), anyLong(), anyInt(), anyInt(), anyString(), anyString(),
                any());
        verify(accounts, never()).addUsage(anyLong(), any(), anyInt(), anyInt());
        verify(publisher, never()).awardOnce(anyLong(), anyLong());
    }

    // =================================================================================================================
    @Nested
    @TestMethodOrder(MethodOrderer.DisplayName.class)
    @DisplayName("01. form – UC 3 bước 2 Form \"Viết review\" (UTC-088 → UTC-100)")
    class ReviewFormPage {

        @Test
        @DisplayName("UTC-088 GBR-12 Khách mở form → 401 \"Bạn cần đăng nhập để viết review.\"")
        void utc088_guest_unauthorized() {
            assertApiError(() -> service.form(null, EVENT_ID), HttpStatus.UNAUTHORIZED, MSG_LOGIN);
        }

        @Test
        @DisplayName("UTC-089 AF10 Phiên của tài khoản không còn (không đọc được tài khoản) → 401")
        void utc089_accountGone_unauthorized() {
            when(accounts.account(MEMBER_ID)).thenReturn(Optional.empty());

            assertApiError(() -> service.form(MEMBER, EVENT_ID), HttpStatus.UNAUTHORIZED, "Bạn chưa đăng nhập.");
        }

        @ParameterizedTest(name = "[{index}] vai trò {0}")
        @ValueSource(strings = {"MODERATOR", "ACADEMIC_MANAGER", "ADMIN"})
        @DisplayName("UTC-090 AF9 GBR-13 Tài khoản nhân viên → 403 MSG18")
        void utc090_staff_forbidden(String role) {
            account(MEMBER_ID, role, "ACTIVE", NOW.minus(Duration.ofDays(30)));

            assertApiError(() -> service.form(MEMBER, EVENT_ID), HttpStatus.FORBIDDEN, MSG18);
        }

        @Test
        @DisplayName("UTC-091 GBR-09 Tài khoản bị khóa → 403 MSG18")
        void utc091_lockedAccount_forbidden() {
            account(MEMBER_ID, "USER", "LOCKED", NOW.minus(Duration.ofDays(30)));

            assertApiError(() -> service.form(MEMBER, EVENT_ID), HttpStatus.FORBIDDEN, MSG18);
        }

        @Test
        @DisplayName("UTC-092 AF11 Sự kiện đã bị xóa → 404")
        void utc092_deletedEvent_notFound() {
            when(repository.event(EVENT_ID)).thenReturn(Optional.empty());

            assertApiError(() -> service.form(MEMBER, EVENT_ID), HttpStatus.NOT_FOUND, MSG_EVENT_NOT_FOUND);
        }

        @Test
        @DisplayName("UTC-093 AF7 GBR-41 Sự kiện chưa bắt đầu → \"Viết review được khi sự kiện bắt đầu.\"")
        void utc093_notStarted_rejected() {
            when(repository.event(EVENT_ID)).thenReturn(Optional.of(event(TODAY.plusDays(1), TODAY.plusDays(2))));

            assertApiError(() -> service.form(MEMBER, EVENT_ID), HttpStatus.BAD_REQUEST, MSG_NOT_STARTED);
        }

        @Test
        @DisplayName("UTC-094 Sự kiện đã diễn ra vẫn mở form viết review")
        void utc094_pastEvent_formOpens() {
            when(repository.event(EVENT_ID)).thenReturn(Optional.of(event(TODAY.minusDays(10), TODAY.minusDays(9))));

            assertThat(service.form(MEMBER, EVENT_ID).event().state()).isEqualTo("PAST");
        }

        @Test
        @DisplayName("UTC-095 Giới hạn: 10–2.000 ký tự, 5 ảnh ≤ 20 MB, 1 video ≤ 30 giây")
        void utc095_formLimits() {
            ReviewForm form = service.form(MEMBER, EVENT_ID);

            assertThat(form.limits().contentMin()).isEqualTo(10);
            assertThat(form.limits().contentMax()).isEqualTo(2000);
            assertThat(form.limits().imagesMax()).isEqualTo(5);
            assertThat(form.limits().imageMaxMb()).isEqualTo(20);
            assertThat(form.limits().videosMax()).isEqualTo(1);
            assertThat(form.limits().videoMaxSeconds()).isEqualTo(30);
            assertThat(form.limits().videoMaxMb()).isEqualTo(50);
        }

        @Test
        @DisplayName("UTC-096 Chưa có review năm nay: form trống, \"Review cho lần tổ chức năm 2026\"")
        void utc096_noOwnReview_emptyForm() {
            ReviewForm form = service.form(MEMBER, EVENT_ID);

            assertThat(form.review()).isNull();
            assertThat(form.event().reviewYear()).isEqualTo(2026);
        }

        @Test
        @DisplayName("UTC-097 AF1 Đã có review năm nay: form \"Sửa review\" với nội dung và tệp cũ")
        void utc097_ownReview_editForm() {
            when(repository.ownReviewId(EVENT_ID, 2026, MEMBER_ID)).thenReturn(Optional.of(REVIEW_ID));
            when(repository.ownReview(REVIEW_ID)).thenReturn(Optional.of(new OwnRow(REVIEW_ID, 4, CONTENT,
                    "PUBLISHED")));
            when(repository.attachedMedia(REVIEW_ID)).thenReturn(List.of(file(1, "IMAGE", "PASSED")));

            ReviewForm form = service.form(MEMBER, EVENT_ID);

            assertThat(form.review().id()).isEqualTo(REVIEW_ID);
            assertThat(form.review().rating()).isEqualTo(4);
            assertThat(form.review().content()).isEqualTo(CONTENT);
            assertThat(form.review().media()).hasSize(1);
        }

        @Test
        @DisplayName("UTC-098 AF12 Review năm nay đã bị Moderator gỡ → 409, không mở form")
        void utc098_removedReview_conflict() {
            when(repository.ownReviewId(EVENT_ID, 2026, MEMBER_ID)).thenReturn(Optional.of(REVIEW_ID));
            when(repository.ownReview(REVIEW_ID)).thenReturn(Optional.of(new OwnRow(REVIEW_ID, 1, "x", "REMOVED")));

            assertApiError(() -> service.form(MEMBER, EVENT_ID), HttpStatus.CONFLICT,
                    "Review năm 2026 của bạn đã bị gỡ nên không viết lại được cho lần tổ chức này.");
        }

        @Test
        @DisplayName("UTC-099 GBR-32 Tài khoản mới: hạn mức hôm nay x/3 bài viết và review · x/2 ghim · x/1 video")
        void utc099_newAccount_quota() {
            newAccount();
            when(accounts.usage(MEMBER_ID, TODAY)).thenReturn(new Usage(1, 0, 1));

            ReviewForm form = service.form(MEMBER, EVENT_ID);

            assertThat(form.quota().newAccount()).isTrue();
            assertThat(form.quota().newAccountDays()).isEqualTo(7);
            assertThat(form.quota().postsUsed()).isEqualTo(1);
            assertThat(form.quota().postsLimit()).isEqualTo(3);
            assertThat(form.quota().pinsLimit()).isEqualTo(2);
            assertThat(form.quota().videosUsed()).isEqualTo(1);
            assertThat(form.quota().videosLimit()).isEqualTo(1);
        }

        @Test
        @DisplayName("UTC-100 GBR-27 Form cho biết thành viên có được chèn link không (có tag định danh)")
        void utc100_canPostLinksFollowsIdentityTag() {
            when(filter.hasIdentityTag(MEMBER_ID)).thenReturn(true);
            assertThat(service.form(MEMBER, EVENT_ID).canPostLinks()).isTrue();

            when(filter.hasIdentityTag(MEMBER_ID)).thenReturn(false);
            assertThat(service.form(MEMBER, EVENT_ID).canPostLinks()).isFalse();
        }
    }

    // =================================================================================================================
    @Nested
    @TestMethodOrder(MethodOrderer.DisplayName.class)
    @DisplayName("02. create – UC 3 Gửi đánh giá lễ hội & sự kiện (UTC-101 → UTC-138)")
    class CreateReview {

        @Test
        @DisplayName("UTC-101 Bước 5 Không có ảnh/video: đăng ngay (PUBLISHED)")
        void utc101_noMedia_publishedAtOnce() {
            ReviewSaved saved = create(request(5, CONTENT));

            assertThat(saved.status()).isEqualTo("PUBLISHED");
            assertThat(saved.id()).isEqualTo(REVIEW_ID);
            assertThat(saved.eventId()).isEqualTo(EVENT_ID);
            verify(repository).insertReview(MEMBER_ID, EVENT_ID, 2026, 5, CONTENT, "PUBLISHED", NOW);
        }

        @Test
        @DisplayName("UTC-102 GBR-22 Review được đăng: cộng +10 điểm (một lần)")
        void utc102_published_pointsAwardedOnce() {
            create(request(5, CONTENT));
            commit();

            verify(publisher).awardOnce(MEMBER_ID, REVIEW_ID);
            verify(publisher, never()).scanSoon(anyLong());
        }

        @Test
        @DisplayName("UTC-103 Postcondition: số bài đăng trong ngày (giờ Nhật) tăng 1")
        void utc103_dailyUsageIncreased() {
            create(request(5, CONTENT));

            verify(accounts).addUsage(MEMBER_ID, TODAY, 1, 0);
        }

        @Test
        @DisplayName("UTC-104 Năm của review = năm của ngày bắt đầu (20/12/2026 – 05/01/2027, viết 03/01/2027 → 2026)")
        void utc104_crossYearEvent_reviewBelongsToStartYear() {
            service = serviceAt(Instant.parse("2027-01-03T03:00:00Z"));
            when(repository.event(EVENT_ID)).thenReturn(Optional.of(event(LocalDate.of(2026, 12, 20),
                    LocalDate.of(2027, 1, 5))));

            create(request(4, CONTENT));

            verify(repository).ownReviewId(EVENT_ID, 2026, MEMBER_ID);
            verify(repository).insertReview(eq(MEMBER_ID), eq(EVENT_ID), eq(2026), eq(4), eq(CONTENT),
                    eq("PUBLISHED"), any());
        }

        @Test
        @DisplayName("UTC-105 AF2 GBR-28 Có ảnh: review chờ quét (PROCESSING), chưa cộng điểm")
        void utc105_withPhotos_processingNoPointsYet() {
            MultipartFile photo = image(1);
            when(media.prepareImage(photo)).thenReturn(preparedImage(List.of()));

            ReviewSaved saved = service.create(MEMBER, EVENT_ID, request(5, CONTENT), List.of(photo), null);

            assertThat(saved.status()).isEqualTo("PROCESSING");
            verify(repository).insertReview(MEMBER_ID, EVENT_ID, 2026, 5, CONTENT, "PROCESSING", NOW);
            verify(publisher, never()).awardOnce(anyLong(), anyLong());
        }

        @Test
        @DisplayName("UTC-106 AF2 Ảnh/video được quét sau khi lưu xong (sau commit)")
        void utc106_withPhotos_scannedAfterCommit() {
            MultipartFile photo = image(1);
            when(media.prepareImage(photo)).thenReturn(preparedImage(List.of()));

            service.create(MEMBER, EVENT_ID, request(5, CONTENT), List.of(photo), null);
            verify(publisher, never()).scanSoon(anyLong());
            commit();

            verify(publisher).scanSoon(REVIEW_ID);
        }

        @Test
        @DisplayName("UTC-107 Ảnh được lưu vào thư mục review và gắn theo thứ tự đã chọn")
        void utc107_photosSavedInOrder() {
            MultipartFile a = image(1);
            MultipartFile b = image(2);
            Prepared pa = preparedImage(List.of());
            Prepared pb = preparedImage(List.of());
            when(media.prepareImage(a)).thenReturn(pa);
            when(media.prepareImage(b)).thenReturn(pb);
            when(media.save(MEMBER_ID, "review", pa)).thenReturn(701L);
            when(media.save(MEMBER_ID, "review", pb)).thenReturn(702L);

            service.create(MEMBER, EVENT_ID, request(5, CONTENT), List.of(a, b), null);

            InOrder order = inOrder(repository);
            order.verify(repository).linkMedia(REVIEW_ID, 701L, 0);
            order.verify(repository).linkMedia(REVIEW_ID, 702L, 1);
        }

        @Test
        @DisplayName("UTC-108 Có video: tính 1 video vào hạn mức trong ngày")
        void utc108_withVideo_countsVideoUsage() {
            MultipartFile clip = video();
            when(media.prepareVideo(clip)).thenReturn(preparedVideo());

            ReviewSaved saved = service.create(MEMBER, EVENT_ID, request(5, CONTENT), null, clip);

            assertThat(saved.status()).isEqualTo("PROCESSING");
            verify(accounts).addUsage(MEMBER_ID, TODAY, 1, 1);
        }

        @Test
        @DisplayName("UTC-109 Đúng 5 ảnh hợp lệ")
        void utc109_fivePhotos_valid() {
            List<MultipartFile> photos = new ArrayList<>();
            for (int i = 0; i < 5; i++) {
                MultipartFile p = image(i);
                when(media.prepareImage(p)).thenReturn(preparedImage(List.of()));
                photos.add(p);
            }

            assertThat(service.create(MEMBER, EVENT_ID, request(5, CONTENT), photos, null).status())
                    .isEqualTo("PROCESSING");
        }

        @Test
        @DisplayName("UTC-110 Tệp rỗng trong danh sách ảnh bị bỏ qua")
        void utc110_emptyFilesIgnored() {
            ReviewSaved saved = service.create(MEMBER, EVENT_ID, request(5, CONTENT),
                    List.of(new MockMultipartFile("images", new byte[0])), new MockMultipartFile("video", new byte[0]));

            assertThat(saved.status()).isEqualTo("PUBLISHED");
            verify(media, never()).prepareImage(any());
            verify(media, never()).prepareVideo(any());
        }

        @ParameterizedTest(name = "[{index}] {0} sao")
        @ValueSource(ints = {1, 5})
        @DisplayName("UTC-111 GBR-40 Số sao biên 1 và 5 hợp lệ")
        void utc111_ratingBoundaries_valid(int rating) {
            assertThat(create(request(rating, CONTENT)).status()).isEqualTo("PUBLISHED");
        }

        @ParameterizedTest(name = "[{index}] {0} sao")
        @NullSource
        @ValueSource(ints = {0, 6, -1})
        @DisplayName("UTC-112 AF3 Thiếu hoặc sai số sao → \"Hãy chọn số sao (1 đến 5).\"")
        void utc112_invalidRating_rejected(Integer rating) {
            assertInvalid(() -> create(request(rating, CONTENT)), "rating", "Hãy chọn số sao (1 đến 5).");
            verifyNoReviewSaved();
        }

        @ParameterizedTest(name = "[{index}] nội dung = \"{0}\"")
        @NullSource
        @ValueSource(strings = {"", "    "})
        @DisplayName("UTC-113 AF3 Nội dung trống → \"Hãy viết vài dòng về sự kiện.\"")
        void utc113_emptyContent_rejected(String content) {
            assertInvalid(() -> create(request(5, content)), "content", "Hãy viết vài dòng về sự kiện.");
            verifyNoReviewSaved();
        }

        @Test
        @DisplayName("UTC-114 AF3 Nội dung 9 ký tự → \"Nội dung cần ít nhất 10 ký tự.\"")
        void utc114_content9_rejected() {
            assertInvalid(() -> create(request(5, "123456789")), "content", "Nội dung cần ít nhất 10 ký tự.");
            verifyNoReviewSaved();
        }

        @Test
        @DisplayName("UTC-115 Nội dung đúng 10 ký tự và đúng 2.000 ký tự hợp lệ")
        void utc115_content10And2000_valid() {
            assertThat(create(request(5, "1234567890")).status()).isEqualTo("PUBLISHED");
            assertThat(create(request(5, "a".repeat(2000))).status()).isEqualTo("PUBLISHED");
        }

        @Test
        @DisplayName("UTC-116 AF3 Nội dung 2.001 ký tự → \"Nội dung tối đa 2.000 ký tự (hiện có 2001).\"")
        void utc116_content2001_rejected() {
            assertInvalid(() -> create(request(5, "a".repeat(2001))), "content",
                    "Nội dung tối đa 2.000 ký tự (hiện có 2001).");
            verifyNoReviewSaved();
        }

        @Test
        @DisplayName("UTC-117 AF3 Khoảng trắng đầu cuối không được tính và không được lưu")
        void utc117_surroundingSpacesNotCounted() {
            assertInvalid(() -> create(request(5, "    123456789    ")), "content", "Nội dung cần ít nhất 10 ký tự.");

            create(request(5, "   " + CONTENT + "  \n"));
            verify(repository).insertReview(MEMBER_ID, EVENT_ID, 2026, 5, CONTENT, "PUBLISHED", NOW);
        }

        @Test
        @DisplayName("UTC-118 AF3 Máy chủ nhận 6 ảnh → \"Tối đa 5 ảnh.\", không đọc ảnh nào")
        void utc118_sixPhotos_rejected() {
            List<MultipartFile> photos = new ArrayList<>();
            for (int i = 0; i < 6; i++) {
                photos.add(image(i));
            }

            assertInvalid(() -> service.create(MEMBER, EVENT_ID, request(5, CONTENT), photos, null), "images",
                    "Tối đa 5 ảnh.");
            verify(media, never()).prepareImage(any());
            verifyNoReviewSaved();
        }

        @Test
        @DisplayName("UTC-119 AF3 GBR-31 Ảnh sai định dạng hoặc quá 20 MB: báo lỗi tại ô ảnh")
        void utc119_invalidPhoto_rejected() {
            MultipartFile photo = image(1);
            when(media.prepareImage(photo)).thenThrow(new InvalidMediaException(
                    "Ảnh phải là JPG, PNG hoặc HEIC và không quá 20 MB."));

            assertInvalid(() -> service.create(MEMBER, EVENT_ID, request(5, CONTENT), List.of(photo), null),
                    "images", "Ảnh phải là JPG, PNG hoặc HEIC và không quá 20 MB.");
            verifyNoReviewSaved();
        }

        @Test
        @DisplayName("UTC-120 AF3 GBR-31 Video quá 30 giây hoặc không đọc được: báo lỗi tại ô video")
        void utc120_invalidVideo_rejected() {
            MultipartFile clip = video();
            when(media.prepareVideo(clip)).thenThrow(new InvalidMediaException(
                    "Video phải là MP4 hoặc MOV, dài tối đa 30 giây và không quá 50 MB."));

            assertInvalid(() -> service.create(MEMBER, EVENT_ID, request(5, CONTENT), null, clip), "video",
                    "Video phải là MP4 hoặc MOV, dài tối đa 30 giây và không quá 50 MB.");
            verifyNoReviewSaved();
        }

        @Test
        @DisplayName("UTC-121 AF3 Nhiều lỗi: báo từng trường, câu đầu là câu tóm tắt")
        void utc121_severalErrors_allReported() {
            ApiException e = apiError(() -> create(request(null, "ngắn")));

            assertThat(e.getMessage()).isEqualTo("Hãy chọn số sao (1 đến 5).");
            assertThat(e.getFields()).containsOnlyKeys("rating", "content");
        }

        @Test
        @DisplayName("UTC-122 Bước 4 Thứ tự kiểm tra: liên kết → mã QR → hạn mức → từ khóa cấm → lưu")
        void utc122_checkOrder() {
            MultipartFile photo = image(1);
            when(media.prepareImage(photo)).thenReturn(preparedImage(List.of("https://example.org")));

            service.create(MEMBER, EVENT_ID, request(5, CONTENT), List.of(photo), null);

            InOrder order = inOrder(media, filter, accounts, repository);
            order.verify(media).prepareImage(photo);
            order.verify(filter).checkLinks(CONTENT, MEMBER_ID);
            order.verify(filter).checkQrCodes(List.of(List.of("https://example.org")), MEMBER_ID);
            order.verify(accounts).lockUsage(MEMBER_ID, TODAY);
            order.verify(filter).checkKeywords(CONTENT);
            order.verify(filter).checkQrKeywords(List.of(List.of("https://example.org")));
            order.verify(repository).insertReview(MEMBER_ID, EVENT_ID, 2026, 5, CONTENT, "PROCESSING", NOW);
        }

        @Test
        @DisplayName("UTC-123 Dữ liệu sai: dừng ở bước 1, không kiểm tra liên kết, hạn mức, từ khóa")
        void utc123_fieldErrorStopsBeforeLaterSteps() {
            catchThrowable(() -> create(request(0, CONTENT)));

            verifyNoInteractions(filter);
            verify(accounts, never()).lockUsage(anyLong(), any());
        }

        @Test
        @DisplayName("UTC-124 AF4 GBR-27 Chèn link khi không có tag → từ chối, dừng trước hạn mức và từ khóa")
        void utc124_linkWithoutTag_rejected() {
            doThrow(ApiException.badRequest("Chỉ tài khoản đã có tag định danh mới được chèn link."))
                    .when(filter).checkLinks(anyString(), anyLong());

            assertApiError(() -> create(request(5, "Xem thêm ở https://example.org nhé")), HttpStatus.BAD_REQUEST,
                    "Chỉ tài khoản đã có tag định danh mới được chèn link.");
            verify(accounts, never()).lockUsage(anyLong(), any());
            verify(filter, never()).checkKeywords(anyString());
            verifyNoReviewSaved();
        }

        @Test
        @DisplayName("UTC-125 AF4 Ảnh có mã QR khi không có tag → \"Ảnh thứ N bạn vừa chọn có mã QR…\"")
        void utc125_qrPhotoWithoutTag_rejected() {
            MultipartFile photo = image(1);
            when(media.prepareImage(photo)).thenReturn(preparedImage(List.of("https://zalo.me/123")));
            doThrow(ApiException.invalid("Ảnh thứ 1 bạn vừa chọn có mã QR.", java.util.Map.of("images",
                    "Ảnh thứ 1 bạn vừa chọn có mã QR."))).when(filter).checkQrCodes(anyList(), anyLong());

            assertInvalid(() -> service.create(MEMBER, EVENT_ID, request(5, CONTENT), List.of(photo), null),
                    "images", "Ảnh thứ 1 bạn vừa chọn có mã QR.");
            verify(accounts, never()).lockUsage(anyLong(), any());
            verifyNoReviewSaved();
        }

        @Test
        @DisplayName("UTC-126 Mã QR chỉ đọc ở ảnh, không ở video")
        void utc126_qrCodesOnlyFromPhotos() {
            MultipartFile photo = image(1);
            MultipartFile clip = video();
            when(media.prepareImage(photo)).thenReturn(preparedImage(List.of("abc")));
            when(media.prepareVideo(clip)).thenReturn(preparedVideo());

            service.create(MEMBER, EVENT_ID, request(5, CONTENT), List.of(photo), clip);

            verify(filter).checkQrCodes(List.of(List.of("abc")), MEMBER_ID);
        }

        @Test
        @DisplayName("UTC-127 AF5 GBR-32 Tài khoản mới đã đăng 3/3 → 429, thông báo đúng như đặc tả")
        void utc127_newAccountPostLimit_rejected() {
            newAccount();
            when(accounts.lockUsage(MEMBER_ID, TODAY)).thenReturn(new Usage(3, 0, 0));

            assertApiError(() -> create(request(5, CONTENT)), HttpStatus.TOO_MANY_REQUESTS,
                    "Tài khoản mới (dưới 7 ngày) chỉ được đăng 3 bài viết hoặc review mỗi ngày. Hôm nay bạn đã dùng "
                            + "hết 3/3, hãy quay lại vào ngày mai.");
            verify(filter, never()).checkKeywords(anyString());
            verifyNoReviewSaved();
        }

        @Test
        @DisplayName("UTC-128 Tài khoản mới đã đăng 2/3: vẫn đăng được")
        void utc128_newAccountUnderLimit_allowed() {
            newAccount();
            when(accounts.lockUsage(MEMBER_ID, TODAY)).thenReturn(new Usage(2, 0, 0));

            assertThat(create(request(5, CONTENT)).status()).isEqualTo("PUBLISHED");
        }

        @Test
        @DisplayName("UTC-129 AF5 Tài khoản mới đã đăng 1/1 video, gửi thêm video → 429")
        void utc129_newAccountVideoLimit_rejected() {
            newAccount();
            when(accounts.lockUsage(MEMBER_ID, TODAY)).thenReturn(new Usage(0, 0, 1));
            MultipartFile clip = video();
            when(media.prepareVideo(clip)).thenReturn(preparedVideo());

            assertApiError(() -> service.create(MEMBER, EVENT_ID, request(5, CONTENT), null, clip),
                    HttpStatus.TOO_MANY_REQUESTS,
                    "Tài khoản mới chỉ được đăng 1 video mỗi ngày. Hãy bỏ video hoặc thử lại vào ngày mai.");
            verifyNoReviewSaved();
        }

        @Test
        @DisplayName("UTC-130 Tài khoản mới đã đăng 1/1 video, review không có video: vẫn đăng được")
        void utc130_videoLimitIgnoredWithoutVideo() {
            newAccount();
            when(accounts.lockUsage(MEMBER_ID, TODAY)).thenReturn(new Usage(0, 0, 1));

            assertThat(create(request(5, CONTENT)).status()).isEqualTo("PUBLISHED");
        }

        @Test
        @DisplayName("UTC-131 Tài khoản đủ 7 ngày không bị giới hạn (đã đăng 3 bài vẫn đăng được)")
        void utc131_accountOf7Days_notLimited() {
            account(MEMBER_ID, "USER", "ACTIVE", NOW.minus(Duration.ofDays(7)));
            when(accounts.lockUsage(MEMBER_ID, TODAY)).thenReturn(new Usage(3, 0, 1));

            assertThat(create(request(5, CONTENT)).status()).isEqualTo("PUBLISHED");
        }

        @Test
        @DisplayName("UTC-132 GBR-32 Hạn mức theo cài đặt của Admin (10 ngày, 2 bài/ngày)")
        void utc132_limitsFollowAdminSettings() {
            when(settings.getInt(SystemSettings.NEW_ACCOUNT_DAYS, 7)).thenReturn(10);
            when(settings.getInt(SystemSettings.POSTS_PER_DAY, 3)).thenReturn(2);
            account(MEMBER_ID, "USER", "ACTIVE", NOW.minus(Duration.ofDays(8)));
            when(accounts.lockUsage(MEMBER_ID, TODAY)).thenReturn(new Usage(2, 0, 0));

            assertApiError(() -> create(request(5, CONTENT)), HttpStatus.TOO_MANY_REQUESTS,
                    "Tài khoản mới (dưới 10 ngày) chỉ được đăng 2 bài viết hoặc review mỗi ngày. Hôm nay bạn đã dùng "
                            + "hết 2/2, hãy quay lại vào ngày mai.");
        }

        @Test
        @DisplayName("UTC-133 AF6 GBR-25 Chứa từ khóa cấm → từ chối, nêu từ khóa, không lưu")
        void utc133_bannedKeyword_rejected() {
            doThrow(ApiException.badRequest("Nội dung chứa từ khóa không được phép: \"fb88\"."))
                    .when(filter).checkKeywords(anyString());

            assertApiError(() -> create(request(5, "Vào fb88 chơi cho vui nhé")), HttpStatus.BAD_REQUEST,
                    "Nội dung chứa từ khóa không được phép: \"fb88\".");
            verifyNoReviewSaved();
        }

        @Test
        @DisplayName("UTC-134 AF8 GBR-41 Đã có review năm nay → 409 \"Bạn đã viết review cho lần tổ chức năm 2026…\"")
        void utc134_alreadyReviewedThisYear_conflict() {
            when(repository.ownReviewId(EVENT_ID, 2026, MEMBER_ID)).thenReturn(Optional.of(REVIEW_ID));
            when(repository.ownReview(REVIEW_ID)).thenReturn(Optional.of(new OwnRow(REVIEW_ID, 4, CONTENT,
                    "PUBLISHED")));

            assertApiError(() -> create(request(5, CONTENT)), HttpStatus.CONFLICT,
                    "Bạn đã viết review cho lần tổ chức năm 2026. Hãy sửa review đó.");
            verifyNoReviewSaved();
        }

        @Test
        @DisplayName("UTC-135 AF12 Review năm nay đã bị gỡ → 409 \"…đã bị gỡ nên không viết lại được…\"")
        void utc135_removedThisYear_conflict() {
            when(repository.ownReviewId(EVENT_ID, 2026, MEMBER_ID)).thenReturn(Optional.of(REVIEW_ID));
            when(repository.ownReview(REVIEW_ID)).thenReturn(Optional.of(new OwnRow(REVIEW_ID, 1, "x", "REMOVED")));

            assertApiError(() -> create(request(5, CONTENT)), HttpStatus.CONFLICT,
                    "Review năm 2026 của bạn đã bị gỡ nên không viết lại được cho lần tổ chức này.");
            verifyNoReviewSaved();
        }

        @Test
        @DisplayName("UTC-136 Gửi trùng cùng lúc (khóa duy nhất) → 409 \"Bạn đã viết review…\"")
        void utc136_concurrentDuplicate_conflict() {
            when(repository.insertReview(anyLong(), anyLong(), anyInt(), anyInt(), anyString(), anyString(), any()))
                    .thenThrow(new DuplicateKeyException("uq_review_event_year"));

            assertApiError(() -> create(request(5, CONTENT)), HttpStatus.CONFLICT,
                    "Bạn đã viết review cho lần tổ chức năm 2026. Hãy sửa review đó.");
        }

        @Test
        @DisplayName("UTC-137 AF7/AF11 Sự kiện chưa bắt đầu → 400; đã bị xóa → 404; không lưu")
        void utc137_notStartedOrDeleted_rejected() {
            when(repository.event(EVENT_ID)).thenReturn(Optional.of(event(TODAY.plusDays(1), TODAY.plusDays(1))));
            assertApiError(() -> create(request(5, CONTENT)), HttpStatus.BAD_REQUEST, MSG_NOT_STARTED);

            when(repository.event(EVENT_ID)).thenReturn(Optional.empty());
            assertApiError(() -> create(request(5, CONTENT)), HttpStatus.NOT_FOUND, MSG_EVENT_NOT_FOUND);
            verifyNoReviewSaved();
        }

        @Test
        @DisplayName("UTC-138 GBR-12/13 Khách → 401; nhân viên → 403; không lưu")
        void utc138_guestAndStaff_rejected() {
            assertApiError(() -> service.create(null, EVENT_ID, request(5, CONTENT), null, null),
                    HttpStatus.UNAUTHORIZED, MSG_LOGIN);

            account(MEMBER_ID, "MODERATOR", "ACTIVE", NOW.minus(Duration.ofDays(30)));
            assertApiError(() -> create(request(5, CONTENT)), HttpStatus.FORBIDDEN, MSG18);
            verifyNoReviewSaved();
        }
    }

    // =================================================================================================================
    @Nested
    @TestMethodOrder(MethodOrderer.DisplayName.class)
    @DisplayName("03. update – UC 3 AF1 Sửa review năm nay (UTC-139 → UTC-153)")
    class UpdateReview {

        @BeforeEach
        void published() {
            ownReview("PUBLISHED");
            when(repository.attachedMedia(REVIEW_ID)).thenReturn(List.of(file(1, "IMAGE", "PASSED"),
                    file(2, "IMAGE", "PASSED")));
        }

        private ReviewSaved update(Integer rating, String content, List<Long> keep, List<MultipartFile> images,
                                   MultipartFile video) {
            return service.update(MEMBER, REVIEW_ID, new ReviewUpdateRequest(rating, content, keep), images, video);
        }

        @Test
        @DisplayName("UTC-139 Sửa review đã đăng, không thêm tệp: vẫn PUBLISHED, lưu sao và nội dung mới")
        void utc139_editPublished_staysPublished() {
            ReviewSaved saved = update(4, "Đông quá nhưng vẫn vui.", List.of(1L, 2L), null, null);

            assertThat(saved.status()).isEqualTo("PUBLISHED");
            assertThat(saved.eventId()).isEqualTo(EVENT_ID);
            verify(repository).updateReview(REVIEW_ID, 4, "Đông quá nhưng vẫn vui.", "PUBLISHED", NOW);
        }

        @Test
        @DisplayName("UTC-140 GBR-33 Sửa không tính là bài mới: không tăng số bài trong ngày, không kiểm hạn mức")
        void utc140_editIsNotNewPost() {
            update(4, CONTENT, List.of(1L, 2L), null, null);

            verify(accounts, never()).lockUsage(anyLong(), any());
            verify(accounts, never()).addUsage(anyLong(), any(), anyInt(), anyInt());
            verify(repository, never()).insertReview(anyLong(), anyLong(), anyInt(), anyInt(), anyString(),
                    anyString(), any());
        }

        @Test
        @DisplayName("UTC-141 GBR-33 Bản sửa qua cùng kiểm tra liên kết và từ khóa cấm")
        void utc141_editRunsSameChecks() {
            update(4, CONTENT, List.of(1L, 2L), null, null);

            verify(filter).checkLinks(CONTENT, MEMBER_ID);
            verify(filter).checkKeywords(CONTENT);
        }

        @Test
        @DisplayName("UTC-142 AF6 Bản sửa chứa từ khóa cấm → từ chối, không lưu")
        void utc142_editWithBannedKeyword_rejected() {
            doThrow(ApiException.badRequest("Nội dung chứa từ khóa không được phép: \"cá cược\"."))
                    .when(filter).checkKeywords(anyString());

            assertApiError(() -> update(4, "Có chỗ cá cược gần đó", List.of(1L, 2L), null, null),
                    HttpStatus.BAD_REQUEST, "Nội dung chứa từ khóa không được phép: \"cá cược\".");
            verify(repository, never()).updateReview(anyLong(), anyInt(), anyString(), anyString(), any());
        }

        @Test
        @DisplayName("UTC-143 Bỏ ảnh cũ: ảnh bị bỏ được gỡ và xóa, ảnh giữ lại xếp lại thứ tự")
        void utc143_removePhoto_unlinkedAndDiscarded() {
            update(4, CONTENT, List.of(2L), null, null);

            verify(repository).unlinkMedia(REVIEW_ID, List.of(1L));
            verify(media).discard(List.of(1L));
            verify(repository).setMediaOrder(REVIEW_ID, 2L, 0);
        }

        @Test
        @DisplayName("UTC-144 Giữ ảnh theo thứ tự mới người dùng chọn")
        void utc144_keptPhotosReordered() {
            update(4, CONTENT, List.of(2L, 1L), null, null);

            verify(repository).setMediaOrder(REVIEW_ID, 2L, 0);
            verify(repository).setMediaOrder(REVIEW_ID, 1L, 1);
            verify(repository).unlinkMedia(REVIEW_ID, List.of());
        }

        @Test
        @DisplayName("UTC-145 Thêm ảnh mới: review chờ quét lại, ảnh mới xếp sau ảnh giữ lại")
        void utc145_newPhoto_backToProcessing() {
            MultipartFile photo = image(3);
            Prepared prepared = preparedImage(List.of());
            when(media.prepareImage(photo)).thenReturn(prepared);
            when(media.save(MEMBER_ID, "review", prepared)).thenReturn(703L);

            ReviewSaved saved = update(4, CONTENT, List.of(1L, 2L), List.of(photo), null);
            commit();

            assertThat(saved.status()).isEqualTo("PROCESSING");
            verify(repository).linkMedia(REVIEW_ID, 703L, 2);
            verify(publisher).scanSoon(REVIEW_ID);
        }

        @Test
        @DisplayName("UTC-146 GBR-22/33 Sửa không cộng điểm lần nữa (chỉ gọi trả điểm một lần duy nhất)")
        void utc146_editDoesNotPayTwice() {
            update(4, CONTENT, List.of(1L, 2L), null, null);

            verify(publisher).awardOnce(MEMBER_ID, REVIEW_ID);
            verify(points, never()).add(anyLong(), anyInt(), anyString(), anyString(), any());
        }

        @Test
        @DisplayName("UTC-147 AF3 Giữ 4 ảnh + thêm 2 ảnh → \"Tối đa 5 ảnh.\"")
        void utc147_keptPlusNewOver5_rejected() {
            when(repository.attachedMedia(REVIEW_ID)).thenReturn(List.of(file(1, "IMAGE", "PASSED"),
                    file(2, "IMAGE", "PASSED"), file(3, "IMAGE", "PASSED"), file(4, "IMAGE", "PASSED")));

            assertInvalid(() -> update(4, CONTENT, List.of(1L, 2L, 3L, 4L), List.of(image(5), image(6)), null),
                    "images", "Tối đa 5 ảnh.");
            verify(repository, never()).updateReview(anyLong(), anyInt(), anyString(), anyString(), any());
        }

        @Test
        @DisplayName("UTC-148 AF3 Giữ video cũ + thêm video mới → \"Tối đa 1 video.\"")
        void utc148_keptVideoPlusNewVideo_rejected() {
            when(repository.attachedMedia(REVIEW_ID)).thenReturn(List.of(file(5, "VIDEO", "PASSED")));

            assertInvalid(() -> update(4, CONTENT, List.of(5L), null, video()), "video", "Tối đa 1 video.");
        }

        @Test
        @DisplayName("UTC-149 Thêm video vào review chưa có video: tính hạn mức video")
        void utc149_addFirstVideo_countsVideo() {
            MultipartFile clip = video();
            when(media.prepareVideo(clip)).thenReturn(preparedVideo());

            update(4, CONTENT, List.of(1L, 2L), null, clip);

            verify(accounts).lockUsage(MEMBER_ID, TODAY);
            verify(accounts).addUsage(MEMBER_ID, TODAY, 0, 1);
        }

        @Test
        @DisplayName("UTC-150 Thay video (review đã có video): không tính thêm hạn mức video")
        void utc150_replaceVideo_notCounted() {
            when(repository.attachedMedia(REVIEW_ID)).thenReturn(List.of(file(5, "VIDEO", "PASSED")));
            MultipartFile clip = video();
            when(media.prepareVideo(clip)).thenReturn(preparedVideo());

            update(4, CONTENT, List.of(), null, clip);

            verify(accounts, never()).lockUsage(anyLong(), any());
            verify(accounts, never()).addUsage(anyLong(), any(), anyInt(), anyInt());
        }

        @Test
        @DisplayName("UTC-151 Review bị từ chối, sửa bỏ tệp vi phạm, tệp còn lại đã đạt → được đăng")
        void utc151_rejectedReviewWithPassedFiles_published() {
            ownReview("REJECTED");

            assertThat(update(4, CONTENT, List.of(1L, 2L), null, null).status()).isEqualTo("PUBLISHED");
        }

        @Test
        @DisplayName("UTC-152 Review đang chờ quét, tệp giữ lại chưa quét xong → vẫn chờ")
        void utc152_processingReviewWithWaitingFile_staysProcessing() {
            ownReview("PROCESSING");
            when(repository.attachedMedia(REVIEW_ID)).thenReturn(List.of(file(1, "IMAGE", "PROCESSING")));

            assertThat(update(4, CONTENT, List.of(1L), null, null).status()).isEqualTo("PROCESSING");
        }

        @Test
        @DisplayName("UTC-153 Chỉ sửa được review của mình, đang hiện / chờ quét / bị từ chối, của sự kiện còn tồn tại")
        void utc153_editGuards() {
            othersReview("PUBLISHED");
            assertApiError(() -> update(4, CONTENT, null, null, null), HttpStatus.FORBIDDEN, MSG18);

            ownReview("HIDDEN");
            assertApiError(() -> update(4, CONTENT, null, null, null), HttpStatus.NOT_FOUND, MSG_REVIEW_NOT_FOUND);

            ownReview("REMOVED");
            assertApiError(() -> update(4, CONTENT, null, null, null), HttpStatus.NOT_FOUND, MSG_REVIEW_NOT_FOUND);

            ownReview("PUBLISHED");
            when(repository.event(EVENT_ID)).thenReturn(Optional.empty());
            assertApiError(() -> update(4, CONTENT, null, null, null), HttpStatus.NOT_FOUND, MSG_REVIEW_NOT_FOUND);

            verify(repository, never()).updateReview(anyLong(), anyInt(), anyString(), anyString(), any());
        }
    }

    // =================================================================================================================
    @Nested
    @TestMethodOrder(MethodOrderer.DisplayName.class)
    @DisplayName("04. delete – UC 2 AF5 Xóa review của mình (UTC-154 → UTC-159)")
    class DeleteReview {

        @Test
        @DisplayName("UTC-154 Xóa review của mình: xóa review, xóa ảnh/video")
        void utc154_deleteOwn_reviewAndFilesGone() {
            ownReview("PUBLISHED");
            when(repository.allMediaIds(REVIEW_ID)).thenReturn(List.of(1L, 2L));

            service.delete(MEMBER, REVIEW_ID);

            verify(repository).deleteReview(REVIEW_ID);
            verify(media).discard(List.of(1L, 2L));
        }

        @Test
        @DisplayName("UTC-155 GBR-23 Điểm của review bị tác giả tự xóa được thu lại")
        void utc155_pointsWithdrawn() {
            ownReview("PUBLISHED");

            service.delete(MEMBER, REVIEW_ID);

            verify(points).withdrawAll(MEMBER_ID, "REVIEW", REVIEW_ID, "CONTENT_DELETED");
        }

        @Test
        @DisplayName("UTC-156 Đóng các báo cáo đang chờ về review đã xóa")
        void utc156_openCasesClosed() {
            ownReview("PUBLISHED");

            service.delete(MEMBER, REVIEW_ID);

            verify(reports).closeCasesOf("REVIEW", List.of(REVIEW_ID));
        }

        @ParameterizedTest(name = "[{index}] trạng thái {0}")
        @ValueSource(strings = {"PROCESSING", "REJECTED", "HIDDEN"})
        @DisplayName("UTC-157 Xóa được review đang quét, bị từ chối hoặc bị ẩn của mình")
        void utc157_deleteOwnInOtherStates(String status) {
            ownReview(status);

            service.delete(MEMBER, REVIEW_ID);

            verify(repository).deleteReview(REVIEW_ID);
        }

        @Test
        @DisplayName("UTC-158 Review đã bị Moderator gỡ không xóa được → 404")
        void utc158_removedReview_notFound() {
            ownReview("REMOVED");

            assertApiError(() -> service.delete(MEMBER, REVIEW_ID), HttpStatus.NOT_FOUND, MSG_REVIEW_NOT_FOUND);
            verify(repository, never()).deleteReview(anyLong());
            verifyNoInteractions(points);
        }

        @Test
        @DisplayName("UTC-159 Xóa review của người khác → 403; Khách → 401")
        void utc159_othersReviewOrGuest_rejected() {
            othersReview("PUBLISHED");
            assertApiError(() -> service.delete(MEMBER, REVIEW_ID), HttpStatus.FORBIDDEN, MSG18);
            assertApiError(() -> service.delete(null, REVIEW_ID), HttpStatus.UNAUTHORIZED, MSG_LOGIN);

            verify(repository, never()).deleteReview(anyLong());
            verifyNoInteractions(points);
        }
    }

    // =================================================================================================================
    @Nested
    @TestMethodOrder(MethodOrderer.DisplayName.class)
    @DisplayName("05. like – UC 2 AF3 Thích review (UTC-160 → UTC-165)")
    class LikeReview {

        @BeforeEach
        void othersPublished() {
            othersReview("PUBLISHED");
            when(repository.likeCount(REVIEW_ID)).thenReturn(4);
        }

        @Test
        @DisplayName("UTC-160 Thích review của người khác: lưu lượt thích, trả số lượt mới")
        void utc160_like_saved() {
            LikeState state = service.like(MEMBER, REVIEW_ID, true);

            verify(repository).addLike(REVIEW_ID, MEMBER_ID, NOW);
            assertThat(state.liked()).isTrue();
            assertThat(state.likeCount()).isEqualTo(4);
        }

        @Test
        @DisplayName("UTC-161 Bấm lần nữa để bỏ thích")
        void utc161_unlike_removed() {
            LikeState state = service.like(MEMBER, REVIEW_ID, false);

            verify(repository).removeLike(REVIEW_ID, MEMBER_ID);
            verify(repository, never()).addLike(anyLong(), anyLong(), any());
            assertThat(state.liked()).isFalse();
        }

        @Test
        @DisplayName("UTC-162 GBR-34 Thích review của chính mình → \"Bạn không thể thích review của chính mình.\"")
        void utc162_likeOwn_rejected() {
            ownReview("PUBLISHED");

            assertApiError(() -> service.like(MEMBER, REVIEW_ID, true), HttpStatus.BAD_REQUEST,
                    "Bạn không thể thích review của chính mình.");
            verify(repository, never()).addLike(anyLong(), anyLong(), any());
        }

        @ParameterizedTest(name = "[{index}] trạng thái {0}")
        @ValueSource(strings = {"HIDDEN", "PROCESSING", "REJECTED", "REMOVED"})
        @DisplayName("UTC-163 Review không còn hiện → 404")
        void utc163_notVisible_notFound(String status) {
            othersReview(status);

            assertApiError(() -> service.like(MEMBER, REVIEW_ID, true), HttpStatus.NOT_FOUND, MSG_REVIEW_NOT_FOUND);
            verify(repository, never()).addLike(anyLong(), anyLong(), any());
        }

        @Test
        @DisplayName("UTC-164 GBR-39 Tác giả bị khóa/xóa hoặc sự kiện đã bị xóa → 404")
        void utc164_inactiveAuthorOrDeletedEvent_notFound() {
            when(repository.authorActive(REVIEW_ID)).thenReturn(false);
            assertApiError(() -> service.like(MEMBER, REVIEW_ID, true), HttpStatus.NOT_FOUND, MSG_REVIEW_NOT_FOUND);

            when(repository.authorActive(REVIEW_ID)).thenReturn(true);
            when(repository.event(EVENT_ID)).thenReturn(Optional.empty());
            assertApiError(() -> service.like(MEMBER, REVIEW_ID, true), HttpStatus.NOT_FOUND, MSG_REVIEW_NOT_FOUND);
        }

        @Test
        @DisplayName("UTC-165 AF6 Khách → 401; nhân viên → 403")
        void utc165_guestAndStaff_rejected() {
            assertApiError(() -> service.like(null, REVIEW_ID, true), HttpStatus.UNAUTHORIZED, MSG_LOGIN);

            account(MEMBER_ID, "ADMIN", "ACTIVE", NOW.minus(Duration.ofDays(30)));
            assertApiError(() -> service.like(MEMBER, REVIEW_ID, true), HttpStatus.FORBIDDEN, MSG18);
            verify(repository, never()).addLike(anyLong(), anyLong(), any());
        }
    }

    // =================================================================================================================
    @Nested
    @TestMethodOrder(MethodOrderer.DisplayName.class)
    @DisplayName("06. report – UC 2 AF4 Báo cáo review (UTC-166 → UTC-172)")
    class ReportReview {

        @BeforeEach
        void othersPublished() {
            othersReview("PUBLISHED");
        }

        @Test
        @DisplayName("UTC-166 Gửi báo cáo: lưu với lý do, ghi chú, đúng review và tác giả")
        void utc166_report_saved() {
            service.report(MEMBER, REVIEW_ID, new ReportRequest(2L, "Quảng cáo"));

            verify(reports).report(new Target("REVIEW", REVIEW_ID, OTHER_ID), MEMBER_ID, 2L, "Quảng cáo");
        }

        @Test
        @DisplayName("UTC-167 Chưa vượt ngưỡng: review vẫn hiện")
        void utc167_underThreshold_staysPublished() {
            when(reports.report(any(), anyLong(), any(), any())).thenReturn(new Outcome(5, false));

            service.report(MEMBER, REVIEW_ID, new ReportRequest(2L, null));

            verify(repository, never()).setStatus(anyLong(), anyString(), anyString());
        }

        @Test
        @DisplayName("UTC-168 GBR-37 Vượt ngưỡng báo cáo: review tự ẩn (HIDDEN)")
        void utc168_overThreshold_hidden() {
            when(reports.report(any(), anyLong(), any(), any())).thenReturn(new Outcome(6, true));

            service.report(MEMBER, REVIEW_ID, new ReportRequest(2L, null));

            verify(repository).setStatus(REVIEW_ID, "PUBLISHED", "HIDDEN");
        }

        @Test
        @DisplayName("UTC-169 GBR-36 Không chọn lý do → \"Hãy chọn lý do báo cáo.\", review không bị ẩn")
        void utc169_noReason_rejected() {
            when(reports.report(any(), anyLong(), isNull(), any()))
                    .thenThrow(ApiException.badRequest("Hãy chọn lý do báo cáo."));

            assertApiError(() -> service.report(MEMBER, REVIEW_ID, null), HttpStatus.BAD_REQUEST,
                    "Hãy chọn lý do báo cáo.");
            verify(repository, never()).setStatus(anyLong(), anyString(), anyString());
        }

        @Test
        @DisplayName("UTC-170 GBR-36 Báo cáo review của mình → \"Bạn không thể báo cáo nội dung của chính mình.\"")
        void utc170_reportOwn_rejected() {
            ownReview("PUBLISHED");
            when(reports.report(eq(new Target("REVIEW", REVIEW_ID, MEMBER_ID)), eq(MEMBER_ID), any(), any()))
                    .thenThrow(ApiException.badRequest("Bạn không thể báo cáo nội dung của chính mình."));

            assertApiError(() -> service.report(MEMBER, REVIEW_ID, new ReportRequest(1L, null)),
                    HttpStatus.BAD_REQUEST, "Bạn không thể báo cáo nội dung của chính mình.");
        }

        @Test
        @DisplayName("UTC-171 GBR-36 Báo cáo lần hai cùng review → bị từ chối (409)")
        void utc171_secondReport_rejected() {
            when(reports.report(any(), anyLong(), any(), any()))
                    .thenThrow(ApiException.conflict("Bạn đã báo cáo nội dung này rồi."));

            assertApiError(() -> service.report(MEMBER, REVIEW_ID, new ReportRequest(1L, null)), HttpStatus.CONFLICT,
                    "Bạn đã báo cáo nội dung này rồi.");
        }

        @Test
        @DisplayName("UTC-172 Review không còn hiện hoặc sự kiện đã bị xóa → 404, không ghi báo cáo")
        void utc172_notVisible_notFound() {
            othersReview("HIDDEN");
            assertApiError(() -> service.report(MEMBER, REVIEW_ID, new ReportRequest(1L, null)),
                    HttpStatus.NOT_FOUND, MSG_REVIEW_NOT_FOUND);

            othersReview("PUBLISHED");
            when(repository.event(EVENT_ID)).thenReturn(Optional.empty());
            assertApiError(() -> service.report(MEMBER, REVIEW_ID, new ReportRequest(1L, null)),
                    HttpStatus.NOT_FOUND, MSG_REVIEW_NOT_FOUND);

            verify(reports, never()).report(any(), anyLong(), any(), any());
        }
    }
}
