package com.sep490.vsa.feature.user;

import com.sep490.vsa.common.exception.ApiException;
import com.sep490.vsa.common.security.AppUserPrincipal;
import com.sep490.vsa.feature.blog.ActiveAuthors;
import com.sep490.vsa.feature.qa.QaRepository;
import com.sep490.vsa.feature.user.PublicProfileDtos.BlogLine;
import com.sep490.vsa.feature.user.PublicProfileDtos.Page;
import com.sep490.vsa.feature.user.PublicProfileDtos.PublicProfile;
import com.sep490.vsa.feature.user.PublicProfileDtos.ReviewLine;
import com.sep490.vsa.feature.user.PublicProfileDtos.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;

/**
 * Public profile (UC-U07): plain SQL over other features' tables, read only. Only what everyone can see counts: published
 * content, reviews of active places and of events not deleted, so the owner sees the same page as anyone else. A
 * LOCKED or DELETED account answers 404, like its content ({@link ActiveAuthors}).
 */
@Service
public class PublicProfileService {

    static final String NOT_FOUND = "Không tìm thấy người dùng này.";
    static final int PAGE_SIZE = 10;
    /** A list is not read 2,000 rows deep, and a deep OFFSET is costly ("cần chốt"), as on "Review mới". */
    static final int MAX_PAGE = 199;
    static final int EXCERPT = 200;

    private static final String REVIEW_SHOWN = """
            r.author_id = ? AND r.status = 'PUBLISHED'
            AND ((r.event_id IS NULL AND p.status = 'ACTIVE') OR (r.event_id IS NOT NULL AND e.deleted_at IS NULL))""";
    private static final String REVIEW_FROM = """
            FROM reviews r LEFT JOIN places p ON p.id = r.place_id LEFT JOIN events e ON e.id = r.event_id
            """;

    private final JdbcTemplate jdbc;

    public PublicProfileService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public PublicProfile profile(long id, AppUserPrincipal viewer) {
        UserRow user = active(id);
        boolean member = Role.USER.name().equals(user.role());
        boolean self = viewer != null && viewer.id() == id;
        boolean viewerCanWrite = viewer == null || Role.USER.name().equals(viewer.role());
        String rank = !member ? null : jdbc.query(
                "SELECT name FROM ranks WHERE min_points <= ? ORDER BY min_points DESC LIMIT 1",
                (rs, i) -> rs.getString(1), user.points()).stream().findFirst().orElse(null);
        List<Tag> tags = jdbc.query("""
                SELECT t.name, t.color FROM user_tags ut JOIN tag_types t ON t.id = ut.tag_type_id
                WHERE ut.user_id = ? AND ut.revoked_at IS NULL AND t.is_active ORDER BY ut.granted_at""",
                (rs, i) -> new Tag(rs.getString(1), rs.getString(2)), id);
        return new PublicProfile(id, user.displayName(), user.avatarUrl(), tags, rank,
                member ? null : Role.valueOf(user.role()).label(), user.createdAt(),
                count("SELECT COUNT(*) " + REVIEW_FROM + "WHERE " + REVIEW_SHOWN, id),
                blogCount(id, "POST"), blogCount(id, "QA"), bestAnswers(id), likesReceived(id), self,
                member && !self && viewerCanWrite);
    }

    public Page<ReviewLine> reviews(long id, int page) {
        active(id);
        int p = page(page);
        List<ReviewLine> rows = jdbc.query("""
                SELECT r.id, r.place_id, r.event_id, COALESCE(p.name_vi, e.title) AS title, c.icon, r.rating,
                       LEFT(r.content, 400) AS content_start, r.like_count, r.created_at
                %s
                LEFT JOIN place_categories c ON c.id = p.category_id
                WHERE %s
                ORDER BY r.created_at DESC, r.id DESC LIMIT ? OFFSET ?""".formatted(REVIEW_FROM, REVIEW_SHOWN),
                (rs, i) -> new ReviewLine(rs.getLong("id"), rs.getObject("place_id", Long.class),
                        rs.getObject("event_id", Long.class), rs.getString("title"), rs.getString("icon"),
                        rs.getInt("rating"), excerpt(rs.getString("content_start")), rs.getInt("like_count"),
                        rs.getTimestamp("created_at").toInstant()),
                id, PAGE_SIZE + 1, p * PAGE_SIZE);
        return page(rows, p);
    }

