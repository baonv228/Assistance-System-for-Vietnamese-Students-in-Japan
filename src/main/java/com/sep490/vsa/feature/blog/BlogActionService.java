package com.sep490.vsa.feature.blog;

import com.sep490.vsa.common.exception.ApiException;
import com.sep490.vsa.common.security.AppUserPrincipal;
import com.sep490.vsa.common.settings.SystemSettings;
import com.sep490.vsa.common.text.VietnameseText;
import com.sep490.vsa.feature.blog.BlogDetailDtos.CommentRequest;
import com.sep490.vsa.feature.blog.BlogDetailDtos.CommentUpdateRequest;
import com.sep490.vsa.feature.blog.BlogDetailDtos.LikeState;
import com.sep490.vsa.feature.blog.BlogDetailDtos.ReportRequest;
import com.sep490.vsa.feature.blog.BlogDetailDtos.SaveState;
import com.sep490.vsa.feature.blog.BlogDetailRepository.BlogRow;
import com.sep490.vsa.feature.blog.BlogDetailRepository.CommentRow;
import com.sep490.vsa.feature.blog.BlogDetailRepository.CommentText;
import com.sep490.vsa.feature.blog.BlogDetailRepository.Member;
import com.sep490.vsa.feature.media.MediaService;
import com.sep490.vsa.feature.moderation.ContentFilter;
import com.sep490.vsa.feature.notification.NotificationService;
import com.sep490.vsa.feature.report.ContentReportService;
import com.sep490.vsa.feature.report.ContentReportService.Target;
import com.sep490.vsa.feature.user.PointService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Actions on Post Detail: like, save, comment, report (UC-16), choose the best answer, delete one's own
 * post (UC-13) or comment (UC-15), edit one's own comment or answer (Update Comment / Update Answer). Only signed-in Users (Members) act; Guests are sent to sign in by the page
 * and get 401 from the API. Nobody likes or reports their own content.
 * <p>
 * Shared by Blog (kind POST, /api/blogs/...) and Hỏi-Đáp (kind QA, /api/questions/... in feature/qa): every
 * action on the post itself names the kind it expects, and the wrong kind answers "not found" (MSG19).
 * <p>
 * Actions that add, like, save, choose, report or delete lock the post row first (then a comment row, then the
 * user's own rows), so two actions on one post run one after another, always in the same lock order. They read with
 * READ COMMITTED: what an action reads after waiting for a lock includes what the action before it saved. So points
 * are paid or taken back once, two first reports or two likes on one item do not deadlock, and a comment limit
 * counts the comment the same user saved a moment earlier.
 * <p>
 * A post, question, comment or answer of a locked or deleted account is hidden ({@link ActiveAuthors}): acting on
 * it answers "not found", like on a hidden one. Writers may still delete their own comments under such a post.
 */
@Service
@Transactional(isolation = Isolation.READ_COMMITTED)
public class BlogActionService {

    static final int COMMENT_MAX = 2000;
    static final String NO_PERMISSION = "Bạn không có quyền thực hiện thao tác này.";          // MSG18

    /** Replies nest at most this deep (a top-level comment is depth 0). "cần chốt". */
    static final int MAX_REPLY_DEPTH = 20;
    /** Anti-spam (not in the SRS yet, "cần chốt"): at most one new comment, reply or answer per user every 15 s. */
    static final Duration COMMENT_MIN_INTERVAL = Duration.ofSeconds(15);
    /** "cần chốt": an account younger than new_account_days writes at most 20 comments a day (Japan time). */
    static final int COMMENTS_PER_DAY_NEW_ACCOUNT = 20;
    /**
     * "cần chốt": the same text (compared after {@link VietnameseText#squash}) already on this many other posts in
     * the last 24 hours is refused. Only texts of at least 30 letters and digits count, so "Cảm ơn bạn đã chia sẻ"
     * stays free. The newest 500 comments of the 24 hours are compared (bounds the work for a very busy account).
     */
    static final int REPEATED_TEXT_POSTS = 3;
    static final Duration REPEATED_TEXT_WINDOW = Duration.ofHours(24);
    static final int REPEATED_TEXT_MIN_CHARS = 30;
    static final int REPEATED_TEXT_SCAN = 500;

    private final BlogDetailRepository repository;
    private final ContentFilter filter;
    private final ContentReportService reports;
    private final PointService points;
    private final NotificationService notifications;
    private final MediaService media;
    private final SystemSettings settings;
    private final Clock clock;

    public BlogActionService(BlogDetailRepository repository, ContentFilter filter, ContentReportService reports,
                             PointService points, NotificationService notifications, MediaService media,
                             SystemSettings settings, Clock clock) {
        this.repository = repository;
        this.filter = filter;
        this.reports = reports;
        this.points = points;
        this.notifications = notifications;
        this.media = media;
        this.settings = settings;
        this.clock = clock;
    }

    // ---------------------------------------------------------------- post

    /**
     * The post row is locked before the like is written: two likes sent at the same moment would otherwise both
     * take a shared lock on it (foreign key of the like) and then both wait to update its like count (deadlock).
     */
    public LikeState likeBlog(AppUserPrincipal me, String kind, long blogId, boolean on) {
        long userId = member(me);
        BlogRow blog = lockedPublishedBlog(blogId, kind);
        if (blog.authorId() == userId) {
            throw ApiException.badRequest("Bạn không thể thích bài viết của chính mình.");
        }
        int count = repository.setBlogLike(blogId, userId, on, clock.instant());
        return new LikeState(on, count);
    }

    /** Saving works on any published post, one's own included ("Đã lưu" in Tôi). The post row is locked first. */
    public SaveState saveBlog(AppUserPrincipal me, String kind, long blogId, boolean on) {
        long userId = member(me);
        lockedPublishedBlog(blogId, kind);
        repository.setSaved(blogId, userId, on, clock.instant());
        return new SaveState(on);
    }

    /** The post stays locked while the report is counted, so reports on one post run one after another. */
    public void reportBlog(AppUserPrincipal me, String kind, long blogId, ReportRequest request) {
        long userId = member(me);
        BlogRow blog = lockedPublishedBlog(blogId, kind);
        var outcome = reports.report(new Target("BLOG", blogId, blog.authorId()), userId, request.reasonId(),
                request.note());
        if (outcome.hide()) {
            repository.hideBlog(blogId);
        }
    }

    /**
     * The author deletes their own post. The post and its comments are deleted for good, the points the post
     * earned are taken back ("Tự xóa bài thì điểm của bài đó được thu lại"), open reports are closed, and the
     * post's photo and video files are removed from storage. The post row is locked first: a second delete sent at
     * the same moment waits, then finds the post gone (404) and takes nothing back twice.
     */
    public void deleteBlog(AppUserPrincipal me, String kind, long blogId) {
        long userId = member(me);
        BlogRow blog = repository.lockBlog(blogId)
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
     * the 5 points from the previous writer to the new one. The question row is locked first, so two choices sent
     * at the same moment see each other's result and pay the 5 points once.
     */
    public void chooseBest(AppUserPrincipal me, long blogId, long commentId) {
        long userId = member(me);
        BlogRow blog = lockedPublishedBlog(blogId, "QA");
        if (blog.authorId() != userId) {
            throw new ApiException(HttpStatus.FORBIDDEN, NO_PERMISSION);
        }
        CommentRow answer = repository.findComment(commentId)
                .filter(c -> c.blogId() == blogId && shown(c))
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
     * <p>
     * Anti-spam limits ("cần chốt", see the constants): one new comment every 15 s, 20 a day for a new account, and
     * the same long text on at most 3 other posts a day. Replies nest at most {@link #MAX_REPLY_DEPTH} deep.
     */
    public long comment(AppUserPrincipal me, String kind, long blogId, CommentRequest request) {
        Member account = account(me);
        long userId = me.id();
        BlogRow blog = lockedPublishedBlog(blogId, kind);
        String content = commentText(request == null ? null : request.content());
        CommentRow parent = null;
        if (request.parentId() != null) {
            parent = repository.findComment(request.parentId())
                    .filter(c -> c.blogId() == blogId && shown(c))
                    .orElseThrow(() -> ApiException.badRequest("Bình luận bạn trả lời không còn nữa."));
            if (depth(parent) >= MAX_REPLY_DEPTH) {
                throw ApiException.badRequest("Chuỗi trả lời đã quá dài. Hãy trả lời một bình luận ở phía trên.");
            }
        }
        filter.check(content, userId);

        Instant now = clock.instant();
        checkCommentLimits(userId, account.createdAt(), blogId, content, now);
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

    /**
     * The writer changes the text of their own comment, reply or answer. The new text goes through the same
     * length, keyword and link checks as a new comment; the comment shows "đã sửa". Likes, replies and the
     * best-answer mark stay, and nobody is notified. A placeholder left by a deleted comment cannot be edited.
     *
     * @return the post the comment belongs to, so the page can reload it
     */
    public long updateComment(AppUserPrincipal me, long commentId, CommentUpdateRequest request) {
        long userId = member(me);
        CommentRow comment = publishedComment(commentId);
        if (comment.authorId() != userId) {
            throw new ApiException(HttpStatus.FORBIDDEN, NO_PERMISSION);
        }
        String content = commentText(request == null ? null : request.content());
        filter.check(content, userId);
        repository.updateComment(commentId, content, clock.instant());
        return comment.blogId();
    }

    /** The post and then the comment are locked before the like is written (same reason as {@link #likeBlog}). */
    public LikeState likeComment(AppUserPrincipal me, long commentId, boolean on) {
        long userId = member(me);
        CommentRow comment = lockedShownComment(commentId);
        if (comment.authorId() == userId) {
            throw ApiException.badRequest("Bạn không thể thích bình luận của chính mình.");
        }
        return new LikeState(on, repository.setCommentLike(commentId, userId, on, clock.instant()));
    }

    /**
     * The post and then the comment stay locked while the report is counted, so reports on one comment run one
     * after another.
     *
     * @return the post the comment belongs to, so the page can reload it
     */
    public long reportComment(AppUserPrincipal me, long commentId, ReportRequest request) {
        long userId = member(me);
        CommentRow comment = lockedShownComment(commentId);
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
     * The post and then the comment are locked first: a second delete sent at the same moment waits, then finds the
     * comment gone (404) and takes nothing back twice.
     *
     * @return the post the comment belonged to
     */
    public long deleteComment(AppUserPrincipal me, long commentId) {
        long userId = member(me);
        CommentRow comment = repository.findComment(commentId)
                .filter(BlogActionService::deletable)
                .orElseThrow(BlogActionService::commentNotFound);
        if (comment.authorId() != userId) {
            throw new ApiException(HttpStatus.FORBIDDEN, NO_PERMISSION);
        }
        BlogRow blog = repository.lockBlog(comment.blogId()).orElseThrow(BlogActionService::commentNotFound);
        comment = repository.lockComment(commentId).filter(BlogActionService::deletable)
                .orElseThrow(BlogActionService::commentNotFound);
        if (Long.valueOf(commentId).equals(blog.bestCommentId())) {
            repository.setBestComment(blog.id(), null);
        }
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
        account(me);
        return me.id();
    }

    /** The signed-in Member's account row, checked like {@link #member}. */
    private Member account(AppUserPrincipal me) {
        if (me == null) {
            throw ApiException.unauthorized("Bạn cần đăng nhập để thực hiện thao tác này.");
        }
        Member account = repository.member(me.id()).orElseThrow(() -> ApiException.unauthorized("Bạn chưa đăng nhập."));
        if (!"USER".equals(account.role()) || !"ACTIVE".equals(account.status())) {
            throw new ApiException(HttpStatus.FORBIDDEN, NO_PERMISSION);
        }
        return account;
    }

    /** How deep the comment sits: 0 for a top-level comment. Stops counting at MAX_REPLY_DEPTH (bounded walk up). */
    private int depth(CommentRow comment) {
        int depth = 0;
        Long up = comment.parentId();
        while (up != null && depth < MAX_REPLY_DEPTH) {
            depth++;
            up = repository.parentIdOf(up);
        }
        return depth;
    }

    /**
     * The anti-spam limits of a new comment, reply or answer, in this order: the 15-second pause (everyone), the
     * daily number for a new account (MSG44 wording), the same text on other posts. Counted from {@code comments};
     * the user's lock of the day comes first so two comments sent together are counted one after the other.
     */
    private void checkCommentLimits(long userId, Instant createdAt, long blogId, String content, Instant now) {
        LocalDate today = LocalDate.ofInstant(now, BlogWriteService.JAPAN);
        repository.lockUserDay(userId, today);

        Instant last = repository.lastCommentAt(userId);
        Instant allowedAt = last == null ? null : last.plus(COMMENT_MIN_INTERVAL);
        if (allowedAt != null && now.isBefore(allowedAt)) {
            long wait = (Duration.between(now, allowedAt).toMillis() + 999) / 1000;
            wait = Math.max(1, Math.min(wait, COMMENT_MIN_INTERVAL.toSeconds()));
            throw ApiException.tooManyRequests("Bạn bình luận hơi nhanh. Vui lòng đợi " + wait
                    + " giây rồi thử lại.");
        }

        int newDays = settings.getInt(SystemSettings.NEW_ACCOUNT_DAYS, 7);
        boolean newAccount = createdAt != null && createdAt.isAfter(now.minus(Duration.ofDays(newDays)));
        if (newAccount) {
            Instant dayStart = today.atStartOfDay(BlogWriteService.JAPAN).toInstant();
            Instant dayEnd = today.plusDays(1).atStartOfDay(BlogWriteService.JAPAN).toInstant();
            if (repository.countComments(userId, dayStart, dayEnd) >= COMMENTS_PER_DAY_NEW_ACCOUNT) {
                throw ApiException.tooManyRequests("Bạn đã đạt giới hạn bình luận trong ngày của tài khoản mới (dưới "
                        + newDays + " ngày: tối đa " + COMMENTS_PER_DAY_NEW_ACCOUNT + " bình luận mỗi ngày). "
                        + "Vui lòng thử lại vào ngày mai.");
            }
        }

        String text = VietnameseText.squash(content);
        if (text.length() >= REPEATED_TEXT_MIN_CHARS) {
            Set<Long> postsWithText = new HashSet<>();
            for (CommentText earlier : repository.recentCommentsElsewhere(userId, blogId,
                    now.minus(REPEATED_TEXT_WINDOW), REPEATED_TEXT_SCAN)) {
                if (text.equals(VietnameseText.squash(earlier.content()))) {
                    postsWithText.add(earlier.blogId());
                }
            }
            if (postsWithText.size() >= REPEATED_TEXT_POSTS) {
                throw ApiException.badRequest(
                        "Bạn đã gửi nội dung này ở nhiều bài khác. Hãy viết bình luận riêng cho bài này.");
            }
        }
    }

    /** The stripped text of a new or edited comment: not empty, at most 2,000 characters. */
    private static String commentText(String raw) {
        String content = raw == null ? "" : raw.strip();
        if (content.isEmpty()) {
            throw ApiException.badRequest("Hãy viết nội dung bình luận.");
        }
        if (content.length() > COMMENT_MAX) {
            throw ApiException.badRequest("Bình luận tối đa 2.000 ký tự.");
        }
        return content;
    }

    /**
     * Actions need a published post of the expected kind (null = either) by an active account; anything else is
     * "not found" (MSG19).
     */
    private BlogRow publishedBlog(long blogId, String kind) {
        return repository.findBlog(blogId)
                .filter(b -> kind == null || kind.equals(b.type()))
                .filter(b -> "PUBLISHED".equals(b.status()) && b.authorActive())
                .orElseThrow(BlogActionService::notFound);
    }

    /** {@link #publishedBlog}, with the post row locked until the action ends (see the class comment). */
    private BlogRow lockedPublishedBlog(long blogId, String kind) {
        return repository.lockBlog(blogId)
                .filter(b -> kind == null || kind.equals(b.type()))
                .filter(b -> "PUBLISHED".equals(b.status()) && b.authorActive())
                .orElseThrow(BlogActionService::notFound);
    }

    /** What a notification opens: BLOG for a post, QUESTION for a question (the Hỏi-Đáp page). */
    static String refType(BlogRow blog) {
        return "QA".equals(blog.type()) ? "QUESTION" : "BLOG";
    }

    /** A comment others can see and act on: published, by an active account. */
    private static boolean shown(CommentRow comment) {
        return "PUBLISHED".equals(comment.status()) && comment.authorActive();
    }

    private CommentRow publishedComment(long commentId) {
        CommentRow comment = repository.findComment(commentId).filter(BlogActionService::shown)
                .orElseThrow(BlogActionService::commentNotFound);
        publishedBlog(comment.blogId(), null);
        return comment;
    }

    /** {@link #publishedComment} with the post row and then the comment row locked until the action ends. */
    private CommentRow lockedShownComment(long commentId) {
        CommentRow comment = repository.findComment(commentId).filter(BlogActionService::shown)
                .orElseThrow(BlogActionService::commentNotFound);
        lockedPublishedBlog(comment.blogId(), null);
        return repository.lockComment(commentId).filter(BlogActionService::shown)
                .orElseThrow(BlogActionService::commentNotFound);
    }

    /** The writer may delete a published comment, or one of theirs still being processed. */
    private static boolean deletable(CommentRow comment) {
        return "PUBLISHED".equals(comment.status()) || "PROCESSING".equals(comment.status());
    }

    private static ApiException notFound() {
        return new ApiException(HttpStatus.NOT_FOUND, BlogDetailService.NOT_FOUND);
    }

    private static ApiException commentNotFound() {
        return new ApiException(HttpStatus.NOT_FOUND, "Không tìm thấy bình luận này.");
    }
}
