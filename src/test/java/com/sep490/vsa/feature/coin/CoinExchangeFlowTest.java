package com.sep490.vsa.feature.coin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.IntNode;
import com.sep490.vsa.common.exception.ApiException;
import com.sep490.vsa.feature.coin.CoinDtos.ExchangeRequest;
import com.sep490.vsa.feature.quest.MemberGuard;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * "Đổi" xu into rank points (contract 3.5, 5.4): the exchange itself, its 400 messages in the contract's order, the
 * Japan month's cap, exchanges at the same moment and who may exchange.
 */
@TestPropertySource(properties = "spring.datasource.url=jdbc:mysql://localhost:3306/asvsj_coin_test?createDatabaseIfNotExist=true&useUnicode=true&characterEncoding=utf8&connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true")
class CoinExchangeFlowTest extends CoinTestSupport {

    @Test
    void exchangingThreePointsSpends150XuAndAddsThreeRankPoints() throws Exception {
        LocalDate day = freshDay();
        long user = member();
        give(user, 340);
        Instant now = clock.instant();

        JsonNode answer = json(exchange(user, 3)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.message").value("Đã đổi 150 xu lấy 3 điểm.")));
        JsonNode data = answer.get("data");
        assertThat(fieldNames(data)).containsExactlyInAnyOrder("pointsAdded", "coinsSpent", "rankBefore", "rankAfter",
                "wallet");
        assertThat(data.get("pointsAdded").asInt()).isEqualTo(3);
        assertThat(data.get("coinsSpent").asInt()).isEqualTo(150);
        assertThat(data.get("rankBefore").asText()).isEqualTo("Người mới");
        assertThat(data.get("rankAfter").asText()).isEqualTo("Người mới");
        JsonNode wallet = data.get("wallet");
        assertThat(fieldNames(wallet)).containsExactlyInAnyOrder("coins", "rate", "monthCap", "exchangedThisMonth",
                "exchangeableNow", "monthResetAt", "points", "rankName");
        assertThat(wallet.get("coins").asInt()).isEqualTo(190);
        assertThat(wallet.get("rate").asInt()).isEqualTo(50);
        assertThat(wallet.get("monthCap").asInt()).isEqualTo(10);
        assertThat(wallet.get("exchangedThisMonth").asInt()).isEqualTo(3);
        assertThat(wallet.get("exchangeableNow").asInt()).isEqualTo(3);          // min(190 / 50, 10 − 3)
        assertThat(wallet.get("monthResetAt").asText()).isEqualTo(monthResetAfter(day).toString());
        assertThat(wallet.get("monthResetAt").asText()).endsWith("T15:00:00Z");
        assertThat(wallet.get("points").asInt()).isEqualTo(3);
        assertThat(wallet.get("rankName").asText()).isEqualTo("Người mới");

        assertThat(coinsOf(user)).isEqualTo(190);
        assertThat(pointsOf(user)).isEqualTo(3);
        // One EXCHANGE row in the book of xu, without a reference or a quest date
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT id, delta, title, ref_type, ref_id, quest_date, created_at FROM coin_transactions
                WHERE user_id = ? AND reason = 'EXCHANGE'""", user);
        assertThat(rows).hasSize(1);
        Map<String, Object> row = rows.get(0);
        assertThat(row.get("delta")).isEqualTo(-150);
        assertThat(row.get("title")).isEqualTo("Đổi lấy 3 điểm");
        assertThat(row.get("ref_type")).isNull();
        assertThat(row.get("ref_id")).isNull();
        assertThat(row.get("quest_date")).isNull();
        long exchangeId = ((Number) row.get("id")).longValue();
        // One point line COIN_EXCHANGE / COIN pointing at that row
        List<Map<String, Object>> lines = jdbc.queryForList(
                "SELECT delta, reason, ref_type, ref_id, created_at FROM point_transactions WHERE user_id = ?", user);
        assertThat(lines).hasSize(1);
        assertThat(lines.get(0).get("delta")).isEqualTo(3);
        assertThat(lines.get(0).get("reason")).isEqualTo("COIN_EXCHANGE");
        assertThat(lines.get(0).get("ref_type")).isEqualTo("COIN");
        assertThat(((Number) lines.get(0).get("ref_id")).longValue()).isEqualTo(exchangeId);
        assertThat(jdbc.queryForObject("SELECT created_at FROM point_transactions WHERE user_id = ?",
                java.sql.Timestamp.class, user).toInstant()).isEqualTo(now);
        // Balances follow both books
        assertThat(coinsOf(user)).isEqualTo(coinBookSum(user));
        assertThat(pointsOf(user)).isEqualTo(pointBookSum(user));
    }

    @Test
    void reaching100PointsChangesTheRankAndTheProfileShowsIt() throws Exception {
        freshDay();
        long user = member();
        jdbc.update("UPDATE users SET points = 98 WHERE id = ?", user);
        give(user, 350);

        exchange(user, 3)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Đã đổi 150 xu lấy 3 điểm."))
                .andExpect(jsonPath("$.data.pointsAdded").value(3))
                .andExpect(jsonPath("$.data.coinsSpent").value(150))
                .andExpect(jsonPath("$.data.rankBefore").value("Người mới"))
                .andExpect(jsonPath("$.data.rankAfter").value("Người chỉ đường"))
                .andExpect(jsonPath("$.data.wallet.coins").value(200))
                .andExpect(jsonPath("$.data.wallet.exchangedThisMonth").value(3))
                .andExpect(jsonPath("$.data.wallet.exchangeableNow").value(4))
                .andExpect(jsonPath("$.data.wallet.points").value(101))
                .andExpect(jsonPath("$.data.wallet.rankName").value("Người chỉ đường"));

        // "Tôi" shows the new points and the xu left, and keeps every field it had
        JsonNode profile = json(getAs(user, "/api/me/profile").andExpect(status().isOk())).get("data");
        assertThat(profile.get("points").asInt()).isEqualTo(101);
        assertThat(profile.get("coins").asInt()).isEqualTo(200);
        assertThat(fieldNames(profile)).contains("id", "email", "displayName", "avatarUrl", "role", "roleLabel",
                "points", "coins", "ranks", "tags", "pendingTag", "areaId", "areaName", "stage", "createdAt",
                "accountAgeDays", "newAccount", "newAccountDays", "today", "reportMutedUntil", "hasPassword",
                "linkedProviders", "savedCount");

        // One more point: the next exchange starts from the new rank
        exchange(user, 1)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.rankBefore").value("Người chỉ đường"))
                .andExpect(jsonPath("$.data.rankAfter").value("Người chỉ đường"));
    }

    @Test
    void anAmountThatIsNotAWholeNumberOfAtLeastOneIsRefusedOnPoints() throws Exception {
        freshDay();
        long user = member();
        give(user, 1000);
        List<String> bodies = List.of("{}", "{\"points\":null}", "{\"points\":0}", "{\"points\":-1}",
                "{\"points\":2.5}", "{\"points\":3.0}", "{\"points\":1e1}", "{\"points\":\"3\"}", "{\"points\":true}",
                "{\"points\":{}}", "{\"points\":[3]}", "{\"amount\":3}", "[3]", "3", "\"3\"", "null",
                "{\"points\":-99999999999999999999}");
        for (String body : bodies) {
            JsonNode answer = json(exchange(user, body).andExpect(status().isBadRequest()));
            assertThat(answer.get("success").asBoolean()).as(body).isFalse();
            assertThat(answer.get("message").asText()).as(body).isEqualTo(INVALID);
            assertThat(fieldNames(answer.get("data"))).as(body).containsExactly("points");
            assertThat(answer.get("data").get("points").asText()).as(body).isEqualTo(INVALID);
        }
        exchangeWithoutBody(user)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(INVALID))
                .andExpect(jsonPath("$.data.points").value(INVALID));

        // Nothing was exchanged
        assertThat(coinsOf(user)).isEqualTo(1000);
        assertThat(pointsOf(user)).isZero();
        assertThat(count("SELECT COUNT(*) FROM coin_transactions WHERE user_id = ? AND reason = 'EXCHANGE'", user))
                .isZero();
        assertThat(count("SELECT COUNT(*) FROM point_transactions WHERE user_id = ?", user)).isZero();
    }

    @Test
    void theChecksRunInTheContractsOrder() throws Exception {
        freshDay();
        // The contract's example of too few xu
        long poor = member();
        give(poor, 120);
        expectPointsError(exchange(poor, 3), "Bạn cần 150 xu để đổi 3 điểm, hiện có 120 xu.");

        long user = member();
        give(user, 400);
        exchange(user, 8).andExpect(status().isOk());                       // 0 xu left, 8 points this month
        // More than what is left of the cap: said before the missing xu
        expectPointsError(exchange(user, 3), "Tháng này bạn chỉ còn đổi được 2 điểm.");
        expectPointsError(exchange(user, 2), "Bạn cần 100 xu để đổi 2 điểm, hiện có 0 xu.");
        give(user, 120);
        expectPointsError(exchange(user, 3), "Tháng này bạn chỉ còn đổi được 2 điểm.");
        exchange(user, 2).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.wallet.exchangedThisMonth").value(10))
                .andExpect(jsonPath("$.data.wallet.exchangeableNow").value(0));
        // Cap reached: said before the missing xu (20 xu left), and still with enough xu
        expectPointsError(exchange(user, 1), "Bạn đã đổi đủ 10 điểm của tháng này. Tháng sau đổi tiếp nhé.");
        give(user, 1000);
        expectPointsError(exchange(user, 1), "Bạn đã đổi đủ 10 điểm của tháng này. Tháng sau đổi tiếp nhé.");
        // An invalid amount is said first of all
        expectPointsError(exchange(user, 0), INVALID);
        expectPointsError(exchange(user, "{\"points\":2.5}"), INVALID);

        assertThat(coinsOf(user)).isEqualTo(400 - 400 + 120 - 100 + 1000);
        assertThat(pointsOf(user)).isEqualTo(10);
        assertThat(coinsOf(user)).isEqualTo(coinBookSum(user));
        assertThat(pointsOf(user)).isEqualTo(pointBookSum(user));
    }

    @Test
    void bigNumbersAreWrittenWithAThousandsDot() throws Exception {
        assertThat(CoinText.number(0)).isEqualTo("0");
        assertThat(CoinText.number(999)).isEqualTo("999");
        assertThat(CoinText.number(1000)).isEqualTo("1.000");
        assertThat(CoinText.number(1_234_567)).isEqualTo("1.234.567");
        assertThat(CoinText.number(-1500)).isEqualTo("-1.500");

        freshDay();
        long user = member();
        give(user, 1234);
        int cap = coinSettings.getMonthCap();
        coinSettings.setMonthCap(100);
        try {
            expectPointsError(exchange(user, 25), "Bạn cần 1.250 xu để đổi 25 điểm, hiện có 1.234 xu.");
            // A number too big for any integer type is still a whole number: over the cap
            expectPointsError(exchange(user, "{\"points\":99999999999999999999}"),
                    "Tháng này bạn chỉ còn đổi được 100 điểm.");
            give(user, 16);
            exchange(user, 25)
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.message").value("Đã đổi 1.250 xu lấy 25 điểm."))
                    .andExpect(jsonPath("$.data.coinsSpent").value(1250))
                    .andExpect(jsonPath("$.data.wallet.monthCap").value(100));
            assertThat(jdbc.queryForObject(
                    "SELECT title FROM coin_transactions WHERE user_id = ? AND reason = 'EXCHANGE'", String.class, user))
                    .isEqualTo("Đổi lấy 25 điểm");
            getAs(user, "/api/me/points")
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.items[0].title").value("Đổi 1.250 xu"));
        } finally {
            coinSettings.setMonthCap(cap);
        }
    }

    @Test
    void theMonthsCapStartsAgainAtMidnightJapanTimeOnThe1st() throws Exception {
        LocalDate last = YearMonth.from(freshDay()).atEndOfMonth();
        setJapanTime(last, LocalTime.of(23, 59, 59));
        long user = member();
        give(user, 1000);

        exchange(user, 10)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.wallet.exchangedThisMonth").value(10))
                .andExpect(jsonPath("$.data.wallet.exchangeableNow").value(0))
                .andExpect(jsonPath("$.data.wallet.monthResetAt").value(monthResetAfter(last).toString()));
        expectPointsError(exchange(user, 1), "Bạn đã đổi đủ 10 điểm của tháng này. Tháng sau đổi tiếp nhé.");

        // 00:00:00 Japan time on the 1st (still the previous day in UTC): a new month
        LocalDate first = last.plusDays(1);
        setJapanTime(first, LocalTime.MIDNIGHT);
        exchange(user, 1)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.wallet.coins").value(450))
                .andExpect(jsonPath("$.data.wallet.exchangedThisMonth").value(1))
                .andExpect(jsonPath("$.data.wallet.exchangeableNow").value(9))
                .andExpect(jsonPath("$.data.wallet.monthResetAt").value(monthResetAfter(first).toString()));
        assertThat(pointsOf(user)).isEqualTo(11);
    }

    @Test
    void exchangesAtTheSameMomentNeverPassTheBalanceOrTheCap() throws Exception {
        freshDay();
        // Xu for only 2 of the 4 exchanges of 3 points
        long poor = member();
        give(poor, 340);
        List<String> outcomes = exchangeAtOnce(poor, 4, 3);
        assertThat(outcomes).filteredOn("OK"::equals).hasSize(2);
        assertThat(outcomes).filteredOn(o -> !"OK".equals(o))
                .containsOnly("Bạn cần 150 xu để đổi 3 điểm, hiện có 40 xu.");
        assertConsistent(poor, 2, 3, 40);

        // Xu for all of them, but the month's cap of 10 leaves room for 3
        long rich = member();
        give(rich, 2000);
        outcomes = exchangeAtOnce(rich, 4, 3);
        assertThat(outcomes).filteredOn("OK"::equals).hasSize(3);
        assertThat(outcomes).filteredOn(o -> !"OK".equals(o)).containsOnly("Tháng này bạn chỉ còn đổi được 1 điểm.");
        assertConsistent(rich, 3, 3, 2000 - 450);
    }

    @Test
    void guestsGet401StaffGetMsg18AndMembersMayExchange() throws Exception {
        freshDay();
        guestGet("/api/me/coins").andExpect(status().isUnauthorized());
        guestPost(EXCHANGE_URL, "{\"points\":1}").andExpect(status().isUnauthorized());

        for (String role : List.of("MODERATOR", "ACADEMIC_MANAGER", "ADMIN")) {
            long staff = user(role, "ACTIVE");
            give(staff, 500);
            getAs(staff, role, "/api/me/coins")
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.success").value(false))
                    .andExpect(jsonPath("$.message").value(MemberGuard.NO_PERMISSION));
            postAs(staff, role, EXCHANGE_URL, "{\"points\":1}")
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.message").value(MemberGuard.NO_PERMISSION));
            // The permission is checked before the amount
            postAs(staff, role, EXCHANGE_URL, "{\"points\":0}")
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.message").value(MemberGuard.NO_PERMISSION));
            assertThat(coinsOf(staff)).isEqualTo(500);
            assertThat(pointsOf(staff)).isZero();
        }

        long user = member();
        give(user, 100);
        getAs(user, "/api/me/coins").andExpect(status().isOk()).andExpect(jsonPath("$.success").value(true));
        exchange(user, 1).andExpect(status().isOk()).andExpect(jsonPath("$.data.wallet.coins").value(50));
    }

    // ---------------------------------------------------------------- helpers

    private static void expectPointsError(org.springframework.test.web.servlet.ResultActions result, String message)
            throws Exception {
        result.andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value(message))
                .andExpect(jsonPath("$.data.points").value(message));
    }

    /** {@code threads} exchanges of {@code points} each, released together; "OK" or the 400 message of each. */
    private List<String> exchangeAtOnce(long user, int threads, int points) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            CountDownLatch start = new CountDownLatch(1);
            List<Future<String>> tries = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                tries.add(pool.submit(() -> {
                    start.await();
                    try {
                        coins.exchange(principal(user, "USER"), new ExchangeRequest(IntNode.valueOf(points)));
                        return "OK";
                    } catch (ApiException refused) {
                        assertThat(refused.getStatus().value()).isEqualTo(400);
                        assertThat(refused.getFields()).containsEntry("points", refused.getMessage());
                        return refused.getMessage();
                    }
                }));
            }
            start.countDown();
            List<String> outcomes = new ArrayList<>();
            for (Future<String> attempt : tries) {
                outcomes.add(attempt.get(30, TimeUnit.SECONDS));          // none fails otherwise or deadlocks
            }
            return outcomes;
        } finally {
            pool.shutdownNow();
        }
    }

    private void assertConsistent(long user, int exchanges, int points, int coinsLeft) {
        assertThat(coinsOf(user)).isEqualTo(coinsLeft).isNotNegative();
        assertThat(coinsOf(user)).isEqualTo(coinBookSum(user));
        assertThat(pointsOf(user)).isEqualTo(exchanges * points).isEqualTo(pointBookSum(user));
        assertThat(pointsOf(user)).isLessThanOrEqualTo(coinSettings.getMonthCap());
        assertThat(count("SELECT COUNT(*) FROM coin_transactions WHERE user_id = ? AND reason = 'EXCHANGE'", user))
                .isEqualTo(exchanges);
        assertThat(count("SELECT COALESCE(SUM(delta), 0) FROM coin_transactions WHERE user_id = ? AND reason = 'EXCHANGE'",
                user)).isEqualTo(-exchanges * points * 50);
        // Every point line pays for its own EXCHANGE row
        assertThat(count("""
                SELECT COUNT(DISTINCT p.ref_id) FROM point_transactions p
                JOIN coin_transactions c ON c.id = p.ref_id AND c.user_id = p.user_id AND c.reason = 'EXCHANGE'
                WHERE p.user_id = ? AND p.reason = 'COIN_EXCHANGE' AND p.ref_type = 'COIN'""", user))
                .isEqualTo(exchanges);
        assertThat(ledger.wallet(user).exchangedThisMonth()).isEqualTo(exchanges * points);
    }
}
