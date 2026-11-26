package com.sep490.vsa.feature.blog;

import com.sep490.vsa.common.exception.ApiException;
import com.sep490.vsa.common.security.AppUserPrincipal;
import com.sep490.vsa.common.settings.SystemSettings;
import com.sep490.vsa.common.text.VietnameseText;
import com.sep490.vsa.feature.blog.BlogDetailDtos.CommentRequest;
import com.sep490.vsa.feature.blog.BlogDetailDtos.CommentUpdateRequest;
import com.sep490.vsa.feature.blog.BlogDetailRepository.BlogRow;
import com.sep490.vsa.feature.blog.BlogDetailRepository.CommentRow;
import com.sep490.vsa.feature.blog.BlogDetailRepository.CommentText;
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
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.http.HttpStatus;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit tests of the comment actions of Post Detail on the Blog ({@link BlogActionService} with kind POST): UC 1.8 Add
 * Comment ({@code comment}, replies included), UC 1.9 Update Comment ({@code updateComment}) and UC 1.10 Delete Comment
 * ({@code deleteComment}), written from the SRS use cases (Report 3), the use case tests in
 * {@code usecase/comment/*} and the rules of CLAUDE.md ("New comments, replies and answers": one per user every 15 s,
 * 20 a Japan day for accounts younger than {@code new_account_days}, the same text of at least 30 letters on at most 3
 * other posts in 24 h, replies at most 20 deep; banned keywords MSG36 and links BR-22).
 * <p>
 * The database is not used: {@link BlogDetailRepository}, {@link ContentFilter}, {@link ContentReportService},
 * {@link PointService}, {@link NotificationService}, {@link MediaService} and {@link SystemSettings} are mocks (the
 * keyword and link checks are stubbed with the real messages of {@link ContentFilter}); the clock is fixed at
 * 20/11/2026 12:00 Japan time.
 */
@TestClassOrder(ClassOrderer.DisplayName.class)
@DisplayName("BlogActionService – Bình luận trên Blog: thêm, sửa, xóa (UC 1.8, 1.9, 1.10)")
class BlogActionServiceCommentTest {

    /** 12:00 in Japan: the Japan date is 20/11/2026. */
    private static final Instant NOW = Instant.parse("2026-11-20T03:00:00Z");
    private static final LocalDate TODAY = LocalDate.of(2026, 11, 20);
    /** The Japan day 20/11/2026 in UTC: [19/11 15:00, 20/11 15:00). */
    private static final Instant DAY_START = Instant.parse("2026-11-19T15:00:00Z");
    private static final Instant DAY_END = Instant.parse("2026-11-20T15:00:00Z");

    private static final long AUTHOR_ID = 1L;      // writer of the post, "Lan"
    private static final long ME_ID = 10L;         // the member who comments, "Minh"
    private static final long OTHER_ID = 11L;      // another member, "Hoa"
    private static final long MOD_ID = 20L;
    private static final long EDITOR_ID = 21L;
    private static final long ADMIN_ID = 22L;
    private static final long BLOG_ID = 100L;
    private static final long QUESTION_ID = 200L;
    private static final long COMMENT_ID = 500L;
    private static final long NEW_ID = 700L;

    private static final AppUserPrincipal ME = principal(ME_ID, "Minh", "USER");
    private static final AppUserPrincipal AUTHOR = principal(AUTHOR_ID, "Lan", "USER");
    private static final AppUserPrincipal OTHER = principal(OTHER_ID, "Hoa", "USER");
    private static final AppUserPrincipal MODERATOR = principal(MOD_ID, "Moderator", "MODERATOR");
    private static final AppUserPrincipal EDITOR = principal(EDITOR_ID, "BTV", "ACADEMIC_MANAGER");
    private static final AppUserPrincipal ADMIN = principal(ADMIN_ID, "Admin", "ADMIN");

    private static final String TITLE = "Kinh nghiệm thuê nhà ở Tokyo";
    private static final String TEXT = "Mua thẻ IC ngay ở ga đầu tiên nhé.";
    private static final String LONG_TEXT =
            "Mình có kinh nghiệm làm thủ tục này rồi, bạn cứ nhắn hỏi mình nhé mọi người ơi";

    private static final String MSG_LOGIN = "Bạn cần đăng nhập để thực hiện thao tác này.";
    private static final String MSG_NO_SESSION = "Bạn chưa đăng nhập.";
    private static final String MSG18 = "Bạn không có quyền thực hiện thao tác này.";
    private static final String MSG19 = "Không tìm thấy nội dung bạn yêu cầu. Bài viết có thể đã bị gỡ hoặc đã bị xóa.";
    private static final String EMPTY = "Hãy viết nội dung bình luận.";
    private static final String TOO_LONG = "Bình luận tối đa 2.000 ký tự.";
    private static final String PARENT_GONE = "Bình luận bạn trả lời không còn nữa.";
    private static final String TOO_DEEP = "Chuỗi trả lời đã quá dài. Hãy trả lời một bình luận ở phía trên.";
    private static final String COMMENT_NOT_FOUND = "Không tìm thấy bình luận này.";
    private static final String MSG36 =
            "Chưa đăng được: nội dung chứa từ khóa không được phép “fb88”. Hãy sửa rồi gửi lại.";
    private static final String LINK_NEEDS_TAG = "Chỉ tài khoản đã có tag định danh mới được chèn link.";
    private static final String CONTACT_NEEDS_TAG =
            "Chỉ tài khoản đã có tag định danh mới được ghi số điện thoại hoặc tài khoản Zalo, LINE, Telegram.";
    private static final String SHORT_LINK = "Không dùng link rút gọn (bit.ly, tinyurl…). Hãy dán link đầy đủ.";
    private static final String DAILY_LIMIT = "Bạn đã đạt giới hạn bình luận trong ngày của tài khoản mới (dưới 7 "
            + "ngày: tối đa 20 bình luận mỗi ngày). Vui lòng thử lại vào ngày mai.";             // MSG44
    private static final String REPEATED =
            "Bạn đã gửi nội dung này ở nhiều bài khác. Hãy viết bình luận riêng cho bài này.";

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
        service = serviceAt(NOW);

        account(ME_ID, "USER", "ACTIVE", NOW.minus(Duration.ofDays(30)));
        account(AUTHOR_ID, "USER", "ACTIVE", NOW.minus(Duration.ofDays(90)));
        account(OTHER_ID, "USER", "ACTIVE", NOW.minus(Duration.ofDays(60)));
        account(MOD_ID, "MODERATOR", "ACTIVE", NOW.minus(Duration.ofDays(200)));
        account(EDITOR_ID, "ACADEMIC_MANAGER", "ACTIVE", NOW.minus(Duration.ofDays(200)));
        account(ADMIN_ID, "ADMIN", "ACTIVE", NOW.minus(Duration.ofDays(200)));
        when(settings.getInt(SystemSettings.NEW_ACCOUNT_DAYS, 7)).thenReturn(7);

        when(repository.lockBlog(BLOG_ID)).thenReturn(Optional.of(post("PUBLISHED", "ACTIVE")));
        when(repository.findBlog(BLOG_ID)).thenReturn(Optional.of(post("PUBLISHED", "ACTIVE")));
        when(repository.insertComment(anyLong(), anyLong(), any(), anyString(), any())).thenReturn(NEW_ID);
        when(repository.updateComment(anyLong(), anyString(), any())).thenReturn(true);

        // Comment 500: a published top-level comment of "Minh" on post 100
        CommentRow mine = comment(COMMENT_ID, BLOG_ID, null, ME_ID, "ACTIVE", "PUBLISHED");
        when(repository.findComment(COMMENT_ID)).thenReturn(Optional.of(mine));
        when(repository.lockComment(COMMENT_ID)).thenReturn(Optional.of(mine));
    }

    // ---------------------------------------------------------------- helpers

    private BlogActionService serviceAt(Instant instant) {
        return new BlogActionService(repository, filter, reports, points, notifications, media, settings,
                Clock.fixed(instant, ZoneOffset.UTC));
    }

    private static AppUserPrincipal principal(long id, String name, String role) {
        return new AppUserPrincipal(id, "user" + id + "@example.org", name, role, "/");
    }

    private void account(long id, String role, String status, Instant createdAt) {
        when(repository.member(id)).thenReturn(Optional.of(new Member(role, status, createdAt)));
    }

    /** Post 100 of "Lan" (kind POST) with the given status and author status. */
    private static BlogRow post(String status, String authorStatus) {
        return blog(BLOG_ID, "POST", status, AUTHOR_ID, authorStatus, null);
    }

    private static BlogRow blog(long id, String type, String status, long authorId, String authorStatus, Long best) {
        return new BlogRow(id, authorId, "Lan", null, 120, authorStatus, type, TITLE, "Nội dung bài viết", status,
                false, false, best, 3, 2, NOW.minus(Duration.ofDays(2)));
    }

    private static CommentRow comment(long id, long blogId, Long parentId, long authorId, String authorStatus,
                                      String status) {
        return new CommentRow(id, blogId, parentId, authorId, "User " + authorId, null, 50, authorStatus,
                "REMOVED".equals(status) ? "" : "Nội dung bình luận " + id, status, false, 2,
                NOW.minus(Duration.ofHours(1)));
    }

    /** A published comment of "Hoa" on post 100 that a reply can answer. */
    private CommentRow parent(long id, String authorStatus, String status) {
        CommentRow row = comment(id, BLOG_ID, null, OTHER_ID, authorStatus, status);
        when(repository.findComment(id)).thenReturn(Optional.of(row));
        return row;
    }

    /**
     * A chain of published comments of "Hoa" on post 100 with ids 600 (depth 0) to 600 + {@code parentDepth}; returns
     * the id of the deepest one, the comment the reply answers.
     */
    private long chain(int parentDepth) {
        for (int depth = 0; depth <= parentDepth; depth++) {
            long id = 600L + depth;
            Long up = depth == 0 ? null : id - 1;
            when(repository.parentIdOf(id)).thenReturn(up);
        }
        long deepest = 600L + parentDepth;
        when(repository.findComment(deepest)).thenReturn(Optional.of(
                comment(deepest, BLOG_ID, parentDepth == 0 ? null : deepest - 1, OTHER_ID, "ACTIVE", "PUBLISHED")));
        return deepest;
    }

    private static CommentRequest request(String content) {
        return new CommentRequest(content, null);
    }

    private static CommentRequest reply(String content, long parentId) {
        return new CommentRequest(content, parentId);
    }

    private static String commented(String who) {
        return who + " đã bình luận bài “" + TITLE + "” của bạn.";
    }

    private static String replied(String who) {
        return who + " đã trả lời bình luận của bạn trong “" + TITLE + "”.";
    }

    private static String tooFast(int seconds) {
        return "Bạn bình luận hơi nhanh. Vui lòng đợi " + seconds + " giây rồi thử lại.";
    }

    private void lastCommentAgo(Duration ago) {
        when(repository.lastCommentAt(ME_ID)).thenReturn(NOW.minus(ago));
    }

    private void commentsToday(long count) {
        when(repository.countComments(eq(ME_ID), any(), any())).thenReturn(count);
    }

    private void earlierElsewhere(CommentText... earlier) {
        when(repository.recentCommentsElsewhere(eq(ME_ID), eq(BLOG_ID), any(), anyInt())).thenReturn(List.of(earlier));
    }

    private void filterRefuses(String text, String message) {
        doThrow(ApiException.badRequest(message)).when(filter).check(text, ME_ID);
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

    private String insertedContent() {
        ArgumentCaptor<String> content = ArgumentCaptor.forClass(String.class);
        verify(repository).insertComment(eq(BLOG_ID), eq(ME_ID), any(), content.capture(), eq(NOW));
        return content.getValue();
    }

    private void verifyNothingSaved() {
        verify(repository, never()).insertComment(anyLong(), anyLong(), any(), any(), any());
        verify(repository, never()).recountComments(anyLong());
        verifyNoInteractions(notifications, points, reports, media);
    }

    private void verifyNotEdited() {
        verify(repository, never()).updateComment(anyLong(), any(), any());
        verifyNoInteractions(notifications, points, reports, media);
    }

    private void verifyNothingDeleted() {
        verify(repository, never()).deleteComment(anyLong());
        verify(repository, never()).blankComment(anyLong(), any());
        verify(repository, never()).setBestComment(anyLong(), any());
        verify(repository, never()).recountComments(anyLong());
        verifyNoInteractions(points, reports, notifications, media);
    }

    // =================================================================================================================
    @Nested
    @TestMethodOrder(MethodOrderer.DisplayName.class)
    @DisplayName("01. comment – UC 1.8 Thêm bình luận (UTC-330 → UTC-395)")
    class AddComment {

        // ------------------------------------------------------------ normal flow

        @Test
        @DisplayName("UTC-330 Thành viên bình luận bài của người khác: lưu ngay, trả về mã mới, đếm lại số bình luận")
        void utc330_memberCommentsOnAnotherMembersPost() {
            long id = service.comment(ME, "POST", BLOG_ID, request(TEXT));

            assertThat(id).isEqualTo(NEW_ID);
            verify(repository).insertComment(BLOG_ID, ME_ID, null, TEXT, NOW);
            verify(repository).recountComments(BLOG_ID);
        }

        @Test
        @DisplayName("UTC-331 Nội dung được bỏ khoảng trắng hai đầu trước khi kiểm tra và lưu")
        void utc331_contentIsStrippedBeforeCheckAndSave() {
            service.comment(ME, "POST", BLOG_ID, request("  \n" + TEXT + "  \t"));

            verify(filter).check(TEXT, ME_ID);
            assertThat(insertedContent()).isEqualTo(TEXT);
        }

        @Test
        @DisplayName("UTC-332 Tác giả bài nhận đúng một thông báo NEW_COMMENT \"<tên> đã bình luận bài “<tiêu đề>” của bạn.\"")
        void utc332_postAuthorIsNotifiedOnce() {
            service.comment(ME, "POST", BLOG_ID, request(TEXT));

            verify(notifications).send(AUTHOR_ID, NotificationService.NEW_COMMENT, commented("Minh"), "BLOG", BLOG_ID);
            verify(notifications, times(1)).send(anyLong(), anyString(), anyString(), anyString(), any());
        }

        @Test
        @DisplayName("UTC-333 Trả lời bình luận của thành viên khác: lưu dưới bình luận gốc, báo cho tác giả bài và người viết bình luận")
        void utc333_replyIsSavedUnderItsParentAndBothWritersAreTold() {
            parent(COMMENT_ID + 1, "ACTIVE", "PUBLISHED");

            long id = service.comment(ME, "POST", BLOG_ID, reply(TEXT, COMMENT_ID + 1));

            assertThat(id).isEqualTo(NEW_ID);
            verify(repository).insertComment(BLOG_ID, ME_ID, COMMENT_ID + 1, TEXT, NOW);
            verify(notifications).send(AUTHOR_ID, NotificationService.NEW_COMMENT, commented("Minh"), "BLOG", BLOG_ID);
            verify(notifications).send(OTHER_ID, NotificationService.NEW_COMMENT, replied("Minh"), "BLOG", BLOG_ID);
            verify(notifications, times(2)).send(anyLong(), anyString(), anyString(), anyString(), any());
        }

        @Test
        @DisplayName("UTC-334 Trả lời bình luận của chính tác giả bài: tác giả chỉ nhận một thông báo")
        void utc334_replyToThePostAuthorsCommentNotifiesThemOnce() {
            when(repository.findComment(501L)).thenReturn(Optional.of(
                    comment(501L, BLOG_ID, null, AUTHOR_ID, "ACTIVE", "PUBLISHED")));

            service.comment(ME, "POST", BLOG_ID, reply(TEXT, 501L));

            verify(notifications).send(AUTHOR_ID, NotificationService.NEW_COMMENT, commented("Minh"), "BLOG", BLOG_ID);
            verify(notifications, times(1)).send(anyLong(), anyString(), anyString(), anyString(), any());
        }

        @Test
        @DisplayName("UTC-335 Tác giả tự bình luận bài của mình: không ai nhận thông báo")
        void utc335_authorCommentingOwnPostNotifiesNobody() {
            long id = service.comment(AUTHOR, "POST", BLOG_ID, request(TEXT));

            assertThat(id).isEqualTo(NEW_ID);
            verify(repository).insertComment(BLOG_ID, AUTHOR_ID, null, TEXT, NOW);
            verifyNoInteractions(notifications);
        }

        @Test
        @DisplayName("UTC-336 Tự trả lời bình luận của mình trên bài người khác: chỉ tác giả bài được báo")
        void utc336_replyToOwnCommentNotifiesOnlyThePostAuthor() {
            service.comment(ME, "POST", BLOG_ID, reply(TEXT, COMMENT_ID));

            verify(repository).insertComment(BLOG_ID, ME_ID, COMMENT_ID, TEXT, NOW);
            verify(notifications).send(AUTHOR_ID, NotificationService.NEW_COMMENT, commented("Minh"), "BLOG", BLOG_ID);
            verify(notifications, never()).send(eq(ME_ID), anyString(), anyString(), anyString(), any());
        }

        @Test
        @DisplayName("UTC-337 Tác giả bài trả lời bình luận của thành viên khác: chỉ người viết bình luận được báo")
        void utc337_authorReplyingNotifiesOnlyTheCommentWriter() {
            parent(COMMENT_ID + 1, "ACTIVE", "PUBLISHED");

            service.comment(AUTHOR, "POST", BLOG_ID, reply(TEXT, COMMENT_ID + 1));

            verify(notifications).send(OTHER_ID, NotificationService.NEW_COMMENT, replied("Lan"), "BLOG", BLOG_ID);
            verify(notifications, never()).send(eq(AUTHOR_ID), anyString(), anyString(), anyString(), any());
        }

        @Test
        @DisplayName("UTC-338 Bình luận không cộng điểm, không mở báo cáo, không đụng tới tệp media")
        void utc338_commentEarnsNoPointsAndTouchesNoReportOrMedia() {
            service.comment(ME, "POST", BLOG_ID, request(TEXT));

            verifyNoInteractions(points, reports, media);
        }

        @Test
        @DisplayName("UTC-339 Thứ tự: đọc tài khoản → khóa bài → lọc nội dung → khóa ngày của người viết → lưu → đếm lại → báo")
        void utc339_stepsRunInTheLockingOrder() {
            service.comment(ME, "POST", BLOG_ID, request(TEXT));

            InOrder order = inOrder(repository, filter, notifications);
            order.verify(repository).member(ME_ID);
            order.verify(repository).lockBlog(BLOG_ID);
            order.verify(filter).check(TEXT, ME_ID);
            order.verify(repository).lockUserDay(ME_ID, TODAY);
            order.verify(repository).lastCommentAt(ME_ID);
            order.verify(repository).insertComment(BLOG_ID, ME_ID, null, TEXT, NOW);
            order.verify(repository).recountComments(BLOG_ID);
            order.verify(notifications).send(eq(AUTHOR_ID), anyString(), anyString(), anyString(), any());
            verify(repository, never()).findBlog(anyLong());
        }

        // ------------------------------------------------------------ content

        @Test
        @DisplayName("UTC-340 Nội dung trống, chỉ khoảng trắng hoặc null (3 lần): \"Hãy viết nội dung bình luận.\", không lưu gì")
        void utc340_blankContentIsRefused() {
            for (String blank : new String[] {"", "   \n\t ", null}) {
                assertApiError(() -> service.comment(ME, "POST", BLOG_ID, request(blank)), HttpStatus.BAD_REQUEST, EMPTY);
            }
            verifyNoInteractions(filter);
            verify(repository, never()).lockUserDay(anyLong(), any());
            verifyNothingSaved();
        }

        @Test
        @DisplayName("UTC-341 Không có thân yêu cầu (request = null): \"Hãy viết nội dung bình luận.\"")
        void utc341_missingRequestIsRefused() {
            assertApiError(() -> service.comment(ME, "POST", BLOG_ID, null), HttpStatus.BAD_REQUEST, EMPTY);
            verifyNothingSaved();
        }

        @Test
        @DisplayName("UTC-342 Nội dung đúng 1 ký tự: được đăng")
        void utc342_oneCharacterIsAccepted() {
            assertThat(service.comment(ME, "POST", BLOG_ID, request("?"))).isEqualTo(NEW_ID);
            assertThat(insertedContent()).isEqualTo("?");
        }

        @Test
        @DisplayName("UTC-343 Nội dung đúng 2.000 ký tự: được đăng")
        void utc343_exactly2000CharactersAreAccepted() {
            String text = "a".repeat(2000);

            assertThat(service.comment(ME, "POST", BLOG_ID, request(text))).isEqualTo(NEW_ID);
            assertThat(insertedContent()).hasSize(2000);
        }

        @Test
        @DisplayName("UTC-344 2.000 ký tự kèm khoảng trắng hai đầu: khoảng trắng không bị tính, lưu đúng 2.000 ký tự")
        void utc344_surroundingSpacesAreNotCounted() {
            service.comment(ME, "POST", BLOG_ID, request("   " + "a".repeat(2000) + "   "));

            assertThat(insertedContent()).isEqualTo("a".repeat(2000));
        }

        @Test
        @DisplayName("UTC-345 Nội dung 2.001 ký tự: \"Bình luận tối đa 2.000 ký tự.\", không lưu gì")
        void utc345_2001CharactersAreRefused() {
            assertApiError(() -> service.comment(ME, "POST", BLOG_ID, request("a".repeat(2001))),
                    HttpStatus.BAD_REQUEST, TOO_LONG);
            verifyNoInteractions(filter);
            verifyNothingSaved();
        }

        // ------------------------------------------------------------ who may comment

        @Test
        @DisplayName("UTC-346 Khách (chưa đăng nhập): 401, không đọc hay ghi gì")
        void utc346_guestIsRefused() {
            assertApiError(() -> service.comment(null, "POST", BLOG_ID, request(TEXT)), HttpStatus.UNAUTHORIZED,
                    MSG_LOGIN);
            verifyNoInteractions(repository, filter, notifications, points);
        }

        @Test
        @DisplayName("UTC-347 Tài khoản nhân viên (Moderator, BTV tiếng Nhật, Admin – 3 lần): 403 MSG18, bài không bị khóa")
        void utc347_staffAccountsAreRefused() {
            for (AppUserPrincipal staff : List.of(MODERATOR, EDITOR, ADMIN)) {
                assertApiError(() -> service.comment(staff, "POST", BLOG_ID, request(TEXT)), HttpStatus.FORBIDDEN,
                        MSG18);
            }
            verify(repository, never()).lockBlog(anyLong());
            verifyNothingSaved();
        }

        @Test
        @DisplayName("UTC-348 Tài khoản bị khóa hoặc đã xóa (2 lần): 403 MSG18, không lưu gì")
        void utc348_lockedOrDeletedAccountIsRefused() {
            for (String status : List.of("LOCKED", "DELETED")) {
                account(ME_ID, "USER", status, NOW.minus(Duration.ofDays(30)));
                assertApiError(() -> service.comment(ME, "POST", BLOG_ID, request(TEXT)), HttpStatus.FORBIDDEN, MSG18);
            }
            verify(repository, never()).lockBlog(anyLong());
            verifyNothingSaved();
        }

        @Test
        @DisplayName("UTC-349 Phiên còn nhưng tài khoản không còn trong bảng users: 401 \"Bạn chưa đăng nhập.\"")
        void utc349_sessionWithoutAccountRowIsRefused() {
            when(repository.member(ME_ID)).thenReturn(Optional.empty());

            assertApiError(() -> service.comment(ME, "POST", BLOG_ID, request(TEXT)), HttpStatus.UNAUTHORIZED,
                    MSG_NO_SESSION);
            verifyNothingSaved();
        }

        // ------------------------------------------------------------ post no longer available

        @Test
        @DisplayName("UTC-350 Bài không tồn tại hoặc đã bị xóa: 404 MSG19")
        void utc350_missingPostAnswersMsg19() {
            when(repository.lockBlog(BLOG_ID)).thenReturn(Optional.empty());

            assertApiError(() -> service.comment(ME, "POST", BLOG_ID, request(TEXT)), HttpStatus.NOT_FOUND, MSG19);
            verifyNothingSaved();
        }

        @Test
        @DisplayName("UTC-351 Bài đang quét, bị từ chối, bị ẩn hoặc bị gỡ (4 lần): 404 MSG19, không lưu gì")
        void utc351_unpublishedPostAnswersMsg19() {
            for (String status : List.of("PROCESSING", "REJECTED", "HIDDEN", "REMOVED")) {
                when(repository.lockBlog(BLOG_ID)).thenReturn(Optional.of(post(status, "ACTIVE")));
                assertApiError(() -> service.comment(ME, "POST", BLOG_ID, request(TEXT)), HttpStatus.NOT_FOUND, MSG19);
            }
            verifyNoInteractions(filter);
            verifyNothingSaved();
        }

        @Test
        @DisplayName("UTC-352 Tác giả bình luận bài đang quét ảnh/video của chính mình: 404 MSG19")
        void utc352_authorCannotCommentOnOwnProcessingPost() {
            when(repository.lockBlog(BLOG_ID)).thenReturn(Optional.of(post("PROCESSING", "ACTIVE")));

            assertApiError(() -> service.comment(AUTHOR, "POST", BLOG_ID, request(TEXT)), HttpStatus.NOT_FOUND, MSG19);
            verifyNothingSaved();
        }

        @Test
        @DisplayName("UTC-353 Tác giả bài bị khóa hoặc đã xóa tài khoản (2 lần): bài bị ẩn, 404 MSG19")
        void utc353_postOfLockedOrDeletedAuthorAnswersMsg19() {
            for (String authorStatus : List.of("LOCKED", "DELETED")) {
                when(repository.lockBlog(BLOG_ID)).thenReturn(Optional.of(post("PUBLISHED", authorStatus)));
                assertApiError(() -> service.comment(ME, "POST", BLOG_ID, request(TEXT)), HttpStatus.NOT_FOUND, MSG19);
            }
            verifyNothingSaved();
        }

        @Test
        @DisplayName("UTC-354 Mã của một câu hỏi Hỏi-Đáp gửi qua Blog (kind POST): 404 MSG19")
        void utc354_questionIdThroughTheBlogAnswersMsg19() {
            when(repository.lockBlog(QUESTION_ID)).thenReturn(Optional.of(
                    blog(QUESTION_ID, "QA", "PUBLISHED", AUTHOR_ID, "ACTIVE", null)));

            assertApiError(() -> service.comment(ME, "POST", QUESTION_ID, request(TEXT)), HttpStatus.NOT_FOUND, MSG19);
            verifyNothingSaved();
        }

        @Test
        @DisplayName("UTC-355 Bài không còn được báo trước lỗi nội dung trống (thứ tự kiểm tra)")
        void utc355_missingPostIsReportedBeforeBlankContent() {
            when(repository.lockBlog(BLOG_ID)).thenReturn(Optional.of(post("HIDDEN", "ACTIVE")));

            assertApiError(() -> service.comment(ME, "POST", BLOG_ID, request("   ")), HttpStatus.NOT_FOUND, MSG19);
        }

        // ------------------------------------------------------------ replies

        @Test
        @DisplayName("UTC-356 Trả lời bình luận không tồn tại: \"Bình luận bạn trả lời không còn nữa.\"")
        void utc356_replyToMissingCommentIsRefused() {
            when(repository.findComment(999L)).thenReturn(Optional.empty());

            assertApiError(() -> service.comment(ME, "POST", BLOG_ID, reply(TEXT, 999L)), HttpStatus.BAD_REQUEST,
                    PARENT_GONE);
            verifyNothingSaved();
        }

        @Test
        @DisplayName("UTC-357 Trả lời bình luận bị ẩn vì báo cáo, bị gỡ, đang xử lý (3 lần): \"Bình luận bạn trả lời không còn nữa.\"")
        void utc357_replyToCommentNotShownIsRefused() {
            for (String status : List.of("HIDDEN", "REMOVED", "PROCESSING")) {
                parent(501L, "ACTIVE", status);
                assertApiError(() -> service.comment(ME, "POST", BLOG_ID, reply(TEXT, 501L)), HttpStatus.BAD_REQUEST,
                        PARENT_GONE);
            }
            verifyNothingSaved();
        }

        @Test
        @DisplayName("UTC-358 Trả lời bình luận thuộc một bài khác: \"Bình luận bạn trả lời không còn nữa.\"")
        void utc358_replyToCommentOfAnotherPostIsRefused() {
            when(repository.findComment(501L)).thenReturn(Optional.of(
                    comment(501L, 101L, null, OTHER_ID, "ACTIVE", "PUBLISHED")));

            assertApiError(() -> service.comment(ME, "POST", BLOG_ID, reply(TEXT, 501L)), HttpStatus.BAD_REQUEST,
                    PARENT_GONE);
            verifyNothingSaved();
        }

        @Test
        @DisplayName("UTC-359 Trả lời bình luận của tài khoản bị khóa hoặc đã xóa (2 lần): \"Bình luận bạn trả lời không còn nữa.\"")
        void utc359_replyToCommentOfLockedOrDeletedAccountIsRefused() {
            for (String authorStatus : List.of("LOCKED", "DELETED")) {
                parent(501L, authorStatus, "PUBLISHED");
                assertApiError(() -> service.comment(ME, "POST", BLOG_ID, reply(TEXT, 501L)), HttpStatus.BAD_REQUEST,
                        PARENT_GONE);
            }
            verifyNothingSaved();
        }

        @Test
        @DisplayName("UTC-360 Bình luận gốc ở độ sâu 19: câu trả lời (độ sâu 20) được đăng")
        void utc360_replyAtDepth20IsAccepted() {
            long parentId = chain(19);

            assertThat(service.comment(ME, "POST", BLOG_ID, reply(TEXT, parentId))).isEqualTo(NEW_ID);
            verify(repository).insertComment(BLOG_ID, ME_ID, parentId, TEXT, NOW);
            verify(repository, times(19)).parentIdOf(anyLong());
        }

        @Test
        @DisplayName("UTC-361 Bình luận gốc ở độ sâu 20: \"Chuỗi trả lời đã quá dài. Hãy trả lời một bình luận ở phía trên.\"")
        void utc361_replyAtDepth21IsRefused() {
            long parentId = chain(20);

            assertApiError(() -> service.comment(ME, "POST", BLOG_ID, reply(TEXT, parentId)), HttpStatus.BAD_REQUEST,
                    TOO_DEEP);
            verifyNoInteractions(filter);
            verifyNothingSaved();
        }

        @Test
        @DisplayName("UTC-362 Chuỗi cũ sâu 25 tầng: bị từ chối, chỉ đi ngược lên tối đa 20 bước")
        void utc362_deeperOldChainIsRefusedWithABoundedWalk() {
            long parentId = chain(25);

            assertApiError(() -> service.comment(ME, "POST", BLOG_ID, reply(TEXT, parentId)), HttpStatus.BAD_REQUEST,
                    TOO_DEEP);
            verify(repository, times(20)).parentIdOf(anyLong());
            verifyNothingSaved();
        }

        @Test
        @DisplayName("UTC-363 Bình luận mới (không có parentId): không đọc bình luận gốc, không tính độ sâu")
        void utc363_topLevelCommentReadsNoParent() {
            service.comment(ME, "POST", BLOG_ID, request(TEXT));

            verify(repository, never()).findComment(anyLong());
            verify(repository, never()).parentIdOf(anyLong());
        }

        @Test
        @DisplayName("UTC-364 Bình luận gốc không còn được báo trước từ khóa cấm (thứ tự kiểm tra)")
        void utc364_goneParentIsReportedBeforeBannedKeyword() {
            when(repository.findComment(999L)).thenReturn(Optional.empty());
            filterRefuses("Vào fb88 chơi đi", MSG36);

            assertApiError(() -> service.comment(ME, "POST", BLOG_ID, reply("Vào fb88 chơi đi", 999L)),
                    HttpStatus.BAD_REQUEST, PARENT_GONE);
            verifyNoInteractions(filter);
        }

        // ------------------------------------------------------------ community rules (MSG36, BR-22)

        @Test
        @DisplayName("UTC-365 Từ khóa cấm: MSG36 nêu đúng từ khóa, không lưu, không đọc giới hạn, không báo ai")
        void utc365_bannedKeywordIsRefused() {
            filterRefuses("Vào fb88 chơi đi mọi người", MSG36);

            assertApiError(() -> service.comment(ME, "POST", BLOG_ID, request("Vào fb88 chơi đi mọi người")),
                    HttpStatus.BAD_REQUEST, MSG36);
            verify(repository, never()).lockUserDay(anyLong(), any());
            verify(repository, never()).lastCommentAt(anyLong());
            verifyNothingSaved();
        }

        @Test
        @DisplayName("UTC-366 Link khi chưa có tag định danh: \"Chỉ tài khoản đã có tag định danh mới được chèn link.\"")
        void utc366_linkWithoutIdentityTagIsRefused() {
            filterRefuses("Xem thêm ở https://example.com/huong-dan nhé", LINK_NEEDS_TAG);

            assertApiError(() -> service.comment(ME, "POST", BLOG_ID,
                    request("Xem thêm ở https://example.com/huong-dan nhé")), HttpStatus.BAD_REQUEST, LINK_NEEDS_TAG);
            verifyNothingSaved();
        }

        @Test
        @DisplayName("UTC-367 Số điện thoại hoặc Zalo khi chưa có tag định danh (2 lần): bị từ chối, không lưu gì")
        void utc367_contactWithoutIdentityTagIsRefused() {
            for (String text : List.of("Cần gì cứ gọi 090 1234 5678", "ib zalo 0901 234 567")) {
                filterRefuses(text, CONTACT_NEEDS_TAG);
                assertApiError(() -> service.comment(ME, "POST", BLOG_ID, request(text)), HttpStatus.BAD_REQUEST,
                        CONTACT_NEEDS_TAG);
            }
            verifyNothingSaved();
        }

        @Test
        @DisplayName("UTC-368 Link rút gọn (cấm với mọi người, kể cả có tag): bị từ chối, không lưu gì")
        void utc368_shortLinkIsRefused() {
            filterRefuses("Xem ở https://bit.ly/abc123 nhé", SHORT_LINK);

            assertApiError(() -> service.comment(ME, "POST", BLOG_ID, request("Xem ở https://bit.ly/abc123 nhé")),
                    HttpStatus.BAD_REQUEST, SHORT_LINK);
            verifyNothingSaved();
        }

        @Test
        @DisplayName("UTC-369 Thành viên có tag định danh gửi link đầy đủ: bộ lọc cho qua, bình luận được lưu")
        void utc369_taggedMemberMayPostAFullLink() {
            String text = "Xem thêm ở https://example.com/huong-dan nhé";

            assertThat(service.comment(ME, "POST", BLOG_ID, request(text))).isEqualTo(NEW_ID);
            verify(filter).check(text, ME_ID);
            verify(repository).insertComment(BLOG_ID, ME_ID, null, text, NOW);
        }

        @Test
        @DisplayName("UTC-370 Từ khóa cấm được báo trước giới hạn 15 giây (thứ tự kiểm tra)")
        void utc370_bannedKeywordIsReportedBefore15SecondLimit() {
            lastCommentAgo(Duration.ofSeconds(5));
            filterRefuses("Ghé fb88 nhé", MSG36);

            assertApiError(() -> service.comment(ME, "POST", BLOG_ID, request("Ghé fb88 nhé")), HttpStatus.BAD_REQUEST,
                    MSG36);
            verifyNothingSaved();
        }

        // ------------------------------------------------------------ one new comment every 15 seconds

        @Test
        @DisplayName("UTC-371 Bình luận 5 giây sau bình luận trước: 429 \"…Vui lòng đợi 10 giây rồi thử lại.\", không lưu")
        void utc371_secondCommentWithin15SecondsIsRefused() {
            lastCommentAgo(Duration.ofSeconds(5));

            assertApiError(() -> service.comment(ME, "POST", BLOG_ID, request(TEXT)), HttpStatus.TOO_MANY_REQUESTS,
                    tooFast(10));
            verify(repository).lockUserDay(ME_ID, TODAY);
            verifyNothingSaved();
        }

        @Test
        @DisplayName("UTC-372 Bình luận ngay cùng thời điểm bình luận trước: đợi 15 giây")
        void utc372_sameMomentWaits15Seconds() {
            lastCommentAgo(Duration.ZERO);

            assertApiError(() -> service.comment(ME, "POST", BLOG_ID, request(TEXT)), HttpStatus.TOO_MANY_REQUESTS,
                    tooFast(15));
            verifyNothingSaved();
        }

        @Test
        @DisplayName("UTC-373 14 giây và 14,5 giây sau bình luận trước (2 lần): đợi 1 giây (làm tròn lên)")
        void utc373_remainingSecondIsRoundedUp() {
            for (Duration ago : List.of(Duration.ofSeconds(14), Duration.ofMillis(14_500))) {
                lastCommentAgo(ago);
                assertApiError(() -> service.comment(ME, "POST", BLOG_ID, request(TEXT)), HttpStatus.TOO_MANY_REQUESTS,
                        tooFast(1));
            }
            verifyNothingSaved();
        }

        @Test
        @DisplayName("UTC-374 Đúng 15 giây sau bình luận trước: được đăng")
        void utc374_exactly15SecondsLaterIsAccepted() {
            lastCommentAgo(Duration.ofSeconds(15));

            assertThat(service.comment(ME, "POST", BLOG_ID, request(TEXT))).isEqualTo(NEW_ID);
        }

        @Test
        @DisplayName("UTC-375 Giờ bình luận trước lệch về tương lai 30 giây: thời gian chờ không quá 15 giây")
        void utc375_waitIsCappedAt15Seconds() {
            when(repository.lastCommentAt(ME_ID)).thenReturn(NOW.plusSeconds(30));

            assertApiError(() -> service.comment(ME, "POST", BLOG_ID, request(TEXT)), HttpStatus.TOO_MANY_REQUESTS,
                    tooFast(15));
            verifyNothingSaved();
        }

        @Test
        @DisplayName("UTC-376 Chưa từng bình luận: không bị giới hạn 15 giây")
        void utc376_firstEverCommentIsAccepted() {
            when(repository.lastCommentAt(ME_ID)).thenReturn(null);

            assertThat(service.comment(ME, "POST", BLOG_ID, request(TEXT))).isEqualTo(NEW_ID);
        }

        // ------------------------------------------------------------ new accounts: 20 a Japan day

        @Test
        @DisplayName("UTC-377 Tài khoản 6 ngày tuổi đã có 19 bình luận hôm nay: bình luận thứ 20 được đăng")
        void utc377_newAccount20thCommentOfTheDayIsAccepted() {
            account(ME_ID, "USER", "ACTIVE", NOW.minus(Duration.ofDays(6)));
            commentsToday(19);

            assertThat(service.comment(ME, "POST", BLOG_ID, request(TEXT))).isEqualTo(NEW_ID);
        }

        @Test
        @DisplayName("UTC-378 Tài khoản 6 ngày tuổi đã có 20 bình luận hôm nay: 429 MSG44, không lưu")
        void utc378_newAccount21stCommentOfTheDayIsRefused() {
            account(ME_ID, "USER", "ACTIVE", NOW.minus(Duration.ofDays(6)));
            commentsToday(20);

            assertApiError(() -> service.comment(ME, "POST", BLOG_ID, request(TEXT)), HttpStatus.TOO_MANY_REQUESTS,
                    DAILY_LIMIT);
            verifyNothingSaved();
        }

        @Test
        @DisplayName("UTC-379 Số bình luận được đếm theo ngày Nhật: [19/11 15:00 UTC, 20/11 15:00 UTC)")
        void utc379_dailyCountUsesTheJapanDay() {
            account(ME_ID, "USER", "ACTIVE", NOW.minus(Duration.ofDays(6)));

            service.comment(ME, "POST", BLOG_ID, request(TEXT));

            verify(repository).countComments(ME_ID, DAY_START, DAY_END);
            verify(settings).getInt(SystemSettings.NEW_ACCOUNT_DAYS, 7);
        }

        @Test
        @DisplayName("UTC-380 Lúc 00:30 giờ Nhật ngày 20/11 (19/11 15:30 UTC): khóa và đếm theo ngày 20/11")
        void utc380_justAfterJapanMidnightCountsTheNewDay() {
            Instant at = Instant.parse("2026-11-19T15:30:00Z");
            BlogActionService early = serviceAt(at);
            account(ME_ID, "USER", "ACTIVE", at.minus(Duration.ofDays(6)));

            early.comment(ME, "POST", BLOG_ID, request(TEXT));

            verify(repository).lockUserDay(ME_ID, TODAY);
            verify(repository).countComments(ME_ID, DAY_START, DAY_END);
            verify(repository).insertComment(BLOG_ID, ME_ID, null, TEXT, at);
        }

        @Test
        @DisplayName("UTC-381 Tài khoản 8 ngày tuổi đã có 30 bình luận hôm nay: không bị giới hạn, không đếm")
        void utc381_olderAccountHasNoDailyLimit() {
            account(ME_ID, "USER", "ACTIVE", NOW.minus(Duration.ofDays(8)));
            commentsToday(30);

            assertThat(service.comment(ME, "POST", BLOG_ID, request(TEXT))).isEqualTo(NEW_ID);
            verify(repository, never()).countComments(anyLong(), any(), any());
        }

        @Test
        @DisplayName("UTC-382 Tài khoản đúng 7 ngày tuổi: không còn là tài khoản mới")
        void utc382_accountExactly7DaysOldIsNotNew() {
            account(ME_ID, "USER", "ACTIVE", NOW.minus(Duration.ofDays(7)));
            commentsToday(20);

            assertThat(service.comment(ME, "POST", BLOG_ID, request(TEXT))).isEqualTo(NEW_ID);
            verify(repository, never()).countComments(anyLong(), any(), any());
        }

        @Test
        @DisplayName("UTC-383 Tài khoản 7 ngày trừ 1 giây tuổi đã có 20 bình luận hôm nay: vẫn là tài khoản mới, 429 MSG44")
        void utc383_accountOneSecondUnder7DaysIsStillNew() {
            account(ME_ID, "USER", "ACTIVE", NOW.minus(Duration.ofDays(7)).plusSeconds(1));
            commentsToday(20);

            assertApiError(() -> service.comment(ME, "POST", BLOG_ID, request(TEXT)), HttpStatus.TOO_MANY_REQUESTS,
                    DAILY_LIMIT);
            verifyNothingSaved();
        }

        @Test
        @DisplayName("UTC-384 Cài đặt new_account_days = 3, tài khoản 2 ngày tuổi có 20 bình luận: MSG44 ghi \"dưới 3 ngày\"")
        void utc384_newAccountDaysSettingIsUsedInTheRuleAndMessage() {
            when(settings.getInt(SystemSettings.NEW_ACCOUNT_DAYS, 7)).thenReturn(3);
            account(ME_ID, "USER", "ACTIVE", NOW.minus(Duration.ofDays(2)));
            commentsToday(20);

            assertApiError(() -> service.comment(ME, "POST", BLOG_ID, request(TEXT)), HttpStatus.TOO_MANY_REQUESTS,
                    "Bạn đã đạt giới hạn bình luận trong ngày của tài khoản mới (dưới 3 ngày: tối đa 20 bình luận mỗi "
                            + "ngày). Vui lòng thử lại vào ngày mai.");
            verifyNothingSaved();
        }

        @Test
        @DisplayName("UTC-385 Cài đặt new_account_days = 3, tài khoản 5 ngày tuổi có 20 bình luận: không bị giới hạn")
        void utc385_accountOlderThanTheSettingHasNoLimit() {
            when(settings.getInt(SystemSettings.NEW_ACCOUNT_DAYS, 7)).thenReturn(3);
            account(ME_ID, "USER", "ACTIVE", NOW.minus(Duration.ofDays(5)));
            commentsToday(20);

            assertThat(service.comment(ME, "POST", BLOG_ID, request(TEXT))).isEqualTo(NEW_ID);
        }

        @Test
        @DisplayName("UTC-386 Không rõ ngày tạo tài khoản (created_at = null): không coi là tài khoản mới")
        void utc386_unknownCreationDateIsNotANewAccount() {
            account(ME_ID, "USER", "ACTIVE", null);
            commentsToday(25);

            assertThat(service.comment(ME, "POST", BLOG_ID, request(TEXT))).isEqualTo(NEW_ID);
            verify(repository, never()).countComments(anyLong(), any(), any());
        }

        // ------------------------------------------------------------ the same text on other posts

        @Test
        @DisplayName("UTC-387 Cùng đoạn dài đã có ở 2 bài khác trong 24 giờ: vẫn được đăng")
        void utc387_sameLongTextOnTwoOtherPostsIsAccepted() {
            earlierElsewhere(new CommentText(101L, LONG_TEXT), new CommentText(102L, LONG_TEXT));

            assertThat(service.comment(ME, "POST", BLOG_ID, request(LONG_TEXT))).isEqualTo(NEW_ID);
        }

        @Test
        @DisplayName("UTC-388 Cùng đoạn dài đã có ở 3 bài khác trong 24 giờ: \"Bạn đã gửi nội dung này ở nhiều bài khác…\"")
        void utc388_sameLongTextOnThreeOtherPostsIsRefused() {
            earlierElsewhere(new CommentText(101L, LONG_TEXT), new CommentText(102L, LONG_TEXT),
                    new CommentText(103L, LONG_TEXT));

            assertApiError(() -> service.comment(ME, "POST", BLOG_ID, request(LONG_TEXT)), HttpStatus.BAD_REQUEST,
                    REPEATED);
            verifyNothingSaved();
        }

        @Test
        @DisplayName("UTC-389 Khác chữ hoa, dấu tiếng Việt và dấu câu vẫn là cùng nội dung: bị từ chối")
        void utc389_caseAccentsAndPunctuationDoNotMakeANewText() {
            earlierElsewhere(new CommentText(101L, LONG_TEXT), new CommentText(102L, LONG_TEXT),
                    new CommentText(103L, LONG_TEXT));
            String variant = "MINH CO KINH NGHIEM lam thu tuc nay roi!!! Ban cu nhan hoi minh nhe, moi nguoi oi.";

            assertApiError(() -> service.comment(ME, "POST", BLOG_ID, request(variant)), HttpStatus.BAD_REQUEST,
                    REPEATED);
            verifyNothingSaved();
        }

        @Test
        @DisplayName("UTC-390 Nhiều bản trên cùng một bài khác chỉ tính là một bài: 3 bản trên 2 bài vẫn được đăng")
        void utc390_copiesOnOneOtherPostCountAsOnePost() {
            earlierElsewhere(new CommentText(101L, LONG_TEXT), new CommentText(101L, LONG_TEXT),
                    new CommentText(102L, LONG_TEXT));

            assertThat(service.comment(ME, "POST", BLOG_ID, request(LONG_TEXT))).isEqualTo(NEW_ID);
        }

        @Test
        @DisplayName("UTC-391 Bình luận khác nội dung trên 3 bài khác không bị tính là lặp")
        void utc391_differentTextsElsewhereDoNotCount() {
            earlierElsewhere(new CommentText(101L, "Mình cũng từng thuê nhà ở Saitama, giá rẻ hơn Tokyo nhiều."),
                    new CommentText(102L, "Bạn nên hỏi thêm công ty bất động sản gần ga nhé mọi người."),
                    new CommentText(103L, "Tiền lễ và tiền cọc thường là hai tháng tiền nhà đó bạn."));

            assertThat(service.comment(ME, "POST", BLOG_ID, request(LONG_TEXT))).isEqualTo(NEW_ID);
        }

        @Test
        @DisplayName("UTC-392 Đoạn ngắn \"Cảm ơn bạn đã chia sẻ nhé!\" (dưới 30 chữ cái/chữ số): không kiểm tra lặp")
        void utc392_shortTextIsNeverCheckedForRepeats() {
            String thanks = "Cảm ơn bạn đã chia sẻ nhé!";
            earlierElsewhere(new CommentText(101L, thanks), new CommentText(102L, thanks),
                    new CommentText(103L, thanks), new CommentText(104L, thanks));

            assertThat(service.comment(ME, "POST", BLOG_ID, request(thanks))).isEqualTo(NEW_ID);
            verify(repository, never()).recentCommentsElsewhere(anyLong(), anyLong(), any(), anyInt());
        }

        @Test
        @DisplayName("UTC-393 Đoạn đúng 30 chữ cái/chữ số đã có ở 3 bài khác: bị tính lặp, bị từ chối")
        void utc393_textOfExactly30LettersIsChecked() {
            String text = "Cảm ơn bạn đã chia sẻ kinh nghiệm thật";
            assertThat(VietnameseText.squash(text)).hasSize(30);
            earlierElsewhere(new CommentText(101L, text), new CommentText(102L, text), new CommentText(103L, text));

            assertApiError(() -> service.comment(ME, "POST", BLOG_ID, request(text)), HttpStatus.BAD_REQUEST, REPEATED);
            verifyNothingSaved();
        }

        @Test
        @DisplayName("UTC-394 Đoạn 29 chữ cái/chữ số đã có ở 3 bài khác: không bị tính lặp, được đăng")
        void utc394_textOf29LettersIsNotChecked() {
            String text = "Cảm ơn bạn đã chia sẻ kinh nghiệm hay";
            assertThat(VietnameseText.squash(text)).hasSize(29);
            earlierElsewhere(new CommentText(101L, text), new CommentText(102L, text), new CommentText(103L, text));

            assertThat(service.comment(ME, "POST", BLOG_ID, request(text))).isEqualTo(NEW_ID);
            verify(repository, never()).recentCommentsElsewhere(anyLong(), anyLong(), any(), anyInt());
        }

        @Test
        @DisplayName("UTC-395 Kiểm tra lặp đọc tối đa 500 bình luận mới nhất trong 24 giờ, trừ bài đang bình luận")
        void utc395_repeatCheckReadsTheLast24HoursOfOtherPosts() {
            service.comment(ME, "POST", BLOG_ID, request(LONG_TEXT));

            verify(repository).recentCommentsElsewhere(ME_ID, BLOG_ID, NOW.minus(Duration.ofHours(24)), 500);
        }
    }

    // =================================================================================================================
    @Nested
    @TestMethodOrder(MethodOrderer.DisplayName.class)
    @DisplayName("02. updateComment – UC 1.9 Cập nhật bình luận (UTC-396 → UTC-422)")
    class UpdateComment {

        private static final String NEW_TEXT = "Bình luận đã sửa lại cho rõ hơn";

        @Test
        @DisplayName("UTC-396 Người viết sửa bình luận của mình: lưu nội dung mới, trả về mã bài để tải lại")
        void utc396_writerEditsOwnComment() {
            long blogId = service.updateComment(ME, COMMENT_ID, new CommentUpdateRequest(NEW_TEXT));

            assertThat(blogId).isEqualTo(BLOG_ID);
            verify(repository).updateComment(COMMENT_ID, NEW_TEXT, NOW);
            verify(repository).findBlog(BLOG_ID);
            verify(repository, never()).lockBlog(anyLong());
        }

        @Test
        @DisplayName("UTC-397 Nội dung mới được bỏ khoảng trắng hai đầu rồi qua bộ lọc từ khóa và link")
        void utc397_newTextIsStrippedAndFiltered() {
            service.updateComment(ME, COMMENT_ID, new CommentUpdateRequest("  " + NEW_TEXT + "  \n"));

            verify(filter).check(NEW_TEXT, ME_ID);
            verify(repository).updateComment(COMMENT_ID, NEW_TEXT, NOW);
        }

        @Test
        @DisplayName("UTC-398 Sửa không báo ai, không đổi điểm, không đếm lại số bình luận, không đụng tới lượt thích")
        void utc398_editIsQuiet() {
            service.updateComment(ME, COMMENT_ID, new CommentUpdateRequest(NEW_TEXT));

            verifyNoInteractions(notifications, points, reports, media);
            verify(repository, never()).recountComments(anyLong());
            verify(repository, never()).setCommentLike(anyLong(), anyLong(), anyBoolean(), any());
            verify(repository, never()).setBestComment(anyLong(), any());
        }

        @Test
        @DisplayName("UTC-399 Người viết sửa câu trả lời (reply) của mình dưới một bình luận: được lưu")
        void utc399_writerEditsOwnReply() {
            when(repository.findComment(510L)).thenReturn(Optional.of(
                    comment(510L, BLOG_ID, COMMENT_ID + 1, ME_ID, "ACTIVE", "PUBLISHED")));

            assertThat(service.updateComment(ME, 510L, new CommentUpdateRequest("Câu trả lời đã sửa")))
                    .isEqualTo(BLOG_ID);
            verify(repository).updateComment(510L, "Câu trả lời đã sửa", NOW);
        }

        @Test
        @DisplayName("UTC-400 Giới hạn đăng không áp dụng khi sửa (2 giây sau bình luận trước, tài khoản mới đủ 20 bình luận, nội dung lặp)")
        void utc400_postingLimitsDoNotApplyToEdits() {
            account(ME_ID, "USER", "ACTIVE", NOW.minus(Duration.ofDays(2)));
            lastCommentAgo(Duration.ofSeconds(2));
            commentsToday(20);
            earlierElsewhere(new CommentText(101L, LONG_TEXT), new CommentText(102L, LONG_TEXT),
                    new CommentText(103L, LONG_TEXT));

            assertThat(service.updateComment(ME, COMMENT_ID, new CommentUpdateRequest(LONG_TEXT))).isEqualTo(BLOG_ID);
            verify(repository).updateComment(COMMENT_ID, LONG_TEXT, NOW);
            verify(repository, never()).lockUserDay(anyLong(), any());
            verify(repository, never()).lastCommentAt(anyLong());
            verify(repository, never()).countComments(anyLong(), any(), any());
            verify(repository, never()).recentCommentsElsewhere(anyLong(), anyLong(), any(), anyInt());
        }

        @Test
        @DisplayName("UTC-401 Nội dung trống, chỉ khoảng trắng hoặc null (3 lần): \"Hãy viết nội dung bình luận.\", giữ nguyên")
        void utc401_blankTextIsRefused() {
            for (String blank : new String[] {"", "   ", null}) {
                assertApiError(() -> service.updateComment(ME, COMMENT_ID, new CommentUpdateRequest(blank)),
                        HttpStatus.BAD_REQUEST, EMPTY);
            }
            verifyNoInteractions(filter);
            verifyNotEdited();
        }

        @Test
        @DisplayName("UTC-402 Không có thân yêu cầu (request = null): \"Hãy viết nội dung bình luận.\"")
        void utc402_missingRequestIsRefused() {
            assertApiError(() -> service.updateComment(ME, COMMENT_ID, null), HttpStatus.BAD_REQUEST, EMPTY);
            verifyNotEdited();
        }

        @Test
        @DisplayName("UTC-403 Sửa thành đúng 2.000 ký tự: được lưu")
        void utc403_exactly2000CharactersAreSaved() {
            service.updateComment(ME, COMMENT_ID, new CommentUpdateRequest("a".repeat(2000)));

            verify(repository).updateComment(COMMENT_ID, "a".repeat(2000), NOW);
        }

        @Test
        @DisplayName("UTC-404 Sửa thành 2.001 ký tự: \"Bình luận tối đa 2.000 ký tự.\", giữ nguyên")
        void utc404_2001CharactersAreRefused() {
            assertApiError(() -> service.updateComment(ME, COMMENT_ID, new CommentUpdateRequest("a".repeat(2001))),
                    HttpStatus.BAD_REQUEST, TOO_LONG);
            verifyNotEdited();
        }

        @Test
        @DisplayName("UTC-405 Nội dung mới chứa từ khóa cấm: MSG36, giữ nguyên")
        void utc405_bannedKeywordIsRefused() {
            filterRefuses("Chơi ở fb88 đi", MSG36);

            assertApiError(() -> service.updateComment(ME, COMMENT_ID, new CommentUpdateRequest("Chơi ở fb88 đi")),
                    HttpStatus.BAD_REQUEST, MSG36);
            verifyNotEdited();
        }

        @Test
        @DisplayName("UTC-406 Nội dung mới có link khi chưa có tag định danh: bị từ chối, giữ nguyên")
        void utc406_linkWithoutIdentityTagIsRefused() {
            filterRefuses("Xem https://example.com nhé", LINK_NEEDS_TAG);

            assertApiError(() -> service.updateComment(ME, COMMENT_ID,
                    new CommentUpdateRequest("Xem https://example.com nhé")), HttpStatus.BAD_REQUEST, LINK_NEEDS_TAG);
            verifyNotEdited();
        }

        @Test
        @DisplayName("UTC-407 Nội dung mới có số điện thoại / Zalo khi chưa có tag định danh: bị từ chối, giữ nguyên")
        void utc407_contactWithoutIdentityTagIsRefused() {
            filterRefuses("ib zalo 0901 234 567", CONTACT_NEEDS_TAG);

            assertApiError(() -> service.updateComment(ME, COMMENT_ID, new CommentUpdateRequest("ib zalo 0901 234 567")),
                    HttpStatus.BAD_REQUEST, CONTACT_NEEDS_TAG);
            verifyNotEdited();
        }

        @Test
        @DisplayName("UTC-408 Nội dung mới có link rút gọn: bị từ chối, giữ nguyên")
        void utc408_shortLinkIsRefused() {
            filterRefuses("Xem https://bit.ly/abc123 nhé", SHORT_LINK);

            assertApiError(() -> service.updateComment(ME, COMMENT_ID,
                    new CommentUpdateRequest("Xem https://bit.ly/abc123 nhé")), HttpStatus.BAD_REQUEST, SHORT_LINK);
            verifyNotEdited();
        }

        @Test
        @DisplayName("UTC-409 Bình luận không tồn tại: 404 \"Không tìm thấy bình luận này.\"")
        void utc409_missingCommentIsNotFound() {
            when(repository.findComment(999L)).thenReturn(Optional.empty());

            assertApiError(() -> service.updateComment(ME, 999L, new CommentUpdateRequest(NEW_TEXT)),
                    HttpStatus.NOT_FOUND, COMMENT_NOT_FOUND);
            verifyNotEdited();
        }

        @Test
        @DisplayName("UTC-410 Bình luận bị ẩn vì báo cáo, bị gỡ / chỉ còn chỗ trống, đang xử lý (3 lần): 404 \"Không tìm thấy bình luận này.\"")
        void utc410_commentNotShownIsNotFound() {
            for (String status : List.of("HIDDEN", "REMOVED", "PROCESSING")) {
                when(repository.findComment(COMMENT_ID)).thenReturn(Optional.of(
                        comment(COMMENT_ID, BLOG_ID, null, ME_ID, "ACTIVE", status)));
                assertApiError(() -> service.updateComment(ME, COMMENT_ID, new CommentUpdateRequest(NEW_TEXT)),
                        HttpStatus.NOT_FOUND, COMMENT_NOT_FOUND);
            }
            verifyNotEdited();
        }

        @Test
        @DisplayName("UTC-411 Bài bị ẩn, bị gỡ hoặc đang quét (3 lần): 404 MSG19, giữ nguyên")
        void utc411_commentOnPostNoLongerShownAnswersMsg19() {
            for (String status : List.of("HIDDEN", "REMOVED", "PROCESSING")) {
                when(repository.findBlog(BLOG_ID)).thenReturn(Optional.of(post(status, "ACTIVE")));
                assertApiError(() -> service.updateComment(ME, COMMENT_ID, new CommentUpdateRequest(NEW_TEXT)),
                        HttpStatus.NOT_FOUND, MSG19);
            }
            verifyNotEdited();
        }

        @Test
        @DisplayName("UTC-412 Tác giả bài bị khóa hoặc đã xóa tài khoản (2 lần): 404 MSG19, giữ nguyên")
        void utc412_postOfLockedOrDeletedAuthorAnswersMsg19() {
            for (String authorStatus : List.of("LOCKED", "DELETED")) {
                when(repository.findBlog(BLOG_ID)).thenReturn(Optional.of(post("PUBLISHED", authorStatus)));
                assertApiError(() -> service.updateComment(ME, COMMENT_ID, new CommentUpdateRequest(NEW_TEXT)),
                        HttpStatus.NOT_FOUND, MSG19);
            }
            verifyNotEdited();
        }

        @Test
        @DisplayName("UTC-413 Bài đã bị xóa: 404 MSG19")
        void utc413_deletedPostAnswersMsg19() {
            when(repository.findBlog(BLOG_ID)).thenReturn(Optional.empty());

            assertApiError(() -> service.updateComment(ME, COMMENT_ID, new CommentUpdateRequest(NEW_TEXT)),
                    HttpStatus.NOT_FOUND, MSG19);
            verifyNotEdited();
        }

        @Test
        @DisplayName("UTC-414 Thành viên khác sửa bình luận: 403 MSG18, giữ nguyên")
        void utc414_otherMemberCannotEdit() {
            assertApiError(() -> service.updateComment(OTHER, COMMENT_ID, new CommentUpdateRequest("Người khác sửa")),
                    HttpStatus.FORBIDDEN, MSG18);
            verifyNoInteractions(filter);
            verifyNotEdited();
        }

        @Test
        @DisplayName("UTC-415 Tác giả bài sửa bình luận của người khác: 403 MSG18, giữ nguyên")
        void utc415_postAuthorCannotEditOthersComment() {
            assertApiError(() -> service.updateComment(AUTHOR, COMMENT_ID, new CommentUpdateRequest("Tác giả bài sửa")),
                    HttpStatus.FORBIDDEN, MSG18);
            verifyNotEdited();
        }

        @Test
        @DisplayName("UTC-416 Khách (chưa đăng nhập): 401, không đọc bình luận")
        void utc416_guestIsRefused() {
            assertApiError(() -> service.updateComment(null, COMMENT_ID, new CommentUpdateRequest(NEW_TEXT)),
                    HttpStatus.UNAUTHORIZED, MSG_LOGIN);
            verifyNoInteractions(repository, filter);
        }

        @Test
        @DisplayName("UTC-417 Tài khoản nhân viên (Moderator, BTV tiếng Nhật, Admin – 3 lần): 403 MSG18, không đọc bình luận")
        void utc417_staffAccountsAreRefused() {
            for (AppUserPrincipal staff : List.of(MODERATOR, EDITOR, ADMIN)) {
                assertApiError(() -> service.updateComment(staff, COMMENT_ID, new CommentUpdateRequest(NEW_TEXT)),
                        HttpStatus.FORBIDDEN, MSG18);
            }
            verify(repository, never()).findComment(anyLong());
            verifyNotEdited();
        }

        @Test
        @DisplayName("UTC-418 Người viết bị khóa hoặc đã xóa tài khoản (2 lần): 403 MSG18, giữ nguyên")
        void utc418_lockedOrDeletedWriterIsRefused() {
            for (String status : List.of("LOCKED", "DELETED")) {
                account(ME_ID, "USER", status, NOW.minus(Duration.ofDays(30)));
                assertApiError(() -> service.updateComment(ME, COMMENT_ID, new CommentUpdateRequest(NEW_TEXT)),
                        HttpStatus.FORBIDDEN, MSG18);
            }
            verifyNotEdited();
        }

        @Test
        @DisplayName("UTC-419 Không phải người viết lại gửi nội dung trống: báo MSG18 trước lỗi nội dung")
        void utc419_permissionIsCheckedBeforeTheText() {
            assertApiError(() -> service.updateComment(OTHER, COMMENT_ID, new CommentUpdateRequest("   ")),
                    HttpStatus.FORBIDDEN, MSG18);
        }

        @Test
        @DisplayName("UTC-420 Bình luận không còn lại gửi nội dung trống: báo \"Không tìm thấy bình luận này.\" trước")
        void utc420_missingCommentIsCheckedBeforeTheText() {
            when(repository.findComment(999L)).thenReturn(Optional.empty());

            assertApiError(() -> service.updateComment(ME, 999L, new CommentUpdateRequest("")), HttpStatus.NOT_FOUND,
                    COMMENT_NOT_FOUND);
        }

        @Test
        @DisplayName("UTC-421 Bài bị ẩn và người sửa không phải người viết: báo MSG19 trước MSG18")
        void utc421_hiddenPostIsReportedBeforePermission() {
            when(repository.findBlog(BLOG_ID)).thenReturn(Optional.of(post("HIDDEN", "ACTIVE")));

            assertApiError(() -> service.updateComment(OTHER, COMMENT_ID, new CommentUpdateRequest(NEW_TEXT)),
                    HttpStatus.NOT_FOUND, MSG19);
        }

        @Test
        @DisplayName("UTC-422 Sửa lại bình luận đã sửa trước đó, hai lần liên tiếp: lần nào cũng được lưu (số lần sửa không bị giới hạn)")
        void utc422_alreadyEditedCommentCanBeEditedAgain() {
            when(repository.findComment(COMMENT_ID)).thenReturn(Optional.of(new CommentRow(COMMENT_ID, BLOG_ID, null,
                    ME_ID, "Minh", null, 50, "ACTIVE", "Bình luận đã sửa một lần", "PUBLISHED", true, 4,
                    NOW.minus(Duration.ofHours(1)))));

            service.updateComment(ME, COMMENT_ID, new CommentUpdateRequest("Lần sửa thứ nhất"));
            service.updateComment(ME, COMMENT_ID, new CommentUpdateRequest("Lần sửa thứ hai"));

            InOrder order = inOrder(repository);
            order.verify(repository).updateComment(COMMENT_ID, "Lần sửa thứ nhất", NOW);
            order.verify(repository).updateComment(COMMENT_ID, "Lần sửa thứ hai", NOW);
        }
    }

    // =================================================================================================================
    @Nested
    @TestMethodOrder(MethodOrderer.DisplayName.class)
    @DisplayName("03. deleteComment – UC 1.10 Xóa bình luận (UTC-423 → UTC-446)")
    class DeleteComment {

        @Test
        @DisplayName("UTC-423 Xóa bình luận không có câu trả lời: xóa hẳn, đếm lại số bình luận, trả về mã bài")
        void utc423_commentWithoutRepliesIsDeleted() {
            long blogId = service.deleteComment(ME, COMMENT_ID);

            assertThat(blogId).isEqualTo(BLOG_ID);
            verify(repository).deleteComment(COMMENT_ID);
            verify(repository).recountComments(BLOG_ID);
            verify(repository, never()).blankComment(anyLong(), any());
            verify(repository, never()).setBestComment(anyLong(), any());
        }

        @Test
        @DisplayName("UTC-424 Xóa bình luận đã có câu trả lời: để lại chỗ trống \"Bình luận đã bị xóa.\", giữ các câu trả lời")
        void utc424_commentWithRepliesBecomesAPlaceholder() {
            when(repository.hasReplies(COMMENT_ID)).thenReturn(true);

            assertThat(service.deleteComment(ME, COMMENT_ID)).isEqualTo(BLOG_ID);
            verify(repository).blankComment(COMMENT_ID, NOW);
            verify(repository, never()).deleteComment(anyLong());
            verify(repository).recountComments(BLOG_ID);
        }

        @Test
        @DisplayName("UTC-425 Xóa một câu trả lời (reply): bình luận cha vẫn hiển thị nên được giữ nguyên")
        void utc425_deletingAReplyKeepsItsPublishedParent() {
            CommentRow myReply = comment(510L, BLOG_ID, COMMENT_ID + 1, ME_ID, "ACTIVE", "PUBLISHED");
            when(repository.findComment(510L)).thenReturn(Optional.of(myReply));
            when(repository.lockComment(510L)).thenReturn(Optional.of(myReply));
            parent(COMMENT_ID + 1, "ACTIVE", "PUBLISHED");

            service.deleteComment(ME, 510L);

            verify(repository).deleteComment(510L);
            verify(repository, never()).deleteComment(COMMENT_ID + 1);
            verify(repository).recountComments(BLOG_ID);
        }

        @Test
        @DisplayName("UTC-426 Xóa câu trả lời cuối dưới chuỗi chỗ trống: các chỗ trống không còn câu trả lời cũng bị xóa")
        void utc426_emptyPlaceholdersAboveAreRemoved() {
            CommentRow last = comment(503L, BLOG_ID, 502L, ME_ID, "ACTIVE", "PUBLISHED");
            when(repository.findComment(503L)).thenReturn(Optional.of(last));
            when(repository.lockComment(503L)).thenReturn(Optional.of(last));
            when(repository.findComment(502L)).thenReturn(Optional.of(comment(502L, BLOG_ID, 501L, OTHER_ID, "ACTIVE", "REMOVED")));
            when(repository.findComment(501L)).thenReturn(Optional.of(comment(501L, BLOG_ID, null, AUTHOR_ID, "ACTIVE", "REMOVED")));
            when(repository.isBlankPlaceholder(502L)).thenReturn(true);
            when(repository.isBlankPlaceholder(501L)).thenReturn(true);

            service.deleteComment(ME, 503L);

            InOrder order = inOrder(repository);
            order.verify(repository).deleteComment(503L);
            order.verify(repository).deleteComment(502L);
            order.verify(repository).deleteComment(501L);
            order.verify(repository).recountComments(BLOG_ID);
        }

        @Test
        @DisplayName("UTC-427 Chỗ trống phía trên còn câu trả lời khác: chỗ trống được giữ")
        void utc427_placeholderWithOtherRepliesIsKept() {
            CommentRow last = comment(503L, BLOG_ID, 502L, ME_ID, "ACTIVE", "PUBLISHED");
            when(repository.findComment(503L)).thenReturn(Optional.of(last));
            when(repository.lockComment(503L)).thenReturn(Optional.of(last));
            when(repository.findComment(502L)).thenReturn(Optional.of(comment(502L, BLOG_ID, null, OTHER_ID, "ACTIVE", "REMOVED")));
            when(repository.isBlankPlaceholder(502L)).thenReturn(true);
            when(repository.hasReplies(502L)).thenReturn(true);

            service.deleteComment(ME, 503L);

            verify(repository).deleteComment(503L);
            verify(repository, never()).deleteComment(502L);
        }

        @Test
        @DisplayName("UTC-428 Bình luận cha bị Moderator gỡ (vẫn giữ nội dung, không phải chỗ trống): được giữ")
        void utc428_moderatorRemovedParentIsKept() {
            CommentRow last = comment(503L, BLOG_ID, 502L, ME_ID, "ACTIVE", "PUBLISHED");
            when(repository.findComment(503L)).thenReturn(Optional.of(last));
            when(repository.lockComment(503L)).thenReturn(Optional.of(last));
            when(repository.findComment(502L)).thenReturn(Optional.of(new CommentRow(502L, BLOG_ID, null, OTHER_ID,
                    "Hoa", null, 50, "ACTIVE", "Nội dung bị Moderator gỡ", "REMOVED", false, 0, NOW.minusSeconds(3600))));
            when(repository.isBlankPlaceholder(502L)).thenReturn(false);

            service.deleteComment(ME, 503L);

            verify(repository).deleteComment(503L);
            verify(repository, never()).deleteComment(502L);
        }

        @Test
        @DisplayName("UTC-429 Báo cáo đang mở về bình luận được đóng (\"Tác giả đã tự xóa nội dung.\")")
        void utc429_openReportCasesAreClosed() {
            service.deleteComment(ME, COMMENT_ID);

            verify(reports).closeCasesOf("COMMENT", List.of(COMMENT_ID));
        }

        @Test
        @DisplayName("UTC-430 Điểm gắn với bình luận được thu lại (CONTENT_DELETED), không ai nhận thông báo")
        void utc430_pointsAreWithdrawnAndNobodyIsNotified() {
            service.deleteComment(ME, COMMENT_ID);

            verify(points).withdrawAll(ME_ID, "COMMENT", COMMENT_ID, "CONTENT_DELETED");
            verifyNoInteractions(notifications, media);
        }

        @Test
        @DisplayName("UTC-431 Thứ tự: đọc bình luận → khóa bài → khóa bình luận → thu điểm → đóng báo cáo → xóa → đếm lại")
        void utc431_stepsRunInTheLockingOrder() {
            service.deleteComment(ME, COMMENT_ID);

            InOrder order = inOrder(repository, points, reports);
            order.verify(repository).member(ME_ID);
            order.verify(repository).findComment(COMMENT_ID);
            order.verify(repository).lockBlog(BLOG_ID);
            order.verify(repository).lockComment(COMMENT_ID);
            order.verify(points).withdrawAll(ME_ID, "COMMENT", COMMENT_ID, "CONTENT_DELETED");
            order.verify(reports).closeCasesOf("COMMENT", List.of(COMMENT_ID));
            order.verify(repository).hasReplies(COMMENT_ID);
            order.verify(repository).deleteComment(COMMENT_ID);
            order.verify(repository).recountComments(BLOG_ID);
        }

        @Test
        @DisplayName("UTC-432 Bình luận của mình còn đang xử lý (PROCESSING): xóa được")
        void utc432_processingCommentCanBeDeleted() {
            CommentRow processing = comment(COMMENT_ID, BLOG_ID, null, ME_ID, "ACTIVE", "PROCESSING");
            when(repository.findComment(COMMENT_ID)).thenReturn(Optional.of(processing));
            when(repository.lockComment(COMMENT_ID)).thenReturn(Optional.of(processing));

            assertThat(service.deleteComment(ME, COMMENT_ID)).isEqualTo(BLOG_ID);
            verify(repository).deleteComment(COMMENT_ID);
        }

        @Test
        @DisplayName("UTC-433 Bài bị ẩn, bị gỡ hoặc tác giả bài bị khóa sau khi bình luận (3 lần): người viết vẫn xóa được")
        void utc433_writerMayStillDeleteUnderAPostNoLongerShown() {
            List<BlogRow> changed = List.of(post("HIDDEN", "ACTIVE"), post("REMOVED", "ACTIVE"),
                    post("PUBLISHED", "LOCKED"));
            for (BlogRow blog : changed) {
                when(repository.lockBlog(BLOG_ID)).thenReturn(Optional.of(blog));
                assertThat(service.deleteComment(ME, COMMENT_ID)).isEqualTo(BLOG_ID);
            }
            verify(repository, times(3)).deleteComment(COMMENT_ID);
        }

        @Test
        @DisplayName("UTC-434 Bình luận không tồn tại: 404 \"Không tìm thấy bình luận này.\", không đổi gì")
        void utc434_missingCommentIsNotFound() {
            when(repository.findComment(999L)).thenReturn(Optional.empty());

            assertApiError(() -> service.deleteComment(ME, 999L), HttpStatus.NOT_FOUND, COMMENT_NOT_FOUND);
            verify(repository, never()).lockBlog(anyLong());
            verifyNothingDeleted();
        }

        @Test
        @DisplayName("UTC-435 Bình luận bị ẩn vì báo cáo, đã thành chỗ trống / bị gỡ, bị từ chối (3 lần): 404, không đổi gì")
        void utc435_commentNotDeletableIsNotFound() {
            for (String status : List.of("HIDDEN", "REMOVED", "REJECTED")) {
                when(repository.findComment(COMMENT_ID)).thenReturn(Optional.of(
                        comment(COMMENT_ID, BLOG_ID, null, ME_ID, "ACTIVE", status)));
                assertApiError(() -> service.deleteComment(ME, COMMENT_ID), HttpStatus.NOT_FOUND, COMMENT_NOT_FOUND);
            }
            verifyNothingDeleted();
        }

        @Test
        @DisplayName("UTC-436 Bài đã bị xóa giữa lúc đọc và lúc khóa: 404 \"Không tìm thấy bình luận này.\"")
        void utc436_postDeletedMeanwhileIsNotFound() {
            when(repository.lockBlog(BLOG_ID)).thenReturn(Optional.empty());

            assertApiError(() -> service.deleteComment(ME, COMMENT_ID), HttpStatus.NOT_FOUND, COMMENT_NOT_FOUND);
            verifyNothingDeleted();
        }

        @Test
        @DisplayName("UTC-437 Hai lần xóa gửi cùng lúc: lần sau thấy bình luận đã mất khi khóa, 404, không thu điểm hai lần")
        void utc437_secondDeleteAtTheSameMomentFindsNothing() {
            when(repository.lockComment(COMMENT_ID)).thenReturn(Optional.empty());

            assertApiError(() -> service.deleteComment(ME, COMMENT_ID), HttpStatus.NOT_FOUND, COMMENT_NOT_FOUND);
            verifyNothingDeleted();
        }

        @Test
        @DisplayName("UTC-438 Bình luận bị ẩn vì báo cáo giữa lúc đọc và lúc khóa: 404, không đổi gì")
        void utc438_commentHiddenMeanwhileIsNotFound() {
            when(repository.lockComment(COMMENT_ID)).thenReturn(Optional.of(
                    comment(COMMENT_ID, BLOG_ID, null, ME_ID, "ACTIVE", "HIDDEN")));

            assertApiError(() -> service.deleteComment(ME, COMMENT_ID), HttpStatus.NOT_FOUND, COMMENT_NOT_FOUND);
            verifyNothingDeleted();
        }

        @Test
        @DisplayName("UTC-439 Thành viên khác xóa bình luận: 403 MSG18, bài không bị khóa, không xóa gì")
        void utc439_otherMemberCannotDelete() {
            assertApiError(() -> service.deleteComment(OTHER, COMMENT_ID), HttpStatus.FORBIDDEN, MSG18);
            verify(repository, never()).lockBlog(anyLong());
            verifyNothingDeleted();
        }

        @Test
        @DisplayName("UTC-440 Tác giả bài xóa bình luận của người khác: 403 MSG18, không xóa gì")
        void utc440_postAuthorCannotDeleteOthersComment() {
            assertApiError(() -> service.deleteComment(AUTHOR, COMMENT_ID), HttpStatus.FORBIDDEN, MSG18);
            verifyNothingDeleted();
        }

        @Test
        @DisplayName("UTC-441 Khách (chưa đăng nhập): 401, không đọc hay xóa gì")
        void utc441_guestIsRefused() {
            assertApiError(() -> service.deleteComment(null, COMMENT_ID), HttpStatus.UNAUTHORIZED, MSG_LOGIN);
            verifyNoInteractions(repository);
            verifyNothingDeleted();
        }

        @Test
        @DisplayName("UTC-442 Tài khoản nhân viên (Moderator, BTV tiếng Nhật, Admin – 3 lần): 403 MSG18, không xóa gì")
        void utc442_staffAccountsAreRefused() {
            for (AppUserPrincipal staff : List.of(MODERATOR, EDITOR, ADMIN)) {
                assertApiError(() -> service.deleteComment(staff, COMMENT_ID), HttpStatus.FORBIDDEN, MSG18);
            }
            verify(repository, never()).findComment(anyLong());
            verifyNothingDeleted();
        }

        @Test
        @DisplayName("UTC-443 Người viết bị khóa hoặc đã xóa tài khoản (2 lần): 403 MSG18, không xóa gì")
        void utc443_lockedOrDeletedWriterIsRefused() {
            for (String status : List.of("LOCKED", "DELETED")) {
                account(ME_ID, "USER", status, NOW.minus(Duration.ofDays(30)));
                assertApiError(() -> service.deleteComment(ME, COMMENT_ID), HttpStatus.FORBIDDEN, MSG18);
            }
            verifyNothingDeleted();
        }

        @Test
        @DisplayName("UTC-444 Bình luận có câu trả lời: không xóa bình luận cha phía trên (chỉ làm trống chính nó)")
        void utc444_placeholderDoesNotWalkUpToParents() {
            CommentRow middle = comment(502L, BLOG_ID, 501L, ME_ID, "ACTIVE", "PUBLISHED");
            when(repository.findComment(502L)).thenReturn(Optional.of(middle));
            when(repository.lockComment(502L)).thenReturn(Optional.of(middle));
            when(repository.hasReplies(502L)).thenReturn(true);

            service.deleteComment(ME, 502L);

            verify(repository).blankComment(502L, NOW);
            verify(repository, never()).deleteComment(anyLong());
            verify(repository, never()).isBlankPlaceholder(anyLong());
        }

        @Test
        @DisplayName("UTC-445 Bình luận cha đã bị xóa hẳn trước đó: dừng dọn chỗ trống, không lỗi")
        void utc445_missingParentStopsTheCleanUp() {
            CommentRow orphan = comment(503L, BLOG_ID, 502L, ME_ID, "ACTIVE", "PUBLISHED");
            when(repository.findComment(503L)).thenReturn(Optional.of(orphan));
            when(repository.lockComment(503L)).thenReturn(Optional.of(orphan));
            when(repository.findComment(502L)).thenReturn(Optional.empty());

            assertThat(service.deleteComment(ME, 503L)).isEqualTo(BLOG_ID);
            verify(repository).deleteComment(503L);
            verify(repository, never()).deleteComment(502L);
        }

        @Test
        @DisplayName("UTC-446 Xóa bình luận trên Blog không đụng tới dấu \"câu trả lời hay nhất\"")
        void utc446_deletingABlogCommentLeavesNoBestMarkChange() {
            service.deleteComment(ME, COMMENT_ID);

            verify(repository, never()).setBestComment(anyLong(), any());
            verify(points, never()).add(anyLong(), anyInt(), anyString(), anyString(), any());
        }
    }
}
