package com.sep490.vsa.feature.blog;

import com.sep490.vsa.common.exception.ApiException;
import com.sep490.vsa.common.security.AppUserPrincipal;
import com.sep490.vsa.common.settings.SystemSettings;
import com.sep490.vsa.feature.blog.BlogDetailDtos.ReportRequest;
import com.sep490.vsa.feature.blog.BlogDetailRepository.BlogRow;
import com.sep490.vsa.feature.blog.BlogDetailRepository.CommentRow;
import com.sep490.vsa.feature.blog.BlogDetailRepository.Member;
import com.sep490.vsa.feature.media.MediaService;
import com.sep490.vsa.feature.moderation.ContentFilter;
import com.sep490.vsa.feature.notification.NotificationService;
import com.sep490.vsa.feature.report.ContentReportService;
import com.sep490.vsa.feature.report.ContentReportService.Outcome;
import com.sep490.vsa.feature.report.ContentReportService.Target;
import com.sep490.vsa.feature.user.PointService;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
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

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit tests of UC 1.11 "Submit Content Report" (Gửi báo cáo nội dung) on the Blog and Hỏi-Đáp side
 * ({@link BlogActionService#reportBlog} for posts and questions, {@link BlogActionService#reportComment} for comments,
 * replies and answers), written from CLAUDE.md ("Key business rules": 1–5 reports → "Cần xử lý", more than 5 →
 * auto-hidden and "Khẩn"; "Post Detail (UC-10)"; posts, questions and comments of locked or deleted accounts are
 * hidden) and the use case test {@code usecase/report/SubmitContentReportTest}.
 * <p>
 * The database is not used: {@link BlogDetailRepository}, {@link ContentReportService} (which counts the report and
 * decides whether to hide), {@link ContentFilter}, {@link PointService}, {@link NotificationService},
 * {@link MediaService} and {@link SystemSettings} are mocks; the clock is fixed at 20/11/2026 12:00 Japan time.
 */
@TestClassOrder(ClassOrderer.DisplayName.class)
@DisplayName("BlogActionService – Gửi báo cáo bài viết, câu hỏi, bình luận (UC 1.11)")
class BlogActionServiceReportTest {

    private static final long MEMBER_ID = 10L;
    private static final long AUTHOR_ID = 11L;
    private static final long WRITER_ID = 12L;
    private static final long POST_ID = 300L;
    private static final long QUESTION_ID = 301L;
    private static final long COMMENT_ID = 700L;
    private static final long ANSWER_ID = 701L;
    private static final long REPLY_ID = 702L;
    /** 12:00 in Japan, 20/11/2026. */
    private static final Instant NOW = Instant.parse("2026-11-20T03:00:00Z");

    private static final AppUserPrincipal MEMBER = principal(MEMBER_ID, "USER");

    private static final String MSG_LOGIN = "Bạn cần đăng nhập để thực hiện thao tác này.";
    private static final String MSG_NOT_SIGNED_IN = "Bạn chưa đăng nhập.";
    private static final String MSG18 = "Bạn không có quyền thực hiện thao tác này.";
    private static final String MSG19 = "Không tìm thấy nội dung bạn yêu cầu. Bài viết có thể đã bị gỡ hoặc đã bị xóa.";
    private static final String COMMENT_GONE = "Không tìm thấy bình luận này.";
    private static final String OWN_CONTENT = "Bạn không thể báo cáo nội dung của chính mình.";
    private static final String MSG39 = "Bạn đã báo cáo nội dung này rồi.";
    private static final String MSG40 = "Hãy chọn lý do báo cáo.";
    private static final String REPORTING_LOCKED = "Bạn bị khóa báo cáo đến 27/11/2026 vì có quá nhiều báo cáo sai.";

    private static ValidatorFactory validatorFactory;
    private static Validator validator;

    private BlogDetailRepository repository;
    private ContentFilter filter;
    private ContentReportService reports;
    private PointService points;
    private NotificationService notifications;
    private MediaService media;
    private SystemSettings settings;
    private BlogActionService service;

    @BeforeAll
    static void startValidator() {
        validatorFactory = Validation.buildDefaultValidatorFactory();
        validator = validatorFactory.getValidator();
    }

    @AfterAll
    static void closeValidator() {
        validatorFactory.close();
    }

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

        account(MEMBER_ID, "USER", "ACTIVE");
        when(repository.lockBlog(POST_ID)).thenReturn(Optional.of(blog(POST_ID, "POST", "PUBLISHED", "ACTIVE")));
        when(repository.lockBlog(QUESTION_ID)).thenReturn(Optional.of(blog(QUESTION_ID, "QA", "PUBLISHED", "ACTIVE")));
        comment(COMMENT_ID, POST_ID, null, "PUBLISHED", "ACTIVE");
        comment(ANSWER_ID, QUESTION_ID, null, "PUBLISHED", "ACTIVE");
        comment(REPLY_ID, POST_ID, COMMENT_ID, "PUBLISHED", "ACTIVE");
        when(reports.report(any(), anyLong(), any(), any())).thenReturn(new Outcome(1, false));
    }

    // ---------------------------------------------------------------- helpers

    private static AppUserPrincipal principal(long id, String role) {
        return new AppUserPrincipal(id, "user" + id + "@example.org", "User " + id, role, "/");
    }

    private void account(long id, String role, String status) {
        when(repository.member(id)).thenReturn(Optional.of(new Member(role, status, NOW.minus(Duration.ofDays(30)))));
    }

    private static BlogRow blog(long id, String type, String status, String authorStatus) {
        return new BlogRow(id, AUTHOR_ID, "Tác giả", null, 120, authorStatus, type,
                "QA".equals(type) ? "Làm thẻ ngân hàng ở đâu?" : "Cho thuê nhà giá rẻ", "Nội dung", status, false,
                false, null, 3, 2, NOW.minus(Duration.ofDays(2)));
    }

    private static CommentRow commentRow(long id, long blogId, Long parentId, String status, String authorStatus) {
        return new CommentRow(id, blogId, parentId, WRITER_ID, "Người bình luận", null, 50, authorStatus,
                "Bình luận có lời lẽ khó nghe", status, false, 0, NOW.minus(Duration.ofHours(3)));
    }

    private void comment(long id, long blogId, Long parentId, String status, String authorStatus) {
        CommentRow row = commentRow(id, blogId, parentId, status, authorStatus);
        when(repository.findComment(id)).thenReturn(Optional.of(row));
        when(repository.lockComment(id)).thenReturn(Optional.of(row));
    }

    private AppUserPrincipal staff(String role) {
        long id = switch (role) {
            case "MODERATOR" -> 20L;
            case "ACADEMIC_MANAGER" -> 30L;
            default -> 1L;
        };
        account(id, role, "ACTIVE");
        return principal(id, role);
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

    private void reportFails(ApiException error) {
        when(reports.report(any(), anyLong(), any(), any())).thenThrow(error);
    }

    private void verifyNoReport() {
        verifyNoInteractions(reports);
        verify(repository, never()).hideBlog(anyLong());
        verify(repository, never()).hideComment(anyLong());
    }

    private void verifyNothingHidden() {
        verify(repository, never()).hideBlog(anyLong());
        verify(repository, never()).hideComment(anyLong());
        verify(repository, never()).recountComments(anyLong());
    }

    // =================================================================================================================
    @Nested
    @TestMethodOrder(MethodOrderer.DisplayName.class)
    @DisplayName("01. reportBlog – UC 1.11 Báo cáo bài viết, câu hỏi (UTC-001 → UTC-025)")
    class ReportBlog {

        @Test
        @DisplayName("UTC-001 Báo cáo bài viết có lý do và ghi chú: gửi báo cáo loại BLOG")
        void utc001_memberReportsPost_reportSentWithBlogTarget() {
            service.reportBlog(MEMBER, "POST", POST_ID, new ReportRequest(1L, "Đòi chuyển cọc trước khi xem nhà"));

            verify(reports).report(new Target("BLOG", POST_ID, AUTHOR_ID), MEMBER_ID, 1L,
                    "Đòi chuyển cọc trước khi xem nhà");
            verifyNothingHidden();
        }

        @Test
        @DisplayName("UTC-002 Báo cáo câu hỏi trên Hỏi-Đáp: hồ sơ vẫn là loại BLOG")
        void utc002_memberReportsQuestion_caseTypeIsBlog() {
            service.reportBlog(MEMBER, "QA", QUESTION_ID, new ReportRequest(4L, null));

            verify(reports).report(new Target("BLOG", QUESTION_ID, AUTHOR_ID), MEMBER_ID, 4L, null);
            verifyNothingHidden();
        }

        @Test
        @DisplayName("UTC-003 Ghi chú được chuyển nguyên văn cho bước ghi báo cáo")
        void utc003_noteIsPassedUnchanged() {
            service.reportBlog(MEMBER, "POST", POST_ID, new ReportRequest(2L, "  Ảnh lấy từ trang khác  "));

            verify(reports).report(new Target("BLOG", POST_ID, AUTHOR_ID), MEMBER_ID, 2L, "  Ảnh lấy từ trang khác  ");
        }

        @Test
        @DisplayName("UTC-004 Báo cáo không có ghi chú: vẫn gửi được")
        void utc004_reportWithoutNote_nullNotePassed() {
            service.reportBlog(MEMBER, "POST", POST_ID, new ReportRequest(3L, null));

            verify(reports).report(any(Target.class), anyLong(), any(), isNull());
        }

        @Test
        @DisplayName("UTC-005 Khóa dòng bài viết trước khi ghi báo cáo")
        void utc005_postRowLockedBeforeReport() {
            service.reportBlog(MEMBER, "POST", POST_ID, new ReportRequest(1L, null));

            InOrder order = inOrder(repository, reports);
            order.verify(repository).member(MEMBER_ID);
            order.verify(repository).lockBlog(POST_ID);
            order.verify(reports).report(new Target("BLOG", POST_ID, AUTHOR_ID), MEMBER_ID, 1L, null);
            verify(repository, never()).findBlog(anyLong());
        }

        @Test
        @DisplayName("UTC-006 Báo cáo thứ 5: bài vẫn hiển thị")
        void utc006_fifthReport_postStaysPublished() {
            when(reports.report(any(), anyLong(), any(), any())).thenReturn(new Outcome(5, false));

            service.reportBlog(MEMBER, "POST", POST_ID, new ReportRequest(1L, null));

            verify(repository, never()).hideBlog(anyLong());
        }

        @Test
        @DisplayName("UTC-007 Báo cáo thứ 6: bài viết tự ẩn")
        void utc007_sixthReport_postHidden() {
            when(reports.report(any(), anyLong(), any(), any())).thenReturn(new Outcome(6, true));

            service.reportBlog(MEMBER, "POST", POST_ID, new ReportRequest(1L, null));

            InOrder order = inOrder(repository, reports);
            order.verify(repository).lockBlog(POST_ID);
            order.verify(reports).report(any(), anyLong(), any(), any());
            order.verify(repository).hideBlog(POST_ID);
        }

        @Test
        @DisplayName("UTC-008 Câu hỏi vượt ngưỡng báo cáo: câu hỏi tự ẩn")
        void utc008_questionOverThreshold_questionHidden() {
            when(reports.report(any(), anyLong(), any(), any())).thenReturn(new Outcome(9, true));

            service.reportBlog(MEMBER, "QA", QUESTION_ID, new ReportRequest(4L, null));

            verify(repository).hideBlog(QUESTION_ID);
            verify(repository, never()).hideBlog(POST_ID);
        }

        @Test
        @DisplayName("UTC-009 Báo cáo câu hỏi qua đường dẫn Blog: không tìm thấy")
        void utc009_questionThroughBlogAddress_404() {
            assertApiError(() -> service.reportBlog(MEMBER, "POST", QUESTION_ID, new ReportRequest(1L, null)),
                    HttpStatus.NOT_FOUND, MSG19);
            verifyNoReport();
        }

        @Test
        @DisplayName("UTC-010 Báo cáo bài viết qua đường dẫn Hỏi-Đáp: không tìm thấy")
        void utc010_postThroughQuestionAddress_404() {
            assertApiError(() -> service.reportBlog(MEMBER, "QA", POST_ID, new ReportRequest(1L, null)),
                    HttpStatus.NOT_FOUND, MSG19);
            verifyNoReport();
        }

        @Test
        @DisplayName("UTC-011 Bài viết không tồn tại: không tìm thấy")
        void utc011_missingPost_404() {
            when(repository.lockBlog(999_999L)).thenReturn(Optional.empty());

            assertApiError(() -> service.reportBlog(MEMBER, "POST", 999_999L, new ReportRequest(1L, null)),
                    HttpStatus.NOT_FOUND, MSG19);
            verifyNoReport();
        }

        @ParameterizedTest(name = "[{index}] trạng thái {0}")
        @ValueSource(strings = {"PROCESSING", "HIDDEN", "REMOVED", "REJECTED"})
        @DisplayName("UTC-012 Bài chưa đăng, bị ẩn, bị gỡ, bị từ chối: không tìm thấy")
        void utc012_postNotPublished_404(String status) {
            when(repository.lockBlog(POST_ID)).thenReturn(Optional.of(blog(POST_ID, "POST", status, "ACTIVE")));

            assertApiError(() -> service.reportBlog(MEMBER, "POST", POST_ID, new ReportRequest(1L, null)),
                    HttpStatus.NOT_FOUND, MSG19);
            verifyNoReport();
        }

        @ParameterizedTest(name = "[{index}] tác giả {0}")
        @ValueSource(strings = {"LOCKED", "DELETED"})
        @DisplayName("UTC-013 Bài của tài khoản bị khóa hoặc đã xóa: không tìm thấy")
        void utc013_postOfLockedOrDeletedAuthor_404(String authorStatus) {
            when(repository.lockBlog(POST_ID)).thenReturn(Optional.of(blog(POST_ID, "POST", "PUBLISHED", authorStatus)));

            assertApiError(() -> service.reportBlog(MEMBER, "POST", POST_ID, new ReportRequest(1L, null)),
                    HttpStatus.NOT_FOUND, MSG19);
            verifyNoReport();
        }

        @Test
        @DisplayName("UTC-014 Khách báo cáo: phải đăng nhập")
        void utc014_guestReports_401() {
            assertApiError(() -> service.reportBlog(null, "POST", POST_ID, new ReportRequest(1L, null)),
                    HttpStatus.UNAUTHORIZED, MSG_LOGIN);
            verifyNoInteractions(repository);
            verifyNoReport();
        }

        @ParameterizedTest(name = "[{index}] vai trò {0}")
        @ValueSource(strings = {"MODERATOR", "ACADEMIC_MANAGER", "ADMIN"})
        @DisplayName("UTC-015 Tài khoản nhân viên báo cáo: bị từ chối")
        void utc015_staffReports_403(String role) {
            AppUserPrincipal staff = staff(role);

            assertApiError(() -> service.reportBlog(staff, "POST", POST_ID, new ReportRequest(1L, null)),
                    HttpStatus.FORBIDDEN, MSG18);
            verify(repository, never()).lockBlog(anyLong());
            verifyNoReport();
        }

        @Test
        @DisplayName("UTC-016 Tài khoản bị khóa báo cáo: bị từ chối")
        void utc016_lockedMemberReports_403() {
            account(MEMBER_ID, "USER", "LOCKED");

            assertApiError(() -> service.reportBlog(MEMBER, "POST", POST_ID, new ReportRequest(1L, null)),
                    HttpStatus.FORBIDDEN, MSG18);
            verifyNoReport();
        }

        @Test
        @DisplayName("UTC-017 Tài khoản đã xóa báo cáo: bị từ chối")
        void utc017_deletedMemberReports_403() {
            account(MEMBER_ID, "USER", "DELETED");

            assertApiError(() -> service.reportBlog(MEMBER, "POST", POST_ID, new ReportRequest(1L, null)),
                    HttpStatus.FORBIDDEN, MSG18);
            verifyNoReport();
        }

        @Test
        @DisplayName("UTC-018 Tài khoản không còn trong hệ thống: phải đăng nhập")
        void utc018_accountGone_401() {
            when(repository.member(MEMBER_ID)).thenReturn(Optional.empty());

            assertApiError(() -> service.reportBlog(MEMBER, "POST", POST_ID, new ReportRequest(1L, null)),
                    HttpStatus.UNAUTHORIZED, MSG_NOT_SIGNED_IN);
            verifyNoReport();
        }

        @Test
        @DisplayName("UTC-019 Báo cáo bài của chính mình: bị từ chối, bài không bị ẩn")
        void utc019_ownPost_400() {
            AppUserPrincipal author = principal(AUTHOR_ID, "USER");
            account(AUTHOR_ID, "USER", "ACTIVE");
            reportFails(ApiException.badRequest(OWN_CONTENT));

            assertApiError(() -> service.reportBlog(author, "POST", POST_ID, new ReportRequest(1L, null)),
                    HttpStatus.BAD_REQUEST, OWN_CONTENT);
            verify(reports).report(new Target("BLOG", POST_ID, AUTHOR_ID), AUTHOR_ID, 1L, null);
            verifyNothingHidden();
        }

        @Test
        @DisplayName("UTC-020 Báo cáo lần hai cùng bài: báo đã báo cáo rồi")
        void utc020_reportTwice_409() {
            reportFails(ApiException.conflict(MSG39));

            assertApiError(() -> service.reportBlog(MEMBER, "POST", POST_ID, new ReportRequest(2L, null)),
                    HttpStatus.CONFLICT, MSG39);
            verifyNothingHidden();
        }

        @Test
        @DisplayName("UTC-021 Lý do không thuộc danh sách báo cáo: yêu cầu chọn lý do")
        void utc021_reasonNotOffered_400() {
            reportFails(ApiException.badRequest(MSG40));

            assertApiError(() -> service.reportBlog(MEMBER, "POST", POST_ID, new ReportRequest(987_654_321L, null)),
                    HttpStatus.BAD_REQUEST, MSG40);
            verify(reports).report(new Target("BLOG", POST_ID, AUTHOR_ID), MEMBER_ID, 987_654_321L, null);
            verifyNothingHidden();
        }

        @Test
        @DisplayName("UTC-022 Thành viên đang bị khóa báo cáo: bị từ chối")
        void utc022_reportingLocked_403() {
            reportFails(new ApiException(HttpStatus.FORBIDDEN, REPORTING_LOCKED));

            assertApiError(() -> service.reportBlog(MEMBER, "POST", POST_ID, new ReportRequest(1L, null)),
                    HttpStatus.FORBIDDEN, REPORTING_LOCKED);
            verifyNothingHidden();
        }

        @Test
        @DisplayName("UTC-023 Gửi báo cáo không chọn lý do: báo \"Hãy chọn lý do báo cáo.\"")
        void utc023_requestWithoutReason_validationMessage() {
            assertThat(validator.validate(new ReportRequest(null, "Không chọn lý do")))
                    .extracting(ConstraintViolation::getMessage)
                    .containsExactly(MSG40);
            assertThat(validator.validate(new ReportRequest(null, "x")))
                    .extracting(v -> v.getPropertyPath().toString())
                    .containsExactly("reasonId");
        }

        @Test
        @DisplayName("UTC-024 Gửi báo cáo có lý do, không ghi chú: hợp lệ")
        void utc024_requestWithReasonOnly_valid() {
            assertThat(validator.validate(new ReportRequest(1L, null))).isEmpty();
        }

        @Test
        @DisplayName("UTC-025 Báo cáo không thích, không lưu, không cộng trừ điểm, không gửi thông báo")
        void utc025_reportHasNoOtherSideEffects() {
            service.reportBlog(MEMBER, "POST", POST_ID, new ReportRequest(1L, "Spam"));

            verify(repository, never()).setBlogLike(anyLong(), anyLong(), org.mockito.ArgumentMatchers.anyBoolean(),
                    any());
            verify(repository, never()).setSaved(anyLong(), anyLong(), org.mockito.ArgumentMatchers.anyBoolean(),
                    any());
            verifyNoInteractions(points, notifications, media, filter);
        }
    }

    // =================================================================================================================
    @Nested
    @TestMethodOrder(MethodOrderer.DisplayName.class)
    @DisplayName("02. reportComment – UC 1.11 Báo cáo bình luận, câu trả lời (UTC-026 → UTC-048)")
    class ReportComment {

        @Test
        @DisplayName("UTC-026 Báo cáo bình luận: gửi báo cáo loại COMMENT, trả lại bài viết")
        void utc026_memberReportsComment_commentTargetAndPostReturned() {
            long blogId = service.reportComment(MEMBER, COMMENT_ID, new ReportRequest(3L, null));

            assertThat(blogId).isEqualTo(POST_ID);
            verify(reports).report(new Target("COMMENT", COMMENT_ID, WRITER_ID), MEMBER_ID, 3L, null);
            verifyNothingHidden();
        }

        @Test
        @DisplayName("UTC-027 Báo cáo câu trả lời của câu hỏi: loại COMMENT, trả lại câu hỏi")
        void utc027_memberReportsAnswer_questionReturned() {
            long blogId = service.reportComment(MEMBER, ANSWER_ID, new ReportRequest(2L, "Yucho cần thẻ cư trú"));

            assertThat(blogId).isEqualTo(QUESTION_ID);
            verify(repository).lockBlog(QUESTION_ID);
            verify(reports).report(new Target("COMMENT", ANSWER_ID, WRITER_ID), MEMBER_ID, 2L, "Yucho cần thẻ cư trú");
        }

        @Test
        @DisplayName("UTC-028 Báo cáo một phản hồi trong bình luận: loại COMMENT")
        void utc028_memberReportsReply_commentTarget() {
            long blogId = service.reportComment(MEMBER, REPLY_ID, new ReportRequest(4L, null));

            assertThat(blogId).isEqualTo(POST_ID);
            verify(reports).report(new Target("COMMENT", REPLY_ID, WRITER_ID), MEMBER_ID, 4L, null);
        }

        @Test
        @DisplayName("UTC-029 Khóa dòng bài viết rồi dòng bình luận trước khi ghi báo cáo")
        void utc029_postThenCommentLockedBeforeReport() {
            service.reportComment(MEMBER, COMMENT_ID, new ReportRequest(3L, null));

            InOrder order = inOrder(repository, reports);
            order.verify(repository).member(MEMBER_ID);
            order.verify(repository).findComment(COMMENT_ID);
            order.verify(repository).lockBlog(POST_ID);
            order.verify(repository).lockComment(COMMENT_ID);
            order.verify(reports).report(any(), anyLong(), any(), any());
        }

        @Test
        @DisplayName("UTC-030 Ghi chú được chuyển nguyên văn cho bước ghi báo cáo")
        void utc030_noteIsPassedUnchanged() {
            service.reportComment(MEMBER, COMMENT_ID, new ReportRequest(3L, "  Lời lẽ xúc phạm  "));

            verify(reports).report(new Target("COMMENT", COMMENT_ID, WRITER_ID), MEMBER_ID, 3L, "  Lời lẽ xúc phạm  ");
        }

        @Test
        @DisplayName("UTC-031 Báo cáo thứ 5: bình luận vẫn hiển thị")
        void utc031_fifthReport_commentStaysPublished() {
            when(reports.report(any(), anyLong(), any(), any())).thenReturn(new Outcome(5, false));

            service.reportComment(MEMBER, COMMENT_ID, new ReportRequest(3L, null));

            verify(repository, never()).hideComment(anyLong());
            verify(repository, never()).recountComments(anyLong());
        }

        @Test
        @DisplayName("UTC-032 Báo cáo thứ 6: bình luận tự ẩn, đếm lại số bình luận của bài")
        void utc032_sixthReport_commentHiddenAndRecounted() {
            when(reports.report(any(), anyLong(), any(), any())).thenReturn(new Outcome(6, true));
            when(repository.hideComment(COMMENT_ID)).thenReturn(true);

            long blogId = service.reportComment(MEMBER, COMMENT_ID, new ReportRequest(3L, null));

            assertThat(blogId).isEqualTo(POST_ID);
            InOrder order = inOrder(repository, reports);
            order.verify(reports).report(any(), anyLong(), any(), any());
            order.verify(repository).hideComment(COMMENT_ID);
            order.verify(repository).recountComments(POST_ID);
            verify(repository, never()).hideBlog(anyLong());
        }

        @Test
        @DisplayName("UTC-033 Vượt ngưỡng nhưng bình luận không còn ở trạng thái đã đăng: không đếm lại")
        void utc033_overThresholdButNotHiddenNow_noRecount() {
            when(reports.report(any(), anyLong(), any(), any())).thenReturn(new Outcome(7, true));
            when(repository.hideComment(COMMENT_ID)).thenReturn(false);

            service.reportComment(MEMBER, COMMENT_ID, new ReportRequest(3L, null));

            verify(repository).hideComment(COMMENT_ID);
            verify(repository, never()).recountComments(anyLong());
        }

        @Test
        @DisplayName("UTC-034 Khách báo cáo bình luận: phải đăng nhập")
        void utc034_guestReportsComment_401() {
            assertApiError(() -> service.reportComment(null, COMMENT_ID, new ReportRequest(3L, null)),
                    HttpStatus.UNAUTHORIZED, MSG_LOGIN);
            verifyNoInteractions(repository);
            verifyNoReport();
        }

        @ParameterizedTest(name = "[{index}] vai trò {0}")
        @ValueSource(strings = {"MODERATOR", "ACADEMIC_MANAGER", "ADMIN"})
        @DisplayName("UTC-035 Tài khoản nhân viên báo cáo bình luận: bị từ chối")
        void utc035_staffReportsComment_403(String role) {
            AppUserPrincipal staff = staff(role);

            assertApiError(() -> service.reportComment(staff, COMMENT_ID, new ReportRequest(3L, null)),
                    HttpStatus.FORBIDDEN, MSG18);
            verify(repository, never()).findComment(anyLong());
            verifyNoReport();
        }

        @Test
        @DisplayName("UTC-036 Tài khoản bị khóa báo cáo bình luận: bị từ chối")
        void utc036_lockedMemberReportsComment_403() {
            account(MEMBER_ID, "USER", "LOCKED");

            assertApiError(() -> service.reportComment(MEMBER, COMMENT_ID, new ReportRequest(3L, null)),
                    HttpStatus.FORBIDDEN, MSG18);
            verifyNoReport();
        }

        @Test
        @DisplayName("UTC-037 Bình luận không tồn tại: không tìm thấy bình luận")
        void utc037_missingComment_404() {
            when(repository.findComment(888_888L)).thenReturn(Optional.empty());

            assertApiError(() -> service.reportComment(MEMBER, 888_888L, new ReportRequest(3L, null)),
                    HttpStatus.NOT_FOUND, COMMENT_GONE);
            verify(repository, never()).lockBlog(anyLong());
            verifyNoReport();
        }

        @ParameterizedTest(name = "[{index}] trạng thái {0}")
        @ValueSource(strings = {"HIDDEN", "REMOVED", "PROCESSING"})
        @DisplayName("UTC-038 Bình luận bị ẩn, bị gỡ hoặc chưa đăng: không tìm thấy bình luận")
        void utc038_commentNotPublished_404(String status) {
            comment(COMMENT_ID, POST_ID, null, status, "ACTIVE");

            assertApiError(() -> service.reportComment(MEMBER, COMMENT_ID, new ReportRequest(3L, null)),
                    HttpStatus.NOT_FOUND, COMMENT_GONE);
            verify(repository, never()).lockBlog(anyLong());
            verifyNoReport();
        }

        @ParameterizedTest(name = "[{index}] người viết {0}")
        @ValueSource(strings = {"LOCKED", "DELETED"})
        @DisplayName("UTC-039 Bình luận của tài khoản bị khóa hoặc đã xóa: không tìm thấy bình luận")
        void utc039_commentOfLockedOrDeletedWriter_404(String writerStatus) {
            comment(COMMENT_ID, POST_ID, null, "PUBLISHED", writerStatus);

            assertApiError(() -> service.reportComment(MEMBER, COMMENT_ID, new ReportRequest(3L, null)),
                    HttpStatus.NOT_FOUND, COMMENT_GONE);
            verifyNoReport();
        }

        @Test
        @DisplayName("UTC-040 Bình luận của bài đã bị ẩn: không tìm thấy bài viết")
        void utc040_commentOfHiddenPost_404() {
            when(repository.lockBlog(POST_ID)).thenReturn(Optional.of(blog(POST_ID, "POST", "HIDDEN", "ACTIVE")));

            assertApiError(() -> service.reportComment(MEMBER, COMMENT_ID, new ReportRequest(3L, null)),
                    HttpStatus.NOT_FOUND, MSG19);
            verify(repository, never()).lockComment(anyLong());
            verifyNoReport();
        }

        @Test
        @DisplayName("UTC-041 Bình luận trong bài của tài khoản bị khóa: không tìm thấy bài viết")
        void utc041_commentOfPostByLockedAuthor_404() {
            when(repository.lockBlog(POST_ID)).thenReturn(Optional.of(blog(POST_ID, "POST", "PUBLISHED", "LOCKED")));

            assertApiError(() -> service.reportComment(MEMBER, COMMENT_ID, new ReportRequest(3L, null)),
                    HttpStatus.NOT_FOUND, MSG19);
            verifyNoReport();
        }

        @Test
        @DisplayName("UTC-042 Bài chứa bình luận đã bị xóa: không tìm thấy bài viết")
        void utc042_postOfCommentGone_404() {
            when(repository.lockBlog(POST_ID)).thenReturn(Optional.empty());

            assertApiError(() -> service.reportComment(MEMBER, COMMENT_ID, new ReportRequest(3L, null)),
                    HttpStatus.NOT_FOUND, MSG19);
            verifyNoReport();
        }

        @Test
        @DisplayName("UTC-043 Bình luận vừa bị ẩn trong lúc chờ khóa: không tìm thấy bình luận")
        void utc043_commentHiddenWhileWaitingForLock_404() {
            when(repository.lockComment(COMMENT_ID))
                    .thenReturn(Optional.of(commentRow(COMMENT_ID, POST_ID, null, "HIDDEN", "ACTIVE")));

            assertApiError(() -> service.reportComment(MEMBER, COMMENT_ID, new ReportRequest(3L, null)),
                    HttpStatus.NOT_FOUND, COMMENT_GONE);
            verifyNoReport();
        }

        @Test
        @DisplayName("UTC-044 Bình luận vừa bị xóa trong lúc chờ khóa: không tìm thấy bình luận")
        void utc044_commentDeletedWhileWaitingForLock_404() {
            when(repository.lockComment(COMMENT_ID)).thenReturn(Optional.empty());

            assertApiError(() -> service.reportComment(MEMBER, COMMENT_ID, new ReportRequest(3L, null)),
                    HttpStatus.NOT_FOUND, COMMENT_GONE);
            verifyNoReport();
        }

        @Test
        @DisplayName("UTC-045 Báo cáo bình luận của chính mình: bị từ chối")
        void utc045_ownComment_400() {
            AppUserPrincipal writer = principal(WRITER_ID, "USER");
            account(WRITER_ID, "USER", "ACTIVE");
            reportFails(ApiException.badRequest(OWN_CONTENT));

            assertApiError(() -> service.reportComment(writer, COMMENT_ID, new ReportRequest(3L, null)),
                    HttpStatus.BAD_REQUEST, OWN_CONTENT);
            verify(reports).report(new Target("COMMENT", COMMENT_ID, WRITER_ID), WRITER_ID, 3L, null);
            verifyNothingHidden();
        }

        @Test
        @DisplayName("UTC-046 Báo cáo lần hai cùng bình luận: báo đã báo cáo rồi")
        void utc046_reportCommentTwice_409() {
            reportFails(ApiException.conflict(MSG39));

            assertApiError(() -> service.reportComment(MEMBER, COMMENT_ID, new ReportRequest(3L, null)),
                    HttpStatus.CONFLICT, MSG39);
            verifyNothingHidden();
        }

        @Test
        @DisplayName("UTC-047 Thành viên đang bị khóa báo cáo báo cáo bình luận: bị từ chối")
        void utc047_reportingLockedOnComment_403() {
            reportFails(new ApiException(HttpStatus.FORBIDDEN, REPORTING_LOCKED));

            assertApiError(() -> service.reportComment(MEMBER, COMMENT_ID, new ReportRequest(3L, null)),
                    HttpStatus.FORBIDDEN, REPORTING_LOCKED);
            verifyNothingHidden();
        }

        @Test
        @DisplayName("UTC-048 Báo cáo bình luận không có lý do: yêu cầu chọn lý do")
        void utc048_commentReportWithoutReason_400() {
            reportFails(ApiException.badRequest(MSG40));

            assertApiError(() -> service.reportComment(MEMBER, COMMENT_ID, new ReportRequest(null, "ghi chú")),
                    HttpStatus.BAD_REQUEST, MSG40);
            verify(reports).report(new Target("COMMENT", COMMENT_ID, WRITER_ID), MEMBER_ID, null, "ghi chú");
            verifyNothingHidden();
        }
    }
}
