package com.sep490.vsa.feature.place;

import com.sep490.vsa.feature.place.MapDtos.MapCategory;
import com.sep490.vsa.feature.place.MapDtos.MapEvent;
import com.sep490.vsa.feature.place.MapDtos.MapPlace;
import com.sep490.vsa.feature.place.MapDtos.PhraseItem;
import com.sep490.vsa.feature.place.MapDtos.ReviewMedia;
import com.sep490.vsa.feature.place.MapDtos.ScamWarningRef;
import com.sep490.vsa.feature.place.MapDtos.Tag;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
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
 * Plain SQL for the Map: places with their review figures, place details, reviews, phrases of a category and event
 * pins. Reviews, phrases and events belong to other parts of the app; this class only reads them, except review
 * likes and saved places, which the Map owns.
 */
@Repository
public class MapRepository {

    /** "Cảnh báo lừa đảo": a published post with this topic linked to a place puts "!" on its pin. */
    static final String SCAM_WARNING_TOPIC = "Cảnh báo lừa đảo";

    private final JdbcTemplate jdbc;
    private final NamedParameterJdbcTemplate named;

    public MapRepository(JdbcTemplate jdbc, NamedParameterJdbcTemplate named) {
        this.jdbc = jdbc;
        this.named = named;
    }

    // ---------------------------------------------------------------- map

    public List<MapCategory> categories() {
        return jdbc.query("""
                SELECT c.id, c.name_vi, c.icon,
                       (SELECT COUNT(*) FROM places p WHERE p.category_id = c.id AND p.status = 'ACTIVE'
                          AND p.lat IS NOT NULL AND p.lng IS NOT NULL) AS n
                FROM place_categories c ORDER BY c.sort_order, c.id""",
                (rs, i) -> new MapCategory(rs.getString(1), rs.getString(2), rs.getString(3), rs.getInt(4)));
    }

    /**
     * Every approved place with a position, plus the viewer's own pins waiting for a Moderator. Places saved from
     * Google Maps before the switch to OpenStreetMap without a position cannot be drawn and are left out.
     */
    public List<MapPlace> places(Long viewerId) {
        Set<Long> scam = new HashSet<>(scamPlaceIds());
        return jdbc.query("""
                SELECT p.id, p.name_vi, p.name_ja, p.name_en, p.category_id, p.lat, p.lng, p.source, p.status,
                       p.is_closed, p.osm_id, COUNT(r.id) AS reviews, AVG(r.rating) AS rating
                FROM places p
                LEFT JOIN reviews r ON r.place_id = p.id AND r.status = 'PUBLISHED'
                WHERE p.lat IS NOT NULL AND p.lng IS NOT NULL
                  AND (p.status = 'ACTIVE' OR (p.status = 'PENDING' AND p.created_by = ?))
                GROUP BY p.id
                ORDER BY p.id""",
                (rs, i) -> new MapPlace(rs.getLong("id"), rs.getString("name_vi"), rs.getString("name_ja"),
                        rs.getString("name_en"), rs.getString("category_id"), rs.getDouble("lat"),
                        rs.getDouble("lng"), rs.getString("source"), rs.getString("status"),
                        rs.getBoolean("is_closed"), rs.getInt("reviews"), rating(rs.getBigDecimal("rating")),
                        scam.contains(rs.getLong("id")), rs.getString("osm_id")),
                viewerId == null ? -1L : viewerId);
    }

    /** Places (or the places they were merged into) linked by a published "Cảnh báo lừa đảo" post. */
    public List<Long> scamPlaceIds() {
        return jdbc.query("""
                SELECT DISTINCT shown.id
                FROM blogs b
                JOIN blog_topic_links btl ON btl.blog_id = b.id
                JOIN blog_topics t ON t.id = btl.topic_id AND t.name = ? AND t.is_active
                JOIN blog_place_links bpl ON bpl.blog_id = b.id
                JOIN places p ON p.id = bpl.place_id
                JOIN places shown ON shown.id = CASE WHEN p.status = 'MERGED' THEN p.merged_into_id ELSE p.id END
                WHERE b.status = 'PUBLISHED' AND b.blog_type = 'POST' AND shown.status = 'ACTIVE'""",
                (rs, i) -> rs.getLong(1), SCAM_WARNING_TOPIC);
    }

