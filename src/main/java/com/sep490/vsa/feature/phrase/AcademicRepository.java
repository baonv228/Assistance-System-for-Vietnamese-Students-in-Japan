package com.sep490.vsa.feature.phrase;

import com.sep490.vsa.feature.phrase.AcademicDtos.LibraryRow;
import com.sep490.vsa.feature.phrase.AcademicDtos.LogLine;
import com.sep490.vsa.feature.phrase.AcademicDtos.Reporter;
import com.sep490.vsa.feature.phrase.AcademicDtos.ThinTopic;
import com.sep490.vsa.feature.phrase.AcademicDtos.TopicRow;
import com.sep490.vsa.feature.phrase.PhraseDtos.CategoryRef;
import com.sep490.vsa.feature.phrase.PhraseDtos.TopicRef;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Plain SQL for the Academic Manager: learning topics, the phrase library (soft delete with {@code deleted_at}) and
 * the report cases on phrases.
 */
@Repository
public class AcademicRepository {

    static final int PAGE_SIZE = 20;

    private final JdbcTemplate jdbc;
    private final NamedParameterJdbcTemplate named;

    public AcademicRepository(JdbcTemplate jdbc, NamedParameterJdbcTemplate named) {
        this.jdbc = jdbc;
        this.named = named;
    }

    public record PhraseRow(long id, String categoryId, String kind, String japanese, String romaji, String meaningVi,
                            long createdBy) {
    }

    public record CaseRow(long id, String targetType, long targetId, String status) {
    }

    public record CaseHead(long id, long targetId, String priority, int reportCount, Instant createdAt) {
    }

    // ---------------------------------------------------------------- overview

    private int count(String sql, Object... args) {
        Integer n = jdbc.queryForObject(sql, Integer.class, args);
        return n == null ? 0 : n;
    }

    public int openPhraseReports() {
        return count("""
                SELECT COUNT(*) FROM report_cases c JOIN phrases ph ON ph.id = c.target_id AND ph.deleted_at IS NULL
                WHERE c.status = 'OPEN' AND c.target_type = 'PHRASE'""");
    }

    public int phrases(String kind) {
        return count("SELECT COUNT(*) FROM phrases WHERE deleted_at IS NULL AND kind = ?", kind);
    }

    public List<CategoryRef> emptyCategories() {
        return jdbc.query("""
                SELECT c.id, c.name_vi, c.icon FROM place_categories c
                WHERE NOT EXISTS (SELECT 1 FROM phrases ph WHERE ph.category_id = c.id AND ph.deleted_at IS NULL)
                ORDER BY c.sort_order, c.id""", (rs, i) -> new CategoryRef(rs.getString(1), rs.getString(2),
                rs.getString(3)));
    }

    public List<ThinTopic> thinTopics(int below) {
        return jdbc.query("""
                SELECT t.id, t.name, COUNT(ph.id) FROM learning_topics t
                LEFT JOIN phrase_topic_links l ON l.topic_id = t.id
                LEFT JOIN phrases ph ON ph.id = l.phrase_id AND ph.deleted_at IS NULL
                GROUP BY t.id, t.name HAVING COUNT(ph.id) < ? ORDER BY COUNT(ph.id), t.name""",
                (rs, i) -> new ThinTopic(rs.getString(1), rs.getString(2), rs.getInt(3)), below);
    }

    public List<LogLine> recentActions(long actorId, int limit) {
        return jdbc.query("""
                SELECT description, created_at FROM activity_logs WHERE actor_id = ?
                ORDER BY created_at DESC, id DESC LIMIT ?""",
                (rs, i) -> new LogLine(rs.getString(1), rs.getTimestamp(2).toInstant()), actorId, limit);
    }

    // ---------------------------------------------------------------- learning topics

    public List<TopicRow> topics() {
        return jdbc.query("""
                SELECT t.id, t.name, t.icon, COUNT(DISTINCT ph.id),
                       GROUP_CONCAT(DISTINCT c.name_vi ORDER BY c.sort_order SEPARATOR '|')
                FROM learning_topics t
                LEFT JOIN phrase_topic_links l ON l.topic_id = t.id
                LEFT JOIN phrases ph ON ph.id = l.phrase_id AND ph.deleted_at IS NULL
                LEFT JOIN place_categories c ON c.id = ph.category_id
                GROUP BY t.id, t.name, t.icon
                ORDER BY t.name""", (rs, i) -> {
            String names = rs.getString(5);
            return new TopicRow(rs.getString(1), rs.getString(2), rs.getString(3), rs.getInt(4),
                    names == null ? List.of() : Arrays.asList(names.split("\\|")));
        });
    }

