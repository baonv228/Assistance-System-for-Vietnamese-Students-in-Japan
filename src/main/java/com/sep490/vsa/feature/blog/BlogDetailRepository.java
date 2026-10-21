package com.sep490.vsa.feature.blog;

import com.sep490.vsa.feature.blog.BlogDetailDtos.Media;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * SQL for Post Detail and its actions (like, save, comment, best answer, delete). Plain SQL like the feed:
 * the blog tables have no JPA entities yet, so nothing here clashes with teammates' future entities.
 */
@Repository
public class BlogDetailRepository {

    private final JdbcTemplate jdbc;

    public BlogDetailRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // ---------------------------------------------------------------- reads

    /** @param authorStatus {@code users.status} of the author: only an ACTIVE author's post is shown ({@link ActiveAuthors}) */
    public record BlogRow(Long id, Long authorId, String authorName, String avatarUrl, int authorPoints,
                          String authorStatus, String type, String title, String content, String status, boolean pinned,
                          boolean edited, Long bestCommentId, int likeCount, int commentCount, Instant createdAt) {

        public boolean authorActive() {
            return "ACTIVE".equals(authorStatus);
        }
    }

    private static final String SELECT_BLOG = """
            SELECT b.id, b.author_id, u.display_name, u.avatar_url, u.points, u.status, b.blog_type, b.title, b.content,
                   b.status, b.is_pinned, b.is_edited, b.best_comment_id, b.like_count, b.comment_count, b.created_at
            FROM blogs b JOIN users u ON u.id = b.author_id WHERE b.id = ?""";

    private static BlogRow blog(ResultSet rs, int i) throws SQLException {
        return new BlogRow(rs.getLong(1), rs.getLong(2), rs.getString(3), rs.getString(4), rs.getInt(5),
                rs.getString(6), rs.getString(7), rs.getString(8), rs.getString(9), rs.getString(10),
                rs.getBoolean(11), rs.getBoolean(12), nullableLong(rs, 13), rs.getInt(14), rs.getInt(15),
                instant(rs.getTimestamp(16)));
    }

    public Optional<BlogRow> findBlog(long id) {
        return jdbc.query(SELECT_BLOG, BlogDetailRepository::blog, id).stream().findFirst();
    }

    /**
     * {@link #findBlog} with the post row locked until the transaction ends, so actions on one post run one after
     * another. Only the post row is locked ({@code OF b}): a lock on the author's {@code users} row would also block
     * everyone who notifies or pays that author.
     */
    public Optional<BlogRow> lockBlog(long id) {
        return jdbc.query(SELECT_BLOG + " FOR UPDATE OF b", BlogDetailRepository::blog, id).stream().findFirst();
    }

    /** @param authorStatus {@code users.status} of the writer: a comment of a locked or deleted account is hidden */
    public record CommentRow(Long id, Long blogId, Long parentId, Long authorId, String authorName, String avatarUrl,
                             int authorPoints, String authorStatus, String content, String status, boolean edited,
                             int likeCount, Instant createdAt) {

        public boolean authorActive() {
            return "ACTIVE".equals(authorStatus);
        }
    }

    private static final String SELECT_COMMENT = """
            SELECT c.id, c.blog_id, c.parent_id, c.author_id, u.display_name, u.avatar_url, u.points, u.status, c.content,
                   c.status, c.is_edited, c.like_count, c.created_at
            FROM comments c JOIN users u ON u.id = c.author_id""";

    private static CommentRow comment(ResultSet rs, int i) throws SQLException {
        return new CommentRow(rs.getLong(1), rs.getLong(2), nullableLong(rs, 3), rs.getLong(4), rs.getString(5),
                rs.getString(6), rs.getInt(7), rs.getString(8), rs.getString(9), rs.getString(10), rs.getBoolean(11),
                rs.getInt(12), instant(rs.getTimestamp(13)));
    }

    /** Every comment of the post, oldest first; the service decides what each viewer sees. */
    public List<CommentRow> comments(long blogId) {
        return jdbc.query(SELECT_COMMENT + " WHERE c.blog_id = ? ORDER BY c.created_at, c.id",
                BlogDetailRepository::comment, blogId);
    }

    public Optional<CommentRow> findComment(long id) {
        return jdbc.query(SELECT_COMMENT + " WHERE c.id = ?", BlogDetailRepository::comment, id).stream().findFirst();
    }

