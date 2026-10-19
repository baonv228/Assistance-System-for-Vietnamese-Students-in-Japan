package com.sep490.vsa.feature.blog;

import com.sep490.vsa.feature.blog.BlogWriteDtos.ScamWarning;
import com.sep490.vsa.feature.blog.BlogWriteDtos.TopicOption;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * SQL for Write Post (UC-11) and for publishing a post once its media is scanned. Plain SQL like the rest of
 * feature/blog: the blog tables have no JPA entities yet.
 */
@Repository
public class BlogWriteRepository {

    /** Posts with this topic that are linked to a place put "!" on the place's pin (BR-13). */
    static final String SCAM_WARNING_TOPIC = "Cảnh báo lừa đảo";

    private final JdbcTemplate jdbc;
    private final NamedParameterJdbcTemplate named;

    public BlogWriteRepository(JdbcTemplate jdbc, NamedParameterJdbcTemplate named) {
        this.jdbc = jdbc;
        this.named = named;
    }

    // ---------------------------------------------------------------- author

    public record Account(Long id, String role, String status, Instant createdAt) {
    }

    public Optional<Account> account(long userId) {
        return jdbc.query("SELECT id, role, status, created_at FROM users WHERE id = ? AND deleted_at IS NULL",
                (rs, i) -> new Account(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getTimestamp(4).toInstant()),
                userId).stream().findFirst();
    }

    public record Usage(int posts, int pins, int videos) {
    }

    /** Today's counters without locking (for the note on the screen). */
    public Usage usage(long userId, LocalDate day) {
        return jdbc.query("SELECT posts, pins, videos FROM user_daily_usage WHERE user_id = ? AND usage_date = ?",
                (rs, i) -> new Usage(rs.getInt(1), rs.getInt(2), rs.getInt(3)), userId, Date.valueOf(day))
                .stream().findFirst().orElse(new Usage(0, 0, 0));
    }

    /**
     * Today's counters, locked until the transaction ends, so two posts sent at the same moment cannot both
     * slip under the limit. The upsert takes the row lock directly (no shared-lock upgrade, no deadlock).
     */
    public Usage lockUsage(long userId, LocalDate day) {
        jdbc.update("""
                INSERT INTO user_daily_usage (user_id, usage_date) VALUES (?, ?)
                ON DUPLICATE KEY UPDATE posts = posts""", userId, Date.valueOf(day));
        return jdbc.queryForObject(
                "SELECT posts, pins, videos FROM user_daily_usage WHERE user_id = ? AND usage_date = ? FOR UPDATE",
                (rs, i) -> new Usage(rs.getInt(1), rs.getInt(2), rs.getInt(3)), userId, Date.valueOf(day));
    }

    public void addUsage(long userId, LocalDate day, int posts, int videos) {
        jdbc.update("""
                INSERT INTO user_daily_usage (user_id, usage_date, posts, videos) VALUES (?, ?, ?, ?)
                ON DUPLICATE KEY UPDATE posts = posts + ?, videos = videos + ?""",
                userId, Date.valueOf(day), posts, videos, posts, videos);
    }

    // ---------------------------------------------------------------- topics and places

    public List<TopicOption> activeTopics() {
        return jdbc.query("SELECT id, name, is_other FROM blog_topics WHERE is_active ORDER BY is_other, sort_order, name",
                (rs, i) -> new TopicOption(rs.getLong(1), rs.getString(2), rs.getBoolean(3),
                        SCAM_WARNING_TOPIC.equals(rs.getString(2))));
    }

    public int countActiveTopics(List<Long> ids) {
        if (ids.isEmpty()) {
            return 0;
        }
        Integer n = named.queryForObject("SELECT COUNT(*) FROM blog_topics WHERE is_active AND id IN (:ids)",
                Map.of("ids", ids), Integer.class);
        return n == null ? 0 : n;
    }

    /** Only places that are on the map (approved, not removed or merged) can be linked. The search is PlaceService. */
    public int countActivePlaces(List<Long> ids) {
        if (ids.isEmpty()) {
            return 0;
        }
        Integer n = named.queryForObject("SELECT COUNT(*) FROM places WHERE status = 'ACTIVE' AND id IN (:ids)",
                Map.of("ids", ids), Integer.class);
        return n == null ? 0 : n;
    }

