package com.sep490.vsa.feature.place;

import com.sep490.vsa.feature.blog.ActiveAuthors;
import com.sep490.vsa.feature.place.PlaceModerationDtos.MergeCounts;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Plain SQL of UC-M09 "Gộp trùng lặp": the places the queue compares, the pairs marked "Không trùng", and the moves of
 * a merge (reviews, saves, post links, guide items, "đã đóng cửa" reports, older merges) to the place that is kept.
 */
@Repository
public class PlaceMergeRepository {

    private final JdbcTemplate jdbc;

    public PlaceMergeRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record Candidate(long id, String nameVi, String nameJa, String nameEn, String categoryId, double lat,
                            double lng, String source, int reviewCount) {
    }

    /** Every approved place with a position, with its published reviews of active accounts. */
    public List<Candidate> activePlaces() {
        return jdbc.query("""
                SELECT p.id, p.name_vi, p.name_ja, p.name_en, p.category_id, p.lat, p.lng, p.source,
                       (SELECT COUNT(*) FROM reviews r WHERE r.place_id = p.id AND r.status = 'PUBLISHED' AND %s)
                FROM places p WHERE p.status = 'ACTIVE' AND p.lat IS NOT NULL AND p.lng IS NOT NULL
                ORDER BY p.id""".formatted(ActiveAuthors.of("r")),
                (rs, i) -> new Candidate(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4),
                        rs.getString(5), rs.getDouble(6), rs.getDouble(7), rs.getString(8), rs.getInt(9)));
    }

    /** "a:b" keys (a &lt; b) of the pairs marked "Không trùng". */
    public Set<String> dismissed() {
        return new HashSet<>(jdbc.query("SELECT CONCAT(place_a_id, ':', place_b_id) FROM duplicate_dismissals",
                (rs, i) -> rs.getString(1)));
    }

    /** @return false when the pair was marked already */
    public boolean dismiss(long a, long b, long moderatorId, Instant now) {
        return jdbc.update("""
                INSERT IGNORE INTO duplicate_dismissals (place_a_id, place_b_id, dismissed_by, dismissed_at)
                VALUES (?, ?, ?, ?)""", Math.min(a, b), Math.max(a, b), moderatorId, Timestamp.from(now)) > 0;
    }

    public MergeCounts counts(long placeId) {
        return jdbc.queryForObject("""
                SELECT (SELECT COUNT(*) FROM reviews r WHERE r.place_id = ? AND r.status = 'PUBLISHED' AND %s),
                       (SELECT COUNT(*) FROM saved_places WHERE place_id = ?),
                       (SELECT COUNT(*) FROM blog_place_links WHERE place_id = ?),
                       (SELECT COUNT(*) FROM guide_items WHERE place_id = ?)""".formatted(ActiveAuthors.of("r")),
                (rs, i) -> new MergeCounts(rs.getInt(1), rs.getInt(2), rs.getInt(3), rs.getInt(4)),
                placeId, placeId, placeId, placeId);
    }

    /** People with a review (any status) on both places. */
    public int sameAuthors(long a, long b) {
        Integer n = jdbc.queryForObject("""
                SELECT COUNT(*) FROM reviews x JOIN reviews y ON y.author_id = x.author_id
                WHERE x.place_id = ? AND y.place_id = ?""", Integer.class, a, b);
        return n == null ? 0 : n;
    }

    public record PickRow(long id, String name, String categoryName, String address, Double lat, Double lng) {
    }

    /**
     * Approved places where every typed word is in one of the names or the address (accents ignored), 20 at most:
     * "tabata ga" finds "Ga Tabata" and "Tabata Station".
     */
    public List<PickRow> search(String q, long exceptId) {
        StringBuilder sql = new StringBuilder("""
                SELECT p.id, p.name_vi, c.name_vi, p.address, p.lat, p.lng
                FROM places p JOIN place_categories c ON c.id = p.category_id
                WHERE p.status = 'ACTIVE' AND p.id <> ?""");
        List<Object> args = new java.util.ArrayList<>(List.of(exceptId));
        for (String word : q.strip().split("\\s+")) {
            String like = "%" + PlaceRepository.escapeLike(word) + "%";
            sql.append(" AND (p.name_vi LIKE ? OR p.name_ja LIKE ? OR p.name_en LIKE ? OR p.address LIKE ?)");
            args.addAll(List.of(like, like, like, like));
        }
        sql.append(" ORDER BY p.name_vi LIMIT 20");
        return jdbc.query(sql.toString(), (rs, i) -> new PickRow(rs.getLong(1), rs.getString(2), rs.getString(3),
                rs.getString(4), decimal(rs.getBigDecimal(5)), decimal(rs.getBigDecimal(6))), args.toArray());
    }

    // ---------------------------------------------------------------- the merge itself

    /** Locks both rows, lower id first, so two merges touching the same places wait instead of deadlocking. */
    public void lockPair(long a, long b) {
        jdbc.queryForList("SELECT id FROM places WHERE id IN (?, ?) ORDER BY id FOR UPDATE", Long.class,
                Math.min(a, b), Math.max(a, b));
    }

    public record Twin(long keptReview, long mergedReview, boolean mergedIsNewer) {
    }

    /** Reviews of one author on both places (reviews are unique per author and place). */
    public List<Twin> twins(long keptId, long mergedId) {
        return jdbc.query("""
                SELECT k.id, m.id, m.created_at > k.created_at
                FROM reviews k JOIN reviews m ON m.author_id = k.author_id
                WHERE k.place_id = ? AND m.place_id = ?""",
                (rs, i) -> new Twin(rs.getLong(1), rs.getLong(2), rs.getBoolean(3)), keptId, mergedId);
    }

    public List<Long> reviewMediaIds(long reviewId) {
        return jdbc.queryForList("SELECT media_id FROM review_media WHERE review_id = ?", Long.class, reviewId);
    }

    public void deleteReview(long reviewId) {
        jdbc.update("DELETE FROM reviews WHERE id = ?", reviewId);
    }

    /** @return how many reviews moved */
    public int moveReviews(long fromId, long toId) {
        return jdbc.update("UPDATE reviews SET place_id = ?, updated_at = updated_at WHERE place_id = ?", toId, fromId);
    }

    public void moveSaves(long fromId, long toId) {
        jdbc.update("""
                INSERT IGNORE INTO saved_places (user_id, place_id, saved_at)
                SELECT user_id, ?, saved_at FROM saved_places WHERE place_id = ?""", toId, fromId);
        jdbc.update("DELETE FROM saved_places WHERE place_id = ?", fromId);
    }

    public void movePostLinks(long fromId, long toId) {
        jdbc.update("""
                INSERT IGNORE INTO blog_place_links (blog_id, place_id)
                SELECT blog_id, ? FROM blog_place_links WHERE place_id = ?""", toId, fromId);
        jdbc.update("DELETE FROM blog_place_links WHERE place_id = ?", fromId);
    }

    public void moveGuideItems(long fromId, long toId) {
        jdbc.update("UPDATE guide_items SET place_id = ? WHERE place_id = ?", toId, fromId);
    }

    /** Open "đã đóng cửa" reports follow the place (one per user); handled ones stay with the merged place. */
    public void moveClosedReports(long fromId, long toId) {
        jdbc.update("""
                INSERT IGNORE INTO place_closed_reports (place_id, user_id, created_at)
                SELECT ?, user_id, created_at FROM place_closed_reports WHERE place_id = ? AND resolved_at IS NULL""",
                toId, fromId);
        jdbc.update("DELETE FROM place_closed_reports WHERE place_id = ? AND resolved_at IS NULL", fromId);
    }

    /** Places merged into the merged place before now point at the kept place (links follow one step only). */
    public void repointMerged(long fromId, long toId) {
        jdbc.update("UPDATE places SET merged_into_id = ? WHERE status = 'MERGED' AND merged_into_id = ?", toId, fromId);
    }

    public boolean markMerged(long id, long intoId, Instant now) {
        return jdbc.update("""
                UPDATE places SET status = 'MERGED', merged_into_id = ?, updated_at = ? WHERE id = ? AND status = 'ACTIVE'""",
                intoId, Timestamp.from(now), id) > 0;
    }

    /** The kept place takes the chosen values. */
    public void applyValues(long id, PlaceModerationRepository.PlaceValues v, Instant now) {
        jdbc.update("""
                UPDATE places SET name_vi = ?, name_ja = ?, name_en = ?, address = ?, category_id = ?, area_id = ?,
                                  lat = ?, lng = ?, updated_at = ?
                WHERE id = ?""", v.nameVi(), v.nameJa(), v.nameEn(), v.address(), v.categoryId(), v.areaId(),
                java.math.BigDecimal.valueOf(v.lat()).setScale(6, java.math.RoundingMode.HALF_UP),
                java.math.BigDecimal.valueOf(v.lng()).setScale(6, java.math.RoundingMode.HALF_UP),
                Timestamp.from(now), id);
    }

    private static Double decimal(java.math.BigDecimal value) {
        return value == null ? null : value.doubleValue();
    }
}
