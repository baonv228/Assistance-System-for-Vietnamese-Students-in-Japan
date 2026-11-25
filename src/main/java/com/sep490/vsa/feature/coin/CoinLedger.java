package com.sep490.vsa.feature.coin;

import com.sep490.vsa.feature.coin.CoinDtos.Wallet;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Date;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;

/**
 * The book of "xu" ({@code coin_transactions}) and the balance kept with it ({@code users.coins}, never below 0), like
 * {@code PointService} for rank points. Xu never count toward the rank; only an exchange (CoinService) turns them into
 * rank points, recorded in {@code point_transactions} with reason {@link #POINT_REASON}.
 * <p>
 * Callers lock the Member's {@code users} row first (FOR UPDATE), then call {@link #add}, so two awards or an award and
 * an exchange of the same Member run one after the other.
 */
@Service
public class CoinLedger {

    public static final ZoneId JAPAN = ZoneId.of("Asia/Tokyo");

    /** Reasons of coin_transactions. */
    public static final String DAILY_QUEST = "DAILY_QUEST";
    public static final String DAILY_QUEST_ALL = "DAILY_QUEST_ALL";
    public static final String QUEST_STREAK = "QUEST_STREAK";
    public static final String EXCHANGE = "EXCHANGE";
    public static final String DEV_ADJUST = "DEV_ADJUST";

    /** ref_type of a DAILY_QUEST row (ref_id = user_daily_quests.id). */
    public static final String REF_QUEST = "QUEST";
    /** ref_type of DAILY_QUEST_ALL and QUEST_STREAK rows (ref_id = user_quest_days.id). */
    public static final String REF_QUEST_DAY = "QUEST_DAY";

    /** Reason of the point_transactions row of an exchange; its ref_type is {@link #POINT_REF} (ref_id = the EXCHANGE row). */
    public static final String POINT_REASON = "COIN_EXCHANGE";
    public static final String POINT_REF = "COIN";

    private static final String DEFAULT_RANK = "Người mới";

    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final CoinSettings settings;

    public CoinLedger(JdbcTemplate jdbc, Clock clock, CoinSettings settings) {
        this.jdbc = jdbc;
        this.clock = clock;
        this.settings = settings;
    }

    /**
     * Records one change of xu and moves the balance (never below 0). Returns the id of the new row, or {@code null}
     * when nothing was written: {@code delta} 0, or a row with the same (user, reason, refType, refId) exists already
     * (a quest, a day bonus or a streak milestone pays once, whatever runs at the same moment).
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public Long add(long userId, int delta, String reason, String title, String refType, Long refId,
                    LocalDate questDate) {
        if (delta == 0) {
            return null;
        }
        String shortTitle = title == null ? "" : title.length() <= 160 ? title : title.substring(0, 159) + "…";
        Long id;
        try {
            jdbc.update("""
                    INSERT INTO coin_transactions (user_id, delta, reason, title, ref_type, ref_id, quest_date, created_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?)""", userId, delta, reason, shortTitle, refType, refId,
                    questDate == null ? null : Date.valueOf(questDate),
                    Timestamp.from(clock.instant().truncatedTo(ChronoUnit.SECONDS)));   // DATETIME: cut, never rounded up
            id = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        } catch (DuplicateKeyException alreadyPaid) {
            return null;
        }
        jdbc.update("UPDATE users SET coins = GREATEST(0, coins + ?) WHERE id = ?", delta, userId);
        return id;
    }

    /** The Member's balance of xu (0 for an unknown id). */
    @Transactional(readOnly = true)
    public int balance(long userId) {
        return jdbc.query("SELECT coins FROM users WHERE id = ?", (rs, i) -> rs.getInt(1), userId)
                .stream().findFirst().orElse(0);
    }

    /** Xu of quests and the all-done bonus received on one Japan day (DAILY_QUEST + DAILY_QUEST_ALL), for the daily cap. */
    @Transactional(readOnly = true)
    public int questCoinsOn(long userId, LocalDate questDate) {
        Integer sum = jdbc.queryForObject("""
                SELECT COALESCE(SUM(delta), 0) FROM coin_transactions
                WHERE user_id = ? AND quest_date = ? AND reason IN ('DAILY_QUEST', 'DAILY_QUEST_ALL')""",
                Integer.class, userId, Date.valueOf(questDate));
        return sum == null ? 0 : sum;
    }

    /** Rank points received from xu in the current Japan month (sum of the COIN_EXCHANGE point lines). */
    @Transactional(readOnly = true)
    public int exchangedThisMonth(long userId) {
        YearMonth month = YearMonth.now(clock.withZone(JAPAN));
        Instant from = month.atDay(1).atStartOfDay(JAPAN).toInstant();
        Instant to = month.plusMonths(1).atDay(1).atStartOfDay(JAPAN).toInstant();
        Integer sum = jdbc.queryForObject("""
                SELECT COALESCE(SUM(delta), 0) FROM point_transactions
                WHERE user_id = ? AND reason = ? AND created_at >= ? AND created_at < ?""",
                Integer.class, userId, POINT_REASON, Timestamp.from(from), Timestamp.from(to));
        return sum == null ? 0 : sum;
    }

    /** 0:00 Japan time on the 1st of next month, when the monthly cap of exchanges starts again. */
    public Instant monthResetAt() {
        return YearMonth.now(clock.withZone(JAPAN)).plusMonths(1).atDay(1).atStartOfDay(JAPAN).toInstant();
    }

    /** The rank a number of points gives (same rule as the member Home's rankName). */
    @Transactional(readOnly = true)
    public String rankName(int points) {
        return jdbc.query("SELECT name FROM ranks WHERE min_points <= ? ORDER BY min_points DESC LIMIT 1",
                (rs, i) -> rs.getString(1), points).stream().findFirst().orElse(DEFAULT_RANK);
    }

    /** The Member's wallet as the API shows it (contract 5.0). */
    @Transactional(readOnly = true)
    public Wallet wallet(long userId) {
        int[] row = jdbc.query("SELECT coins, points FROM users WHERE id = ?",
                (rs, i) -> new int[] {rs.getInt(1), rs.getInt(2)}, userId).stream().findFirst().orElse(new int[] {0, 0});
        int coins = row[0];
        int points = row[1];
        int rate = Math.max(1, settings.getRate());
        int cap = Math.max(0, settings.getMonthCap());
        int exchanged = exchangedThisMonth(userId);
        int exchangeable = Math.max(0, Math.min(coins / rate, cap - exchanged));
        return new Wallet(coins, rate, cap, exchanged, exchangeable, monthResetAt(), points, rankName(points));
    }
}
