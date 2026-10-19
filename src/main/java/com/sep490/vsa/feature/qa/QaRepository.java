package com.sep490.vsa.feature.qa;

import com.sep490.vsa.feature.blog.BlogDtos.Author;
import com.sep490.vsa.feature.blog.BlogDtos.TopicCount;
import com.sep490.vsa.feature.qa.QaDtos.Stats;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Read-only SQL for the Hỏi-Đáp list. Questions are rows of {@code blogs} with {@code blog_type = 'QA'}; their
 * answers are rows of {@code comments} (an answer is a top-level comment, replies hang under it).
 */
@Repository
public class QaRepository {

    /** Questions a viewer may see: published ones, plus their own whose media is still being scanned. */
    private static final String VISIBLE = """
            b.blog_type = 'QA'
            AND (b.status = 'PUBLISHED' OR (b.status = 'PROCESSING' AND :viewerId IS NOT NULL AND b.author_id = :viewerId))""";

    /**
     * Solved = the chosen best answer is still published. A best answer later hidden by reports or removed no longer
     * counts, the same as on the question page ({@code BlogDetailService} only shows a visible best answer).
     */
    private static final String SOLVED_SQL =
            "EXISTS (SELECT 1 FROM comments bc WHERE bc.id = b.best_comment_id AND bc.status = 'PUBLISHED')";

    private static final String SELECT_ROW = """
            SELECT b.id, b.status, b.is_pinned, %s AS solved, b.title,
                   LEFT(b.content, 400) AS content_start, u.id AS author_id, u.display_name, u.avatar_url,
                   b.like_count, b.comment_count, b.created_at
            FROM blogs b JOIN users u ON u.id = b.author_id""".formatted(SOLVED_SQL);

    private final NamedParameterJdbcTemplate jdbc;

    public QaRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record Row(Long id, String status, boolean pinned, boolean solved, String title, String contentStart,
                      Long authorId, String authorName, String avatarUrl, int likeCount, int answerCount,
                      Instant createdAt) {
    }

    // ---------------------------------------------------------------- list

    public long count(QaListQuery query) {
        MapSqlParameterSource params = new MapSqlParameterSource();
        Long n = jdbc.queryForObject("SELECT COUNT(*) FROM blogs b WHERE " + where(query, params), params, Long.class);
        return n == null ? 0 : n;
    }

    public List<Row> find(QaListQuery query) {
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("limit", query.size())
                .addValue("offset", (long) query.page() * query.size());
        String where = where(query, params);
        String order = switch (query.sort()) {
            // Moderator-pinned questions stay on top of the default view
            case NEWEST -> "b.is_pinned DESC, b.created_at DESC, b.id DESC";
            case LIKES -> "b.like_count DESC, b.created_at DESC, b.id DESC";
            case ANSWERS -> "b.comment_count DESC, b.created_at DESC, b.id DESC";
        };
        return jdbc.query(SELECT_ROW + " WHERE " + where + " ORDER BY " + order + " LIMIT :limit OFFSET :offset",
                params, QaRepository::row);
    }

    private static Row row(ResultSet rs, int i) throws SQLException {
        return new Row(rs.getLong("id"), rs.getString("status"), rs.getBoolean("is_pinned"), rs.getBoolean("solved"),
                rs.getString("title"), rs.getString("content_start"), rs.getLong("author_id"),
                rs.getString("display_name"), rs.getString("avatar_url"), rs.getInt("like_count"),
                rs.getInt("comment_count"), instant(rs.getTimestamp("created_at")));
    }

