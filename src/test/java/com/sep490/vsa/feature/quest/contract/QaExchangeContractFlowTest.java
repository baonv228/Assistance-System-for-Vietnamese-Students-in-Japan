package com.sep490.vsa.feature.quest.contract;

import com.fasterxml.jackson.databind.JsonNode;
import org.assertj.core.api.SoftAssertions;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.concurrent.ThreadLocalRandom;

import static org.assertj.core.api.Assertions.assertThat;

/** 3.5 / 5.4 "Đổi" xu into rank points, 5.5 the xu history. */
@TestPropertySource(properties = QaContractSupport.QA_DB)
class QaExchangeContractFlowTest extends QaContractSupport {

    static final String INVALID = "Hãy chọn số điểm muốn đổi (ít nhất 1 điểm).";
    static final String CAP_FULL = "Bạn đã đổi đủ 10 điểm của tháng này. Tháng sau đổi tiếp nhé.";

    @Test
    void anythingButAWholeNumberAtLeastOneIsRefusedFirst() throws Exception {
        freshDay();
        long m = member();
        SoftAssertions s = new SoftAssertions();
        String[] bodies = {"{}", "{\"points\":null}", "{\"points\":0}", "{\"points\":-1}", "{\"points\":2.5}",
                "{\"points\":\"3\"}", "{\"points\":true}", "{\"points\":\"abc\"}", "{\"points\":[3]}",
                "{\"points\":{}}"};
        for (String b : bodies) {
            expectPointsError(s, postAs(m, "/api/me/coins/exchange", b), INVALID, b);
        }
        // Order of checks: an invalid value is refused before the month cap and the balance.
        seedCoins(m, 500);
        ok(exchangeRaw(m, "10"));
        expectPointsError(s, exchangeRaw(m, "0"), INVALID, "0 with the month cap reached");
        s.assertThat(coinsOf(m)).isZero();
        s.assertThat(pointsOf(m)).isEqualTo(10);
        s.assertAll();
    }

    @Test
    void theMonthCapIsCheckedBeforeTheBalance() throws Exception {
        freshDay();
        long m = member();
        SoftAssertions s = new SoftAssertions();
        expectPointsError(s, exchangeRaw(m, "11"), "Tháng này bạn chỉ còn đổi được 10 điểm.", "11 with 0 xu");
        expectPointsError(s, exchangeRaw(m, "1000"), "Tháng này bạn chỉ còn đổi được 10 điểm.", "1000 with 0 xu");

        seedCoins(m, 400);
        clock.advance(GAP);
        ok(exchangeRaw(m, "8"));
        s.assertThat(coinsOf(m)).isZero();
        expectPointsError(s, exchangeRaw(m, "3"), "Tháng này bạn chỉ còn đổi được 2 điểm.", "3 with 8 exchanged");
        expectPointsError(s, exchangeRaw(m, "2"), "Bạn cần 100 xu để đổi 2 điểm, hiện có 0 xu.", "2 with 0 xu");

        seedCoins(m, 130);
        clock.advance(GAP);
        JsonNode done = ok(exchangeRaw(m, "2"));
        s.assertThat(done.path("wallet").path("exchangedThisMonth").asInt()).isEqualTo(10);
        s.assertThat(done.path("wallet").path("exchangeableNow").asInt()).as("never negative, cap reached").isZero();
        s.assertThat(done.path("wallet").path("coins").asInt()).isEqualTo(30);
        expectPointsError(s, exchangeRaw(m, "1"), CAP_FULL, "1 with the cap reached");
        expectPointsError(s, exchangeRaw(m, "5"), CAP_FULL, "5 with the cap reached");

        s.assertThat(coinsOf(m)).isEqualTo(30);
        s.assertThat(pointsOf(m)).isEqualTo(10);
        s.assertThat(count("SELECT COUNT(*) FROM coin_transactions WHERE user_id = ? AND reason = 'EXCHANGE'", m))
                .isEqualTo(2);
        s.assertThat(count("SELECT COUNT(*) FROM point_transactions WHERE user_id = ? AND reason = 'COIN_EXCHANGE'", m))
                .isEqualTo(2);
        s.assertAll();
    }

