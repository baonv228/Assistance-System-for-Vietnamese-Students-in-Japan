package com.sep490.vsa.feature.quest.dev;

import com.fasterxml.jackson.databind.JsonNode;
import com.sep490.vsa.common.exception.ApiException;
import com.sep490.vsa.common.live.LiveEvents;
import com.sep490.vsa.common.security.AppUserPrincipal;
import com.sep490.vsa.feature.coin.CoinDtos.Wallet;
import com.sep490.vsa.feature.coin.CoinLedger;
import com.sep490.vsa.feature.quest.MemberGuard;
import com.sep490.vsa.feature.quest.QuestDtos.Today;
import com.sep490.vsa.feature.quest.QuestService;
import com.sep490.vsa.feature.quest.QuestSettings;
import com.sep490.vsa.feature.quest.dev.DevQuestDtos.History;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Conditional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Date;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The test-only API of "Nhiệm vụ hằng ngày" (contract 5.9), so the mobile team and the end-to-end tests can play a
 * scenario again and again: clear today's quests, make the days done before today, force today's quests, set the xu.
 * Registered only when {@link DevApiGuard} allows it (the flag AND a database on this machine), never on the shared
 * database.
 * <p>
 * Every call acts on the signed-in Member (role USER, ACTIVE; guests 401, staff MSG18 through {@link MemberGuard}) and
 * on today's Japan date of the application clock, in one READ COMMITTED transaction that locks the Member's users row
 * first, like every write of quests and xu: a flash card answer or an exchange of the same Member waits for it. A call
 * refused with 400 changes nothing (the whole transaction is rolled back).
 */
@Service
@Conditional(DevApiGuard.On.class)
public class DevQuestService {

    private static final Logger log = LoggerFactory.getLogger(DevQuestService.class);

    static final int MAX_DONE_DAYS = 365;
    static final int MAX_COINS = 1_000_000;
    static final String DONE_DAYS_INVALID = "Số ngày đã xong phải là số nguyên từ 0 đến 365.";
    static final String COINS_INVALID = "Số xu phải là số nguyên từ 0 đến 1.000.000.";
    static final String CLEAR_INVALID = "Giá trị clearMonthExchanges phải là true hoặc false.";
    /** Title of the DEV_ADJUST row that sets the balance (contract 5.9). */
    static final String ADJUST_TITLE = "Điều chỉnh khi thử";
    /** Type of the notification of a streak milestone (written by QuestService). */
    static final String STREAK_NOTIFICATION = "QUEST_STREAK";

    private final JdbcTemplate jdbc;
    private final MemberGuard guard;
    private final QuestService quests;
    private final QuestSettings settings;
    private final CoinLedger ledger;
    private final LiveEvents live;
    private final Clock clock;

    public DevQuestService(JdbcTemplate jdbc, MemberGuard guard, QuestService quests, QuestSettings settings,
                           CoinLedger ledger, LiveEvents live, Clock clock) {
        this.jdbc = jdbc;
        this.guard = guard;
        this.quests = quests;
        this.settings = settings;
        this.ledger = ledger;
        this.live = live;
        this.clock = clock;
        log.info("Test-only API /api/dev/quests/** and /api/dev/coins is ON ({}=true, database on this machine).",
                DevApiGuard.FLAG);
    }

    // ---------------------------------------------------------------- DELETE /api/dev/quests/today

