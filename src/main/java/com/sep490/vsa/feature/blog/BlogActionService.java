package com.sep490.vsa.feature.blog;

import com.sep490.vsa.common.exception.ApiException;
import com.sep490.vsa.common.security.AppUserPrincipal;
import com.sep490.vsa.feature.blog.BlogDetailDtos.CommentRequest;
import com.sep490.vsa.feature.blog.BlogDetailDtos.LikeState;
import com.sep490.vsa.feature.blog.BlogDetailDtos.ReportRequest;
import com.sep490.vsa.feature.blog.BlogDetailDtos.SaveState;
import com.sep490.vsa.feature.blog.BlogDetailRepository.BlogRow;
import com.sep490.vsa.feature.blog.BlogDetailRepository.CommentRow;
import com.sep490.vsa.feature.media.MediaService;
import com.sep490.vsa.feature.moderation.ContentFilter;
import com.sep490.vsa.feature.notification.NotificationService;
import com.sep490.vsa.feature.report.ContentReportService;
import com.sep490.vsa.feature.report.ContentReportService.Target;
import com.sep490.vsa.feature.user.PointService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

/**
 * Actions on Post Detail: like, save, comment, report (UC-16), choose the best answer, delete one's own
 * post (UC-13) or comment (UC-15). Only signed-in Users (Members) act; Guests are sent to sign in by the page
 * and get 401 from the API. Nobody likes or reports their own content.
 * <p>
 * Shared by Blog (kind POST, /api/blogs/...) and Hỏi-Đáp (kind QA, /api/questions/... in feature/qa): every
 * action on the post itself names the kind it expects, and the wrong kind answers "not found" (MSG19).
 */
@Service
@Transactional
public class BlogActionService {

    static final int COMMENT_MAX = 2000;
    static final String NO_PERMISSION = "Bạn không có quyền thực hiện thao tác này.";          // MSG18

    private final BlogDetailRepository repository;
    private final ContentFilter filter;
    private final ContentReportService reports;
    private final PointService points;
    private final NotificationService notifications;
    private final MediaService media;
    private final Clock clock;

    public BlogActionService(BlogDetailRepository repository, ContentFilter filter, ContentReportService reports,
                             PointService points, NotificationService notifications, MediaService media,
                             Clock clock) {
        this.repository = repository;
        this.filter = filter;
        this.reports = reports;
        this.points = points;
        this.notifications = notifications;
        this.media = media;
        this.clock = clock;
    }

    // ---------------------------------------------------------------- post

    public LikeState likeBlog(AppUserPrincipal me, String kind, long blogId, boolean on) {
        long userId = member(me);
        BlogRow blog = publishedBlog(blogId, kind);
        if (blog.authorId() == userId) {
            throw ApiException.badRequest("Bạn không thể thích bài viết của chính mình.");
        }
        int count = repository.setBlogLike(blogId, userId, on, clock.instant());
        return new LikeState(on, count);
    }

    /** Saving works on any published post, one's own included ("Đã lưu" in Tôi). */
    public SaveState saveBlog(AppUserPrincipal me, String kind, long blogId, boolean on) {
        long userId = member(me);
        publishedBlog(blogId, kind);
        repository.setSaved(blogId, userId, on, clock.instant());
        return new SaveState(on);
    }

    public void reportBlog(AppUserPrincipal me, String kind, long blogId, ReportRequest request) {
        long userId = member(me);
        BlogRow blog = publishedBlog(blogId, kind);
        var outcome = reports.report(new Target("BLOG", blogId, blog.authorId()), userId, request.reasonId(),
                request.note());
        if (outcome.hide()) {
            repository.hideBlog(blogId);
        }
    }

    /**
     * The author deletes their own post. The post and its comments are deleted for good, the points the post
     * earned are taken back ("Tự xóa bài thì điểm của bài đó được thu lại"), open reports are closed, and the
     * post's photo and video files are removed from storage.
     */
    public void deleteBlog(AppUserPrincipal me, String kind, long blogId) {
        long userId = member(me);
        BlogRow blog = repository.findBlog(blogId)
                .filter(b -> kind.equals(b.type()) && !"REMOVED".equals(b.status()))
                .orElseThrow(BlogActionService::notFound);
        if (blog.authorId() != userId) {
            throw new ApiException(HttpStatus.FORBIDDEN, NO_PERMISSION);
        }
        reports.closeCasesOf("COMMENT", repository.commentIds(blogId));
        reports.closeCasesOf("BLOG", List.of(blogId));
        points.withdrawAll(userId, "BLOG", blogId, "CONTENT_DELETED");
        media.discard(repository.mediaIds(blogId));
        repository.deleteBlog(blogId);
    }

