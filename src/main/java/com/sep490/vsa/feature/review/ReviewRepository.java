package com.sep490.vsa.feature.review;

import org.springframework.jdbc.core.JdbcTemplate;
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
import java.util.Optional;

/** Plain SQL on {@code reviews}, {@code review_media} and the daily usage of new accounts. */
@Repository
public class ReviewRepository {

    private final JdbcTemplate jdbc;

    public ReviewRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // ---------------------------------------------------------------- author and place

    public record Account(long id, String role, String status, Instant createdAt) {
    }

    public Optional<Account> account(long userId) {
        return jdbc.query("SELECT id, role, status, created_at FROM users WHERE id = ? AND deleted_at IS NULL",
                (rs, i) -> new Account(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getTimestamp(4).toInstant()),
                userId).stream().findFirst();
    }

    public record PlaceRef(long id, String name, String status, Long mergedIntoId) {
    }

    public Optional<PlaceRef> place(long placeId) {
        return jdbc.query("SELECT id, name_vi, status, merged_into_id FROM places WHERE id = ?",
                (rs, i) -> new PlaceRef(rs.getLong(1), rs.getString(2), rs.getString(3), (Long) rs.getObject(4)),
                placeId).stream().findFirst();
    }

    /** The user's review of the place (any status but REMOVED: a removed review waits for an appeal). */
    public Optional<Long> reviewOf(long placeId, long authorId) {
        return jdbc.query("SELECT id FROM reviews WHERE place_id = ? AND author_id = ? AND status <> 'REMOVED'",
                (rs, i) -> rs.getLong(1), placeId, authorId).stream().findFirst();
    }

