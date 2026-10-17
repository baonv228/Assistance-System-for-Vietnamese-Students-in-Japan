package com.sep490.vsa.feature.blog;

import com.sep490.vsa.feature.blog.BlogDtos.PlaceRef;
import com.sep490.vsa.feature.blog.BlogDtos.Tag;
import com.sep490.vsa.feature.blog.BlogDtos.TopicRef;
import com.sep490.vsa.feature.report.ContentReportService.Reason;
import jakarta.validation.constraints.NotNull;

import java.time.Instant;
import java.util.List;

/** JSON shapes of Post Detail (UC-10 View Blog Details) and its actions. */
public final class BlogDetailDtos {

    private BlogDetailDtos() {
    }

    /** @param rankName Người mới, Người chỉ đường or Senpai tin cậy */
    public record Author(Long id, String displayName, String avatarUrl, List<Tag> tags, String rankName) {
    }

    /** @param type IMAGE or VIDEO */
    public record Media(Long id, String type, String url, String mimeType, Integer width, Integer height) {
    }

    /**
     * What the current viewer may do. A guest gets all false and the page sends them to sign in.
     *
     * @param canInteract signed in as a User (Member) and the post is published
     */
    public record Viewer(boolean signedIn, boolean author, boolean canInteract, boolean liked, boolean saved) {
    }

    /**
     * One comment with its replies.
     *
     * @param removed the comment was deleted or removed but still has replies: the page shows a placeholder
     * @param best    chosen as "Câu trả lời hay nhất" by the asker (Q&amp;A only)
     */
    public record CommentNode(Long id, Long parentId, Author author, String content, boolean edited, boolean removed,
                              boolean best, boolean mine, boolean liked, int likeCount, Instant createdAt,
                              List<CommentNode> replies) {
    }

    /**
     * @param status PUBLISHED; PROCESSING, HIDDEN or REJECTED only when the author views their own post
     * @param bestCommentId set on a solved question ("Đã giải quyết")
     */
    public record BlogDetail(Long id, String type, String status, boolean pinned, boolean edited, String title,
                             String content, Author author, List<TopicRef> topics, List<PlaceRef> places,
                             List<Media> media, int likeCount, int commentCount, Long bestCommentId,
                             Instant createdAt, Viewer viewer, List<CommentNode> comments,
                             List<Reason> reportReasons) {
    }

    public record LikeState(boolean active, int count) {
    }

    public record SaveState(boolean saved) {
    }

    public record CommentRequest(String content, Long parentId) {
    }

    public record ReportRequest(@NotNull(message = "Hãy chọn lý do báo cáo.") Long reasonId, String note) {
    }

    public record BestCommentRequest(@NotNull(message = "Hãy chọn câu trả lời.") Long commentId) {
    }
}
