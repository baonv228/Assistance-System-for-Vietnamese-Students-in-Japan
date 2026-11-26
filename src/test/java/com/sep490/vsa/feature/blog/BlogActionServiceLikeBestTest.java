package com.sep490.vsa.feature.blog;

import com.sep490.vsa.common.exception.ApiException;
import com.sep490.vsa.common.security.AppUserPrincipal;
import com.sep490.vsa.common.settings.SystemSettings;
import com.sep490.vsa.feature.blog.BlogDetailDtos.LikeState;
import com.sep490.vsa.feature.blog.BlogDetailRepository.BlogRow;
import com.sep490.vsa.feature.blog.BlogDetailRepository.CommentRow;
import com.sep490.vsa.feature.blog.BlogDetailRepository.Member;
import com.sep490.vsa.feature.media.MediaService;
import com.sep490.vsa.feature.moderation.ContentFilter;
import com.sep490.vsa.feature.notification.NotificationService;
import com.sep490.vsa.feature.qa.QaDtos.BestAnswerRequest;
import com.sep490.vsa.feature.report.ContentReportService;
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
import org.mockito.InOrder;
import org.springframework.http.HttpStatus;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit tests of the reading-side actions of {@link BlogActionService}: "Thích" / "Bỏ thích" on a post (UC 1.4 step 7)
 * and on a comment or answer, "Thích" on a question and "Chọn câu trả lời hay nhất" (UC 1.18 alternative flow 5a/5b).
 * <p>
 * The database is not used: {@link BlogDetailRepository}, {@link PointService}, {@link NotificationService},
 * {@link ContentReportService}, {@link ContentFilter}, {@link MediaService} and {@link SystemSettings} are mocks; the
 * clock is fixed at 20/11/2026 12:00 Japan time. Every account is read again from the database ({@code member}).
 */
@TestClassOrder(ClassOrderer.DisplayName.class)
@DisplayName("BlogActionService – Thích bài viết, bình luận, câu hỏi; chọn câu trả lời hay nhất (UC 1.4, UC 1.18)")
class BlogActionServiceLikeBestTest {

    static final String MSG18 = "Bạn không có quyền thực hiện thao tác này.";
    static final String MSG19 = "Không tìm thấy nội dung bạn yêu cầu. Bài viết có thể đã bị gỡ hoặc đã bị xóa.";
    static final String SIGN_IN = "Bạn cần đăng nhập để thực hiện thao tác này.";
    static final String COMMENT_NOT_FOUND = "Không tìm thấy bình luận này.";
    static final String ANSWER_NOT_FOUND = "Không tìm thấy câu trả lời này.";

    private static final Instant NOW = Instant.parse("2026-11-20T03:00:00Z");
    private static final Instant JOINED = Instant.parse("2026-01-10T00:00:00Z");

    private static final long POST_ID = 100L;
    private static final long QUESTION_ID = 200L;
    private static final long AUTHOR_ID = 50L;     // writer of the post / asker of the question
    private static final long MEMBER_ID = 10L;     // a reader
    private static final long HELPER_ID = 60L;     // writer of an answer / comment
    private static final long HELPER2_ID = 61L;