    /**
     * The asker marks one answer as "Câu trả lời hay nhất": it moves to the top, the question shows
     * "Đã giải quyết", and its writer gets +5 points and a notification. Choosing another answer later moves
     * the 5 points from the previous writer to the new one.
     */
    public void chooseBest(AppUserPrincipal me, long blogId, long commentId) {
        long userId = member(me);
        BlogRow blog = publishedBlog(blogId, "QA");
        if (blog.authorId() != userId) {
            throw new ApiException(HttpStatus.FORBIDDEN, NO_PERMISSION);
        }
        CommentRow answer = repository.findComment(commentId)
                .filter(c -> c.blogId() == blogId && "PUBLISHED".equals(c.status()))
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Không tìm thấy câu trả lời này."));
        if (answer.parentId() != null) {
            throw ApiException.badRequest("Chỉ chọn được câu trả lời trực tiếp cho câu hỏi, không chọn phần trả lời bình luận.");
        }
        if (answer.authorId() == userId) {
            throw ApiException.badRequest("Bạn không thể chọn câu trả lời của chính mình.");
        }
        if (Long.valueOf(commentId).equals(blog.bestCommentId())) {
            return;
        }
        if (blog.bestCommentId() != null) {
            repository.findComment(blog.bestCommentId()).ifPresent(previous ->
                    points.withdrawAll(previous.authorId(), "COMMENT", previous.id(), "BEST_ANSWER_CHANGED"));
        }
        repository.setBestComment(blogId, commentId);
        points.add(answer.authorId(), PointService.BEST_ANSWER, "BEST_ANSWER", "COMMENT", commentId);
        notifications.send(answer.authorId(), NotificationService.BEST_ANSWER,
                "Câu trả lời của bạn cho “" + blog.title() + "” được chọn là câu trả lời hay nhất. +"
                        + PointService.BEST_ANSWER + " điểm.", refType(blog), blogId);
    }

    // ---------------------------------------------------------------- comments

    /**
     * Adds a comment (or an answer on a question), or a reply when {@code parentId} is set. The text goes
     * through the keyword and link checks and is published at once (comments have no media to scan).
     * The post's author, and the author of the comment being replied to, get a notification.
     */
    public long comment(AppUserPrincipal me, String kind, long blogId, CommentRequest request) {
        long userId = member(me);
        BlogRow blog = publishedBlog(blogId, kind);
        String content = request == null || request.content() == null ? "" : request.content().strip();
        if (content.isEmpty()) {
            throw ApiException.badRequest("Hãy viết nội dung bình luận.");
        }
        if (content.length() > COMMENT_MAX) {
            throw ApiException.badRequest("Bình luận tối đa 2.000 ký tự.");
        }
        CommentRow parent = null;
        if (request.parentId() != null) {
            parent = repository.findComment(request.parentId())
                    .filter(c -> c.blogId() == blogId && "PUBLISHED".equals(c.status()))
                    .orElseThrow(() -> ApiException.badRequest("Bình luận bạn trả lời không còn nữa."));
        }
        filter.check(content, userId);

        Instant now = clock.instant();
        long id = repository.insertComment(blogId, userId, parent == null ? null : parent.id(), content, now);
        repository.recountComments(blogId);

        String who = me.displayName();
        String word = "QA".equals(blog.type()) && parent == null ? "trả lời câu hỏi" : "bình luận bài";
        if (blog.authorId() != userId) {
            notifications.send(blog.authorId(), NotificationService.NEW_COMMENT,
                    who + " đã " + word + " “" + blog.title() + "” của bạn.", refType(blog), blogId);
        }
        if (parent != null && parent.authorId() != userId && !parent.authorId().equals(blog.authorId())) {
            notifications.send(parent.authorId(), NotificationService.NEW_COMMENT,
                    who + " đã trả lời bình luận của bạn trong “" + blog.title() + "”.", refType(blog), blogId);
        }
        return id;
    }