    /** Every topic's id and name, locked, so two names are checked for duplicates one at a time. */
    public Map<String, String> lockTopicNames() {
        Map<String, String> names = new LinkedHashMap<>();
        jdbc.query("SELECT id, name FROM learning_topics FOR UPDATE", rs -> {
            names.put(rs.getString(1), rs.getString(2));
        });
        return names;
    }

    public Optional<TopicRow> lockTopic(String id) {
        return jdbc.query("SELECT id, name, icon FROM learning_topics WHERE id = ? FOR UPDATE",
                (rs, i) -> new TopicRow(rs.getString(1), rs.getString(2), rs.getString(3), 0, List.of()), id)
                .stream().findFirst();
    }

    public void insertTopic(String id, String name, String icon) {
        jdbc.update("INSERT INTO learning_topics (id, name, icon) VALUES (?, ?, ?)", id, name, icon);
    }

    public void updateTopic(String id, String name, String icon) {
        jdbc.update("UPDATE learning_topics SET name = ?, icon = ? WHERE id = ?", name, icon, id);
    }

    /** Removes the topic and its labels; the phrases stay. Returns how many phrases lost the label. */
    public int deleteTopic(String id) {
        int links = jdbc.update("DELETE FROM phrase_topic_links WHERE topic_id = ?", id);
        jdbc.update("DELETE FROM learning_topics WHERE id = ?", id);
        return links;
    }

    public boolean topicExists(String id) {
        return !jdbc.queryForList("SELECT 1 FROM learning_topics WHERE id = ?", Integer.class, id).isEmpty();
    }

    // ---------------------------------------------------------------- library

    public List<CategoryRef> categories() {
        return jdbc.query("SELECT id, name_vi, icon FROM place_categories ORDER BY sort_order, id",
                (rs, i) -> new CategoryRef(rs.getString(1), rs.getString(2), rs.getString(3)));
    }

    public List<TopicRef> topicRefs() {
        return jdbc.query("SELECT id, name FROM learning_topics ORDER BY name",
                (rs, i) -> new TopicRef(rs.getString(1), rs.getString(2)));
    }

    public boolean categoryExists(String id) {
        return !jdbc.queryForList("SELECT 1 FROM place_categories WHERE id = ?", Integer.class, id).isEmpty();
    }

    /** One page of the library, newest first, with how much each entry is used. */
    public List<LibraryRow> library(String category, String topic, String kind, String keyword, int page) {
        MapSqlParameterSource params = new MapSqlParameterSource();
        String where = libraryWhere(category, topic, kind, keyword, params);
        params.addValue("size", PAGE_SIZE).addValue("offset", page * PAGE_SIZE);
        return rows(named.query(LIBRARY_SELECT + " WHERE " + where + " ORDER BY ph.id DESC LIMIT :size OFFSET :offset",
                params, (rs, i) -> readLibraryRow(rs)));
    }

    public long libraryTotal(String category, String topic, String kind, String keyword) {
        MapSqlParameterSource params = new MapSqlParameterSource();
        String where = libraryWhere(category, topic, kind, keyword, params);
        Long n = named.queryForObject("SELECT COUNT(*) FROM phrases ph JOIN place_categories c ON c.id = ph.category_id "
                + "WHERE " + where, params, Long.class);
        return n == null ? 0 : n;
    }

    /** One entry as the library shows it (null when deleted). */
    public LibraryRow libraryRow(long id) {
        List<LibraryRow> rows = rows(jdbc.query(LIBRARY_SELECT + " WHERE ph.deleted_at IS NULL AND ph.id = ?",
                (rs, i) -> readLibraryRow(rs), id));
        return rows.isEmpty() ? null : rows.get(0);
    }

    private static final String LIBRARY_SELECT = """
            SELECT ph.id, ph.kind, ph.japanese, ph.romaji, ph.meaning_vi, c.id AS cid, c.name_vi, c.icon,
                   (SELECT COUNT(*) FROM report_cases rc WHERE rc.target_type = 'PHRASE' AND rc.target_id = ph.id
                       AND rc.status = 'OPEN') AS open_reports
            FROM phrases ph JOIN place_categories c ON c.id = ph.category_id""";