    /**
     * Forgets today's quests of the Member ({@link #clearDay}); the next {@code GET /api/me/quests/today} makes a new
     * set. Returns the wallet after it.
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Wallet clearToday(AppUserPrincipal me) {
        long userId = guard.lockMember(me);
        clearDay(userId, today());
        changed(userId);
        return ledger.wallet(userId);
    }

    // ---------------------------------------------------------------- POST /api/dev/quests/history

    /**
     * Replaces the Member's days before today by {@code doneDays} days done right before today (today−N … today−1,
     * all done at noon Japan time, streak 1 … N, oldest 1), without quests and without xu: with 6, finishing today's
     * quests reaches the 7-day milestone. 0 only removes the days before today. Today's row is kept as it is: the call is
     * meant before today's quests are done (a day already done keeps the streak it was given).
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public History history(AppUserPrincipal me, JsonNode doneDaysValue) {
        long userId = guard.lockMember(me);
        Map<String, String> errors = new LinkedHashMap<>();
        Integer doneDays = wholeNumber(doneDaysValue, MAX_DONE_DAYS, "doneDays", DONE_DAYS_INVALID, errors);
        refuse(errors);
        LocalDate day = today();
        jdbc.update("DELETE FROM user_quest_days WHERE user_id = ? AND quest_date < ?", userId, Date.valueOf(day));
        Timestamp now = Timestamp.from(clock.instant());
        List<Object[]> rows = new ArrayList<>();
        for (int streak = 1; streak <= doneDays; streak++) {
            LocalDate date = day.minusDays(doneDays - streak + 1L);
            Instant noon = date.atTime(LocalTime.NOON).atZone(settings.zoneId()).toInstant();
            rows.add(new Object[] {userId, Date.valueOf(date), Timestamp.from(noon), streak, now});
        }
        if (!rows.isEmpty()) {
            jdbc.batchUpdate("""
                    INSERT INTO user_quest_days (user_id, quest_date, all_done_at, streak, created_at)
                    VALUES (?, ?, ?, ?, ?)""", rows);
        }
        changed(userId);
        return new History(doneDays, currentStreak(userId, day));
    }

    // ---------------------------------------------------------------- PUT /api/dev/quests/today

    /**
     * Today's quests made from exactly these codes, the fixed quest first when listed (contract 5.9): today is cleared
     * as by {@link #clearToday}, then {@link QuestService#forceToday} makes the set (targets lowered to the library as
     * usual). An unknown code, or one the Member cannot do now, answers 400 on {@code codes} naming it, and nothing
     * changes (the clearing is rolled back too). Returns the same as {@code GET /api/me/quests/today}.
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Today forceToday(AppUserPrincipal me, JsonNode codesValue) {
        long userId = guard.lockMember(me);
        clearDay(userId, today());
        // forceToday joins this transaction and answers the day as GET /api/me/quests/today does (QuestService.today)
        return quests.forceToday(me, codes(codesValue));
    }

    // ---------------------------------------------------------------- PUT /api/dev/coins

    /**
     * Sets the Member's balance of xu to {@code coins} with ONE row DEV_ADJUST "Điều chỉnh khi thử" (delta = the
     * difference; no row when there is none), so the balance still equals the sum of the book. With
     * {@code clearMonthExchanges} the exchanges of this Japan month are undone first ({@link #clearMonthExchanges}), so
     * the month's cap is free again. Returns the wallet after it.
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Wallet setCoins(AppUserPrincipal me, JsonNode coinsValue, JsonNode clearValue) {
        long userId = guard.lockMember(me);
        Map<String, String> errors = new LinkedHashMap<>();
        Integer coins = wholeNumber(coinsValue, MAX_COINS, "coins", COINS_INVALID, errors);
        boolean clear = false;
        if (clearValue != null && !clearValue.isNull()) {
            if (clearValue.isBoolean()) {
                clear = clearValue.booleanValue();
            } else {
                errors.put("clearMonthExchanges", CLEAR_INVALID);
            }
        }
        refuse(errors);
        if (clear) {
            clearMonthExchanges(userId);
        }
        Integer balance = jdbc.queryForObject("SELECT coins FROM users WHERE id = ?", Integer.class, userId);
        ledger.add(userId, coins - (balance == null ? 0 : balance), CoinLedger.DEV_ADJUST, ADJUST_TITLE, null, null,
                null);
        changed(userId);
        return ledger.wallet(userId);
    }

    // ---------------------------------------------------------------- the work

    /**
     * Everything today's quests left, as if the Member had not studied today: the quests (their counted entries go by
     * cascade), the day done, the xu they paid (DAILY_QUEST, DAILY_QUEST_ALL and QUEST_STREAK rows of that quest_date,
     * taken off users.coins, never below 0, which also frees the daily cap) and the streak milestone's notification.
     * Flash card marks, the answer log, the phrase of the day, exchanges and other days are kept.
     */
    private void clearDay(long userId, LocalDate day) {
        Date date = Date.valueOf(day);
        List<Long> dayRow = jdbc.queryForList("SELECT id FROM user_quest_days WHERE user_id = ? AND quest_date = ?",
                Long.class, userId, date);
        Object[] questCoins = {userId, date, CoinLedger.DAILY_QUEST, CoinLedger.DAILY_QUEST_ALL, CoinLedger.QUEST_STREAK};
        Integer paid = jdbc.queryForObject("""
                SELECT COALESCE(SUM(delta), 0) FROM coin_transactions
                WHERE user_id = ? AND quest_date = ? AND reason IN (?, ?, ?)""", Integer.class, questCoins);
        jdbc.update("DELETE FROM coin_transactions WHERE user_id = ? AND quest_date = ? AND reason IN (?, ?, ?)",
                questCoins);
        if (paid != null && paid != 0) {
            jdbc.update("UPDATE users SET coins = GREATEST(0, coins - ?) WHERE id = ?", paid, userId);
        }
        for (long id : dayRow) {
            jdbc.update("DELETE FROM notifications WHERE user_id = ? AND type = ? AND ref_type = ? AND ref_id = ?",
                    userId, STREAK_NOTIFICATION, CoinLedger.REF_QUEST, id);
        }
        jdbc.update("DELETE FROM user_daily_quests WHERE user_id = ? AND quest_date = ?", userId, date);
        jdbc.update("DELETE FROM user_quest_days WHERE user_id = ? AND quest_date = ?", userId, date);
    }

