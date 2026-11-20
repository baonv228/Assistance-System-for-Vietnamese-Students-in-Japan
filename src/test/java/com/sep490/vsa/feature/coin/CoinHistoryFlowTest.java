package com.sep490.vsa.feature.coin;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * "Lịch sử xu" (GET /api/me/coins, contract 5.5), the exchange's line in "Lịch sử điểm" (GET /api/me/points, 5.7) and
 * the rule that xu never move rank points by themselves (sections 1, 3.4).
 */
@TestPropertySource(properties = "spring.datasource.url=jdbc:mysql://localhost:3306/asvsj_coin_test?createDatabaseIfNotExist=true&useUnicode=true&characterEncoding=utf8&connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true")
class CoinHistoryFlowTest extends CoinTestSupport {

    @Test
    void theHistoryListsEveryLineNewestFirstWithItsLabelTitleAndLink() throws Exception {
        LocalDate day = freshDay();
        long user = member();
        Instant first = clock.instant();
        inTransaction(() -> ledger.add(user, 10, CoinLedger.DAILY_QUEST, "Ôn 10 thẻ ghi nhớ", CoinLedger.REF_QUEST,
                900_000_001L, day));
        clock.advance(Duration.ofMinutes(1));
        inTransaction(() -> ledger.add(user, 10, CoinLedger.DAILY_QUEST, "Thuộc 3 mục mới", CoinLedger.REF_QUEST,
                900_000_002L, day));
        clock.advance(Duration.ofMinutes(1));
        Instant dayDone = clock.instant();
        // Two lines at the same second: the later row first
        inTransaction(() -> ledger.add(user, 20, CoinLedger.DAILY_QUEST_ALL, "Nhiệm vụ ngày 18/11",
                CoinLedger.REF_QUEST_DAY, 900_000_003L, day));
        inTransaction(() -> ledger.add(user, 50, CoinLedger.QUEST_STREAK, "Học 7 ngày liên tiếp",
                CoinLedger.REF_QUEST_DAY, 900_000_003L, day));
        clock.advance(Duration.ofMinutes(1));
        give(user, 100);
        clock.advance(Duration.ofMinutes(1));
        Instant exchangedAt = clock.instant();
        exchange(user, 3).andExpect(status().isOk());

        JsonNode data = json(getAs(user, "/api/me/coins")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))).get("data");
        assertThat(fieldNames(data)).containsExactlyInAnyOrder("items", "page", "size", "total", "wallet");
        assertThat(data.get("page").asInt()).isZero();
        assertThat(data.get("size").asInt()).isEqualTo(20);
        assertThat(data.get("total").asLong()).isEqualTo(6);
        JsonNode items = data.get("items");
        assertThat(items).hasSize(6);
        expectLine(items.get(0), -150, "EXCHANGE", "Đổi sang điểm cấp bậc", "Đổi lấy 3 điểm", "/profile#points",
                exchangedAt);
        expectLine(items.get(1), 100, "DEV_ADJUST", "Điều chỉnh khi thử", "Điều chỉnh khi thử", null,
                exchangedAt.minus(Duration.ofMinutes(1)));
        expectLine(items.get(2), 50, "QUEST_STREAK", "Chuỗi ngày học", "Học 7 ngày liên tiếp", "/quests", dayDone);
        expectLine(items.get(3), 20, "DAILY_QUEST_ALL", "Xong mọi nhiệm vụ trong ngày", "Nhiệm vụ ngày 18/11",
                "/quests", dayDone);
        expectLine(items.get(4), 10, "DAILY_QUEST", "Nhiệm vụ hằng ngày", "Thuộc 3 mục mới", "/quests",
                first.plus(Duration.ofMinutes(1)));
        expectLine(items.get(5), 10, "DAILY_QUEST", "Nhiệm vụ hằng ngày", "Ôn 10 thẻ ghi nhớ", "/quests", first);
        assertThat(items.get(2).get("id").asLong()).isGreaterThan(items.get(3).get("id").asLong());
        assertThat(fieldNames(items.get(0))).containsExactly("id", "delta", "reason", "reasonLabel", "title", "link",
                "createdAt");
        Long exchangeId = jdbc.queryForObject(
                "SELECT id FROM coin_transactions WHERE user_id = ? AND reason = 'EXCHANGE'", Long.class, user);
        assertThat(items.get(0).get("id").asLong()).isEqualTo(exchangeId);

        // The wallet after all of it: 10 + 10 + 20 + 50 + 100 − 150
        JsonNode wallet = data.get("wallet");
        assertThat(wallet.get("coins").asInt()).isEqualTo(40);
        assertThat(wallet.get("rate").asInt()).isEqualTo(50);
        assertThat(wallet.get("monthCap").asInt()).isEqualTo(10);
        assertThat(wallet.get("exchangedThisMonth").asInt()).isEqualTo(3);
        assertThat(wallet.get("exchangeableNow").asInt()).isZero();
        assertThat(wallet.get("monthResetAt").asText()).isEqualTo(monthResetAfter(day).toString());
        assertThat(wallet.get("points").asInt()).isEqualTo(3);
        assertThat(wallet.get("rankName").asText()).isEqualTo("Người mới");
    }

    @Test
    void theHistoryHas20LinesAPageAndKeepsThePageWithin0To199() throws Exception {
        freshDay();
        long user = member();
        for (int i = 0; i < 21; i++) {
            clock.advance(Duration.ofSeconds(1));
            int n = i;
            inTransaction(() -> ledger.add(user, 1, CoinLedger.DEV_ADJUST, "Dòng " + n, null, null, null));
        }

        getAs(user, "/api/me/coins")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.page").value(0))
                .andExpect(jsonPath("$.data.total").value(21))
                .andExpect(jsonPath("$.data.items.length()").value(20))
                .andExpect(jsonPath("$.data.items[0].title").value("Dòng 20"))
                .andExpect(jsonPath("$.data.items[19].title").value("Dòng 1"))
                .andExpect(jsonPath("$.data.wallet.coins").value(21));
        getAs(user, "/api/me/coins?page=1")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.page").value(1))
                .andExpect(jsonPath("$.data.size").value(20))
                .andExpect(jsonPath("$.data.total").value(21))
                .andExpect(jsonPath("$.data.items.length()").value(1))
                .andExpect(jsonPath("$.data.items[0].title").value("Dòng 0"));
        getAs(user, "/api/me/coins?page=-3")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.page").value(0))
                .andExpect(jsonPath("$.data.items.length()").value(20))
                .andExpect(jsonPath("$.data.items[0].title").value("Dòng 20"));
        getAs(user, "/api/me/coins?page=5000")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.page").value(199))
                .andExpect(jsonPath("$.data.total").value(21))
                .andExpect(jsonPath("$.data.items.length()").value(0));
    }

    @Test
    void thePointHistoryShowsAnExchangeAsDoiXuNhiemVuAndKeepsTheOtherReasons() throws Exception {
        freshDay();
        long user = member();
        Instant now = clock.instant();
        Map<String, Object> place = jdbc.queryForMap(
                "SELECT id, name_vi FROM places WHERE status = 'ACTIVE' ORDER BY id LIMIT 1");
        long placeId = ((Number) place.get("id")).longValue();
        pointLine(user, 15, "PIN_APPROVED", "PLACE", placeId, now.minus(Duration.ofHours(2)));
        pointLine(user, 10, "REVIEW_CREATED", "REVIEW", 999_999_999L, now.minus(Duration.ofHours(1)));
        // An exchange line whose row of xu is gone (last month, so it does not count toward this month's cap)
        pointLine(user, 1, "COIN_EXCHANGE", "COIN", null, now.minus(Duration.ofDays(40)));
        give(user, 340);
        exchange(user, 3).andExpect(status().isOk());

        JsonNode data = json(getAs(user, "/api/me/points").andExpect(status().isOk())).get("data");
        assertThat(data.get("total").asLong()).isEqualTo(4);
        JsonNode items = data.get("items");
        JsonNode exchange = items.get(0);
        assertThat(exchange.get("delta").asInt()).isEqualTo(3);
        assertThat(exchange.get("reason").asText()).isEqualTo("COIN_EXCHANGE");
        assertThat(exchange.get("reasonLabel").asText()).isEqualTo("Đổi xu nhiệm vụ");
        assertThat(exchange.get("title").asText()).isEqualTo("Đổi 150 xu");
        assertThat(exchange.get("link").asText()).isEqualTo("/quests");
        assertThat(exchange.get("createdAt").asText()).isEqualTo(now.toString());
        assertThat(fieldNames(exchange)).containsExactly("id", "delta", "reason", "reasonLabel", "title", "link",
                "createdAt");
        // The other reasons read as before
        JsonNode review = items.get(1);
        assertThat(review.get("reason").asText()).isEqualTo("REVIEW_CREATED");
        assertThat(review.get("reasonLabel").asText()).isEqualTo("Viết review");
        assertThat(review.get("title").isNull()).isTrue();
        assertThat(review.get("link").isNull()).isTrue();
        JsonNode pin = items.get(2);
        assertThat(pin.get("reasonLabel").asText()).isEqualTo("Ghim được duyệt");
        assertThat(pin.get("title").asText()).isEqualTo(place.get("name_vi"));
        assertThat(pin.get("link").asText()).isEqualTo("/map?place=" + placeId);
        JsonNode orphan = items.get(3);
        assertThat(orphan.get("reasonLabel").asText()).isEqualTo("Đổi xu nhiệm vụ");
        assertThat(orphan.get("title").isNull()).isTrue();
        assertThat(orphan.get("link").asText()).isEqualTo("/quests");
    }

    @Test
    void xuNeverMoveRankPointsExceptThroughAnExchange() throws Exception {
        LocalDate day = freshDay();
        long user = member();
        jdbc.update("UPDATE users SET points = 98 WHERE id = ?", user);
        inTransaction(() -> {
            ledger.add(user, 10, CoinLedger.DAILY_QUEST, "Ôn 10 thẻ ghi nhớ", CoinLedger.REF_QUEST, 900_000_011L, day);
            ledger.add(user, 20, CoinLedger.DAILY_QUEST_ALL, "Nhiệm vụ ngày", CoinLedger.REF_QUEST_DAY, 900_000_012L,
                    day);
            ledger.add(user, 50, CoinLedger.QUEST_STREAK, "Học 7 ngày liên tiếp", CoinLedger.REF_QUEST_DAY,
                    900_000_012L, day);
            return ledger.add(user, 300, CoinLedger.DEV_ADJUST, "Điều chỉnh khi thử", null, null, null);
        });

        // 380 xu, more than "Senpai tin cậy" asks in points, and still a "Người mới" with 98 points
        assertThat(coinsOf(user)).isEqualTo(380);
        assertThat(pointsOf(user)).isEqualTo(98);
        assertThat(count("SELECT COUNT(*) FROM point_transactions WHERE user_id = ?", user)).isZero();
        getAs(user, "/api/me/points")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(0))
                .andExpect(jsonPath("$.data.points").value(98));
        getAs(user, "/api/me/profile")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.points").value(98))
                .andExpect(jsonPath("$.data.coins").value(380));
        getAs(user, "/api/me/coins")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.wallet.points").value(98))
                .andExpect(jsonPath("$.data.wallet.rankName").value("Người mới"))
                .andExpect(jsonPath("$.data.wallet.exchangeableNow").value(7));

        // Only "Đổi" turns them into points, at 50 xu each
        exchange(user, 2).andExpect(status().isOk()).andExpect(jsonPath("$.data.rankAfter").value("Người chỉ đường"));
        assertThat(pointsOf(user)).isEqualTo(100);
        assertThat(coinsOf(user)).isEqualTo(280);
        assertThat(count("SELECT COUNT(*) FROM point_transactions WHERE user_id = ?", user)).isEqualTo(1);
    }

    // ---------------------------------------------------------------- helpers

    private static void expectLine(JsonNode line, int delta, String reason, String label, String title, String link,
                                   Instant createdAt) {
        assertThat(line.get("delta").asInt()).as(reason).isEqualTo(delta);
        assertThat(line.get("reason").asText()).isEqualTo(reason);
        assertThat(line.get("reasonLabel").asText()).as(reason).isEqualTo(label);
        assertThat(line.get("title").asText()).as(reason).isEqualTo(title);
        if (link == null) {
            assertThat(line.get("link").isNull()).as(reason).isTrue();
        } else {
            assertThat(line.get("link").asText()).as(reason).isEqualTo(link);
        }
        assertThat(line.get("createdAt").asText()).as(reason).isEqualTo(createdAt.toString());
    }

    private void pointLine(long user, int delta, String reason, String refType, Long refId, Instant at) {
        jdbc.update("""
                INSERT INTO point_transactions (user_id, delta, reason, ref_type, ref_id, created_at)
                VALUES (?, ?, ?, ?, ?, ?)""", user, delta, reason, refType, refId, Timestamp.from(at));
    }
}
