package com.sep490.vsa.feature.blog;

import com.sep490.vsa.feature.blog.BlogDetailDtos.Media;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
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

    public record BlogRow(Long id, Long authorId, String authorName, String avatarUrl, int authorPoints,
                          String type, String title, String content, String status, boolean pinned, boolean edited,
                          Long bestCommentId, int likeCount, int commentCount, Instant createdAt) {
    }

    public Optional<BlogRow> findBlog(long id) {
        return jdbc.query("""
                SELECT b.id, b.author_id, u.display_name, u.avatar_url, u.points, b.blog_type, b.title, b.content,
                       b.status, b.is_pinned, b.is_edited, b.best_comment_id, b.like_count, b.comment_count, b.created_at
                FROM blogs b JOIN users u ON u.id = b.author_id WHERE b.id = ?""",
                (rs, i) -> new BlogRow(rs.getLong(1), rs.getLong(2), rs.getString(3), rs.getString(4), rs.getInt(5),
                        rs.getString(6), rs.getString(7), rs.getString(8), rs.getString(9), rs.getBoolean(10),
                        rs.getBoolean(11), nullableLong(rs, 12), rs.getInt(13), rs.getInt(14), instant(rs.getTimestamp(15))),
                id).stream().findFirst();
    }

    public record CommentRow(Long id, Long blogId, Long parentId, Long authorId, String authorName, String avatarUrl,
                             int authorPoints, String content, String status, boolean edited, int likeCount,
                             Instant createdAt) {
    }

    private static final String SELECT_COMMENT = """
            SELECT c.id, c.blog_id, c.parent_id, c.author_id, u.display_name, u.avatar_url, u.points, c.content,
                   c.status, c.is_edited, c.like_count, c.created_at
            FROM comments c JOIN users u ON u.id = c.author_id""";

    private static CommentRow comment(ResultSet rs, int i) throws SQLException {
        return new CommentRow(rs.getLong(1), rs.getLong(2), nullableLong(rs, 3), rs.getLong(4), rs.getString(5),
                rs.getString(6), rs.getInt(7), rs.getString(8), rs.getString(9), rs.getBoolean(10), rs.getInt(11),
                instant(rs.getTimestamp(12)));
    }

    /** Every comment of the post, oldest first; the service decides what each viewer sees. */
    public List<CommentRow> comments(long blogId) {
        return jdbc.query(SELECT_COMMENT + " WHERE c.blog_id = ? ORDER BY c.created_at, c.id",
                BlogDetailRepository::comment, blogId);
    }

    public Optional<CommentRow> findComment(long id) {
        return jdbc.query(SELECT_COMMENT + " WHERE c.id = ?", BlogDetailRepository::comment, id).stream().findFirst();
    }

    public boolean hasReplies(long commentId) {
        return count("SELECT COUNT(*) FROM comments WHERE parent_id = ?", commentId) > 0;
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

    public record Member(String role, String status) {
    }

    public Optional<Member> member(long userId) {
        return jdbc.query("SELECT role, status FROM users WHERE id = ?",
                (rs, i) -> new Member(rs.getString(1), rs.getString(2)), userId).stream().findFirst();
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

    /** Keeps {@code blogs.comment_count} equal to the number of published comments and replies. */
    public void recountComments(long blogId) {
        jdbc.update("""
                UPDATE blogs SET comment_count = (SELECT COUNT(*) FROM comments WHERE blog_id = ? AND status = 'PUBLISHED'),
                                 updated_at = updated_at
                WHERE id = ?""", blogId, blogId);
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

    /** Deletes the post; topics, places, media links, likes, saves, comments and snapshots go with it (CASCADE). */
    public void deleteBlog(long blogId) {
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
