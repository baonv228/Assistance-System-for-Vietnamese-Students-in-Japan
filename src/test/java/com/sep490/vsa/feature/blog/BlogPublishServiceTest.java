package com.sep490.vsa.feature.blog;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.sep490.vsa.feature.blog.BlogWriteRepository.MediaState;
import com.sep490.vsa.feature.blog.BlogWriteRepository.PendingBlog;
import com.sep490.vsa.feature.media.MediaService;
import com.sep490.vsa.feature.moderation.ContentFilter;
import com.sep490.vsa.feature.moderation.MediaScanService;
import com.sep490.vsa.feature.notification.NotificationService;
import com.sep490.vsa.feature.user.PointService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.ClassOrderer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestClassOrder;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.InOrder;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests of the second half of "Tạo bài viết" (UC 1.5, AF 8a: a post with photos or a video waits in PROCESSING
 * until its files are scanned) for Blog posts: {@link BlogPublishService#process} / {@link BlogPublishService#scanSoon}
 * (all files passed → PUBLISHED + points once + CONTENT_PUBLISHED; a file rejected → REJECTED, rejected files deleted,
 * CONTENT_REJECTED with the reason; banned keywords checked again before scanning and before publishing), the retry
 * job {@link BlogPublishService#retryPending} (20 waiting posts a run, in id order after the previous run's last one,
 * from the oldest again after a short page) and {@link BlogPublishService#awardOnce} (+10 the first time only).
 * <p>
 * The database is not used: {@link BlogWriteRepository}, {@link MediaScanService}, {@link MediaService},
 * {@link ContentFilter}, {@link PointService}, {@link NotificationService} and the transaction manager are mocks; the
 * clock is fixed at 20/11/2026 12:00 Japan time. One test runs the real {@link BlogWriteRepository#postAwarded} SQL
 * against a mocked {@link JdbcTemplate}.
 */
@TestClassOrder(ClassOrderer.DisplayName.class)
@DisplayName("BlogPublishService – Tạo bài viết: duyệt ảnh/video rồi đăng bài (UC 1.5)")
class BlogPublishServiceTest {

    private static final long AUTHOR_ID = 10L;
    private static final long BLOG_ID = 500L;
    private static final Instant NOW = Instant.parse("2026-11-20T03:00:00Z");
    private static final String TITLE = "Ảnh hanami ở công viên Ueno";
    private static final String CONTENT = "Hoa nở đẹp nhất vào sáng sớm.";

    private BlogWriteRepository repository;
    private MediaScanService scanner;
    private MediaService media;
    private ContentFilter filter;
    private PointService points;
    private NotificationService notifications;
    private PlatformTransactionManager txManager;
    private BlogPublishService service;

    @BeforeEach
    void setUp() {
        repository = mock(BlogWriteRepository.class);
        scanner = mock(MediaScanService.class);
        media = mock(MediaService.class);
        filter = mock(ContentFilter.class);
        points = mock(PointService.class);
        notifications = mock(NotificationService.class);
        txManager = mock(PlatformTransactionManager.class);
        service = new BlogPublishService(repository, scanner, media, filter, points, notifications, txManager,
                Clock.fixed(NOW, ZoneOffset.UTC));
        when(repository.pendingBlog(BLOG_ID)).thenReturn(Optional.of(pending(TITLE, CONTENT, "PROCESSING")));
        when(repository.lockBlog(BLOG_ID)).thenReturn(Optional.of(pending(TITLE, CONTENT, "PROCESSING")));
        when(repository.setStatus(anyLong(), anyString(), anyString())).thenReturn(true);
        when(filter.bannedKeyword(any())).thenReturn(Optional.empty());
        when(scanner.rejectionReason(any())).thenReturn(Optional.empty());
    }

    // ---------------------------------------------------------------- helpers

    private static PendingBlog pending(String title, String content, String status) {
        return new PendingBlog(BLOG_ID, AUTHOR_ID, "POST", title, content, status);
    }

    private static MediaState file(long id, String scanStatus) {
        return new MediaState(id, scanStatus);
    }

    /** The files before the scan (first read) and after it (read again under the lock). */
    private void files(List<MediaState> before, List<MediaState> after) {
        when(repository.mediaStates(BLOG_ID)).thenReturn(before, after);
    }

    private static String published(String title) {
        return "Bài viết “" + title + "” đã qua kiểm duyệt ảnh/video và đã được đăng. +10 điểm.";
    }

    private static String rejected(String title, String reason) {
        return "Bài viết “" + title + "” chưa được đăng vì " + reason + ". Bạn có thể sửa bài rồi gửi lại.";
    }

    private void verifyNoDecision() {
        verify(repository, never()).setStatus(anyLong(), anyString(), anyString());
        verify(points, never()).add(anyLong(), anyInt(), anyString(), anyString(), any());
        verify(notifications, never()).send(anyLong(), anyString(), anyString(), anyString(), any());
        verify(media, never()).discard(any());
    }

    // =================================================================================================================
    @Nested
    @TestMethodOrder(MethodOrderer.DisplayName.class)
    @DisplayName("01. process / scanSoon – UC 1.5 Tạo bài viết: kết quả quét ảnh/video (AF 8a) (UTC-223 → UTC-244)")
    class ScanOutcome {

        @Test
        @DisplayName("UTC-223 Mọi tệp qua quét: bài được đăng (PUBLISHED), +10 điểm, tác giả nhận thông báo CONTENT_PUBLISHED")
        void utc223_allFilesPass_publishedWithPointsAndNotification() {
            files(List.of(file(701, "PROCESSING"), file(702, "PROCESSING")),
                    List.of(file(701, "PASSED"), file(702, "PASSED")));

            service.process(BLOG_ID);

            verify(scanner).scan(701L);
            verify(scanner).scan(702L);
            verify(repository).setStatus(BLOG_ID, "PROCESSING", "PUBLISHED");
            verify(points).add(AUTHOR_ID, 10, "BLOG_CREATED", "BLOG", BLOG_ID);
            verify(notifications).send(AUTHOR_ID, "CONTENT_PUBLISHED", published(TITLE), "BLOG", BLOG_ID);
        }

        @Test
        @DisplayName("UTC-224 Chỉ quét tệp còn chờ (PROCESSING), tệp đã qua không quét lại")
        void utc224_onlyWaitingFilesAreScanned() {
            files(List.of(file(701, "PASSED"), file(702, "PROCESSING")),
                    List.of(file(701, "PASSED"), file(702, "PASSED")));

            service.process(BLOG_ID);

            verify(scanner, never()).scan(701L);
            verify(scanner).scan(702L);
            verify(repository).setStatus(BLOG_ID, "PROCESSING", "PUBLISHED");
        }

        @Test
        @DisplayName("UTC-225 scanSoon (gọi sau khi bài được lưu) làm đúng việc của process")
        void utc225_scanSoonDoesTheSameWork() {
            files(List.of(file(701, "PROCESSING")), List.of(file(701, "PASSED")));

            service.scanSoon(BLOG_ID);

            verify(scanner).scan(701L);
            verify(repository).setStatus(BLOG_ID, "PROCESSING", "PUBLISHED");
            verify(notifications).send(AUTHOR_ID, "CONTENT_PUBLISHED", published(TITLE), "BLOG", BLOG_ID);
        }

        @Test
        @DisplayName("UTC-226 Quét xong mới mở giao dịch; quyết định đọc lại bài có khóa rồi commit")
        void utc226_decisionInATransactionOnTheLockedPost() {
            files(List.of(file(701, "PROCESSING")), List.of(file(701, "PASSED")));

            service.process(BLOG_ID);

            InOrder order = inOrder(scanner, txManager, repository);
            order.verify(scanner).scan(701L);
            order.verify(txManager).getTransaction(any());
            order.verify(repository).lockBlog(BLOG_ID);
            order.verify(repository).setStatus(BLOG_ID, "PROCESSING", "PUBLISHED");
            order.verify(txManager).commit(any());
        }

        @Test
        @DisplayName("UTC-227 Bản sửa của bài đã từng được cộng điểm qua quét: đăng lại, không cộng thêm, thông báo \"Bản sửa bài viết…\"")
        void utc227_editedPostAlreadyPaid_noPointsEditNotice() {
            files(List.of(file(703, "PROCESSING")), List.of(file(703, "PASSED")));
            when(repository.postAwarded(BLOG_ID)).thenReturn(true);

            service.process(BLOG_ID);

            verify(repository).setStatus(BLOG_ID, "PROCESSING", "PUBLISHED");
            verify(points, never()).add(anyLong(), anyInt(), anyString(), anyString(), any());
            verify(notifications).send(AUTHOR_ID, "CONTENT_PUBLISHED", "Bản sửa bài viết “" + TITLE
                    + "” đã qua kiểm duyệt ảnh/video và đã được đăng.", "BLOG", BLOG_ID);
        }

        @Test
        @DisplayName("UTC-228 Một tệp bị từ chối: bài REJECTED, chỉ tệp vi phạm bị xóa, tác giả nhận lý do, không cộng điểm")
        void utc228_fileRejected_postRejectedWithReason() {
            String reason = "chữ trong ảnh có số điện thoại di động";
            files(List.of(file(701, "PROCESSING"), file(702, "PROCESSING")),
                    List.of(file(701, "PASSED"), file(702, "REJECTED")));
            when(scanner.rejectionReason(List.of(702L))).thenReturn(Optional.of(reason));

            service.process(BLOG_ID);

            verify(repository).setStatus(BLOG_ID, "PROCESSING", "REJECTED");
            verify(media).discard(List.of(702L));
            verify(notifications).send(AUTHOR_ID, "CONTENT_REJECTED", rejected(TITLE, reason), "BLOG", BLOG_ID);
            verify(points, never()).add(anyLong(), anyInt(), anyString(), anyString(), any());
            verify(repository, never()).setStatus(BLOG_ID, "PROCESSING", "PUBLISHED");
        }

        @Test
        @DisplayName("UTC-229 Không có lý do lưu lại: thông báo nêu \"ảnh hoặc video vi phạm Quy tắc cộng đồng\"")
        void utc229_noStoredReason_communityRules() {
            files(List.of(file(701, "PROCESSING")), List.of(file(701, "REJECTED")));

            service.process(BLOG_ID);

            verify(notifications).send(AUTHOR_ID, "CONTENT_REJECTED",
                    rejected(TITLE, "ảnh hoặc video vi phạm Quy tắc cộng đồng"), "BLOG", BLOG_ID);
        }

        @Test
        @DisplayName("UTC-230 Hai tệp bị từ chối: cả hai bị xóa, lý do lấy theo cả hai tệp")
        void utc230_twoFilesRejected_bothDiscarded() {
            files(List.of(file(701, "PROCESSING"), file(702, "PROCESSING"), file(703, "PROCESSING")),
                    List.of(file(701, "REJECTED"), file(702, "PASSED"), file(703, "REJECTED")));

            service.process(BLOG_ID);

            verify(scanner).rejectionReason(List.of(701L, 703L));
            verify(media).discard(List.of(701L, 703L));
            verify(repository).setStatus(BLOG_ID, "PROCESSING", "REJECTED");
        }

        @Test
        @DisplayName("UTC-231 Một tệp bị từ chối trong khi tệp khác chưa có kết quả: bài bị từ chối ngay")
        void utc231_rejectedWhileAnotherStillWaits_rejectedAtOnce() {
            files(List.of(file(701, "PROCESSING"), file(702, "PROCESSING")),
                    List.of(file(701, "REJECTED"), file(702, "PROCESSING")));

            service.process(BLOG_ID);

            verify(repository).setStatus(BLOG_ID, "PROCESSING", "REJECTED");
            verify(media).discard(List.of(701L));
        }

        @Test
        @DisplayName("UTC-232 Bộ quét chưa trả lời (tệp vẫn PROCESSING): bài tiếp tục chờ, không đổi trạng thái, không thông báo")
        void utc232_scannerDidNotAnswer_postKeepsWaiting() {
            files(List.of(file(701, "PROCESSING")), List.of(file(701, "PROCESSING")));

            service.process(BLOG_ID);

            verify(scanner).scan(701L);
            verifyNoDecision();
        }

        @Test
        @DisplayName("UTC-233 Tiêu đề có từ khóa cấm: không quét tệp, bài REJECTED, thông báo nêu từ khóa, tệp được giữ")
        void utc233_bannedKeywordInTitle_rejectedWithoutScanning() {
            String title = "Kèo fb88 cuối tuần";
            when(repository.pendingBlog(BLOG_ID)).thenReturn(Optional.of(pending(title, CONTENT, "PROCESSING")));
            when(repository.lockBlog(BLOG_ID)).thenReturn(Optional.of(pending(title, CONTENT, "PROCESSING")));
            when(filter.bannedKeyword(title)).thenReturn(Optional.of("fb88"));
            when(repository.mediaStates(BLOG_ID)).thenReturn(List.of(file(701, "PROCESSING")));

            service.process(BLOG_ID);

            verify(scanner, never()).scan(anyLong());
            verify(repository).setStatus(BLOG_ID, "PROCESSING", "REJECTED");
            verify(notifications).send(AUTHOR_ID, "CONTENT_REJECTED",
                    rejected(title, "có từ khóa không được phép “fb88”"), "BLOG", BLOG_ID);
            verify(media, never()).discard(any());
            verify(points, never()).add(anyLong(), anyInt(), anyString(), anyString(), any());
        }

        @Test
        @DisplayName("UTC-234 Nội dung có từ khóa cấm (tiêu đề sạch): bị từ chối với từ khóa trong nội dung")
        void utc234_bannedKeywordInContent_rejected() {
            String content = "Ai cần nhà cái uy tín thì nhắn mình";
            when(repository.pendingBlog(BLOG_ID)).thenReturn(Optional.of(pending(TITLE, content, "PROCESSING")));
            when(repository.lockBlog(BLOG_ID)).thenReturn(Optional.of(pending(TITLE, content, "PROCESSING")));
            when(filter.bannedKeyword(content)).thenReturn(Optional.of("nhà cái"));

            service.process(BLOG_ID);

            verify(scanner, never()).scan(anyLong());
            verify(notifications).send(AUTHOR_ID, "CONTENT_REJECTED",
                    rejected(TITLE, "có từ khóa không được phép “nhà cái”"), "BLOG", BLOG_ID);
        }

        @Test
        @DisplayName("UTC-235 Cả tiêu đề và nội dung có từ khóa: nêu từ khóa của tiêu đề, không xét nội dung")
        void utc235_titleKeywordNamedFirst() {
            String title = "Kèo fb88 cuối tuần";
            String content = "Chơi casino online";
            when(repository.pendingBlog(BLOG_ID)).thenReturn(Optional.of(pending(title, content, "PROCESSING")));
            when(repository.lockBlog(BLOG_ID)).thenReturn(Optional.of(pending(title, content, "PROCESSING")));
            when(filter.bannedKeyword(title)).thenReturn(Optional.of("fb88"));
            when(filter.bannedKeyword(content)).thenReturn(Optional.of("casino"));

            service.process(BLOG_ID);

            verify(notifications).send(AUTHOR_ID, "CONTENT_REJECTED",
                    rejected(title, "có từ khóa không được phép “fb88”"), "BLOG", BLOG_ID);
            verify(filter, never()).bannedKeyword(content);
        }

        @Test
        @DisplayName("UTC-236 Admin thêm từ khóa trong lúc quét: tệp đã quét nhưng bài vẫn bị từ chối trước khi đăng, không cộng điểm")
        void utc236_keywordAddedDuringTheScan_rejectedBeforePublishing() {
            String content = "Ngắm hoa xong ghé tiệm pachinko gần ga Ueno";
            when(repository.pendingBlog(BLOG_ID)).thenReturn(Optional.of(pending(TITLE, content, "PROCESSING")));
            when(repository.lockBlog(BLOG_ID)).thenReturn(Optional.of(pending(TITLE, content, "PROCESSING")));
            files(List.of(file(701, "PROCESSING")), List.of(file(701, "PASSED")));
            when(filter.bannedKeyword(content)).thenReturn(Optional.empty(), Optional.of("pachinko"));

            service.process(BLOG_ID);

            verify(scanner).scan(701L);
            verify(repository).setStatus(BLOG_ID, "PROCESSING", "REJECTED");
            verify(repository, never()).setStatus(BLOG_ID, "PROCESSING", "PUBLISHED");
            verify(notifications).send(AUTHOR_ID, "CONTENT_REJECTED",
                    rejected(TITLE, "có từ khóa không được phép “pachinko”"), "BLOG", BLOG_ID);
            verify(points, never()).add(anyLong(), anyInt(), anyString(), anyString(), any());
        }

        @Test
        @DisplayName("UTC-237 Bài không còn tồn tại: không quét, không mở giao dịch, không làm gì")
        void utc237_postGone_nothingDone() {
            when(repository.pendingBlog(BLOG_ID)).thenReturn(Optional.empty());

            service.process(BLOG_ID);

            verify(scanner, never()).scan(anyLong());
            verify(txManager, never()).getTransaction(any());
            verifyNoDecision();
        }

        @ParameterizedTest(name = "[{index}] status = {0}")
        @ValueSource(strings = {"PUBLISHED", "REJECTED", "HIDDEN"})
        @DisplayName("UTC-238 Bài không còn chờ quét (PUBLISHED, REJECTED, HIDDEN): không quét, không làm gì")
        void utc238_postNotWaiting_nothingDone(String status) {
            when(repository.pendingBlog(BLOG_ID)).thenReturn(Optional.of(pending(TITLE, CONTENT, status)));

            service.process(BLOG_ID);

            verify(scanner, never()).scan(anyLong());
            verify(repository, never()).mediaStates(anyLong());
            verifyNoDecision();
        }

        @Test
        @DisplayName("UTC-239 Trong lúc quét, lần chạy khác đã đăng bài (đọc lại có khóa thấy PUBLISHED): không làm gì thêm")
        void utc239_publishedMeanwhile_nothingMore() {
            files(List.of(file(701, "PROCESSING")), List.of(file(701, "PASSED")));
            when(repository.lockBlog(BLOG_ID)).thenReturn(Optional.of(pending(TITLE, CONTENT, "PUBLISHED")));

            service.process(BLOG_ID);

            verifyNoDecision();
        }

        @Test
        @DisplayName("UTC-240 Tác giả xóa bài trong lúc quét (đọc lại có khóa không thấy bài): không làm gì")
        void utc240_deletedDuringTheScan_nothingDone() {
            files(List.of(file(701, "PROCESSING")), List.of(file(701, "PASSED")));
            when(repository.lockBlog(BLOG_ID)).thenReturn(Optional.empty());

            service.process(BLOG_ID);

            verifyNoDecision();
        }

        @Test
        @DisplayName("UTC-241 Đổi trạng thái không thành (bài đã rời PROCESSING): không cộng điểm, không thông báo")
        void utc241_statusChangeLost_noPointsNoNotice() {
            files(List.of(file(701, "PROCESSING")), List.of(file(701, "PASSED")));
            when(repository.setStatus(BLOG_ID, "PROCESSING", "PUBLISHED")).thenReturn(false);

            service.process(BLOG_ID);

            verify(repository, never()).postAwarded(anyLong());
            verify(points, never()).add(anyLong(), anyInt(), anyString(), anyString(), any());
            verify(notifications, never()).send(anyLong(), anyString(), anyString(), anyString(), any());
        }

        @Test
        @DisplayName("UTC-242 Bài chờ quét không còn tệp nào: được đăng và cộng điểm")
        void utc242_noFileLeft_published() {
            files(List.of(), List.of());

            service.process(BLOG_ID);

            verify(scanner, never()).scan(anyLong());
            verify(repository).setStatus(BLOG_ID, "PROCESSING", "PUBLISHED");
            verify(points).add(AUTHOR_ID, 10, "BLOG_CREATED", "BLOG", BLOG_ID);
        }

        @Test
        @DisplayName("UTC-243 Bài đang được quét thì lần gọi thứ hai cùng lúc bỏ qua (không quét hai lần)")
        void utc243_postAlreadyBeingScanned_secondCallSkipped() {
            files(List.of(file(701, "PROCESSING")), List.of(file(701, "PASSED")));
            when(scanner.scan(701L)).thenAnswer(inv -> {
                service.process(BLOG_ID);          // the retry job reaches the same post meanwhile
                return "PASS";
            });

            service.process(BLOG_ID);

            verify(repository, times(1)).pendingBlog(BLOG_ID);
            verify(scanner, times(1)).scan(701L);
            verify(repository, times(1)).setStatus(BLOG_ID, "PROCESSING", "PUBLISHED");
        }

        @Test
        @DisplayName("UTC-244 Lần quét lỗi (ngoại lệ) không giữ bài lại: lần sau vẫn quét và đăng được")
        void utc244_failedScanReleasesThePost() {
            when(repository.mediaStates(BLOG_ID)).thenReturn(List.of(file(701, "PROCESSING")),
                    List.of(file(701, "PROCESSING")), List.of(file(701, "PASSED")));
            when(scanner.scan(701L)).thenThrow(new IllegalStateException("OpenAI down")).thenReturn("PASS");

            assertThatThrownBy(() -> service.process(BLOG_ID)).hasMessage("OpenAI down");
            service.process(BLOG_ID);

            verify(scanner, times(2)).scan(701L);
            verify(repository).setStatus(BLOG_ID, "PROCESSING", "PUBLISHED");
        }
    }

    // =================================================================================================================
    @Nested
    @TestMethodOrder(MethodOrderer.DisplayName.class)
    @DisplayName("02. retryPending – UC 1.5 Tạo bài viết: quét lại bài còn chờ (UTC-245 → UTC-250)")
    class RetryJob {

        private List<Long> ids(long from, int count) {
            List<Long> ids = new ArrayList<>();
            for (int i = 0; i < count; i++) {
                ids.add(from + i);
            }
            return ids;
        }

        @Test
        @DisplayName("UTC-245 Lần chạy đầu hỏi 20 bài chờ quét đã gửi hơn 1 phút, bắt đầu từ bài cũ nhất")
        void utc245_firstRunAsksTwentyOldestPostsSentOverAMinuteAgo() {
            service.retryPending();

            verify(repository).processingBlogIds(NOW.minus(Duration.ofMinutes(1)), 0L, 20);
        }

        @Test
        @DisplayName("UTC-246 Mỗi bài nhận được đều được quét lại")
        void utc246_eachPostIsProcessed() {
            when(repository.processingBlogIds(any(), anyLong(), anyInt())).thenReturn(List.of(501L, 502L));

            service.retryPending();

            verify(repository).pendingBlog(501L);
            verify(repository).pendingBlog(502L);
        }

        @Test
        @DisplayName("UTC-247 Trang đủ 20 bài: lần chạy sau đi tiếp sau bài cuối cùng của trang")
        void utc247_fullPage_nextRunContinuesAfterLastId() {
            when(repository.processingBlogIds(any(), eq(0L), anyInt())).thenReturn(ids(601, 20));

            service.retryPending();
            service.retryPending();

            verify(repository).processingBlogIds(NOW.minus(Duration.ofMinutes(1)), 620L, 20);
        }

        @Test
        @DisplayName("UTC-248 Trang 19 bài (chưa đủ): lần chạy sau bắt đầu lại từ bài cũ nhất")
        void utc248_shortPage_nextRunStartsFromTheOldest() {
            when(repository.processingBlogIds(any(), anyLong(), anyInt())).thenReturn(ids(601, 19));

            service.retryPending();
            service.retryPending();

            verify(repository, times(2)).processingBlogIds(NOW.minus(Duration.ofMinutes(1)), 0L, 20);
        }

        @Test
        @DisplayName("UTC-249 Không có bài nào chờ: không quét gì, lần sau vẫn bắt đầu từ bài cũ nhất")
        void utc249_nothingWaiting() {
            service.retryPending();
            service.retryPending();

            verify(repository, never()).pendingBlog(anyLong());
            verify(repository, times(2)).processingBlogIds(NOW.minus(Duration.ofMinutes(1)), 0L, 20);
        }

        @Test
        @DisplayName("UTC-250 Một bài lỗi không chặn các bài sau; ghi cảnh báo \"Scan of post 501 failed\"")
        void utc250_oneFailingPostDoesNotStopTheOthers() {
            when(repository.processingBlogIds(any(), anyLong(), anyInt())).thenReturn(List.of(501L, 502L));
            when(repository.pendingBlog(501L)).thenThrow(new IllegalStateException("DB down"));
            Logger logger = (Logger) LoggerFactory.getLogger(BlogPublishService.class);
            ListAppender<ILoggingEvent> appender = new ListAppender<>();
            appender.start();
            logger.addAppender(appender);
            try {
                service.retryPending();
            } finally {
                logger.detachAppender(appender);
            }

            verify(repository).pendingBlog(502L);
            assertThat(appender.list).hasSize(1);
            assertThat(appender.list.get(0).getLevel()).isEqualTo(Level.WARN);
            assertThat(appender.list.get(0).getFormattedMessage()).isEqualTo("Scan of post 501 failed: DB down");
        }
    }

    // =================================================================================================================
    @Nested
    @TestMethodOrder(MethodOrderer.DisplayName.class)
    @DisplayName("03. awardOnce – UC 1.5 Tạo bài viết: +10 điểm chỉ một lần (UTC-251 → UTC-253)")
    class AwardOnce {

        @Test
        @DisplayName("UTC-251 Bài chưa từng được cộng: +10 điểm lý do BLOG_CREATED, trả về true")
        void utc251_notPaidYet_pointsAdded() {
            assertThat(service.awardOnce(AUTHOR_ID, BLOG_ID)).isTrue();

            verify(points).add(AUTHOR_ID, 10, "BLOG_CREATED", "BLOG", BLOG_ID);
        }

        @Test
        @DisplayName("UTC-252 Bài đã được cộng trước đó: không cộng nữa, trả về false")
        void utc252_alreadyPaid_nothingAdded() {
            when(repository.postAwarded(BLOG_ID)).thenReturn(true);

            assertThat(service.awardOnce(AUTHOR_ID, BLOG_ID)).isFalse();

            verify(points, never()).add(anyLong(), anyInt(), anyString(), anyString(), any());
        }

        @Test
        @DisplayName("UTC-253 \"Đã cộng chưa\" đếm dòng điểm BLOG_CREATED của chính bài đó (1 → có; 0 hoặc không có → chưa)")
        void utc253_postAwardedCountsBlogCreatedLines() {
            JdbcTemplate jdbc = mock(JdbcTemplate.class);
            BlogWriteRepository real = new BlogWriteRepository(jdbc, mock(NamedParameterJdbcTemplate.class));
            when(jdbc.queryForObject(contains("reason = 'BLOG_CREATED'"), eq(Integer.class), eq(BLOG_ID)))
                    .thenReturn(1, 0, null);

            assertThat(real.postAwarded(BLOG_ID)).isTrue();
            assertThat(real.postAwarded(BLOG_ID)).isFalse();
            assertThat(real.postAwarded(BLOG_ID)).isFalse();
            verify(jdbc, times(3)).queryForObject(contains("ref_type = 'BLOG' AND ref_id = ?"), eq(Integer.class),
                    eq(BLOG_ID));
        }
    }
}
