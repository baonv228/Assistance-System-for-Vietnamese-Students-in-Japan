package com.sep490.vsa.feature.blog;

import com.sep490.vsa.feature.blog.BlogDtos.Author;
import com.sep490.vsa.feature.blog.BlogDtos.PlaceRef;
import com.sep490.vsa.feature.blog.BlogDtos.Tag;
import com.sep490.vsa.feature.blog.BlogDtos.TopicCount;
import com.sep490.vsa.feature.blog.BlogDtos.TopicRef;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Read-only SQL for the Blog Feed. Plain SQL (not JPA) because the filters are dynamic and the screen
 * also reads users, tags, topics and places; it never writes the blog tables.
 * <p>
 * The feed, its statistics and "Bài nổi bật" cover posts only ({@code blog_type = 'POST'}); questions live in the
 * same table but are listed by feature/qa. {@link #topicsOf}, {@link #placesOf} and {@link #tagsOf} work for
 * both and are reused by feature/qa.
 */
@Repository
public class BlogFeedRepository {

    /** Posts a viewer may see: published ones, plus their own posts whose media is still being scanned. */
    private static final String VISIBLE = """
            b.blog_type = 'POST'
            AND (b.status = 'PUBLISHED' OR (b.status = 'PROCESSING' AND :viewerId IS NOT NULL AND b.author_id = :viewerId))""";

    private final NamedParameterJdbcTemplate jdbc;

    public BlogFeedRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** One row of {@code blogs} joined with its author, before topics, places and tags are attached. */
    public record Row(Long id, String type, String status, boolean pinned, boolean answered, String title,
                      String contentStart, Long authorId, String authorName, String avatarUrl, int likeCount,
                      int commentCount, Instant createdAt) {
    }

    // ---------------------------------------------------------------- list

    public long count(BlogFeedQuery query) {
        MapSqlParameterSource params = new MapSqlParameterSource();
        String where = where(query, params);
        Long total = jdbc.queryForObject("SELECT COUNT(*) FROM blogs b WHERE " + where, params, Long.class);
        return total == null ? 0 : total;
    }

    public List<Row> find(BlogFeedQuery query) {
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("limit", query.size())
                .addValue("offset", (long) query.page() * query.size());
        String where = where(query, params);
        String order = switch (query.sort()) {
            // Moderator-pinned posts stay on top of the default view
            case NEWEST -> "b.is_pinned DESC, b.created_at DESC, b.id DESC";
            case LIKES -> "b.like_count DESC, b.created_at DESC, b.id DESC";
            case COMMENTS -> "b.comment_count DESC, b.created_at DESC, b.id DESC";
        };
        return jdbc.query(SELECT_ROW + " WHERE " + where + " ORDER BY " + order + " LIMIT :limit OFFSET :offset",
                params, BlogFeedRepository::row);
    }

    /** Published posts (not questions) with these ids, in the given order (used for the featured block). */
    public List<Row> findPublished(List<Long> ids) {
        if (ids.isEmpty()) {
            return List.of();
        }
        Map<Long, Row> byId = new HashMap<>();
        jdbc.query(SELECT_ROW + " WHERE b.id IN (:ids) AND b.status = 'PUBLISHED' AND b.blog_type = 'POST'", Map.of("ids", ids),
                (ResultSet rs) -> {
                    while (rs.next()) {
                        Row r = row(rs, 0);
                        byId.put(r.id(), r);
                    }
                    return null;
                });
        return ids.stream().filter(byId::containsKey).map(byId::get).toList();
    }

    private static final String SELECT_ROW = """
            SELECT b.id, b.blog_type, b.status, b.is_pinned, (b.best_comment_id IS NOT NULL) AS answered, b.title,
                   LEFT(b.content, 400) AS content_start, u.id AS author_id, u.display_name, u.avatar_url,
                   b.like_count, b.comment_count, b.created_at
            FROM blogs b JOIN users u ON u.id = b.author_id""";

    private static Row row(ResultSet rs, int i) throws SQLException {
        return new Row(rs.getLong("id"), rs.getString("blog_type"), rs.getString("status"), rs.getBoolean("is_pinned"),
                rs.getBoolean("answered"), rs.getString("title"), rs.getString("content_start"),
                rs.getLong("author_id"), rs.getString("display_name"), rs.getString("avatar_url"),
                rs.getInt("like_count"), rs.getInt("comment_count"), instant(rs.getTimestamp("created_at")));
    }

    private static String where(BlogFeedQuery query, MapSqlParameterSource params) {
        params.addValue("viewerId", query.viewerId());
        List<String> parts = new ArrayList<>();
        parts.add("(" + VISIBLE + ")");
        if (!query.topicIds().isEmpty()) {
            parts.add("""
                    EXISTS (SELECT 1 FROM blog_topic_links btl JOIN blog_topics t ON t.id = btl.topic_id
                            WHERE btl.blog_id = b.id AND t.is_active AND btl.topic_id IN (:topicIds))""");
            params.addValue("topicIds", query.topicIds());
        }
        if (query.keyword() != null) {
            // Title, content, or the name (vi/ja/en) of a linked place
            parts.add("(" + folded("b.title") + " LIKE :kw OR " + folded("b.content") + " LIKE :kw OR EXISTS ("
                    + "SELECT 1 FROM blog_place_links bpl JOIN places p ON p.id = bpl.place_id "
                    + "WHERE bpl.blog_id = b.id AND p.status = 'ACTIVE' AND (" + folded("p.name_vi") + " LIKE :kw OR "
                    + folded("p.name_ja") + " LIKE :kw OR " + folded("p.name_en") + " LIKE :kw)))");
            params.addValue("kw", "%" + escapeLike(query.keyword()) + "%");
        }
        return String.join(" AND ", parts);
    }

    /**
     * Matches {@link BlogFeedQuery#fold(String)} on the database side: MySQL 8's accent- and case-insensitive
     * collation treats "Phân", "phan" and "PHAN" (and "Đ"/"d") as equal, whatever the column's own collation is.
     */
    private static String folded(String column) {
        return column + " COLLATE utf8mb4_0900_ai_ci";
    }

    static String escapeLike(String text) {
        return text.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    // ---------------------------------------------------------------- details for a page of cards

    public Map<Long, List<TopicRef>> topicsOf(Collection<Long> blogIds) {
        Map<Long, List<TopicRef>> result = new HashMap<>();
        if (blogIds.isEmpty()) {
            return result;
        }
        jdbc.query("""
                SELECT btl.blog_id, t.id, t.name FROM blog_topic_links btl JOIN blog_topics t ON t.id = btl.topic_id
                WHERE btl.blog_id IN (:ids) AND t.is_active ORDER BY t.is_other, t.sort_order, t.name""",
                Map.of("ids", blogIds), (ResultSet rs) -> {
                    while (rs.next()) {
                        result.computeIfAbsent(rs.getLong(1), k -> new ArrayList<>())
                                .add(new TopicRef(rs.getLong(2), rs.getString(3)));
                    }
                    return null;
                });
        return result;
    }

    /** Linked places that are still on the map; a merged place shows the place it was merged into. */
    public Map<Long, List<PlaceRef>> placesOf(Collection<Long> blogIds) {
        Map<Long, List<PlaceRef>> result = new HashMap<>();
        if (blogIds.isEmpty()) {
            return result;
        }
        jdbc.query("""
                SELECT bpl.blog_id, shown.id, shown.name_vi, c.name_vi, shown.address, shown.lat, shown.lng
                FROM blog_place_links bpl
                JOIN places p ON p.id = bpl.place_id
                JOIN places shown ON shown.id = CASE WHEN p.status = 'MERGED' THEN p.merged_into_id ELSE p.id END
                JOIN place_categories c ON c.id = shown.category_id
                WHERE bpl.blog_id IN (:ids) AND shown.status = 'ACTIVE'
                ORDER BY bpl.blog_id, shown.name_vi""",
                Map.of("ids", blogIds), (ResultSet rs) -> {
                    while (rs.next()) {
                        List<PlaceRef> list = result.computeIfAbsent(rs.getLong(1), k -> new ArrayList<>());
                        long placeId = rs.getLong(2);
                        if (list.stream().noneMatch(p -> p.id() == placeId)) {
                            list.add(new PlaceRef(placeId, rs.getString(3), rs.getString(4), rs.getString(5),
                                    decimal(rs.getBigDecimal(6)), decimal(rs.getBigDecimal(7))));
                        }
                    }
                    return null;
                });
        return result;
    }

    private static Double decimal(java.math.BigDecimal value) {
        return value == null ? null : value.doubleValue();
    }

    /** Approved, not revoked identity tags of each user. */
    public Map<Long, List<Tag>> tagsOf(Collection<Long> userIds) {
        Map<Long, List<Tag>> result = new HashMap<>();
        if (userIds.isEmpty()) {
            return result;
        }
        jdbc.query("""
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

    // ---------------------------------------------------------------- topics and side statistics

    public List<TopicRef> activeTopics() {
        return jdbc.query("SELECT id, name FROM blog_topics WHERE is_active ORDER BY is_other, sort_order, name",
                (rs, i) -> new TopicRef(rs.getLong(1), rs.getString(2)));
    }

    public List<TopicCount> popularTopics(int limit) {
        return jdbc.query("""
                SELECT t.id, t.name, COUNT(*) AS posts FROM blog_topic_links btl
                JOIN blog_topics t ON t.id = btl.topic_id JOIN blogs b ON b.id = btl.blog_id
                WHERE t.is_active AND b.status = 'PUBLISHED' AND b.blog_type = 'POST'
                GROUP BY t.id, t.name, t.is_other, t.sort_order
                ORDER BY posts DESC, t.is_other, t.sort_order LIMIT :limit""",
                Map.of("limit", limit), (rs, i) -> new TopicCount(rs.getLong(1), rs.getString(2), rs.getLong(3)));
    }

    /** Authors with the most published posts since {@code since}; ties go to the most recent writer. */
    public List<Map.Entry<Author, Long>> topWriters(Instant since, int limit) {
        return jdbc.query("""
                SELECT u.id, u.display_name, u.avatar_url, COUNT(*) AS posts FROM blogs b JOIN users u ON u.id = b.author_id
                WHERE b.status = 'PUBLISHED' AND b.blog_type = 'POST' AND b.created_at >= :since AND u.status = 'ACTIVE'
                GROUP BY u.id, u.display_name, u.avatar_url
                ORDER BY posts DESC, MAX(b.created_at) DESC LIMIT :limit""",
                Map.of("since", Timestamp.from(since), "limit", limit),
                (rs, i) -> Map.entry(new Author(rs.getLong(1), rs.getString(2), rs.getString(3), List.of()),
                        rs.getLong(4)));
    }

    // ---------------------------------------------------------------- featured snapshots

    /** Blog ids of the latest snapshot for WEEK or MONTH, by rank. */
    public List<Long> latestFeatured(String period) {
        return jdbc.query("""
                SELECT fs.blog_id FROM featured_snapshots fs
                WHERE fs.period = :period
                  AND fs.computed_at = (SELECT MAX(x.computed_at) FROM featured_snapshots x WHERE x.period = :period)
                ORDER BY fs.rank_no""",
                Map.of("period", period), (rs, i) -> rs.getLong(1));
    }

    /** Candidate for a featured snapshot (posts only): score = likes + 2 × comments + the author's rank bonus. */
    public record Candidate(Long blogId, Long authorId, int score) {
    }

    public List<Candidate> featuredCandidates(Instant since, int minScore) {
        return jdbc.query("""
                SELECT b.id, b.author_id,
                       b.like_count + 2 * b.comment_count
                         + COALESCE((SELECT MAX(r.featured_bonus) FROM ranks r WHERE r.min_points <= u.points), 0) AS score
                FROM blogs b JOIN users u ON u.id = b.author_id
                WHERE b.status = 'PUBLISHED' AND b.blog_type = 'POST' AND b.created_at >= :since AND u.status = 'ACTIVE'
                HAVING score >= :minScore
                ORDER BY score DESC, b.created_at DESC, b.id DESC""",
                Map.of("since", Timestamp.from(since), "minScore", minScore),
                (rs, i) -> new Candidate(rs.getLong(1), rs.getLong(2), rs.getInt(3)));
    }

    /** Replaces the snapshot of one period with the new top list (rank 1 first). */
    public void replaceFeatured(String period, Instant computedAt, List<Candidate> top) {
        jdbc.update("DELETE FROM featured_snapshots WHERE period = :period", Map.of("period", period));
        int rank = 1;
        for (Candidate c : top) {
            Map<String, Object> values = new LinkedHashMap<>();
            values.put("period", period);
            values.put("computedAt", Timestamp.from(computedAt));
            values.put("blogId", c.blogId());
            values.put("rank", rank++);
            values.put("score", c.score());
            jdbc.update("""
                    INSERT INTO featured_snapshots (period, computed_at, blog_id, rank_no, score)
                    VALUES (:period, :computedAt, :blogId, :rank, :score)""", values);
        }
    }

    private static Instant instant(Timestamp ts) {
        return ts == null ? null : ts.toInstant();
    }
}
