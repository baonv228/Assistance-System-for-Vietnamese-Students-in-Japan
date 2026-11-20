package com.sep490.vsa.feature.quest.contract;

import com.fasterxml.jackson.databind.JsonNode;
import org.assertj.core.api.SoftAssertions;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.ResultActions;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 5.9 test API, switched on (app.quests.dev-api.enabled=true) on this machine's database (3.9): what the mobile team's
 * end-to-end tests rely on to run again and again.
 */
@TestPropertySource(properties = {QaContractSupport.QA_DB, "app.quests.dev-api.enabled=true"})
class QaDevApiContractFlowTest extends QaContractSupport {

    @Test
    void deleteTodayGivesTodaysXuBackKeepsExchangesAndMarksAndMakesANewSet() throws Exception {
        LocalDate day = freshDay();
        long m = member();
        List<Long> lib = payLibrary(5, m);
        seedCoins(m, 100);
        ok(exchangeRaw(m, "1"));
        long phraseOfDay = homePhraseId();
        JsonNode before = today(m);
        finishPayDay(m, lib);
        SoftAssertions s = new SoftAssertions();
        s.assertThat(coinsOf(m)).isEqualTo(100);

        ResultActions del = deleteAs(m, "/api/dev/quests/today");
        s.assertThat(del.andReturn().getResponse().getStatus()).as("DELETE today; body " + body(del)).isEqualTo(200);
        s.assertThat(coinsOf(m)).as("today's 50 xu taken back").isEqualTo(50);
        s.assertThat(count("SELECT COALESCE(SUM(delta), 0) FROM coin_transactions WHERE user_id = ?", m))
                .as("users.coins = ledger").isEqualTo(coinsOf(m));
        s.assertThat(count("""
                SELECT COUNT(*) FROM coin_transactions WHERE user_id = ? AND quest_date = ?
                AND reason IN ('DAILY_QUEST', 'DAILY_QUEST_ALL', 'QUEST_STREAK')""", m, day.toString())).isZero();
        s.assertThat(count("SELECT COUNT(*) FROM coin_transactions WHERE user_id = ? AND reason = 'EXCHANGE'", m))
                .as("exchange kept").isEqualTo(1);
        s.assertThat(pointsOf(m)).isEqualTo(1);
        s.assertThat(count("SELECT COUNT(*) FROM user_phrase_marks WHERE user_id = ? AND is_learned", m))
                .as("marks kept").isEqualTo(5);
        s.assertThat(count("SELECT COUNT(*) FROM user_quest_days WHERE user_id = ? AND quest_date = ?", m,
                day.toString())).isZero();
        s.assertThat(homePhraseId()).as("daily_phrases kept").isEqualTo(phraseOfDay);

        JsonNode fresh = today(m);
        for (JsonNode q : fresh.path("quests")) {
            s.assertThat(q.path("progress").asInt()).isZero();
            s.assertThat(q.path("id").asLong()).as("a new set").isNotIn(ids(before));
        }
        s.assertThat(fresh.path("coinsToday").asInt()).isZero();
        s.assertThat(fresh.path("streak").path("todayDone").asBoolean()).isFalse();
        s.assertThat(fresh.path("bonus").path("completed").asBoolean()).isFalse();

        finishPayDay(m, lib);
        s.assertThat(coinsOf(m)).as("the day can be played and paid again").isEqualTo(100);
        s.assertAll();
    }

    @Test
    void deleteTodayAfterTheXuWereSpentNeverBreaks() throws Exception {
        freshDay();
        long m = member();
        List<Long> lib = payLibrary(5, m);
        finishPayDay(m, lib);
        clock.advance(GAP);
        ok(exchangeRaw(m, "1"));
        assertThat(coinsOf(m)).isZero();

        ResultActions del = deleteAs(m, "/api/dev/quests/today");
        int status = del.andReturn().getResponse().getStatus();
        SoftAssertions s = new SoftAssertions();
        s.assertThat(status).as("DELETE today when today's xu were already exchanged; body " + body(del))
                .isLessThan(500);
        s.assertThat(coinsOf(m)).isGreaterThanOrEqualTo(0);
        s.assertThat(pointsOf(m)).as("exchanges untouched").isEqualTo(1);
        s.assertAll();
    }

