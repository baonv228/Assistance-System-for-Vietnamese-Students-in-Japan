package com.sep490.vsa.feature.blog;

import com.sep490.vsa.feature.blog.BlogDetailDtos.Author;
import com.sep490.vsa.feature.blog.BlogDetailDtos.CommentNode;
import com.sep490.vsa.feature.blog.BlogDetailRepository.CommentRow;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * Builds the comment tree of a post from flat rows (by {@code parent_id}).
 * <ul>
 *   <li>Published comments are shown. Hidden, rejected or deleted comments are left out, unless they still
 *       have visible replies: then they stay as an empty placeholder so the replies keep their context.</li>
 *   <li>Top level: the best answer first; on a question, answers by "Senpai tin cậy" come next (rank perk);
 *       then oldest first. Replies are always oldest first.</li>
 * </ul>
 */
final class CommentTree {

    private CommentTree() {
    }

    /**
     * @param senpaiMinPoints points needed for the highest rank; their answers are listed first on a question
     * @param author          turns a comment row into its author (name, tags, rank)
     */
    static List<CommentNode> build(List<CommentRow> rows, Long viewerId, Long bestCommentId, boolean question,
                                   int senpaiMinPoints, Set<Long> likedByViewer, Function<CommentRow, Author> author) {
        Map<Long, List<CommentRow>> children = new HashMap<>();
        List<CommentRow> roots = new ArrayList<>();
        Map<Long, CommentRow> byId = new HashMap<>();
        rows.forEach(r -> byId.put(r.id(), r));
        for (CommentRow r : rows) {
            // A reply whose parent is missing is shown at the top level rather than lost
            if (r.parentId() == null || !byId.containsKey(r.parentId())) {
                roots.add(r);
            } else {
                children.computeIfAbsent(r.parentId(), k -> new ArrayList<>()).add(r);
            }
        }
        Comparator<CommentRow> oldestFirst = Comparator.comparing(CommentRow::createdAt,
                Comparator.nullsLast(Comparator.naturalOrder())).thenComparing(CommentRow::id);
        Comparator<CommentRow> topOrder = Comparator
                .comparing((CommentRow r) -> r.id().equals(bestCommentId) ? 0 : 1)
                .thenComparing(r -> question && r.authorPoints() >= senpaiMinPoints ? 0 : 1)
                .thenComparing(oldestFirst);

        Builder builder = new Builder(children, viewerId, bestCommentId, likedByViewer, author, oldestFirst);
        return roots.stream().sorted(topOrder).map(builder::node).filter(n -> n != null).toList();
    }

    static boolean visible(CommentRow r, Long viewerId) {
        return "PUBLISHED".equals(r.status())
                || ("PROCESSING".equals(r.status()) && viewerId != null && viewerId.equals(r.authorId()));
    }

    private record Builder(Map<Long, List<CommentRow>> children, Long viewerId, Long bestCommentId,
                           Set<Long> liked, Function<CommentRow, Author> author, Comparator<CommentRow> order) {

        /** The node, or null when neither the comment nor any reply below it is visible. */
        CommentNode node(CommentRow r) {
            List<CommentNode> replies = children.getOrDefault(r.id(), List.of()).stream().sorted(order)
                    .map(this::node).filter(n -> n != null).toList();
            boolean shown = visible(r, viewerId);
            if (!shown && replies.isEmpty()) {
                return null;
            }
            boolean mine = viewerId != null && viewerId.equals(r.authorId());
            if (!shown) {
                return new CommentNode(r.id(), r.parentId(), null, "", false, true, false, false, false, 0,
                        r.createdAt(), replies);
            }
            return new CommentNode(r.id(), r.parentId(), author.apply(r), r.content(), r.edited(), false,
                    r.id().equals(bestCommentId), mine, liked.contains(r.id()), r.likeCount(), r.createdAt(), replies);
        }
    }
}
