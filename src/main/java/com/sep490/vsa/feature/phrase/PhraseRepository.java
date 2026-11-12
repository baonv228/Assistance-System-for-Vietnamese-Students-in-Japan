package com.sep490.vsa.feature.phrase;

import com.sep490.vsa.feature.phrase.PhraseDtos.CategoryRef;
import com.sep490.vsa.feature.phrase.PhraseDtos.Group;
import com.sep490.vsa.feature.phrase.PhraseDtos.PhraseCard;
import com.sep490.vsa.feature.phrase.PhraseDtos.TopicRef;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Plain SQL over {@code phrases}, {@code learning_topics}, {@code phrase_topic_links}, {@code user_phrase_marks} and
 * {@code saved_phrases}. Phrases are written by the Academic Manager; Members keep their flash card answers ("Chưa
 * thuộc" / "Đã thuộc") and save entries ("Lưu", listed in "Đã lưu").
 * <p>
 * A guest is read as viewer id -1, so the marks joins find nothing.
 */
@Repository
public class PhraseRepository {

    /** At most this many phrases in one answer (a search over everything); the library holds a few hundred at most. */
    private static final int LIST_MAX = 200;

    /** The Member's own lists: "Chưa thuộc", "Đã thuộc", "Đã lưu" (alias sv = the viewer's saved_phrases row). */
    static final Map<String, String> MINE = Map.of(
            "learning", "m.is_learning",
            "learned", "m.is_learned",
            "saved", "sv.user_id IS NOT NULL");

    private static final String SHOWN_PHRASE = "ph.deleted_at IS NULL";

    private final JdbcTemplate jdbc;
    private final NamedParameterJdbcTemplate named;

    public PhraseRepository(JdbcTemplate jdbc, NamedParameterJdbcTemplate named) {
        this.jdbc = jdbc;
        this.named = named;
    }

    public record Account(String role, String status) {
    }

    /** What a list shows: one topic, one category, one of the viewer's own lists ({@link #MINE} key) and/or a search text. */
    public record Filter(String topicId, String categoryId, String mine, String keyword) {
    }

    public Optional<Account> account(long userId) {
        return jdbc.query("SELECT role, status FROM users WHERE id = ?",
                (rs, i) -> new Account(rs.getString(1), rs.getString(2)), userId).stream().findFirst();
    }

    // ---------------------------------------------------------------- start page

    /** The topics holding entries of this kind, with how many; topics with the most entries first. */
    public List<Group> topics(long viewerId, String kind) {
        return jdbc.query("""
                SELECT t.id, t.name, t.icon, COUNT(ph.id), COALESCE(SUM(m.is_learned), 0)
                FROM learning_topics t
                JOIN phrase_topic_links l ON l.topic_id = t.id
                JOIN phrases ph ON ph.id = l.phrase_id AND ph.deleted_at IS NULL AND ph.kind = ?
                LEFT JOIN user_phrase_marks m ON m.phrase_id = ph.id AND m.user_id = ?
                GROUP BY t.id, t.name, t.icon
                ORDER BY COUNT(ph.id) DESC, t.name""",
                (rs, i) -> new Group(rs.getString(1), rs.getString(2), rs.getString(3), rs.getInt(4), rs.getInt(5)),
                kind, viewerId);
    }

    /** The place categories holding entries of this kind, in the Map's order. */
    public List<Group> categories(long viewerId, String kind) {
        return jdbc.query("""
                SELECT c.id, c.name_vi, c.icon, COUNT(ph.id), COALESCE(SUM(m.is_learned), 0)
                FROM place_categories c
                JOIN phrases ph ON ph.category_id = c.id AND ph.deleted_at IS NULL AND ph.kind = ?
                LEFT JOIN user_phrase_marks m ON m.phrase_id = ph.id AND m.user_id = ?
                GROUP BY c.id, c.name_vi, c.icon, c.sort_order
                ORDER BY c.sort_order, c.id""",
                (rs, i) -> new Group(rs.getString(1), rs.getString(2), rs.getString(3), rs.getInt(4), rs.getInt(5)),
                kind, viewerId);
    }

    /** {entries of this kind, of them kept by the Member as "Đã thuộc"}. */
    public int[] kindTotals(long viewerId, String kind) {
        return jdbc.queryForObject("""
                SELECT COUNT(ph.id), COALESCE(SUM(m.is_learned), 0) FROM phrases ph
                LEFT JOIN user_phrase_marks m ON m.phrase_id = ph.id AND m.user_id = ?
                WHERE ph.kind = ? AND ph.deleted_at IS NULL""",
                (rs, i) -> new int[] {rs.getInt(1), rs.getInt(2)}, viewerId, kind);
    }

    // ---------------------------------------------------------------- lists

    /** How many entries the Member saved that are still shown (the "Đã lưu" button of the start page). */
    public int savedCount(long viewerId) {
        Integer n = jdbc.queryForObject("""
                SELECT COUNT(*) FROM saved_phrases sv JOIN phrases ph ON ph.id = sv.phrase_id
                WHERE sv.user_id = ? AND ph.deleted_at IS NULL""", Integer.class, viewerId);
        return n == null ? 0 : n;
    }

    /**
     * Phrases matching the filter, by category (the Map's order) then as written, with their topics; the "Đã lưu" list
     * newest saved first. The search text
     * is matched accent- and case-insensitively against the Japanese, romaji, meaning, the category name and the topic
     * names.
     */
    public List<PhraseCard> phrases(Filter filter, long viewerId) {
        MapSqlParameterSource params = new MapSqlParameterSource("viewerId", viewerId).addValue("max", LIST_MAX);
        List<String> where = new ArrayList<>(List.of(SHOWN_PHRASE));
        if (filter.topicId() != null) {
            where.add("EXISTS (SELECT 1 FROM phrase_topic_links l WHERE l.phrase_id = ph.id AND l.topic_id = :topicId)");
            params.addValue("topicId", filter.topicId());
        }
        if (filter.categoryId() != null) {
            where.add("ph.category_id = :categoryId");
            params.addValue("categoryId", filter.categoryId());
        }
        if (filter.mine() != null) {
            where.add(MINE.get(filter.mine()));
        }
        if (filter.keyword() != null) {
            where.add("(" + folded("ph.japanese") + " LIKE :kw OR " + folded("ph.romaji") + " LIKE :kw OR "
                    + folded("ph.meaning_vi") + " LIKE :kw OR " + folded("c.name_vi") + " LIKE :kw OR EXISTS ("
                    + "SELECT 1 FROM phrase_topic_links l JOIN learning_topics t ON t.id = l.topic_id "
                    + "WHERE l.phrase_id = ph.id AND " + folded("t.name") + " LIKE :kw))");
            params.addValue("kw", "%" + escapeLike(filter.keyword()) + "%");
        }
        List<Row> rows = named.query("""
                SELECT ph.id, ph.kind, ph.japanese, ph.romaji, ph.meaning_vi, c.id AS cid, c.name_vi, c.icon,
                       COALESCE(m.is_learning, FALSE) AS learning, COALESCE(m.is_learned, FALSE) AS learned,
                       sv.user_id IS NOT NULL AS saved
                FROM phrases ph
                JOIN place_categories c ON c.id = ph.category_id
                LEFT JOIN user_phrase_marks m ON m.phrase_id = ph.id AND m.user_id = :viewerId
                LEFT JOIN saved_phrases sv ON sv.phrase_id = ph.id AND sv.user_id = :viewerId
                WHERE %s
                ORDER BY %s
                LIMIT :max""".formatted(String.join(" AND ", where),
                        "saved".equals(filter.mine()) ? "sv.created_at DESC, ph.id DESC" : "c.sort_order, c.id, ph.id"),
                params,
                (rs, i) -> new Row(rs.getLong("id"), rs.getString("kind"), rs.getString("japanese"),
                        rs.getString("romaji"), rs.getString("meaning_vi"),
                        new CategoryRef(rs.getString("cid"), rs.getString("name_vi"), rs.getString("icon")),
                        rs.getBoolean("learning"), rs.getBoolean("learned"), rs.getBoolean("saved")));
        Map<Long, List<TopicRef>> topics = topicsOf(rows.stream().map(Row::id).toList());
        return rows.stream().map(r -> new PhraseCard(r.id(), r.kind(), r.japanese(), r.romaji(), r.meaningVi(),
                r.category(), topics.getOrDefault(r.id(), List.of()), r.learning(), r.learned(), r.saved()))
                .toList();
    }

    private record Row(long id, String kind, String japanese, String romaji, String meaningVi, CategoryRef category,
                       boolean learning, boolean learned, boolean saved) {
    }

    private Map<Long, List<TopicRef>> topicsOf(List<Long> phraseIds) {
        Map<Long, List<TopicRef>> map = new LinkedHashMap<>();
        if (phraseIds.isEmpty()) {
            return map;
        }
        named.query("""
                SELECT l.phrase_id, t.id, t.name FROM phrase_topic_links l JOIN learning_topics t ON t.id = l.topic_id
                WHERE l.phrase_id IN (:ids) ORDER BY t.name""", new MapSqlParameterSource("ids", phraseIds),
                rs -> {
                    map.computeIfAbsent(rs.getLong(1), k -> new ArrayList<>())
                            .add(new TopicRef(rs.getString(2), rs.getString(3)));
                });
        return map;
    }

    // ---------------------------------------------------------------- writes

    /**
     * The phrase's writer (the Academic Manager), the row locked: FOR SHARE while a mark is changed (the phrase cannot
     * be deleted meanwhile), FOR UPDATE for a report (reports on one phrase run one at a time).
     */
    public Optional<Long> lockPhrase(long phraseId, boolean exclusive) {
        return jdbc.query("SELECT created_by FROM phrases ph WHERE id = ? AND " + SHOWN_PHRASE
                        + (exclusive ? " FOR UPDATE" : " FOR SHARE"), (rs, i) -> rs.getLong(1), phraseId)
                .stream().findFirst();
    }

    /** Keeps the flash card answer: "Chưa thuộc" ({@code learned} false) or "Đã thuộc"; the row is made on first use. */
    public void setFlash(long userId, long phraseId, boolean learned) {
        jdbc.update("""
                INSERT INTO user_phrase_marks (user_id, phrase_id, is_learned, is_learning) VALUES (?, ?, ?, ?)
                ON DUPLICATE KEY UPDATE is_learned = ?, is_learning = ?""",
                userId, phraseId, learned, !learned, learned, !learned);
    }

    /** Forgets the flash card answer (neither "Chưa thuộc" nor "Đã thuộc"); the row stays with both false. */
    public void clearFlash(long userId, long phraseId) {
        jdbc.update("UPDATE user_phrase_marks SET is_learned = FALSE, is_learning = FALSE WHERE user_id = ? AND phrase_id = ?",
                userId, phraseId);
    }

    /** "Lưu" / "Bỏ lưu". Saving twice keeps one row and its first time; unsaving an unsaved entry is fine. */
    public void setSaved(long userId, long phraseId, boolean on) {
        if (on) {
            jdbc.update("""
                    INSERT INTO saved_phrases (user_id, phrase_id) VALUES (?, ?)
                    ON DUPLICATE KEY UPDATE created_at = created_at""", userId, phraseId);
        } else {
            jdbc.update("DELETE FROM saved_phrases WHERE user_id = ? AND phrase_id = ?", userId, phraseId);
        }
    }

    /** {learning, learned} of one phrase for one user. */
    public boolean[] marks(long userId, long phraseId) {
        return jdbc.query("""
                        SELECT is_learning, is_learned FROM user_phrase_marks WHERE user_id = ? AND phrase_id = ?""",
                        (rs, i) -> new boolean[] {rs.getBoolean(1), rs.getBoolean(2)}, userId, phraseId)
                .stream().findFirst().orElse(new boolean[] {false, false});
    }

    private static String folded(String column) {
        return column + " COLLATE utf8mb4_0900_ai_ci";
    }

    static String escapeLike(String text) {
        return text.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }
}
