package com.sep490.vsa.feature.phrase;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.sql.Date;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Optional;

/**
 * "Câu tiếng Nhật hôm nay": one phrase (kind PHRASE) for everyone on a Japan day, shown on the Home (guest and member)
 * and listened to by the quest PHRASE_OF_DAY. The phrase is chosen the first time a day needs it (the day number
 * modulo the phrases not deleted, in id order: the rule the Home always used) and kept in {@code daily_phrases}, so it
 * stays the same all day even when the Academic Manager adds or deletes phrases meanwhile. A kept phrase deleted later
 * that day is still answered, so the Home and the quest keep agreeing.
 * <p>
 * Joins the caller's transaction, so a request never holds two pooled connections (with REQUIRES_NEW, ten requests
 * each holding one connection and waiting for a second stalled the whole pool). A read-write caller (the quests, or
 * no transaction at all) keeps the day's row at once; a read-only caller (the Home) cannot write, so
 * {@code HomeController} keeps it just before the Home's read-only transaction starts, and if the day still has no row
 * (a request crossing midnight) the read-only caller gets the phrase the rule chooses without keeping it. Two first
 * requests of a day at the same moment both try to insert; the primary key keeps one and both read it back.
 */
@Service
public class PhraseOfDayService {

    public static final ZoneId JAPAN = ZoneId.of("Asia/Tokyo");

    /** The phrase of a day, with what the Home shows. {@code topicId}: one learning topic (null when none). */
    public record PhraseOfDay(long id, String japanese, String romaji, String meaningVi, String categoryId,
                              String categoryName, String categoryIcon, String topicId) {
    }

    private final JdbcTemplate jdbc;
    private final Clock clock;

    public PhraseOfDayService(JdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    /** Today's Japan date by the application clock. */
    public LocalDate today() {
        return LocalDate.now(clock.withZone(JAPAN));
    }

    /**
     * The phrase of today (Japan date); empty when the library holds no phrase. Called without a transaction (as
     * {@code HomeController} does before the Home), it keeps the day's row in a short transaction of its own.
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Optional<PhraseOfDay> phraseOfToday() {
        return find(today());
    }

    /** The phrase of a Japan day, chosen and kept on first need; empty when the library holds no phrase. */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Optional<PhraseOfDay> phraseOf(LocalDate day) {
        return find(day);
    }

    private Optional<PhraseOfDay> find(LocalDate day) {
        Long id = stored(day);
        if (id == null) {
            Long chosen = choose(day);
            if (chosen == null) {
                return Optional.empty();
            }
            if (TransactionSynchronizationManager.isCurrentTransactionReadOnly()) {
                return read(chosen);            // the Home: its controller kept the day's row just before
            }
            jdbc.update("INSERT IGNORE INTO daily_phrases (phrase_date, phrase_id, created_at) VALUES (?, ?, ?)",
                    Date.valueOf(day), chosen, Timestamp.from(clock.instant()));
            id = stored(day);
            if (id == null) {                   // the chosen phrase was removed for good at this very moment
                return Optional.empty();
            }
        }
        return read(id);
    }

    private Long stored(LocalDate day) {
        return jdbc.query("SELECT phrase_id FROM daily_phrases WHERE phrase_date = ?", (rs, i) -> rs.getLong(1),
                Date.valueOf(day)).stream().findFirst().orElse(null);
    }

    /** The rule the Home used before phrases were kept: day number modulo the phrases not deleted, in id order. */
    private Long choose(LocalDate day) {
        Long total = jdbc.queryForObject("SELECT COUNT(*) FROM phrases WHERE kind = 'PHRASE' AND deleted_at IS NULL",
                Long.class);
        if (total == null || total == 0) {
            return null;
        }
        long offset = Math.floorMod(day.toEpochDay(), total);
        return jdbc.query("""
                SELECT id FROM phrases WHERE kind = 'PHRASE' AND deleted_at IS NULL ORDER BY id LIMIT 1 OFFSET ?""",
                (rs, i) -> rs.getLong(1), offset).stream().findFirst().orElse(null);
    }

    private Optional<PhraseOfDay> read(long phraseId) {
        return jdbc.query("""
                SELECT ph.id, ph.japanese, ph.romaji, ph.meaning_vi, c.id, c.name_vi, c.icon,
                       (SELECT MIN(l.topic_id) FROM phrase_topic_links l WHERE l.phrase_id = ph.id)
                FROM phrases ph JOIN place_categories c ON c.id = ph.category_id
                WHERE ph.id = ?""",
                (rs, i) -> new PhraseOfDay(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4),
                        rs.getString(5), rs.getString(6), rs.getString(7), rs.getString(8)), phraseId)
                .stream().findFirst();
    }
}
