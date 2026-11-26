package com.sep490.vsa.feature.blog;

import com.sep490.vsa.common.dto.ApiResponse;
import com.sep490.vsa.common.exception.ApiException;
import com.sep490.vsa.common.security.AppUserPrincipal;
import com.sep490.vsa.common.settings.SystemSettings;
import com.sep490.vsa.feature.blog.BlogDetailRepository.BlogRow;
import com.sep490.vsa.feature.blog.BlogDetailRepository.Member;
import com.sep490.vsa.feature.media.MediaService;
import com.sep490.vsa.feature.moderation.ContentFilter;
import com.sep490.vsa.feature.notification.NotificationService;
import com.sep490.vsa.feature.qa.QaController;
import com.sep490.vsa.feature.qa.QaService;
import com.sep490.vsa.feature.report.ContentReportService;
import com.sep490.vsa.feature.user.PointService;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
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
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit tests of "Xóa câu hỏi" in Hỏi-Đáp: {@link BlogActionService#deleteBlog} called with kind QA (as
 * {@link QaController} does for {@code DELETE /api/questions/{id}}), and the answer message of
 * {@link QaController#delete}. Written from the use case UC 1.16 Delete Question (SRS Report 3, II.1.13) and the
 * rules recorded in CLAUDE.md ("Blog feed – Post Detail", "Hỏi-Đáp"): the asker's own question only, hard delete of the
 * question with its answers and replies, the points the question earned are taken back (the +5 of a best answer is
 * not), open report cases are closed, the files are discarded, an id of the other kind answers 404 (MSG19).
 * <p>
 * The database is not used: {@link BlogDetailRepository}, {@link ContentFilter}, {@link ContentReportService},
 * {@link PointService}, {@link NotificationService}, {@link MediaService} and {@link SystemSettings} are mocks; the
 * clock is fixed at 20/11/2026 12:00 Japan time. {@code @Transactional} is not active (no Spring proxy).
 */
@TestClassOrder(ClassOrderer.DisplayName.class)
@DisplayName("BlogActionService – Hỏi-Đáp: xóa câu hỏi (UC 1.16)")
class BlogActionServiceDeleteQuestionTest {

    private static final long ASKER_ID = 10L;
    private static final long HELPER_ID = 12L;
    private static final long STRANGER_ID = 13L;
    private static final long STAFF_ID = 20L;
    private static final long QUESTION_ID = 700L;
    private static final long BEST_ANSWER_ID = 801L;
    private static final String QA = "QA";
    /** 12:00 in Japan: the Japan date is 20/11/2026. */
    private static final Instant NOW = Instant.parse("2026-11-20T03:00:00Z");

    private static final AppUserPrincipal ASKER = principal(ASKER_ID, "USER");

    private static final String MSG18 = "Bạn không có quyền thực hiện thao tác này.";
    private static final String MSG19 =
            "Không tìm thấy nội dung bạn yêu cầu. Bài viết có thể đã bị gỡ hoặc đã bị xóa.";
    private static final String LOGIN_REQUIRED = "Bạn cần đăng nhập để thực hiện thao tác này.";
    private static final String NOT_SIGNED_IN = "Bạn chưa đăng nhập.";
    private static final String DELETED = "Đã xóa câu hỏi.";

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

        member(ASKER_ID, "USER", "ACTIVE");
        member(HELPER_ID, "USER", "ACTIVE");
        member(STRANGER_ID, "USER", "ACTIVE");
        question(ASKER_ID, QA, "PUBLISHED", BEST_ANSWER_ID);
        when(repository.commentIds(QUESTION_ID)).thenReturn(List.of(801L, 802L, 803L));
        when(repository.mediaIds(QUESTION_ID)).thenReturn(List.of(401L, 402L));
    }

    // ---------------------------------------------------------------- helpers

    private static AppUserPrincipal principal(long id, String role) {
        return new AppUserPrincipal(id, "user" + id + "@example.org", "User " + id, role, "/");
    }

    private void member(long id, String role, String status) {
        when(repository.member(id)).thenReturn(Optional.of(new Member(role, status, NOW.minus(Duration.ofDays(30)))));
    }

    /** Question 700 as the locked read sees it. */
    private void question(long authorId, String type, String status, Long bestCommentId) {
        when(repository.lockBlog(QUESTION_ID)).thenReturn(Optional.of(new BlogRow(QUESTION_ID, authorId,
                "User " + authorId, null, 10, "ACTIVE", type, "Gia hạn visa du học cần giấy tờ gì?",
                "Mình ở Arakawa.", status, false, false, bestCommentId, 2, 3, NOW.minus(Duration.ofDays(1)))));
    }

    private static ApiException apiError(ThrowingCallable call, HttpStatus status, String message) {
        Throwable thrown = catchThrowable(call);
        assertThat(thrown).isInstanceOf(ApiException.class).hasMessage(message);
        ApiException error = (ApiException) thrown;
        assertThat(error.getStatus()).isEqualTo(status);
        return error;
    }

    /** Nothing was deleted, closed, withdrawn or discarded. */
    private void assertNothingDeleted() {
        verify(repository, never()).deleteBlog(anyLong());
        verify(reports, never()).closeCasesOf(anyString(), any());
        verify(points, never()).withdrawAll(anyLong(), anyString(), anyLong(), anyString());
        verify(media, never()).discard(any());
    }

    // =================================================================================================================
    @Nested
    @TestMethodOrder(MethodOrderer.DisplayName.class)
    @DisplayName("01. deleteBlog – UC 1.16 Xóa câu hỏi (UTC-157 → UTC-174)")
    class DeleteQuestion {

        @Test
        @DisplayName("UTC-157 Người hỏi xóa câu hỏi của mình: báo cáo được đóng, điểm câu hỏi thu lại, ảnh bị xóa, câu hỏi và câu trả lời bị xóa hẳn")
        void utc157_askerDeletesOwnQuestion() {
            service.deleteBlog(ASKER, QA, QUESTION_ID);

            verify(reports).closeCasesOf("COMMENT", List.of(801L, 802L, 803L));
            verify(reports).closeCasesOf("BLOG", List.of(QUESTION_ID));
            verify(points).withdrawAll(ASKER_ID, "BLOG", QUESTION_ID, "CONTENT_DELETED");
            verify(media).discard(List.of(401L, 402L));
            verify(repository).deleteBlog(QUESTION_ID);
        }

        @Test
        @DisplayName("UTC-158 Thứ tự: khóa dòng câu hỏi → đóng báo cáo câu trả lời → đóng báo cáo câu hỏi → thu điểm → xóa ảnh → xóa câu hỏi")
        void utc158_stepsRunInOrder() {
            service.deleteBlog(ASKER, QA, QUESTION_ID);

            InOrder order = inOrder(repository, reports, points, media);
            order.verify(repository).lockBlog(QUESTION_ID);
            order.verify(reports).closeCasesOf("COMMENT", List.of(801L, 802L, 803L));
            order.verify(reports).closeCasesOf("BLOG", List.of(QUESTION_ID));
            order.verify(points).withdrawAll(ASKER_ID, "BLOG", QUESTION_ID, "CONTENT_DELETED");
            order.verify(media).discard(List.of(401L, 402L));
            order.verify(repository).deleteBlog(QUESTION_ID);
        }

        @Test
        @DisplayName("UTC-159 Câu hỏi chưa có câu trả lời và không có ảnh: vẫn xóa được")
        void utc159_questionWithoutAnswersOrPhotos() {
            question(ASKER_ID, QA, "PUBLISHED", null);
            when(repository.commentIds(QUESTION_ID)).thenReturn(List.of());
            when(repository.mediaIds(QUESTION_ID)).thenReturn(List.of());

            service.deleteBlog(ASKER, QA, QUESTION_ID);

            verify(reports).closeCasesOf("COMMENT", List.of());
            verify(media).discard(List.of());
            verify(repository).deleteBlog(QUESTION_ID);
        }

        @Test
        @DisplayName("UTC-160 Chỉ thu điểm của chính câu hỏi; +5 của người có câu trả lời hay nhất không bị thu")
        void utc160_onlyTheQuestionsOwnPointsAreTakenBack() {
            service.deleteBlog(ASKER, QA, QUESTION_ID);

            verify(points).withdrawAll(ASKER_ID, "BLOG", QUESTION_ID, "CONTENT_DELETED");
            verify(points, never()).withdrawAll(eq(HELPER_ID), anyString(), anyLong(), anyString());
            verify(points, never()).withdrawAll(anyLong(), eq("COMMENT"), anyLong(), anyString());
            verify(points, never()).add(anyLong(), anyInt(), any(), any(), any());
            verifyNoMoreInteractions(points);
        }

        @ParameterizedTest(name = "[{index}] trạng thái {0}")
        @ValueSource(strings = {"HIDDEN", "REJECTED", "PROCESSING"})
        @DisplayName("UTC-161 Câu hỏi đang bị ẩn do báo cáo, bị từ chối hoặc đang quét (chưa bị Moderator gỡ): người hỏi vẫn xóa được")
        void utc161_unpublishedQuestionMayBeDeleted(String status) {
            question(ASKER_ID, QA, status, null);

            service.deleteBlog(ASKER, QA, QUESTION_ID);

            verify(points).withdrawAll(ASKER_ID, "BLOG", QUESTION_ID, "CONTENT_DELETED");
            verify(repository).deleteBlog(QUESTION_ID);
        }

        @Test
        @DisplayName("UTC-162 Câu hỏi đã bị Moderator gỡ (REMOVED): 404 MSG19, không xóa, không thu điểm")
        void utc162_removedQuestionIsNotFound() {
            question(ASKER_ID, QA, "REMOVED", null);

            apiError(() -> service.deleteBlog(ASKER, QA, QUESTION_ID), HttpStatus.NOT_FOUND, MSG19);

            assertNothingDeleted();
        }

        @Test
        @DisplayName("UTC-163 Câu hỏi không còn (đã xóa, bấm xóa lần hai): 404 MSG19, không thu điểm lần hai")
        void utc163_missingQuestionIsNotFound() {
            when(repository.lockBlog(QUESTION_ID)).thenReturn(Optional.empty());

            apiError(() -> service.deleteBlog(ASKER, QA, QUESTION_ID), HttpStatus.NOT_FOUND, MSG19);

            assertNothingDeleted();
        }

        @Test
        @DisplayName("UTC-164 Id của một bài viết Blog không xóa được qua Hỏi-Đáp: 404 MSG19")
        void utc164_postIdIsNotFoundAsAQuestion() {
            question(ASKER_ID, "POST", "PUBLISHED", null);

            apiError(() -> service.deleteBlog(ASKER, QA, QUESTION_ID), HttpStatus.NOT_FOUND, MSG19);

            assertNothingDeleted();
        }

        @Test
        @DisplayName("UTC-165 Câu hỏi không xóa được qua trang Blog (loại POST): 404 MSG19")
        void utc165_questionIsNotDeletedFromTheBlog() {
            apiError(() -> service.deleteBlog(ASKER, "POST", QUESTION_ID), HttpStatus.NOT_FOUND, MSG19);

            assertNothingDeleted();
        }

        @ParameterizedTest(name = "[{index}] thành viên {0}")
        @ValueSource(longs = {HELPER_ID, STRANGER_ID})
        @DisplayName("UTC-166 Người không phải người hỏi (kể cả người trả lời): 403 MSG18, không xóa gì")
        void utc166_otherMemberIsForbidden(long memberId) {
            apiError(() -> service.deleteBlog(principal(memberId, "USER"), QA, QUESTION_ID), HttpStatus.FORBIDDEN,
                    MSG18);

            assertNothingDeleted();
        }

        @Test
        @DisplayName("UTC-167 Câu hỏi đã bị gỡ của người khác: 404 MSG19 (xét \"không tìm thấy\" trước quyền)")
        void utc167_notFoundComesBeforeForbidden() {
            question(HELPER_ID, QA, "REMOVED", null);

            apiError(() -> service.deleteBlog(ASKER, QA, QUESTION_ID), HttpStatus.NOT_FOUND, MSG19);
        }

        @Test
        @DisplayName("UTC-168 Khách (hết phiên) xóa câu hỏi: 401 \"Bạn cần đăng nhập để thực hiện thao tác này.\"")
        void utc168_guestMustSignIn() {
            apiError(() -> service.deleteBlog(null, QA, QUESTION_ID), HttpStatus.UNAUTHORIZED, LOGIN_REQUIRED);

            verifyNoInteractions(repository, reports, points, media);
        }

        @ParameterizedTest(name = "[{index}] vai trò {0}")
        @ValueSource(strings = {"MODERATOR", "ACADEMIC_MANAGER", "ADMIN"})
        @DisplayName("UTC-169 Nhân viên xóa câu hỏi qua Hỏi-Đáp: 403 MSG18, câu hỏi không bị khóa, không xóa gì")
        void utc169_staffCannotDelete(String role) {
            member(STAFF_ID, role, "ACTIVE");

            apiError(() -> service.deleteBlog(principal(STAFF_ID, role), QA, QUESTION_ID), HttpStatus.FORBIDDEN,
                    MSG18);

            verify(repository, never()).lockBlog(anyLong());
            assertNothingDeleted();
        }

        @ParameterizedTest(name = "[{index}] trạng thái tài khoản {0}")
        @ValueSource(strings = {"LOCKED", "DELETED"})
        @DisplayName("UTC-170 Người hỏi bị khóa hoặc đã xóa tài khoản: 403 MSG18, không xóa gì")
        void utc170_lockedOrDeletedAskerCannotDelete(String status) {
            member(ASKER_ID, "USER", status);

            apiError(() -> service.deleteBlog(ASKER, QA, QUESTION_ID), HttpStatus.FORBIDDEN, MSG18);

            assertNothingDeleted();
        }

        @Test
        @DisplayName("UTC-171 Không còn tài khoản: 401 \"Bạn chưa đăng nhập.\"")
        void utc171_missingAccountMustSignInAgain() {
            when(repository.member(ASKER_ID)).thenReturn(Optional.empty());

            apiError(() -> service.deleteBlog(ASKER, QA, QUESTION_ID), HttpStatus.UNAUTHORIZED, NOT_SIGNED_IN);

            assertNothingDeleted();
        }

        @Test
        @DisplayName("UTC-172 Câu hỏi được đọc bằng khóa dòng (FOR UPDATE) để hai lần xóa cùng lúc không thu điểm hai lần")
        void utc172_questionRowIsLocked() {
            service.deleteBlog(ASKER, QA, QUESTION_ID);

            verify(repository).lockBlog(QUESTION_ID);
            verify(repository, never()).findBlog(anyLong());
        }

        @Test
        @DisplayName("UTC-173 Xóa câu hỏi không gửi thông báo cho ai và không đổi số bài đã đăng trong ngày")
        void utc173_deletingNotifiesNobodyAndKeepsTheDailyCount() {
            service.deleteBlog(ASKER, QA, QUESTION_ID);

            verifyNoInteractions(notifications, filter, settings);
            verify(repository, never()).lockUserDay(anyLong(), any());
        }

        @Test
        @DisplayName("UTC-174 Xóa ảnh thất bại: lỗi được báo lên, câu hỏi chưa bị xóa (giao dịch sẽ hủy)")
        void utc174_failureBeforeTheDeleteKeepsTheQuestion() {
            doThrow(new IllegalStateException("storage down")).when(media).discard(List.of(401L, 402L));

            Throwable thrown = catchThrowable(() -> service.deleteBlog(ASKER, QA, QUESTION_ID));

            assertThat(thrown).isInstanceOf(IllegalStateException.class).hasMessage("storage down");
            verify(repository, never()).deleteBlog(anyLong());
        }
    }

    // =================================================================================================================
    @Nested
    @TestMethodOrder(MethodOrderer.DisplayName.class)
    @DisplayName("02. QaController.delete – UC 1.16 câu trả lời của API (UTC-175 → UTC-176)")
    class DeleteController {

        private BlogActionService actionService;
        private QaController controller;

        @BeforeEach
        void controller() {
            actionService = mock(BlogActionService.class);
            controller = new QaController(mock(QaService.class), mock(BlogDetailService.class), actionService,
                    mock(BlogWriteService.class));
        }

        @Test
        @DisplayName("UTC-175 Xóa thành công: \"Đã xóa câu hỏi.\", dịch vụ được gọi với loại QA")
        void utc175_deleteAnswersQuestionDeleted() {
            ApiResponse<Void> response = controller.delete(QUESTION_ID, ASKER);

            verify(actionService).deleteBlog(ASKER, "QA", QUESTION_ID);
            assertThat(response.success()).isTrue();
            assertThat(response.message()).isEqualTo(DELETED);
            assertThat(response.data()).isNull();
        }

        @Test
        @DisplayName("UTC-176 Dịch vụ từ chối (403 MSG18): lỗi được trả nguyên, không có \"Đã xóa câu hỏi.\"")
        void utc176_refusalPassesThrough() {
            doThrow(new ApiException(HttpStatus.FORBIDDEN, MSG18)).when(actionService)
                    .deleteBlog(ASKER, "QA", QUESTION_ID);

            apiError(() -> controller.delete(QUESTION_ID, ASKER), HttpStatus.FORBIDDEN, MSG18);
        }
    }
}