    private static String libraryWhere(String category, String topic, String kind, String keyword,
                                       MapSqlParameterSource params) {
        List<String> where = new ArrayList<>(List.of("ph.deleted_at IS NULL"));
        if (category != null) {
            where.add("ph.category_id = :category");
            params.addValue("category", category);
        }
        if (topic != null) {
            where.add("EXISTS (SELECT 1 FROM phrase_topic_links l WHERE l.phrase_id = ph.id AND l.topic_id = :topic)");
            params.addValue("topic", topic);
        }
        if (kind != null) {
            where.add("ph.kind = :kind");
            params.addValue("kind", kind);
        }
        if (keyword != null) {
            where.add("(ph.japanese COLLATE utf8mb4_0900_ai_ci LIKE :kw OR ph.romaji COLLATE utf8mb4_0900_ai_ci LIKE :kw"
                    + " OR ph.meaning_vi COLLATE utf8mb4_0900_ai_ci LIKE :kw)");
            params.addValue("kw", "%" + PhraseRepository.escapeLike(keyword) + "%");
        }
        return String.join(" AND ", where);
    }

    private static LibraryRow readLibraryRow(ResultSet rs) throws SQLException {
        return new LibraryRow(rs.getLong("id"), rs.getString("kind"), rs.getString("japanese"),
                rs.getString("romaji"), rs.getString("meaning_vi"),
                new CategoryRef(rs.getString("cid"), rs.getString("name_vi"), rs.getString("icon")), List.of(),
                rs.getInt("open_reports"));
    }

    /** Adds the topics to the rows. */
    private List<LibraryRow> rows(List<LibraryRow> raw) {
        Map<Long, List<TopicRef>> topics = topicsOf(raw.stream().map(LibraryRow::id).toList());
        return raw.stream().map(r -> new LibraryRow(r.id(), r.kind(), r.japanese(), r.romaji(),
                r.meaningVi(), r.category(), topics.getOrDefault(r.id(), List.of()), r.openReports())).toList();
    }

    private Map<Long, List<TopicRef>> topicsOf(List<Long> phraseIds) {
        Map<Long, List<TopicRef>> map = new LinkedHashMap<>();
        if (phraseIds.isEmpty()) {
            return map;
        }
        named.query("""
                SELECT l.phrase_id, t.id, t.name FROM phrase_topic_links l JOIN learning_topics t ON t.id = l.topic_id
                WHERE l.phrase_id IN (:ids) ORDER BY t.name""", new MapSqlParameterSource("ids", phraseIds), rs -> {
            map.computeIfAbsent(rs.getLong(1), k -> new ArrayList<>()).add(new TopicRef(rs.getString(2), rs.getString(3)));
        });
        return map;
    }

