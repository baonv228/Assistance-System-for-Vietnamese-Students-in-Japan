package com.sep490.vsa.feature.event;

import com.sep490.vsa.common.exception.ApiException;
import com.sep490.vsa.common.security.AppUserPrincipal;
import com.sep490.vsa.feature.admin.ActivityLogService;
import com.sep490.vsa.feature.event.EventDtos.AlbumPhoto;
import com.sep490.vsa.feature.event.EventDtos.EventCard;
import com.sep490.vsa.feature.event.EventDtos.EventDetail;
import com.sep490.vsa.feature.event.EventDtos.EventRequest;
import com.sep490.vsa.feature.event.EventDtos.ModEvent;
import com.sep490.vsa.feature.event.EventDtos.ModSaved;
import com.sep490.vsa.feature.event.EventDtos.PlacePick;
import com.sep490.vsa.feature.event.EventDtos.Reason;
import com.sep490.vsa.feature.event.EventDtos.ReviewItem;
import com.sep490.vsa.feature.event.EventRepository.EventRow;
import com.sep490.vsa.feature.event.EventRepository.OwnRow;
import com.sep490.vsa.feature.event.EventRepository.RankRow;
import com.sep490.vsa.feature.event.EventRepository.ReviewRow;
import com.sep490.vsa.feature.event.EventRules.CleanEvent;
import com.sep490.vsa.feature.media.InvalidMediaException;
import com.sep490.vsa.feature.media.MediaService;
import com.sep490.vsa.feature.media.MediaService.Prepared;
import com.sep490.vsa.feature.report.ContentReportService;
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
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.transaction.PlatformTransactionManager;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit tests of "Lễ hội và sự kiện" for everyone and for the Moderator ({@link EventService}), written from the full use
 * case specification (docs/use-cases/dac-ta-uc-hoc-tieng-nhat-va-su-kien.md: UC 1 "Duyệt lễ hội &amp; sự kiện", UC 2 "Xem
 * chi tiết lễ hội &amp; sự kiện", UC 4 "Tạo lễ hội / sự kiện", UC 5 "Cập nhật lễ hội / sự kiện", UC 6 "Xóa lễ hội / sự
 * kiện") and the business rules (docs/use-cases/business-rules-vi.md: GBR-17, GBR-36, GBR-39, GBR-41, GBR-48).
 * <p>
 * The database is not used: {@link EventRepository}, {@link ContentReportService}, {@link MediaService} and
 * {@link ActivityLogService} are mocks; the clock is fixed at 20/11/2026 12:00 Japan time.
 */
@TestClassOrder(ClassOrderer.DisplayName.class)
@DisplayName("EventService – Lễ hội và sự kiện: danh sách, chi tiết, Moderator (UC 1, 2, 4, 5, 6)")
class EventServiceTest {

    private static final long MOD_ID = 20L;
    private static final long MEMBER_ID = 10L;
    private static final long EVENT_ID = 900L;
    /** 12:00 in Japan: the Japan date is 20/11/2026. */
    private static final Instant NOW = Instant.parse("2026-11-20T03:00:00Z");
    private static final LocalDate TODAY = LocalDate.of(2026, 11, 20);

    private static final BigDecimal LAT = new BigDecimal("35.7148");
    private static final BigDecimal LNG = new BigDecimal("139.7967");

    private static final AppUserPrincipal MEMBER = principal(MEMBER_ID, "USER");
    private static final AppUserPrincipal MODERATOR = principal(MOD_ID, "MODERATOR");

    private EventRepository repository;
    private ContentReportService reports;
    private MediaService media;
    private ActivityLogService activityLog;
    private PlatformTransactionManager txManager;
    private EventService service;

    @BeforeEach
    void setUp() {
        repository = mock(EventRepository.class);
        reports = mock(ContentReportService.class);
        media = mock(MediaService.class);
        activityLog = mock(ActivityLogService.class);
        txManager = mock(PlatformTransactionManager.class);
        service = serviceAt(NOW);
        when(repository.insertEvent(any(), anyLong(), any())).thenReturn(EVENT_ID);
        when(repository.event(EVENT_ID)).thenReturn(Optional.of(row(EVENT_ID, TODAY.plusDays(5), TODAY.plusDays(6),
                LAT, LNG)));
        when(repository.lockEvent(EVENT_ID)).thenReturn(Optional.of(row(EVENT_ID, TODAY.plusDays(5),
                TODAY.plusDays(6), LAT, LNG)));
        when(reports.reasons()).thenReturn(List.of(new ContentReportService.Reason(1, "Spam"),
                new ContentReportService.Reason(2, "Lừa đảo")));
    }

    // ---------------------------------------------------------------- helpers

    private EventService serviceAt(Instant instant) {
        return new EventService(repository, reports, media, activityLog, txManager,
                Clock.fixed(instant, ZoneOffset.UTC));
    }

    private static AppUserPrincipal principal(long id, String role) {
        return new AppUserPrincipal(id, "user" + id + "@example.org", "User " + id, role, "/");
    }

    private static EventRow row(long id, LocalDate start, LocalDate end, BigDecimal lat, BigDecimal lng) {
        return new EventRow(id, "Lễ hội " + id, start, end, lat == null ? "Chưa gắn vị trí" : "Công viên Ueno", lat,
                lng, "Miễn phí", "Nên đi sớm.", null, null, "Moderator A");
    }

    private static EventRow rowWithCover(long id, long coverMediaId) {
        return new EventRow(id, "Lễ hội " + id, TODAY.plusDays(5), TODAY.plusDays(6), "Công viên Ueno", LAT, LNG,
                "Miễn phí", "Nên đi sớm.", coverMediaId, "event/2026/11/old.jpg", "Moderator A");
    }

    /** A valid "Đăng sự kiện" form. */
    private static EventRequest request() {
        return request("Lễ hội pháo hoa Sumida", TODAY.plusDays(5), TODAY.plusDays(6), "Sông Sumida", LAT, LNG,
                "Miễn phí", "Đi sớm để có chỗ ngồi.");
    }

    private static EventRequest request(String title, LocalDate start, LocalDate end, String place, BigDecimal lat,
                                        BigDecimal lng, String cost, String tip) {
        return new EventRequest(title, start, end, place, lat, lng, cost, tip, null);
    }

    private static EventRequest withRemoveCover(EventRequest r) {
        return new EventRequest(r.title(), r.startDate(), r.endDate(), r.locationName(), r.lat(), r.lng(),
                r.costNote(), r.tip(), true);
    }

    private static MockMultipartFile coverFile() {
        return new MockMultipartFile("cover", "cover.jpg", "image/jpeg", new byte[] {1, 2, 3});
    }

    private static Prepared preparedImage() {
        return new Prepared("IMAGE", new byte[] {1}, "image/jpeg", "jpg", 1600, 900, null, List.of());
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

    /** 400 with {@code message} as the summary and under {@code field}. */
    private static void assertInvalid(Runnable call, String field, String message) {
        ApiException e = apiError(call);
        assertThat(e.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(e.getMessage()).isEqualTo(message);
        assertThat(e.getFields()).containsEntry(field, message);
    }

    private CleanEvent insertedEvent() {
        ArgumentCaptor<CleanEvent> saved = ArgumentCaptor.forClass(CleanEvent.class);
        verify(repository).insertEvent(saved.capture(), eq(MOD_ID), eq(NOW));
        return saved.getValue();
    }

    private void verifyNothingSaved() {
        verify(repository, never()).insertEvent(any(), anyLong(), any());
        verify(repository, never()).updateEvent(anyLong(), any(), any());
        verify(repository, never()).setCover(anyLong(), any());
        verify(activityLog, never()).recordLog(any(), anyString(), anyString(), any(), any(), any(), any());
    }

    private static String repeat(int n) {
        return "a".repeat(n);
    }

    // =================================================================================================================
    @Nested
    @TestMethodOrder(MethodOrderer.DisplayName.class)
    @DisplayName("01. list – UC 1 Duyệt lễ hội & sự kiện (UTC-001 → UTC-014)")
    class BrowseEvents {

        @Test
        @DisplayName("UTC-001 Sự kiện bắt đầu hôm nay: \"Đang diễn ra\", viết review được")
        void utc001_eventStartsToday_isNowAndReviewable() {
            when(repository.events(null)).thenReturn(List.of(row(1, TODAY, TODAY.plusDays(2), LAT, LNG)));

            EventCard card = service.list(null).get(0);

            assertThat(card.state()).isEqualTo("NOW");
            assertThat(card.daysUntil()).isZero();
            assertThat(card.canReview()).isTrue();
        }

        @Test
        @DisplayName("UTC-002 Sự kiện kết thúc hôm nay vẫn \"Đang diễn ra\"")
        void utc002_eventEndsToday_isStillNow() {
            when(repository.events(null)).thenReturn(List.of(row(1, TODAY.minusDays(3), TODAY, LAT, LNG)));

            assertThat(service.list(null).get(0).state()).isEqualTo("NOW");
        }

        @Test
        @DisplayName("UTC-003 Sự kiện bắt đầu ngày mai: sắp diễn ra, còn 1 ngày, chưa viết review được")
        void utc003_eventStartsTomorrow_isUpcomingNotReviewable() {
            when(repository.events(null)).thenReturn(List.of(row(1, TODAY.plusDays(1), TODAY.plusDays(2), LAT, LNG)));

            EventCard card = service.list(null).get(0);

            assertThat(card.state()).isEqualTo("UPCOMING");
            assertThat(card.daysUntil()).isEqualTo(1);
            assertThat(card.canReview()).isFalse();
        }

        @Test
        @DisplayName("UTC-004 Sự kiện bắt đầu sau 30 ngày (nhóm \"Trong 30 ngày tới\") và sau 31 ngày (\"Sau này\")")
        void utc004_daysUntilSplitsNext30DaysFromLater() {
            when(repository.events(null)).thenReturn(List.of(
                    row(1, TODAY.plusDays(30), TODAY.plusDays(31), LAT, LNG),
                    row(2, TODAY.plusDays(31), TODAY.plusDays(32), LAT, LNG)));

            List<EventCard> cards = service.list(null);

            assertThat(cards).extracting(EventCard::daysUntil).containsExactly(30L, 31L);
            assertThat(cards).extracting(EventCard::state).containsOnly("UPCOMING");
        }

        @Test
        @DisplayName("UTC-005 Sự kiện kết thúc hôm qua: \"Đã diễn ra\", vẫn viết review được")
        void utc005_eventEndedYesterday_isPastButReviewable() {
            when(repository.events(null)).thenReturn(List.of(row(1, TODAY.minusDays(5), TODAY.minusDays(1), LAT, LNG)));

            EventCard card = service.list(null).get(0);

            assertThat(card.state()).isEqualTo("PAST");
            assertThat(card.daysUntil()).isZero();
            assertThat(card.canReview()).isTrue();
        }

        @Test
        @DisplayName("UTC-006 Ngày tính theo giờ Nhật: 23:30 giờ Việt Nam 19/11 đã là 20/11 ở Nhật")
        void utc006_daysAreJapanDays() {
            // 19/11/2026 16:30 UTC = 19/11 23:30 ở Việt Nam = 20/11 01:30 ở Nhật
            EventService late = serviceAt(Instant.parse("2026-11-19T16:30:00Z"));
            when(repository.events(null)).thenReturn(List.of(row(1, TODAY, TODAY, LAT, LNG)));

            assertThat(late.list(null).get(0).state()).isEqualTo("NOW");
        }

        @Test
        @DisplayName("UTC-007 Giữ thứ tự sự kiện bắt đầu sớm hơn trước như kho dữ liệu trả về")
        void utc007_listKeepsSoonestFirstOrder() {
            when(repository.events(null)).thenReturn(List.of(
                    row(3, TODAY.minusDays(10), TODAY.minusDays(9), LAT, LNG),
                    row(1, TODAY, TODAY, LAT, LNG),
                    row(2, TODAY.plusDays(40), TODAY.plusDays(41), null, null)));

            assertThat(service.list(null)).extracting(EventCard::id).containsExactly(3L, 1L, 2L);
        }

        @Test
        @DisplayName("UTC-008 Thẻ có điểm trung bình (làm tròn 1 chữ số) và số review")
        void utc008_cardShowsRoundedAverageAndCount() {
            when(repository.events(null)).thenReturn(List.of(row(1, TODAY, TODAY, LAT, LNG)));
            when(repository.reviewStats()).thenReturn(Map.of(1L, new double[] {4.25, 4}));

            EventCard card = service.list(null).get(0);

            assertThat(card.avgRating()).isEqualTo(4.3);
            assertThat(card.reviewCount()).isEqualTo(4);
        }

        @Test
        @DisplayName("UTC-009 Chưa có review: không có điểm, số review = 0 (\"Chưa có review\")")
        void utc009_noReview_noAverageAndZeroCount() {
            when(repository.events(null)).thenReturn(List.of(row(1, TODAY, TODAY, LAT, LNG)));

            EventCard card = service.list(null).get(0);

            assertThat(card.avgRating()).isNull();
            assertThat(card.reviewCount()).isZero();
        }

        @Test
        @DisplayName("UTC-010 Có ảnh bìa thì trả đường dẫn ảnh; không có thì null (trang vẽ hình minh họa)")
        void utc010_coverUrlOrNull() {
            EventRow withCover = rowWithCover(1, 77);
            EventRow without = row(2, TODAY, TODAY, LAT, LNG);
            when(repository.events(null)).thenReturn(List.of(withCover, without));
            when(repository.coverUrl(withCover)).thenReturn("/media/event/2026/11/old.jpg");

            List<EventCard> cards = service.list(null);

            assertThat(cards.get(0).coverUrl()).isEqualTo("/media/event/2026/11/old.jpg");
            assertThat(cards.get(1).coverUrl()).isNull();
        }

        @Test
        @DisplayName("UTC-011 AF1 Tìm kiếm: từ khóa được bỏ khoảng trắng đầu cuối rồi gửi xuống kho")
        void utc011_searchKeywordIsTrimmed() {
            service.list("  hanabi  ");

            verify(repository).events("hanabi");
        }

        @Test
        @DisplayName("UTC-012 AF1 Tìm kiếm: ô tìm chỉ có khoảng trắng = không lọc")
        void utc012_blankSearch_listsEverything() {
            service.list("   ");

            verify(repository).events(isNull());
        }

        @Test
        @DisplayName("UTC-013 AF1 Tìm kiếm: từ khóa dài hơn 100 ký tự bị cắt còn 100")
        void utc013_longSearch_cutTo100() {
            service.list("x".repeat(150));

            verify(repository).events("x".repeat(100));
        }

        @Test
        @DisplayName("UTC-014 AF2 Chưa có sự kiện: danh sách rỗng, không thay đổi dữ liệu")
        void utc014_noEvent_emptyListAndNoWrite() {
            assertThat(service.list(null)).isEmpty();

            verify(repository, never()).insertEvent(any(), anyLong(), any());
            verify(repository, never()).softDelete(anyLong(), any());
            verifyNoInteractions(activityLog);
        }
    }

    // =================================================================================================================
    @Nested
    @TestMethodOrder(MethodOrderer.DisplayName.class)
    @DisplayName("02. pins – ghim sự kiện trên Bản đồ (UC 1, UC 4 bước 6) (UTC-015 → UTC-019)")
    class MapPins {

        @BeforeEach
        void events() {
            when(repository.events(null)).thenReturn(List.of(
                    row(1, TODAY.minusDays(1), TODAY.plusDays(1), LAT, LNG),      // đang diễn ra
                    row(2, TODAY.plusDays(10), TODAY.plusDays(10), LAT, LNG),     // còn 10 ngày
                    row(3, TODAY.plusDays(20), TODAY.plusDays(20), LAT, LNG),     // còn 20 ngày
                    row(4, TODAY.plusDays(45), TODAY.plusDays(45), LAT, LNG),     // còn 45 ngày
                    row(5, TODAY.minusDays(9), TODAY.minusDays(8), LAT, LNG),     // đã qua
                    row(6, TODAY, TODAY, null, null)));                          // chưa gắn vị trí
        }

        @Test
        @DisplayName("UTC-015 \"15 ngày tới\": sự kiện đang diễn ra và bắt đầu trong 15 ngày")
        void utc015_window15() {
            assertThat(service.pins(15)).extracting(EventCard::id).containsExactly(1L, 2L);
        }

        @Test
        @DisplayName("UTC-016 \"30 ngày tới\": thêm sự kiện còn 20 ngày, không có sự kiện còn 45 ngày")
        void utc016_window30() {
            assertThat(service.pins(30)).extracting(EventCard::id).containsExactly(1L, 2L, 3L);
        }

        @Test
        @DisplayName("UTC-017 Cửa sổ lớn hơn 30 ngày bị giới hạn ở 30")
        void utc017_windowCappedAt30() {
            assertThat(service.pins(90)).extracting(EventCard::id).containsExactly(1L, 2L, 3L);
        }

        @ParameterizedTest(name = "[{index}] days = {0}")
        @ValueSource(ints = {0, -1})
        @DisplayName("UTC-018 \"Tắt\": không có ghim nào")
        void utc018_off_noPins(int days) {
            assertThat(service.pins(days)).isEmpty();
            verify(repository, never()).events(any());
        }

        @Test
        @DisplayName("UTC-019 Sự kiện đã qua hoặc chưa gắn vị trí không có ghim")
        void utc019_pastOrUnlocated_noPin() {
            assertThat(service.pins(30)).extracting(EventCard::id).doesNotContain(5L, 6L);
        }
    }

    // =================================================================================================================
    @Nested
    @TestMethodOrder(MethodOrderer.DisplayName.class)
    @DisplayName("03. detail – UC 2 Xem chi tiết lễ hội & sự kiện (UTC-020 → UTC-034)")
    class EventDetails {

        private static final long REVIEW_MINE = 31L;
        private static final long REVIEW_OTHER = 32L;

        @BeforeEach
        void event() {
            when(repository.event(EVENT_ID)).thenReturn(Optional.of(row(EVENT_ID, TODAY.minusDays(1),
                    TODAY.plusDays(1), LAT, LNG)));
            when(repository.ranks()).thenReturn(List.of(new RankRow("Senpai tin cậy", 300),
                    new RankRow("Người chỉ đường", 100), new RankRow("Người mới", 0)));
        }

        private ReviewRow review(long id, long authorId, int points) {
            return new ReviewRow(id, authorId, "User " + authorId, null, points, 5, "Rất đông vui, pháo hoa đẹp.",
                    2026, "PUBLISHED", false, NOW, 3);
        }

        @Test
        @DisplayName("UTC-020 AF8 Sự kiện không tồn tại hoặc đã bị xóa → 404")
        void utc020_missingEvent_notFound() {
            when(repository.event(EVENT_ID)).thenReturn(Optional.empty());

            assertApiError(() -> service.detail(EVENT_ID, null), HttpStatus.NOT_FOUND,
                    "Không tìm thấy sự kiện. Sự kiện có thể đã bị xóa.");
        }

        @Test
        @DisplayName("UTC-021 Hiển thị thông tin sự kiện, điểm trung bình gộp mọi năm và \"Mẹo từ Moderator\"")
        void utc021_showsEventStatsAndTip() {
            when(repository.reviewStats()).thenReturn(Map.of(EVENT_ID, new double[] {3.96, 25}));

            EventDetail detail = service.detail(EVENT_ID, null);

            assertThat(detail.event().title()).isEqualTo("Lễ hội " + EVENT_ID);
            assertThat(detail.event().avgRating()).isEqualTo(4.0);
            assertThat(detail.event().reviewCount()).isEqualTo(25);
            assertThat(detail.tip()).isEqualTo("Nên đi sớm.");
        }

        @Test
        @DisplayName("UTC-022 Album: tối đa 12 ảnh, kèm tổng số ảnh")
        void utc022_albumCappedAt12WithTotal() {
            List<AlbumPhoto> photos = new ArrayList<>();
            for (int i = 0; i < 15; i++) {
                photos.add(new AlbumPhoto("/media/review/" + i + ".jpg", "User " + i, 2026, 800, 600));
            }
            when(repository.album(EVENT_ID)).thenReturn(photos);

            EventDetail detail = service.detail(EVENT_ID, null);

            assertThat(detail.album()).hasSize(EventService.ALBUM_MAX).hasSize(12);
            assertThat(detail.album().get(0).url()).isEqualTo("/media/review/0.jpg");
            assertThat(detail.albumCount()).isEqualTo(15);
        }

        @Test
        @DisplayName("UTC-023 Album ít hơn 12 ảnh: hiện đủ")
        void utc023_smallAlbum_allShown() {
            when(repository.album(EVENT_ID)).thenReturn(List.of(new AlbumPhoto("/media/a.jpg", "Lan", 2025, 1, 1)));

            EventDetail detail = service.detail(EVENT_ID, null);

            assertThat(detail.album()).hasSize(1);
            assertThat(detail.albumCount()).isEqualTo(1);
            assertThat(detail.album().get(0).authorName()).isEqualTo("Lan");
            assertThat(detail.album().get(0).year()).isEqualTo(2025);
        }

        @Test
        @DisplayName("UTC-024 Review hiện tên, hạng theo điểm, số sao, năm, lượt thích")
        void utc024_reviewShowsAuthorRankAndFields() {
            when(repository.reviews(EVENT_ID, null)).thenReturn(List.of(review(REVIEW_OTHER, 50L, 150)));

            ReviewItem item = service.detail(EVENT_ID, null).reviews().get(0);

            assertThat(item.author().displayName()).isEqualTo("User 50");
            assertThat(item.author().rankName()).isEqualTo("Người chỉ đường");
            assertThat(item.rating()).isEqualTo(5);
            assertThat(item.year()).isEqualTo(2026);
            assertThat(item.likeCount()).isEqualTo(3);
            assertThat(item.mine()).isFalse();
            assertThat(item.liked()).isFalse();
        }

        @Test
        @DisplayName("UTC-025 Hạng: 0 → Người mới, 100 → Người chỉ đường, 300 → Senpai tin cậy")
        void utc025_rankNameByPoints() {
            List<RankRow> ranks = List.of(new RankRow("Senpai tin cậy", 300), new RankRow("Người chỉ đường", 100),
                    new RankRow("Người mới", 0));

            assertThat(EventService.rankName(ranks, 0)).isEqualTo("Người mới");
            assertThat(EventService.rankName(ranks, 99)).isEqualTo("Người mới");
            assertThat(EventService.rankName(ranks, 100)).isEqualTo("Người chỉ đường");
            assertThat(EventService.rankName(ranks, 299)).isEqualTo("Người chỉ đường");
            assertThat(EventService.rankName(ranks, 300)).isEqualTo("Senpai tin cậy");
        }

        @Test
        @DisplayName("UTC-026 AF7 Chưa có review: danh sách review rỗng")
        void utc026_noReview_emptyList() {
            assertThat(service.detail(EVENT_ID, null).reviews()).isEmpty();
        }

        @Test
        @DisplayName("UTC-027 Khách: không đăng nhập, không phải Thành viên, không có review của mình")
        void utc027_guestViewer() {
            EventDetail detail = service.detail(EVENT_ID, null);

            assertThat(detail.viewer().signedIn()).isFalse();
            assertThat(detail.viewer().member()).isFalse();
            assertThat(detail.viewer().myReviewId()).isNull();
            assertThat(detail.viewer().reviewRemoved()).isFalse();
            verify(repository).reviews(EVENT_ID, null);
            verify(repository, never()).likedReviews(anyLong(), anyLong());
        }

        @Test
        @DisplayName("UTC-028 Thành viên chưa có review năm nay: nút \"Viết review năm 2026\"")
        void utc028_memberWithoutReview() {
            EventDetail detail = service.detail(EVENT_ID, MEMBER);

            assertThat(detail.viewer().member()).isTrue();
            assertThat(detail.viewer().myReviewId()).isNull();
            assertThat(detail.event().reviewYear()).isEqualTo(2026);
            verify(repository).ownReviewId(EVENT_ID, 2026, MEMBER_ID);
        }

        @Test
        @DisplayName("UTC-029 Thành viên đã có review năm nay: \"Sửa review năm 2026\"")
        void utc029_memberWithReview_canEdit() {
            when(repository.ownReviewId(EVENT_ID, 2026, MEMBER_ID)).thenReturn(Optional.of(REVIEW_MINE));
            when(repository.ownReview(REVIEW_MINE)).thenReturn(Optional.of(new OwnRow(REVIEW_MINE, 4, "Vui",
                    "PUBLISHED")));

            EventDetail detail = service.detail(EVENT_ID, MEMBER);

            assertThat(detail.viewer().myReviewId()).isEqualTo(REVIEW_MINE);
            assertThat(detail.viewer().reviewRemoved()).isFalse();
        }

        @Test
        @DisplayName("UTC-030 Review năm nay bị Moderator gỡ: không có nút viết/sửa")
        void utc030_memberReviewRemoved_noButton() {
            when(repository.ownReviewId(EVENT_ID, 2026, MEMBER_ID)).thenReturn(Optional.of(REVIEW_MINE));
            when(repository.ownReview(REVIEW_MINE)).thenReturn(Optional.of(new OwnRow(REVIEW_MINE, 1, "x",
                    "REMOVED")));

            EventDetail detail = service.detail(EVENT_ID, MEMBER);

            assertThat(detail.viewer().myReviewId()).isNull();
            assertThat(detail.viewer().reviewRemoved()).isTrue();
        }

        @Test
        @DisplayName("UTC-031 Tài khoản nhân viên: không phải Thành viên (không thấy nút viết review)")
        void utc031_staffViewer_notMember() {
            EventDetail detail = service.detail(EVENT_ID, MODERATOR);

            assertThat(detail.viewer().signedIn()).isTrue();
            assertThat(detail.viewer().member()).isFalse();
        }

        @Test
        @DisplayName("UTC-032 AF3/AF5 Review của mình được đánh dấu \"mine\", review đã thích được đánh dấu \"liked\"")
        void utc032_mineAndLikedFlags() {
            when(repository.reviews(EVENT_ID, MEMBER_ID)).thenReturn(List.of(review(REVIEW_MINE, MEMBER_ID, 0),
                    review(REVIEW_OTHER, 50L, 0)));
            when(repository.likedReviews(EVENT_ID, MEMBER_ID)).thenReturn(Set.of(REVIEW_OTHER));

            List<ReviewItem> items = service.detail(EVENT_ID, MEMBER).reviews();

            assertThat(items.get(0).mine()).isTrue();
            assertThat(items.get(0).liked()).isFalse();
            assertThat(items.get(1).mine()).isFalse();
            assertThat(items.get(1).liked()).isTrue();
        }

        @Test
        @DisplayName("UTC-033 Sự kiện chưa bắt đầu: không viết review được (\"Viết review được khi sự kiện bắt đầu.\")")
        void utc033_upcomingEvent_cannotReview() {
            when(repository.event(EVENT_ID)).thenReturn(Optional.of(row(EVENT_ID, TODAY.plusDays(3),
                    TODAY.plusDays(4), LAT, LNG)));

            assertThat(service.detail(EVENT_ID, MEMBER).event().canReview()).isFalse();
        }

        @Test
        @DisplayName("UTC-034 AF4 Có danh sách lý do báo cáo; mở trang không thay đổi dữ liệu")
        void utc034_reportReasonsAndNoWrite() {
            EventDetail detail = service.detail(EVENT_ID, MEMBER);

            assertThat(detail.reportReasons()).extracting(Reason::label).containsExactly("Spam", "Lừa đảo");
            verify(reports, never()).report(any(), anyLong(), any(), any());
            verify(repository, never()).softDelete(anyLong(), any());
            verifyNoInteractions(activityLog);
        }
    }

    // =================================================================================================================
    @Nested
    @TestMethodOrder(MethodOrderer.DisplayName.class)
    @DisplayName("04. create – UC 4 Tạo lễ hội / sự kiện (UTC-035 → UTC-066)")
    class CreateEvent {

        @Test
        @DisplayName("UTC-035 Đăng sự kiện hợp lệ: lưu kèm người đăng, giờ đăng")
        void utc035_validEvent_saved() {
            ModSaved saved = service.create(MOD_ID, request(), null);

            CleanEvent e = insertedEvent();
            assertThat(e.title()).isEqualTo("Lễ hội pháo hoa Sumida");
            assertThat(e.startDate()).isEqualTo(TODAY.plusDays(5));
            assertThat(e.endDate()).isEqualTo(TODAY.plusDays(6));
            assertThat(e.locationName()).isEqualTo("Sông Sumida");
            assertThat(e.costNote()).isEqualTo("Miễn phí");
            assertThat(e.tip()).isEqualTo("Đi sớm để có chỗ ngồi.");
            assertThat(saved.event().id()).isEqualTo(EVENT_ID);
        }

        @Test
        @DisplayName("UTC-036 GBR-17 Ghi nhật ký \"Đăng sự kiện \"<tên>\"\"")
        void utc036_activityLogged() {
            service.create(MOD_ID, request(), null);

            @SuppressWarnings("unchecked")
            ArgumentCaptor<Map<String, Object>> values = ArgumentCaptor.forClass(Map.class);
            verify(activityLog).recordLog(eq(MOD_ID), eq("EVENT_CREATED"), eq("EVENT"), eq(EVENT_ID), isNull(),
                    values.capture(), eq("Đăng sự kiện \"Lễ hội pháo hoa Sumida\""));
            assertThat(values.getValue()).containsEntry("title", "Lễ hội pháo hoa Sumida")
                    .containsEntry("startDate", TODAY.plusDays(5).toString())
                    .containsEntry("costNote", "Miễn phí");
        }

        @Test
        @DisplayName("UTC-037 Khoảng trắng đầu cuối của các trường bị bỏ")
        void utc037_fieldsAreTrimmed() {
            service.create(MOD_ID, request("  Hanabi  ", TODAY, TODAY, "  Ueno  ", LAT, LNG, "  1000 yên ",
                    "  Mang ô "), null);

            CleanEvent e = insertedEvent();
            assertThat(e.title()).isEqualTo("Hanabi");
            assertThat(e.locationName()).isEqualTo("Ueno");
            assertThat(e.costNote()).isEqualTo("1000 yên");
            assertThat(e.tip()).isEqualTo("Mang ô");
        }

        @Test
        @DisplayName("UTC-038 AF2 Có vị trí, bỏ trống nơi tổ chức → \"Vị trí trên bản đồ\"")
        void utc038_locatedWithoutPlaceName_defaultsToMapLocation() {
            service.create(MOD_ID, request("Hanabi", TODAY, TODAY, "  ", LAT, LNG, null, null), null);

            assertThat(insertedEvent().locationName()).isEqualTo("Vị trí trên bản đồ");
        }

        @Test
        @DisplayName("UTC-039 AF2 Không vị trí, bỏ trống nơi tổ chức → \"Chưa gắn vị trí\"")
        void utc039_noLocationNoPlaceName_defaultsToNotLocated() {
            service.create(MOD_ID, request("Hanabi", TODAY, TODAY, null, null, null, null, null), null);

            CleanEvent e = insertedEvent();
            assertThat(e.locationName()).isEqualTo("Chưa gắn vị trí");
            assertThat(e.lat()).isNull();
            assertThat(e.lng()).isNull();
            assertThat(e.located()).isFalse();
        }

        @Test
        @DisplayName("UTC-040 AF2 Bỏ trống chi phí → \"Chưa rõ\"; bỏ trống mẹo → không có mẹo")
        void utc040_emptyCostAndTip_defaults() {
            service.create(MOD_ID, request("Hanabi", TODAY, TODAY, "Ueno", LAT, LNG, " ", ""), null);

            CleanEvent e = insertedEvent();
            assertThat(e.costNote()).isEqualTo("Chưa rõ");
            assertThat(e.tip()).isNull();
        }

        @Test
        @DisplayName("UTC-041 Tọa độ được làm tròn 6 chữ số thập phân")
        void utc041_coordinatesRoundedTo6Decimals() {
            service.create(MOD_ID, request("Hanabi", TODAY, TODAY, "Ueno", new BigDecimal("35.71480049"),
                    new BigDecimal("139.79670051"), null, null), null);

            CleanEvent e = insertedEvent();
            assertThat(e.lat()).isEqualByComparingTo("35.714800");
            assertThat(e.lng()).isEqualByComparingTo("139.796701");
        }

        @Test
        @DisplayName("UTC-042 Sự kiện 1 ngày (bắt đầu = kết thúc) hợp lệ")
        void utc042_oneDayEvent_valid() {
            service.create(MOD_ID, request("Hanabi", TODAY, TODAY, null, null, null, null, null), null);

            assertThat(insertedEvent().endDate()).isEqualTo(TODAY);
        }

        @Test
        @DisplayName("UTC-043 Kéo dài đúng 366 ngày hợp lệ")
        void utc043_366Days_valid() {
            service.create(MOD_ID, request("Triển lãm", TODAY, TODAY.plusDays(366), null, null, null, null, null),
                    null);

            assertThat(insertedEvent().endDate()).isEqualTo(TODAY.plusDays(366));
        }

        @Test
        @DisplayName("UTC-044 Tên đúng 160 ký tự, nơi tổ chức 160, chi phí 120, mẹo 500 hợp lệ")
        void utc044_maxLengths_valid() {
            service.create(MOD_ID, request(repeat(160), TODAY, TODAY, repeat(160), LAT, LNG, repeat(120),
                    repeat(500)), null);

            CleanEvent e = insertedEvent();
            assertThat(e.title()).hasSize(160);
            assertThat(e.locationName()).hasSize(160);
            assertThat(e.costNote()).hasSize(120);
            assertThat(e.tip()).hasSize(500);
        }

        @Test
        @DisplayName("UTC-045 Không kiểm tra trùng: đăng 2 sự kiện cùng tên, cùng ngày đều được lưu")
        void utc045_duplicateTitleAndDates_bothSaved() {
            service.create(MOD_ID, request(), null);
            service.create(MOD_ID, request(), null);

            verify(repository, times(2)).insertEvent(any(), eq(MOD_ID), eq(NOW));
        }

        @Test
        @DisplayName("UTC-046 AF3 Không có dữ liệu → \"Hãy nhập tên sự kiện.\"")
        void utc046_nullBody_rejected() {
            assertApiError(() -> service.create(MOD_ID, null, null), HttpStatus.BAD_REQUEST, "Hãy nhập tên sự kiện.");
            verifyNothingSaved();
        }

        @ParameterizedTest(name = "[{index}] tên = \"{0}\"")
        @ValueSource(strings = {"", "   ", "　"})
        @DisplayName("UTC-047 AF3 Tên trống hoặc chỉ có khoảng trắng → \"Hãy nhập tên sự kiện.\"")
        void utc047_blankTitle_rejected(String title) {
            assertInvalid(() -> service.create(MOD_ID, request(title, TODAY, TODAY, null, null, null, null, null),
                    null), "title", "Hãy nhập tên sự kiện.");
            verifyNothingSaved();
        }

        @Test
        @DisplayName("UTC-048 AF3 Tên 161 ký tự → \"Tên sự kiện tối đa 160 ký tự.\"")
        void utc048_title161_rejected() {
            assertInvalid(() -> service.create(MOD_ID, request(repeat(161), TODAY, TODAY, null, null, null, null,
                    null), null), "title", "Tên sự kiện tối đa 160 ký tự.");
            verifyNothingSaved();
        }

        @Test
        @DisplayName("UTC-049 AF3 Thiếu ngày bắt đầu → \"Hãy chọn ngày bắt đầu và ngày kết thúc.\"")
        void utc049_missingStart_rejected() {
            assertInvalid(() -> service.create(MOD_ID, request("Hanabi", null, TODAY, null, null, null, null, null),
                    null), "dates", "Hãy chọn ngày bắt đầu và ngày kết thúc.");
            verifyNothingSaved();
        }

        @Test
        @DisplayName("UTC-050 AF3 Thiếu ngày kết thúc → \"Hãy chọn ngày bắt đầu và ngày kết thúc.\"")
        void utc050_missingEnd_rejected() {
            assertInvalid(() -> service.create(MOD_ID, request("Hanabi", TODAY, null, null, null, null, null, null),
                    null), "dates", "Hãy chọn ngày bắt đầu và ngày kết thúc.");
        }

        @Test
        @DisplayName("UTC-051 AF3 Ngày kết thúc trước ngày bắt đầu → báo lỗi")
        void utc051_endBeforeStart_rejected() {
            assertInvalid(() -> service.create(MOD_ID, request("Hanabi", TODAY, TODAY.minusDays(1), null, null, null,
                    null, null), null), "dates", "Ngày kết thúc phải bằng hoặc sau ngày bắt đầu.");
            verifyNothingSaved();
        }

        @Test
        @DisplayName("UTC-052 AF3 Kéo dài 367 ngày → \"Sự kiện kéo dài tối đa 366 ngày. Hãy kiểm tra lại năm.\"")
        void utc052_367Days_rejected() {
            assertInvalid(() -> service.create(MOD_ID, request("Hanabi", TODAY, TODAY.plusDays(367), null, null, null,
                    null, null), null), "dates", "Sự kiện kéo dài tối đa 366 ngày. Hãy kiểm tra lại năm.");
            verifyNothingSaved();
        }

        @Test
        @DisplayName("UTC-053 AF3 Có vĩ độ, thiếu kinh độ → \"Vị trí cần đủ vĩ độ và kinh độ, hoặc bỏ vị trí.\"")
        void utc053_latWithoutLng_rejected() {
            assertInvalid(() -> service.create(MOD_ID, request("Hanabi", TODAY, TODAY, null, LAT, null, null, null),
                    null), "location", "Vị trí cần đủ vĩ độ và kinh độ, hoặc bỏ vị trí.");
        }

        @Test
        @DisplayName("UTC-054 AF3 Có kinh độ, thiếu vĩ độ → cùng thông báo")
        void utc054_lngWithoutLat_rejected() {
            assertInvalid(() -> service.create(MOD_ID, request("Hanabi", TODAY, TODAY, null, null, LNG, null, null),
                    null), "location", "Vị trí cần đủ vĩ độ và kinh độ, hoặc bỏ vị trí.");
        }

        @ParameterizedTest(name = "[{index}] lat = {0}, lng = {1}")
        @org.junit.jupiter.params.provider.CsvSource({"90.0001, 139", "-91, 139", "35, 180.5", "35, -181"})
        @DisplayName("UTC-055 AF3 Tọa độ ngoài phạm vi → \"Vị trí không hợp lệ. Hãy bấm lại lên bản đồ.\"")
        void utc055_outOfRangeCoordinates_rejected(String lat, String lng) {
            assertInvalid(() -> service.create(MOD_ID, request("Hanabi", TODAY, TODAY, null, new BigDecimal(lat),
                    new BigDecimal(lng), null, null), null), "location", "Vị trí không hợp lệ. Hãy bấm lại lên bản đồ.");
            verifyNothingSaved();
        }

        @Test
        @DisplayName("UTC-056 Tọa độ biên (±90, ±180) hợp lệ")
        void utc056_boundaryCoordinates_valid() {
            service.create(MOD_ID, request("Hanabi", TODAY, TODAY, null, new BigDecimal("-90"), new BigDecimal("180"),
                    null, null), null);

            assertThat(insertedEvent().located()).isTrue();
        }

        @Test
        @DisplayName("UTC-057 AF3 Nơi tổ chức 161 ký tự → \"Tên nơi tổ chức tối đa 160 ký tự.\"")
        void utc057_place161_rejected() {
            assertInvalid(() -> service.create(MOD_ID, request("Hanabi", TODAY, TODAY, repeat(161), null, null, null,
                    null), null), "locationName", "Tên nơi tổ chức tối đa 160 ký tự.");
        }

        @Test
        @DisplayName("UTC-058 AF3 Chi phí 121 ký tự → \"Chi phí tối đa 120 ký tự.\"")
        void utc058_cost121_rejected() {
            assertInvalid(() -> service.create(MOD_ID, request("Hanabi", TODAY, TODAY, null, null, null, repeat(121),
                    null), null), "costNote", "Chi phí tối đa 120 ký tự.");
        }

        @Test
        @DisplayName("UTC-059 AF3 Mẹo 501 ký tự → \"Mẹo cho người đi tối đa 500 ký tự.\"")
        void utc059_tip501_rejected() {
            assertInvalid(() -> service.create(MOD_ID, request("Hanabi", TODAY, TODAY, null, null, null, null,
                    repeat(501)), null), "tip", "Mẹo cho người đi tối đa 500 ký tự.");
        }

        @Test
        @DisplayName("UTC-060 AF3 Nhiều trường sai: báo từng trường, câu đầu tiên là câu tóm tắt")
        void utc060_severalErrors_allFieldsReported() {
            ApiException e = apiError(() -> service.create(MOD_ID, request("", TODAY, TODAY.minusDays(2), null, LAT,
                    null, repeat(121), null), null));

            assertThat(e.getMessage()).isEqualTo("Hãy nhập tên sự kiện.");
            assertThat(e.getFields()).containsOnlyKeys("title", "dates", "location", "costNote");
            verifyNothingSaved();
        }

        @Test
        @DisplayName("UTC-061 Ảnh bìa hợp lệ: nén lại, lưu vào thư mục event, gắn vào sự kiện")
        void utc061_validCover_savedAndAttached() {
            MockMultipartFile cover = coverFile();
            Prepared prepared = preparedImage();
            when(media.prepareImage(cover)).thenReturn(prepared);
            when(media.save(MOD_ID, "event", prepared)).thenReturn(555L);

            service.create(MOD_ID, request(), cover);

            verify(media).prepareImage(cover);
            verify(repository).setCover(EVENT_ID, 555L);
        }

        @Test
        @DisplayName("UTC-062 Ảnh bìa do nhân viên tải lên không cần quét kiểm duyệt (đánh dấu đạt ngay)")
        void utc062_coverMarkedPassedWithoutScan() {
            MockMultipartFile cover = coverFile();
            Prepared prepared = preparedImage();
            when(media.prepareImage(cover)).thenReturn(prepared);
            when(media.save(MOD_ID, "event", prepared)).thenReturn(555L);

            service.create(MOD_ID, request(), cover);

            verify(repository).markCoverPassed(555L);
        }

        @Test
        @DisplayName("UTC-063 AF4 Ảnh bìa không hợp lệ: báo lỗi ở ô ảnh bìa, không lưu sự kiện")
        void utc063_invalidCover_rejectedNothingSaved() {
            MockMultipartFile cover = coverFile();
            when(media.prepareImage(cover)).thenThrow(new InvalidMediaException(
                    "Ảnh phải là JPG, PNG hoặc HEIC và không quá 20 MB."));

            assertInvalid(() -> service.create(MOD_ID, request(), cover), "cover",
                    "Ảnh phải là JPG, PNG hoặc HEIC và không quá 20 MB.");
            verifyNothingSaved();
            verify(media, never()).save(anyLong(), anyString(), any());
        }

        @Test
        @DisplayName("UTC-064 Không chọn ảnh bìa (hoặc tệp rỗng): không gắn ảnh")
        void utc064_noCover_noCoverSaved() {
            service.create(MOD_ID, request(), new MockMultipartFile("cover", new byte[0]));

            verify(media, never()).prepareImage(any());
            verify(repository, never()).setCover(anyLong(), any());
        }

        @Test
        @DisplayName("UTC-065 Bước 6: thông báo ghim theo trạng thái sự kiện")
        void utc065_publishMessages() {
            when(repository.event(EVENT_ID)).thenReturn(Optional.of(row(EVENT_ID, TODAY, TODAY, LAT, LNG)));
            assertThat(service.create(MOD_ID, request(), null).message())
                    .isEqualTo("Đã đăng. Ghim sự kiện đã hiện trên bản đồ.");

            when(repository.event(EVENT_ID)).thenReturn(Optional.of(row(EVENT_ID, TODAY.plusDays(30),
                    TODAY.plusDays(30), LAT, LNG)));
            assertThat(service.create(MOD_ID, request(), null).message())
                    .isEqualTo("Đã đăng. Ghim sự kiện đã hiện trên bản đồ.");

            when(repository.event(EVENT_ID)).thenReturn(Optional.of(row(EVENT_ID, TODAY.plusDays(45),
                    TODAY.plusDays(46), LAT, LNG)));
            assertThat(service.create(MOD_ID, request(), null).message())
                    .isEqualTo("Đã đăng. Ghim sẽ hiện trên bản đồ khi còn 30 ngày (bắt đầu sau 45 ngày).");

            when(repository.event(EVENT_ID)).thenReturn(Optional.of(row(EVENT_ID, TODAY.minusDays(9),
                    TODAY.minusDays(8), LAT, LNG)));
            assertThat(service.create(MOD_ID, request(), null).message())
                    .isEqualTo("Đã đăng. Sự kiện đã qua nên ghim không hiện trên bản đồ.");

            when(repository.event(EVENT_ID)).thenReturn(Optional.of(row(EVENT_ID, TODAY, TODAY, null, null)));
            assertThat(service.create(MOD_ID, request(), null).message())
                    .isEqualTo("Đã đăng. Sự kiện chỉ hiện trong danh sách vì chưa gắn vị trí.");
        }

        @Test
        @DisplayName("UTC-066 Postcondition: sự kiện vừa đăng nằm trong danh sách công khai")
        void utc066_createdEventAppearsInList() {
            service.create(MOD_ID, request(), null);
            EventRow stored = repository.event(EVENT_ID).orElseThrow();
            when(repository.events(null)).thenReturn(List.of(stored));

            assertThat(service.list(null)).extracting(EventCard::id).containsExactly(EVENT_ID);
        }
    }

    // =================================================================================================================
    @Nested
    @TestMethodOrder(MethodOrderer.DisplayName.class)
    @DisplayName("05. update – UC 5 Cập nhật lễ hội / sự kiện (UTC-067 → UTC-077)")
    class UpdateEvent {

        @Test
        @DisplayName("UTC-067 Lưu thay đổi hợp lệ: cập nhật sự kiện, \"Đã lưu thay đổi.\" kèm trạng thái ghim")
        void utc067_validUpdate_saved() {
            ModSaved saved = service.update(MOD_ID, EVENT_ID, request(), null);

            ArgumentCaptor<CleanEvent> e = ArgumentCaptor.forClass(CleanEvent.class);
            verify(repository).updateEvent(eq(EVENT_ID), e.capture(), eq(NOW));
            assertThat(e.getValue().title()).isEqualTo("Lễ hội pháo hoa Sumida");
            assertThat(saved.message()).isEqualTo("Đã lưu thay đổi. Ghim sự kiện đã hiện trên bản đồ.");
        }

        @Test
        @DisplayName("UTC-068 GBR-17 Ghi nhật ký \"Sửa sự kiện \"<tên>\"\" với giá trị cũ và mới")
        void utc068_activityLoggedWithOldAndNewValues() {
            service.update(MOD_ID, EVENT_ID, request(), null);

            @SuppressWarnings("unchecked")
            ArgumentCaptor<Map<String, Object>> oldValues = ArgumentCaptor.forClass(Map.class);
            @SuppressWarnings("unchecked")
            ArgumentCaptor<Map<String, Object>> newValues = ArgumentCaptor.forClass(Map.class);
            verify(activityLog).recordLog(eq(MOD_ID), eq("EVENT_UPDATED"), eq("EVENT"), eq(EVENT_ID),
                    oldValues.capture(), newValues.capture(), eq("Sửa sự kiện \"Lễ hội pháo hoa Sumida\""));
            assertThat(oldValues.getValue()).containsEntry("title", "Lễ hội " + EVENT_ID);
            assertThat(newValues.getValue()).containsEntry("title", "Lễ hội pháo hoa Sumida");
        }

        @Test
        @DisplayName("UTC-069 AF6 Sự kiện vừa bị Moderator khác xóa → 404, không lưu gì")
        void utc069_deletedMeanwhile_notFound() {
            when(repository.lockEvent(EVENT_ID)).thenReturn(Optional.empty());

            assertApiError(() -> service.update(MOD_ID, EVENT_ID, request(), null), HttpStatus.NOT_FOUND,
                    "Không tìm thấy sự kiện. Sự kiện có thể đã bị xóa.");
            verifyNothingSaved();
        }

        @Test
        @DisplayName("UTC-070 AF5 Dữ liệu sai: kiểm tra như UC 4, không khóa và không sửa sự kiện")
        void utc070_invalidData_rejectedBeforeLock() {
            assertInvalid(() -> service.update(MOD_ID, EVENT_ID, request("", TODAY, TODAY, null, null, null, null,
                    null), null), "title", "Hãy nhập tên sự kiện.");
            verify(repository, never()).lockEvent(anyLong());
            verifyNothingSaved();
        }

        @Test
        @DisplayName("UTC-071 AF1 Đổi ảnh bìa: ảnh mới thay ảnh cũ, ảnh cũ bị bỏ")
        void utc071_newCover_replacesOld() {
            when(repository.lockEvent(EVENT_ID)).thenReturn(Optional.of(rowWithCover(EVENT_ID, 77L)));
            MockMultipartFile cover = coverFile();
            Prepared prepared = preparedImage();
            when(media.prepareImage(cover)).thenReturn(prepared);
            when(media.save(MOD_ID, "event", prepared)).thenReturn(556L);

            service.update(MOD_ID, EVENT_ID, request(), cover);

            verify(repository).setCover(EVENT_ID, 556L);
            verify(repository).markCoverPassed(556L);
            verify(media).discard(List.of(77L));
        }

        @Test
        @DisplayName("UTC-072 AF2 Bỏ ảnh bìa: sự kiện không còn ảnh, ảnh cũ bị bỏ")
        void utc072_removeCover_dropsIt() {
            when(repository.lockEvent(EVENT_ID)).thenReturn(Optional.of(rowWithCover(EVENT_ID, 77L)));

            service.update(MOD_ID, EVENT_ID, withRemoveCover(request()), null);

            verify(repository).setCover(EVENT_ID, null);
            verify(media).discard(List.of(77L));
            verify(media, never()).save(anyLong(), anyString(), any());
        }

        @Test
        @DisplayName("UTC-073 Không đổi ảnh bìa: giữ nguyên ảnh cũ")
        void utc073_coverUntouched() {
            when(repository.lockEvent(EVENT_ID)).thenReturn(Optional.of(rowWithCover(EVENT_ID, 77L)));

            service.update(MOD_ID, EVENT_ID, request(), null);

            verify(repository, never()).setCover(anyLong(), any());
            verify(media, never()).discard(any());
        }

        @Test
        @DisplayName("UTC-074 AF3 Bỏ vị trí: sự kiện chỉ hiện trong danh sách")
        void utc074_removeLocation_listOnly() {
            when(repository.event(EVENT_ID)).thenReturn(Optional.of(row(EVENT_ID, TODAY, TODAY, null, null)));

            ModSaved saved = service.update(MOD_ID, EVENT_ID, request("Hanabi", TODAY, TODAY, null, null, null, null,
                    null), null);

            ArgumentCaptor<CleanEvent> e = ArgumentCaptor.forClass(CleanEvent.class);
            verify(repository).updateEvent(eq(EVENT_ID), e.capture(), eq(NOW));
            assertThat(e.getValue().located()).isFalse();
            assertThat(e.getValue().locationName()).isEqualTo("Chưa gắn vị trí");
            assertThat(saved.message()).isEqualTo("Đã lưu thay đổi. Sự kiện chỉ hiện trong danh sách vì chưa gắn vị trí.");
        }

        @Test
        @DisplayName("UTC-075 AF5 Ảnh bìa mới không hợp lệ: không sửa sự kiện")
        void utc075_invalidNewCover_nothingSaved() {
            MockMultipartFile cover = coverFile();
            when(media.prepareImage(cover)).thenThrow(new InvalidMediaException("Máy chủ đang xử lý nhiều ảnh."));

            assertInvalid(() -> service.update(MOD_ID, EVENT_ID, request(), cover), "cover",
                    "Máy chủ đang xử lý nhiều ảnh.");
            verifyNothingSaved();
        }

        @Test
        @DisplayName("UTC-076 Postcondition: review đã có giữ nguyên khi sửa sự kiện")
        void utc076_reviewsKept() {
            service.update(MOD_ID, EVENT_ID, request(), null);

            verify(repository, never()).deleteReview(anyLong());
            verify(repository, never()).setStatus(anyLong(), anyString(), anyString());
            verifyNoInteractions(reports);
        }

        @Test
        @DisplayName("UTC-077 Đổi ngày bắt đầu sang năm khác: review mới thuộc năm mới")
        void utc077_startMovedToNextYear_reviewYearFollows() {
            LocalDate start = LocalDate.of(2027, 1, 2);
            when(repository.event(EVENT_ID)).thenReturn(Optional.of(row(EVENT_ID, start, start.plusDays(1), LAT, LNG)));

            ModSaved saved = service.update(MOD_ID, EVENT_ID, request("Hanabi", start, start.plusDays(1), null, LAT,
                    LNG, null, null), null);

            assertThat(saved.event().reviewYear()).isEqualTo(2027);
        }
    }

    // =================================================================================================================
    @Nested
    @TestMethodOrder(MethodOrderer.DisplayName.class)
    @DisplayName("06. delete – UC 6 Xóa lễ hội / sự kiện (UTC-078 → UTC-082)")
    class DeleteEvent {

        @Test
        @DisplayName("UTC-078 Xóa: ẩn sự kiện (xóa mềm), dữ liệu review vẫn giữ")
        void utc078_softDelete() {
            service.delete(MOD_ID, EVENT_ID);

            verify(repository).softDelete(EVENT_ID, NOW);
            verify(repository, never()).deleteReview(anyLong());
        }

        @Test
        @DisplayName("UTC-079 Đóng các báo cáo đang chờ về review của sự kiện")
        void utc079_openReportCasesClosed() {
            when(repository.reviewIds(EVENT_ID)).thenReturn(List.of(31L, 32L));

            service.delete(MOD_ID, EVENT_ID);

            verify(reports).closeCasesOf("REVIEW", List.of(31L, 32L), "Sự kiện đã bị xóa.");
            verify(reports, never()).report(any(), anyLong(), any(), any());
        }

        @Test
        @DisplayName("UTC-080 GBR-17 Ghi nhật ký \"Xóa sự kiện \"<tên>\"\"")
        void utc080_activityLogged() {
            service.delete(MOD_ID, EVENT_ID);

            verify(activityLog).recordLog(eq(MOD_ID), eq("EVENT_DELETED"), eq("EVENT"), eq(EVENT_ID), any(),
                    isNull(), eq("Xóa sự kiện \"Lễ hội " + EVENT_ID + "\""));
        }

        @Test
        @DisplayName("UTC-081 AF2 Sự kiện vừa bị Moderator khác xóa → 404, không làm gì thêm")
        void utc081_alreadyDeleted_notFound() {
            when(repository.lockEvent(EVENT_ID)).thenReturn(Optional.empty());

            assertApiError(() -> service.delete(MOD_ID, EVENT_ID), HttpStatus.NOT_FOUND,
                    "Không tìm thấy sự kiện. Sự kiện có thể đã bị xóa.");
            verify(repository, never()).softDelete(anyLong(), any());
            verifyNoInteractions(reports, activityLog);
        }

        @Test
        @DisplayName("UTC-082 AF3 Sau khi xóa, trang chi tiết báo \"Không tìm thấy sự kiện…\"")
        void utc082_detailAfterDelete_notFound() {
            service.delete(MOD_ID, EVENT_ID);
            when(repository.event(EVENT_ID)).thenReturn(Optional.empty());

            assertApiError(() -> service.detail(EVENT_ID, MEMBER), HttpStatus.NOT_FOUND,
                    "Không tìm thấy sự kiện. Sự kiện có thể đã bị xóa.");
        }
    }

    // =================================================================================================================
    @Nested
    @TestMethodOrder(MethodOrderer.DisplayName.class)
    @DisplayName("07. places, moderatorList – UC 4 AF1 và bảng sự kiện của Moderator (UTC-083 → UTC-087)")
    class ModeratorTools {

        @ParameterizedTest(name = "[{index}] q = \"{0}\"")
        @ValueSource(strings = {"", " ", "U", " U "})
        @DisplayName("UTC-083 AF1 Gõ dưới 2 ký tự: không gợi ý, không hỏi kho")
        void utc083_shortQuery_noSuggestion(String q) {
            assertThat(service.places(q)).isEmpty();
            verify(repository, never()).placesWithPosition(anyString(), anyInt());
        }

        @Test
        @DisplayName("UTC-084 AF1 Gõ từ 2 ký tự: gợi ý tối đa 8 địa điểm đã duyệt có tọa độ")
        void utc084_query_suggestsUpTo8Places() {
            PlacePick ueno = new PlacePick(5L, "Công viên Ueno", "Taito, Tokyo", LAT, LNG);
            when(repository.placesWithPosition("Ue", 8)).thenReturn(List.of(ueno));

            assertThat(service.places(" Ue ")).containsExactly(ueno);
        }

        @Test
        @DisplayName("UTC-085 AF1 Từ khóa dài hơn 100 ký tự bị cắt còn 100")
        void utc085_longQuery_cutTo100() {
            service.places("p".repeat(120));

            verify(repository).placesWithPosition("p".repeat(100), 8);
        }

        @Test
        @DisplayName("UTC-086 Bảng của Moderator có mẹo và người đăng")
        void utc086_moderatorListShowsTipAndAuthor() {
            when(repository.events(null)).thenReturn(List.of(row(1, TODAY, TODAY, LAT, LNG)));

            ModEvent row = service.moderatorList().get(0);

            assertThat(row.tip()).isEqualTo("Nên đi sớm.");
            assertThat(row.createdBy()).isEqualTo("Moderator A");
            assertThat(row.event().state()).isEqualTo("NOW");
        }

        @Test
        @DisplayName("UTC-087 Bảng của Moderator gồm cả sự kiện đã qua")
        void utc087_moderatorListIncludesPastEvents() {
            when(repository.events(null)).thenReturn(List.of(row(1, TODAY.minusDays(30), TODAY.minusDays(29), LAT,
                    LNG)));

            assertThat(service.moderatorList()).extracting(m -> m.event().state()).containsExactly("PAST");
        }
    }
}