    private static final AppUserPrincipal AUTHOR = principal(AUTHOR_ID, "USER");
    private static final AppUserPrincipal MEMBER = principal(MEMBER_ID, "USER");
    private static final AppUserPrincipal HELPER = principal(HELPER_ID, "USER");
    private static final AppUserPrincipal MODERATOR = principal(20L, "MODERATOR");
    private static final AppUserPrincipal ADMIN = principal(1L, "ADMIN");
    private static final AppUserPrincipal EDITOR = principal(3L, "ACADEMIC_MANAGER");

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
        for (long id : List.of(AUTHOR_ID, MEMBER_ID, HELPER_ID, HELPER2_ID)) {
            account(id, "USER", "ACTIVE");
        }
        account(20L, "MODERATOR", "ACTIVE");
        account(1L, "ADMIN", "ACTIVE");
        account(3L, "ACADEMIC_MANAGER", "ACTIVE");
    }

    // ---------------------------------------------------------------- helpers

    private static AppUserPrincipal principal(long id, String role) {
        return new AppUserPrincipal(id, "user" + id + "@example.org", "User " + id, role, "/");
    }

    private void account(long id, String role, String status) {
        when(repository.member(id)).thenReturn(Optional.of(new Member(role, status, JOINED)));
    }

    private static BlogRow blog(long id, String type, String status, String authorStatus, Long best) {
        String title = "QA".equals(type) ? "Đi Ikebukuro thế nào?" : "Kinh nghiệm thuê nhà";
        return new BlogRow(id, AUTHOR_ID, "Lan", null, 0, authorStatus, type, title, "Nội dung", status, false, false,
                best, 7, 2, NOW);
    }

    private void givenLockedBlog(BlogRow row) {
        when(repository.lockBlog(row.id())).thenReturn(Optional.of(row));
    }

    private void givenPost() {
        givenLockedBlog(blog(POST_ID, "POST", "PUBLISHED", "ACTIVE", null));
    }

    private void givenQuestion(Long best) {
        givenLockedBlog(blog(QUESTION_ID, "QA", "PUBLISHED", "ACTIVE", best));
    }

    private static CommentRow comment(long id, long blogId, Long parent, long author, String status,
                                      String authorStatus) {
        return new CommentRow(id, blogId, parent, author, "User " + author, null, 0, authorStatus, "Câu trả lời " + id,
                status, false, 1, NOW);
    }

    private static CommentRow answer(long id, long author) {
        return comment(id, QUESTION_ID, null, author, "PUBLISHED", "ACTIVE");
    }

    private void givenComment(CommentRow row) {
        when(repository.findComment(row.id())).thenReturn(Optional.of(row));
        when(repository.lockComment(row.id())).thenReturn(Optional.of(row));
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

    private void verifyNoLikeWritten() {
        verify(repository, never()).setBlogLike(anyLong(), anyLong(), anyBoolean(), any());
        verify(repository, never()).setCommentLike(anyLong(), anyLong(), anyBoolean(), any());
    }

    private void verifyNothingChosen() {
        verify(repository, never()).setBestComment(anyLong(), any());
        verify(points, never()).add(anyLong(), anyInt(), anyString(), anyString(), any());
        verify(points, never()).withdrawAll(anyLong(), anyString(), anyLong(), anyString());
        verify(notifications, never()).send(anyLong(), anyString(), anyString(), anyString(), any());
    }

    private static String bestText(String title) {
        return "Câu trả lời của bạn cho “" + title + "” được chọn là câu trả lời hay nhất. +5 điểm.";
    }

    // =================================================================================================================
    @Nested
    @TestMethodOrder(MethodOrderer.DisplayName.class)
    @DisplayName("01. likeBlog(POST) – UC 1.4 bước 7 Thích / bỏ thích bài viết (UTC-096 → UTC-110)")
    class LikePost {

        @Test
        @DisplayName("UTC-096 Thành viên id=10 thích bài đã đăng của người khác: lượt thích ghi xuống, trả active = true, count = 8")
        void utc096_memberLikesAPublishedPost() {
            givenPost();
            when(repository.setBlogLike(POST_ID, MEMBER_ID, true, NOW)).thenReturn(8);

            LikeState state = service.likeBlog(MEMBER, "POST", POST_ID, true);

            assertThat(state).isEqualTo(new LikeState(true, 8));
            verify(repository).setBlogLike(POST_ID, MEMBER_ID, true, NOW);
        }

        @Test
        @DisplayName("UTC-097 Bỏ thích: trả active = false, count = 6")
        void utc097_memberUnlikesAPost() {
            givenPost();
            when(repository.setBlogLike(POST_ID, MEMBER_ID, false, NOW)).thenReturn(6);

            assertThat(service.likeBlog(MEMBER, "POST", POST_ID, false)).isEqualTo(new LikeState(false, 6));
        }

        @Test
        @DisplayName("UTC-098 Số lượt thích là số đếm lại trong kho: thích hai lần vẫn trả 1 (không đếm trùng) (2 lần)")
        void utc098_countIsTheRecountFromTheDatabase() {
            givenPost();
            when(repository.setBlogLike(POST_ID, MEMBER_ID, true, NOW)).thenReturn(1);

            assertThat(service.likeBlog(MEMBER, "POST", POST_ID, true).count()).isEqualTo(1);
            assertThat(service.likeBlog(MEMBER, "POST", POST_ID, true).count()).isEqualTo(1);
        }

        @Test
        @DisplayName("UTC-099 Thứ tự: đọc tài khoản → khóa dòng bài viết → ghi lượt thích; không đọc bài không khóa")
        void utc099_accountThenLockThenWrite() {
            givenPost();

            service.likeBlog(MEMBER, "POST", POST_ID, true);

            InOrder order = inOrder(repository);
            order.verify(repository).member(MEMBER_ID);
            order.verify(repository).lockBlog(POST_ID);
            order.verify(repository).setBlogLike(POST_ID, MEMBER_ID, true, NOW);
            verify(repository, never()).findBlog(anyLong());
        }

        @Test
        @DisplayName("UTC-100 Thích bài không cộng điểm, không gửi thông báo, không tạo báo cáo")
        void utc100_likingGivesNoPointsAndNoNotification() {
            givenPost();

            service.likeBlog(MEMBER, "POST", POST_ID, true);

            verifyNoInteractions(points, notifications, reports, filter, media);
        }

        @Test
        @DisplayName("UTC-101 Khách (chưa đăng nhập): 401 \"Bạn cần đăng nhập để thực hiện thao tác này.\", không đọc gì")
        void utc101_guestMustSignIn() {
            assertApiError(() -> service.likeBlog(null, "POST", POST_ID, true), HttpStatus.UNAUTHORIZED, SIGN_IN);
            verifyNoInteractions(repository);
        }

        @Test
        @DisplayName("UTC-102 Phiên còn nhưng tài khoản không còn trong kho: 401 \"Bạn chưa đăng nhập.\"")
        void utc102_missingAccountRowIsNotSignedIn() {
            when(repository.member(99L)).thenReturn(Optional.empty());

            assertApiError(() -> service.likeBlog(principal(99L, "USER"), "POST", POST_ID, true),
                    HttpStatus.UNAUTHORIZED, "Bạn chưa đăng nhập.");
            verifyNoLikeWritten();
        }

        @Test
        @DisplayName("UTC-103 Nhân viên (Moderator, Admin, BTV tiếng Nhật) thích bài: 403 MSG18, không khóa bài (3 lần)")
        void utc103_staffCannotLike() {
            givenPost();
            for (AppUserPrincipal staff : List.of(MODERATOR, ADMIN, EDITOR)) {
                assertApiError(() -> service.likeBlog(staff, "POST", POST_ID, true), HttpStatus.FORBIDDEN, MSG18);
            }
            verify(repository, never()).lockBlog(anyLong());
            verifyNoLikeWritten();
        }

        @Test
        @DisplayName("UTC-104 Tài khoản thành viên bị khóa / đã xóa (đọc lại từ kho): 403 MSG18 (2 lần)")
        void utc104_lockedOrDeletedMemberCannotLike() {
            givenPost();
            for (String status : List.of("LOCKED", "DELETED")) {
                account(MEMBER_ID, "USER", status);
                assertApiError(() -> service.likeBlog(MEMBER, "POST", POST_ID, true), HttpStatus.FORBIDDEN, MSG18);
            }
            verifyNoLikeWritten();
        }

        @Test
        @DisplayName("UTC-105 Vai trò đọc lại từ kho: phiên ghi USER nhưng kho đã đổi thành MODERATOR → 403 MSG18")
        void utc105_roleIsReadFromTheDatabase() {
            givenPost();
            account(MEMBER_ID, "MODERATOR", "ACTIVE");

            assertApiError(() -> service.likeBlog(MEMBER, "POST", POST_ID, true), HttpStatus.FORBIDDEN, MSG18);
            verifyNoLikeWritten();
        }

        @Test
        @DisplayName("UTC-106 Tác giả thích bài của chính mình: 400 \"Bạn không thể thích bài viết của chính mình.\"")
        void utc106_authorCannotLikeOwnPost() {
            givenPost();

            assertApiError(() -> service.likeBlog(AUTHOR, "POST", POST_ID, true), HttpStatus.BAD_REQUEST,
                    "Bạn không thể thích bài viết của chính mình.");
            verifyNoLikeWritten();
        }

        @Test
        @DisplayName("UTC-107 Bài không tồn tại: 404 MSG19")
        void utc107_missingPostIsNotFound() {
            when(repository.lockBlog(POST_ID)).thenReturn(Optional.empty());

            assertApiError(() -> service.likeBlog(MEMBER, "POST", POST_ID, true), HttpStatus.NOT_FOUND, MSG19);
            verifyNoLikeWritten();
        }

        @Test
        @DisplayName("UTC-108 Bài đang quét / bị ẩn / bị từ chối / bị gỡ: 404 MSG19 (4 lần)")
        void utc108_unpublishedPostTakesNoLike() {
            for (String state : List.of("PROCESSING", "HIDDEN", "REJECTED", "REMOVED")) {
                givenLockedBlog(blog(POST_ID, "POST", state, "ACTIVE", null));
                assertApiError(() -> service.likeBlog(MEMBER, "POST", POST_ID, true), HttpStatus.NOT_FOUND, MSG19);
            }
            verifyNoLikeWritten();
        }

        @Test
        @DisplayName("UTC-109 Bài của tài khoản bị khóa / đã xóa: 404 MSG19 (2 lần)")
        void utc109_postOfALockedOrDeletedAccountTakesNoLike() {
            for (String account : List.of("LOCKED", "DELETED")) {
                givenLockedBlog(blog(POST_ID, "POST", "PUBLISHED", account, null));
                assertApiError(() -> service.likeBlog(MEMBER, "POST", POST_ID, true), HttpStatus.NOT_FOUND, MSG19);
            }
            verifyNoLikeWritten();
        }

        @Test
        @DisplayName("UTC-110 Mã của một câu hỏi gửi qua /api/blogs (loại POST): 404 MSG19")
        void utc110_questionIdWithPostKindIsNotFound() {
            givenQuestion(null);

            assertApiError(() -> service.likeBlog(MEMBER, "POST", QUESTION_ID, true), HttpStatus.NOT_FOUND, MSG19);
            verifyNoLikeWritten();
        }
    }

    // =================================================================================================================
    @Nested
    @TestMethodOrder(MethodOrderer.DisplayName.class)
    @DisplayName("02. likeComment – UC 1.4 bước 7 Thích / bỏ thích bình luận (UTC-111 → UTC-122)")
    class LikeComment {

        private static final long COMMENT_ID = 300L;

        private CommentRow postComment(String status, String authorStatus) {
            return comment(COMMENT_ID, POST_ID, null, HELPER_ID, status, authorStatus);
        }

        @Test
        @DisplayName("UTC-111 Thành viên id=10 thích bình luận 300 của người khác: active = true, count = 2")
        void utc111_memberLikesAComment() {
            givenPost();
            givenComment(postComment("PUBLISHED", "ACTIVE"));
            when(repository.setCommentLike(COMMENT_ID, MEMBER_ID, true, NOW)).thenReturn(2);

            assertThat(service.likeComment(MEMBER, COMMENT_ID, true)).isEqualTo(new LikeState(true, 2));
        }

        @Test
        @DisplayName("UTC-112 Bỏ thích bình luận: active = false, count = 0")
        void utc112_memberUnlikesAComment() {
            givenPost();
            givenComment(postComment("PUBLISHED", "ACTIVE"));
            when(repository.setCommentLike(COMMENT_ID, MEMBER_ID, false, NOW)).thenReturn(0);

            assertThat(service.likeComment(MEMBER, COMMENT_ID, false)).isEqualTo(new LikeState(false, 0));
        }

        @Test
        @DisplayName("UTC-113 Thứ tự khóa: đọc bình luận → khóa bài → khóa bình luận → ghi lượt thích")
        void utc113_postIsLockedBeforeTheComment() {
            givenPost();
            givenComment(postComment("PUBLISHED", "ACTIVE"));

            service.likeComment(MEMBER, COMMENT_ID, true);

            InOrder order = inOrder(repository);
            order.verify(repository).findComment(COMMENT_ID);
            order.verify(repository).lockBlog(POST_ID);
            order.verify(repository).lockComment(COMMENT_ID);
            order.verify(repository).setCommentLike(COMMENT_ID, MEMBER_ID, true, NOW);
        }

        @Test
        @DisplayName("UTC-114 Thích câu trả lời trên một câu hỏi (cùng lệnh /api/comments)")
        void utc114_answerOnAQuestionCanBeLiked() {
            givenQuestion(null);
            givenComment(answer(301L, HELPER_ID));
            when(repository.setCommentLike(301L, MEMBER_ID, true, NOW)).thenReturn(5);

            assertThat(service.likeComment(MEMBER, 301L, true)).isEqualTo(new LikeState(true, 5));
        }

        @Test
        @DisplayName("UTC-115 Khách: 401, không đọc gì")
        void utc115_guestMustSignIn() {
            assertApiError(() -> service.likeComment(null, COMMENT_ID, true), HttpStatus.UNAUTHORIZED, SIGN_IN);
            verifyNoInteractions(repository);
        }

        @Test
        @DisplayName("UTC-116 Moderator thích bình luận: 403 MSG18")
        void utc116_staffCannotLikeAComment() {
            givenPost();
            givenComment(postComment("PUBLISHED", "ACTIVE"));

            assertApiError(() -> service.likeComment(MODERATOR, COMMENT_ID, true), HttpStatus.FORBIDDEN, MSG18);
            verifyNoLikeWritten();
        }

        @Test
        @DisplayName("UTC-117 Thích bình luận của chính mình: 400 \"Bạn không thể thích bình luận của chính mình.\"")
        void utc117_writerCannotLikeOwnComment() {
            givenPost();
            givenComment(postComment("PUBLISHED", "ACTIVE"));

            assertApiError(() -> service.likeComment(HELPER, COMMENT_ID, true), HttpStatus.BAD_REQUEST,
                    "Bạn không thể thích bình luận của chính mình.");
            verifyNoLikeWritten();
        }

        @Test
        @DisplayName("UTC-118 Bình luận không tồn tại: 404 \"Không tìm thấy bình luận này.\"")
        void utc118_missingCommentIsNotFound() {
            when(repository.findComment(COMMENT_ID)).thenReturn(Optional.empty());

            assertApiError(() -> service.likeComment(MEMBER, COMMENT_ID, true), HttpStatus.NOT_FOUND,
                    COMMENT_NOT_FOUND);
            verify(repository, never()).lockBlog(anyLong());
            verifyNoLikeWritten();
        }

        @Test
        @DisplayName("UTC-119 Bình luận bị ẩn / bị gỡ / đang xử lý: 404 \"Không tìm thấy bình luận này.\" (3 lần)")
        void utc119_hiddenOrRemovedCommentIsNotFound() {
            givenPost();
            for (String status : List.of("HIDDEN", "REMOVED", "PROCESSING")) {
                givenComment(postComment(status, "ACTIVE"));
                assertApiError(() -> service.likeComment(MEMBER, COMMENT_ID, true), HttpStatus.NOT_FOUND,
                        COMMENT_NOT_FOUND);
            }
            verifyNoLikeWritten();
        }

        @Test
        @DisplayName("UTC-120 Bình luận của tài khoản bị khóa / đã xóa: 404 \"Không tìm thấy bình luận này.\" (2 lần)")
        void utc120_commentOfALockedOrDeletedAccountIsNotFound() {
            givenPost();
            for (String account : List.of("LOCKED", "DELETED")) {
                givenComment(postComment("PUBLISHED", account));
                assertApiError(() -> service.likeComment(MEMBER, COMMENT_ID, true), HttpStatus.NOT_FOUND,
                        COMMENT_NOT_FOUND);
            }
            verifyNoLikeWritten();
        }

        @Test
        @DisplayName("UTC-121 Bài chứa bình luận bị ẩn hoặc tác giả bài bị khóa: 404 MSG19 (2 lần)")
        void utc121_commentUnderAHiddenPostIsNotFound() {
            givenComment(postComment("PUBLISHED", "ACTIVE"));
            givenLockedBlog(blog(POST_ID, "POST", "HIDDEN", "ACTIVE", null));
            assertApiError(() -> service.likeComment(MEMBER, COMMENT_ID, true), HttpStatus.NOT_FOUND, MSG19);

            givenLockedBlog(blog(POST_ID, "POST", "PUBLISHED", "LOCKED", null));
            assertApiError(() -> service.likeComment(MEMBER, COMMENT_ID, true), HttpStatus.NOT_FOUND, MSG19);
            verifyNoLikeWritten();
        }

        @Test
        @DisplayName("UTC-122 Bình luận bị ẩn trong lúc chờ khóa (đọc lại sau khi khóa): 404, không ghi lượt thích")
        void utc122_commentHiddenWhileWaitingForTheLock() {
            givenPost();
            when(repository.findComment(COMMENT_ID)).thenReturn(Optional.of(postComment("PUBLISHED", "ACTIVE")));
            when(repository.lockComment(COMMENT_ID)).thenReturn(Optional.of(postComment("HIDDEN", "ACTIVE")));

            assertApiError(() -> service.likeComment(MEMBER, COMMENT_ID, true), HttpStatus.NOT_FOUND,
                    COMMENT_NOT_FOUND);
            verifyNoLikeWritten();
        }
    }

    // =================================================================================================================
    @Nested
    @TestMethodOrder(MethodOrderer.DisplayName.class)
    @DisplayName("03. likeBlog(QA) – UC 1.18 Thích / bỏ thích câu hỏi (UTC-239 → UTC-244)")
    class LikeQuestion {

        @Test
        @DisplayName("UTC-239 Thành viên thích câu hỏi đã đăng: active = true, count = 1")
        void utc239_memberLikesAQuestion() {
            givenQuestion(null);
            when(repository.setBlogLike(QUESTION_ID, MEMBER_ID, true, NOW)).thenReturn(1);

            assertThat(service.likeBlog(MEMBER, "QA", QUESTION_ID, true)).isEqualTo(new LikeState(true, 1));
        }

        @Test
        @DisplayName("UTC-240 Thành viên bỏ thích câu hỏi: active = false, count = 0")
        void utc240_memberUnlikesAQuestion() {
            givenQuestion(null);
            when(repository.setBlogLike(QUESTION_ID, MEMBER_ID, false, NOW)).thenReturn(0);

            assertThat(service.likeBlog(MEMBER, "QA", QUESTION_ID, false)).isEqualTo(new LikeState(false, 0));
        }

        @Test
        @DisplayName("UTC-241 Người hỏi thích câu hỏi của mình: 400 \"Bạn không thể thích bài viết của chính mình.\"")
        void utc241_askerCannotLikeOwnQuestion() {
            givenQuestion(null);

            assertApiError(() -> service.likeBlog(AUTHOR, "QA", QUESTION_ID, true), HttpStatus.BAD_REQUEST,
                    "Bạn không thể thích bài viết của chính mình.");
            verifyNoLikeWritten();
        }

        @Test
        @DisplayName("UTC-242 Mã của một bài viết Blog gửi qua /api/questions (loại QA): 404 MSG19")
        void utc242_postIdWithQuestionKindIsNotFound() {
            givenPost();

            assertApiError(() -> service.likeBlog(MEMBER, "QA", POST_ID, true), HttpStatus.NOT_FOUND, MSG19);
            verifyNoLikeWritten();
        }

        @Test
        @DisplayName("UTC-243 Câu hỏi đang quét / bị ẩn: 404 MSG19 (2 lần)")
        void utc243_unpublishedQuestionTakesNoLike() {
            for (String state : List.of("PROCESSING", "HIDDEN")) {
                givenLockedBlog(blog(QUESTION_ID, "QA", state, "ACTIVE", null));
                assertApiError(() -> service.likeBlog(MEMBER, "QA", QUESTION_ID, true), HttpStatus.NOT_FOUND, MSG19);
            }
            verifyNoLikeWritten();
        }

        @Test
        @DisplayName("UTC-244 Khách: 401; Moderator: 403 MSG18 (2 lần)")
        void utc244_guestAndStaffCannotLikeAQuestion() {
            givenQuestion(null);

            assertApiError(() -> service.likeBlog(null, "QA", QUESTION_ID, true), HttpStatus.UNAUTHORIZED, SIGN_IN);
            assertApiError(() -> service.likeBlog(MODERATOR, "QA", QUESTION_ID, true), HttpStatus.FORBIDDEN, MSG18);
            verifyNoLikeWritten();
        }
    }

    // =================================================================================================================
    @Nested
    @TestMethodOrder(MethodOrderer.DisplayName.class)
    @DisplayName("04. chooseBest – UC 1.18 luồng thay thế 5a/5b Chọn câu trả lời hay nhất (UTC-245 → UTC-267)")
    class ChooseBest {

        @Test
        @DisplayName("UTC-245 Người hỏi chọn câu trả lời 301 của id=60: đánh dấu, +5 điểm cho người viết, thông báo")
        void utc245_askerChoosesTheBestAnswer() {
            givenQuestion(null);
            givenComment(answer(301L, HELPER_ID));

            service.chooseBest(AUTHOR, QUESTION_ID, 301L);

            verify(repository).setBestComment(QUESTION_ID, 301L);
            verify(points).add(HELPER_ID, 5, "BEST_ANSWER", "COMMENT", 301L);
            verify(notifications).send(HELPER_ID, "BEST_ANSWER", bestText("Đi Ikebukuro thế nào?"), "QUESTION",
                    QUESTION_ID);
        }

        @Test
        @DisplayName("UTC-246 Lần chọn đầu tiên: không thu hồi điểm của ai")
        void utc246_firstChoiceWithdrawsNothing() {
            givenQuestion(null);
            givenComment(answer(301L, HELPER_ID));

            service.chooseBest(AUTHOR, QUESTION_ID, 301L);

            verify(points, never()).withdrawAll(anyLong(), anyString(), anyLong(), anyString());
        }

        @Test
        @DisplayName("UTC-247 Đổi từ 301 (id=60) sang 302 (id=61): thu 5 điểm của id=60 rồi cộng 5 điểm cho id=61")
        void utc247_changingTheBestAnswerMovesThePoints() {
            givenQuestion(301L);
            givenComment(answer(301L, HELPER_ID));
            givenComment(answer(302L, HELPER2_ID));

            service.chooseBest(AUTHOR, QUESTION_ID, 302L);

            InOrder order = inOrder(points, repository);
            order.verify(points).withdrawAll(HELPER_ID, "COMMENT", 301L, "BEST_ANSWER_CHANGED");
            order.verify(repository).setBestComment(QUESTION_ID, 302L);
            order.verify(points).add(HELPER2_ID, 5, "BEST_ANSWER", "COMMENT", 302L);
            verify(notifications).send(HELPER2_ID, "BEST_ANSWER", bestText("Đi Ikebukuro thế nào?"), "QUESTION",
                    QUESTION_ID);
        }

        @Test
        @DisplayName("UTC-248 Đổi giữa hai câu trả lời của cùng người id=60: điểm của câu cũ thu lại, câu mới được +5")
        void utc248_changingBetweenTwoAnswersOfOneWriter() {
            givenQuestion(301L);
            givenComment(answer(301L, HELPER_ID));
            givenComment(answer(302L, HELPER_ID));

            service.chooseBest(AUTHOR, QUESTION_ID, 302L);

            verify(points).withdrawAll(HELPER_ID, "COMMENT", 301L, "BEST_ANSWER_CHANGED");
            verify(points).add(HELPER_ID, 5, "BEST_ANSWER", "COMMENT", 302L);
        }

        @Test
        @DisplayName("UTC-249 Câu trả lời hay nhất cũ (301) đã bị xóa khỏi kho: không thu hồi, câu mới vẫn được +5")
        void utc249_previousBestAnswerGoneWithdrawsNothing() {
            givenQuestion(301L);
            when(repository.findComment(301L)).thenReturn(Optional.empty());
            givenComment(answer(302L, HELPER2_ID));

            service.chooseBest(AUTHOR, QUESTION_ID, 302L);

            verify(points, never()).withdrawAll(anyLong(), anyString(), anyLong(), anyString());
            verify(repository).setBestComment(QUESTION_ID, 302L);
            verify(points).add(HELPER2_ID, 5, "BEST_ANSWER", "COMMENT", 302L);
        }

        @Test
        @DisplayName("UTC-250 Chọn lại đúng câu đang là hay nhất: không ghi gì, không cộng thêm, không thông báo lại")
        void utc250_choosingTheSameAnswerAgainChangesNothing() {
            givenQuestion(301L);
            givenComment(answer(301L, HELPER_ID));

            service.chooseBest(AUTHOR, QUESTION_ID, 301L);

            verifyNothingChosen();
        }

        @Test
        @DisplayName("UTC-251 Khóa dòng câu hỏi trước khi đọc câu trả lời; không đọc câu hỏi không khóa")
        void utc251_questionRowIsLockedFirst() {
            givenQuestion(null);
            givenComment(answer(301L, HELPER_ID));

            service.chooseBest(AUTHOR, QUESTION_ID, 301L);

            InOrder order = inOrder(repository);
            order.verify(repository).member(AUTHOR_ID);
            order.verify(repository).lockBlog(QUESTION_ID);
            order.verify(repository).findComment(301L);
            order.verify(repository).setBestComment(QUESTION_ID, 301L);
            verify(repository, never()).findBlog(anyLong());
        }

        @Test
        @DisplayName("UTC-252 Khách: 401 \"Bạn cần đăng nhập để thực hiện thao tác này.\"")
        void utc252_guestMustSignIn() {
            assertApiError(() -> service.chooseBest(null, QUESTION_ID, 301L), HttpStatus.UNAUTHORIZED, SIGN_IN);
            verifyNothingChosen();
        }

        @Test
        @DisplayName("UTC-253 Nhân viên (Moderator, Admin, BTV tiếng Nhật): 403 MSG18 (3 lần)")
        void utc253_staffCannotChoose() {
            givenQuestion(null);
            givenComment(answer(301L, HELPER_ID));
            for (AppUserPrincipal staff : List.of(MODERATOR, ADMIN, EDITOR)) {
                assertApiError(() -> service.chooseBest(staff, QUESTION_ID, 301L), HttpStatus.FORBIDDEN, MSG18);
            }
            verifyNothingChosen();
        }

        @Test
        @DisplayName("UTC-254 Người hỏi có tài khoản bị khóa: 403 MSG18")
        void utc254_lockedAskerCannotChoose() {
            givenQuestion(null);
            givenComment(answer(301L, HELPER_ID));
            account(AUTHOR_ID, "USER", "LOCKED");

            assertApiError(() -> service.chooseBest(AUTHOR, QUESTION_ID, 301L), HttpStatus.FORBIDDEN, MSG18);
            verifyNothingChosen();
        }

        @Test
        @DisplayName("UTC-255 Không phải người hỏi (thành viên khác, chính người viết câu trả lời): 403 MSG18 (2 lần)")
        void utc255_onlyTheAskerChooses() {
            givenQuestion(null);
            givenComment(answer(301L, HELPER_ID));
            for (AppUserPrincipal other : List.of(MEMBER, HELPER)) {
                assertApiError(() -> service.chooseBest(other, QUESTION_ID, 301L), HttpStatus.FORBIDDEN, MSG18);
            }
            verify(repository, never()).findComment(anyLong());
            verifyNothingChosen();
        }

        @Test
        @DisplayName("UTC-256 Thành viên khác chọn một mã câu trả lời không tồn tại: vẫn 403 MSG18 (kiểm tra người hỏi trước)")
        void utc256_askerIsCheckedBeforeTheAnswer() {
            givenQuestion(null);

            assertApiError(() -> service.chooseBest(MEMBER, QUESTION_ID, 999L), HttpStatus.FORBIDDEN, MSG18);
            verifyNothingChosen();
        }

        @Test
        @DisplayName("UTC-257 Câu hỏi không tồn tại: 404 MSG19")
        void utc257_missingQuestionIsNotFound() {
            when(repository.lockBlog(QUESTION_ID)).thenReturn(Optional.empty());

            assertApiError(() -> service.chooseBest(AUTHOR, QUESTION_ID, 301L), HttpStatus.NOT_FOUND, MSG19);
            verifyNothingChosen();
        }

        @Test
        @DisplayName("UTC-258 Câu hỏi đang quét / bị ẩn / bị từ chối / bị gỡ: 404 MSG19 (4 lần)")
        void utc258_unpublishedQuestionIsNotFound() {
            givenComment(answer(301L, HELPER_ID));
            for (String state : List.of("PROCESSING", "HIDDEN", "REJECTED", "REMOVED")) {
                givenLockedBlog(blog(QUESTION_ID, "QA", state, "ACTIVE", null));
                assertApiError(() -> service.chooseBest(AUTHOR, QUESTION_ID, 301L), HttpStatus.NOT_FOUND, MSG19);
            }
            verifyNothingChosen();
        }

        @Test
        @DisplayName("UTC-259 Mã của một bài viết Blog: 404 MSG19")
        void utc259_postIdIsNotAQuestion() {
            givenPost();
            givenComment(comment(301L, POST_ID, null, HELPER_ID, "PUBLISHED", "ACTIVE"));

            assertApiError(() -> service.chooseBest(AUTHOR, POST_ID, 301L), HttpStatus.NOT_FOUND, MSG19);
            verifyNothingChosen();
        }

        @Test
        @DisplayName("UTC-260 Câu trả lời không tồn tại (999999999): 404 \"Không tìm thấy câu trả lời này.\"")
        void utc260_missingAnswerIsNotFound() {
            givenQuestion(null);
            when(repository.findComment(999_999_999L)).thenReturn(Optional.empty());

            assertApiError(() -> service.chooseBest(AUTHOR, QUESTION_ID, 999_999_999L), HttpStatus.NOT_FOUND,
                    ANSWER_NOT_FOUND);
            verifyNothingChosen();
        }

        @Test
        @DisplayName("UTC-261 Câu trả lời thuộc câu hỏi khác (201): 404 \"Không tìm thấy câu trả lời này.\"")
        void utc261_answerOfAnotherQuestionIsNotFound() {
            givenQuestion(null);
            givenComment(comment(305L, 201L, null, HELPER_ID, "PUBLISHED", "ACTIVE"));

            assertApiError(() -> service.chooseBest(AUTHOR, QUESTION_ID, 305L), HttpStatus.NOT_FOUND,
                    ANSWER_NOT_FOUND);
            verifyNothingChosen();
        }

        @Test
        @DisplayName("UTC-262 Câu trả lời bị ẩn / bị gỡ: 404 \"Không tìm thấy câu trả lời này.\" (2 lần)")
        void utc262_hiddenOrRemovedAnswerIsNotFound() {
            givenQuestion(null);
            for (String status : List.of("HIDDEN", "REMOVED")) {
                givenComment(comment(301L, QUESTION_ID, null, HELPER_ID, status, "ACTIVE"));
                assertApiError(() -> service.chooseBest(AUTHOR, QUESTION_ID, 301L), HttpStatus.NOT_FOUND,
                        ANSWER_NOT_FOUND);
            }
            verifyNothingChosen();
        }

        @Test
        @DisplayName("UTC-263 Người viết câu trả lời bị khóa / đã xóa: 404 \"Không tìm thấy câu trả lời này.\" (2 lần)")
        void utc263_answerOfALockedOrDeletedAccountIsNotFound() {
            givenQuestion(null);
            for (String account : List.of("LOCKED", "DELETED")) {
                givenComment(comment(301L, QUESTION_ID, null, HELPER_ID, "PUBLISHED", account));
                assertApiError(() -> service.chooseBest(AUTHOR, QUESTION_ID, 301L), HttpStatus.NOT_FOUND,
                        ANSWER_NOT_FOUND);
            }
            verifyNothingChosen();
        }

        @Test
        @DisplayName("UTC-264 Chọn một phản hồi (không phải câu trả lời trực tiếp): 400")
        void utc264_replyCannotBeChosen() {
            givenQuestion(null);
            givenComment(comment(303L, QUESTION_ID, 301L, MEMBER_ID, "PUBLISHED", "ACTIVE"));

            assertApiError(() -> service.chooseBest(AUTHOR, QUESTION_ID, 303L), HttpStatus.BAD_REQUEST,
                    "Chỉ chọn được câu trả lời trực tiếp cho câu hỏi, không chọn phần trả lời bình luận.");
            verifyNothingChosen();
        }

        @Test
        @DisplayName("UTC-265 Chọn câu trả lời của chính mình: 400 \"Bạn không thể chọn câu trả lời của chính mình.\"")
        void utc265_askerCannotChooseOwnAnswer() {
            givenQuestion(null);
            givenComment(answer(304L, AUTHOR_ID));

            assertApiError(() -> service.chooseBest(AUTHOR, QUESTION_ID, 304L), HttpStatus.BAD_REQUEST,
                    "Bạn không thể chọn câu trả lời của chính mình.");
            verifyNothingChosen();
        }

        @Test
        @DisplayName("UTC-266 Phản hồi do chính người hỏi viết: báo lỗi phản hồi trước lỗi \"của chính mình\"")
        void utc266_replyCheckComesBeforeOwnAnswerCheck() {
            givenQuestion(null);
            givenComment(comment(306L, QUESTION_ID, 301L, AUTHOR_ID, "PUBLISHED", "ACTIVE"));

            assertApiError(() -> service.chooseBest(AUTHOR, QUESTION_ID, 306L), HttpStatus.BAD_REQUEST,
                    "Chỉ chọn được câu trả lời trực tiếp cho câu hỏi, không chọn phần trả lời bình luận.");
            verifyNothingChosen();
        }

        @Test
        @DisplayName("UTC-267 Chưa chọn câu trả lời (answerId = null): \"Hãy chọn câu trả lời.\"; answerId = 301 hợp lệ")
        void utc267_answerIdIsRequired() {
            Set<ConstraintViolation<BestAnswerRequest>> missing = validator.validate(new BestAnswerRequest(null));
            Set<ConstraintViolation<BestAnswerRequest>> given = validator.validate(new BestAnswerRequest(301L));

            assertThat(missing).extracting(ConstraintViolation::getMessage).containsExactly("Hãy chọn câu trả lời.");
            assertThat(given).isEmpty();
        }
    }
}
