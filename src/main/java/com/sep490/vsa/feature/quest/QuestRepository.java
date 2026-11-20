package com.sep490.vsa.feature.quest;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sep490.vsa.feature.coin.CoinLedger;
import com.sep490.vsa.feature.quest.QuestDtos.PhraseRef;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Plain SQL of "Nhiệm vụ hằng ngày": the day's quests ({@code user_daily_quests}), the entries counted for them
 * ({@code user_quest_items}), the days done ({@code user_quest_days}) and the answer log ({@code phrase_answer_log}).
 * The library of "Nhật ngữ" ({@code phrases}, {@code phrase_topic_links}, {@code user_phrase_marks}) is only read:
 * an entry counts while {@code deleted_at IS NULL}, of both kinds (Mẫu câu and Từ vựng), as the "Luyện tập" dialog.
 */
@Repository
class QuestRepository {

    private static final TypeReference<LinkedHashMap<String, Object>> PARAMS = new TypeReference<>() {
    };

    /** The Member's flash card answer of an entry ({@code m}): "Đã thuộc", "Chưa thuộc", or none ("Chưa luyện"). */
    private static final String MARK_JOIN = "LEFT JOIN user_phrase_marks m ON m.phrase_id = ph.id AND m.user_id = ?";

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    QuestRepository(JdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    /** One quest of one day. {@code completedAt} null while not completed; {@code coins} = xu received. */
    record QuestRow(long id, String code, int sortOrder, Map<String, Object> params, String title, int target,
                    int progress, int coins, Instant completedAt) {

        boolean completed() {
            return completedAt != null;
        }
    }

    /** A learning topic holding entries: how many, and how many the Member does not keep as "Đã thuộc". */
    record TopicStat(String id, String name, int entries, int notLearned) {
    }

    /** A day on which every quest was completed, with the streak kept for it. */
    record DoneDay(long id, LocalDate date, int streak) {
    }

    // ---------------------------------------------------------------- quests of a day

    List<QuestRow> quests(long userId, LocalDate day) {
        return jdbc.query("""
                SELECT id, code, sort_order, params, title, target, progress, coins, completed_at
                FROM user_daily_quests WHERE user_id = ? AND quest_date = ? ORDER BY sort_order, id""",
                (rs, i) -> new QuestRow(rs.getLong(1), rs.getString(2), rs.getInt(3), params(rs.getString(4)),
                        rs.getString(5), rs.getInt(6), rs.getInt(7), rs.getInt(8), instant(rs, 9)),
                userId, Date.valueOf(day));
    }

    Set<String> codesOn(long userId, LocalDate day) {
        return new HashSet<>(jdbc.queryForList(
                "SELECT code FROM user_daily_quests WHERE user_id = ? AND quest_date = ?", String.class,
                userId, Date.valueOf(day)));
    }

    long insertQuest(long userId, LocalDate day, String code, int sortOrder, Map<String, Object> params, String title,
                     int target, Instant now) {
        KeyHolder keys = new GeneratedKeyHolder();
        String paramsJson = toJson(params);
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement("""
                    INSERT INTO user_daily_quests (user_id, quest_date, code, sort_order, params, title, target, created_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?)""", Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, userId);
            ps.setDate(2, Date.valueOf(day));
            ps.setString(3, code);
            ps.setInt(4, sortOrder);
            ps.setString(5, paramsJson);
            ps.setString(6, title);
            ps.setInt(7, target);
            ps.setTimestamp(8, Timestamp.from(now));
            return ps;
        }, keys);
        Number id = keys.getKey();
        if (id == null) {
            throw new IllegalStateException("No id for the new quest");
        }
        return id.longValue();
    }

    // ---------------------------------------------------------------- what the library allows (generation)

    /** The Member's stage (PRE, ARR, LEAVE, HOME), null when not chosen. */
    String stage(long userId) {
        List<String> stage = jdbc.queryForList("SELECT stage FROM users WHERE id = ?", String.class, userId);
        return stage.isEmpty() ? null : stage.get(0);
    }

    /** Entries of the library (not deleted, both kinds). */
    int libraryCount() {
        return count("SELECT COUNT(*) FROM phrases WHERE deleted_at IS NULL");
    }

    /** Entries the Member does not keep as "Đã thuộc" and that never counted for {@code code} on any day. */
    int notLearnedNeverCounted(long userId, String code) {
        return count("""
                SELECT COUNT(*) FROM phrases ph
                %s
                WHERE ph.deleted_at IS NULL AND NOT COALESCE(m.is_learned, FALSE)
                  AND NOT EXISTS (SELECT 1 FROM user_quest_items i
                                  JOIN user_daily_quests q ON q.id = i.user_daily_quest_id
                                  WHERE i.phrase_id = ph.id AND q.user_id = ? AND q.code = ?)""".formatted(MARK_JOIN),
                userId, userId, code);
    }

    /** Reads the phrase row FOR SHARE (nothing when it does not exist), before the users row is locked. */
    void sharePhrase(long phraseId) {
        jdbc.queryForList("SELECT id FROM phrases WHERE id = ? FOR SHARE", Long.class, phraseId);
    }

    /**
     * Entries not "Đã thuộc" and never counted for this quest code, of one "Luyện tập" status: {@code learning} true =
     * "Chưa thuộc", false = no mark ("new").
     */
    int notCountedFor(long userId, String code, boolean learning) {
        return count("""
                SELECT COUNT(*) FROM phrases ph
                %s
                WHERE ph.deleted_at IS NULL AND NOT COALESCE(m.is_learned, FALSE)
                  AND COALESCE(m.is_learning, FALSE) = ?
                  AND NOT EXISTS (SELECT 1 FROM user_quest_items i
                                  JOIN user_daily_quests q ON q.id = i.user_daily_quest_id
                                  WHERE i.phrase_id = ph.id AND q.user_id = ? AND q.code = ?)""".formatted(MARK_JOIN),
                userId, learning, userId, code);
    }

    /** Entries the Member keeps as "Chưa thuộc" (status "learning" of "Luyện tập"). */
    int learningCount(long userId) {
        return count("SELECT COUNT(*) FROM phrases ph " + MARK_JOIN
                + " WHERE ph.deleted_at IS NULL AND COALESCE(m.is_learning, FALSE)", userId);
    }

    /** Entries without any answer: no row or both marks off (status "new", "Chưa luyện", of "Luyện tập"). */
    int unansweredCount(long userId) {
        return count("SELECT COUNT(*) FROM phrases ph " + MARK_JOIN
                + " WHERE ph.deleted_at IS NULL AND NOT COALESCE(m.is_learning, FALSE)"
                + " AND NOT COALESCE(m.is_learned, FALSE)", userId);
    }

    /** Learning topics holding at least one entry, with their counts for this Member. */
    List<TopicStat> topicStats(long userId) {
        return jdbc.query("""
                SELECT t.id, t.name, COUNT(ph.id), SUM(CASE WHEN COALESCE(m.is_learned, FALSE) THEN 0 ELSE 1 END)
                FROM learning_topics t
                JOIN phrase_topic_links l ON l.topic_id = t.id
                JOIN phrases ph ON ph.id = l.phrase_id AND ph.deleted_at IS NULL
                %s
                GROUP BY t.id, t.name""".formatted(MARK_JOIN),
                (rs, i) -> new TopicStat(rs.getString(1), rs.getString(2), rs.getInt(3), rs.getInt(4)), userId);
    }

    // ---------------------------------------------------------------- counting

    /** When the Member's previous flash card answer was logged (null for none). */
    Instant lastAnswerAt(long userId) {
        Timestamp at = jdbc.queryForObject("SELECT MAX(answered_at) FROM phrase_answer_log WHERE user_id = ?",
                Timestamp.class, userId);
        return at == null ? null : at.toInstant();
    }

    void logAnswer(long userId, long phraseId, boolean learned, Instant at) {
        jdbc.update("INSERT INTO phrase_answer_log (user_id, phrase_id, learned, answered_at) VALUES (?, ?, ?, ?)",
                userId, phraseId, learned, Timestamp.from(at));
    }

    /** Whether this entry ever counted for a quest of this code of the Member, on any day. */
    boolean everCounted(long userId, String code, long phraseId) {
        return count("""
                SELECT COUNT(*) FROM user_quest_items i JOIN user_daily_quests q ON q.id = i.user_daily_quest_id
                WHERE i.phrase_id = ? AND q.user_id = ? AND q.code = ?""", phraseId, userId, code) > 0;
    }

    boolean inTopic(long phraseId, String topicId) {
        return count("SELECT COUNT(*) FROM phrase_topic_links WHERE phrase_id = ? AND topic_id = ?",
                phraseId, topicId) > 0;
    }

    /** Counts the entry for the quest; false when it was counted already (each entry once per quest). */
    boolean countItem(long questId, long phraseId, Instant at) {
        return jdbc.update("""
                INSERT IGNORE INTO user_quest_items (user_daily_quest_id, phrase_id, counted_at) VALUES (?, ?, ?)""",
                questId, phraseId, Timestamp.from(at)) == 1;
    }

    /** One more step, never above the target; the progress after it. */
    int addProgress(long questId) {
        jdbc.update("UPDATE user_daily_quests SET progress = progress + 1 WHERE id = ? AND progress < target", questId);
        Integer progress = jdbc.queryForObject("SELECT progress FROM user_daily_quests WHERE id = ?", Integer.class,
                questId);
        return progress == null ? 0 : progress;
    }

    void complete(long questId, int coins, Instant at) {
        jdbc.update("UPDATE user_daily_quests SET coins = ?, completed_at = ? WHERE id = ? AND completed_at IS NULL",
                coins, Timestamp.from(at), questId);
    }

    // ---------------------------------------------------------------- days done, streak, bonus

    /** The days done between two Japan dates (both included). */
    List<DoneDay> doneDays(long userId, LocalDate from, LocalDate to) {
        return jdbc.query("""
                SELECT id, quest_date, COALESCE(streak, 1) FROM user_quest_days
                WHERE user_id = ? AND quest_date BETWEEN ? AND ? AND all_done_at IS NOT NULL""",
                (rs, i) -> new DoneDay(rs.getLong(1), rs.getObject(2, LocalDate.class), rs.getInt(3)),
                userId, Date.valueOf(from), Date.valueOf(to));
    }

    int bestStreak(long userId) {
        return count("SELECT COALESCE(MAX(streak), 0) FROM user_quest_days WHERE user_id = ? AND all_done_at IS NOT NULL",
                userId);
    }

    /** Marks the day done with its streak (a row already there keeps what it holds); the row as kept. */
    DoneDay markDayDone(long userId, LocalDate day, int streak, Instant at) {
        jdbc.update("""
                INSERT INTO user_quest_days (user_id, quest_date, all_done_at, streak, created_at) VALUES (?, ?, ?, ?, ?)
                ON DUPLICATE KEY UPDATE all_done_at = COALESCE(all_done_at, VALUES(all_done_at)),
                                        streak = COALESCE(streak, VALUES(streak))""",
                userId, Date.valueOf(day), Timestamp.from(at), streak, Timestamp.from(at));
        return jdbc.queryForObject("""
                SELECT id, quest_date, COALESCE(streak, 1) FROM user_quest_days WHERE user_id = ? AND quest_date = ?""",
                (rs, i) -> new DoneDay(rs.getLong(1), rs.getObject(2, LocalDate.class), rs.getInt(3)),
                userId, Date.valueOf(day));
    }

    /** Xu of the all-done bonus received for a day (0 under the daily cap). */
    int bonusReceived(long userId, LocalDate day) {
        return count("""
                SELECT COALESCE(SUM(delta), 0) FROM coin_transactions
                WHERE user_id = ? AND reason = ? AND quest_date = ?""",
                userId, CoinLedger.DAILY_QUEST_ALL, Date.valueOf(day));
    }

    /** A phrase as the quest shows it, even when it was deleted later that day. */
    Optional<PhraseRef> phrase(long phraseId) {
        return jdbc.query("SELECT id, japanese, romaji, meaning_vi FROM phrases WHERE id = ?",
                (rs, i) -> new PhraseRef(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4)),
                phraseId).stream().findFirst();
    }

    // ---------------------------------------------------------------- helpers

    private int count(String sql, Object... args) {
        Integer n = jdbc.queryForObject(sql, Integer.class, args);
        return n == null ? 0 : n;
    }

    private Map<String, Object> params(String text) {
        if (text == null || text.isBlank()) {
            return Map.of();
        }
        try {
            return json.readValue(text, PARAMS);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Unreadable quest params: " + text, e);
        }
    }

    private String toJson(Map<String, Object> params) {
        try {
            return json.writeValueAsString(params == null ? Map.of() : params);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Quest params cannot be written", e);
        }
    }

    private static Instant instant(ResultSet rs, int column) throws SQLException {
        Timestamp at = rs.getTimestamp(column);
        return at == null ? null : at.toInstant();
    }
}
