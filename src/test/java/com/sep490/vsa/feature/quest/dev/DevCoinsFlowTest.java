package com.sep490.vsa.feature.quest.dev;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code PUT /api/dev/coins} (contract 5.9): the balance set by one DEV_ADJUST row "Điều chỉnh khi thử", and this Japan
 * month's exchanges undone on request, so "Đổi", the monthly cap and a new rank can be tried again and again.
 */
class DevCoinsFlowTest extends DevApiTestSupport {

    private static final String COINS_INVALID = "Số xu phải là số nguyên từ 0 đến 1.000.000.";
    private static final String CLEAR_INVALID = "Giá trị clearMonthExchanges phải là true hoặc false.";

    @Test
    void theBalanceIsSetByOneDevAdjustRow() throws Exception {
        freshDay();
        long user = member();

        var result = setCoins(user, "{\"coins\":600}").andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Đã đặt số dư 600 xu."));
        JsonNode wallet = data(result);

        assertThat(wallet.path("coins").asInt()).isEqualTo(600);
        assertThat(wallet.path("exchangeableNow").asInt()).isEqualTo(10);
        assertThat(wallet.path("points").asInt()).isZero();
        assertThat(coinsOf(user)).isEqualTo(600);
        assertThat(adjustments(user)).containsExactly(600);
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM coin_transactions WHERE user_id = ? AND reason = 'DEV_ADJUST'
                  AND title = 'Điều chỉnh khi thử' AND ref_type IS NULL AND ref_id IS NULL AND quest_date IS NULL""",
                Integer.class, user)).isEqualTo(1);

        // The same balance again: no row; then down and to 0, one row each
        setCoins(user, "{\"coins\":600}").andExpect(status().isOk());
        assertThat(adjustments(user)).containsExactly(600);
        setCoins(user, "{\"coins\":250,\"clearMonthExchanges\":false}").andExpect(status().isOk());
        assertThat(data(setCoins(user, "{\"coins\":0}").andExpect(status().isOk())).path("coins").asInt()).isZero();
        assertThat(adjustments(user)).containsExactly(600, -350, -250);
        assertThat(coinsOf(user)).isZero();
        assertThat(bookOf(user)).isZero();
        assertThat(pointsOf(user)).isZero();
        assertThat(data(setCoins(user, "{\"coins\":1000000}").andExpect(status().isOk())).path("coins").asInt())
                .isEqualTo(1_000_000);
        assertThat(rows("SELECT COUNT(*) FROM point_transactions WHERE user_id = ?", user)).isZero();

        // "Lịch sử xu" shows the rows with their label
        getAs(user, "/api/me/coins").andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].reason").value("DEV_ADJUST"))
                .andExpect(jsonPath("$.data.items[0].reasonLabel").value("Điều chỉnh khi thử"))
                .andExpect(jsonPath("$.data.items[0].title").value("Điều chỉnh khi thử"));
    }

    @Test
    void clearingThisMonthsExchangesGivesPointsAndXuBackAndFreesTheCap() throws Exception {
        LocalDate mid = freshDay().withDayOfMonth(15);
        long user = member();
        jdbc.update("UPDATE users SET points = 40 WHERE id = ?", user);

        // Last month: one exchange that must stay
        setJapanTime(mid.minusMonths(1), LocalTime.NOON);
        setCoins(user, "{\"coins\":100}").andExpect(status().isOk());
        exchange(user, 1).andExpect(status().isOk());
        assertThat(pointsOf(user)).isEqualTo(41);

        // This month: 3 + 7 points, the cap of 10 reached
        setJapanTime(mid, LocalTime.NOON);
        setCoins(user, "{\"coins\":600}").andExpect(status().isOk());
        exchange(user, 3).andExpect(status().isOk());
        exchange(user, 7).andExpect(status().isOk());
        assertThat(coinsOf(user)).isEqualTo(100);
        assertThat(pointsOf(user)).isEqualTo(51);
        exchange(user, 1).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.data.points").value("Bạn đã đổi đủ 10 điểm của tháng này. Tháng sau đổi tiếp nhé."));

        // Without clearMonthExchanges the exchanges stay
        JsonNode kept = data(setCoins(user, "{\"coins\":600}").andExpect(status().isOk()));
        assertThat(kept.path("exchangedThisMonth").asInt()).isEqualTo(10);
        assertThat(kept.path("exchangeableNow").asInt()).isZero();
        assertThat(pointsOf(user)).isEqualTo(51);

        JsonNode wallet = data(setCoins(user, "{\"coins\":600,\"clearMonthExchanges\":true}")
                .andExpect(status().isOk()));

        assertThat(wallet.path("coins").asInt()).isEqualTo(600);
        assertThat(wallet.path("points").asInt()).isEqualTo(41);
        assertThat(wallet.path("exchangedThisMonth").asInt()).isZero();
        assertThat(wallet.path("exchangeableNow").asInt()).isEqualTo(10);
        assertThat(pointsOf(user)).isEqualTo(41);
        assertThat(coinsOf(user)).isEqualTo(600);
        assertThat(bookOf(user)).isEqualTo(600);
        // Only last month's exchange is left, in points and in xu
        assertThat(jdbc.queryForList(
                "SELECT delta FROM point_transactions WHERE user_id = ? AND reason = 'COIN_EXCHANGE'", Integer.class,
                user)).containsExactly(1);
        assertThat(jdbc.queryForList(
                "SELECT delta FROM coin_transactions WHERE user_id = ? AND reason = 'EXCHANGE'", Integer.class, user))
                .containsExactly(-50);
        getAs(user, "/api/me/points").andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[?(@.reason == 'COIN_EXCHANGE')]").isArray())
                .andExpect(jsonPath("$.data.items[?(@.reason == 'COIN_EXCHANGE')].delta").value(1));

        // The month's cap is free again
        exchange(user, 10).andExpect(status().isOk());
        assertThat(pointsOf(user)).isEqualTo(51);
        assertThat(coinsOf(user)).isEqualTo(100);
    }

    @Test
    void pointsTakenBackNeverGoBelowZero() throws Exception {
        freshDay();
        long user = member();
        setCoins(user, "{\"coins\":200}").andExpect(status().isOk());
        exchange(user, 3).andExpect(status().isOk());
        jdbc.update("UPDATE users SET points = 1 WHERE id = ?", user);   // e.g. a removed post took 20 points since

        JsonNode wallet = data(setCoins(user, "{\"coins\":50,\"clearMonthExchanges\":true}")
                .andExpect(status().isOk()));

        assertThat(wallet.path("points").asInt()).isZero();
        assertThat(wallet.path("coins").asInt()).isEqualTo(50);
        assertThat(pointsOf(user)).isZero();
        assertThat(bookOf(user)).isEqualTo(50);
    }

    @Test
    void wrongValuesAreRefusedOnTheirFieldAndChangeNothing() throws Exception {
        freshDay();
        long user = member();
        setCoins(user, "{\"coins\":300}").andExpect(status().isOk());
        exchange(user, 2).andExpect(status().isOk());

        for (String body : List.of("{\"coins\":-1}", "{\"coins\":1000001}", "{}", "{\"coins\":null}",
                "{\"coins\":\"600\"}", "{\"coins\":2.5}", "{\"coins\":true}", "[]", "600",
                "{\"clearMonthExchanges\":true}")) {
            setCoins(user, body).andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.success").value(false))
                    .andExpect(jsonPath("$.message").value(COINS_INVALID))
                    .andExpect(jsonPath("$.data.coins").value(COINS_INVALID));
        }
        for (String body : List.of("{\"coins\":10,\"clearMonthExchanges\":\"yes\"}",
                "{\"coins\":10,\"clearMonthExchanges\":1}")) {
            setCoins(user, body).andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.message").value(CLEAR_INVALID))
                    .andExpect(jsonPath("$.data.clearMonthExchanges").value(CLEAR_INVALID))
                    .andExpect(jsonPath("$.data.coins").doesNotExist());
        }
        setCoins(user, "{\"coins\":-5,\"clearMonthExchanges\":\"yes\"}").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.data.coins").value(COINS_INVALID))
                .andExpect(jsonPath("$.data.clearMonthExchanges").value(CLEAR_INVALID));

        assertThat(coinsOf(user)).isEqualTo(200);
        assertThat(pointsOf(user)).isEqualTo(2);
        assertThat(adjustments(user)).containsExactly(300);
    }

    /** The DEV_ADJUST deltas of the user, oldest first. */
    private List<Integer> adjustments(long user) {
        return jdbc.queryForList(
                "SELECT delta FROM coin_transactions WHERE user_id = ? AND reason = 'DEV_ADJUST' ORDER BY id",
                Integer.class, user);
    }
}