    /** {@link #findComment} with the comment row (only that row) locked until the transaction ends. */
    public Optional<CommentRow> lockComment(long id) {
        return jdbc.query(SELECT_COMMENT + " WHERE c.id = ? FOR UPDATE OF c", BlogDetailRepository::comment, id)
                .stream().findFirst();
    }

    /** The comment's parent id; null for a top-level comment or a comment that no longer exists. */
    public Long parentIdOf(long commentId) {
        List<Long> parent = jdbc.query("SELECT parent_id FROM comments WHERE id = ?",
                (rs, i) -> nullableLong(rs, 1), commentId);
        return parent.isEmpty() ? null : parent.get(0);
    }

    public boolean hasReplies(long commentId) {
        return count("SELECT COUNT(*) FROM comments WHERE parent_id = ?", commentId) > 0;
    }

    /** Ids of the post's photos and videos (their files are removed when the post is deleted). */
    public List<Long> mediaIds(long blogId) {
        return jdbc.query("SELECT media_id FROM blog_media WHERE blog_id = ?", (rs, i) -> rs.getLong(1), blogId);
    }

    /** Ids of every comment of the post (used to close their report cases when the post is deleted). */
    public List<Long> commentIds(long blogId) {
        return jdbc.query("SELECT id FROM comments WHERE blog_id = ?", (rs, i) -> rs.getLong(1), blogId);
    }

    /**
     * Photos and videos of the post. Media whose scan was rejected or that was deleted never shows;
     * media still being scanned shows only to the author.
     */
    public List<Media> media(long blogId, boolean includeProcessing, String publicBaseUrl) {
        return jdbc.query("""
                SELECT m.id, m.media_type, m.storage_key, m.mime_type, m.width, m.height FROM blog_media bm
                JOIN media m ON m.id = bm.media_id
                WHERE bm.blog_id = ? AND m.deleted_at IS NULL
                  AND (m.scan_status = 'PASSED' OR (? AND m.scan_status = 'PROCESSING'))
                ORDER BY bm.sort_order, m.id""",
                (rs, i) -> new Media(rs.getLong(1), rs.getString(2), publicBaseUrl + rs.getString(3), rs.getString(4),
                        (Integer) rs.getObject(5), (Integer) rs.getObject(6)),
                blogId, includeProcessing);
    }

    public record RankRow(String name, int minPoints) {
    }

    /** Ranks from the highest threshold down. */
    public List<RankRow> ranks() {
        return jdbc.query("SELECT name, min_points FROM ranks ORDER BY min_points DESC",
                (rs, i) -> new RankRow(rs.getString(1), rs.getInt(2)));
    }

    public boolean likedBlog(long blogId, long userId) {
        return count("SELECT COUNT(*) FROM blog_likes WHERE blog_id = ? AND user_id = ?", blogId, userId) > 0;
    }

    public boolean savedBlog(long blogId, long userId) {
        return count("SELECT COUNT(*) FROM saved_blogs WHERE blog_id = ? AND user_id = ?", blogId, userId) > 0;
    }

    public Set<Long> likedComments(long blogId, long userId) {
        return new HashSet<>(jdbc.query("""
                SELECT cl.comment_id FROM comment_likes cl JOIN comments c ON c.id = cl.comment_id
                WHERE c.blog_id = ? AND cl.user_id = ?""", (rs, i) -> rs.getLong(1), blogId, userId));
    }

    public record Member(String role, String status, Instant createdAt) {
    }

    public Optional<Member> member(long userId) {
        return jdbc.query("SELECT role, status, created_at FROM users WHERE id = ?",
                (rs, i) -> new Member(rs.getString(1), rs.getString(2), instant(rs.getTimestamp(3))), userId)
                .stream().findFirst();
    }

    // ---------------------------------------------------------------- comment limits (spam)

    /**
     * Locks the user's {@code user_daily_usage} row of {@code day} (made when missing) until the transaction ends:
     * the same lock Write Post takes, so one user's comments (and posts) run one after another. The upsert takes
     * the row lock directly. Not the {@code users} row: other people's comments, likes and notifications take shared
     * locks on users rows (foreign keys), so two users answering each other at the same moment would deadlock.
     */
    public void lockUserDay(long userId, LocalDate day) {
        jdbc.update("""
                INSERT INTO user_daily_usage (user_id, usage_date) VALUES (?, ?)
                ON DUPLICATE KEY UPDATE posts = posts""", userId, Date.valueOf(day));
    }

