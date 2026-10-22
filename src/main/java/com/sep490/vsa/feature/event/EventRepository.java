package com.sep490.vsa.feature.event;

import com.sep490.vsa.feature.blog.ActiveAuthors;
import com.sep490.vsa.feature.event.EventDtos.AlbumPhoto;
import com.sep490.vsa.feature.event.EventDtos.Media;
import com.sep490.vsa.feature.event.EventDtos.PlacePick;
import com.sep490.vsa.feature.event.EventDtos.Tag;
import com.sep490.vsa.feature.event.EventRules.CleanEvent;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Plain SQL for events and event reviews ({@code events}, {@code reviews} rows with an {@code event_id},
 * {@code review_media}, {@code review_likes}). Reviews of places live in the same tables but are not read here:
 * every review query filters {@code event_id}.
 * <p>
 * Reviews shown to Members and Guests follow the blog rule: published, and the author's account ACTIVE
 * ({@link ActiveAuthors}). A deleted event ({@code deleted_at}) is shown nowhere, its reviews neither.
 */
@Repository
public class EventRepository {

    private final JdbcTemplate jdbc;
    private final NamedParameterJdbcTemplate named;
    private final String mediaBaseUrl;

    public EventRepository(JdbcTemplate jdbc, NamedParameterJdbcTemplate named,
                           @Value("${app.media.public-base-url:/media/}") String mediaBaseUrl) {
        this.jdbc = jdbc;
        this.named = named;
        this.mediaBaseUrl = mediaBaseUrl;
    }

    // ---------------------------------------------------------------- events

    /** An event row. {@code coverKey} is the storage key of its cover photo, or null. */
    public record EventRow(Long id, String title, LocalDate startDate, LocalDate endDate, String locationName,
                           BigDecimal lat, BigDecimal lng, String costNote, String tip, Long coverMediaId,
                           String coverKey, String createdByName) {
    }

    private static final String EVENT_COLUMNS = """
            e.id, e.title, e.start_date, e.end_date, e.location_name, e.lat, e.lng, e.cost_note, e.tip,
            e.cover_media_id, cm.storage_key, cu.display_name
            FROM events e
            LEFT JOIN media cm ON cm.id = e.cover_media_id AND cm.deleted_at IS NULL
            LEFT JOIN users cu ON cu.id = e.created_by""";

    private static EventRow eventRow(ResultSet rs, int i) throws java.sql.SQLException {
        return new EventRow(rs.getLong(1), rs.getString(2), rs.getDate(3).toLocalDate(), rs.getDate(4).toLocalDate(),
                rs.getString(5), rs.getBigDecimal(6), rs.getBigDecimal(7), rs.getString(8), rs.getString(9),
                (Long) rs.getObject(10, Long.class), rs.getString(11), rs.getString(12));
    }

    /**
     * Events not deleted, soonest first. {@code keyword} (may be null) matches the title, the place or the cost,
     * ignoring case and Vietnamese accents ({@code utf8mb4_0900_ai_ci}: "le hoi" finds "Lễ hội").
     */
    public List<EventRow> events(String keyword) {
        if (keyword == null) {
            return jdbc.query("SELECT " + EVENT_COLUMNS + " WHERE e.deleted_at IS NULL ORDER BY e.start_date, e.id",
                    EventRepository::eventRow);
        }
        String like = "%" + escapeLike(keyword) + "%";
        return jdbc.query("SELECT " + EVENT_COLUMNS + " " + """
                 WHERE e.deleted_at IS NULL
                   AND (e.title COLLATE utf8mb4_0900_ai_ci LIKE ?
                        OR e.location_name COLLATE utf8mb4_0900_ai_ci LIKE ?
                        OR e.cost_note COLLATE utf8mb4_0900_ai_ci LIKE ?)
                 ORDER BY e.start_date, e.id""", EventRepository::eventRow, like, like, like);
    }

    public Optional<EventRow> event(long id) {
        return jdbc.query("SELECT " + EVENT_COLUMNS + " WHERE e.id = ? AND e.deleted_at IS NULL",
                EventRepository::eventRow, id).stream().findFirst();
    }

    /** Locks the event row (reviews of one event are written one after another, see EventReviewService). */
    public Optional<EventRow> lockEvent(long id) {
        return jdbc.query("SELECT " + EVENT_COLUMNS + " WHERE e.id = ? AND e.deleted_at IS NULL FOR UPDATE",
                EventRepository::eventRow, id).stream().findFirst();
    }