    @Test
    void notEnoughXuSaysWhatIsNeeded() throws Exception {
        freshDay();
        long m = member();
        seedCoins(m, 120);
        SoftAssertions s = new SoftAssertions();
        expectPointsError(s, exchangeRaw(m, "3"), "Bạn cần 150 xu để đổi 3 điểm, hiện có 120 xu.", "3 with 120 xu");
        s.assertThat(coinsOf(m)).isEqualTo(120);
        s.assertThat(pointsOf(m)).isZero();
        JsonNode ok = ok(exchangeRaw(m, "2"));
        s.assertThat(ok.path("coinsSpent").asInt()).isEqualTo(100);
        s.assertThat(ok.path("wallet").path("coins").asInt()).isEqualTo(20);
        s.assertThat(ok.path("wallet").path("exchangeableNow").asInt()).isZero();
        s.assertAll();
    }

    /** A whole number too big for an int is still a whole number ≥ 1: the month cap answers. */
    @Test
    void aHugeWholeNumberMeetsTheMonthCap() throws Exception {
        freshDay();
        long m = member();
        seedCoins(m, 100);
        SoftAssertions s = new SoftAssertions();
        expectPointsError(s, exchangeRaw(m, "99999999999"), "Tháng này bạn chỉ còn đổi được 10 điểm.",
                "99999999999");
        s.assertThat(coinsOf(m)).isEqualTo(100);
        s.assertAll();
    }

    @Test
    void theMonthCapFollowsTheJapanCalendarMonth() throws Exception {
        LocalDate fresh = freshDay();
        LocalDate lastOfNov = LocalDate.of(fresh.getYear(), 11, 30);
        LocalDate firstOfDec = lastOfNov.plusDays(1);
        setJapanTime(lastOfNov, LocalTime.of(23, 59, 59));
        long m = member();
        seedCoins(m, 1500);

        SoftAssertions s = new SoftAssertions();
        JsonNode nov = ok(exchangeRaw(m, "10"));
        s.assertThat(nov.path("wallet").path("exchangedThisMonth").asInt()).isEqualTo(10);
        s.assertThat(instant(nov.path("wallet").get("monthResetAt"))).as("Nov: resets at Dec 1 00:00 JST")
                .isEqualTo(japanMidnight(firstOfDec));
        s.assertThat(nov.path("wallet").path("monthResetAt").asText())
                .isEqualTo(lastOfNov + "T15:00:00Z");
        clock.advance(java.time.Duration.ofMillis(900));
        expectPointsError(s, exchangeRaw(m, "1"), CAP_FULL, "23:59:59.9 JST on Nov 30");

        clock.set(japanMidnight(firstOfDec));
        JsonNode wallet = ok(getAs(m, "/api/me/coins")).path("wallet");
        s.assertThat(wallet.path("exchangedThisMonth").asInt()).as("Dec 1 00:00:00 JST: a new month").isZero();
        s.assertThat(wallet.path("exchangeableNow").asInt()).isEqualTo(10);
        s.assertThat(instant(wallet.get("monthResetAt"))).isEqualTo(japanMidnight(firstOfDec.plusMonths(1)));
        JsonNode dec = ok(exchangeRaw(m, "10"));
        s.assertThat(dec.path("wallet").path("exchangedThisMonth").asInt()).isEqualTo(10);
        s.assertThat(dec.path("wallet").path("points").asInt()).isEqualTo(20);
        s.assertThat(dec.path("wallet").path("coins").asInt()).isEqualTo(500);

        setJapanTime(LocalDate.of(fresh.getYear(), 12, 31), LocalTime.of(23, 59, 59));
        s.assertThat(ok(getAs(m, "/api/me/coins")).path("wallet").path("exchangedThisMonth").asInt())
                .as("Dec 31 23:59:59 JST: the exchange made at Dec 1 00:00 JST (Nov 30 15:00 UTC) is December's")
                .isEqualTo(10);
        clock.set(japanMidnight(LocalDate.of(fresh.getYear() + 1, 1, 1)));
        s.assertThat(ok(getAs(m, "/api/me/coins")).path("wallet").path("exchangedThisMonth").asInt()).isZero();
        s.assertAll();
    }