    @Test
    void historyMakesDoneDaysForTheSeventhDayMilestone() throws Exception {
        LocalDate day = freshDay();
        long m = member();
        List<Long> lib = payLibrary(5, m);

        ResultActions post = postAs(m, "/api/dev/quests/history", "{\"doneDays\":6}");
        SoftAssertions s = new SoftAssertions();
        s.assertThat(post.andReturn().getResponse().getStatus()).as("POST history; body " + body(post)).isEqualTo(200);
        s.assertThat(coinsOf(m)).as("no xu for made days").isZero();
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT DATE_FORMAT(quest_date, '%Y-%m-%d') AS d, streak, all_done_at FROM user_quest_days
                WHERE user_id = ? ORDER BY quest_date""", m);
        s.assertThat(rows).hasSize(6);
        for (int i = 0; i < rows.size(); i++) {
            s.assertThat(rows.get(i).get("d")).isEqualTo(day.minusDays(6 - i).toString());
            s.assertThat(((Number) rows.get(i).get("streak")).intValue()).isEqualTo(i + 1);
            s.assertThat(rows.get(i).get("all_done_at")).isNotNull();
        }
        ResultActions again = postAs(m, "/api/dev/quests/history", "{\"doneDays\":6}");
        s.assertThat(again.andReturn().getResponse().getStatus()).as("same call again (tests run again); body "
                + body(again)).isEqualTo(200);
        s.assertThat(count("SELECT COUNT(*) FROM user_quest_days WHERE user_id = ?", m)).isEqualTo(6);

        JsonNode t = today(m);
        s.assertThat(t.path("streak").path("current").asInt()).isEqualTo(6);
        List<JsonNode> changes = finishPayDay(m, lib);
        JsonNode last = changes.get(changes.size() - 1);
        s.assertThat(last.path("streakMilestone").path("days").asInt()).isEqualTo(7);
        s.assertThat(coinsOf(m)).isEqualTo(100);

        ResultActions clear = postAs(m, "/api/dev/quests/history", "{\"doneDays\":0}");
        s.assertThat(clear.andReturn().getResponse().getStatus()).as("doneDays 0; body " + body(clear)).isEqualTo(200);
        s.assertThat(count("SELECT COUNT(*) FROM user_quest_days WHERE user_id = ? AND quest_date < ?", m,
                day.toString())).as("earlier days cleared").isZero();
        JsonNode after = today(m);
        for (int i = 0; i < 6; i++) {
            s.assertThat(after.path("recentDays").path(i).path("done").asBoolean()).isFalse();
        }
        s.assertAll();
    }

    @Test
    void putTodayForcesTheSetAndRefusesWhatCannotBeDone() throws Exception {
        freshDay();
        long m = member();
        payLibrary(5, m);
        SoftAssertions s = new SoftAssertions();
        JsonNode first = today(m);
        listen(m, requireQuest(first, "PHRASE_OF_DAY").path("phrase").path("id").asLong());
        s.assertThat(coinsOf(m)).isEqualTo(10);

        ResultActions put = putAs(m, "/api/dev/quests/today", "{\"codes\":[\"FLASH_REVIEW\",\"TOPIC\",\"PHRASE_OF_DAY\"]}");
        s.assertThat(put.andReturn().getResponse().getStatus()).as("PUT today; body " + body(put)).isEqualTo(200);
        JsonNode forced = today(m);
        s.assertThat(codes(forced.path("quests"))).containsExactlyInAnyOrder("FLASH_REVIEW", "TOPIC", "PHRASE_OF_DAY");
        for (JsonNode q : forced.path("quests")) {
            s.assertThat(q.path("id").asLong()).as("old set removed like DELETE").isNotIn(ids(first));
            s.assertThat(q.path("progress").asInt()).isZero();
        }
        s.assertThat(coinsOf(m)).as("xu of the old set taken back").isZero();

        for (String code : new String[]{"REVIEW_LEARNING", "LEARN_NEW", "QUIZ"}) {
            ResultActions bad = putAs(m, "/api/dev/quests/today",
                    "{\"codes\":[\"FLASH_REVIEW\",\"" + code + "\",\"TOPIC\"]}");
            JsonNode body = json(bad);
            s.assertThat(bad.andReturn().getResponse().getStatus()).as(code + " cannot be done; body " + body)
                    .isEqualTo(400);
            s.assertThat(body.toString()).as("the 400 names " + code).contains(code);
        }
        s.assertThat(ids(today(m))).as("a refused PUT leaves today's set").isEqualTo(ids(forced));
        s.assertAll();
    }

    @Test
    void putCoinsSetsTheBalanceAndClearsTheMonthsExchanges() throws Exception {
        freshDay();
        long m = member();
        seedCoins(m, 300);
        ok(exchangeRaw(m, "4"));
        SoftAssertions s = new SoftAssertions();
        s.assertThat(coinsOf(m)).isEqualTo(100);

        ResultActions keep = putAs(m, "/api/dev/coins", "{\"coins\":250}");
        s.assertThat(keep.andReturn().getResponse().getStatus()).as("PUT coins; body " + body(keep)).isEqualTo(200);
        s.assertThat(coinsOf(m)).isEqualTo(250);
        s.assertThat(pointsOf(m)).as("exchanges kept without clearMonthExchanges").isEqualTo(4);
        JsonNode newest = ok(getAs(m, "/api/me/coins")).path("items").path(0);
        s.assertThat(newest.path("reason").asText()).isEqualTo("DEV_ADJUST");
        s.assertThat(newest.path("title").asText()).isEqualTo("Điều chỉnh khi thử");
        s.assertThat(newest.path("delta").asInt()).isEqualTo(150);

        clock.advance(GAP);
        ResultActions clear = putAs(m, "/api/dev/coins", "{\"coins\":600,\"clearMonthExchanges\":true}");
        s.assertThat(clear.andReturn().getResponse().getStatus()).as("PUT coins clear; body " + body(clear))
                .isEqualTo(200);
        s.assertThat(coinsOf(m)).isEqualTo(600);
        s.assertThat(pointsOf(m)).as("points of this month's exchanges taken back").isZero();
        s.assertThat(count("SELECT COUNT(*) FROM point_transactions WHERE user_id = ? AND reason = 'COIN_EXCHANGE'", m))
                .isZero();
        s.assertThat(count("SELECT COUNT(*) FROM coin_transactions WHERE user_id = ? AND reason = 'EXCHANGE'", m))
                .isZero();
        s.assertThat(count("SELECT COALESCE(SUM(delta), 0) FROM coin_transactions WHERE user_id = ?", m))
                .as("users.coins = ledger").isEqualTo(600);
        JsonNode wallet = ok(getAs(m, "/api/me/coins")).path("wallet");
        s.assertThat(wallet.path("exchangedThisMonth").asInt()).isZero();
        s.assertThat(wallet.path("exchangeableNow").asInt()).isEqualTo(10);
        s.assertThat(ok(exchangeRaw(m, "10")).path("wallet").path("points").asInt()).isEqualTo(10);
        s.assertAll();
    }

    /** "đủ cả ba trạng thái … để mọi loại nhiệm vụ đều khả thi" for user@gmail.com. */
    @Test
    void theSampleMemberCanBeGivenEveryKindOfQuest() throws Exception {
        LocalDate day = freshDay();
        Long sample = jdbc.query("SELECT id FROM users WHERE email = 'user@gmail.com'",
                rs -> rs.next() ? rs.getLong(1) : null);
        assertThat(sample).as("DevDataSeeder's user@gmail.com").isNotNull();
        try {
            SoftAssertions s = new SoftAssertions();
            int learning = count("""
                    SELECT COUNT(*) FROM user_phrase_marks k JOIN phrases p ON p.id = k.phrase_id
                    WHERE k.user_id = ? AND k.is_learning AND p.deleted_at IS NULL""", sample);
            int learned = count("""
                    SELECT COUNT(*) FROM user_phrase_marks k JOIN phrases p ON p.id = k.phrase_id
                    WHERE k.user_id = ? AND k.is_learned AND p.deleted_at IS NULL""", sample);
            int none = count("""
                    SELECT COUNT(*) FROM phrases p WHERE p.deleted_at IS NULL AND NOT EXISTS
                    (SELECT 1 FROM user_phrase_marks k WHERE k.user_id = ? AND k.phrase_id = p.id)""", sample);
            s.assertThat(learning).as("Chưa thuộc marks (REVIEW_LEARNING needs 5)").isGreaterThanOrEqualTo(5);
            s.assertThat(learned).as("Đã thuộc marks").isGreaterThanOrEqualTo(1);
            s.assertThat(none).as("entries never practised (LEARN_NEW 'new' needs 3)").isGreaterThanOrEqualTo(3);
            for (String[] pair : new String[][]{{"LEARN_NEW", "REVIEW_LEARNING"}, {"TOPIC", "PHRASE_OF_DAY"}}) {
                ResultActions put = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .put("/api/dev/quests/today").session(session(sample, "USER"))
                        .cookie(new jakarta.servlet.http.Cookie("XSRF-TOKEN", CSRF)).header("X-XSRF-TOKEN", CSRF)
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("{\"codes\":[\"FLASH_REVIEW\",\"" + pair[0] + "\",\"" + pair[1] + "\"]}"));
                s.assertThat(put.andReturn().getResponse().getStatus()).as("PUT " + String.join("+", pair) + "; body "
                        + body(put)).isEqualTo(200);
            }
            s.assertAll();
        } finally {
            jdbc.update("DELETE FROM coin_transactions WHERE user_id = ? AND quest_date = ?", sample, day.toString());
            jdbc.update("DELETE FROM user_daily_quests WHERE user_id = ? AND quest_date = ?", sample, day.toString());
            jdbc.update("DELETE FROM daily_phrases WHERE phrase_date = ?", day.toString());
        }
    }

    private static List<Long> ids(JsonNode today) {
        List<Long> ids = new java.util.ArrayList<>();
        today.path("quests").forEach(q -> ids.add(q.path("id").asLong()));
        return ids;
    }
}
