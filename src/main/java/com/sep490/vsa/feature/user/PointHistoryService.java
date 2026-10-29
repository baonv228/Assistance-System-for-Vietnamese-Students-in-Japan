package com.sep490.vsa.feature.user;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * UC-U10 "Lịch sử điểm" on Tôi: the user's point changes, newest first, 20 a page, each with its reason and the
 * content it is about. The content is linked only while people can still see it (a published post or question, a
 * published review of an active place or of an event not deleted, an approved place); otherwise the line says what it
 * was without a link. Reads other features' tables with plain SQL and never writes them.
 */
@Service
public class PointHistoryService {

    static final int PAGE_SIZE = 20;

    /** Reasons written by PointService callers, as the page shows them. */
    static final Map<String, String> REASONS = Map.of(
            "REVIEW_CREATED", "Viết review",
            "BLOG_CREATED", "Đăng bài viết",
            "BEST_ANSWER", "Câu trả lời hay nhất",
            "BEST_ANSWER_CHANGED", "Không còn là câu trả lời hay nhất",
            "PIN_APPROVED", "Ghim được duyệt",
            "CONTENT_DELETED", "Tự xóa nội dung, điểm được thu lại",
            "CONTENT_REMOVED", "Nội dung bị Moderator gỡ",
            "PRACTICE_CREATED", "Câu luyện tập",
            "PRACTICE_CORRECT", "Câu được đánh dấu đúng");

    public record PointLine(long id, int delta, String reason, String reasonLabel, String title, String link,
                            Instant createdAt) {
    }

    public record PointPage(List<PointLine> items, int page, int size, long total, int points) {
    }

    private final JdbcTemplate jdbc;

    public PointHistoryService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional(readOnly = true)
    public PointPage history(long userId, int page) {
        int p = Math.max(0, page);
        List<PointLine> items = jdbc.query("""
                SELECT t.id, t.delta, t.reason, t.ref_type, t.ref_id, t.created_at,
                       b.title, b.blog_type, b.status = 'PUBLISHED' AS blog_shown,
                       cb.id, cb.title, cb.blog_type, c.status = 'PUBLISHED' AND cb.status = 'PUBLISHED' AS comment_shown,
                       rp.id, rp.name_vi, rp.status = 'ACTIVE' AND r.status = 'PUBLISHED' AS review_place_shown,
                       e.id, e.title, e.deleted_at IS NULL AND r.status = 'PUBLISHED' AS review_event_shown,
                       COALESCE(pm.id, pl.id), COALESCE(pm.name_vi, pl.name_vi),
                       COALESCE(pm.status, pl.status) = 'ACTIVE' AS place_shown
                FROM point_transactions t
                LEFT JOIN blogs b ON t.ref_type = 'BLOG' AND b.id = t.ref_id
                LEFT JOIN comments c ON t.ref_type = 'COMMENT' AND c.id = t.ref_id
                LEFT JOIN blogs cb ON cb.id = c.blog_id
                LEFT JOIN reviews r ON t.ref_type = 'REVIEW' AND r.id = t.ref_id
                LEFT JOIN places rp ON rp.id = r.place_id
                LEFT JOIN events e ON e.id = r.event_id
                LEFT JOIN places pl ON t.ref_type = 'PLACE' AND pl.id = t.ref_id
                LEFT JOIN places pm ON pm.id = pl.merged_into_id AND pl.status = 'MERGED'
                WHERE t.user_id = ?
                ORDER BY t.created_at DESC, t.id DESC
                LIMIT ? OFFSET ?""", (rs, i) -> line(rs), userId, PAGE_SIZE, p * PAGE_SIZE);
        Long total = jdbc.queryForObject("SELECT COUNT(*) FROM point_transactions WHERE user_id = ?", Long.class, userId);
        Integer points = jdbc.queryForObject("SELECT points FROM users WHERE id = ?", Integer.class, userId);
        return new PointPage(items, p, PAGE_SIZE, total == null ? 0 : total, points == null ? 0 : points);
    }

    private static PointLine line(ResultSet rs) throws SQLException {
        String refType = rs.getString("ref_type");
        String title = null;
        String link = null;
        switch (refType == null ? "" : refType) {
            case "BLOG" -> {
                title = rs.getString(7);
                if (rs.getBoolean("blog_shown")) {
                    link = blogLink(rs.getString(8), rs.getLong("ref_id"));
                }
            }
            case "COMMENT" -> {
                title = rs.getString(11);
                if (rs.getBoolean("comment_shown")) {
                    link = blogLink(rs.getString(12), rs.getLong(10));
                }
            }
            case "REVIEW" -> {
                if (rs.getObject(14) != null) {
                    title = rs.getString(15);
                    link = rs.getBoolean("review_place_shown") ? "/map?place=" + rs.getLong(14) : null;
                } else if (rs.getObject(17) != null) {
                    title = rs.getString(18);
                    link = rs.getBoolean("review_event_shown") ? "/events/" + rs.getLong(17) : null;
                }
            }
            case "PLACE" -> {
                title = rs.getString(21);
                if (rs.getBoolean("place_shown")) {
                    link = "/map?place=" + rs.getLong(20);
                }
            }
            default -> {
            }
        }
        String reason = rs.getString("reason");
        return new PointLine(rs.getLong("id"), rs.getInt("delta"), reason, REASONS.getOrDefault(reason, reason), title,
                link, rs.getTimestamp("created_at").toInstant());
    }

    private static String blogLink(String type, long id) {
        return ("QA".equals(type) ? "/qa/" : "/blog/") + id;
    }
}
