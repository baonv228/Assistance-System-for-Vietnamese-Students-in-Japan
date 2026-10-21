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

    /**
     * Only places that are on the map (approved, not removed or merged) can be linked. The search is PlaceService.
     * <p>
     * A locking read ({@code FOR SHARE}), so it sees the rows as committed now, like
     * {@link com.sep490.vsa.feature.place.PlaceRepository#insertOsmPlace}: when another post saved the same new
     * OpenStreetMap place a moment earlier, the save links that row, which a plain SELECT would not find in this
     * transaction's older snapshot ("Có địa điểm không còn trên bản đồ"). The rows stay share-locked until the post is
     * saved, so a place cannot be removed in between. Outside a transaction (the first check, before the photos are
     * decoded) the lock ends with the statement.
     */
    public int countActivePlaces(List<Long> ids) {
        if (ids.isEmpty()) {
            return 0;
        }
        Integer n = named.queryForObject(
                "SELECT COUNT(*) FROM places WHERE status = 'ACTIVE' AND id IN (:ids) FOR SHARE",
                Map.of("ids", ids), Integer.class);
        return n == null ? 0 : n;
    }

    /**
     * Places that have a published "Cảnh báo lừa đảo" post, newest post first. Questions do not count: asking
     * whether a place is a scam is not a warning. A merged place reports the place it was merged into.
     * A post of a locked or deleted account is hidden ({@link ActiveAuthors}) and marks no place.
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
                WHERE b.status = 'PUBLISHED' AND b.blog_type = 'POST' AND shown.status = 'ACTIVE' AND %s
                  AND (:placeId IS NULL OR shown.id = :placeId)
                ORDER BY b.created_at DESC, b.id DESC""".formatted(ActiveAuthors.of("b")), params,
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

    // ---------------------------------------------------------------- editing a post

    public record EditableBlog(Long id, Long authorId, String type, String status, String title, String content) {
    }

    /** The post as it is now; {@code lock} keeps the row locked until the transaction ends (the save). */
    public Optional<EditableBlog> editableBlog(long blogId, boolean lock) {
        return jdbc.query("SELECT id, author_id, blog_type, status, title, content FROM blogs WHERE id = ?"
                        + (lock ? " FOR UPDATE" : ""),
                (rs, i) -> new EditableBlog(rs.getLong(1), rs.getLong(2), rs.getString(3), rs.getString(4),
                        rs.getString(5), rs.getString(6)), blogId).stream().findFirst();
    }

    /** Active topics the post is linked to, in the order of the topic list. */
    public List<Long> topicIds(long blogId) {
        return jdbc.query("""
                SELECT t.id FROM blog_topic_links btl JOIN blog_topics t ON t.id = btl.topic_id
                WHERE btl.blog_id = ? AND t.is_active ORDER BY t.is_other, t.sort_order, t.name""",
                (rs, i) -> rs.getLong(1), blogId);
    }

    /** @param type IMAGE or VIDEO */
    public record AttachedMedia(Long id, String type, String storageKey, String scanStatus, int sortOrder) {
    }

    /** Photos and video of the post that are not deleted (rejected files are deleted by the scan), in display order. */
    public List<AttachedMedia> attachedMedia(long blogId) {
        return jdbc.query("""
                SELECT m.id, m.media_type, m.storage_key, m.scan_status, bm.sort_order
                FROM blog_media bm JOIN media m ON m.id = bm.media_id
                WHERE bm.blog_id = ? AND m.deleted_at IS NULL ORDER BY bm.sort_order, m.id""",
                (rs, i) -> new AttachedMedia(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4),
                        rs.getInt(5)), blogId);
    }

    /** New title and content; the post shows "· đã sửa" from now on. */
    public void updateBlog(long blogId, String title, String content, String status, Instant now) {
        jdbc.update("UPDATE blogs SET title = ?, content = ?, status = ?, is_edited = TRUE, updated_at = ? WHERE id = ?",
                title, content, status, Timestamp.from(now), blogId);
    }

    public void unlinkTopics(long blogId) {
        jdbc.update("DELETE FROM blog_topic_links WHERE blog_id = ?", blogId);
    }

    public void unlinkPlaces(long blogId) {
        jdbc.update("DELETE FROM blog_place_links WHERE blog_id = ?", blogId);
    }

    public void unlinkMedia(long blogId, List<Long> mediaIds) {
        if (mediaIds.isEmpty()) {
            return;
        }
        named.update("DELETE FROM blog_media WHERE blog_id = :blogId AND media_id IN (:ids)",
                Map.of("blogId", blogId, "ids", mediaIds));
    }

    public void setMediaOrder(long blogId, long mediaId, int sortOrder) {
        jdbc.update("UPDATE blog_media SET sort_order = ? WHERE blog_id = ? AND media_id = ?", sortOrder, blogId, mediaId);
    }

    /** True when the post has already earned its points once (they are never paid twice, even after an edit). */
    public boolean postAwarded(long blogId) {
        Integer n = jdbc.queryForObject("""
                SELECT COUNT(*) FROM point_transactions WHERE ref_type = 'BLOG' AND ref_id = ? AND reason = 'BLOG_CREATED'""",
                Integer.class, blogId);
        return n != null && n > 0;
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

    /**
     * One page of the posts still waiting for their scan that were sent before {@code before} (for the retry job), in
     * id order after {@code afterId}: the job walks through all of them page by page, so posts that stay stuck (a
     * missing file, a scanner that keeps failing on them) never keep newer ones from being tried.
     */
    public List<Long> processingBlogIds(Instant before, long afterId, int limit) {
        return jdbc.query("""
                SELECT id FROM blogs WHERE status = 'PROCESSING' AND created_at < ? AND id > ? ORDER BY id LIMIT ?""",
                (rs, i) -> rs.getLong(1), Timestamp.from(before), afterId, limit);
    }
}