    public List<ScamWarningRef> scamWarnings(long placeId) {
        return jdbc.query("""
                SELECT DISTINCT b.id, b.title, b.created_at
                FROM blogs b
                JOIN blog_topic_links btl ON btl.blog_id = b.id
                JOIN blog_topics t ON t.id = btl.topic_id AND t.name = ? AND t.is_active
                JOIN blog_place_links bpl ON bpl.blog_id = b.id
                JOIN places p ON p.id = bpl.place_id
                WHERE b.status = 'PUBLISHED' AND b.blog_type = 'POST'
                  AND (p.id = ? OR (p.status = 'MERGED' AND p.merged_into_id = ?))
                ORDER BY b.created_at DESC, b.id DESC""",
                (rs, i) -> new ScamWarningRef(rs.getLong(1), rs.getString(2)), SCAM_WARNING_TOPIC, placeId, placeId);
    }

    public record AreaCenter(String name, Double lat, Double lng) {
    }

    /** The viewer's area and the middle of the approved places in it (areas have no position of their own). */
    public Optional<AreaCenter> areaCenter(long userId) {
        return jdbc.query("""
                SELECT a.display_name, AVG(p.lat), AVG(p.lng)
                FROM users u JOIN areas a ON a.id = u.area_id
                LEFT JOIN places p ON p.area_id = a.id AND p.status = 'ACTIVE' AND p.lat IS NOT NULL
                WHERE u.id = ? GROUP BY a.id, a.display_name""",
                (rs, i) -> new AreaCenter(rs.getString(1), decimal(rs.getBigDecimal(2)), decimal(rs.getBigDecimal(3))),
                userId).stream().findFirst();
    }

    // ---------------------------------------------------------------- place details

    public record PlaceRow(long id, String nameVi, String nameJa, String nameEn, String address, String osmId,
                           Double lat, Double lng, String categoryId, String categoryName, String categoryIcon,
                           String source, String status, boolean closed, Long mergedIntoId, long createdBy) {
    }

