package com.sep490.vsa.feature.notification;

import com.sep490.vsa.feature.notification.NotificationDtos.NotificationItem;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.util.List;

/**
 * Plain SQL over {@code notifications} for the bell and the "Thông báo" page. Each row gets the page it opens: a post
 * or question by its {@code blog_type} (whatever {@code ref_type} it was sent with), a place review on the Map, an event
 * review on its event; nothing when the post, review or event is gone.
 */
@Repository
public class NotificationRepository {

    private static final String SELECT_ITEMS = """
            SELECT n.id, n.type, n.content, n.is_read, n.created_at,
                   CASE
                       WHEN b.id IS NOT NULL AND b.blog_type = 'QA' THEN CONCAT('/qa/', b.id)
                       WHEN b.id IS NOT NULL THEN CONCAT('/blog/', b.id)
                       WHEN r.event_id IS NOT NULL AND e.id IS NOT NULL THEN CONCAT('/events/', e.id)
                       WHEN r.place_id IS NOT NULL THEN CONCAT('/map?place=', r.place_id)
                   END AS link
            FROM notifications n
            LEFT JOIN blogs b ON n.ref_type IN ('BLOG', 'QUESTION') AND b.id = n.ref_id
            LEFT JOIN reviews r ON n.ref_type = 'REVIEW' AND r.id = n.ref_id
            LEFT JOIN events e ON e.id = r.event_id AND e.deleted_at IS NULL
            WHERE n.user_id = ?""";

    private static final RowMapper<NotificationItem> ITEM = (rs, i) -> {
        Timestamp created = rs.getTimestamp(5);
        return new NotificationItem(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(6),
                rs.getBoolean(4), created == null ? null : created.toInstant());
    };

    private final JdbcTemplate jdbc;

    public NotificationRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** {@code limit} notifications from {@code offset}, newest first. */
    public List<NotificationItem> page(long userId, int offset, int limit) {
        return jdbc.query(SELECT_ITEMS + " ORDER BY n.created_at DESC, n.id DESC LIMIT ? OFFSET ?",
                ITEM, userId, limit, offset);
    }

    /** Notifications newer than {@code afterId}, newest first, at most {@code limit}. */
    public List<NotificationItem> after(long userId, long afterId, int limit) {
        return jdbc.query(SELECT_ITEMS + " AND n.id > ? ORDER BY n.id DESC LIMIT ?", ITEM, userId, afterId, limit);
    }

    public int unread(long userId) {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM notifications WHERE user_id = ? AND is_read = FALSE",
                Integer.class, userId);
        return n == null ? 0 : n;
    }

    public long latestId(long userId) {
        Long id = jdbc.queryForObject("SELECT COALESCE(MAX(id), 0) FROM notifications WHERE user_id = ?",
                Long.class, userId);
        return id == null ? 0 : id;
    }

    /** The user's own posts, questions and reviews (place and event) still waiting for their media scan. */
    public int processing(long userId) {
        Integer n = jdbc.queryForObject("""
                SELECT (SELECT COUNT(*) FROM blogs WHERE author_id = ? AND status = 'PROCESSING')
                     + (SELECT COUNT(*) FROM reviews WHERE author_id = ? AND status = 'PROCESSING')""",
                Integer.class, userId, userId);
        return n == null ? 0 : n;
    }

    /** Marks the user's unread notifications as read, those up to {@code upToId} when it is given. */
    public int markRead(long userId, Long upToId) {
        return upToId == null
                ? jdbc.update("UPDATE notifications SET is_read = TRUE WHERE user_id = ? AND is_read = FALSE", userId)
                : jdbc.update("""
                        UPDATE notifications SET is_read = TRUE WHERE user_id = ? AND is_read = FALSE AND id <= ?""",
                        userId, upToId);
    }
}