    /** True when the user has any review of the place, even a REMOVED one (the UNIQUE key counts it too). */
    public boolean anyReviewOf(long placeId, long authorId) {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM reviews WHERE place_id = ? AND author_id = ?",
                Integer.class, placeId, authorId);
        return n != null && n > 0;
    }

    // ---------------------------------------------------------------- daily usage (shared with posts)

    public record Usage(int posts, int videos) {
    }

    public Usage usage(long userId, LocalDate day) {
        return jdbc.query("SELECT posts, videos FROM user_daily_usage WHERE user_id = ? AND usage_date = ?",
                        (rs, i) -> new Usage(rs.getInt(1), rs.getInt(2)), userId, Date.valueOf(day))
                .stream().findFirst().orElse(new Usage(0, 0));
    }

    /** Today's counters, locked until the transaction ends (two reviews sent at once cannot both slip through). */
    public Usage lockUsage(long userId, LocalDate day) {
        jdbc.update("""
                INSERT INTO user_daily_usage (user_id, usage_date) VALUES (?, ?)
                ON DUPLICATE KEY UPDATE posts = posts""", userId, Date.valueOf(day));
        return jdbc.queryForObject(
                "SELECT posts, videos FROM user_daily_usage WHERE user_id = ? AND usage_date = ? FOR UPDATE",
                (rs, i) -> new Usage(rs.getInt(1), rs.getInt(2)), userId, Date.valueOf(day));
    }

    public void addUsage(long userId, LocalDate day, int posts, int videos) {
        jdbc.update("""
                INSERT INTO user_daily_usage (user_id, usage_date, posts, videos) VALUES (?, ?, ?, ?)
                ON DUPLICATE KEY UPDATE posts = posts + ?, videos = videos + ?""",
                userId, Date.valueOf(day), posts, videos, posts, videos);
    }

    // ---------------------------------------------------------------- reviews

    public long insert(long authorId, long placeId, int rating, String content, String status, Instant now) {
        KeyHolder key = new GeneratedKeyHolder();
        Timestamp at = Timestamp.from(now);
        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement("""
                    INSERT INTO reviews (author_id, place_id, rating, content, status, created_at, updated_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?)""", Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, authorId);
            ps.setLong(2, placeId);
            ps.setInt(3, rating);
            ps.setString(4, content);
            ps.setString(5, status);
            ps.setTimestamp(6, at);
            ps.setTimestamp(7, at);
            return ps;
        }, key);
        return key.getKey().longValue();
    }

    public record ReviewRow(long id, long authorId, Long placeId, int rating, String content, String status) {
    }

    /** @param lock {@code SELECT … FOR UPDATE}, for edits and deletes */
    public Optional<ReviewRow> review(long reviewId, boolean lock) {
        return jdbc.query("SELECT id, author_id, place_id, rating, content, status FROM reviews WHERE id = ?"
                        + (lock ? " FOR UPDATE" : ""),
                (rs, i) -> new ReviewRow(rs.getLong(1), rs.getLong(2), (Long) rs.getObject(3), rs.getInt(4),
                        rs.getString(5), rs.getString(6)),
                reviewId).stream().findFirst();
    }

    public void update(long reviewId, int rating, String content, String status, Instant now) {
        jdbc.update("""
                UPDATE reviews SET rating = ?, content = ?, status = ?, is_edited = TRUE, updated_at = ?
                WHERE id = ?""", rating, content, status, Timestamp.from(now), reviewId);
    }

    public void delete(long reviewId) {
        jdbc.update("DELETE FROM reviews WHERE id = ?", reviewId);
    }

    public boolean setStatus(long reviewId, String from, String to) {
        return jdbc.update("UPDATE reviews SET status = ?, updated_at = updated_at WHERE id = ? AND status = ?",
                to, reviewId, from) > 0;
    }

    public String status(long reviewId) {
        return jdbc.query("SELECT status FROM reviews WHERE id = ?", (rs, i) -> rs.getString(1), reviewId)
                .stream().findFirst().orElse(null);
    }

    /**
     * Place reviews still PROCESSING since before {@code before}, oldest first (picked up by the retry job). Event
     * reviews are left to {@code EventReviewPublishService}.
     */
    public List<Long> processingIds(Instant before, int limit) {
        return jdbc.query("""
                SELECT id FROM reviews WHERE status = 'PROCESSING' AND event_id IS NULL AND updated_at < ?
                ORDER BY updated_at LIMIT ?""",
                (rs, i) -> rs.getLong(1), Timestamp.from(before), limit);
    }

    public boolean awarded(long reviewId) {
        Integer n = jdbc.queryForObject("""
                SELECT COUNT(*) FROM point_transactions WHERE ref_type = 'REVIEW' AND ref_id = ? AND reason = 'REVIEW_CREATED'""",
                Integer.class, reviewId);
        return n != null && n > 0;
    }

    // ---------------------------------------------------------------- new pin (UC-U19)

    public List<ReviewDtos.PinCategory> categories() {
        return jdbc.query("SELECT id, name_vi, icon FROM place_categories ORDER BY sort_order, id",
                (rs, i) -> new ReviewDtos.PinCategory(rs.getString(1), rs.getString(2), rs.getString(3)));
    }

    public int pinsToday(long userId, LocalDate day) {
        Integer n = jdbc.query("SELECT pins FROM user_daily_usage WHERE user_id = ? AND usage_date = ?",
                (rs, i) -> rs.getInt(1), userId, Date.valueOf(day)).stream().findFirst().orElse(0);
        return n;
    }

    /** Today's pins, the usage row locked until the transaction ends. */
    public int lockPins(long userId, LocalDate day) {
        jdbc.update("""
                INSERT INTO user_daily_usage (user_id, usage_date) VALUES (?, ?)
                ON DUPLICATE KEY UPDATE pins = pins""", userId, Date.valueOf(day));
        return jdbc.queryForObject(
                "SELECT pins FROM user_daily_usage WHERE user_id = ? AND usage_date = ? FOR UPDATE",
                Integer.class, userId, Date.valueOf(day));
    }

    public void addPin(long userId, LocalDate day) {
        jdbc.update("""
                INSERT INTO user_daily_usage (user_id, usage_date, pins) VALUES (?, ?, 1)
                ON DUPLICATE KEY UPDATE pins = pins + 1""", userId, Date.valueOf(day));
    }

    public record NearPlace(long id, String nameVi, String nameJa, String nameEn, String categoryName, double lat,
                            double lng) {
    }

    /** Approved places in a box of about {@code metres} around the point (the caller measures the real distance). */
    public List<NearPlace> activeAround(double lat, double lng, double metres) {
        double dLat = metres / 111_320;
        double dLng = metres / (111_320 * Math.max(0.1, Math.cos(Math.toRadians(lat))));
        return jdbc.query("""
                SELECT p.id, p.name_vi, p.name_ja, p.name_en, c.name_vi, p.lat, p.lng
                FROM places p JOIN place_categories c ON c.id = p.category_id
                WHERE p.status = 'ACTIVE' AND p.lat BETWEEN ? AND ? AND p.lng BETWEEN ? AND ?""",
                (rs, i) -> new NearPlace(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4),
                        rs.getString(5), rs.getDouble(6), rs.getDouble(7)),
                lat - dLat, lat + dLat, lng - dLng, lng + dLng);
    }

    public boolean categoryExists(String id) {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM place_categories WHERE id = ?", Integer.class, id);
        return n != null && n > 0;
    }

    /** The community pin, PENDING until a Moderator approves it (only its author sees it meanwhile). */
    public long insertPin(long userId, String nameVi, String nameJa, String nameEn, String categoryId, String address,
                          double lat, double lng, Instant now) {
        KeyHolder key = new GeneratedKeyHolder();
        Timestamp at = Timestamp.from(now);
        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement("""
                    INSERT INTO places (name_vi, name_ja, name_en, category_id, address, lat, lng, source, status,
                                        created_by, created_at, updated_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, 'USER', 'PENDING', ?, ?, ?)""", Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, nameVi);
            ps.setString(2, nameJa);
            ps.setString(3, nameEn);
            ps.setString(4, categoryId);
            ps.setString(5, address);
            ps.setBigDecimal(6, java.math.BigDecimal.valueOf(lat).setScale(6, java.math.RoundingMode.HALF_UP));
            ps.setBigDecimal(7, java.math.BigDecimal.valueOf(lng).setScale(6, java.math.RoundingMode.HALF_UP));
            ps.setLong(8, userId);
            ps.setTimestamp(9, at);
            ps.setTimestamp(10, at);
            return ps;
        }, key);
        long id = key.getKey().longValue();
        com.sep490.vsa.feature.area.AreaService.assignPlace(jdbc, id);
        jdbc.update("""
                INSERT INTO place_history (place_id, action, actor_id, detail, created_at)
                VALUES (?, 'CREATED', ?, JSON_OBJECT('source', 'USER', 'via', 'PIN'), ?)""", id, userId, at);
        return id;
    }

    // ---------------------------------------------------------------- media

    public record AttachedMedia(long id, String type, String storageKey, String scanStatus) {
    }

    /** Photos and the video of the review that were not deleted, in order. */
    public List<AttachedMedia> media(long reviewId) {
        return jdbc.query("""
                SELECT m.id, m.media_type, m.storage_key, m.scan_status FROM review_media rm
                JOIN media m ON m.id = rm.media_id
                WHERE rm.review_id = ? AND m.deleted_at IS NULL ORDER BY rm.sort_order, m.id""",
                (rs, i) -> new AttachedMedia(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4)),
                reviewId);
    }

    public List<Long> allMediaIds(long reviewId) {
        return jdbc.query("SELECT media_id FROM review_media WHERE review_id = ?", (rs, i) -> rs.getLong(1), reviewId);
    }

    public void linkMedia(long reviewId, long mediaId, int order) {
        jdbc.update("INSERT INTO review_media (review_id, media_id, sort_order) VALUES (?, ?, ?)",
                reviewId, mediaId, order);
    }

    public void setMediaOrder(long reviewId, long mediaId, int order) {
        jdbc.update("UPDATE review_media SET sort_order = ? WHERE review_id = ? AND media_id = ?",
                order, reviewId, mediaId);
    }

    public void unlinkMedia(long reviewId, List<Long> mediaIds) {
        for (Long id : mediaIds) {
            jdbc.update("DELETE FROM review_media WHERE review_id = ? AND media_id = ?", reviewId, id);
        }
    }
}
