package com.sep490.vsa.feature.blog;

import com.sep490.vsa.feature.blog.BlogDetailDtos.Author;
import com.sep490.vsa.feature.blog.BlogDetailDtos.CommentNode;
import com.sep490.vsa.feature.blog.BlogDetailRepository.CommentRow;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * Builds the comment tree of a post from flat rows (by {@code parent_id}).
 * <ul>
 *   <li>Published comments are shown. Hidden, rejected or deleted comments, and comments of locked or deleted
 *       accounts ({@link ActiveAuthors}), are left out, unless they still have visible replies: then they stay as an
 *       empty placeholder (no writer, no text) so the replies keep their context.</li>
 *   <li>Top level: the best answer first; on a question, answers by "Senpai tin cậy" come next (rank perk);
 *       then oldest first. Replies are always oldest first.</li>
 *   <li>Replies deeper than {@link BlogActionService#MAX_REPLY_DEPTH} are refused; older rows that are deeper
 *       are shown under their ancestor at that depth. So the tree, and the JSON of the page, nests at most one level
 *       more than that, whatever the data (a long chain once made the page fail for everyone).</li>
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
        Map<Long, Long> shownUnder = shownParents(rows);
        for (CommentRow r : rows) {
            Long parent = shownUnder.get(r.id());
            if (parent == null) {
                roots.add(r);
            } else {
                children.computeIfAbsent(parent, k -> new ArrayList<>()).add(r);
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

    /**
     * The comment each row is shown under (null = top level): its parent; the top level when the parent is missing
     * (rather than losing the reply); its ancestor at depth MAX_REPLY_DEPTH when the row sits deeper. Computed
     * without recursion, so even a very long chain of replies cannot overflow the stack.
     */
    static Map<Long, Long> shownParents(List<CommentRow> rows) {
        Map<Long, CommentRow> byId = new HashMap<>();
        rows.forEach(r -> byId.put(r.id(), r));
        Map<Long, Integer> depth = new HashMap<>();
        for (CommentRow r : rows) {
            // Walk up to a comment whose depth is known (or past the top), then fill in the depths on the way back
            Deque<CommentRow> path = new ArrayDeque<>();
            CommentRow at = r;
            while (at != null && !depth.containsKey(at.id()) && path.size() <= byId.size()) {
                path.push(at);
                at = at.parentId() == null ? null : byId.get(at.parentId());
            }
            int d = at != null && depth.containsKey(at.id()) ? depth.get(at.id()) : -1;
            while (!path.isEmpty()) {
                depth.put(path.pop().id(), ++d);
            }
        }
        // Parents before their replies, so a deep row finds where its parent is shown
        List<CommentRow> shallowFirst = rows.stream()
                .sorted(Comparator.comparing((CommentRow r) -> depth.get(r.id())).thenComparing(CommentRow::id))
                .toList();
        Map<Long, Long> shownUnder = new HashMap<>();
        for (CommentRow r : shallowFirst) {
            int d = depth.get(r.id());
            Long parent = d == 0 ? null : r.parentId();
            if (d > BlogActionService.MAX_REPLY_DEPTH) {
                parent = depth.get(r.parentId()) == BlogActionService.MAX_REPLY_DEPTH ? r.parentId()
                        : shownUnder.get(r.parentId());
            }
            shownUnder.put(r.id(), parent);
        }
        return shownUnder;
    }

    /** Shown with its text: published (or the viewer's own still being processed), by an active account. */
    static boolean visible(CommentRow r, Long viewerId) {
        return r.authorActive() && ("PUBLISHED".equals(r.status())
                || ("PROCESSING".equals(r.status()) && viewerId != null && viewerId.equals(r.authorId())));
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