    public LikeState likeComment(AppUserPrincipal me, long commentId, boolean on) {
        long userId = member(me);
        CommentRow comment = publishedComment(commentId);
        if (comment.authorId() == userId) {
            throw ApiException.badRequest("Bạn không thể thích bình luận của chính mình.");
        }
        return new LikeState(on, repository.setCommentLike(commentId, userId, on, clock.instant()));
    }

    /** @return the post the comment belongs to, so the page can reload it */
    public long reportComment(AppUserPrincipal me, long commentId, ReportRequest request) {
        long userId = member(me);
        CommentRow comment = publishedComment(commentId);
        var outcome = reports.report(new Target("COMMENT", commentId, comment.authorId()), userId,
                request.reasonId(), request.note());
        if (outcome.hide() && repository.hideComment(commentId)) {
            repository.recountComments(comment.blogId());
        }
        return comment.blogId();
    }

    /**
     * The writer deletes their own comment. A comment with replies stays as an empty placeholder so the
     * replies are kept; otherwise it is deleted. A deleted best answer loses its mark and its 5 points.
     *
     * @return the post the comment belonged to
     */
    public long deleteComment(AppUserPrincipal me, long commentId) {
        long userId = member(me);
        CommentRow comment = repository.findComment(commentId)
                .filter(c -> "PUBLISHED".equals(c.status()) || "PROCESSING".equals(c.status()))
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Không tìm thấy bình luận này."));
        if (comment.authorId() != userId) {
            throw new ApiException(HttpStatus.FORBIDDEN, NO_PERMISSION);
        }
        repository.findBlog(comment.blogId())
                .filter(b -> Long.valueOf(commentId).equals(b.bestCommentId()))
                .ifPresent(b -> repository.setBestComment(b.id(), null));
        points.withdrawAll(userId, "COMMENT", commentId, "CONTENT_DELETED");
        reports.closeCasesOf("COMMENT", List.of(commentId));
        if (repository.hasReplies(commentId)) {
            repository.blankComment(commentId, clock.instant());
        } else {
            repository.deleteComment(commentId);
            removeEmptyPlaceholders(comment.parentId());
        }
        repository.recountComments(comment.blogId());
        return comment.blogId();
    }

    /** A placeholder left by a self-deleted comment goes away once its last reply is deleted too. */
    private void removeEmptyPlaceholders(Long parentId) {
        Long current = parentId;
        while (current != null) {
            CommentRow parent = repository.findComment(current).orElse(null);
            if (parent == null || !repository.isBlankPlaceholder(parent.id()) || repository.hasReplies(parent.id())) {
                return;
            }
            repository.deleteComment(parent.id());
            current = parent.parentId();
        }
    }

    // ---------------------------------------------------------------- checks

    /** The signed-in Member's id. Guests get 401; staff roles and locked accounts get MSG18. */
    private long member(AppUserPrincipal me) {
        if (me == null) {
            throw ApiException.unauthorized("Bạn cần đăng nhập để thực hiện thao tác này.");
        }
        var account = repository.member(me.id()).orElseThrow(() -> ApiException.unauthorized("Bạn chưa đăng nhập."));
        if (!"USER".equals(account.role()) || !"ACTIVE".equals(account.status())) {
            throw new ApiException(HttpStatus.FORBIDDEN, NO_PERMISSION);
        }
        return me.id();
    }

    /** Actions need a published post of the expected kind (null = either); anything else is "not found" (MSG19). */
    private BlogRow publishedBlog(long blogId, String kind) {
        return repository.findBlog(blogId)
                .filter(b -> kind == null || kind.equals(b.type()))
                .filter(b -> "PUBLISHED".equals(b.status()))
                .orElseThrow(BlogActionService::notFound);
    }

    /** What a notification opens: BLOG for a post, QUESTION for a question (the Hỏi-Đáp page). */
    static String refType(BlogRow blog) {
        return "QA".equals(blog.type()) ? "QUESTION" : "BLOG";
    }

    private CommentRow publishedComment(long commentId) {
        CommentRow comment = repository.findComment(commentId).filter(c -> "PUBLISHED".equals(c.status()))
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Không tìm thấy bình luận này."));
        publishedBlog(comment.blogId(), null);
        return comment;
    }

    private static ApiException notFound() {
        return new ApiException(HttpStatus.NOT_FOUND, BlogDetailService.NOT_FOUND);
    }
}
