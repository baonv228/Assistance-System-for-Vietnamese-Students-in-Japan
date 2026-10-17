package com.sep490.vsa.feature.blog;

import com.sep490.vsa.common.exception.ApiException;
import com.sep490.vsa.common.security.AppUserPrincipal;
import com.sep490.vsa.feature.blog.BlogDetailDtos.Author;
import com.sep490.vsa.feature.blog.BlogDetailDtos.BlogDetail;
import com.sep490.vsa.feature.blog.BlogDetailDtos.CommentNode;
import com.sep490.vsa.feature.blog.BlogDetailDtos.Viewer;
import com.sep490.vsa.feature.blog.BlogDetailRepository.BlogRow;
import com.sep490.vsa.feature.blog.BlogDetailRepository.CommentRow;
import com.sep490.vsa.feature.blog.BlogDetailRepository.RankRow;
import com.sep490.vsa.feature.blog.BlogDtos.Tag;
import com.sep490.vsa.feature.report.ContentReportService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** UC-10 View Blog Details: the post, its author, media and comment tree, and what the viewer may do. */
@Service
@Transactional(readOnly = true)
public class BlogDetailService {

    /** MSG19, shown for a post that does not exist, was removed or was deleted. */
    static final String NOT_FOUND = "Không tìm thấy nội dung bạn yêu cầu. Bài viết có thể đã bị gỡ hoặc đã bị xóa.";

    private final BlogDetailRepository repository;
    private final BlogFeedRepository feedRepository;
    private final ContentReportService reports;
    private final String mediaBaseUrl;

    public BlogDetailService(BlogDetailRepository repository, BlogFeedRepository feedRepository,
                             ContentReportService reports,
                             @Value("${app.media.public-base-url:/media/}") String mediaBaseUrl) {
        this.repository = repository;
        this.feedRepository = feedRepository;
        this.reports = reports;
        this.mediaBaseUrl = mediaBaseUrl;
    }

    public BlogDetail detail(long blogId, AppUserPrincipal me) {
        Long viewerId = me == null ? null : me.id();
        BlogRow blog = repository.findBlog(blogId).filter(b -> canSee(b, viewerId))
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, NOT_FOUND));
        boolean author = blog.authorId().equals(viewerId);

        List<CommentRow> rows = repository.comments(blogId);
        Set<Long> userIds = new HashSet<>();
        userIds.add(blog.authorId());
        rows.forEach(r -> userIds.add(r.authorId()));
        Map<Long, List<Tag>> tags = feedRepository.tagsOf(userIds);
        List<RankRow> ranks = repository.ranks();
        int senpaiMin = ranks.isEmpty() ? Integer.MAX_VALUE : ranks.get(0).minPoints();

        // The best answer only counts while it is still visible
        Long best = rows.stream().filter(r -> r.id().equals(blog.bestCommentId()) && CommentTree.visible(r, viewerId))
                .findFirst().map(CommentRow::id).orElse(null);
        Set<Long> liked = viewerId == null ? Set.of() : repository.likedComments(blogId, viewerId);
        List<CommentNode> comments = CommentTree.build(rows, viewerId, best, "QA".equals(blog.type()), senpaiMin,
                liked, r -> new Author(r.authorId(), r.authorName(), r.avatarUrl(),
                        tags.getOrDefault(r.authorId(), List.of()), rankName(ranks, r.authorPoints())));

        boolean published = "PUBLISHED".equals(blog.status());
        boolean member = me != null && "USER".equals(me.role());
        Viewer viewer = new Viewer(me != null, author, member && published,
                viewerId != null && repository.likedBlog(blogId, viewerId),
                viewerId != null && repository.savedBlog(blogId, viewerId));

        return new BlogDetail(blog.id(), blog.type(), blog.status(), blog.pinned(), blog.edited(), blog.title(),
                blog.content(),
                new Author(blog.authorId(), blog.authorName(), blog.avatarUrl(),
                        tags.getOrDefault(blog.authorId(), List.of()), rankName(ranks, blog.authorPoints())),
                feedRepository.topicsOf(List.of(blogId)).getOrDefault(blogId, List.of()),
                feedRepository.placesOf(List.of(blogId)).getOrDefault(blogId, List.of()),
                repository.media(blogId, author, mediaBaseUrl),
                blog.likeCount(), blog.commentCount(), best, blog.createdAt(), viewer, comments,
                reports.reasons());
    }

    /**
     * Everyone sees a published post. The author also sees their own post while its media is scanned
     * (PROCESSING), after it was hidden by reports (HIDDEN) or rejected by the scan (REJECTED), with a note.
     * A post removed by a Moderator (REMOVED) is not shown here; its author is notified and can appeal.
     */
    static boolean canSee(BlogRow blog, Long viewerId) {
        return switch (blog.status()) {
            case "PUBLISHED" -> true;
            case "PROCESSING", "HIDDEN", "REJECTED" -> blog.authorId().equals(viewerId);
            default -> false;
        };
    }

    static String rankName(List<RankRow> ranksHighFirst, int points) {
        return ranksHighFirst.stream().filter(r -> points >= r.minPoints()).findFirst().map(RankRow::name)
                .orElse("Người mới");
    }
}