    @Test
    void theXuHistoryHasPagesOf20NewestFirst() throws Exception {
        LocalDate day = freshDay();
        long m = member();
        Instant start = clock.instant().minusSeconds(3600);
        for (int i = 0; i < 23; i++) {
            jdbc.update("""
                    INSERT INTO coin_transactions (user_id, delta, reason, title, ref_type, ref_id, quest_date, created_at)
                    VALUES (?, 10, 'DAILY_QUEST', ?, 'QUEST', ?, ?, ?)""", m, "Nhiệm vụ QA " + i,
                    -ThreadLocalRandom.current().nextLong(1, 1L << 40), day.minusDays(30 - i).toString(),
                    Timestamp.from(start.plusSeconds(60L * i)));
        }
        jdbc.update("UPDATE users SET coins = 230 WHERE id = ?", m);

        SoftAssertions s = new SoftAssertions();
        JsonNode p0 = ok(getAs(m, "/api/me/coins"));
        s.assertThat(p0.path("page").asInt()).isZero();
        s.assertThat(p0.path("size").asInt()).isEqualTo(20);
        s.assertThat(p0.path("total").asInt()).isEqualTo(23);
        s.assertThat(p0.path("items").size()).isEqualTo(20);
        s.assertThat(p0.path("items").path(0).path("title").asText()).as("newest first").isEqualTo("Nhiệm vụ QA 22");
        s.assertThat(p0.path("items").path(0).path("reasonLabel").asText()).isEqualTo("Nhiệm vụ hằng ngày");
        s.assertThat(p0.path("items").path(0).path("link").asText()).isEqualTo("/quests");
        s.assertThat(instant(p0.path("items").path(0).get("createdAt"))).isEqualTo(start.plusSeconds(60L * 22));
        s.assertThat(p0.path("wallet").path("coins").asInt()).isEqualTo(230);
        JsonNode p1 = ok(getAs(m, "/api/me/coins?page=1"));
        s.assertThat(p1.path("page").asInt()).isEqualTo(1);
        s.assertThat(p1.path("items").size()).isEqualTo(3);
        s.assertThat(p1.path("items").path(2).path("title").asText()).isEqualTo("Nhiệm vụ QA 0");
        s.assertThat(p1.path("total").asInt()).isEqualTo(23);
        s.assertAll();
    }

    @Test
    void reachingOneHundredPointsChangesTheRankEverywhere() throws Exception {
        freshDay();
        long m = member();
        jdbc.update("UPDATE users SET points = 97 WHERE id = ?", m);
        seedCoins(m, 175);

        SoftAssertions s = new SoftAssertions();
        JsonNode before = ok(getAs(m, "/api/me/coins")).path("wallet");
        s.assertThat(before.path("rankName").asText()).isEqualTo("Người mới");
        s.assertThat(before.path("exchangeableNow").asInt()).isEqualTo(3);
        JsonNode same = ok(exchangeRaw(m, "1"));
        s.assertThat(same.path("rankBefore").asText()).isEqualTo("Người mới");
        s.assertThat(same.path("rankAfter").asText()).as("98 points: same rank").isEqualTo("Người mới");
        clock.advance(GAP);
        JsonNode up = ok(exchangeRaw(m, "2"));
        s.assertThat(up.path("rankBefore").asText()).isEqualTo("Người mới");
        s.assertThat(up.path("rankAfter").asText()).isEqualTo("Người chỉ đường");
        s.assertThat(up.path("wallet").path("points").asInt()).isEqualTo(100);
        s.assertThat(up.path("wallet").path("coins").asInt()).isEqualTo(25);
        JsonNode profile = ok(getAs(m, "/api/me/profile"));
        s.assertThat(profile.path("points").asInt()).isEqualTo(100);
        s.assertThat(profile.path("coins").asInt()).isEqualTo(25);
        s.assertThat(ok(getAs(m, "/api/me/home")).path("rankName").asText()).isEqualTo("Người chỉ đường");
        s.assertThat(ok(getAs(m, "/api/me/points")).path("points").asInt()).isEqualTo(100);
        s.assertAll();
    }

    private static void expectPointsError(SoftAssertions s, org.springframework.test.web.servlet.ResultActions r,
                                          String message, String what) throws Exception {
        int status = r.andReturn().getResponse().getStatus();
        JsonNode body = json(r);
        s.assertThat(status).as(what + " → HTTP 400; body " + body).isEqualTo(400);
        s.assertThat(body.path("success").asBoolean(true)).as(what + " success").isFalse();
        s.assertThat(body.path("data").path("points").asText(null)).as(what + " data.points").isEqualTo(message);
        s.assertThat(body.path("message").asText(null)).as(what + " message").isEqualTo(message);
        assertThat(body).isNotNull();
    }
}
