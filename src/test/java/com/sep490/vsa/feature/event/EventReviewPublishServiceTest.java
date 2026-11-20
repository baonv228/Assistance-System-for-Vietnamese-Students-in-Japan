package com.sep490.vsa.feature.event;

import com.sep490.vsa.feature.event.EventRepository.EventRow;
import com.sep490.vsa.feature.event.EventRepository.MediaState;
import com.sep490.vsa.feature.event.EventRepository.ReviewState;
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
import org.springframework.transaction.PlatformTransactionManager;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit tests of the second half of writing an event review with photos or a video ({@link EventReviewPublishService}),
 * written from the use case specification (docs/use-cases/dac-ta-uc-hoc-tieng-nhat-va-su-kien.md: UC 3 AF2 "Có
 * ảnh/video") and the business rules (docs/use-cases/business-rules-vi.md: GBR-22, GBR-25, GBR-26, GBR-28).
 * <p>
 * The database and the outside scanners are not used: {@link EventRepository}, {@link MediaScanService} and the other
 * services are mocks.
 */
@TestClassOrder(ClassOrderer.DisplayName.class)
@DisplayName("EventReviewPublishService – Quét ảnh/video của review sự kiện (UC 3 AF2)")
class EventReviewPublishServiceTest {

    private static final long AUTHOR_ID = 10L;
    private static final long EVENT_ID = 900L;
    private static final long REVIEW_ID = 31L;
    private static final Instant NOW = Instant.parse("2026-11-20T03:00:00Z");
    private static final String TITLE = "Lễ hội pháo hoa Sumida";
    private static final String CONTENT = "Pháo hoa rất đẹp, nên đi sớm để có chỗ.";

    private EventRepository repository;
    private MediaScanService scanner;
    private MediaService media;
    private ContentFilter filter;
    private PointService points;
    private NotificationService notifications;
    private EventReviewPublishService service;

    @BeforeEach
    void setUp() {
        repository = mock(EventRepository.class);
        scanner = mock(MediaScanService.class);
        media = mock(MediaService.class);
        filter = mock(ContentFilter.class);
        points = mock(PointService.class);
        notifications = mock(NotificationService.class);
        service = new EventReviewPublishService(repository, scanner, media, filter, points, notifications,
                mock(PlatformTransactionManager.class), Clock.fixed(NOW, ZoneOffset.UTC));

        review("PROCESSING");
        when(repository.event(EVENT_ID)).thenReturn(Optional.of(new EventRow(EVENT_ID, TITLE,
                LocalDate.of(2026, 11, 19), LocalDate.of(2026, 11, 21), "Sông Sumida", null, null, "Miễn phí", null,
                null, null, "Moderator A")));
        when(filter.bannedKeyword(anyString())).thenReturn(Optional.empty());
        when(repository.setStatus(eq(REVIEW_ID), anyString(), anyString())).thenReturn(true);
    }

    // ---------------------------------------------------------------- helpers

    private void review(String status) {
        when(repository.review(eq(REVIEW_ID), anyBoolean())).thenReturn(Optional.of(new ReviewState(REVIEW_ID,
                EVENT_ID, AUTHOR_ID, 2026, status, CONTENT)));
    }

    private static MediaState file(long id, String scanStatus) {
        return new MediaState(id, scanStatus);
    }

    private void verifyNotDecided() {
        verify(repository, never()).setStatus(anyLong(), anyString(), anyString());
        verifyNoInteractions(notifications, points);
    }

    // =================================================================================================================
    @Nested
    @TestMethodOrder(MethodOrderer.DisplayName.class)
    @DisplayName("01. process – kết quả quét (UTC-173 → UTC-183)")
    class Process {

        @Test
        @DisplayName("UTC-173 Mọi tệp đạt: review được đăng (PUBLISHED)")
        void utc173_allPassed_published() {
            when(repository.mediaStates(REVIEW_ID)).thenReturn(
                    List.of(file(1, "PROCESSING"), file(2, "PROCESSING")),
                    List.of(file(1, "PASSED"), file(2, "PASSED")));

            service.process(REVIEW_ID);

            verify(scanner).scan(1L);
            verify(scanner).scan(2L);
            verify(repository).setStatus(REVIEW_ID, "PROCESSING", "PUBLISHED");
        }

        @Test
        @DisplayName("UTC-174 GBR-22 Lần đăng đầu: +10 điểm và báo cho tác giả \"… đã được đăng. +10 điểm.\"")
        void utc174_firstPublication_pointsAndNotification() {
            when(repository.mediaStates(REVIEW_ID)).thenReturn(List.of(file(1, "PASSED")));

            service.process(REVIEW_ID);

            verify(points).add(AUTHOR_ID, PointService.REVIEW, "REVIEW_CREATED", "REVIEW", REVIEW_ID);
            verify(notifications).send(AUTHOR_ID, NotificationService.CONTENT_PUBLISHED,
                    "Review “" + TITLE + "” đã qua kiểm duyệt ảnh/video và đã được đăng. +10 điểm.", "REVIEW",
                    REVIEW_ID);
        }

        @Test
        @DisplayName("UTC-175 Bản sửa của review đã đăng trước đó: không cộng điểm lần nữa")
        void utc175_editRepublished_noPointsAgain() {
            when(repository.mediaStates(REVIEW_ID)).thenReturn(List.of(file(1, "PASSED")));
            when(repository.reviewAwarded(REVIEW_ID)).thenReturn(true);

            service.process(REVIEW_ID);

            verify(points, never()).add(anyLong(), anyInt(), anyString(), anyString(), any());
            verify(notifications).send(AUTHOR_ID, NotificationService.CONTENT_PUBLISHED,
                    "Bản sửa review “" + TITLE + "” đã qua kiểm duyệt ảnh/video và đã được đăng.", "REVIEW",
                    REVIEW_ID);
        }

        @Test
        @DisplayName("UTC-176 Tệp đã đạt trước đó không bị quét lại")
        void utc176_passedFileNotScannedAgain() {
            when(repository.mediaStates(REVIEW_ID)).thenReturn(List.of(file(1, "PASSED"), file(2, "PROCESSING")),
                    List.of(file(1, "PASSED"), file(2, "PASSED")));

            service.process(REVIEW_ID);

            verify(scanner, never()).scan(1L);
            verify(scanner).scan(2L);
        }

        @Test
        @DisplayName("UTC-177 GBR-28 Có tệp vi phạm: từ chối review, xóa tệp vi phạm, báo lý do cho tác giả")
        void utc177_rejectedFile_reviewRejected() {
            when(repository.mediaStates(REVIEW_ID)).thenReturn(List.of(file(1, "PASSED"), file(2, "REJECTED")));
            when(scanner.rejectionReason(List.of(2L))).thenReturn(Optional.of("ảnh có nội dung khiêu dâm"));

            service.process(REVIEW_ID);

            verify(repository).setStatus(REVIEW_ID, "PROCESSING", "REJECTED");
            verify(media).discard(List.of(2L));
            verify(notifications).send(AUTHOR_ID, NotificationService.CONTENT_REJECTED,
                    "Review “" + TITLE + "” chưa được đăng vì ảnh có nội dung khiêu dâm. Bạn có thể sửa review rồi "
                            + "gửi lại.", "REVIEW", REVIEW_ID);
            verifyNoInteractions(points);
        }

        @Test
        @DisplayName("UTC-178 Không rõ lý do: \"ảnh hoặc video vi phạm Quy tắc cộng đồng\"")
        void utc178_rejectedWithoutReason_defaultReason() {
            when(repository.mediaStates(REVIEW_ID)).thenReturn(List.of(file(2, "REJECTED")));
            when(scanner.rejectionReason(anyCollection())).thenReturn(Optional.empty());

            service.process(REVIEW_ID);

            verify(notifications).send(AUTHOR_ID, NotificationService.CONTENT_REJECTED,
                    "Review “" + TITLE + "” chưa được đăng vì ảnh hoặc video vi phạm Quy tắc cộng đồng. Bạn có thể "
                            + "sửa review rồi gửi lại.", "REVIEW", REVIEW_ID);
        }

        @Test
        @DisplayName("UTC-179 GBR-26 Từ khóa cấm mới thêm trước khi quét: từ chối, không gửi tệp đi quét")
        void utc179_bannedKeywordBeforeScan_rejectedWithoutScanning() {
            when(filter.bannedKeyword(CONTENT)).thenReturn(Optional.of("fb88"));
            when(repository.mediaStates(REVIEW_ID)).thenReturn(List.of(file(1, "PROCESSING")));

            service.process(REVIEW_ID);

            verifyNoInteractions(scanner);
            verify(repository).setStatus(REVIEW_ID, "PROCESSING", "REJECTED");
            verify(notifications).send(AUTHOR_ID, NotificationService.CONTENT_REJECTED,
                    "Review “" + TITLE + "” chưa được đăng vì có từ khóa không được phép “fb88”. Bạn có thể sửa "
                            + "review rồi gửi lại.", "REVIEW", REVIEW_ID);
        }

        @Test
        @DisplayName("UTC-180 GBR-26 Từ khóa cấm thêm trong lúc quét: kiểm tra lần cuối từ chối, không đăng")
        void utc180_bannedKeywordAddedDuringScan_rejected() {
            when(filter.bannedKeyword(CONTENT)).thenReturn(Optional.empty(), Optional.of("cá cược"));
            when(repository.mediaStates(REVIEW_ID)).thenReturn(List.of(file(1, "PROCESSING")),
                    List.of(file(1, "PASSED")));

            service.process(REVIEW_ID);

            verify(scanner).scan(1L);
            verify(repository).setStatus(REVIEW_ID, "PROCESSING", "REJECTED");
            verify(repository, never()).setStatus(REVIEW_ID, "PROCESSING", "PUBLISHED");
            verifyNoInteractions(points);
        }

        @Test
        @DisplayName("UTC-181 Dịch vụ quét lỗi (tệp vẫn chờ): review vẫn chờ, không báo, không cộng điểm")
        void utc181_scannerFailed_staysProcessing() {
            when(repository.mediaStates(REVIEW_ID)).thenReturn(List.of(file(1, "PROCESSING")));

            service.process(REVIEW_ID);

            verify(scanner).scan(1L);
            verifyNotDecided();
        }

        @ParameterizedTest(name = "[{index}] trạng thái {0}")
        @ValueSource(strings = {"PUBLISHED", "REJECTED", "HIDDEN"})
        @DisplayName("UTC-182 Review không còn chờ quét: không làm gì")
        void utc182_notProcessing_nothingDone(String status) {
            review(status);

            service.process(REVIEW_ID);

            verifyNoInteractions(scanner);
            verifyNotDecided();
        }

        @Test
        @DisplayName("UTC-183 Review đã bị xóa hoặc vừa được xử lý ở luồng khác: không báo, không cộng điểm")
        void utc183_goneOrHandledMeanwhile_nothingDone() {
            when(repository.review(eq(REVIEW_ID), anyBoolean())).thenReturn(Optional.empty());
            service.process(REVIEW_ID);
            verifyNotDecided();

            review("PROCESSING");
            when(repository.mediaStates(REVIEW_ID)).thenReturn(List.of(file(1, "PASSED")));
            when(repository.setStatus(REVIEW_ID, "PROCESSING", "PUBLISHED")).thenReturn(false);
            service.process(REVIEW_ID);
            verifyNoInteractions(notifications, points);
        }
    }

    // =================================================================================================================
    @Nested
    @TestMethodOrder(MethodOrderer.DisplayName.class)
    @DisplayName("02. awardOnce, retryPending (UTC-184 → UTC-187)")
    class AwardAndRetry {

        @Test
        @DisplayName("UTC-184 GBR-22 Chưa được cộng: cộng +10 điểm, lý do REVIEW_CREATED")
        void utc184_notAwarded_pays() {
            assertThat(service.awardOnce(AUTHOR_ID, REVIEW_ID)).isTrue();

            verify(points).add(AUTHOR_ID, 10, "REVIEW_CREATED", "REVIEW", REVIEW_ID);
        }

        @Test
        @DisplayName("UTC-185 Đã được cộng trước đó: không cộng lần hai")
        void utc185_alreadyAwarded_noSecondPayment() {
            when(repository.reviewAwarded(REVIEW_ID)).thenReturn(true);

            assertThat(service.awardOnce(AUTHOR_ID, REVIEW_ID)).isFalse();

            verifyNoInteractions(points);
        }

        @Test
        @DisplayName("UTC-186 AF2 Quét lại sau: lấy tối đa 20 review chờ quá 1 phút và xử lý từng review")
        void utc186_retryPending_processesWaitingReviews() {
            when(repository.processingReviewIds(NOW.minusSeconds(60), 0L, 20)).thenReturn(List.of(REVIEW_ID));
            when(repository.mediaStates(REVIEW_ID)).thenReturn(List.of(file(1, "PASSED")));

            service.retryPending();

            verify(repository).processingReviewIds(NOW.minusSeconds(60), 0L, 20);
            verify(repository).setStatus(REVIEW_ID, "PROCESSING", "PUBLISHED");
        }

        @Test
        @DisplayName("UTC-187 Sự kiện đã bị xóa: thông báo ghi \"sự kiện\" thay cho tên")
        void utc187_deletedEvent_titleFallback() {
            when(repository.event(EVENT_ID)).thenReturn(Optional.empty());
            when(repository.mediaStates(REVIEW_ID)).thenReturn(List.of(file(1, "PASSED")));
            when(repository.reviewAwarded(REVIEW_ID)).thenReturn(true);

            service.process(REVIEW_ID);

            verify(notifications).send(AUTHOR_ID, NotificationService.CONTENT_PUBLISHED,
                    "Bản sửa review “sự kiện” đã qua kiểm duyệt ảnh/video và đã được đăng.", "REVIEW", REVIEW_ID);
        }
    }
}