    /**
     * Undoes the Member's exchanges of this Japan month: the point lines COIN_EXCHANGE of the month (their points taken
     * off users.points, never below 0) and the EXCHANGE rows of xu they point at (ref COIN = that row; their xu given
     * back). Exchanges of earlier months stay.
     */
    private void clearMonthExchanges(long userId) {
        YearMonth month = YearMonth.now(clock.withZone(CoinLedger.JAPAN));
        Timestamp from = Timestamp.from(month.atDay(1).atStartOfDay(CoinLedger.JAPAN).toInstant());
        Timestamp to = Timestamp.from(month.plusMonths(1).atDay(1).atStartOfDay(CoinLedger.JAPAN).toInstant());
        record Line(long id, int points, String refType, Long refId) {
        }
        List<Line> lines = jdbc.query("""
                SELECT id, delta, ref_type, ref_id FROM point_transactions
                WHERE user_id = ? AND reason = ? AND created_at >= ? AND created_at < ?""",
                (rs, i) -> new Line(rs.getLong(1), rs.getInt(2), rs.getString(3), rs.getObject(4, Long.class)),
                userId, CoinLedger.POINT_REASON, from, to);
        int points = 0;
        int coins = 0;
        for (Line line : lines) {
            points += line.points();
            jdbc.update("DELETE FROM point_transactions WHERE id = ?", line.id());
            if (line.refId() == null || !CoinLedger.POINT_REF.equals(line.refType())) {
                continue;
            }
            List<Integer> spent = jdbc.queryForList(
                    "SELECT delta FROM coin_transactions WHERE id = ? AND user_id = ? AND reason = ?", Integer.class,
                    line.refId(), userId, CoinLedger.EXCHANGE);
            if (!spent.isEmpty()) {
                jdbc.update("DELETE FROM coin_transactions WHERE id = ?", line.refId());
                coins -= spent.get(0);
            }
        }
        if (points != 0) {
            jdbc.update("UPDATE users SET points = GREATEST(0, points - ?) WHERE id = ?", points, userId);
        }
        if (coins != 0) {
            jdbc.update("UPDATE users SET coins = GREATEST(0, coins + ?) WHERE id = ?", coins, userId);
        }
    }

    /** The streak of contract 3.6: today's when today is done, else yesterday's when yesterday is done, else 0. */
    private int currentStreak(long userId, LocalDate day) {
        Map<LocalDate, Integer> done = new LinkedHashMap<>();
        jdbc.query("""
                SELECT quest_date, COALESCE(streak, 1) FROM user_quest_days
                WHERE user_id = ? AND quest_date BETWEEN ? AND ? AND all_done_at IS NOT NULL""",
                (rs, i) -> done.put(rs.getObject(1, LocalDate.class), rs.getInt(2)),
                userId, Date.valueOf(day.minusDays(1)), Date.valueOf(day));
        return done.getOrDefault(day, done.getOrDefault(day.minusDays(1), 0));
    }

    // ---------------------------------------------------------------- reading the request

    /**
     * A JSON whole number between 0 and {@code max}; anything else (missing, null, text, 2.5, true…) is put in
     * {@code errors} on {@code field}.
     */
    private static Integer wholeNumber(JsonNode value, int max, String field, String message,
                                       Map<String, String> errors) {
        if (value == null || !value.isIntegralNumber() || !value.canConvertToInt()
                || value.intValue() < 0 || value.intValue() > max) {
            errors.put(field, message);
            return null;
        }
        return value.intValue();
    }

    /** The codes as given; a missing or non-list value is no code at all (QuestService answers on "codes"). */
    private static List<String> codes(JsonNode value) {
        List<String> codes = new ArrayList<>();
        if (value != null && value.isArray()) {
            value.forEach(code -> codes.add(code.isTextual() ? code.asText() : code.toString()));
        }
        return codes;
    }

    private static void refuse(Map<String, String> errors) {
        if (!errors.isEmpty()) {
            throw ApiException.invalid(errors.values().iterator().next(), errors);
        }
    }

    private LocalDate today() {
        return LocalDate.now(clock.withZone(settings.zoneId()));
    }

    /** The Member's other pages and devices read their quests and wallet again. */
    private void changed(long userId) {
        live.publish(userId, "quest", Map.of());
    }
}