    /** When the user last wrote a comment, reply or answer (any status); null if never. */
    public Instant lastCommentAt(long userId) {
        return instant(jdbc.queryForObject("SELECT MAX(created_at) FROM comments WHERE author_id = ?",
                Timestamp.class, userId));
    }

    /** The user's comments written in [from, to), any status (a comment the author deleted is gone). */
    public long countComments(long userId, Instant from, Instant to) {
        return count("SELECT COUNT(*) FROM comments WHERE author_id = ? AND created_at >= ? AND created_at < ?",
                userId, Timestamp.from(from), Timestamp.from(to));
    }

    public record CommentText(Long blogId, String content) {
    }

    /** The user's newest comments since {@code since} on posts other than {@code exceptBlogId}, at most {@code limit}. */
    public List<CommentText> recentCommentsElsewhere(long userId, long exceptBlogId, Instant since, int limit) {
        return jdbc.query("""
                SELECT blog_id, content FROM comments WHERE author_id = ? AND blog_id <> ? AND created_at >= ?
                ORDER BY id DESC LIMIT ?""",
                (rs, i) -> new CommentText(rs.getLong(1), rs.getString(2)),
                userId, exceptBlogId, Timestamp.from(since), limit);
    }

    // ---------------------------------------------------------------- likes and saves

    /** @return the new like count */
    public int setBlogLike(long blogId, long userId, boolean on, Instant now) {
        if (on) {
            jdbc.update("INSERT IGNORE INTO blog_likes (blog_id, user_id, created_at) VALUES (?, ?, ?)",
                    blogId, userId, Timestamp.from(now));
        } else {
            jdbc.update("DELETE FROM blog_likes WHERE blog_id = ? AND user_id = ?", blogId, userId);
        }
        // Recount instead of +1/-1 so the cached number can never drift
        jdbc.update("""
                UPDATE blogs SET like_count = (SELECT COUNT(*) FROM blog_likes WHERE blog_id = ?),
                                 updated_at = updated_at
                WHERE id = ?""", blogId, blogId);
        return (int) count("SELECT like_count FROM blogs WHERE id = ?", blogId);
    }

    public int setCommentLike(long commentId, long userId, boolean on, Instant now) {
        if (on) {
            jdbc.update("INSERT IGNORE INTO comment_likes (comment_id, user_id, created_at) VALUES (?, ?, ?)",
                    commentId, userId, Timestamp.from(now));
        } else {
            jdbc.update("DELETE FROM comment_likes WHERE comment_id = ? AND user_id = ?", commentId, userId);
        }
        jdbc.update("""
                UPDATE comments SET like_count = (SELECT COUNT(*) FROM comment_likes WHERE comment_id = ?),
                                    updated_at = updated_at
                WHERE id = ?""", commentId, commentId);
        return (int) count("SELECT like_count FROM comments WHERE id = ?", commentId);
    }

    public void setSaved(long blogId, long userId, boolean on, Instant now) {
        if (on) {
            jdbc.update("INSERT IGNORE INTO saved_blogs (blog_id, user_id, created_at) VALUES (?, ?, ?)",
                    blogId, userId, Timestamp.from(now));
        } else {
            jdbc.update("DELETE FROM saved_blogs WHERE blog_id = ? AND user_id = ?", blogId, userId);
        }
    }

    // ---------------------------------------------------------------- comments

