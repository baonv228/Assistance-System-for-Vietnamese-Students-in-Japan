package com.sep490.vsa.feature.coin;

import com.fasterxml.jackson.databind.JsonNode;
import com.sep490.vsa.common.exception.ApiException;
import com.sep490.vsa.common.live.LiveEvents;
import com.sep490.vsa.common.security.AppUserPrincipal;
import com.sep490.vsa.feature.coin.CoinDtos.CoinLine;
import com.sep490.vsa.feature.coin.CoinDtos.CoinPage;
import com.sep490.vsa.feature.coin.CoinDtos.ExchangeRequest;
import com.sep490.vsa.feature.coin.CoinDtos.ExchangeResult;
import com.sep490.vsa.feature.coin.CoinDtos.Wallet;
import com.sep490.vsa.feature.quest.MemberGuard;
import com.sep490.vsa.feature.user.PointService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

/**
 * What a Member does with "xu" (contract sections 3.5, 5.4, 5.5): turn them into rank points with "Đổi" and read
 * their history. Xu are earned by the daily quests (feature/quest) and kept by {@link CoinLedger}; they never count
 * toward the rank, the featured posts, the order of answers or pins approved without a Moderator, until the Member
 * exchanges them here at a fixed rate ({@code app.coins.rate}, 50 xu = 1 point) and at most {@code app.coins.month-cap}
 * points a Japan month. Members only (role USER, ACTIVE): guests 401, staff MSG18 ({@link MemberGuard}).
 */
@Service
public class CoinService {

    /** Lines of GET /api/me/coins per page, as "Lịch sử điểm". */
    static final int PAGE_SIZE = 20;
    /** Pages are kept within 0–199 like the repo's other lists. */
    static final int MAX_PAGE = 199;

    static final String INVALID_POINTS = "Hãy chọn số điểm muốn đổi (ít nhất 1 điểm).";

    /** How each reason of coin_transactions reads in the history. */
    static final Map<String, String> REASONS = Map.of(
            CoinLedger.DAILY_QUEST, "Nhiệm vụ hằng ngày",
            CoinLedger.DAILY_QUEST_ALL, "Xong mọi nhiệm vụ trong ngày",
            CoinLedger.QUEST_STREAK, "Chuỗi ngày học",
            CoinLedger.EXCHANGE, "Đổi sang điểm cấp bậc",
            CoinLedger.DEV_ADJUST, "Điều chỉnh khi thử");

    /** Where a line leads: the quests (the web sends /quests on to "Nhật ngữ"), or the points on Tôi after an exchange. */
    static final Map<String, String> LINKS = Map.of(
            CoinLedger.DAILY_QUEST, "/quests",
            CoinLedger.DAILY_QUEST_ALL, "/quests",
            CoinLedger.QUEST_STREAK, "/quests",
            CoinLedger.EXCHANGE, "/profile#points");

    private final JdbcTemplate jdbc;
    private final CoinLedger ledger;
    private final PointService points;
    private final MemberGuard guard;
    private final LiveEvents live;

    public CoinService(JdbcTemplate jdbc, CoinLedger ledger, PointService points, MemberGuard guard, LiveEvents live) {
        this.jdbc = jdbc;
        this.ledger = ledger;
        this.points = points;
        this.guard = guard;
        this.live = live;
    }

    /**
     * "Đổi": {@code n} rank points for {@code n × rate} xu, in one transaction. The checks run in the contract's order
     * and stop at the first that fails, each as a 400 on "points": a whole number ≥ 1; the Japan month's cap already
     * reached; more than what is left of it; not enough xu. Then an EXCHANGE row takes the xu and a COIN_EXCHANGE point
     * line (ref COIN = that row) adds the points through {@link PointService}, so they count for the rank like any
     * other point.
     * <p>
     * The users row is locked first (READ COMMITTED, like InterestService and "Xóa tài khoản"), before the balance and
     * the month's exchanges are read: two exchanges at the same moment (a double tap, two phones) run one after the
     * other and never pass the balance or the cap; a quest paying xu meanwhile waits too.
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public ExchangeResult exchange(AppUserPrincipal me, ExchangeRequest request) {
        long userId = guard.lockMember(me);
        long wanted = wantedPoints(request == null ? null : request.points());
        Wallet before = ledger.wallet(userId);
        int cap = before.monthCap();
        int exchanged = before.exchangedThisMonth();
        if (exchanged >= cap) {
            throw pointsError("Bạn đã đổi đủ " + CoinText.number(cap) + " điểm của tháng này. Tháng sau đổi tiếp nhé.");
        }
        if (wanted > cap - exchanged) {
            throw pointsError("Tháng này bạn chỉ còn đổi được " + CoinText.number(cap - exchanged) + " điểm.");
        }
        int n = (int) wanted;
        long cost = (long) n * before.rate();
        if (before.coins() < cost) {
            throw pointsError("Bạn cần " + CoinText.number(cost) + " xu để đổi " + CoinText.number(n)
                    + " điểm, hiện có " + CoinText.number(before.coins()) + " xu.");
        }
        Long exchangeId = ledger.add(userId, (int) -cost, CoinLedger.EXCHANGE, CoinText.exchangeTitle(n), null, null,
                null);
        points.add(userId, n, CoinLedger.POINT_REASON, CoinLedger.POINT_REF, exchangeId);
        Wallet after = ledger.wallet(userId);
        // The Member's other pages and devices read their wallet again
        live.publish(userId, "quest", Map.of());
        return new ExchangeResult(n, (int) cost, before.rankName(), after.rankName(), after);
    }

    /**
     * GET /api/me/coins: the Member's xu changes, newest first (created_at, then id), {@value #PAGE_SIZE} a page,
     * pages kept within 0–{@value #MAX_PAGE}, with the wallet. Each line keeps the title written with it.
     */
    @Transactional(readOnly = true)
    public CoinPage history(AppUserPrincipal me, int page) {
        long userId = guard.member(me);
        int p = Math.max(0, Math.min(page, MAX_PAGE));
        List<CoinLine> items = jdbc.query("""
                SELECT id, delta, reason, title, created_at FROM coin_transactions
                WHERE user_id = ?
                ORDER BY created_at DESC, id DESC
                LIMIT ? OFFSET ?""", (rs, i) -> {
            String reason = rs.getString("reason");
            return new CoinLine(rs.getLong("id"), rs.getInt("delta"), reason, REASONS.getOrDefault(reason, reason),
                    rs.getString("title"), LINKS.get(reason), rs.getTimestamp("created_at").toInstant());
        }, userId, PAGE_SIZE, p * PAGE_SIZE);
        Long total = jdbc.queryForObject("SELECT COUNT(*) FROM coin_transactions WHERE user_id = ?", Long.class, userId);
        return new CoinPage(items, p, PAGE_SIZE, total == null ? 0 : total, ledger.wallet(userId));
    }

    /**
     * The points asked for: only a JSON whole number ≥ 1 ({@code 3}); missing, null, 0, negative, 2.5, 3.0, "3", true,
     * objects and lists are refused. A number too big for a long is kept as {@link Long#MAX_VALUE}: it is over the
     * month's cap anyway, and the cap message is what the Member needs to read.
     */
    private static long wantedPoints(JsonNode value) {
        if (value == null || !value.isIntegralNumber() || value.bigIntegerValue().signum() <= 0) {
            throw pointsError(INVALID_POINTS);
        }
        return value.canConvertToLong() ? value.longValue() : Long.MAX_VALUE;
    }

    private static ApiException pointsError(String message) {
        return ApiException.invalid(message, Map.of("points", message));
    }
}
