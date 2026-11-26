package com.sep490.vsa.feature.blog;

import com.sep490.vsa.common.exception.ApiException;
import com.sep490.vsa.common.security.AppUserPrincipal;
import com.sep490.vsa.common.settings.SystemSettings;
import com.sep490.vsa.feature.blog.BlogDetailRepository.BlogRow;
import com.sep490.vsa.feature.blog.BlogDetailRepository.Member;
import com.sep490.vsa.feature.media.MediaService;
import com.sep490.vsa.feature.moderation.ContentFilter;
import com.sep490.vsa.feature.notification.NotificationService;
import com.sep490.vsa.feature.report.ContentReportService;
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
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit tests of "Xóa bài viết" (UC 1.7, {@link BlogActionService#deleteBlog} with kind POST), written from the use case
 * specification of Report 3 and CLAUDE.md ("Post Detail (UC-10)": {@code DELETE /api/blogs/{id}}, own post only: hard
 * delete, comments deleted newest first, its points withdrawn, its files discarded, open report cases closed; someone
 * else's post → MSG18; a question id → 404; guests 401, staff MSG18).
 * <p>
 * The database is not used: {@link BlogDetailRepository}, {@link ContentReportService}, {@link PointService},
 * {@link MediaService}, {@link NotificationService}, {@link ContentFilter} and {@link SystemSettings} are mocks; the
 * clock is fixed at 20/11/2026 12:00 Japan time. The last test runs the real {@link BlogDetailRepository#deleteBlog}
 * SQL against a mocked {@link JdbcTemplate} (comments newest first, then the post).
 */
@TestClassOrder(ClassOrderer.DisplayName.class)
@DisplayName("BlogActionService – Xóa bài viết (UC 1.7)")
class BlogActionServiceDeletePostTest {

    private static final long MEMBER_ID = 10L;
    private static final long OTHER_ID = 11L;
    private static final long STAFF_ID = 20L;
    private static final long BLOG_ID = 500L;
    private static final String KIND = "POST";
    private static final Instant NOW = Instant.parse("2026-11-20T03:00:00Z");

    private static final String MSG18 = "Bạn không có quyền thực hiện thao tác này.";
    private static final String MSG19 = "Không tìm thấy nội dung bạn yêu cầu. Bài viết có thể đã bị gỡ hoặc đã bị xóa.";
    private static final String SIGN_IN = "Bạn cần đăng nhập để thực hiện thao tác này.";
    private static final String NO_SESSION = "Bạn chưa đăng nhập.";

    private static final AppUserPrincipal MEMBER = principal(MEMBER_ID, "USER");

    private BlogDetailRepository repository;
    private ContentFilter filter;
    private ContentReportService reports;
    private PointService points;
    private NotificationService notifications;
    private MediaService media;
    private SystemSettings settings;
    private BlogActionService service;

    @BeforeEach
    void setUp() {
        repository = mock(BlogDetailRepository.class);
        filter = mock(ContentFilter.class);
        reports = mock(ContentReportService.class);
        points = mock(PointService.class);
        notifications = mock(NotificationService.class);
        media = mock(MediaService.class);
        settings = mock(SystemSettings.class);
        service = new BlogActionService(repository, filter, reports, points, notifications, media, settings,
                Clock.fixed(NOW, ZoneOffset.UTC));
        when(repository.member(MEMBER_ID)).thenReturn(Optional.of(new Member("USER", "ACTIVE",
                NOW.minus(Duration.ofDays(30)))));
        when(repository.lockBlog(BLOG_ID)).thenReturn(Optional.of(blog(MEMBER_ID, "POST", "PUBLISHED")));
        when(repository.commentIds(BLOG_ID)).thenReturn(List.of(801L, 802L, 803L));
        when(repository.mediaIds(BLOG_ID)).thenReturn(List.of(701L, 702L));
    }

    // ---------------------------------------------------------------- helpers

    private static AppUserPrincipal principal(long id, String role) {
        return new AppUserPrincipal(id, "user" + id + "@example.org", "User " + id, role, "/");
    }

    private static BlogRow blog(long authorId, String type, String status) {
        return new BlogRow(BLOG_ID, authorId, "User " + authorId, null, 120, "ACTIVE", type,
                "Kinh nghiệm thuê nhà ở Arakawa", "Nên đi xem nhà vào buổi sáng.", status, false, false, null, 4, 3,
                NOW.minus(Duration.ofDays(1)));
    }

    private AppUserPrincipal staff(String role) {
        when(repository.member(STAFF_ID)).thenReturn(Optional.of(new Member(role, "ACTIVE",
                NOW.minus(Duration.ofDays(300)))));
        return principal(STAFF_ID, role);
    }

    private static void assertApiError(Runnable call, HttpStatus status, String message) {
        Throwable thrown = catchThrowable(call::run);
        assertThat(thrown).isInstanceOf(ApiException.class);
        ApiException e = (ApiException) thrown;
        assertThat(e.getStatus()).isEqualTo(status);
        assertThat(e.getMessage()).isEqualTo(message);
    }

    private void verifyNothingDeleted() {
        verify(repository, never()).deleteBlog(anyLong());
        verify(reports, never()).closeCasesOf(anyString(), any());
        verify(points, never()).withdrawAll(anyLong(), anyString(), anyLong(), anyString());
        verify(media, never()).discard(any());
    }

    // =================================================================================================================
    @Nested
    @TestMethodOrder(MethodOrderer.DisplayName.class)
    @DisplayName("01. deleteBlog – UC 1.7 Xóa bài viết (UTC-312 → UTC-328)")
    class DeletePost {

        @Test
        @DisplayName("UTC-312 Tác giả xóa bài đang đăng của mình: bài bị xóa hẳn")
        void utc312_authorDeletesOwnPost() {
            service.deleteBlog(MEMBER, KIND, BLOG_ID);

            verify(repository).lockBlog(BLOG_ID);
            verify(repository).deleteBlog(BLOG_ID);
        }

        @Test
        @DisplayName("UTC-313 Báo cáo đang mở của các bình luận trong bài và của chính bài được đóng")
        void utc313_openReportCasesClosed() {
            service.deleteBlog(MEMBER, KIND, BLOG_ID);

            verify(reports).closeCasesOf("COMMENT", List.of(801L, 802L, 803L));
            verify(reports).closeCasesOf("BLOG", List.of(BLOG_ID));
        }

        @Test
        @DisplayName("UTC-314 Điểm bài đã nhận được thu lại (lý do CONTENT_DELETED)")
        void utc314_pointsWithdrawn() {
            service.deleteBlog(MEMBER, KIND, BLOG_ID);

            verify(points).withdrawAll(MEMBER_ID, "BLOG", BLOG_ID, "CONTENT_DELETED");
        }

        @Test
        @DisplayName("UTC-315 Ảnh và video của bài bị xóa khỏi kho lưu trữ")
        void utc315_filesDiscarded() {
            service.deleteBlog(MEMBER, KIND, BLOG_ID);

            verify(media).discard(List.of(701L, 702L));
        }

        @Test
        @DisplayName("UTC-316 Thứ tự: khóa bài → đóng báo cáo bình luận → đóng báo cáo bài → thu điểm → xóa tệp → xóa bài")
        void utc316_stepsRunInOrder() {
            service.deleteBlog(MEMBER, KIND, BLOG_ID);

            InOrder order = inOrder(repository, reports, points, media);
            order.verify(repository).member(MEMBER_ID);
            order.verify(repository).lockBlog(BLOG_ID);
            order.verify(reports).closeCasesOf("COMMENT", List.of(801L, 802L, 803L));
            order.verify(reports).closeCasesOf("BLOG", List.of(BLOG_ID));
            order.verify(points).withdrawAll(MEMBER_ID, "BLOG", BLOG_ID, "CONTENT_DELETED");
            order.verify(media).discard(List.of(701L, 702L));
            order.verify(repository).deleteBlog(BLOG_ID);
        }

        @Test
        @DisplayName("UTC-317 Bài không có bình luận, ảnh hay video: vẫn xóa được (danh sách rỗng)")
        void utc317_postWithoutCommentsOrFiles() {
            when(repository.commentIds(BLOG_ID)).thenReturn(List.of());
            when(repository.mediaIds(BLOG_ID)).thenReturn(List.of());

            service.deleteBlog(MEMBER, KIND, BLOG_ID);

            verify(reports).closeCasesOf("COMMENT", List.of());
            verify(media).discard(List.of());
            verify(repository).deleteBlog(BLOG_ID);
        }

        @ParameterizedTest(name = "[{index}] status = {0}")
        @ValueSource(strings = {"PROCESSING", "REJECTED", "HIDDEN"})
        @DisplayName("UTC-318 Bài đang quét, bị từ chối hoặc bị ẩn do báo cáo (chưa bị Moderator gỡ): tác giả vẫn xóa được")
        void utc318_notPublishedButNotRemoved_deleted(String status) {
            when(repository.lockBlog(BLOG_ID)).thenReturn(Optional.of(blog(MEMBER_ID, "POST", status)));

            service.deleteBlog(MEMBER, KIND, BLOG_ID);

            verify(points).withdrawAll(MEMBER_ID, "BLOG", BLOG_ID, "CONTENT_DELETED");
            verify(repository).deleteBlog(BLOG_ID);
        }

        @Test
        @DisplayName("UTC-319 Xóa bài không gửi thông báo cho ai, không cần kiểm tra từ khóa hay cài đặt")
        void utc319_noNotificationNoFilter() {
            service.deleteBlog(MEMBER, KIND, BLOG_ID);

            verifyNoInteractions(notifications, filter, settings);
        }

        @Test
        @DisplayName("UTC-320 Bài đã bị Moderator gỡ (REMOVED): 404 MSG19, không xóa gì")
        void utc320_removedByModerator_notFound() {
            when(repository.lockBlog(BLOG_ID)).thenReturn(Optional.of(blog(MEMBER_ID, "POST", "REMOVED")));

            assertApiError(() -> service.deleteBlog(MEMBER, KIND, BLOG_ID), HttpStatus.NOT_FOUND, MSG19);
            verifyNothingDeleted();
        }

        @Test
        @DisplayName("UTC-321 Mã bài không tồn tại (999): 404 MSG19, không xóa gì")
        void utc321_missingPost_notFound() {
            when(repository.lockBlog(999L)).thenReturn(Optional.empty());

            assertApiError(() -> service.deleteBlog(MEMBER, KIND, 999L), HttpStatus.NOT_FOUND, MSG19);
            verifyNothingDeleted();
        }

        @Test
        @DisplayName("UTC-322 Bấm xóa hai lần cùng lúc: lần thứ hai thấy bài đã mất (404), điểm chỉ bị thu một lần")
        void utc322_secondDelete_notFoundAndPointsTakenOnce() {
            when(repository.lockBlog(BLOG_ID)).thenReturn(Optional.of(blog(MEMBER_ID, "POST", "PUBLISHED")),
                    Optional.empty());

            service.deleteBlog(MEMBER, KIND, BLOG_ID);
            assertApiError(() -> service.deleteBlog(MEMBER, KIND, BLOG_ID), HttpStatus.NOT_FOUND, MSG19);

            verify(points, times(1)).withdrawAll(MEMBER_ID, "BLOG", BLOG_ID, "CONTENT_DELETED");
            verify(repository, times(1)).deleteBlog(BLOG_ID);
        }

        @Test
        @DisplayName("UTC-323 Mã của một câu hỏi (QA) xóa qua trang Blog: 404 MSG19, không xóa gì")
        void utc323_questionIdOnBlog_notFound() {
            when(repository.lockBlog(BLOG_ID)).thenReturn(Optional.of(blog(MEMBER_ID, "QA", "PUBLISHED")));

            assertApiError(() -> service.deleteBlog(MEMBER, KIND, BLOG_ID), HttpStatus.NOT_FOUND, MSG19);
            verifyNothingDeleted();
        }

        @Test
        @DisplayName("UTC-324 Xóa bài của người khác: 403 MSG18, bài, điểm và báo cáo giữ nguyên")
        void utc324_someoneElsesPost_forbidden() {
            when(repository.lockBlog(BLOG_ID)).thenReturn(Optional.of(blog(OTHER_ID, "POST", "PUBLISHED")));

            assertApiError(() -> service.deleteBlog(MEMBER, KIND, BLOG_ID), HttpStatus.FORBIDDEN, MSG18);
            verifyNothingDeleted();
            verify(repository, never()).commentIds(anyLong());
        }

        @Test
        @DisplayName("UTC-325 Khách chưa đăng nhập: 401 \"Bạn cần đăng nhập để thực hiện thao tác này.\", không đọc bài")
        void utc325_guest_unauthorized() {
            assertApiError(() -> service.deleteBlog(null, KIND, BLOG_ID), HttpStatus.UNAUTHORIZED, SIGN_IN);
            verifyNoInteractions(repository, reports, points, media);
        }

        @Test
        @DisplayName("UTC-326 Phiên của tài khoản không còn trong hệ thống: 401 \"Bạn chưa đăng nhập.\"")
        void utc326_accountRowMissing_unauthorized() {
            when(repository.member(MEMBER_ID)).thenReturn(Optional.empty());

            assertApiError(() -> service.deleteBlog(MEMBER, KIND, BLOG_ID), HttpStatus.UNAUTHORIZED, NO_SESSION);
            verify(repository, never()).lockBlog(anyLong());
            verifyNothingDeleted();
        }

        @ParameterizedTest(name = "[{index}] role = {0}")
        @ValueSource(strings = {"MODERATOR", "ACADEMIC_MANAGER", "ADMIN"})
        @DisplayName("UTC-327 Tài khoản nhân viên: 403 MSG18, không khóa hay xóa bài")
        void utc327_staff_forbidden(String role) {
            AppUserPrincipal staff = staff(role);

            assertApiError(() -> service.deleteBlog(staff, KIND, BLOG_ID), HttpStatus.FORBIDDEN, MSG18);
            verify(repository, never()).lockBlog(anyLong());
            verifyNothingDeleted();
        }

        @ParameterizedTest(name = "[{index}] status = {0}")
        @ValueSource(strings = {"LOCKED", "DELETED"})
        @DisplayName("UTC-328 Tài khoản bị khóa hoặc đã xóa: 403 MSG18, không xóa gì")
        void utc328_lockedOrDeletedAccount_forbidden(String status) {
            when(repository.member(MEMBER_ID)).thenReturn(Optional.of(new Member("USER", status,
                    NOW.minus(Duration.ofDays(30)))));

            assertApiError(() -> service.deleteBlog(MEMBER, KIND, BLOG_ID), HttpStatus.FORBIDDEN, MSG18);
            verify(repository, never()).lockBlog(anyLong());
            verifyNothingDeleted();
        }
    }

    // =================================================================================================================
    @Nested
    @TestMethodOrder(MethodOrderer.DisplayName.class)
    @DisplayName("02. BlogDetailRepository.deleteBlog – UC 1.7 Xóa bài viết: câu lệnh xóa (UTC-329 → UTC-329)")
    class DeleteSql {

        @Test
        @DisplayName("UTC-329 Bình luận của bài bị xóa từ mới đến cũ (ORDER BY id DESC) rồi mới xóa bài")
        void utc329_commentsDeletedNewestFirstThenThePost() {
            JdbcTemplate jdbc = mock(JdbcTemplate.class);
            BlogDetailRepository real = new BlogDetailRepository(jdbc);

            real.deleteBlog(BLOG_ID);

            InOrder order = inOrder(jdbc);
            order.verify(jdbc).update("DELETE FROM comments WHERE blog_id = ? ORDER BY id DESC", BLOG_ID);
            order.verify(jdbc).update("DELETE FROM blogs WHERE id = ?", BLOG_ID);
        }
    }
}