    private static String where(QaListQuery query, MapSqlParameterSource params) {
        params.addValue("viewerId", query.viewerId());
        List<String> parts = new ArrayList<>();
        parts.add("(" + VISIBLE + ")");
        switch (query.status()) {
            case UNANSWERED -> parts.add("b.comment_count = 0 AND NOT " + SOLVED_SQL);
            case UNSOLVED -> parts.add("NOT " + SOLVED_SQL);
            case SOLVED -> parts.add(SOLVED_SQL);
            case ALL -> { }
        }
        if (!query.topicIds().isEmpty()) {
            parts.add("""
                    EXISTS (SELECT 1 FROM blog_topic_links btl JOIN blog_topics t ON t.id = btl.topic_id
                            WHERE btl.blog_id = b.id AND t.is_active AND btl.topic_id IN (:topicIds))""");
            params.addValue("topicIds", query.topicIds());
        }
        if (query.keyword() != null) {
            // Question, description, or the name (vi/ja/en) of a linked place; accents and case are ignored
            parts.add("(" + folded("b.title") + " LIKE :kw OR " + folded("b.content") + " LIKE :kw OR EXISTS ("
                    + "SELECT 1 FROM blog_place_links bpl JOIN places p ON p.id = bpl.place_id "
                    + "WHERE bpl.blog_id = b.id AND p.status = 'ACTIVE' AND (" + folded("p.name_vi") + " LIKE :kw OR "
                    + folded("p.name_ja") + " LIKE :kw OR " + folded("p.name_en") + " LIKE :kw)))");
            params.addValue("kw", "%" + escapeLike(query.keyword()) + "%");
        }
        return String.join(" AND ", parts);
    }

    private static String folded(String column) {
        return column + " COLLATE utf8mb4_0900_ai_ci";
    }

    static String escapeLike(String text) {
        return text.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    // ---------------------------------------------------------------- side statistics

    public Stats stats() {
        return jdbc.queryForObject("""
                SELECT COUNT(*),
                       COALESCE(SUM(b.comment_count = 0 AND NOT %1$s), 0),
                       COALESCE(SUM(%1$s), 0)
                FROM blogs b WHERE b.blog_type = 'QA' AND b.status = 'PUBLISHED'""".formatted(SOLVED_SQL), Map.of(),
                (rs, i) -> new Stats(rs.getLong(1), rs.getLong(2), rs.getLong(3)));
    }

    /** Topics with the most published questions. */
    public List<TopicCount> popularTopics(int limit) {
        return jdbc.query("""
                SELECT t.id, t.name, COUNT(*) AS questions FROM blog_topic_links btl
                JOIN blog_topics t ON t.id = btl.topic_id JOIN blogs b ON b.id = btl.blog_id
                WHERE t.is_active AND b.status = 'PUBLISHED' AND b.blog_type = 'QA'
                GROUP BY t.id, t.name, t.is_other, t.sort_order
                ORDER BY questions DESC, t.is_other, t.sort_order LIMIT :limit""",
                Map.of("limit", limit), (rs, i) -> new TopicCount(rs.getLong(1), rs.getString(2), rs.getLong(3)));
    }

    public record AnswererRow(Author author, long answers, long best) {
    }

    /**
     * Members whose answers (top-level comments on published questions) since {@code since} were chosen as best
     * most often, then who answered most. Answers to one's own question do not count.
     */
    public List<AnswererRow> topAnswerers(Instant since, int limit) {
        return jdbc.query("""
                SELECT u.id, u.display_name, u.avatar_url, COUNT(*) AS answers,
                       COALESCE(SUM(b.best_comment_id = c.id), 0) AS best
                FROM comments c
                JOIN blogs b ON b.id = c.blog_id
                JOIN users u ON u.id = c.author_id
                WHERE b.blog_type = 'QA' AND b.status = 'PUBLISHED' AND c.status = 'PUBLISHED'
                  AND c.parent_id IS NULL AND c.author_id <> b.author_id
                  AND c.created_at >= :since AND u.status = 'ACTIVE'
                GROUP BY u.id, u.display_name, u.avatar_url
                ORDER BY best DESC, answers DESC, MAX(c.created_at) DESC LIMIT :limit""",
                Map.of("since", Timestamp.from(since), "limit", limit),
                (rs, i) -> new AnswererRow(new Author(rs.getLong(1), rs.getString(2), rs.getString(3), List.of()),
                        rs.getLong(4), rs.getLong(5)));
    }

    private static Instant instant(Timestamp ts) {
        return ts == null ? null : ts.toInstant();
    }
}
