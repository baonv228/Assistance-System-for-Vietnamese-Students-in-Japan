package com.sep490.vsa.feature.notification;

import com.sep490.vsa.common.exception.ApiException;
import com.sep490.vsa.common.live.LiveEvents;
import com.sep490.vsa.feature.notification.NotificationDtos.NotificationItem;
import com.sep490.vsa.feature.notification.NotificationDtos.NotificationPage;
import com.sep490.vsa.feature.notification.NotificationDtos.NotificationSummary;
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
import org.mockito.InOrder;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit tests of the in-app notifications behind the bell and the "Thông báo" page ({@link NotificationService}) and
 * of the Java side of {@link NotificationRepository} (its SQL text, parameters and row mapping), from CLAUDE.md
 * "Notifications" and the behaviour shown by {@code ScanNotificationFlowTest}.
 * <p>
 * The database is not used: {@link JdbcTemplate}, {@link NotificationRepository} and {@link LiveEvents} are mocks and
 * the clock is fixed at 20/11/2026 03:00 UTC (12:00 Japan time).
 */
@TestClassOrder(ClassOrderer.DisplayName.class)
@DisplayName("NotificationService – Thông báo trong ứng dụng (chuông, trang Thông báo)")
class NotificationServiceTest {

    private static final long USER_ID = 10L;
    private static final Instant NOW = Instant.parse("2026-11-20T03:00:00Z");
    private static final String NOT_FOUND = "Không tìm thấy thông báo này.";

    private JdbcTemplate jdbc;
    private NotificationRepository repository;
    private LiveEvents live;
    private NotificationService service;