    /** An entry that is not deleted, the row locked (reports and marks on it wait). */
    public Optional<PhraseRow> lockPhrase(long id) {
        return jdbc.query("""
                SELECT id, category_id, kind, japanese, romaji, meaning_vi, created_by FROM phrases
                WHERE id = ? AND deleted_at IS NULL FOR UPDATE""",
                (rs, i) -> new PhraseRow(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4),
                        rs.getString(5), rs.getString(6), rs.getLong(7)), id).stream().findFirst();
    }

    /** Another entry of the category with exactly this Japanese (byte for byte, so パン and バン differ). */
    public Optional<Long> duplicate(String categoryId, String japanese, Long exceptId) {
        return jdbc.query("""
                SELECT id FROM phrases WHERE deleted_at IS NULL AND category_id = ?
                  AND japanese COLLATE utf8mb4_bin = ? AND id <> ? ORDER BY id LIMIT 1""",
                (rs, i) -> rs.getLong(1), categoryId, japanese, exceptId == null ? -1 : exceptId).stream().findFirst();
    }

    public long insertPhrase(PhraseRow p, Instant now) {
        GeneratedKeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement("""
                    INSERT INTO phrases (category_id, kind, japanese, romaji, meaning_vi, created_by, created_at, updated_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?)""", Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, p.categoryId());
            ps.setString(2, p.kind());
            ps.setString(3, p.japanese());
            ps.setString(4, p.romaji());
            ps.setString(5, p.meaningVi());
            ps.setLong(6, p.createdBy());
            ps.setTimestamp(7, Timestamp.from(now));
            ps.setTimestamp(8, Timestamp.from(now));
            return ps;
        }, keys);
        return keys.getKey().longValue();
    }

    public void updatePhrase(PhraseRow p, Instant now) {
        jdbc.update("""
                UPDATE phrases SET category_id = ?, kind = ?, japanese = ?, romaji = ?, meaning_vi = ?, updated_at = ?
                WHERE id = ?""", p.categoryId(), p.kind(), p.japanese(), p.romaji(), p.meaningVi(), Timestamp.from(now),
                p.id());
    }

    public List<String> topicIdsOf(long phraseId) {
        return jdbc.queryForList("SELECT topic_id FROM phrase_topic_links WHERE phrase_id = ? ORDER BY topic_id",
                String.class, phraseId);
    }

    public void setTopics(long phraseId, List<String> topicIds) {
        jdbc.update("DELETE FROM phrase_topic_links WHERE phrase_id = ?", phraseId);
        for (String topic : topicIds) {
            jdbc.update("INSERT INTO phrase_topic_links (phrase_id, topic_id) VALUES (?, ?)", phraseId, topic);
        }
    }

    public void softDeletePhrase(long id, Instant now) {
        jdbc.update("UPDATE phrases SET deleted_at = ? WHERE id = ?", Timestamp.from(now), id);
    }

    // ---------------------------------------------------------------- report cases

    /** Open cases on phrases that are not deleted, urgent first, then oldest. */
    public List<CaseHead> openPhraseCases() {
        return jdbc.query("""
                SELECT c.id, c.target_id, c.priority, c.report_count, c.created_at FROM report_cases c
                JOIN phrases ph ON ph.id = c.target_id AND ph.deleted_at IS NULL
                WHERE c.status = 'OPEN' AND c.target_type = 'PHRASE'
                ORDER BY c.priority = 'URGENT' DESC, c.created_at, c.id""",
                (rs, i) -> new CaseHead(rs.getLong(1), rs.getLong(2), rs.getString(3), rs.getInt(4),
                        rs.getTimestamp(5).toInstant()));
    }

    /** The reports of the cases, oldest first: who, which reason, their note. */
    public Map<Long, List<Reporter>> reportersOf(List<Long> caseIds) {
        Map<Long, List<Reporter>> map = new LinkedHashMap<>();
        if (caseIds.isEmpty()) {
            return map;
        }
        named.query("""
                SELECT r.case_id, u.display_name, ro.label, r.note, r.created_at FROM reports r
                JOIN users u ON u.id = r.reporter_id JOIN reason_options ro ON ro.id = r.reason_id
                WHERE r.case_id IN (:ids) ORDER BY r.created_at, r.id""", new MapSqlParameterSource("ids", caseIds), rs -> {
            map.computeIfAbsent(rs.getLong(1), k -> new ArrayList<>()).add(new Reporter(rs.getString(2),
                    rs.getString(3), rs.getString(4), rs.getTimestamp(5).toInstant()));
        });
        return map;
    }

    public Optional<CaseRow> findCase(long caseId) {
        return jdbc.query("SELECT id, target_type, target_id, status FROM report_cases WHERE id = ?",
                (rs, i) -> new CaseRow(rs.getLong(1), rs.getString(2), rs.getLong(3), rs.getString(4)), caseId)
                .stream().findFirst();
    }

    /** The case locked; read again after its content was locked (content first, like a report). */
    public Optional<CaseRow> lockOpenCase(long caseId) {
        return jdbc.query("SELECT id, target_type, target_id, status FROM report_cases WHERE id = ? FOR UPDATE",
                        (rs, i) -> new CaseRow(rs.getLong(1), rs.getString(2), rs.getLong(3), rs.getString(4)), caseId)
                .stream().filter(c -> "OPEN".equals(c.status())).findFirst();
    }

    /** Closes the open cases of these items with a decision (KEEP, REMOVE, CORRECTED). */
    public void closeCases(String targetType, List<Long> targetIds, String decision, String note, long handlerId,
                           Instant now) {
        if (targetIds.isEmpty()) {
            return;
        }
        named.update("""
                UPDATE report_cases SET status = 'RESOLVED', decision = :decision, decision_note = :note,
                       handled_by = :handler, handled_at = :now
                WHERE status = 'OPEN' AND target_type = :type AND target_id IN (:ids)""",
                new MapSqlParameterSource("decision", decision).addValue("note", note).addValue("handler", handlerId)
                        .addValue("now", Timestamp.from(now)).addValue("type", targetType).addValue("ids", targetIds));
    }
}
