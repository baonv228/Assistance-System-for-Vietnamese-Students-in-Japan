package com.sep490.vsa.feature.blog;

import com.sep490.vsa.common.dto.ApiResponse;
import com.sep490.vsa.common.exception.ApiException;
import com.sep490.vsa.common.security.AppUserPrincipal;
import com.sep490.vsa.feature.blog.BlogDetailDtos.BlogDetail;
import com.sep490.vsa.feature.blog.BlogDetailDtos.CommentRequest;
import com.sep490.vsa.feature.blog.BlogDetailDtos.CommentUpdateRequest;
import com.sep490.vsa.feature.blog.BlogDetailDtos.LikeState;
import com.sep490.vsa.feature.blog.BlogDetailDtos.ReportRequest;
import com.sep490.vsa.feature.blog.BlogDetailDtos.SaveState;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Post Detail (UC-10) and its actions, for blog posts only (questions use /api/questions in feature/qa).
 * GET is public (Guests can read); every other call needs a signed-in User. Calls that change the comment list
 * return the refreshed post so the page can redraw it.
 * <p>
 * The /api/comments/{id}/... calls are shared by Blog and Hỏi-Đáp: a comment on a question is an answer.
 */
@RestController
public class BlogDetailController {

    static final String KIND = "POST";

    private final BlogDetailService detailService;
    private final BlogActionService actionService;

    public BlogDetailController(BlogDetailService detailService, BlogActionService actionService) {
        this.detailService = detailService;
        this.actionService = actionService;
    }

    @GetMapping("/api/blogs/{id:\\d+}")
    public ApiResponse<BlogDetail> detail(@PathVariable long id, @AuthenticationPrincipal AppUserPrincipal me) {
        return ApiResponse.ok("OK", detailService.detail(id, me, KIND));
    }

    @DeleteMapping("/api/blogs/{id:\\d+}")
    public ApiResponse<Void> delete(@PathVariable long id, @AuthenticationPrincipal AppUserPrincipal me) {
        actionService.deleteBlog(me, KIND, id);
        return ApiResponse.ok("Đã xóa bài viết.", null);
    }

    // ---------------------------------------------------------------- like, save, report the post

    @PostMapping("/api/blogs/{id:\\d+}/like")
    public ApiResponse<LikeState> like(@PathVariable long id, @AuthenticationPrincipal AppUserPrincipal me) {
        return ApiResponse.ok("OK", actionService.likeBlog(me, KIND, id, true));
    }

    @DeleteMapping("/api/blogs/{id:\\d+}/like")
    public ApiResponse<LikeState> unlike(@PathVariable long id, @AuthenticationPrincipal AppUserPrincipal me) {
        return ApiResponse.ok("OK", actionService.likeBlog(me, KIND, id, false));
    }

    @PostMapping("/api/blogs/{id:\\d+}/save")
    public ApiResponse<SaveState> save(@PathVariable long id, @AuthenticationPrincipal AppUserPrincipal me) {
        return ApiResponse.ok("Đã lưu bài viết.", actionService.saveBlog(me, KIND, id, true));
    }

    @DeleteMapping("/api/blogs/{id:\\d+}/save")
    public ApiResponse<SaveState> unsave(@PathVariable long id, @AuthenticationPrincipal AppUserPrincipal me) {
        return ApiResponse.ok("Đã bỏ lưu bài viết.", actionService.saveBlog(me, KIND, id, false));
    }

    @PostMapping("/api/blogs/{id:\\d+}/report")
    public ApiResponse<Void> report(@PathVariable long id, @Valid @RequestBody ReportRequest request,
                                    @AuthenticationPrincipal AppUserPrincipal me) {
        actionService.reportBlog(me, KIND, id, request);
        return ApiResponse.ok("Cảm ơn bạn. Báo cáo đã được gửi.", null);                       // MSG38
    }

    // ---------------------------------------------------------------- comments

    @PostMapping("/api/blogs/{id:\\d+}/comments")
    public ApiResponse<BlogDetail> comment(@PathVariable long id, @RequestBody CommentRequest request,
                                           @AuthenticationPrincipal AppUserPrincipal me) {
        actionService.comment(me, KIND, id, request);
        return ApiResponse.ok("Đã đăng bình luận.", detailService.detail(id, me, KIND));
    }

    // Shared with Hỏi-Đáp: the refreshed item is returned whatever its kind

    @PostMapping("/api/comments/{id:\\d+}/like")
    public ApiResponse<LikeState> likeComment(@PathVariable long id, @AuthenticationPrincipal AppUserPrincipal me) {
        return ApiResponse.ok("OK", actionService.likeComment(me, id, true));
    }

    @DeleteMapping("/api/comments/{id:\\d+}/like")
    public ApiResponse<LikeState> unlikeComment(@PathVariable long id, @AuthenticationPrincipal AppUserPrincipal me) {
        return ApiResponse.ok("OK", actionService.likeComment(me, id, false));
    }

    @PostMapping("/api/comments/{id:\\d+}/report")
    public ApiResponse<BlogDetail> reportComment(@PathVariable long id, @Valid @RequestBody ReportRequest request,
                                                 @AuthenticationPrincipal AppUserPrincipal me) {
        long blogId = actionService.reportComment(me, id, request);
        return ApiResponse.ok("Cảm ơn bạn. Báo cáo đã được gửi.", detailService.detail(blogId, me, null));
    }

    @PutMapping("/api/comments/{id:\\d+}")
    public ApiResponse<BlogDetail> updateComment(@PathVariable long id, @RequestBody CommentUpdateRequest request,
                                                 @AuthenticationPrincipal AppUserPrincipal me) {
        long blogId = actionService.updateComment(me, id, request);
        return ApiResponse.ok("Đã lưu bình luận.", detailService.detail(blogId, me, null));
    }

    /**
     * The refreshed post, or {@code data: null} when the comment was deleted but the post can no longer be shown to
     * this viewer (hidden or being scanned since the page was opened): the delete still succeeded.
     */
    @DeleteMapping("/api/comments/{id:\\d+}")
    public ApiResponse<BlogDetail> deleteComment(@PathVariable long id, @AuthenticationPrincipal AppUserPrincipal me) {
        long blogId = actionService.deleteComment(me, id);
        BlogDetail post;
        try {
            post = detailService.detail(blogId, me, null);
        } catch (ApiException e) {
            if (e.getStatus() != HttpStatus.NOT_FOUND) {
                throw e;
            }
            post = null;
        }
        return ApiResponse.ok("Đã xóa bình luận.", post);
    }
}