    @BeforeEach
    void setUp() {
        jdbc = mock(JdbcTemplate.class);
        repository = mock(NotificationRepository.class);
        live = mock(LiveEvents.class);
        service = new NotificationService(jdbc, repository, live, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static NotificationItem item(long id) {
        return new NotificationItem(id, NotificationService.NEW_COMMENT, "Thông báo " + id, "/blog/" + id, false,
                NOW.minusSeconds(id));
    }

    private static List<NotificationItem> items(long newestId, int count) {
        List<NotificationItem> list = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            list.add(item(newestId - i));
        }
        return list;
    }

    /** The content argument of the INSERT (4th argument of update: sql, userId, type, content…). */
    private String insertedContent() {
        ArgumentCaptor<Object> content = ArgumentCaptor.forClass(Object.class);
        verify(jdbc).update(contains("INSERT INTO notifications"), eq(USER_ID), anyString(), content.capture(),
                any(), any(), any());
        return (String) content.getValue();
    }

    private static void assertApiError(Runnable call, HttpStatus status, String message) {
        assertThatThrownBy(call::run)
                .isInstanceOf(ApiException.class)
                .hasMessage(message)
                .extracting(e -> ((ApiException) e).getStatus())
                .isEqualTo(status);
    }

    // =================================================================================================================
    @Nested
    @TestMethodOrder(MethodOrderer.DisplayName.class)
    @DisplayName("01. send – Gửi một thông báo cho người dùng (UTC-001 → UTC-014)")
    class Send {

        @Test
        @DisplayName("UTC-001 Ghi một dòng thông báo với giờ của đồng hồ hệ thống")
        void utc001_send_insertsRowWithClockTime() {
            service.send(USER_ID, NotificationService.NEW_COMMENT, "Lan đã bình luận bài viết “Thuê nhà”.", "BLOG",
                    55L);

            verify(jdbc).update(contains("INSERT INTO notifications (user_id, type, content, ref_type, ref_id, created_at)"),
                    eq(USER_ID), eq("NEW_COMMENT"), eq("Lan đã bình luận bài viết “Thuê nhà”."), eq("BLOG"), eq(55L),
                    eq(Timestamp.from(NOW)));
        }

        @Test
        @DisplayName("UTC-002 Báo ngay cho các trang đang mở: sự kiện live \"notification\" không mang nội dung")
        void utc002_send_publishesLiveEventWithoutText() {
            service.send(USER_ID, NotificationService.BEST_ANSWER, "Câu trả lời của bạn được chọn là hay nhất.",
                    "QUESTION", 7L);

            @SuppressWarnings("unchecked")
            ArgumentCaptor<Map<String, ?>> data = ArgumentCaptor.forClass(Map.class);
            verify(live).publish(eq(USER_ID), eq("notification"), data.capture());
            assertThat(data.getValue()).isEmpty();
            verify(live, never()).publish(eq(11L), anyString(), any());
        }

        @Test
        @DisplayName("UTC-003 Ghi dòng trước, rồi mới phát sự kiện live")
        void utc003_send_insertsBeforePublishing() {
            service.send(USER_ID, NotificationService.CONTENT_PUBLISHED, "Bài viết “T” đã được đăng. +10 điểm.", "BLOG",
                    12L);

            InOrder order = inOrder(jdbc, live);
            order.verify(jdbc).update(contains("INSERT INTO notifications"), any(Object[].class));
            order.verify(live).publish(eq(USER_ID), eq("notification"), any());
        }

        @ParameterizedTest(name = "[{index}] type {0}")
        @ValueSource(strings = {"NEW_COMMENT", "BEST_ANSWER", "CONTENT_REJECTED", "CONTENT_PUBLISHED"})
        @DisplayName("UTC-004 Loại thông báo được ghi đúng như truyền vào")
        void utc004_send_storesEachTypeAsGiven(String type) {
            service.send(USER_ID, type, "Nội dung", "BLOG", 1L);

            verify(jdbc).update(contains("INSERT INTO notifications"), eq(USER_ID), eq(type), eq("Nội dung"),
                    eq("BLOG"), eq(1L), eq(Timestamp.from(NOW)));
        }

        @Test
        @DisplayName("UTC-005 Hằng số loại thông báo đúng tên")
        void utc005_send_typeConstantsHaveTheirNames() {
            assertThat(NotificationService.NEW_COMMENT).isEqualTo("NEW_COMMENT");
            assertThat(NotificationService.BEST_ANSWER).isEqualTo("BEST_ANSWER");
            assertThat(NotificationService.CONTENT_REJECTED).isEqualTo("CONTENT_REJECTED");
            assertThat(NotificationService.CONTENT_PUBLISHED).isEqualTo("CONTENT_PUBLISHED");
        }

        @Test
        @DisplayName("UTC-006 Nội dung ngắn có dấu tiếng Việt giữ nguyên")
        void utc006_send_keepsShortVietnameseContent() {
            String text = "Ảnh của bạn không qua kiểm duyệt: có từ khóa “fb88”.";

            service.send(USER_ID, NotificationService.CONTENT_REJECTED, text, "REVIEW", 3L);

            assertThat(insertedContent()).isEqualTo(text);
        }

        @Test
        @DisplayName("UTC-007 Nội dung đúng 500 ký tự: giữ nguyên")
        void utc007_send_keepsContentOfExactly500Chars() {
            String text = "á".repeat(500);

            service.send(USER_ID, NotificationService.NEW_COMMENT, text, "BLOG", 1L);

            assertThat(insertedContent()).isEqualTo(text).hasSize(500);
        }

        @Test
        @DisplayName("UTC-008 Nội dung 501 ký tự: cắt còn 499 ký tự + \"…\"")
        void utc008_send_cutsContentOf501CharsTo499PlusEllipsis() {
            String text = "b".repeat(499) + "XY";

            service.send(USER_ID, NotificationService.NEW_COMMENT, text, "BLOG", 1L);

            assertThat(insertedContent()).hasSize(500).isEqualTo("b".repeat(499) + "…");
        }

        @Test
        @DisplayName("UTC-009 Nội dung rất dài (2000 ký tự): vẫn chỉ 500 ký tự")
        void utc009_send_cutsVeryLongContentTo500Chars() {
            String text = "c".repeat(2000);

            service.send(USER_ID, NotificationService.NEW_COMMENT, text, "BLOG", 1L);

            assertThat(insertedContent()).hasSize(500).endsWith("…").startsWith("c".repeat(499));
        }

        @Test
        @DisplayName("UTC-010 Nội dung rỗng: ghi chuỗi rỗng")
        void utc010_send_storesEmptyContent() {
            service.send(USER_ID, NotificationService.NEW_COMMENT, "", "BLOG", 1L);

            assertThat(insertedContent()).isEmpty();
        }

        @Test
        @DisplayName("UTC-011 Không có ref_type, ref_id: ghi NULL, vẫn báo live")
        void utc011_send_storesNullReference() {
            service.send(USER_ID, "QUEST_STREAK", "Bạn đã học 7 ngày liên tiếp. +50 xu.", null, null);

            verify(jdbc).update(contains("INSERT INTO notifications"), eq(USER_ID), eq("QUEST_STREAK"),
                    eq("Bạn đã học 7 ngày liên tiếp. +50 xu."), isNull(), isNull(), eq(Timestamp.from(NOW)));
            verify(live).publish(eq(USER_ID), eq("notification"), any());
        }

        @Test
        @DisplayName("UTC-012 Nội dung null: lỗi, không ghi, không báo live")
        void utc012_send_nullContentFailsBeforeWriting() {
            assertThatThrownBy(() -> service.send(USER_ID, NotificationService.NEW_COMMENT, null, "BLOG", 1L))
                    .isInstanceOf(NullPointerException.class);

            verifyNoInteractions(jdbc, live);
        }

        @Test
        @DisplayName("UTC-013 Lỗi cơ sở dữ liệu khi ghi: lỗi được ném lên, không báo live")
        void utc013_send_databaseErrorPublishesNothing() {
            when(jdbc.update(anyString(), any(Object[].class))).thenThrow(new DataAccessResourceFailureException("db down"));

            assertThatThrownBy(() -> service.send(USER_ID, NotificationService.NEW_COMMENT, "x", "BLOG", 1L))
                    .isInstanceOf(DataAccessResourceFailureException.class);

            verifyNoInteractions(live);
        }

        @Test
        @DisplayName("UTC-014 Gửi không đụng tới phần đọc (danh sách, đã đọc)")
        void utc014_send_doesNotTouchTheReadSide() {
            service.send(USER_ID, NotificationService.NEW_COMMENT, "x", "BLOG", 1L);

            verifyNoInteractions(repository);
        }
    }

    // =================================================================================================================
    @Nested
    @TestMethodOrder(MethodOrderer.DisplayName.class)
    @DisplayName("02. list – Danh sách thông báo (trang Thông báo, tab Chưa đọc) (UTC-015 → UTC-028)")
    class ListPage {

        @BeforeEach
        void noRows() {
            when(repository.page(anyLong(), anyBoolean(), anyInt(), anyInt())).thenReturn(List.of());
        }

        @Test
        @DisplayName("UTC-015 Trang đầu: hỏi từ vị trí 0, lấy 21 dòng để biết còn trang sau")
        void utc015_list_firstPageAsksOffset0Limit21() {
            service.list(USER_ID, 0, false);

            verify(repository).page(USER_ID, false, 0, 21);
        }

        @Test
        @DisplayName("UTC-016 Trang 1 và trang 7: vị trí 20 và 140")
        void utc016_list_laterPagesAskTwentyPerPage() {
            service.list(USER_ID, 1, false);
            service.list(USER_ID, 7, false);

            verify(repository).page(USER_ID, false, 20, 21);
            verify(repository).page(USER_ID, false, 140, 21);
        }

        @ParameterizedTest(name = "[{index}] page = {0}")
        @ValueSource(ints = {-1, -50, Integer.MIN_VALUE})
        @DisplayName("UTC-017 Trang âm: coi là trang đầu")
        void utc017_list_negativePageIsFirstPage(int page) {
            service.list(USER_ID, page, false);

            verify(repository).page(USER_ID, false, 0, 21);
        }

        @Test
        @DisplayName("UTC-018 Trang 199 (trang cuối được hỏi): vị trí 3980")
        void utc018_list_page199IsTheLastPageAsked() {
            service.list(USER_ID, 199, false);

            verify(repository).page(USER_ID, false, 3980, 21);
        }

        @ParameterizedTest(name = "[{index}] page = {0}")
        @ValueSource(ints = {200, 5000, Integer.MAX_VALUE})
        @DisplayName("UTC-019 Trang quá 199: giữ ở trang 199, không tràn số")
        void utc019_list_pageOver199IsKeptAt199(int page) {
            service.list(USER_ID, page, false);

            verify(repository).page(USER_ID, false, 3980, 21);
        }

        @Test
        @DisplayName("UTC-020 Có 21 dòng: trả 20 dòng đầu, hasMore = true")
        void utc020_list_twentyOneRowsGiveTwentyAndHasMore() {
            when(repository.page(USER_ID, false, 0, 21)).thenReturn(items(123, 21));
            when(repository.unread(USER_ID)).thenReturn(23);

            NotificationPage page = service.list(USER_ID, 0, false);

            assertThat(page.items()).hasSize(20);
            assertThat(page.items().get(0).id()).isEqualTo(123);
            assertThat(page.items().get(19).id()).isEqualTo(104);
            assertThat(page.items()).extracting(NotificationItem::id).doesNotContain(103L);
            assertThat(page.hasMore()).isTrue();
            assertThat(page.unread()).isEqualTo(23);
        }

        @Test
        @DisplayName("UTC-021 Có đúng 20 dòng: trả đủ 20, hasMore = false")
        void utc021_list_exactlyTwentyRowsHaveNoMore() {
            when(repository.page(USER_ID, false, 0, 21)).thenReturn(items(20, 20));

            NotificationPage page = service.list(USER_ID, 0, false);

            assertThat(page.items()).hasSize(20);
            assertThat(page.hasMore()).isFalse();
        }

        @Test
        @DisplayName("UTC-022 Trang cuối 3 dòng: trả 3 dòng, hasMore = false")
        void utc022_list_lastPageWithThreeRows() {
            when(repository.page(USER_ID, false, 20, 21)).thenReturn(items(3, 3));

            NotificationPage page = service.list(USER_ID, 1, false);

            assertThat(page.items()).extracting(NotificationItem::id).containsExactly(3L, 2L, 1L);
            assertThat(page.hasMore()).isFalse();
        }

        @Test
        @DisplayName("UTC-023 Chưa có thông báo nào: danh sách rỗng, chưa đọc = 0")
        void utc023_list_noNotificationsGivesEmptyPage() {
            NotificationPage page = service.list(USER_ID, 0, false);

            assertThat(page.items()).isEmpty();
            assertThat(page.hasMore()).isFalse();
            assertThat(page.unread()).isZero();
        }

        @Test
        @DisplayName("UTC-024 Tab \"Chưa đọc\": chỉ hỏi các thông báo chưa đọc")
        void utc024_list_unreadTabAsksUnreadOnly() {
            when(repository.page(USER_ID, true, 0, 21)).thenReturn(items(9, 2));
            when(repository.unread(USER_ID)).thenReturn(2);

            NotificationPage page = service.list(USER_ID, 0, true);

            verify(repository).page(USER_ID, true, 0, 21);
            verify(repository, never()).page(USER_ID, false, 0, 21);
            assertThat(page.items()).hasSize(2);
            assertThat(page.unread()).isEqualTo(2);
        }

        @Test
        @DisplayName("UTC-025 Số chưa đọc là của mọi thông báo, không chỉ trang này")
        void utc025_list_unreadCountsAllNotOnlyThisPage() {
            when(repository.page(USER_ID, false, 20, 21)).thenReturn(items(3, 3));
            when(repository.unread(USER_ID)).thenReturn(23);

            assertThat(service.list(USER_ID, 1, false).unread()).isEqualTo(23);
        }

        @Test
        @DisplayName("UTC-026 Giữ thứ tự mới nhất trước như kho dữ liệu trả về")
        void utc026_list_keepsNewestFirstOrder() {
            when(repository.page(USER_ID, false, 0, 21)).thenReturn(List.of(item(30), item(29), item(28)));

            assertThat(service.list(USER_ID, 0, false).items()).extracting(NotificationItem::id)
                    .containsExactly(30L, 29L, 28L);
        }

        @Test
        @DisplayName("UTC-027 Chỉ đọc thông báo của chính người dùng")
        void utc027_list_readsOnlyTheCallersRows() {
            service.list(USER_ID, 0, false);

            verify(repository).page(eq(USER_ID), anyBoolean(), anyInt(), anyInt());
            verify(repository).unread(USER_ID);
            verify(repository, never()).page(eq(11L), anyBoolean(), anyInt(), anyInt());
        }

        @Test
        @DisplayName("UTC-028 Xem danh sách không đánh dấu đã đọc, không ghi gì")
        void utc028_list_marksNothingRead() {
            when(repository.page(USER_ID, false, 0, 21)).thenReturn(items(5, 5));

            service.list(USER_ID, 0, false);

            verify(repository, never()).markRead(anyLong(), any());
            verify(repository, never()).markOneRead(anyLong(), anyLong());
            verifyNoInteractions(jdbc, live);
        }
    }

    // =================================================================================================================
    @Nested
    @TestMethodOrder(MethodOrderer.DisplayName.class)
    @DisplayName("03. summary – Chuông thông báo: số chưa đọc, thông báo mới, nội dung đang quét (UTC-029 → UTC-037)")
    class Summary {

        @BeforeEach
        void counts() {
            when(repository.unread(USER_ID)).thenReturn(4);
            when(repository.latestId(USER_ID)).thenReturn(120L);
            when(repository.processing(USER_ID)).thenReturn(0);
        }

        @Test
        @DisplayName("UTC-029 Không có after, seen: không có thông báo mới, số trên chuông = số chưa đọc")
        void utc029_summary_withoutAfterAndSeen() {
            NotificationSummary summary = service.summary(USER_ID, null, null);

            assertThat(summary.unread()).isEqualTo(4);
            assertThat(summary.latestId()).isEqualTo(120);
            assertThat(summary.fresh()).isEmpty();
            assertThat(summary.unseen()).isEqualTo(4);
            verify(repository, never()).after(anyLong(), anyLong(), anyInt());
            verify(repository, never()).unreadAfter(anyLong(), anyLong());
        }

        @Test
        @DisplayName("UTC-030 Có after: lấy tối đa 5 thông báo mới hơn id đó")
        void utc030_summary_afterGivesAtMostFiveNewer() {
            List<NotificationItem> fresh = items(120, 5);
            when(repository.after(USER_ID, 100L, 5)).thenReturn(fresh);

            NotificationSummary summary = service.summary(USER_ID, 100L, null);

            verify(repository).after(USER_ID, 100L, 5);
            assertThat(summary.fresh()).isEqualTo(fresh);
            assertThat(NotificationService.FRESH_MAX).isEqualTo(5);
        }

        @Test
        @DisplayName("UTC-031 after = 0 (tab chưa xử lý thông báo nào): vẫn hỏi các thông báo mới")
        void utc031_summary_afterZeroStillAsks() {
            when(repository.after(USER_ID, 0L, 5)).thenReturn(items(2, 2));

            assertThat(service.summary(USER_ID, 0L, null).fresh()).hasSize(2);
            verify(repository).after(USER_ID, 0L, 5);
        }

        @Test
        @DisplayName("UTC-032 Có seen: số trên chuông = số chưa đọc mới hơn seen")
        void utc032_summary_seenCountsUnreadAfterIt() {
            when(repository.unreadAfter(USER_ID, 115L)).thenReturn(2);

            NotificationSummary summary = service.summary(USER_ID, null, 115L);

            assertThat(summary.unseen()).isEqualTo(2);
            assertThat(summary.unread()).isEqualTo(4);
        }

        @Test
        @DisplayName("UTC-033 seen = thông báo mới nhất: số trên chuông = 0, chưa đọc vẫn còn")
        void utc033_summary_seenAtLatestClearsTheBellNumber() {
            when(repository.unreadAfter(USER_ID, 120L)).thenReturn(0);

            NotificationSummary summary = service.summary(USER_ID, null, 120L);

            assertThat(summary.unseen()).isZero();
            assertThat(summary.unread()).isEqualTo(4);
        }

        @Test
        @DisplayName("UTC-034 Có bài / review đang quét ảnh: processing được trả về")
        void utc034_summary_reportsContentStillProcessing() {
            when(repository.processing(USER_ID)).thenReturn(2);

            assertThat(service.summary(USER_ID, 120L, 120L).processing()).isEqualTo(2);
        }

        @Test
        @DisplayName("UTC-035 Chưa có thông báo nào: mọi số đều 0, latestId = 0")
        void utc035_summary_noNotificationsAtAll() {
            when(repository.unread(USER_ID)).thenReturn(0);
            when(repository.latestId(USER_ID)).thenReturn(0L);
            when(repository.after(USER_ID, 0L, 5)).thenReturn(List.of());
            when(repository.unreadAfter(USER_ID, 0L)).thenReturn(0);

            NotificationSummary summary = service.summary(USER_ID, 0L, 0L);

            assertThat(summary).isEqualTo(new NotificationSummary(0, 0, 0, List.of(), 0));
        }

        @Test
        @DisplayName("UTC-036 Đủ after và seen: trả đúng cả năm giá trị")
        void utc036_summary_allFiveValuesTogether() {
            List<NotificationItem> fresh = List.of(item(120), item(119));
            when(repository.after(USER_ID, 118L, 5)).thenReturn(fresh);
            when(repository.unreadAfter(USER_ID, 117L)).thenReturn(3);
            when(repository.processing(USER_ID)).thenReturn(1);

            assertThat(service.summary(USER_ID, 118L, 117L))
                    .isEqualTo(new NotificationSummary(4, 120, 1, fresh, 3));
        }

        @Test
        @DisplayName("UTC-037 Xem chuông không đánh dấu đã đọc, không ghi gì")
        void utc037_summary_marksNothingRead() {
            service.summary(USER_ID, 100L, 100L);

            verify(repository, never()).markRead(anyLong(), any());
            verify(repository, never()).markOneRead(anyLong(), anyLong());
            verifyNoInteractions(jdbc, live);
        }
    }

    // =================================================================================================================
    @Nested
    @TestMethodOrder(MethodOrderer.DisplayName.class)
    @DisplayName("04. markRead – Đánh dấu tất cả là đã đọc (UTC-038 → UTC-043)")
    class MarkRead {

        @Test
        @DisplayName("UTC-038 Không có upToId: đánh dấu mọi thông báo, trả số chưa đọc còn lại")
        void utc038_markRead_withoutIdMarksAll() {
            when(repository.unread(USER_ID)).thenReturn(0);

            assertThat(service.markRead(USER_ID, null)).isZero();
            verify(repository).markRead(USER_ID, null);
        }

        @Test
        @DisplayName("UTC-039 Có upToId: chỉ đánh dấu tới id đó, thông báo mới hơn vẫn chưa đọc")
        void utc039_markRead_upToIdMarksOnlyUpToIt() {
            when(repository.unread(USER_ID)).thenReturn(2);

            assertThat(service.markRead(USER_ID, 150L)).isEqualTo(2);
            verify(repository).markRead(USER_ID, 150L);
        }

        @Test
        @DisplayName("UTC-040 Đánh dấu trước, rồi mới đếm số còn lại")
        void utc040_markRead_marksBeforeCounting() {
            service.markRead(USER_ID, 150L);

            InOrder order = inOrder(repository);
            order.verify(repository).markRead(USER_ID, 150L);
            order.verify(repository).unread(USER_ID);
        }

        @Test
        @DisplayName("UTC-041 Không còn gì chưa đọc: vẫn chạy được, trả 0")
        void utc041_markRead_nothingUnreadIsFine() {
            when(repository.markRead(USER_ID, null)).thenReturn(0);
            when(repository.unread(USER_ID)).thenReturn(0);

            assertThat(service.markRead(USER_ID, null)).isZero();
        }

        @Test
        @DisplayName("UTC-042 upToId = 0: truyền nguyên 0 xuống, không đánh dấu tất cả")
        void utc042_markRead_upToZeroIsNotAll() {
            when(repository.unread(USER_ID)).thenReturn(5);

            assertThat(service.markRead(USER_ID, 0L)).isEqualTo(5);
            verify(repository).markRead(USER_ID, 0L);
            verify(repository, never()).markRead(USER_ID, null);
        }

        @Test
        @DisplayName("UTC-043 Chỉ đánh dấu thông báo của chính người dùng")
        void utc043_markRead_onlyTheCallersRows() {
            service.markRead(USER_ID, null);

            verify(repository).markRead(eq(USER_ID), any());
            verify(repository, never()).markRead(eq(11L), any());
            verifyNoInteractions(live);
        }
    }

    // =================================================================================================================
    @Nested
    @TestMethodOrder(MethodOrderer.DisplayName.class)
    @DisplayName("05. markOneRead – Bấm vào một thông báo (UTC-044 → UTC-049)")
    class MarkOneRead {

        @Test
        @DisplayName("UTC-044 Thông báo chưa đọc của mình: đánh dấu đã đọc, trả số còn lại")
        void utc044_markOneRead_marksOwnUnread() {
            when(repository.markOneRead(USER_ID, 77L)).thenReturn(1);
            when(repository.unread(USER_ID)).thenReturn(3);

            assertThat(service.markOneRead(USER_ID, 77L)).isEqualTo(3);
            verify(repository, never()).exists(anyLong(), anyLong());
        }

        @Test
        @DisplayName("UTC-045 Bấm lại thông báo đã đọc: không lỗi, không đổi gì")
        void utc045_markOneRead_alreadyReadIsFine() {
            when(repository.markOneRead(USER_ID, 77L)).thenReturn(0);
            when(repository.exists(USER_ID, 77L)).thenReturn(true);
            when(repository.unread(USER_ID)).thenReturn(3);

            assertThat(service.markOneRead(USER_ID, 77L)).isEqualTo(3);
        }

        @Test
        @DisplayName("UTC-046 Thông báo của người khác: 404")
        void utc046_markOneRead_anotherUsersNotificationIs404() {
            when(repository.markOneRead(USER_ID, 88L)).thenReturn(0);
            when(repository.exists(USER_ID, 88L)).thenReturn(false);

            assertApiError(() -> service.markOneRead(USER_ID, 88L), HttpStatus.NOT_FOUND, NOT_FOUND);
            verify(repository).exists(USER_ID, 88L);
            verify(repository, never()).unread(anyLong());
        }

        @Test
        @DisplayName("UTC-047 Id không tồn tại: 404")
        void utc047_markOneRead_missingIdIs404() {
            when(repository.markOneRead(USER_ID, 1_000_077L)).thenReturn(0);
            when(repository.exists(USER_ID, 1_000_077L)).thenReturn(false);

            assertApiError(() -> service.markOneRead(USER_ID, 1_000_077L), HttpStatus.NOT_FOUND, NOT_FOUND);
        }

        @ParameterizedTest(name = "[{index}] id = {0}")
        @ValueSource(longs = {0L, -1L})
        @DisplayName("UTC-048 Id 0 hoặc âm: 404")
        void utc048_markOneRead_zeroOrNegativeIdIs404(long id) {
            assertApiError(() -> service.markOneRead(USER_ID, id), HttpStatus.NOT_FOUND, NOT_FOUND);
        }

        @Test
        @DisplayName("UTC-049 Chỉ đánh dấu một thông báo, không đánh dấu tất cả")
        void utc049_markOneRead_marksOnlyThatOne() {
            when(repository.markOneRead(USER_ID, 77L)).thenReturn(1);

            service.markOneRead(USER_ID, 77L);

            verify(repository).markOneRead(USER_ID, 77L);
            verify(repository, never()).markRead(anyLong(), any());
            verifyNoInteractions(live);
        }
    }

    // =================================================================================================================
    @Nested
    @TestMethodOrder(MethodOrderer.DisplayName.class)
    @DisplayName("06. NotificationRepository.page / after – câu truy vấn, liên kết và ánh xạ dòng (UTC-050 → UTC-057)")
    class RepositoryItems {

        private NotificationRepository real;

        @BeforeEach
        void realRepository() {
            real = new NotificationRepository(jdbc);
        }

        @SuppressWarnings("unchecked")
        private ArgumentCaptor<RowMapper<NotificationItem>> mapperCaptor() {
            return ArgumentCaptor.forClass(RowMapper.class);
        }

        private String pageSql(boolean unreadOnly) {
            real.page(USER_ID, unreadOnly, 40, 21);
            ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
            verify(jdbc).query(sql.capture(), mapperCaptor().capture(), eq(USER_ID), eq(21), eq(40));
            return sql.getValue();
        }

        @Test
        @DisplayName("UTC-050 Tab \"Tất cả\": của người dùng, mới nhất trước, LIMIT 21 OFFSET 40")
        void utc050_repoPage_allTabSqlAndParameters() {
            String sql = pageSql(false);

            assertThat(sql).contains("WHERE n.user_id = ?")
                    .endsWith(" ORDER BY n.created_at DESC, n.id DESC LIMIT ? OFFSET ?")
                    .doesNotContain("n.is_read = FALSE");
        }

        @Test
        @DisplayName("UTC-051 Tab \"Chưa đọc\": thêm điều kiện chưa đọc")
        void utc051_repoPage_unreadTabAddsUnreadFilter() {
            assertThat(pageSql(true)).contains("WHERE n.user_id = ? AND n.is_read = FALSE ORDER BY");
        }

        @Test
        @DisplayName("UTC-052 Thông báo mới hơn after: id > after, mới nhất trước, có giới hạn")
        void utc052_repoAfter_sqlAndParameters() {
            real.after(USER_ID, 100L, 5);

            ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
            verify(jdbc).query(sql.capture(), mapperCaptor().capture(), eq(USER_ID), eq(100L), eq(5));
            assertThat(sql.getValue()).endsWith(" AND n.id > ? ORDER BY n.id DESC LIMIT ?");
        }

        @Test
        @DisplayName("UTC-053 Liên kết của bài viết / câu hỏi theo blog_type: /qa/{id} hoặc /blog/{id}")
        void utc053_repoLink_postsAndQuestionsFollowBlogType() {
            String sql = pageSql(false);

            assertThat(sql).contains("WHEN b.id IS NOT NULL AND b.blog_type = 'QA' THEN CONCAT('/qa/', b.id)")
                    .contains("WHEN b.id IS NOT NULL THEN CONCAT('/blog/', b.id)")
                    .contains("LEFT JOIN blogs b ON n.ref_type IN ('BLOG', 'QUESTION') AND b.id = n.ref_id");
        }

        @Test
        @DisplayName("UTC-054 Liên kết của review: sự kiện chưa xóa → /events/{id}, địa điểm → /map?place={id}")
        void utc054_repoLink_reviewsOpenTheirEventOrPlace() {
            String sql = pageSql(false);

            assertThat(sql).contains("WHEN r.event_id IS NOT NULL AND e.id IS NOT NULL THEN CONCAT('/events/', e.id)")
                    .contains("WHEN r.place_id IS NOT NULL THEN CONCAT('/map?place=', r.place_id)")
                    .contains("LEFT JOIN reviews r ON n.ref_type = 'REVIEW' AND r.id = n.ref_id")
                    .contains("LEFT JOIN events e ON e.id = r.event_id AND e.deleted_at IS NULL");
        }

        @Test
        @DisplayName("UTC-055 Liên kết của địa điểm (chỉ ACTIVE, MERGED) và nhiệm vụ hằng ngày (/quests)")
        void utc055_repoLink_placesAndQuests() {
            String sql = pageSql(false);

            assertThat(sql).contains("WHEN n.ref_type = 'QUEST' THEN '/quests'")
                    .contains("WHEN pl.id IS NOT NULL THEN CONCAT('/map?place=', pl.id)")
                    .contains("pl.status IN ('ACTIVE', 'MERGED')");
        }

        @Test
        @DisplayName("UTC-056 Ánh xạ một dòng: id, loại, nội dung, liên kết, đã đọc, thời điểm")
        void utc056_repoMapper_mapsEveryColumn() throws Exception {
            real.page(USER_ID, false, 0, 21);
            ArgumentCaptor<RowMapper<NotificationItem>> mapper = mapperCaptor();
            verify(jdbc).query(anyString(), mapper.capture(), any(Object[].class));
            ResultSet rs = mock(ResultSet.class);
            when(rs.getLong(1)).thenReturn(77L);
            when(rs.getString(2)).thenReturn("NEW_COMMENT");
            when(rs.getString(3)).thenReturn("Lan đã bình luận bài viết “Thuê nhà”.");
            when(rs.getBoolean(4)).thenReturn(true);
            when(rs.getTimestamp(5)).thenReturn(Timestamp.from(NOW));
            when(rs.getString(6)).thenReturn("/blog/12");

            assertThat(mapper.getValue().mapRow(rs, 0)).isEqualTo(new NotificationItem(77L, "NEW_COMMENT",
                    "Lan đã bình luận bài viết “Thuê nhà”.", "/blog/12", true, NOW));
        }

        @Test
        @DisplayName("UTC-057 Nội dung đã bị xóa (liên kết NULL) và thời điểm NULL: giữ null")
        void utc057_repoMapper_keepsNullLinkAndTime() throws Exception {
            real.after(USER_ID, 1L, 5);
            ArgumentCaptor<RowMapper<NotificationItem>> mapper = mapperCaptor();
            verify(jdbc).query(anyString(), mapper.capture(), any(Object[].class));
            ResultSet rs = mock(ResultSet.class);
            when(rs.getLong(1)).thenReturn(5L);
            when(rs.getString(2)).thenReturn("CONTENT_PUBLISHED");
            when(rs.getString(3)).thenReturn("x");

            NotificationItem row = mapper.getValue().mapRow(rs, 0);

            assertThat(row.link()).isNull();
            assertThat(row.createdAt()).isNull();
            assertThat(row.read()).isFalse();
        }
    }

    // =================================================================================================================
    @Nested
    @TestMethodOrder(MethodOrderer.DisplayName.class)
    @DisplayName("07. NotificationRepository – đếm và đánh dấu đã đọc (UTC-058 → UTC-065)")
    class RepositoryCounts {

        private NotificationRepository real;

        @BeforeEach
        void realRepository() {
            real = new NotificationRepository(jdbc);
        }

        @Test
        @DisplayName("UTC-058 Đếm chưa đọc của người dùng; không có kết quả → 0")
        void utc058_repoUnread_countsOwnUnreadNullIsZero() {
            when(jdbc.queryForObject(contains("WHERE user_id = ? AND is_read = FALSE"), eq(Integer.class), eq(USER_ID)))
                    .thenReturn(7, (Integer) null);

            assertThat(real.unread(USER_ID)).isEqualTo(7);
            assertThat(real.unread(USER_ID)).isZero();
        }

        @Test
        @DisplayName("UTC-059 Đếm chưa đọc mới hơn seen; không có kết quả → 0")
        void utc059_repoUnreadAfter_countsNewerUnread() {
            when(jdbc.queryForObject(contains("AND is_read = FALSE AND id > ?"), eq(Integer.class), eq(USER_ID),
                    eq(115L))).thenReturn(2, (Integer) null);

            assertThat(real.unreadAfter(USER_ID, 115L)).isEqualTo(2);
            assertThat(real.unreadAfter(USER_ID, 115L)).isZero();
        }

        @Test
        @DisplayName("UTC-060 exists: chỉ true khi thông báo thuộc người dùng")
        void utc060_repoExists_trueOnlyForOwnRow() {
            when(jdbc.queryForObject(contains("WHERE id = ? AND user_id = ?"), eq(Integer.class), eq(77L), eq(USER_ID)))
                    .thenReturn(1, 0, null);

            assertThat(real.exists(USER_ID, 77L)).isTrue();
            assertThat(real.exists(USER_ID, 77L)).isFalse();
            assertThat(real.exists(USER_ID, 77L)).isFalse();
        }

        @Test
        @DisplayName("UTC-061 Đánh dấu một thông báo: chỉ của mình và đang chưa đọc")
        void utc061_repoMarkOneRead_onlyOwnUnreadRow() {
            when(jdbc.update(contains("SET is_read = TRUE WHERE id = ? AND user_id = ? AND is_read = FALSE"), eq(77L),
                    eq(USER_ID))).thenReturn(1);

            assertThat(real.markOneRead(USER_ID, 77L)).isEqualTo(1);
            assertThat(real.markOneRead(USER_ID, 78L)).isZero();
        }

        @Test
        @DisplayName("UTC-062 Id mới nhất; chưa có thông báo → 0")
        void utc062_repoLatestId_maxIdOrZero() {
            when(jdbc.queryForObject(contains("SELECT COALESCE(MAX(id), 0) FROM notifications WHERE user_id = ?"),
                    eq(Long.class), eq(USER_ID))).thenReturn(120L, 0L, null);

            assertThat(real.latestId(USER_ID)).isEqualTo(120L);
            assertThat(real.latestId(USER_ID)).isZero();
            assertThat(real.latestId(USER_ID)).isZero();
        }

        @Test
        @DisplayName("UTC-063 Đang quét: đếm cả bài viết / câu hỏi và review PROCESSING của chính tác giả")
        void utc063_repoProcessing_countsOwnPostsAndReviews() {
            ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
            when(jdbc.queryForObject(sql.capture(), eq(Integer.class), eq(USER_ID), eq(USER_ID))).thenReturn(3, null);

            assertThat(real.processing(USER_ID)).isEqualTo(3);
            assertThat(real.processing(USER_ID)).isZero();
            assertThat(sql.getValue())
                    .contains("SELECT COUNT(*) FROM blogs WHERE author_id = ? AND status = 'PROCESSING'")
                    .contains("SELECT COUNT(*) FROM reviews WHERE author_id = ? AND status = 'PROCESSING'");
        }

        @Test
        @DisplayName("UTC-064 Đánh dấu tất cả: không có điều kiện id")
        void utc064_repoMarkRead_allWithoutIdCondition() {
            when(jdbc.update(anyString(), any(Object[].class))).thenReturn(4);

            assertThat(real.markRead(USER_ID, null)).isEqualTo(4);

            verify(jdbc).update("UPDATE notifications SET is_read = TRUE WHERE user_id = ? AND is_read = FALSE",
                    USER_ID);
        }

        @Test
        @DisplayName("UTC-065 Đánh dấu tới upToId: thêm id <= ?")
        void utc065_repoMarkRead_upToIdAddsCondition() {
            real.markRead(USER_ID, 150L);

            verify(jdbc).update(contains("WHERE user_id = ? AND is_read = FALSE AND id <= ?"), eq(USER_ID), eq(150L));
        }
    }
}
