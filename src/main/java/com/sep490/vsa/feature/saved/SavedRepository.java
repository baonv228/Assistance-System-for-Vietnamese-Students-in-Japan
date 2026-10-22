package com.sep490.vsa.feature.saved;

import com.sep490.vsa.feature.blog.ActiveAuthors;
import com.sep490.vsa.feature.qa.QaRepository;
import com.sep490.vsa.feature.saved.SavedDtos.SavedAuthor;
import com.sep490.vsa.feature.saved.SavedDtos.SavedBlog;
import com.sep490.vsa.feature.saved.SavedDtos.SavedPlace;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Read-only SQL over {@code saved_places} and {@code saved_blogs}. Saving and unsaving are done by the features that
 * own the items (POST/DELETE /api/blogs|questions/{id}/save; places once the Map is built).
 * <p>
 * Only items others can still see are listed: ACTIVE places (a merged place shows the place it was merged into) and
 * PUBLISHED posts and questions of active accounts ({@link ActiveAuthors}). The rows of hidden, removed or rejected
 * items, and of items whose author was locked or deleted, stay, so they come back if restored or unlocked.
 */
@Repository
public class SavedRepository {

    private static final String SHOWN_PLACES = """
            FROM saved_places sp
            JOIN places p ON p.id = sp.place_id
            JOIN places shown ON shown.id = CASE WHEN p.status = 'MERGED' THEN p.merged_into_id ELSE p.id END
            WHERE sp.user_id = ? AND shown.status = 'ACTIVE'""";

    /** Saved posts and questions that are shown: published, by an active account. */
    private static final String SHOWN_BLOG = "b.status = 'PUBLISHED' AND " + ActiveAuthors.of("b");

    private static final String SHOWN_BLOGS = """
            FROM saved_blogs sb JOIN blogs b ON b.id = sb.blog_id
            WHERE sb.user_id = ? AND %s""".formatted(SHOWN_BLOG);

    private final JdbcTemplate jdbc;

    public SavedRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record Account(String role, String status) {
    }

    public Optional<Account> account(long userId) {
        return jdbc.query("SELECT role, status FROM users WHERE id = ?",
                (rs, i) -> new Account(rs.getString(1), rs.getString(2)), userId).stream().findFirst();
    }

    /** Saved places, newest first; a place saved twice through a merge is listed once. */
    public List<SavedPlace> places(long userId) {
        return jdbc.query("""
                SELECT shown.id, shown.name_vi, c.id, c.name_vi, c.icon, shown.address, shown.is_closed,
                       MAX(sp.saved_at) AS saved_at
                FROM saved_places sp
                JOIN places p ON p.id = sp.place_id
                JOIN places shown ON shown.id = CASE WHEN p.status = 'MERGED' THEN p.merged_into_id ELSE p.id END
                JOIN place_categories c ON c.id = shown.category_id
                WHERE sp.user_id = ? AND shown.status = 'ACTIVE'
                GROUP BY shown.id, shown.name_vi, c.id, c.name_vi, c.icon, shown.address, shown.is_closed
                ORDER BY saved_at DESC, shown.id DESC""",
                (rs, i) -> new SavedPlace(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4),
                        rs.getString(5), rs.getString(6), rs.getBoolean(7), instant(rs.getTimestamp(8))),
                userId);
    }

    /**
     * Saved posts ("POST") or questions ("QA"), newest saved first. Topics are attached by the service. Solved is
     * the rule of the Hỏi-Đáp list ({@link QaRepository#SOLVED_SQL}).
     */
    public List<SavedBlog> blogs(long userId, String type) {
        return jdbc.query("""
                SELECT b.id, b.blog_type, b.title, u.id, u.display_name, u.avatar_url, b.like_count, b.comment_count,
                       %s AS solved, b.created_at, sb.created_at AS saved_at
                FROM saved_blogs sb JOIN blogs b ON b.id = sb.blog_id JOIN users u ON u.id = b.author_id
                WHERE sb.user_id = ? AND %s AND b.blog_type = ?
                ORDER BY sb.created_at DESC, b.id DESC""".formatted(QaRepository.SOLVED_SQL, SHOWN_BLOG),
                (rs, i) -> new SavedBlog(rs.getLong(1), rs.getString(2), rs.getString(3),
                        new SavedAuthor(rs.getLong(4), rs.getString(5), rs.getString(6)), List.of(),
                        rs.getInt(7), rs.getInt(8), rs.getBoolean(9), instant(rs.getTimestamp(10)),
                        instant(rs.getTimestamp(11))),
                userId, type);
    }

    /** Places + posts + questions shown on "Đã lưu" (the "Đã lưu (N)" button on Tôi). */
    public int count(long userId) {
        Integer places = jdbc.queryForObject("SELECT COUNT(DISTINCT shown.id) " + SHOWN_PLACES, Integer.class, userId);
        Integer blogs = jdbc.queryForObject("SELECT COUNT(*) " + SHOWN_BLOGS, Integer.class, userId);
        return (places == null ? 0 : places) + (blogs == null ? 0 : blogs);
    }

    private static Instant instant(Timestamp ts) {
        return ts == null ? null : ts.toInstant();
    }
}
