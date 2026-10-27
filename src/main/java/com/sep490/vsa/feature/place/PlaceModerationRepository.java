package com.sep490.vsa.feature.place;

import com.sep490.vsa.feature.blog.ActiveAuthors;
import com.sep490.vsa.feature.place.PlaceModerationDtos.AreaRef;
import com.sep490.vsa.feature.place.PlaceModerationDtos.PrefectureRef;
import com.sep490.vsa.feature.place.PlaceModerationDtos.CategoryRef;
import com.sep490.vsa.feature.place.PlaceModerationDtos.ClosedRow;
import com.sep490.vsa.feature.place.PlaceModerationDtos.PlaceRow;
import com.sep490.vsa.feature.place.PlaceModerationDtos.Reason;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Plain SQL for the Moderator's place work: pins waiting for approval, "đã đóng cửa" reports, place history.
 * Every change of a place is written with one {@code place_history} row ({@link #history}).
 */
@Repository
public class PlaceModerationRepository {

    private final JdbcTemplate jdbc;

    public PlaceModerationRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<CategoryRef> categories() {
        return jdbc.query("SELECT id, name_vi, icon FROM place_categories ORDER BY sort_order, id",
                (rs, i) -> new CategoryRef(rs.getString(1), rs.getString(2), rs.getString(3)));
    }

    public Optional<CategoryRef> category(String id) {
        return jdbc.query("SELECT id, name_vi, icon FROM place_categories WHERE id = ?",
                (rs, i) -> new CategoryRef(rs.getString(1), rs.getString(2), rs.getString(3)), id).stream().findFirst();
    }

    public List<Reason> reasons(String scope) {
        return jdbc.query("""
                SELECT id, label FROM reason_options WHERE scope = ? AND is_active ORDER BY sort_order, id""",
                (rs, i) -> new Reason(rs.getLong(1), rs.getString(2)), scope);
    }

    public Optional<String> reasonLabel(long id, String scope) {
        return jdbc.query("SELECT label FROM reason_options WHERE id = ? AND scope = ? AND is_active",
                (rs, i) -> rs.getString(1), id, scope).stream().findFirst();
    }

    // ---------------------------------------------------------------- places

    /** A place as the Moderator's actions need it. */
    public record PlaceState(long id, String nameVi, String nameJa, String nameEn, String address, String categoryId,
                             Double lat, Double lng, String source, String status, boolean closed, long createdBy,
                             String authorName, Instant createdAt) {
    }

    private static final String PLACE_COLUMNS = """
            p.id, p.name_vi, p.name_ja, p.name_en, p.address, p.category_id, p.lat, p.lng, p.source, p.status,
            p.is_closed, p.created_by, u.display_name, p.created_at
            """;

    private static PlaceState placeState(ResultSet rs) throws SQLException {
        return new PlaceState(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5),
                rs.getString(6), decimal(rs, 7), decimal(rs, 8), rs.getString(9), rs.getString(10), rs.getBoolean(11),
                rs.getLong(12), rs.getString(13), rs.getTimestamp(14).toInstant());
    }

    /** @param lock {@code FOR UPDATE}: two Moderators handling the same place wait for each other */
    public Optional<PlaceState> place(long id, boolean lock) {
        return jdbc.query("SELECT " + PLACE_COLUMNS + """
                 FROM places p JOIN users u ON u.id = p.created_by WHERE p.id = ?""" + (lock ? " FOR UPDATE" : ""),
                (rs, i) -> placeState(rs), id).stream().findFirst();
    }

    /** Community pins waiting for a Moderator, oldest first. */
    public List<PlaceState> pendingPins() {
        return jdbc.query("SELECT " + PLACE_COLUMNS + """
                 FROM places p JOIN users u ON u.id = p.created_by
                WHERE p.status = 'PENDING' AND p.source = 'USER' AND p.lat IS NOT NULL
                ORDER BY p.created_at, p.id""", (rs, i) -> placeState(rs));
    }

    public record Around(long id, String nameVi, String nameJa, String nameEn, String categoryName, double lat,
                         double lng, int reviewCount) {
    }

    /**
     * Approved places in a box of about {@code metres} around the point, with their published reviews of active
     * accounts (the caller measures the real distance).
     */
    public List<Around> activeAround(double lat, double lng, double metres, long exceptId) {
        double dLat = metres / 111_320;
        double dLng = metres / (111_320 * Math.max(0.1, Math.cos(Math.toRadians(lat))));
        return jdbc.query("""
                SELECT p.id, p.name_vi, p.name_ja, p.name_en, c.name_vi, p.lat, p.lng,
                       (SELECT COUNT(*) FROM reviews r WHERE r.place_id = p.id AND r.status = 'PUBLISHED' AND %s)
                FROM places p JOIN place_categories c ON c.id = p.category_id
                WHERE p.status = 'ACTIVE' AND p.id <> ? AND p.lat BETWEEN ? AND ? AND p.lng BETWEEN ? AND ?"""
                        .formatted(ActiveAuthors.of("r")),
                (rs, i) -> new Around(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4),
                        rs.getString(5), rs.getDouble(6), rs.getDouble(7), rs.getInt(8)),
                exceptId, lat - dLat, lat + dLat, lng - dLng, lng + dLng);
    }

    public void history(long placeId, String action, long actorId, String detailJson, Instant now) {
        jdbc.update("""
                INSERT INTO place_history (place_id, action, actor_id, detail, created_at)
                VALUES (?, ?, ?, ?, ?)""", placeId, action, actorId, detailJson, Timestamp.from(now));
    }

    /** PENDING → ACTIVE with the corrected name and category. False when another Moderator was faster. */
    public boolean approve(long placeId, String nameVi, String categoryId, long moderatorId, Instant now) {
        return jdbc.update("""
                UPDATE places SET status = 'ACTIVE', name_vi = ?, category_id = ?, reviewed_by = ?, reviewed_at = ?,
                                  updated_at = ?
                WHERE id = ? AND status = 'PENDING'""",
                nameVi, categoryId, moderatorId, Timestamp.from(now), Timestamp.from(now), placeId) > 0;
    }

    public boolean reject(long placeId, String reason, long moderatorId, Instant now) {
        return jdbc.update("""
                UPDATE places SET status = 'REJECTED', reject_reason = ?, reviewed_by = ?, reviewed_at = ?, updated_at = ?
                WHERE id = ? AND status = 'PENDING'""",
                reason, moderatorId, Timestamp.from(now), Timestamp.from(now), placeId) > 0;
    }

    /** The pin becomes MERGED into {@code targetId} (old links to it open the target). */
    public boolean mergePin(long placeId, long targetId, long moderatorId, Instant now) {
        return jdbc.update("""
                UPDATE places SET status = 'MERGED', merged_into_id = ?, reviewed_by = ?, reviewed_at = ?, updated_at = ?
                WHERE id = ? AND status = 'PENDING'""",
                targetId, moderatorId, Timestamp.from(now), Timestamp.from(now), placeId) > 0;
    }

    public void insertMerge(long keptId, long mergedId, String fieldChoicesJson, int movedReviews, long by,
                            Instant now) {
        jdbc.update("""
                INSERT INTO place_merges (kept_place_id, merged_place_id, field_choices, moved_reviews, merged_by,
                                          merged_at)
                VALUES (?, ?, ?, ?, ?, ?)""", keptId, mergedId, fieldChoicesJson, movedReviews, by, Timestamp.from(now));
    }

    // ---------------------------------------------------------------- reviews of a place

    public record ReviewRef(long id, long authorId, int rating, String content, String status) {
    }

    /** The review this author wrote about the place (a pin has exactly one: its first review). */
    public Optional<ReviewRef> reviewOf(long placeId, long authorId) {
        return jdbc.query("""
                SELECT id, author_id, rating, content, status FROM reviews WHERE place_id = ? AND author_id = ?""",
                (rs, i) -> new ReviewRef(rs.getLong(1), rs.getLong(2), rs.getInt(3), rs.getString(4), rs.getString(5)),
                placeId, authorId).stream().findFirst();
    }

    public boolean hasReview(long placeId, long authorId) {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM reviews WHERE place_id = ? AND author_id = ?",
                Integer.class, placeId, authorId);
        return n != null && n > 0;
    }

    public void moveReview(long reviewId, long placeId) {
        jdbc.update("UPDATE reviews SET place_id = ?, updated_at = updated_at WHERE id = ?", placeId, reviewId);
    }

    /** Saves of {@code fromId} move to {@code toId}; a user who saved both keeps one. */
    public void moveSaves(long fromId, long toId) {
        jdbc.update("""
                INSERT IGNORE INTO saved_places (user_id, place_id, saved_at)
                SELECT user_id, ?, saved_at FROM saved_places WHERE place_id = ?""", toId, fromId);
        jdbc.update("DELETE FROM saved_places WHERE place_id = ?", fromId);
    }

    public List<Long> reviewMediaIds(long reviewId) {
        return jdbc.queryForList("SELECT media_id FROM review_media WHERE review_id = ?", Long.class, reviewId);
    }

    /** Hard delete (its media links, likes and report rows go with it through the foreign keys). */
    public void deleteReview(long reviewId) {
        jdbc.update("DELETE FROM reviews WHERE id = ?", reviewId);
    }

    public boolean mediaScanning(long reviewId) {
        Integer n = jdbc.queryForObject("""
                SELECT COUNT(*) FROM review_media rm JOIN media m ON m.id = rm.media_id
                WHERE rm.review_id = ? AND m.deleted_at IS NULL AND m.scan_status = 'PROCESSING'""",
                Integer.class, reviewId);
        return n != null && n > 0;
    }

    // ---------------------------------------------------------------- "đã đóng cửa"

    private static final String CLOSED_COLUMNS = """
            p.id, p.name_vi, c.id, c.name_vi, c.icon,
            (SELECT COUNT(*) FROM place_closed_reports cr WHERE cr.place_id = p.id AND cr.resolved_at IS NULL) AS n,
            p.address,
            (SELECT MAX(r.created_at) FROM reviews r WHERE r.place_id = p.id AND r.status = 'PUBLISHED' AND %s),
            p.closed_at, p.osm_id, p.lat, p.lng
            """.formatted(ActiveAuthors.of("r"));

    private static ClosedRow closedRow(ResultSet rs) throws SQLException {
        Timestamp last = rs.getTimestamp(8);
        Timestamp closedAt = rs.getTimestamp(9);
        return new ClosedRow(rs.getLong(1), rs.getString(2),
                new CategoryRef(rs.getString(3), rs.getString(4), rs.getString(5)), rs.getInt(6), rs.getString(7),
                last == null ? null : last.toInstant(), closedAt == null ? null : closedAt.toInstant(),
                rs.getString(10), decimal(rs, 11), decimal(rs, 12));
    }

    /** Open places with at least {@code threshold} reports not handled yet, most reports first. */
    public List<ClosedRow> waitingClosed(int threshold) {
        return jdbc.query("SELECT " + CLOSED_COLUMNS + """
                 FROM places p JOIN place_categories c ON c.id = p.category_id
                WHERE p.status = 'ACTIVE' AND NOT p.is_closed
                HAVING n >= ?
                ORDER BY n DESC, p.id""", (rs, i) -> closedRow(rs), threshold);
    }

    /** Places with the "đã đóng cửa" label, labelled last first. */
    public List<ClosedRow> closedPlaces() {
        return jdbc.query("SELECT " + CLOSED_COLUMNS + """
                 FROM places p JOIN place_categories c ON c.id = p.category_id
                WHERE p.status = 'ACTIVE' AND p.is_closed
                ORDER BY p.closed_at DESC, p.id DESC""", (rs, i) -> closedRow(rs));
    }

    public int openClosedReports(long placeId) {
        Integer n = jdbc.queryForObject("""
                SELECT COUNT(*) FROM place_closed_reports WHERE place_id = ? AND resolved_at IS NULL""",
                Integer.class, placeId);
        return n == null ? 0 : n;
    }

    public void setClosed(long placeId, boolean closed, Instant now) {
        jdbc.update("UPDATE places SET is_closed = ?, closed_at = ?, updated_at = ? WHERE id = ?",
                closed, closed ? Timestamp.from(now) : null, Timestamp.from(now), placeId);
    }

    /** The reports were right: kept as handled, so the same people do not report the closed place again. */
    public void resolveClosedReports(long placeId, Instant now) {
        jdbc.update("UPDATE place_closed_reports SET resolved_at = ? WHERE place_id = ? AND resolved_at IS NULL",
                Timestamp.from(now), placeId);
    }

    /** The place is open: the reports go, and anyone may report it again later. */
    public int deleteClosedReports(long placeId) {
        return jdbc.update("DELETE FROM place_closed_reports WHERE place_id = ?", placeId);
    }

    // ---------------------------------------------------------------- "Địa điểm" (UC-M07, UC-M08)

    /** The prefectures, for the list filter: "Tokyo · 東京都". */
    public List<PrefectureRef> prefectures() {
        return jdbc.query("SELECT code, CONCAT(name_en, ' · ', name_ja) FROM prefectures ORDER BY code",
                (rs, i) -> new PrefectureRef(rs.getString(1), rs.getString(2)));
    }

    public Optional<AreaRef> area(long id) {
        return jdbc.query("SELECT id, display_name FROM areas WHERE id = ?",
                (rs, i) -> new AreaRef(rs.getLong(1), rs.getString(2)), id).stream().findFirst();
    }

    /** Filters of the places list; null or blank = any. {@code q} matches the three names, accents ignored. */
    public record PlaceFilter(String q, String categoryId, String source, String status, String prefectureCode) {
    }

    private static String where(PlaceFilter f, List<Object> args) {
        StringBuilder sql = new StringBuilder(" WHERE 1 = 1");
        if (f.q() != null && !f.q().isBlank()) {
            String like = "%" + PlaceRepository.escapeLike(f.q().strip()) + "%";
            sql.append(" AND (p.name_vi LIKE ? OR p.name_ja LIKE ? OR p.name_en LIKE ? OR p.address LIKE ?)");
            args.addAll(List.of(like, like, like, like));
        }
        if (f.categoryId() != null && !f.categoryId().isBlank()) {
            sql.append(" AND p.category_id = ?");
            args.add(f.categoryId());
        }
        if (f.source() != null && !f.source().isBlank()) {
            // "Nhóm Senpai ơi thêm" covers both TEAM (sample data) and MODERATOR
            sql.append("TEAM".equals(f.source()) || "MODERATOR".equals(f.source())
                    ? " AND p.source IN ('TEAM', 'MODERATOR')" : " AND p.source = ?");
            if (!"TEAM".equals(f.source()) && !"MODERATOR".equals(f.source())) {
                args.add(f.source());
            }
        }
        if (f.status() != null && !f.status().isBlank()) {
            sql.append(" AND p.status = ?");
            args.add(f.status());
        }
        if (f.prefectureCode() != null && !f.prefectureCode().isBlank()) {
            sql.append(" AND p.area_id IN (SELECT id FROM areas WHERE prefecture_code = ?)");
            args.add(f.prefectureCode());
        }
        return sql.toString();
    }

    /** Newest first. */
    public List<PlaceRow> places(PlaceFilter filter, int offset, int size) {
        List<Object> args = new ArrayList<>();
        String sql = """
                SELECT p.id, p.name_vi, p.name_ja, c.id, c.name_vi, c.icon, p.source, p.status, p.is_closed,
                       a.display_name,
                       (SELECT COUNT(*) FROM reviews r WHERE r.place_id = p.id AND r.status = 'PUBLISHED' AND %s),
                       p.created_at
                FROM places p JOIN place_categories c ON c.id = p.category_id
                LEFT JOIN areas a ON a.id = p.area_id
                """.formatted(ActiveAuthors.of("r")) + where(filter, args) + " ORDER BY p.created_at DESC, p.id DESC LIMIT ? OFFSET ?";
        args.add(size);
        args.add(offset);
        return jdbc.query(sql, (rs, i) -> new PlaceRow(rs.getLong(1), rs.getString(2), rs.getString(3),
                new CategoryRef(rs.getString(4), rs.getString(5), rs.getString(6)), rs.getString(7), rs.getString(8),
                rs.getBoolean(9), rs.getString(10), rs.getInt(11), rs.getTimestamp(12).toInstant()), args.toArray());
    }

    public long countPlaces(PlaceFilter filter) {
        List<Object> args = new ArrayList<>();
        Long n = jdbc.queryForObject("SELECT COUNT(*) FROM places p" + where(filter, args), Long.class, args.toArray());
        return n == null ? 0 : n;
    }

    /** The columns of a place's details that {@link PlaceState} does not have. */
    public record PlaceExtra(String osmId, Long areaId, String areaName, Instant closedAt, String removedReason,
                             String rejectReason, Long mergedIntoId, String mergedIntoName, int reviewCount,
                             int saveCount) {
    }

    public Optional<PlaceExtra> extra(long id) {
        return jdbc.query("""
                SELECT p.osm_id, p.area_id, a.display_name, p.closed_at, p.removed_reason, p.reject_reason,
                       p.merged_into_id, m.name_vi,
                       (SELECT COUNT(*) FROM reviews r WHERE r.place_id = p.id AND r.status = 'PUBLISHED' AND %s),
                       (SELECT COUNT(*) FROM saved_places s WHERE s.place_id = p.id)
                FROM places p LEFT JOIN areas a ON a.id = p.area_id LEFT JOIN places m ON m.id = p.merged_into_id
                WHERE p.id = ?""".formatted(ActiveAuthors.of("r")),
                (rs, i) -> {
                    Timestamp closedAt = rs.getTimestamp(4);
                    return new PlaceExtra(rs.getString(1), (Long) rs.getObject(2, Long.class), rs.getString(3),
                            closedAt == null ? null : closedAt.toInstant(), rs.getString(5), rs.getString(6),
                            (Long) rs.getObject(7, Long.class), rs.getString(8), rs.getInt(9), rs.getInt(10));
                }, id).stream().findFirst();
    }

    public record HistoryRow(String action, String actorName, Instant at, String detail) {
    }

    /** Newest first. */
    public List<HistoryRow> history(long placeId) {
        return jdbc.query("""
                SELECT h.action, u.display_name, h.created_at, h.detail
                FROM place_history h JOIN users u ON u.id = h.actor_id
                WHERE h.place_id = ? ORDER BY h.created_at DESC, h.id DESC""",
                (rs, i) -> new HistoryRow(rs.getString(1), rs.getString(2), rs.getTimestamp(3).toInstant(),
                        rs.getString(4)), placeId);
    }

    public Optional<Long> placeIdOfOsm(String osmId) {
        return jdbc.query("SELECT id FROM places WHERE osm_id = ?", (rs, i) -> rs.getLong(1), osmId)
                .stream().findFirst();
    }

    /** Values of "Thêm địa điểm" / "Sửa thông tin" after the checks (names stripped, empty = null). */
    public record PlaceValues(String nameVi, String nameJa, String nameEn, String address, String categoryId,
                              Long areaId, double lat, double lng) {
    }

    /**
     * A place a Moderator adds: ACTIVE at once (no approval, no first review), source OSM (with its id) or MODERATOR
     * ("Nhóm Senpai ơi thêm").
     */
    public long insertPlace(PlaceValues v, String osmId, long moderatorId, Instant now) {
        org.springframework.jdbc.support.KeyHolder key = new org.springframework.jdbc.support.GeneratedKeyHolder();
        Timestamp at = Timestamp.from(now);
        jdbc.update(connection -> {
            java.sql.PreparedStatement ps = connection.prepareStatement("""
                    INSERT INTO places (osm_id, name_vi, name_ja, name_en, address, category_id, area_id, lat, lng,
                                        source, status, created_by, reviewed_by, reviewed_at, created_at, updated_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'ACTIVE', ?, ?, ?, ?, ?)""",
                    java.sql.Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, osmId);
            ps.setString(2, v.nameVi());
            ps.setString(3, v.nameJa());
            ps.setString(4, v.nameEn());
            ps.setString(5, v.address());
            ps.setString(6, v.categoryId());
            ps.setObject(7, v.areaId());
            ps.setBigDecimal(8, coordinate(v.lat()));
            ps.setBigDecimal(9, coordinate(v.lng()));
            ps.setString(10, osmId == null ? "MODERATOR" : "OSM");
            ps.setLong(11, moderatorId);
            ps.setLong(12, moderatorId);
            ps.setTimestamp(13, at);
            ps.setTimestamp(14, at);
            ps.setTimestamp(15, at);
            return ps;
        }, key);
        return key.getKey().longValue();
    }

    public void updatePlace(long id, PlaceValues v, Instant now) {
        jdbc.update("""
                UPDATE places SET name_vi = ?, name_ja = ?, name_en = ?, address = ?, category_id = ?, area_id = ?,
                                  lat = ?, lng = ?, updated_at = ?
                WHERE id = ?""", v.nameVi(), v.nameJa(), v.nameEn(), v.address(), v.categoryId(), v.areaId(),
                coordinate(v.lat()), coordinate(v.lng()), Timestamp.from(now), id);
    }

    /** ACTIVE → REMOVED (soft: rows, reviews and links stay). False when it is not ACTIVE any more. */
    public boolean remove(long id, String reason, Instant now) {
        return jdbc.update("""
                UPDATE places SET status = 'REMOVED', removed_reason = ?, updated_at = ? WHERE id = ? AND status = 'ACTIVE'""",
                reason, Timestamp.from(now), id) > 0;
    }

    public boolean restore(long id, Instant now) {
        return jdbc.update("""
                UPDATE places SET status = 'ACTIVE', removed_reason = NULL, updated_at = ? WHERE id = ? AND status = 'REMOVED'""",
                Timestamp.from(now), id) > 0;
    }

    private static java.math.BigDecimal coordinate(double value) {
        return java.math.BigDecimal.valueOf(value).setScale(6, java.math.RoundingMode.HALF_UP);
    }

    private static Double decimal(ResultSet rs, int column) throws SQLException {
        java.math.BigDecimal value = rs.getBigDecimal(column);
        return value == null ? null : value.doubleValue();
    }
}