    public Optional<PlaceRow> place(long id) {
        return jdbc.query("""
                SELECT p.id, p.name_vi, p.name_ja, p.name_en, p.address, p.osm_id, p.lat, p.lng, p.category_id,
                       c.name_vi, c.icon, p.source, p.status, p.is_closed, p.merged_into_id, p.created_by
                FROM places p JOIN place_categories c ON c.id = p.category_id WHERE p.id = ?""",
                (rs, i) -> new PlaceRow(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4),
                        rs.getString(5), rs.getString(6), decimal(rs.getBigDecimal(7)), decimal(rs.getBigDecimal(8)),
                        rs.getString(9), rs.getString(10), rs.getString(11), rs.getString(12), rs.getString(13),
                        rs.getBoolean(14), (Long) rs.getObject(15), rs.getLong(16)),
                id).stream().findFirst();
    }

    public record ReviewFigures(int count, Double rating) {
    }

    public ReviewFigures reviewFigures(long placeId) {
        return jdbc.queryForObject("""
                SELECT COUNT(*), AVG(rating) FROM reviews WHERE place_id = ? AND status = 'PUBLISHED'""",
                (rs, i) -> new ReviewFigures(rs.getInt(1), rating(rs.getBigDecimal(2))), placeId);
    }

    /** "Đã đóng cửa" reports a Moderator has not handled yet. */
    public int closedReports(long placeId) {
        return count("SELECT COUNT(*) FROM place_closed_reports WHERE place_id = ? AND resolved_at IS NULL", placeId);
    }

    public boolean reportedClosed(long placeId, long userId) {
        return count("""
                SELECT COUNT(*) FROM place_closed_reports WHERE place_id = ? AND user_id = ? AND resolved_at IS NULL""",
                placeId, userId) > 0;
    }

    /** @return false when this user already reported the place (one report per user and place) */
    public boolean addClosedReport(long placeId, long userId, Instant now) {
        return jdbc.update("""
                INSERT IGNORE INTO place_closed_reports (place_id, user_id, created_at) VALUES (?, ?, ?)""",
                placeId, userId, Timestamp.from(now)) > 0;
    }

    public boolean saved(long placeId, long userId) {
        return count("SELECT COUNT(*) FROM saved_places WHERE place_id = ? AND user_id = ?", placeId, userId) > 0;
    }

    public Long reviewIdOf(long placeId, long userId) {
        return jdbc.query("""
                SELECT id FROM reviews WHERE place_id = ? AND author_id = ? AND status <> 'REMOVED'""",
                (rs, i) -> rs.getLong(1), placeId, userId).stream().findFirst().orElse(null);
    }

    public void setSaved(long placeId, long userId, boolean on, Instant now) {
        if (on) {
            jdbc.update("INSERT IGNORE INTO saved_places (user_id, place_id, saved_at) VALUES (?, ?, ?)",
                    userId, placeId, Timestamp.from(now));
        } else {
            jdbc.update("DELETE FROM saved_places WHERE user_id = ? AND place_id = ?", userId, placeId);
        }
    }

    // ---------------------------------------------------------------- reviews

    public record ReviewRow(long id, long authorId, String authorName, String avatarUrl, String role,
                            boolean authorDeleted, int points, int rating, String content, Instant createdAt,
                            boolean edited, String status, int likeCount) {
    }

    /** Published reviews of the place, newest first, plus the viewer's own review still being scanned. */
    public List<ReviewRow> reviews(long placeId, Long viewerId, int offset, int size) {
        return jdbc.query("""
                SELECT r.id, r.author_id, u.display_name, u.avatar_url, u.role, u.deleted_at IS NOT NULL, u.points,
                       r.rating, r.content, r.created_at, r.is_edited, r.status, r.like_count
                FROM reviews r JOIN users u ON u.id = r.author_id
                WHERE r.place_id = ? AND (r.status = 'PUBLISHED' OR (r.status = 'PROCESSING' AND r.author_id = ?))
                ORDER BY r.created_at DESC, r.id DESC
                LIMIT ? OFFSET ?""",
                (rs, i) -> new ReviewRow(rs.getLong(1), rs.getLong(2), rs.getString(3), rs.getString(4),
                        rs.getString(5), rs.getBoolean(6), rs.getInt(7), rs.getInt(8), rs.getString(9),
                        rs.getTimestamp(10).toInstant(), rs.getBoolean(11), rs.getString(12), rs.getInt(13)),
                placeId, viewerId == null ? -1L : viewerId, size, offset);
    }

    public long reviewCount(long placeId, Long viewerId) {
        Long n = jdbc.queryForObject("""
                SELECT COUNT(*) FROM reviews
                WHERE place_id = ? AND (status = 'PUBLISHED' OR (status = 'PROCESSING' AND author_id = ?))""",
                Long.class, placeId, viewerId == null ? -1L : viewerId);
        return n == null ? 0 : n;
    }

    /** Photos and videos of the reviews; media still being scanned only for its owner. */
    public Map<Long, List<ReviewMedia>> media(Collection<Long> reviewIds, Long viewerId, String publicBaseUrl) {
        Map<Long, List<ReviewMedia>> out = new HashMap<>();
        if (reviewIds.isEmpty()) {
            return out;
        }
        named.query("""
                SELECT rm.review_id, m.id, m.media_type, m.storage_key, m.width, m.height
                FROM review_media rm JOIN media m ON m.id = rm.media_id
                WHERE rm.review_id IN (:ids) AND m.deleted_at IS NULL
                  AND (m.scan_status = 'PASSED' OR (m.scan_status = 'PROCESSING' AND m.owner_id = :viewer))
                ORDER BY rm.review_id, rm.sort_order, m.id""",
                new MapSqlParameterSource("ids", reviewIds).addValue("viewer", viewerId == null ? -1L : viewerId),
                (ResultSet rs) -> {
                    out.computeIfAbsent(rs.getLong(1), k -> new ArrayList<>()).add(new ReviewMedia(rs.getLong(2),
                            rs.getString(3), publicBaseUrl + rs.getString(4), (Integer) rs.getObject(5),
                            (Integer) rs.getObject(6)));
                });
        return out;
    }

    public Map<Long, List<Tag>> tags(Collection<Long> userIds) {
        Map<Long, List<Tag>> out = new HashMap<>();
        if (userIds.isEmpty()) {
            return out;
        }
        named.query("""
                SELECT ut.user_id, t.name, t.color FROM user_tags ut JOIN tag_types t ON t.id = ut.tag_type_id
                WHERE ut.user_id IN (:ids) AND ut.revoked_at IS NULL AND t.is_active ORDER BY ut.granted_at""",
                Map.of("ids", userIds), (ResultSet rs) -> {
                    out.computeIfAbsent(rs.getLong(1), k -> new ArrayList<>())
                            .add(new Tag(rs.getString(2), rs.getString(3)));
                });
        return out;
    }

    public record RankRow(String name, int minPoints) {
    }

    /** Ranks from the highest threshold down. */
    public List<RankRow> ranks() {
        return jdbc.query("SELECT name, min_points FROM ranks ORDER BY min_points DESC",
                (rs, i) -> new RankRow(rs.getString(1), rs.getInt(2)));
    }

    public Set<Long> likedReviews(Collection<Long> reviewIds, long userId) {
        if (reviewIds.isEmpty()) {
            return Set.of();
        }
        return new HashSet<>(named.query("""
                SELECT review_id FROM review_likes WHERE review_id IN (:ids) AND user_id = :user""",
                new MapSqlParameterSource("ids", reviewIds).addValue("user", userId), (rs, i) -> rs.getLong(1)));
    }

    public record ReviewTarget(long id, long authorId, String status, Long placeId, String placeStatus) {
    }

    public Optional<ReviewTarget> reviewTarget(long reviewId) {
        return jdbc.query("""
                SELECT r.id, r.author_id, r.status, r.place_id, p.status
                FROM reviews r LEFT JOIN places p ON p.id = r.place_id WHERE r.id = ?""",
                (rs, i) -> new ReviewTarget(rs.getLong(1), rs.getLong(2), rs.getString(3), (Long) rs.getObject(4),
                        rs.getString(5)),
                reviewId).stream().findFirst();
    }

    public int setReviewLike(long reviewId, long userId, boolean on, Instant now) {
        if (on) {
            jdbc.update("INSERT IGNORE INTO review_likes (review_id, user_id, created_at) VALUES (?, ?, ?)",
                    reviewId, userId, Timestamp.from(now));
        } else {
            jdbc.update("DELETE FROM review_likes WHERE review_id = ? AND user_id = ?", reviewId, userId);
        }
        // Recount instead of +1/-1 so the cached number can never drift
        jdbc.update("""
                UPDATE reviews SET like_count = (SELECT COUNT(*) FROM review_likes WHERE review_id = ?),
                                   updated_at = updated_at
                WHERE id = ?""", reviewId, reviewId);
        return count("SELECT like_count FROM reviews WHERE id = ?", reviewId);
    }

    /** Hides a review that went over the report threshold until a Moderator decides. */
    public boolean hideReview(long reviewId) {
        return jdbc.update("UPDATE reviews SET status = 'HIDDEN', updated_at = updated_at "
                + "WHERE id = ? AND status = 'PUBLISHED'", reviewId) > 0;
    }

    public record Member(String role, String status) {
    }

    public Optional<Member> member(long userId) {
        return jdbc.query("SELECT role, status FROM users WHERE id = ? AND deleted_at IS NULL",
                (rs, i) -> new Member(rs.getString(1), rs.getString(2)), userId).stream().findFirst();
    }

    // ---------------------------------------------------------------- phrases and events

    public List<PhraseItem> phrases(String categoryId) {
        return jdbc.query("""
                SELECT id, kind, japanese, romaji, meaning_vi FROM phrases
                WHERE category_id = ? AND deleted_at IS NULL ORDER BY kind = 'VOCAB', id""",
                (rs, i) -> new PhraseItem(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4),
                        rs.getString(5)),
                categoryId);
    }

    /** Events with a position that are on now or start within {@code days} days (Japan dates). */
    public List<MapEvent> events(LocalDate today, int days) {
        return jdbc.query("""
                SELECT id, title, start_date, end_date, location_name, lat, lng FROM events
                WHERE deleted_at IS NULL AND lat IS NOT NULL AND lng IS NOT NULL
                  AND end_date >= ? AND start_date <= ?
                ORDER BY start_date, id""",
                (rs, i) -> {
                    LocalDate start = rs.getDate(3).toLocalDate();
                    return new MapEvent(rs.getLong(1), rs.getString(2), start, rs.getDate(4).toLocalDate(),
                            rs.getString(5), rs.getDouble(6), rs.getDouble(7),
                            Math.max(0, start.toEpochDay() - today.toEpochDay()));
                },
                Date.valueOf(today), Date.valueOf(today.plusDays(days)));
    }

    // ---------------------------------------------------------------- helpers

    private static Double rating(BigDecimal avg) {
        return avg == null ? null : avg.setScale(1, RoundingMode.HALF_UP).doubleValue();
    }

    private static Double decimal(BigDecimal value) {
        return value == null ? null : value.doubleValue();
    }

    private int count(String sql, Object... args) {
        Integer n = jdbc.queryForObject(sql, Integer.class, args);
        return n == null ? 0 : n;
    }
}