    /**
     * Places that have a published "Cảnh báo lừa đảo" post, newest post first. Questions do not count: asking
     * whether a place is a scam is not a warning. A merged place reports the place it was merged into.
     * {@code placeId} narrows the list to one place (Place Details card).
     */
    public List<ScamWarning> scamWarnings(Long placeId) {
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("topic", SCAM_WARNING_TOPIC)
                .addValue("placeId", placeId);
        return named.query("""
                SELECT shown.id, b.id, b.title
                FROM blogs b
                JOIN blog_topic_links btl ON btl.blog_id = b.id
                JOIN blog_topics t ON t.id = btl.topic_id AND t.name = :topic AND t.is_active
                JOIN blog_place_links bpl ON bpl.blog_id = b.id
                JOIN places p ON p.id = bpl.place_id
                JOIN places shown ON shown.id = CASE WHEN p.status = 'MERGED' THEN p.merged_into_id ELSE p.id END
                WHERE b.status = 'PUBLISHED' AND b.blog_type = 'POST' AND shown.status = 'ACTIVE'
                  AND (:placeId IS NULL OR shown.id = :placeId)
                ORDER BY b.created_at DESC, b.id DESC""", params,
                (rs, i) -> new ScamWarning(rs.getLong(1), rs.getLong(2), rs.getString(3)));
    }

    // ---------------------------------------------------------------- new post

    public long insertBlog(long authorId, String type, String title, String content, String status, Instant now) {
        Timestamp at = Timestamp.from(now);
        KeyHolder key = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement("""
                    INSERT INTO blogs (author_id, blog_type, title, content, status, created_at, updated_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?)""", Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, authorId);
            ps.setString(2, type);
            ps.setString(3, title);
            ps.setString(4, content);
            ps.setString(5, status);
            ps.setTimestamp(6, at);
            ps.setTimestamp(7, at);
            return ps;
        }, key);
        return key.getKey().longValue();
    }

    public void linkTopics(long blogId, List<Long> topicIds) {
        for (Long topicId : topicIds) {
            jdbc.update("INSERT INTO blog_topic_links (blog_id, topic_id) VALUES (?, ?)", blogId, topicId);
        }
    }

    public void linkPlaces(long blogId, List<Long> placeIds) {
        for (Long placeId : placeIds) {
            jdbc.update("INSERT INTO blog_place_links (blog_id, place_id) VALUES (?, ?)", blogId, placeId);
        }
    }

    public void linkMedia(long blogId, long mediaId, int sortOrder) {
        jdbc.update("INSERT INTO blog_media (blog_id, media_id, sort_order) VALUES (?, ?, ?)", blogId, mediaId, sortOrder);
    }

    // ---------------------------------------------------------------- publishing after the scan

    public record PendingBlog(Long id, Long authorId, String type, String title, String status) {
    }

    /** Locks the post row so the scan job and the after-commit scan cannot publish it twice. */
    public Optional<PendingBlog> lockBlog(long blogId) {
        return jdbc.query("SELECT id, author_id, blog_type, title, status FROM blogs WHERE id = ? FOR UPDATE",
                (rs, i) -> new PendingBlog(rs.getLong(1), rs.getLong(2), rs.getString(3), rs.getString(4),
                        rs.getString(5)), blogId).stream().findFirst();
    }

    public String status(long blogId) {
        return jdbc.query("SELECT status FROM blogs WHERE id = ?", (rs, i) -> rs.getString(1), blogId)
                .stream().findFirst().orElse(null);
    }

    public record MediaState(Long id, String scanStatus) {
    }

    /** Media of the post that is not deleted, in display order. */
    public List<MediaState> mediaStates(long blogId) {
        return jdbc.query("""
                SELECT m.id, m.scan_status FROM blog_media bm JOIN media m ON m.id = bm.media_id
                WHERE bm.blog_id = ? AND m.deleted_at IS NULL ORDER BY bm.sort_order, m.id""",
                (rs, i) -> new MediaState(rs.getLong(1), rs.getString(2)), blogId);
    }

    /** Moves the post from one status to another; false when it was no longer in {@code from}. */
    public boolean setStatus(long blogId, String from, String to) {
        return jdbc.update("UPDATE blogs SET status = ?, updated_at = updated_at WHERE id = ? AND status = ?",
                to, blogId, from) > 0;
    }

    /** Posts still waiting for their scan that were sent before {@code before} (for the retry job). */
    public List<Long> processingBlogIds(Instant before, int limit) {
        return jdbc.query("""
                SELECT id FROM blogs WHERE status = 'PROCESSING' AND created_at < ? ORDER BY created_at LIMIT ?""",
                (rs, i) -> rs.getLong(1), Timestamp.from(before), limit);
    }
}