    public long insertComment(long blogId, long authorId, Long parentId, String content, Instant now) {
        Timestamp at = Timestamp.from(now);
        KeyHolder key = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement("""
                    INSERT INTO comments (blog_id, author_id, parent_id, content, status, created_at, updated_at)
                    VALUES (?, ?, ?, ?, 'PUBLISHED', ?, ?)""", Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, blogId);
            ps.setLong(2, authorId);
            ps.setObject(3, parentId);
            ps.setString(4, content);
            ps.setTimestamp(5, at);
            ps.setTimestamp(6, at);
            return ps;
        }, key);
        return key.getKey().longValue();
    }

    /** Published comments and replies of active accounts: what {@code blogs.comment_count} counts. */
    private static final String SHOWN_COMMENTS = """
            SELECT COUNT(*) FROM comments c WHERE c.blog_id = %s AND c.status = 'PUBLISHED' AND %s""";

    /**
     * Keeps {@code blogs.comment_count} equal to the number of published comments and replies whose writer is an
     * active account (comments of locked or deleted accounts are hidden, {@link ActiveAuthors}).
     */
    public void recountComments(long blogId) {
        jdbc.update("""
                UPDATE blogs SET comment_count = (%s), updated_at = updated_at
                WHERE id = ?""".formatted(SHOWN_COMMENTS.formatted("?", ActiveAuthors.of("c"))), blogId, blogId);
    }

    /**
     * Locks the rows of the posts the user commented on, in id order, until the transaction ends. An account change
     * takes these locks before it writes the {@code users} row: the comment actions also lock the post first and
     * reach users rows later (foreign keys, {@link #recountComments}), so the two never wait for each other in a circle.
     */
    public void lockPostsCommentedBy(long userId) {
        jdbc.query("""
                SELECT b.id FROM blogs b WHERE b.id IN (SELECT x.blog_id FROM comments x WHERE x.author_id = ?)
                ORDER BY b.id FOR UPDATE OF b""", (rs, i) -> rs.getLong(1), userId);
    }

    /** {@link #recountComments} for every post the user commented on, after the user's status changed. */
    public void recountPostsCommentedBy(long userId) {
        jdbc.update("""
                UPDATE blogs b SET b.comment_count = (%s), b.updated_at = b.updated_at
                WHERE b.id IN (SELECT x.blog_id FROM comments x WHERE x.author_id = ?)"""
                .formatted(SHOWN_COMMENTS.formatted("b.id", ActiveAuthors.of("c"))), userId);
    }

    /** New text of a published comment, marked "đã sửa". Likes, replies and the best-answer mark stay. */
    public boolean updateComment(long commentId, String content, Instant now) {
        return jdbc.update("""
                UPDATE comments SET content = ?, is_edited = TRUE, updated_at = ?
                WHERE id = ? AND status = 'PUBLISHED'""", content, Timestamp.from(now), commentId) > 0;
    }

    public void deleteComment(long commentId) {
        jdbc.update("DELETE FROM comments WHERE id = ?", commentId);
    }

    /**
     * Deleting a comment that has replies would delete other people's replies too (ON DELETE CASCADE), so the
     * row stays as an empty placeholder ("Bình luận đã bị xóa") and the replies keep their place.
     */
    public void blankComment(long commentId, Instant now) {
        jdbc.update("UPDATE comments SET status = 'REMOVED', content = '', removed_at = ? WHERE id = ?",
                Timestamp.from(now), commentId);
        jdbc.update("DELETE FROM comment_likes WHERE comment_id = ?", commentId);
        jdbc.update("UPDATE comments SET like_count = 0 WHERE id = ?", commentId);
    }

    /** True for the empty row {@link #blankComment} leaves (a Moderator-removed comment keeps its text). */
    public boolean isBlankPlaceholder(long commentId) {
        return count("SELECT COUNT(*) FROM comments WHERE id = ? AND status = 'REMOVED' AND content = ''", commentId) > 0;
    }

    /** Auto-hide after too many reports; only touches content that is still published. */
    public boolean hideComment(long commentId) {
        return jdbc.update("UPDATE comments SET status = 'HIDDEN' WHERE id = ? AND status = 'PUBLISHED'", commentId) > 0;
    }

    // ---------------------------------------------------------------- post

    public void setBestComment(long blogId, Long commentId) {
        jdbc.update("UPDATE blogs SET best_comment_id = ?, updated_at = updated_at WHERE id = ?", commentId, blogId);
    }

    public boolean hideBlog(long blogId) {
        return jdbc.update("UPDATE blogs SET status = 'HIDDEN' WHERE id = ? AND status = 'PUBLISHED'", blogId) > 0;
    }

    /**
     * Deletes the post; topics, places, media links, likes, saves and snapshots go with it (CASCADE). Its comments
     * are deleted first, newest first: a reply is always newer than the comment it answers, so each comment goes
     * after its replies and no cascade runs down a chain of replies (MySQL refuses cascades deeper than 15 levels,
     * which a thread of 15 nested replies already reaches).
     */
    public void deleteBlog(long blogId) {
        jdbc.update("DELETE FROM comments WHERE blog_id = ? ORDER BY id DESC", blogId);
        jdbc.update("DELETE FROM blogs WHERE id = ?", blogId);
    }

    // ---------------------------------------------------------------- helpers

    private long count(String sql, Object... args) {
        Long n = jdbc.queryForObject(sql, Long.class, args);
        return n == null ? 0 : n;
    }

    private static Long nullableLong(ResultSet rs, int column) throws SQLException {
        long v = rs.getLong(column);
        return rs.wasNull() ? null : v;
    }

    private static Instant instant(Timestamp ts) {
        return ts == null ? null : ts.toInstant();
    }
}
