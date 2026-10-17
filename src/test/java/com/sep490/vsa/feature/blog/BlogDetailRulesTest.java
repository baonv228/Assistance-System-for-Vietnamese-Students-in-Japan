package com.sep490.vsa.feature.blog;

import com.sep490.vsa.common.text.VietnameseText;
import com.sep490.vsa.feature.blog.BlogDetailDtos.Author;
import com.sep490.vsa.feature.blog.BlogDetailDtos.CommentNode;
import com.sep490.vsa.feature.blog.BlogDetailRepository.BlogRow;
import com.sep490.vsa.feature.blog.BlogDetailRepository.CommentRow;
import com.sep490.vsa.feature.blog.BlogDetailRepository.RankRow;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class BlogDetailRulesTest {

    private static final Instant T0 = Instant.parse("2026-10-01T00:00:00Z");

    private static CommentRow row(long id, Long parent, long author, int points, String status, int minute) {
        return new CommentRow(id, 1L, parent, author, "u" + author, null, points, "c" + id, status, false, 0,
                T0.plusSeconds(60L * minute));
    }

    private static List<CommentNode> build(List<CommentRow> rows, Long viewer, Long best, boolean question) {
        return CommentTree.build(rows, viewer, best, question, 300, Set.of(),
                r -> new Author(r.authorId(), r.authorName(), null, List.of(), "Người mới"));
    }

    @Test
    void repliesNestUnderTheirParentOldestFirst() {
        List<CommentNode> tree = build(List.of(
                row(1, null, 10, 0, "PUBLISHED", 1),
                row(2, 1L, 11, 0, "PUBLISHED", 3),
                row(3, 1L, 12, 0, "PUBLISHED", 2),
                row(4, null, 13, 0, "PUBLISHED", 4)), null, null, false);
        assertThat(tree).extracting(CommentNode::id).containsExactly(1L, 4L);
        assertThat(tree.get(0).replies()).extracting(CommentNode::id).containsExactly(3L, 2L);
    }

    @Test
    void bestAnswerFirstThenSenpaiOnQuestions() {
        List<CommentRow> rows = List.of(
                row(1, null, 10, 0, "PUBLISHED", 1),
                row(2, null, 11, 350, "PUBLISHED", 2),    // Senpai tin cậy
                row(3, null, 12, 0, "PUBLISHED", 3));
        assertThat(build(rows, null, 3L, true)).extracting(CommentNode::id).containsExactly(3L, 2L, 1L);
        // On an ordinary post the rank does not change the order
        assertThat(build(rows, null, null, false)).extracting(CommentNode::id).containsExactly(1L, 2L, 3L);
        assertThat(build(rows, null, 3L, true).get(0).best()).isTrue();
    }

    @Test
    void hiddenCommentDisappearsUnlessItHasVisibleReplies() {
        List<CommentNode> tree = build(List.of(
                row(1, null, 10, 0, "HIDDEN", 1),
                row(2, null, 11, 0, "REMOVED", 2),
                row(3, 2L, 12, 0, "PUBLISHED", 3),
                row(4, null, 13, 0, "REMOVED", 4),
                row(5, 4L, 14, 0, "HIDDEN", 5)), null, null, false);
        assertThat(tree).extracting(CommentNode::id).containsExactly(2L);
        CommentNode placeholder = tree.get(0);
        assertThat(placeholder.removed()).isTrue();
        assertThat(placeholder.author()).isNull();
        assertThat(placeholder.content()).isEmpty();
        assertThat(placeholder.replies()).extracting(CommentNode::id).containsExactly(3L);
    }

    @Test
    void marksTheViewersOwnComments() {
        List<CommentNode> tree = build(List.of(row(1, null, 10, 0, "PUBLISHED", 1),
                row(2, null, 11, 0, "PUBLISHED", 2)), 11L, null, false);
        assertThat(tree).extracting(CommentNode::mine).containsExactly(false, true);
    }

    @Test
    void whoCanSeeAPost() {
        BlogRow published = blog("PUBLISHED");
        assertThat(BlogDetailService.canSee(published, null)).isTrue();
        for (String own : List.of("PROCESSING", "HIDDEN", "REJECTED")) {
            assertThat(BlogDetailService.canSee(blog(own), 7L)).as(own + " author").isTrue();
            assertThat(BlogDetailService.canSee(blog(own), 8L)).as(own + " other").isFalse();
            assertThat(BlogDetailService.canSee(blog(own), null)).as(own + " guest").isFalse();
        }
        assertThat(BlogDetailService.canSee(blog("REMOVED"), 7L)).isFalse();
    }

    @Test
    void rankFromPoints() {
        List<RankRow> ranks = List.of(new RankRow("Senpai tin cậy", 300), new RankRow("Người chỉ đường", 100),
                new RankRow("Người mới", 0));
        assertThat(BlogDetailService.rankName(ranks, 0)).isEqualTo("Người mới");
        assertThat(BlogDetailService.rankName(ranks, 100)).isEqualTo("Người chỉ đường");
        assertThat(BlogDetailService.rankName(ranks, 999)).isEqualTo("Senpai tin cậy");
    }

    @Test
    void squashIgnoresAccentsSpacesAndPunctuation() {
        assertThat(VietnameseText.squash("Việc  nhẹ, lương-CAO!")).isEqualTo(VietnameseText.squash("việc nhẹ lương cao"));
    }

    private static BlogRow blog(String status) {
        return new BlogRow(1L, 7L, "A", null, 0, "POST", "t", "c", status, false, false, null, 0, 0, T0);
    }
}
