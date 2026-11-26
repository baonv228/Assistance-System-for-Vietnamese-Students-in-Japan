package com.sep490.vsa.feature.blog;

import com.sep490.vsa.common.exception.ApiException;
import com.sep490.vsa.common.security.AppUserPrincipal;
import com.sep490.vsa.common.settings.SystemSettings;
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
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit tests of the answer actions of Hỏi-Đáp ({@link BlogActionService} on a question, {@code blogs.blog_type = 'QA'}):
 * UC 1.19 Submit Answer ({@code comment} with kind QA: an answer is a top-level comment, a reply hangs under it),
 * UC 1.20 Update Answer ({@code updateComment}, called with no kind through {@code /api/comments/{id}}) and UC 1.21
 * Delete Answer ({@code deleteComment}), written from the SRS use cases (Report 3), the use case tests in
 * {@code usecase/answer/*} and the rules of CLAUDE.md (Hỏi-Đáp: answers are comments, notifications with ref_type
 * QUESTION, the best answer gives +5 points and keeps its mark when edited; the comment limits of "New comments,
 * replies and answers").
 * <p>
 * The database is not used: {@link BlogDetailRepository}, {@link ContentFilter}, {@link ContentReportService},
 * {@link PointService}, {@link NotificationService}, {@link MediaService} and {@link SystemSettings} are mocks (the
 * keyword and link checks are stubbed with the real messages of {@link ContentFilter}); the clock is fixed at
 * 20/11/2026 12:00 Japan time.
 */
@TestClassOrder(ClassOrderer.DisplayName.class)
@DisplayName("BlogActionService – Câu trả lời Hỏi-Đáp: gửi, sửa, xóa (UC 1.19, 1.20, 1.21)")
class BlogActionServiceAnswerTest {

    /** 12:00 in Japan: the Japan date is 20/11/2026. */
    private static final Instant NOW = Instant.parse("2026-11-20T03:00:00Z");

    private static final long ASKER_ID = 1L;       // writer of the question, "Lan"
    private static final long ME_ID = 10L;         // the member who answers, "Minh"
    private static final long OTHER_ID = 11L;      // another member, "Hoa"
    private static final long MOD_ID = 20L;
    private static final long EDITOR_ID = 21L;
    private static final long ADMIN_ID = 22L;
    private static final long QUESTION_ID = 200L;
    private static final long BLOG_ID = 100L;
    private static final long ANSWER_ID = 500L;
    private static final long NEW_ID = 700L;

    private static final AppUserPrincipal ME = principal(ME_ID, "Minh", "USER");
    private static final AppUserPrincipal ASKER = principal(ASKER_ID, "Lan", "USER");
    private static final AppUserPrincipal OTHER = principal(OTHER_ID, "Hoa", "USER");
    private static final AppUserPrincipal MODERATOR = principal(MOD_ID, "Moderator", "MODERATOR");
    private static final AppUserPrincipal EDITOR = principal(EDITOR_ID, "BTV", "ACADEMIC_MANAGER");
    private static final AppUserPrincipal ADMIN = principal(ADMIN_ID, "Admin", "ADMIN");

    private static final String TITLE = "Làm thẻ cư trú ở văn phòng quận nào?";
    private static final String TEXT = "Bạn lên văn phòng quận mang theo hộ chiếu nhé.";
    private static final String LONG_TEXT =
            "Bạn nên mang theo thẻ cư trú và hộ chiếu khi đi làm thủ tục ở văn phòng quận.";

    private static final String MSG_LOGIN = "Bạn cần đăng nhập để thực hiện thao tác này.";
    private static final String MSG18 = "Bạn không có quyền thực hiện thao tác này.";
    private static final String MSG19 = "Không tìm thấy nội dung bạn yêu cầu. Bài viết có thể đã bị gỡ hoặc đã bị xóa.";
    private static final String EMPTY = "Hãy viết nội dung bình luận.";
    private static final String TOO_LONG = "Bình luận tối đa 2.000 ký tự.";
    private static final String PARENT_GONE = "Bình luận bạn trả lời không còn nữa.";
    private static final String TOO_DEEP = "Chuỗi trả lời đã quá dài. Hãy trả lời một bình luận ở phía trên.";
    private static final String COMMENT_NOT_FOUND = "Không tìm thấy bình luận này.";
    private static final String MSG36 =
            "Chưa đăng được: nội dung chứa từ khóa không được phép “vay nóng”. Hãy sửa rồi gửi lại.";
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
        service = new BlogActionService(repository, filter, reports, points, notifications, media, settings,
                Clock.fixed(NOW, ZoneOffset.UTC));

        account(ME_ID, "USER", "ACTIVE", NOW.minus(Duration.ofDays(30)));
        account(ASKER_ID, "USER", "ACTIVE", NOW.minus(Duration.ofDays(90)));
        account(OTHER_ID, "USER", "ACTIVE", NOW.minus(Duration.ofDays(60)));
        account(MOD_ID, "MODERATOR", "ACTIVE", NOW.minus(Duration.ofDays(200)));
        account(EDITOR_ID, "ACADEMIC_MANAGER", "ACTIVE", NOW.minus(Duration.ofDays(200)));
        account(ADMIN_ID, "ADMIN", "ACTIVE", NOW.minus(Duration.ofDays(200)));
        when(settings.getInt(SystemSettings.NEW_ACCOUNT_DAYS, 7)).thenReturn(7);

        question("PUBLISHED", "ACTIVE", null);
        when(repository.insertComment(anyLong(), anyLong(), any(), anyString(), any())).thenReturn(NEW_ID);
        when(repository.updateComment(anyLong(), anyString(), any())).thenReturn(true);

        // Answer 500: a published answer of "Minh" to question 200
        answerRow(ANSWER_ID, null, ME_ID, "PUBLISHED");
    }

    // ---------------------------------------------------------------- helpers

    private static AppUserPrincipal principal(long id, String name, String role) {
        return new AppUserPrincipal(id, "user" + id + "@example.org", name, role, "/");
    }

    private void account(long id, String role, String status, Instant createdAt) {
        when(repository.member(id)).thenReturn(Optional.of(new Member(role, status, createdAt)));
    }

    /** Question 200 of "Lan" as both reads return it (locked for answers, plain for edits). */
    private void question(String status, String askerStatus, Long bestCommentId) {
        BlogRow row = blog(QUESTION_ID, "QA", status, askerStatus, bestCommentId);
        when(repository.lockBlog(QUESTION_ID)).thenReturn(Optional.of(row));
        when(repository.findBlog(QUESTION_ID)).thenReturn(Optional.of(row));
    }

    private static BlogRow blog(long id, String type, String status, String authorStatus, Long best) {
        return new BlogRow(id, ASKER_ID, "Lan", null, 120, authorStatus, type, TITLE, "Mình cần làm gì trước?", status,
                false, false, best, 2, 1, NOW.minus(Duration.ofDays(1)));
    }

    private static CommentRow row(long id, long blogId, Long parentId, long authorId, String authorStatus,
                                  String status) {
        return new CommentRow(id, blogId, parentId, authorId, "User " + authorId, null, 50, authorStatus,
                "REMOVED".equals(status) ? "" : "Câu trả lời " + id, status, false, 1, NOW.minus(Duration.ofHours(2)));
    }

    /** An answer (or reply) on question 200, returned by both the plain and the locking read. */
    private CommentRow answerRow(long id, Long parentId, long authorId, String status) {
        CommentRow row = row(id, QUESTION_ID, parentId, authorId, "ACTIVE", status);
        when(repository.findComment(id)).thenReturn(Optional.of(row));
        when(repository.lockComment(id)).thenReturn(Optional.of(row));
        return row;
    }

    /** Answers and replies of "Hoa" with ids 600 (depth 0) to 600 + {@code parentDepth}; returns the deepest id. */
    private long chain(int parentDepth) {
        for (int depth = 0; depth <= parentDepth; depth++) {
            long id = 600L + depth;
            Long up = depth == 0 ? null : id - 1;
            when(repository.parentIdOf(id)).thenReturn(up);
        }
        long deepest = 600L + parentDepth;
        when(repository.findComment(deepest)).thenReturn(Optional.of(
                row(deepest, QUESTION_ID, parentDepth == 0 ? null : deepest - 1, OTHER_ID, "ACTIVE", "PUBLISHED")));
        return deepest;
    }

    private static CommentRequest answer(String content) {
        return new CommentRequest(content, null);
    }

    private static CommentRequest reply(String content, long parentId) {
        return new CommentRequest(content, parentId);
    }

    private static String answered(String who) {
        return who + " đã trả lời câu hỏi “" + TITLE + "” của bạn.";
    }

    private static String commented(String who) {
        return who + " đã bình luận bài “" + TITLE + "” của bạn.";
    }

    private static String replied(String who) {
        return who + " đã trả lời bình luận của bạn trong “" + TITLE + "”.";
    }

    private void filterRefuses(String text, String message) {
        doThrow(ApiException.badRequest(message)).when(filter).check(text, ME_ID);
    }

    private void earlierElsewhere(CommentText... earlier) {
        when(repository.recentCommentsElsewhere(eq(ME_ID), eq(QUESTION_ID), any(), anyInt())).thenReturn(List.of(earlier));
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
    @DisplayName("01. comment (kind QA) – UC 1.19 Gửi câu trả lời (UTC-268 → UTC-302)")
    class SubmitAnswer {

        // ------------------------------------------------------------ normal flow

        @Test
        @DisplayName("UTC-268 Thành viên gửi câu trả lời: lưu ngay là bình luận cấp 1 của câu hỏi, trả về mã mới, đếm lại số câu trả lời")
        void utc268_memberAnswersAQuestion() {
            long id = service.comment(ME, "QA", QUESTION_ID, answer(TEXT));

            assertThat(id).isEqualTo(NEW_ID);
            verify(repository).lockBlog(QUESTION_ID);
            verify(repository).insertComment(QUESTION_ID, ME_ID, null, TEXT, NOW);
            verify(repository).recountComments(QUESTION_ID);
        }

        @Test
        @DisplayName("UTC-269 Người hỏi nhận \"<tên> đã trả lời câu hỏi “<tiêu đề>” của bạn.\" (NEW_COMMENT, mở câu hỏi: ref QUESTION)")
        void utc269_askerIsNotifiedWithAQuestionLink() {
            service.comment(ME, "QA", QUESTION_ID, answer(TEXT));

            verify(notifications).send(ASKER_ID, NotificationService.NEW_COMMENT, answered("Minh"), "QUESTION",
                    QUESTION_ID);
            verify(notifications, times(1)).send(anyLong(), anyString(), anyString(), anyString(), any());
        }

        @Test
        @DisplayName("UTC-270 Phản hồi dưới câu trả lời của người khác: lưu dưới câu trả lời, người hỏi và người viết câu trả lời đều được báo")
        void utc270_replyUnderAnAnswerNotifiesAskerAndAnswerWriter() {
            answerRow(501L, null, OTHER_ID, "PUBLISHED");

            long id = service.comment(ME, "QA", QUESTION_ID, reply("Quầy số 3 ở tầng mấy vậy bạn?", 501L));

            assertThat(id).isEqualTo(NEW_ID);
            verify(repository).insertComment(QUESTION_ID, ME_ID, 501L, "Quầy số 3 ở tầng mấy vậy bạn?", NOW);
            verify(notifications).send(ASKER_ID, NotificationService.NEW_COMMENT, commented("Minh"), "QUESTION",
                    QUESTION_ID);
            verify(notifications).send(OTHER_ID, NotificationService.NEW_COMMENT, replied("Minh"), "QUESTION",
                    QUESTION_ID);
            verify(notifications, times(2)).send(anyLong(), anyString(), anyString(), anyString(), any());
        }

        @Test
        @DisplayName("UTC-271 Người hỏi tự trả lời câu hỏi của mình: không ai được báo")
        void utc271_askerAnsweringOwnQuestionNotifiesNobody() {
            assertThat(service.comment(ASKER, "QA", QUESTION_ID, answer("Mình tự tìm được: phải đặt lịch trước.")))
                    .isEqualTo(NEW_ID);

            verifyNoInteractions(notifications);
        }

        @Test
        @DisplayName("UTC-272 Phản hồi dưới câu trả lời của chính người hỏi: người hỏi chỉ nhận một thông báo")
        void utc272_replyUnderTheAskersAnswerNotifiesThemOnce() {
            answerRow(501L, null, ASKER_ID, "PUBLISHED");

            service.comment(ME, "QA", QUESTION_ID, reply("Cảm ơn bạn đã chia sẻ cách làm.", 501L));

            verify(notifications).send(ASKER_ID, NotificationService.NEW_COMMENT, commented("Minh"), "QUESTION",
                    QUESTION_ID);
            verify(notifications, times(1)).send(anyLong(), anyString(), anyString(), anyString(), any());
        }

        @Test
        @DisplayName("UTC-273 Người hỏi phản hồi dưới câu trả lời của người khác: chỉ người viết câu trả lời được báo")
        void utc273_askerReplyingNotifiesOnlyTheAnswerWriter() {
            answerRow(501L, null, OTHER_ID, "PUBLISHED");

            service.comment(ASKER, "QA", QUESTION_ID, reply("Không có gì nhé.", 501L));

            verify(notifications).send(OTHER_ID, NotificationService.NEW_COMMENT, replied("Lan"), "QUESTION",
                    QUESTION_ID);
            verify(notifications, never()).send(eq(ASKER_ID), anyString(), anyString(), anyString(), any());
        }

        @Test
        @DisplayName("UTC-274 Gửi câu trả lời không được cộng điểm, không mở báo cáo, không đụng tới tệp media")
        void utc274_answeringEarnsNoPoints() {
            service.comment(ME, "QA", QUESTION_ID, answer(TEXT));

            verifyNoInteractions(points, reports, media);
        }

        @Test
        @DisplayName("UTC-275 Câu hỏi đã có câu trả lời hay nhất vẫn nhận thêm câu trả lời, dấu hay nhất giữ nguyên")
        void utc275_solvedQuestionStillTakesAnswers() {
            question("PUBLISHED", "ACTIVE", 499L);

            assertThat(service.comment(ME, "QA", QUESTION_ID, answer(TEXT))).isEqualTo(NEW_ID);
            verify(repository, never()).setBestComment(anyLong(), any());
        }

        // ------------------------------------------------------------ content

        @Test
        @DisplayName("UTC-276 Đúng 2.000 ký tự kèm khoảng trắng hai đầu: bỏ khoảng trắng, lưu đúng 2.000 ký tự")
        void utc276_exactly2000CharactersAfterStrippingAreSaved() {
            service.comment(ME, "QA", QUESTION_ID, answer("  " + "a".repeat(2000) + "  "));

            ArgumentCaptor<String> content = ArgumentCaptor.forClass(String.class);
            verify(repository).insertComment(eq(QUESTION_ID), eq(ME_ID), any(), content.capture(), eq(NOW));
            assertThat(content.getValue()).isEqualTo("a".repeat(2000));
        }

        @Test
        @DisplayName("UTC-277 Câu trả lời 2.001 ký tự: \"Bình luận tối đa 2.000 ký tự.\", không lưu gì")
        void utc277_2001CharactersAreRefused() {
            assertApiError(() -> service.comment(ME, "QA", QUESTION_ID, answer("a".repeat(2001))),
                    HttpStatus.BAD_REQUEST, TOO_LONG);
            verifyNothingSaved();
        }

        @Test
        @DisplayName("UTC-278 Nội dung trống, chỉ khoảng trắng hoặc null (3 lần): \"Hãy viết nội dung bình luận.\", không lưu gì")
        void utc278_blankAnswerIsRefused() {
            for (String blank : new String[] {"", "   \n\t ", null}) {
                assertApiError(() -> service.comment(ME, "QA", QUESTION_ID, answer(blank)), HttpStatus.BAD_REQUEST,
                        EMPTY);
            }
            verifyNoInteractions(filter);
            verifyNothingSaved();
        }

        @Test
        @DisplayName("UTC-279 Không có thân yêu cầu (request = null): \"Hãy viết nội dung bình luận.\"")
        void utc279_missingRequestIsRefused() {
            assertApiError(() -> service.comment(ME, "QA", QUESTION_ID, null), HttpStatus.BAD_REQUEST, EMPTY);
            verifyNothingSaved();
        }

        // ------------------------------------------------------------ question no longer available

        @Test
        @DisplayName("UTC-280 Câu hỏi đang quét ảnh/video: người hỏi và thành viên khác (2 lần) đều nhận 404 MSG19")
        void utc280_processingQuestionTakesNoAnswers() {
            question("PROCESSING", "ACTIVE", null);

            for (AppUserPrincipal who : List.of(ASKER, ME)) {
                assertApiError(() -> service.comment(who, "QA", QUESTION_ID, answer(TEXT)), HttpStatus.NOT_FOUND, MSG19);
            }
            verifyNothingSaved();
        }

        @Test
        @DisplayName("UTC-281 Câu hỏi bị ẩn, bị gỡ hoặc bị từ chối (3 lần): 404 MSG19, không lưu gì")
        void utc281_hiddenRemovedOrRejectedQuestionAnswersMsg19() {
            for (String status : List.of("HIDDEN", "REMOVED", "REJECTED")) {
                question(status, "ACTIVE", null);
                assertApiError(() -> service.comment(ME, "QA", QUESTION_ID, answer(TEXT)), HttpStatus.NOT_FOUND, MSG19);
            }
            verifyNothingSaved();
        }

        @Test
        @DisplayName("UTC-282 Câu hỏi đã bị xóa (không còn): 404 MSG19")
        void utc282_deletedQuestionAnswersMsg19() {
            when(repository.lockBlog(QUESTION_ID)).thenReturn(Optional.empty());

            assertApiError(() -> service.comment(ME, "QA", QUESTION_ID, answer(TEXT)), HttpStatus.NOT_FOUND, MSG19);
            verifyNothingSaved();
        }

        @Test
        @DisplayName("UTC-283 Người hỏi bị khóa hoặc đã xóa tài khoản (2 lần): câu hỏi coi như không còn, 404 MSG19")
        void utc283_questionOfLockedOrDeletedAskerAnswersMsg19() {
            for (String askerStatus : List.of("LOCKED", "DELETED")) {
                question("PUBLISHED", askerStatus, null);
                assertApiError(() -> service.comment(ME, "QA", QUESTION_ID, answer(TEXT)), HttpStatus.NOT_FOUND, MSG19);
            }
            verifyNothingSaved();
        }

        @Test
        @DisplayName("UTC-284 Mã của một bài Blog gửi qua Hỏi-Đáp (kind QA): 404 MSG19")
        void utc284_blogPostIdIsNotAQuestion() {
            when(repository.lockBlog(BLOG_ID)).thenReturn(Optional.of(blog(BLOG_ID, "POST", "PUBLISHED", "ACTIVE", null)));

            assertApiError(() -> service.comment(ME, "QA", BLOG_ID, answer(TEXT)), HttpStatus.NOT_FOUND, MSG19);
            verifyNothingSaved();
        }

        // ------------------------------------------------------------ who may answer

        @Test
        @DisplayName("UTC-285 Khách (chưa đăng nhập): 401, không đọc hay ghi gì")
        void utc285_guestIsRefused() {
            assertApiError(() -> service.comment(null, "QA", QUESTION_ID, answer(TEXT)), HttpStatus.UNAUTHORIZED,
                    MSG_LOGIN);
            verifyNoInteractions(repository, filter);
        }

        @Test
        @DisplayName("UTC-286 Tài khoản nhân viên (Moderator, BTV tiếng Nhật, Admin – 3 lần): 403 MSG18, câu hỏi không bị khóa")
        void utc286_staffAccountsAreRefused() {
            for (AppUserPrincipal staff : List.of(MODERATOR, EDITOR, ADMIN)) {
                assertApiError(() -> service.comment(staff, "QA", QUESTION_ID, answer(TEXT)), HttpStatus.FORBIDDEN,
                        MSG18);
            }
            verify(repository, never()).lockBlog(anyLong());
            verifyNothingSaved();
        }

        @Test
        @DisplayName("UTC-287 Tài khoản bị khóa hoặc đã xóa (2 lần): 403 MSG18, không lưu gì")
        void utc287_lockedOrDeletedAccountIsRefused() {
            for (String status : List.of("LOCKED", "DELETED")) {
                account(ME_ID, "USER", status, NOW.minus(Duration.ofDays(30)));
                assertApiError(() -> service.comment(ME, "QA", QUESTION_ID, answer(TEXT)), HttpStatus.FORBIDDEN, MSG18);
            }
            verifyNothingSaved();
        }

        // ------------------------------------------------------------ replies

        @Test
        @DisplayName("UTC-288 Phản hồi câu trả lời bị ẩn, thuộc câu hỏi khác, không tồn tại, của tài khoản bị khóa (4 lần): \"Bình luận bạn trả lời không còn nữa.\"")
        void utc288_replyToAnAnswerNoLongerShownIsRefused() {
            when(repository.findComment(501L)).thenReturn(Optional.of(row(501L, QUESTION_ID, null, OTHER_ID, "ACTIVE", "HIDDEN")));
            when(repository.findComment(502L)).thenReturn(Optional.of(row(502L, 201L, null, OTHER_ID, "ACTIVE", "PUBLISHED")));
            when(repository.findComment(503L)).thenReturn(Optional.empty());
            when(repository.findComment(504L)).thenReturn(Optional.of(row(504L, QUESTION_ID, null, OTHER_ID, "LOCKED", "PUBLISHED")));

            for (long parentId : List.of(501L, 502L, 503L, 504L)) {
                assertApiError(() -> service.comment(ME, "QA", QUESTION_ID, reply("Mình cũng thắc mắc", parentId)),
                        HttpStatus.BAD_REQUEST, PARENT_GONE);
            }
            verifyNothingSaved();
        }

        @Test
        @DisplayName("UTC-289 Phản hồi ở tầng 20 (dưới bình luận ở độ sâu 19): được đăng")
        void utc289_replyAtDepth20IsAccepted() {
            long parentId = chain(19);

            assertThat(service.comment(ME, "QA", QUESTION_ID, reply("Trả lời ở tầng 20", parentId))).isEqualTo(NEW_ID);
            verify(repository).insertComment(QUESTION_ID, ME_ID, parentId, "Trả lời ở tầng 20", NOW);
        }

        @Test
        @DisplayName("UTC-290 Phản hồi ở tầng 21 (dưới bình luận ở độ sâu 20): \"Chuỗi trả lời đã quá dài…\"")
        void utc290_replyAtDepth21IsRefused() {
            long parentId = chain(20);

            assertApiError(() -> service.comment(ME, "QA", QUESTION_ID, reply("Trả lời ở tầng 21", parentId)),
                    HttpStatus.BAD_REQUEST, TOO_DEEP);
            verifyNothingSaved();
        }

        // ------------------------------------------------------------ community rules

        @Test
        @DisplayName("UTC-291 Từ khóa cấm \"vay nóng\": MSG36 nêu đúng từ khóa, không lưu gì")
        void utc291_bannedKeywordIsRefused() {
            filterRefuses("Ai cần vay nóng thì nhắn mình nhé", MSG36);

            assertApiError(() -> service.comment(ME, "QA", QUESTION_ID, answer("Ai cần vay nóng thì nhắn mình nhé")),
                    HttpStatus.BAD_REQUEST, MSG36);
            verify(repository, never()).lockUserDay(anyLong(), any());
            verifyNothingSaved();
        }

        @Test
        @DisplayName("UTC-292 Link khi chưa có tag định danh: \"Chỉ tài khoản đã có tag định danh mới được chèn link.\"")
        void utc292_linkWithoutIdentityTagIsRefused() {
            filterRefuses("Xem hướng dẫn ở https://www.moj.go.jp/isa/", LINK_NEEDS_TAG);

            assertApiError(() -> service.comment(ME, "QA", QUESTION_ID,
                    answer("Xem hướng dẫn ở https://www.moj.go.jp/isa/")), HttpStatus.BAD_REQUEST, LINK_NEEDS_TAG);
            verifyNothingSaved();
        }

        @Test
        @DisplayName("UTC-293 Số điện thoại khi chưa có tag định danh: bị từ chối, không lưu gì")
        void utc293_phoneNumberWithoutIdentityTagIsRefused() {
            filterRefuses("Gọi mình số 0901234567 nhé", CONTACT_NEEDS_TAG);

            assertApiError(() -> service.comment(ME, "QA", QUESTION_ID, answer("Gọi mình số 0901234567 nhé")),
                    HttpStatus.BAD_REQUEST, CONTACT_NEEDS_TAG);
            verifyNothingSaved();
        }

        @Test
        @DisplayName("UTC-294 Link rút gọn (cấm cả khi có tag): bị từ chối, không lưu gì")
        void utc294_shortLinkIsRefused() {
            filterRefuses("Xem ở bit.ly/abc123 nhé", SHORT_LINK);

            assertApiError(() -> service.comment(ME, "QA", QUESTION_ID, answer("Xem ở bit.ly/abc123 nhé")),
                    HttpStatus.BAD_REQUEST, SHORT_LINK);
            verifyNothingSaved();
        }

        @Test
        @DisplayName("UTC-295 Thành viên có tag định danh gửi link đầy đủ: bộ lọc cho qua, câu trả lời được lưu")
        void utc295_taggedMemberMayPostAFullLink() {
            String text = "Xem hướng dẫn ở https://www.moj.go.jp/isa/";

            assertThat(service.comment(ME, "QA", QUESTION_ID, answer(text))).isEqualTo(NEW_ID);
            verify(filter).check(text, ME_ID);
        }

        // ------------------------------------------------------------ limits

        @Test
        @DisplayName("UTC-296 Câu trả lời thứ hai 10 giây sau câu trước: 429 \"…Vui lòng đợi 5 giây rồi thử lại.\"")
        void utc296_secondAnswerWithin15SecondsIsRefused() {
            when(repository.lastCommentAt(ME_ID)).thenReturn(NOW.minusSeconds(10));

            assertApiError(() -> service.comment(ME, "QA", QUESTION_ID, answer(TEXT)), HttpStatus.TOO_MANY_REQUESTS,
                    "Bạn bình luận hơi nhanh. Vui lòng đợi 5 giây rồi thử lại.");
            verifyNothingSaved();
        }

        @Test
        @DisplayName("UTC-297 Câu trả lời 16 giây sau câu trước: được đăng")
        void utc297_answer16SecondsLaterIsAccepted() {
            when(repository.lastCommentAt(ME_ID)).thenReturn(NOW.minusSeconds(16));

            assertThat(service.comment(ME, "QA", QUESTION_ID, answer(TEXT))).isEqualTo(NEW_ID);
        }

        @Test
        @DisplayName("UTC-298 Tài khoản 2 ngày tuổi đã có 20 bình luận/câu trả lời hôm nay: 429 MSG44")
        void utc298_newAccountDailyLimitApplies() {
            account(ME_ID, "USER", "ACTIVE", NOW.minus(Duration.ofDays(2)));
            when(repository.countComments(eq(ME_ID), any(), any())).thenReturn(20L);

            assertApiError(() -> service.comment(ME, "QA", QUESTION_ID, answer("Câu trả lời thứ 21 hôm nay")),
                    HttpStatus.TOO_MANY_REQUESTS, DAILY_LIMIT);
            verifyNothingSaved();
        }

        @Test
        @DisplayName("UTC-299 Tài khoản 30 ngày tuổi đã có 20 bình luận hôm nay: không bị giới hạn")
        void utc299_olderAccountHasNoDailyLimit() {
            when(repository.countComments(eq(ME_ID), any(), any())).thenReturn(20L);

            assertThat(service.comment(ME, "QA", QUESTION_ID, answer("Câu trả lời thứ 21 hôm nay"))).isEqualTo(NEW_ID);
            verify(repository, never()).countComments(anyLong(), any(), any());
        }

        @Test
        @DisplayName("UTC-300 Cùng nội dung dài đã có ở 3 câu hỏi khác (khác chữ hoa): \"Bạn đã gửi nội dung này ở nhiều bài khác…\"")
        void utc300_sameLongTextOnAFourthQuestionIsRefused() {
            earlierElsewhere(new CommentText(201L, LONG_TEXT), new CommentText(202L, LONG_TEXT),
                    new CommentText(203L, LONG_TEXT));

            assertApiError(() -> service.comment(ME, "QA", QUESTION_ID, answer(LONG_TEXT.toUpperCase())),
                    HttpStatus.BAD_REQUEST, REPEATED);
            verifyNothingSaved();
        }

        @Test
        @DisplayName("UTC-301 Cùng nội dung dài mới có ở 2 câu hỏi khác: câu trả lời thứ ba vẫn được đăng")
        void utc301_sameLongTextOnATHirdQuestionIsAccepted() {
            earlierElsewhere(new CommentText(201L, LONG_TEXT), new CommentText(202L, LONG_TEXT));

            assertThat(service.comment(ME, "QA", QUESTION_ID, answer(LONG_TEXT))).isEqualTo(NEW_ID);
            verify(repository).recentCommentsElsewhere(ME_ID, QUESTION_ID, NOW.minus(Duration.ofHours(24)), 500);
        }

        @Test
        @DisplayName("UTC-302 Lời cảm ơn ngắn \"Cảm ơn bạn đã chia sẻ\" lặp ở 3 câu hỏi khác: không bị tính là lặp")
        void utc302_shortThanksIsNeverRepeated() {
            String thanks = "Cảm ơn bạn đã chia sẻ";
            earlierElsewhere(new CommentText(201L, thanks), new CommentText(202L, thanks), new CommentText(203L, thanks));

            assertThat(service.comment(ME, "QA", QUESTION_ID, answer(thanks))).isEqualTo(NEW_ID);
        }
    }

    // =================================================================================================================
    @Nested
    @TestMethodOrder(MethodOrderer.DisplayName.class)
    @DisplayName("02. updateComment – UC 1.20 Cập nhật câu trả lời (UTC-303 → UTC-318)")
    class UpdateAnswer {

        private static final String NEW_TEXT = "Bạn lên văn phòng quận, nhớ mang hộ chiếu nhé.";

        @Test
        @DisplayName("UTC-303 Người viết sửa câu trả lời: lưu nội dung mới, trả về mã câu hỏi để tải lại")
        void utc303_writerEditsOwnAnswer() {
            long blogId = service.updateComment(ME, ANSWER_ID, new CommentUpdateRequest("  " + NEW_TEXT + "  "));

            assertThat(blogId).isEqualTo(QUESTION_ID);
            verify(filter).check(NEW_TEXT, ME_ID);
            verify(repository).updateComment(ANSWER_ID, NEW_TEXT, NOW);
            verify(repository).findBlog(QUESTION_ID);
        }

        @Test
        @DisplayName("UTC-304 Sửa câu trả lời hay nhất: giữ dấu \"Câu trả lời hay nhất\" và +5 điểm, không báo ai")
        void utc304_bestAnswerKeepsItsMarkAndPoints() {
            question("PUBLISHED", "ACTIVE", ANSWER_ID);

            assertThat(service.updateComment(ME, ANSWER_ID, new CommentUpdateRequest(NEW_TEXT))).isEqualTo(QUESTION_ID);
            verify(repository).updateComment(ANSWER_ID, NEW_TEXT, NOW);
            verify(repository, never()).setBestComment(anyLong(), any());
            verify(repository, never()).recountComments(anyLong());
            verifyNoInteractions(points, notifications, reports);
        }

        @Test
        @DisplayName("UTC-305 Người hỏi sửa phản hồi của mình dưới một câu trả lời: được lưu")
        void utc305_askerEditsOwnReplyUnderAnAnswer() {
            answerRow(510L, ANSWER_ID, ASKER_ID, "PUBLISHED");

            assertThat(service.updateComment(ASKER, 510L, new CommentUpdateRequest("Cảm ơn bạn, mình hỏi được rồi.")))
                    .isEqualTo(QUESTION_ID);
            verify(repository).updateComment(510L, "Cảm ơn bạn, mình hỏi được rồi.", NOW);
        }

        @Test
        @DisplayName("UTC-306 Giới hạn gửi không áp dụng khi sửa (sửa ngay trong 15 giây; tài khoản mới đã đủ 20 bình luận hôm nay)")
        void utc306_postingLimitsDoNotApplyToEdits() {
            account(ME_ID, "USER", "ACTIVE", NOW.minus(Duration.ofDays(2)));
            when(repository.lastCommentAt(ME_ID)).thenReturn(NOW.minusSeconds(1));
            when(repository.countComments(eq(ME_ID), any(), any())).thenReturn(20L);

            assertThat(service.updateComment(ME, ANSWER_ID, new CommentUpdateRequest(NEW_TEXT))).isEqualTo(QUESTION_ID);
            verify(repository).updateComment(ANSWER_ID, NEW_TEXT, NOW);
            verify(repository, never()).lockUserDay(anyLong(), any());
            verify(repository, never()).lastCommentAt(anyLong());
            verify(repository, never()).countComments(anyLong(), any(), any());
        }

        @Test
        @DisplayName("UTC-307 Nội dung trống, chỉ khoảng trắng hoặc null (3 lần): \"Hãy viết nội dung bình luận.\", nội dung cũ giữ nguyên")
        void utc307_blankTextIsRefused() {
            for (String blank : new String[] {"", "  \n ", null}) {
                assertApiError(() -> service.updateComment(ME, ANSWER_ID, new CommentUpdateRequest(blank)),
                        HttpStatus.BAD_REQUEST, EMPTY);
            }
            verifyNotEdited();
        }

        @Test
        @DisplayName("UTC-308 Sửa thành đúng 2.000 ký tự: được lưu")
        void utc308_exactly2000CharactersAreSaved() {
            service.updateComment(ME, ANSWER_ID, new CommentUpdateRequest("a".repeat(2000)));

            verify(repository).updateComment(ANSWER_ID, "a".repeat(2000), NOW);
        }

        @Test
        @DisplayName("UTC-309 Sửa thành 2.001 ký tự: \"Bình luận tối đa 2.000 ký tự.\", nội dung cũ giữ nguyên")
        void utc309_2001CharactersAreRefused() {
            assertApiError(() -> service.updateComment(ME, ANSWER_ID, new CommentUpdateRequest("a".repeat(2001))),
                    HttpStatus.BAD_REQUEST, TOO_LONG);
            verifyNotEdited();
        }

        @Test
        @DisplayName("UTC-310 Câu trả lời bị ẩn do báo cáo, chỉ còn chỗ trống, không tồn tại (3 lần): 404 \"Không tìm thấy bình luận này.\"")
        void utc310_answerNotShownIsNotFound() {
            answerRow(501L, null, ME_ID, "HIDDEN");
            answerRow(502L, null, ME_ID, "REMOVED");
            when(repository.findComment(503L)).thenReturn(Optional.empty());

            for (long id : List.of(501L, 502L, 503L)) {
                assertApiError(() -> service.updateComment(ME, id, new CommentUpdateRequest(NEW_TEXT)),
                        HttpStatus.NOT_FOUND, COMMENT_NOT_FOUND);
            }
            verifyNotEdited();
        }

        @Test
        @DisplayName("UTC-311 Câu hỏi bị ẩn, bị gỡ, đang quét lại hoặc người hỏi bị khóa (4 lần): 404 MSG19, nội dung giữ nguyên")
        void utc311_questionNoLongerShownAnswersMsg19() {
            List<String[]> cases = List.of(new String[] {"HIDDEN", "ACTIVE"}, new String[] {"REMOVED", "ACTIVE"},
                    new String[] {"PROCESSING", "ACTIVE"}, new String[] {"PUBLISHED", "LOCKED"});
            for (String[] c : cases) {
                question(c[0], c[1], null);
                assertApiError(() -> service.updateComment(ME, ANSWER_ID, new CommentUpdateRequest(NEW_TEXT)),
                        HttpStatus.NOT_FOUND, MSG19);
            }
            verifyNotEdited();
        }

        @Test
        @DisplayName("UTC-312 Người hỏi hoặc thành viên khác sửa câu trả lời không phải của mình (2 lần): 403 MSG18")
        void utc312_onlyTheWriterMayEdit() {
            for (AppUserPrincipal notWriter : List.of(ASKER, OTHER)) {
                assertApiError(() -> service.updateComment(notWriter, ANSWER_ID, new CommentUpdateRequest(NEW_TEXT)),
                        HttpStatus.FORBIDDEN, MSG18);
            }
            verifyNoInteractions(filter);
            verifyNotEdited();
        }

        @Test
        @DisplayName("UTC-313 Khách (chưa đăng nhập): 401, nội dung giữ nguyên")
        void utc313_guestIsRefused() {
            assertApiError(() -> service.updateComment(null, ANSWER_ID, new CommentUpdateRequest(NEW_TEXT)),
                    HttpStatus.UNAUTHORIZED, MSG_LOGIN);
            verifyNoInteractions(repository, filter);
        }

        @Test
        @DisplayName("UTC-314 Tài khoản nhân viên (Moderator, BTV tiếng Nhật, Admin – 3 lần): 403 MSG18")
        void utc314_staffAccountsAreRefused() {
            for (AppUserPrincipal staff : List.of(MODERATOR, EDITOR, ADMIN)) {
                assertApiError(() -> service.updateComment(staff, ANSWER_ID, new CommentUpdateRequest(NEW_TEXT)),
                        HttpStatus.FORBIDDEN, MSG18);
            }
            verifyNotEdited();
        }

        @Test
        @DisplayName("UTC-315 Nội dung sửa chứa từ khóa cấm: MSG36, nội dung cũ giữ nguyên")
        void utc315_bannedKeywordIsRefused() {
            filterRefuses("Cần vay nóng thì nhắn mình", MSG36);

            assertApiError(() -> service.updateComment(ME, ANSWER_ID, new CommentUpdateRequest("Cần vay nóng thì nhắn mình")),
                    HttpStatus.BAD_REQUEST, MSG36);
            verifyNotEdited();
        }

        @Test
        @DisplayName("UTC-316 Nội dung sửa có link khi chưa có tag định danh: bị từ chối")
        void utc316_linkWithoutIdentityTagIsRefused() {
            filterRefuses("Xem ở https://www.moj.go.jp/isa/", LINK_NEEDS_TAG);

            assertApiError(() -> service.updateComment(ME, ANSWER_ID,
                    new CommentUpdateRequest("Xem ở https://www.moj.go.jp/isa/")), HttpStatus.BAD_REQUEST, LINK_NEEDS_TAG);
            verifyNotEdited();
        }

        @Test
        @DisplayName("UTC-317 Nội dung sửa có số điện thoại khi chưa có tag định danh: bị từ chối")
        void utc317_phoneNumberWithoutIdentityTagIsRefused() {
            filterRefuses("Gọi mình 0901234567", CONTACT_NEEDS_TAG);

            assertApiError(() -> service.updateComment(ME, ANSWER_ID, new CommentUpdateRequest("Gọi mình 0901234567")),
                    HttpStatus.BAD_REQUEST, CONTACT_NEEDS_TAG);
            verifyNotEdited();
        }

        @Test
        @DisplayName("UTC-318 Nội dung sửa có link rút gọn: bị từ chối")
        void utc318_shortLinkIsRefused() {
            filterRefuses("Xem ở bit.ly/abc123", SHORT_LINK);

            assertApiError(() -> service.updateComment(ME, ANSWER_ID, new CommentUpdateRequest("Xem ở bit.ly/abc123")),
                    HttpStatus.BAD_REQUEST, SHORT_LINK);
            verifyNotEdited();
        }
    }

    // =================================================================================================================
    @Nested
    @TestMethodOrder(MethodOrderer.DisplayName.class)
    @DisplayName("03. deleteComment – UC 1.21 Xóa câu trả lời (UTC-319 → UTC-334)")
    class DeleteAnswer {

        @Test
        @DisplayName("UTC-319 Câu trả lời không có phản hồi: xóa hẳn, đếm lại số câu trả lời, trả về mã câu hỏi")
        void utc319_answerWithoutRepliesIsDeleted() {
            assertThat(service.deleteComment(ME, ANSWER_ID)).isEqualTo(QUESTION_ID);

            verify(repository).deleteComment(ANSWER_ID);
            verify(repository).recountComments(QUESTION_ID);
            verify(repository, never()).blankComment(anyLong(), any());
            verify(repository, never()).setBestComment(anyLong(), any());
        }

        @Test
        @DisplayName("UTC-320 Câu trả lời có phản hồi: chỉ làm trống, giữ chỗ \"Bình luận đã bị xóa.\" để phản hồi vẫn đọc được")
        void utc320_answerWithRepliesBecomesAPlaceholder() {
            when(repository.hasReplies(ANSWER_ID)).thenReturn(true);

            assertThat(service.deleteComment(ME, ANSWER_ID)).isEqualTo(QUESTION_ID);
            verify(repository).blankComment(ANSWER_ID, NOW);
            verify(repository, never()).deleteComment(anyLong());
            verify(repository).recountComments(QUESTION_ID);
        }

        @Test
        @DisplayName("UTC-321 Xóa câu trả lời hay nhất: bỏ dấu, thu lại 5 điểm, câu hỏi chưa giải quyết, người hỏi không được báo")
        void utc321_deletingTheBestAnswerRemovesTheMarkAndPoints() {
            question("PUBLISHED", "ACTIVE", ANSWER_ID);

            service.deleteComment(ME, ANSWER_ID);

            verify(repository).setBestComment(QUESTION_ID, null);
            verify(points).withdrawAll(ME_ID, "COMMENT", ANSWER_ID, "CONTENT_DELETED");
            verify(repository).deleteComment(ANSWER_ID);
            verifyNoInteractions(notifications);
        }

        @Test
        @DisplayName("UTC-322 Câu trả lời hay nhất có phản hồi: bỏ dấu, thu điểm và để lại chỗ trống")
        void utc322_bestAnswerWithRepliesLosesItsMarkAndIsBlanked() {
            question("PUBLISHED", "ACTIVE", ANSWER_ID);
            when(repository.hasReplies(ANSWER_ID)).thenReturn(true);

            service.deleteComment(ME, ANSWER_ID);

            verify(repository).setBestComment(QUESTION_ID, null);
            verify(points).withdrawAll(ME_ID, "COMMENT", ANSWER_ID, "CONTENT_DELETED");
            verify(repository).blankComment(ANSWER_ID, NOW);
            verify(repository, never()).deleteComment(anyLong());
        }

        @Test
        @DisplayName("UTC-323 Xóa một câu trả lời khác khi câu hỏi đã có câu hay nhất: dấu hay nhất giữ nguyên")
        void utc323_deletingAnotherAnswerKeepsTheBestMark() {
            question("PUBLISHED", "ACTIVE", 499L);

            service.deleteComment(ME, ANSWER_ID);

            verify(repository).deleteComment(ANSWER_ID);
            verify(repository, never()).setBestComment(anyLong(), any());
        }

        @Test
        @DisplayName("UTC-324 Xóa một phản hồi dưới câu trả lời: chỉ phản hồi mất, câu trả lời giữ nguyên")
        void utc324_deletingAReplyKeepsTheAnswer() {
            answerRow(501L, null, OTHER_ID, "PUBLISHED");
            answerRow(510L, 501L, ME_ID, "PUBLISHED");

            assertThat(service.deleteComment(ME, 510L)).isEqualTo(QUESTION_ID);
            verify(repository).deleteComment(510L);
            verify(repository, never()).deleteComment(501L);
            verify(repository).recountComments(QUESTION_ID);
        }

        @Test
        @DisplayName("UTC-325 Xóa phản hồi cuối dưới câu trả lời đã làm trống: chỗ trống cũng bị xóa (câu hỏi có thể trở lại \"Chưa ai trả lời\")")
        void utc325_lastReplyUnderABlankedAnswerRemovesThePlaceholder() {
            answerRow(501L, null, OTHER_ID, "REMOVED");
            answerRow(510L, 501L, ME_ID, "PUBLISHED");
            when(repository.isBlankPlaceholder(501L)).thenReturn(true);

            service.deleteComment(ME, 510L);

            InOrder order = inOrder(repository);
            order.verify(repository).deleteComment(510L);
            order.verify(repository).deleteComment(501L);
            order.verify(repository).recountComments(QUESTION_ID);
        }

        @Test
        @DisplayName("UTC-326 Báo cáo đang mở về câu trả lời được đóng (\"Tác giả đã tự xóa nội dung.\")")
        void utc326_openReportCasesAreClosed() {
            service.deleteComment(ME, ANSWER_ID);

            verify(reports).closeCasesOf("COMMENT", List.of(ANSWER_ID));
        }

        @Test
        @DisplayName("UTC-327 Thứ tự khi xóa câu trả lời hay nhất: khóa câu hỏi → khóa câu trả lời → bỏ dấu → thu điểm → đóng báo cáo → xóa → đếm lại")
        void utc327_bestMarkIsRemovedBeforeThePointsAreTaken() {
            question("PUBLISHED", "ACTIVE", ANSWER_ID);

            service.deleteComment(ME, ANSWER_ID);

            InOrder order = inOrder(repository, points, reports);
            order.verify(repository).lockBlog(QUESTION_ID);
            order.verify(repository).lockComment(ANSWER_ID);
            order.verify(repository).setBestComment(QUESTION_ID, null);
            order.verify(points).withdrawAll(ME_ID, "COMMENT", ANSWER_ID, "CONTENT_DELETED");
            order.verify(reports).closeCasesOf("COMMENT", List.of(ANSWER_ID));
            order.verify(repository).deleteComment(ANSWER_ID);
            order.verify(repository).recountComments(QUESTION_ID);
        }

        @Test
        @DisplayName("UTC-328 Câu hỏi bị ẩn trong lúc đó: người viết vẫn xóa được câu trả lời của mình")
        void utc328_writerMayStillDeleteUnderAHiddenQuestion() {
            question("HIDDEN", "ACTIVE", null);

            assertThat(service.deleteComment(ME, ANSWER_ID)).isEqualTo(QUESTION_ID);
            verify(repository).deleteComment(ANSWER_ID);
        }

        @Test
        @DisplayName("UTC-329 Câu trả lời đã bị xóa hoặc bị ẩn do báo cáo (2 lần): 404 \"Không tìm thấy bình luận này.\", không đổi gì")
        void utc329_answerAlreadyDeletedOrHiddenIsNotFound() {
            when(repository.findComment(503L)).thenReturn(Optional.empty());
            answerRow(504L, null, ME_ID, "HIDDEN");

            for (long id : List.of(503L, 504L)) {
                assertApiError(() -> service.deleteComment(ME, id), HttpStatus.NOT_FOUND, COMMENT_NOT_FOUND);
            }
            verifyNothingDeleted();
        }

        @Test
        @DisplayName("UTC-330 Hai lần xóa câu trả lời hay nhất gửi cùng lúc: lần sau nhận 404, không bỏ dấu hay thu điểm lần nữa")
        void utc330_secondDeleteOfTheBestAnswerFindsNothing() {
            question("PUBLISHED", "ACTIVE", ANSWER_ID);
            when(repository.lockComment(ANSWER_ID)).thenReturn(Optional.empty());

            assertApiError(() -> service.deleteComment(ME, ANSWER_ID), HttpStatus.NOT_FOUND, COMMENT_NOT_FOUND);
            verifyNothingDeleted();
        }

        @Test
        @DisplayName("UTC-331 Người hỏi hoặc thành viên khác xóa câu trả lời không phải của mình (2 lần): 403 MSG18, câu trả lời giữ nguyên")
        void utc331_onlyTheWriterMayDelete() {
            question("PUBLISHED", "ACTIVE", ANSWER_ID);

            for (AppUserPrincipal notWriter : List.of(ASKER, OTHER)) {
                assertApiError(() -> service.deleteComment(notWriter, ANSWER_ID), HttpStatus.FORBIDDEN, MSG18);
            }
            verify(repository, never()).lockBlog(anyLong());
            verifyNothingDeleted();
        }

        @Test
        @DisplayName("UTC-332 Khách (chưa đăng nhập): 401, không xóa gì")
        void utc332_guestIsRefused() {
            assertApiError(() -> service.deleteComment(null, ANSWER_ID), HttpStatus.UNAUTHORIZED, MSG_LOGIN);
            verifyNoInteractions(repository);
            verifyNothingDeleted();
        }

        @Test
        @DisplayName("UTC-333 Tài khoản nhân viên (Moderator, BTV tiếng Nhật, Admin – 3 lần): 403 MSG18, không xóa gì")
        void utc333_staffAccountsAreRefused() {
            for (AppUserPrincipal staff : List.of(MODERATOR, EDITOR, ADMIN)) {
                assertApiError(() -> service.deleteComment(staff, ANSWER_ID), HttpStatus.FORBIDDEN, MSG18);
            }
            verifyNothingDeleted();
        }

        @Test
        @DisplayName("UTC-334 Người viết câu trả lời bị khóa hoặc đã xóa tài khoản (2 lần): 403 MSG18, không xóa gì")
        void utc334_lockedOrDeletedWriterIsRefused() {
            for (String status : List.of("LOCKED", "DELETED")) {
                account(ME_ID, "USER", status, NOW.minus(Duration.ofDays(30)));
                assertApiError(() -> service.deleteComment(ME, ANSWER_ID), HttpStatus.FORBIDDEN, MSG18);
            }
            verifyNothingDeleted();
        }
    }
}