    public String coverUrl(EventRow e) {
        return e.coverKey() == null ? null : mediaBaseUrl + e.coverKey();
    }

    /** Average rating and number of the published reviews of active accounts, every year: event id → {avg, n}. */
    public Map<Long, double[]> reviewStats() {
        Map<Long, double[]> stats = new HashMap<>();
        jdbc.query("""
                SELECT r.event_id, AVG(r.rating), COUNT(*) FROM reviews r
                WHERE r.event_id IS NOT NULL AND r.status = 'PUBLISHED' AND %s
                GROUP BY r.event_id""".formatted(ActiveAuthors.of("r")),
                (ResultSet rs) -> {
                    while (rs.next()) {
                        stats.put(rs.getLong(1), new double[]{rs.getDouble(2), rs.getLong(3)});
                    }
                    return null;
                });
        return stats;
    }

    public long insertEvent(CleanEvent e, long moderatorId, Instant now) {
        KeyHolder key = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement("""
                    INSERT INTO events (title, start_date, end_date, location_name, lat, lng, cost_note, tip, created_by,
                                        created_at, updated_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""", Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, e.title());
            ps.setDate(2, Date.valueOf(e.startDate()));
            ps.setDate(3, Date.valueOf(e.endDate()));
            ps.setString(4, e.locationName());
            ps.setBigDecimal(5, e.lat());
            ps.setBigDecimal(6, e.lng());
            ps.setString(7, e.costNote());
            ps.setString(8, e.tip());
            ps.setLong(9, moderatorId);
            ps.setTimestamp(10, Timestamp.from(now));
            ps.setTimestamp(11, Timestamp.from(now));
            return ps;
        }, key);
        return key.getKey().longValue();
    }

    public void updateEvent(long id, CleanEvent e, Instant now) {
        jdbc.update("""
                UPDATE events SET title = ?, start_date = ?, end_date = ?, location_name = ?, lat = ?, lng = ?,
                                  cost_note = ?, tip = ?, updated_at = ?
                WHERE id = ? AND deleted_at IS NULL""",
                e.title(), Date.valueOf(e.startDate()), Date.valueOf(e.endDate()), e.locationName(), e.lat(), e.lng(),
                e.costNote(), e.tip(), Timestamp.from(now), id);
    }

    public void setCover(long eventId, Long mediaId) {
        jdbc.update("UPDATE events SET cover_media_id = ? WHERE id = ?", mediaId, eventId);
    }

    /** Soft delete: the row stays (reviews point to it); the event and its reviews are shown nowhere. */
    public boolean softDelete(long id, Instant now) {
        return jdbc.update("UPDATE events SET deleted_at = ? WHERE id = ? AND deleted_at IS NULL",
                Timestamp.from(now), id) == 1;
    }

    /**
     * A cover photo is uploaded by a Moderator (staff, trusted), so it is not sent to the image scanner: it is marked
     * PASSED as soon as it is saved, and shows at once.
     */
    public void markCoverPassed(long mediaId) {
        jdbc.update("UPDATE media SET scan_status = 'PASSED' WHERE id = ?", mediaId);
    }

    /** "Tìm nhanh địa điểm có sẵn": approved ASVSJ places with a position whose name matches. */
    public List<PlacePick> placesWithPosition(String keyword, int limit) {
        String like = "%" + escapeLike(keyword) + "%";
        return jdbc.query("""
                SELECT id, COALESCE(name_vi, name_en, name_ja), address, lat, lng FROM places
                WHERE status = 'ACTIVE' AND lat IS NOT NULL AND lng IS NOT NULL
                  AND (name_vi COLLATE utf8mb4_0900_ai_ci LIKE ? OR name_ja LIKE ?
                       OR name_en COLLATE utf8mb4_0900_ai_ci LIKE ?)
                ORDER BY name_vi LIMIT ?""",
                (rs, i) -> new PlacePick(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getBigDecimal(4),
                        rs.getBigDecimal(5)), like, like, like, limit);
    }

    // ---------------------------------------------------------------- reviews (read)

    /** A review row for the detail page; the author's points give the rank. */
    public record ReviewRow(Long id, Long authorId, String authorName, String avatarUrl, int authorPoints, int rating,
                            String content, int year, String status, boolean edited, Instant createdAt,
                            int likeCount) {
    }

    /**
     * The event's reviews for this viewer: published ones of active accounts, plus the viewer's own in any state but
     * REMOVED (being scanned, rejected, hidden by reports). Newest year first, then newest review.
     */
    public List<ReviewRow> reviews(long eventId, Long viewerId) {
        return jdbc.query("""
                SELECT r.id, r.author_id, u.display_name, u.avatar_url, u.points, r.rating, r.content, r.event_year,
                       r.status, r.is_edited, r.created_at, r.like_count
                FROM reviews r JOIN users u ON u.id = r.author_id
                WHERE r.event_id = ?
                  AND ((r.status = 'PUBLISHED' AND %s) OR (r.author_id = ? AND r.status <> 'REMOVED'))
                ORDER BY r.event_year DESC, r.created_at DESC, r.id DESC""".formatted(ActiveAuthors.of("r")),
                (rs, i) -> new ReviewRow(rs.getLong(1), rs.getLong(2), rs.getString(3), rs.getString(4), rs.getInt(5),
                        rs.getInt(6), rs.getString(7), rs.getInt(8), rs.getString(9), rs.getBoolean(10),
                        rs.getTimestamp(11).toInstant(), rs.getInt(12)),
                eventId, viewerId == null ? -1L : viewerId);
    }

    /**
     * Photos and videos of these reviews, by review, in their order. Files whose scan was rejected or that were
     * deleted are left out; files still being scanned only for {@code ownerId} (the author sees them).
     */
    public Map<Long, List<Media>> mediaOf(Collection<Long> reviewIds, Long ownerId) {
        Map<Long, List<Media>> result = new HashMap<>();
        if (reviewIds.isEmpty()) {
            return result;
        }
        named.query("""
                SELECT rm.review_id, m.id, m.media_type, m.storage_key, m.width, m.height, m.scan_status
                FROM review_media rm JOIN media m ON m.id = rm.media_id
                WHERE rm.review_id IN (:ids) AND m.deleted_at IS NULL
                  AND (m.scan_status = 'PASSED' OR (m.scan_status = 'PROCESSING' AND m.owner_id = :owner))
                ORDER BY rm.review_id, rm.sort_order, m.id""",
                Map.of("ids", reviewIds, "owner", ownerId == null ? -1L : ownerId), (ResultSet rs) -> {
                    while (rs.next()) {
                        result.computeIfAbsent(rs.getLong(1), k -> new ArrayList<>())
                                .add(new Media(rs.getLong(2), rs.getString(3), mediaBaseUrl + rs.getString(4),
                                        (Integer) rs.getObject(5, Integer.class),
                                        (Integer) rs.getObject(6, Integer.class), rs.getString(7)));
                    }
                    return null;
                });
        return result;
    }

    /** "Album từ review": photos of the published reviews of active accounts, newest year and review first. */
    public List<AlbumPhoto> album(long eventId) {
        return jdbc.query("""
                SELECT m.storage_key, u.display_name, r.event_year, m.width, m.height
                FROM reviews r JOIN users u ON u.id = r.author_id
                JOIN review_media rm ON rm.review_id = r.id JOIN media m ON m.id = rm.media_id
                WHERE r.event_id = ? AND r.status = 'PUBLISHED' AND %s
                  AND m.media_type = 'IMAGE' AND m.scan_status = 'PASSED' AND m.deleted_at IS NULL
                ORDER BY r.event_year DESC, r.created_at DESC, rm.sort_order, m.id""".formatted(ActiveAuthors.of("r")),
                (rs, i) -> new AlbumPhoto(mediaBaseUrl + rs.getString(1), rs.getString(2), rs.getInt(3),
                        (Integer) rs.getObject(4, Integer.class), (Integer) rs.getObject(5, Integer.class)),
                eventId);
    }

    public Set<Long> likedReviews(long eventId, long userId) {
        return new HashSet<>(jdbc.query("""
                SELECT rl.review_id FROM review_likes rl JOIN reviews r ON r.id = rl.review_id
                WHERE r.event_id = ? AND rl.user_id = ?""", (rs, i) -> rs.getLong(1), eventId, userId));
    }

    public Map<Long, List<Tag>> tagsOf(Collection<Long> userIds) {
        Map<Long, List<Tag>> result = new HashMap<>();
        if (userIds.isEmpty()) {
            return result;
        }
        named.query("""
                SELECT ut.user_id, t.name, t.color FROM user_tags ut JOIN tag_types t ON t.id = ut.tag_type_id
                WHERE ut.user_id IN (:ids) AND ut.revoked_at IS NULL AND t.is_active ORDER BY ut.granted_at""",
                Map.of("ids", userIds), (ResultSet rs) -> {
                    while (rs.next()) {
                        result.computeIfAbsent(rs.getLong(1), k -> new ArrayList<>())
                                .add(new Tag(rs.getString(2), rs.getString(3)));
                    }
                    return null;
                });
        return result;
    }

    public record RankRow(String name, int minPoints) {
    }

    /** Ranks from the highest threshold down. */
    public List<RankRow> ranks() {
        return jdbc.query("SELECT name, min_points FROM ranks ORDER BY min_points DESC",
                (rs, i) -> new RankRow(rs.getString(1), rs.getInt(2)));
    }

    /** Every file still attached to the review (any scan status), in order: the edit form and the edit itself. */
    public List<Media> attachedMedia(long reviewId) {
        return jdbc.query("""
                SELECT m.id, m.media_type, m.storage_key, m.width, m.height, m.scan_status
                FROM review_media rm JOIN media m ON m.id = rm.media_id
                WHERE rm.review_id = ? AND m.deleted_at IS NULL ORDER BY rm.sort_order, m.id""",
                (rs, i) -> new Media(rs.getLong(1), rs.getString(2), mediaBaseUrl + rs.getString(3),
                        (Integer) rs.getObject(4, Integer.class), (Integer) rs.getObject(5, Integer.class),
                        rs.getString(6)), reviewId);
    }

    // ---------------------------------------------------------------- reviews (write)

    /** A review as its author or the publish job needs it. */
    public record ReviewState(Long id, Long eventId, Long authorId, int year, String status, String content) {
    }

    private static final String REVIEW_STATE = """
            SELECT id, event_id, author_id, event_year, status, content FROM reviews
            WHERE id = ? AND event_id IS NOT NULL""";

    public Optional<ReviewState> review(long reviewId, boolean lock) {
        return jdbc.query(REVIEW_STATE + (lock ? " FOR UPDATE" : ""),
                (rs, i) -> new ReviewState(rs.getLong(1), rs.getLong(2), rs.getLong(3), rs.getInt(4), rs.getString(5),
                        rs.getString(6)), reviewId).stream().findFirst();
    }

    /** The user's review of the event for this year (any status), if any. */
    public Optional<Long> ownReviewId(long eventId, int year, long userId) {
        return jdbc.query("SELECT id FROM reviews WHERE event_id = ? AND event_year = ? AND author_id = ?",
                (rs, i) -> rs.getLong(1), eventId, year, userId).stream().findFirst();
    }

    public record OwnRow(Long id, int rating, String content, String status) {
    }

    public Optional<OwnRow> ownReview(long reviewId) {
        return jdbc.query("SELECT id, rating, content, status FROM reviews WHERE id = ?",
                (rs, i) -> new OwnRow(rs.getLong(1), rs.getInt(2), rs.getString(3), rs.getString(4)), reviewId)
                .stream().findFirst();
    }

    public long insertReview(long authorId, long eventId, int year, int rating, String content, String status,
                             Instant now) {
        KeyHolder key = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement("""
                    INSERT INTO reviews (author_id, event_id, event_year, rating, content, status, created_at, updated_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?)""", Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, authorId);
            ps.setLong(2, eventId);
            ps.setInt(3, year);
            ps.setInt(4, rating);
            ps.setString(5, content);
            ps.setString(6, status);
            ps.setTimestamp(7, Timestamp.from(now));
            ps.setTimestamp(8, Timestamp.from(now));
            return ps;
        }, key);
        return key.getKey().longValue();
    }

    public void updateReview(long reviewId, int rating, String content, String status, Instant now) {
        jdbc.update("""
                UPDATE reviews SET rating = ?, content = ?, status = ?, is_edited = TRUE, updated_at = ? WHERE id = ?""",
                rating, content, status, Timestamp.from(now), reviewId);
    }

    public void linkMedia(long reviewId, long mediaId, int sortOrder) {
        jdbc.update("INSERT INTO review_media (review_id, media_id, sort_order) VALUES (?, ?, ?)",
                reviewId, mediaId, sortOrder);
    }

    public void unlinkMedia(long reviewId, Collection<Long> mediaIds) {
        if (mediaIds.isEmpty()) {
            return;
        }
        named.update("DELETE FROM review_media WHERE review_id = :review AND media_id IN (:ids)",
                Map.of("review", reviewId, "ids", mediaIds));
    }

    public void setMediaOrder(long reviewId, long mediaId, int sortOrder) {
        jdbc.update("UPDATE review_media SET sort_order = ? WHERE review_id = ? AND media_id = ?",
                sortOrder, reviewId, mediaId);
    }

    /** Ids of the review's media rows (deleted files too), so deleting the review discards all of them. */
    public List<Long> allMediaIds(long reviewId) {
        return jdbc.query("SELECT media_id FROM review_media WHERE review_id = ?", (rs, i) -> rs.getLong(1), reviewId);
    }

    public void deleteReview(long reviewId) {
        jdbc.update("DELETE FROM reviews WHERE id = ?", reviewId);
    }

    /** Moves the review from one status to another; false when it was not in {@code from} any more. */
    public boolean setStatus(long reviewId, String from, String to) {
        return jdbc.update("UPDATE reviews SET status = ? WHERE id = ? AND status = ?", to, reviewId, from) == 1;
    }

    public record MediaState(Long id, String scanStatus) {
    }

    public List<MediaState> mediaStates(long reviewId) {
        return jdbc.query("""
                SELECT m.id, m.scan_status FROM review_media rm JOIN media m ON m.id = rm.media_id
                WHERE rm.review_id = ? AND m.deleted_at IS NULL ORDER BY rm.sort_order, m.id""",
                (rs, i) -> new MediaState(rs.getLong(1), rs.getString(2)), reviewId);
    }

    /** Event reviews still PROCESSING that were sent before {@code before}, after id {@code afterId}, oldest first. */
    public List<Long> processingReviewIds(Instant before, long afterId, int limit) {
        return jdbc.query("""
                SELECT id FROM reviews WHERE event_id IS NOT NULL AND status = 'PROCESSING' AND updated_at < ? AND id > ?
                ORDER BY id LIMIT ?""", (rs, i) -> rs.getLong(1), Timestamp.from(before), afterId, limit);
    }

    /** Whether the review's points were paid already (its first publication), so they are never paid twice. */
    public boolean reviewAwarded(long reviewId) {
        Long n = jdbc.queryForObject("""
                SELECT COUNT(*) FROM point_transactions WHERE ref_type = 'REVIEW' AND ref_id = ? AND reason = ?""",
                Long.class, reviewId, EventReviewPublishService.AWARD_REASON);
        return n != null && n > 0;
    }

    // ---------------------------------------------------------------- likes

    /** @return true when the like is new */
    public boolean addLike(long reviewId, long userId, Instant now) {
        int added = jdbc.update("INSERT IGNORE INTO review_likes (review_id, user_id, created_at) VALUES (?, ?, ?)",
                reviewId, userId, Timestamp.from(now));
        if (added == 1) {
            jdbc.update("UPDATE reviews SET like_count = like_count + 1 WHERE id = ?", reviewId);
        }
        return added == 1;
    }

    public boolean removeLike(long reviewId, long userId) {
        int removed = jdbc.update("DELETE FROM review_likes WHERE review_id = ? AND user_id = ?", reviewId, userId);
        if (removed == 1) {
            jdbc.update("UPDATE reviews SET like_count = GREATEST(0, like_count - 1) WHERE id = ?", reviewId);
        }
        return removed == 1;
    }

    public int likeCount(long reviewId) {
        Integer n = jdbc.queryForObject("SELECT like_count FROM reviews WHERE id = ?", Integer.class, reviewId);
        return n == null ? 0 : n;
    }

    /** Whether the author of the review is an ACTIVE account (hidden content answers 404 to actions). */
    public boolean authorActive(long reviewId) {
        Long n = jdbc.queryForObject("SELECT COUNT(*) FROM reviews r WHERE r.id = ? AND " + ActiveAuthors.of("r"),
                Long.class, reviewId);
        return n != null && n > 0;
    }

    static String escapeLike(String text) {
        return text.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }
}