    public Page<BlogLine> blogs(long id, String kind, int page) {
        active(id);
        int p = page(page);
        List<BlogLine> rows = jdbc.query("""
                SELECT b.id, b.title, LEFT(b.content, 400) AS content_start, b.like_count, b.comment_count, b.created_at,
                       %s AS solved,
                       (SELECT t.name FROM blog_topic_links btl JOIN blog_topics t ON t.id = btl.topic_id
                        WHERE btl.blog_id = b.id AND t.is_active ORDER BY t.is_other, t.sort_order, t.name LIMIT 1)
                         AS topic_name
                FROM blogs b WHERE b.author_id = ? AND b.blog_type = ? AND b.status = 'PUBLISHED'
                ORDER BY b.created_at DESC, b.id DESC LIMIT ? OFFSET ?""".formatted(QaRepository.SOLVED_SQL),
                (rs, i) -> new BlogLine(rs.getLong("id"), rs.getString("title"), excerpt(rs.getString("content_start")),
                        rs.getString("topic_name"), rs.getInt("like_count"), rs.getInt("comment_count"),
                        rs.getBoolean("solved"), rs.getTimestamp("created_at").toInstant()),
                id, kind, PAGE_SIZE + 1, p * PAGE_SIZE);
        return page(rows, p);
    }

    private record UserRow(String displayName, String avatarUrl, String role, int points, Instant createdAt) {
    }

    private UserRow active(long id) {
        return jdbc.query("""
                SELECT display_name, avatar_url, role, points, created_at FROM users WHERE id = ? AND status = 'ACTIVE'""",
                (rs, i) -> new UserRow(rs.getString(1), rs.getString(2), rs.getString(3), rs.getInt(4),
                        rs.getTimestamp(5).toInstant()),
                id).stream().findFirst().orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, NOT_FOUND));
    }

    private long blogCount(long id, String kind) {
        return count("SELECT COUNT(*) FROM blogs WHERE author_id = ? AND blog_type = ? AND status = 'PUBLISHED'", id, kind);
    }

    /** Questions of active accounts, still published, whose best answer is this member's published answer. */
    private long bestAnswers(long id) {
        return count("""
                SELECT COUNT(*) FROM blogs b JOIN comments c ON c.id = b.best_comment_id
                WHERE c.author_id = ? AND c.status = 'PUBLISHED' AND b.status = 'PUBLISHED' AND b.blog_type = 'QA'
                  AND %s""".formatted(ActiveAuthors.of("b")), id);
    }

    /** Likes on the reviews, posts and questions counted above and on published comments under published posts. */
    private long likesReceived(long id) {
        return count("SELECT COALESCE(SUM(r.like_count), 0) " + REVIEW_FROM + "WHERE " + REVIEW_SHOWN, id)
                + count("SELECT COALESCE(SUM(like_count), 0) FROM blogs WHERE author_id = ? AND status = 'PUBLISHED'", id)
                + count("""
                        SELECT COALESCE(SUM(c.like_count), 0) FROM comments c JOIN blogs b ON b.id = c.blog_id
                        WHERE c.author_id = ? AND c.status = 'PUBLISHED' AND b.status = 'PUBLISHED' AND %s"""
                        .formatted(ActiveAuthors.of("b")), id);
    }

    private long count(String sql, Object... args) {
        Long n = jdbc.queryForObject(sql, Long.class, args);
        return n == null ? 0 : n;
    }

    private static int page(int page) {
        return Math.max(0, Math.min(page, MAX_PAGE));
    }

    private static <T> Page<T> page(List<T> rows, int page) {
        boolean more = rows.size() > PAGE_SIZE && page < MAX_PAGE;
        return new Page<>(rows.size() > PAGE_SIZE ? rows.subList(0, PAGE_SIZE) : rows, page, more);
    }

    private static String excerpt(String text) {
        if (text == null) {
            return "";
        }
        String oneLine = text.replaceAll("\\s+", " ").strip();
        return oneLine.length() <= EXCERPT ? oneLine : oneLine.substring(0, EXCERPT).strip() + "…";
    }
}
