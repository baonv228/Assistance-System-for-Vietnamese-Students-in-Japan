package com.sep490.vsa.feature.blog;

import com.sep490.vsa.common.exception.ApiException;
import com.sep490.vsa.common.security.AppUserPrincipal;
import com.sep490.vsa.common.settings.SystemSettings;
import com.sep490.vsa.feature.blog.BlogDetailDtos.SaveState;
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
import org.mockito.invocation.Invocation;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit tests of UC 1.12 "Add Item to Favorites" (Thêm vào mục yêu thích) for posts and questions
 * ({@link BlogActionService#saveBlog}, "Lưu" / "Bỏ lưu" on Blog and Hỏi-Đáp), written from CLAUDE.md ("Post Detail
 * (UC-10)": saving is for signed-in Members only, one's own post included; "Đã lưu"; the kind of the address must
 * match the item, MSG19 otherwise) and the use case test {@code usecase/saved/AddItemToFavoritesTest}. The last
 * group checks the statements of {@link BlogDetailRepository#setSaved} that keep a post saved once.
 * <p>
 * The database is not used: {@link BlogDetailRepository} (and, for its own group, {@link JdbcTemplate}),
 * {@link ContentReportService}, {@link ContentFilter}, {@link PointService}, {@link NotificationService},
 * {@link MediaService} and {@link SystemSettings} are mocks; the clock is fixed at 20/11/2026 12:00 Japan time.
 */
@TestClassOrder(ClassOrderer.DisplayName.class)
@DisplayName("BlogActionService – Lưu bài viết, câu hỏi vào \"Đã lưu\" (UC 1.12)")
class BlogActionServiceSaveTest {

    private static final long MEMBER_ID = 10L;
    private static final long AUTHOR_ID = 11L;
    private static final long POST_ID = 300L;
    private static final long QUESTION_ID = 301L;
    /** 12:00 in Japan, 20/11/2026. */
    private static final Instant NOW = Instant.parse("2026-11-20T03:00:00Z");

    private static final AppUserPrincipal MEMBER = principal(MEMBER_ID, "USER");

    private static final String MSG_LOGIN = "Bạn cần đăng nhập để thực hiện thao tác này.";
    private static final String MSG_NOT_SIGNED_IN = "Bạn chưa đăng nhập.";
    private static final String MSG18 = "Bạn không có quyền thực hiện thao tác này.";
    private static final String MSG19 = "Không tìm thấy nội dung bạn yêu cầu. Bài viết có thể đã bị gỡ hoặc đã bị xóa.";

    private BlogDetailRepository repository;
    private ContentReportService reports;
    private PointService points;
    private NotificationService notifications;
    private MediaService media;
    private ContentFilter filter;
    private BlogActionService service;

    @BeforeEach
    void setUp() {
        repository = mock(BlogDetailRepository.class);
        reports = mock(ContentReportService.class);
        points = mock(PointService.class);
        notifications = mock(NotificationService.class);
        media = mock(MediaService.class);
        filter = mock(ContentFilter.class);
        service = new BlogActionService(repository, filter, reports, points, notifications, media,
                mock(SystemSettings.class), Clock.fixed(NOW, ZoneOffset.UTC));

        account(MEMBER_ID, "USER", "ACTIVE");
        post(POST_ID, "POST", "PUBLISHED", "ACTIVE");
        post(QUESTION_ID, "QA", "PUBLISHED", "ACTIVE");
    }

    // ---------------------------------------------------------------- helpers

    private static AppUserPrincipal principal(long id, String role) {
        return new AppUserPrincipal(id, "user" + id + "@example.org", "User " + id, role, "/");
    }

    private void account(long id, String role, String status) {
        when(repository.member(id)).thenReturn(Optional.of(new Member(role, status, NOW.minus(Duration.ofDays(30)))));
    }

    private void post(long id, String type, String status, String authorStatus) {
        when(repository.lockBlog(id)).thenReturn(Optional.of(new BlogRow(id, AUTHOR_ID, "Tác giả", null, 120,
                authorStatus, type, "QA".equals(type) ? "Mua SIM ở đâu rẻ?" : "Kinh nghiệm thuê nhà", "Nội dung",
                status, false, false, null, 4, 2, NOW.minus(Duration.ofDays(1)))));
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

    private static void assertApiError(Runnable call, HttpStatus status, String message) {
        Throwable thrown = catchThrowable(call::run);
        assertThat(thrown).isInstanceOf(ApiException.class);
        assertThat(((ApiException) thrown).getStatus()).isEqualTo(status);
        assertThat(thrown.getMessage()).isEqualTo(message);
    }

    private void verifyNothingSaved() {
        verify(repository, never()).setSaved(anyLong(), anyLong(), anyBoolean(), any());
    }

    // =================================================================================================================
    @Nested
    @TestMethodOrder(MethodOrderer.DisplayName.class)
    @DisplayName("01. saveBlog – UC 1.12 Lưu, bỏ lưu bài viết và câu hỏi (UTC-001 → UTC-020)")
    class SaveBlog {

        @Test
        @DisplayName("UTC-001 Lưu bài viết đã đăng: nút chuyển sang \"Đã lưu\"")
        void utc001_memberSavesPost_saved() {
            SaveState state = service.saveBlog(MEMBER, "POST", POST_ID, true);

            assertThat(state.saved()).isTrue();
            verify(repository).setSaved(POST_ID, MEMBER_ID, true, NOW);
        }

        @Test
        @DisplayName("UTC-002 Bấm lại để bỏ lưu bài viết: nút trở về \"Lưu\"")
        void utc002_memberUnsavesPost_notSaved() {
            SaveState state = service.saveBlog(MEMBER, "POST", POST_ID, false);

            assertThat(state.saved()).isFalse();
            verify(repository).setSaved(POST_ID, MEMBER_ID, false, NOW);
        }

        @Test
        @DisplayName("UTC-003 Lưu câu hỏi trên Hỏi-Đáp")
        void utc003_memberSavesQuestion_saved() {
            SaveState state = service.saveBlog(MEMBER, "QA", QUESTION_ID, true);

            assertThat(state.saved()).isTrue();
            verify(repository).setSaved(QUESTION_ID, MEMBER_ID, true, NOW);
        }

        @Test
        @DisplayName("UTC-004 Bỏ lưu câu hỏi")
        void utc004_memberUnsavesQuestion_notSaved() {
            SaveState state = service.saveBlog(MEMBER, "QA", QUESTION_ID, false);

            assertThat(state.saved()).isFalse();
            verify(repository).setSaved(QUESTION_ID, MEMBER_ID, false, NOW);
        }

        @Test
        @DisplayName("UTC-005 Lưu bài viết của chính mình: được phép")
        void utc005_authorSavesOwnPost_saved() {
            AppUserPrincipal author = principal(AUTHOR_ID, "USER");
            account(AUTHOR_ID, "USER", "ACTIVE");

            SaveState state = service.saveBlog(author, "POST", POST_ID, true);

            assertThat(state.saved()).isTrue();
            verify(repository).setSaved(POST_ID, AUTHOR_ID, true, NOW);
        }

        @Test
        @DisplayName("UTC-006 Khóa dòng bài viết trước khi lưu")
        void utc006_postRowLockedBeforeSaving() {
            service.saveBlog(MEMBER, "POST", POST_ID, true);

            InOrder order = inOrder(repository);
            order.verify(repository).member(MEMBER_ID);
            order.verify(repository).lockBlog(POST_ID);
            order.verify(repository).setSaved(POST_ID, MEMBER_ID, true, NOW);
            verify(repository, never()).findBlog(anyLong());
        }

        @Test
        @DisplayName("UTC-007 Bấm \"Lưu\" hai lần: cả hai lần đều báo đã lưu, không lỗi")
        void utc007_saveTwice_bothSaved() {
            SaveState first = service.saveBlog(MEMBER, "POST", POST_ID, true);
            SaveState second = service.saveBlog(MEMBER, "POST", POST_ID, true);

            assertThat(first.saved()).isTrue();
            assertThat(second.saved()).isTrue();
            verify(repository, times(2)).setSaved(POST_ID, MEMBER_ID, true, NOW);
        }

        @Test
        @DisplayName("UTC-008 Bỏ lưu bài chưa từng lưu: không báo lỗi")
        void utc008_unsaveNeverSaved_noError() {
            SaveState state = service.saveBlog(MEMBER, "POST", POST_ID, false);

            assertThat(state.saved()).isFalse();
            verify(repository).setSaved(POST_ID, MEMBER_ID, false, NOW);
        }

        @Test
        @DisplayName("UTC-009 Lưu câu hỏi qua đường dẫn Blog: không tìm thấy")
        void utc009_questionThroughBlogAddress_404() {
            assertApiError(() -> service.saveBlog(MEMBER, "POST", QUESTION_ID, true), HttpStatus.NOT_FOUND, MSG19);
            verifyNothingSaved();
        }

        @Test
        @DisplayName("UTC-010 Lưu bài viết qua đường dẫn Hỏi-Đáp: không tìm thấy")
        void utc010_postThroughQuestionAddress_404() {
            assertApiError(() -> service.saveBlog(MEMBER, "QA", POST_ID, true), HttpStatus.NOT_FOUND, MSG19);
            verifyNothingSaved();
        }

        @Test
        @DisplayName("UTC-011 Bài viết không tồn tại: không tìm thấy")
        void utc011_missingPost_404() {
            when(repository.lockBlog(999_999L)).thenReturn(Optional.empty());

            assertApiError(() -> service.saveBlog(MEMBER, "POST", 999_999L, true), HttpStatus.NOT_FOUND, MSG19);
            verifyNothingSaved();
        }

        @ParameterizedTest(name = "[{index}] trạng thái {0}")
        @ValueSource(strings = {"PROCESSING", "HIDDEN", "REMOVED", "REJECTED"})
        @DisplayName("UTC-012 Bài đang quét, bị ẩn, bị gỡ, bị từ chối: không tìm thấy")
        void utc012_postNotPublished_404(String status) {
            post(POST_ID, "POST", status, "ACTIVE");

            assertApiError(() -> service.saveBlog(MEMBER, "POST", POST_ID, true), HttpStatus.NOT_FOUND, MSG19);
            verifyNothingSaved();
        }

        @ParameterizedTest(name = "[{index}] tác giả {0}")
        @ValueSource(strings = {"LOCKED", "DELETED"})
        @DisplayName("UTC-013 Bài của tài khoản bị khóa hoặc đã xóa: không tìm thấy")
        void utc013_postOfLockedOrDeletedAuthor_404(String authorStatus) {
            post(POST_ID, "POST", "PUBLISHED", authorStatus);

            assertApiError(() -> service.saveBlog(MEMBER, "POST", POST_ID, true), HttpStatus.NOT_FOUND, MSG19);
            verifyNothingSaved();
        }

        @Test
        @DisplayName("UTC-014 Bỏ lưu bài đã bị ẩn sau khi lưu: không tìm thấy, mục lưu được giữ")
        void utc014_unsaveHiddenPost_404() {
            post(POST_ID, "POST", "HIDDEN", "ACTIVE");

            assertApiError(() -> service.saveBlog(MEMBER, "POST", POST_ID, false), HttpStatus.NOT_FOUND, MSG19);
            verifyNothingSaved();
        }

        @Test
        @DisplayName("UTC-015 Khách bấm \"Lưu\": phải đăng nhập")
        void utc015_guestSaves_401() {
            assertApiError(() -> service.saveBlog(null, "POST", POST_ID, true), HttpStatus.UNAUTHORIZED, MSG_LOGIN);
            verifyNoInteractions(repository);
        }

        @ParameterizedTest(name = "[{index}] vai trò {0}")
        @ValueSource(strings = {"MODERATOR", "ACADEMIC_MANAGER", "ADMIN"})
        @DisplayName("UTC-016 Tài khoản nhân viên bấm \"Lưu\": bị từ chối")
        void utc016_staffSaves_403(String role) {
            AppUserPrincipal staff = staff(role);

            assertApiError(() -> service.saveBlog(staff, "POST", POST_ID, true), HttpStatus.FORBIDDEN, MSG18);
            verify(repository, never()).lockBlog(anyLong());
            verifyNothingSaved();
        }

        @Test
        @DisplayName("UTC-017 Tài khoản bị khóa bấm \"Lưu\": bị từ chối")
        void utc017_lockedMemberSaves_403() {
            account(MEMBER_ID, "USER", "LOCKED");

            assertApiError(() -> service.saveBlog(MEMBER, "POST", POST_ID, true), HttpStatus.FORBIDDEN, MSG18);
            verifyNothingSaved();
        }

        @Test
        @DisplayName("UTC-018 Tài khoản đã xóa bấm \"Lưu\": bị từ chối")
        void utc018_deletedMemberSaves_403() {
            account(MEMBER_ID, "USER", "DELETED");

            assertApiError(() -> service.saveBlog(MEMBER, "QA", QUESTION_ID, true), HttpStatus.FORBIDDEN, MSG18);
            verifyNothingSaved();
        }

        @Test
        @DisplayName("UTC-019 Tài khoản không còn trong hệ thống: phải đăng nhập")
        void utc019_accountGone_401() {
            when(repository.member(MEMBER_ID)).thenReturn(Optional.empty());

            assertApiError(() -> service.saveBlog(MEMBER, "POST", POST_ID, true), HttpStatus.UNAUTHORIZED,
                    MSG_NOT_SIGNED_IN);
            verifyNothingSaved();
        }

        @Test
        @DisplayName("UTC-020 Lưu bài không thích bài, không cộng điểm, không gửi thông báo")
        void utc020_saveHasNoOtherSideEffects() {
            service.saveBlog(MEMBER, "POST", POST_ID, true);

            verify(repository, never()).setBlogLike(anyLong(), anyLong(), anyBoolean(), any());
            verifyNoInteractions(points, notifications, reports, media, filter);
        }
    }

    // =================================================================================================================
    @Nested
    @TestMethodOrder(MethodOrderer.DisplayName.class)
    @DisplayName("02. BlogDetailRepository.setSaved – Ghi mục đã lưu (UTC-021 → UTC-022)")
    class SetSavedStatements {

        private JdbcTemplate jdbc;
        private BlogDetailRepository store;

        @BeforeEach
        void setUpStore() {
            jdbc = mock(JdbcTemplate.class);
            store = new BlogDetailRepository(jdbc);
        }

        private Invocation onlyStatement() {
            List<Invocation> calls = List.copyOf(mockingDetails(jdbc).getInvocations());
            assertThat(calls).hasSize(1);
            return calls.get(0);
        }

        @Test
        @DisplayName("UTC-021 Lưu: thêm một dòng, lưu lần nữa thì bỏ qua (giữ một mục)")
        void utc021_saveOn_insertIgnore() {
            store.setSaved(POST_ID, MEMBER_ID, true, NOW);

            Invocation call = onlyStatement();
            assertThat((String) call.getArgument(0))
                    .contains("INSERT IGNORE INTO saved_blogs (blog_id, user_id, created_at)");
            assertThat(Arrays.copyOfRange(call.getArguments(), 1, 4))
                    .containsExactly(POST_ID, MEMBER_ID, Timestamp.from(NOW));
        }

        @Test
        @DisplayName("UTC-022 Bỏ lưu: xóa dòng của đúng thành viên và bài viết")
        void utc022_saveOff_deleteRow() {
            store.setSaved(POST_ID, MEMBER_ID, false, NOW);

            Invocation call = onlyStatement();
            assertThat((String) call.getArgument(0))
                    .contains("DELETE FROM saved_blogs WHERE blog_id = ? AND user_id = ?");
            assertThat(Arrays.copyOfRange(call.getArguments(), 1, 3)).containsExactly(POST_ID, MEMBER_ID);
        }
    }
}
