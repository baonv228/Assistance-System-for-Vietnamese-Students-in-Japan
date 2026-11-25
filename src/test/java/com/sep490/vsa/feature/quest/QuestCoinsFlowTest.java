package com.sep490.vsa.feature.quest;

import com.fasterxml.jackson.databind.JsonNode;
import com.sep490.vsa.feature.coin.CoinLedger;
import org.junit.jupiter.api.Test;

import java.sql.Date;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Xu of the quests (contract 3.4): +10 per completed quest and +20 when the day is done, paid in the same transaction,
 * one line each in coin_transactions, at most 50 a Japan day (quests + bonus), never rank points.
 */
class QuestCoinsFlowTest extends QuestFlowSupport {

    /** One entry: FLASH_REVIEW and LEARN_NEW with target 1 and PHRASE_OF_DAY on that entry. */
    private long tinyDay(long user, LocalDate day) {
        clearPhraseOfDay(day);
        long only = phrases(1).get(0);
        force(user, "FLASH_REVIEW", "LEARN_NEW", "PHRASE_OF_DAY");
        return only;
    }

    @Test
    void completedQuestsAndTheDayPayXuButNoRankPoints() throws Exception {
        LocalDate day = freshDay();
        isolateLibrary();
        long user = member();
        long only = tinyDay(user, day);
        long listened = quest(today(user), "PHRASE_OF_DAY").path("phrase").path("id").asLong();

        JsonNode flash = whole(flash(user, only, true).andExpect(status().isOk()));
        assertThat(flash.path("message").asText()).isEqualTo("Đã lưu: Đã thuộc."
                + " Xong nhiệm vụ “Ôn 1 thẻ ghi nhớ” · +10 xu. Xong nhiệm vụ “Thuộc 1 mục mới” · +10 xu.");
        JsonNode change = flash.path("data").path("quests");
        assertThat(change.path("justCompleted")).hasSize(2);
        change.path("justCompleted").forEach(q -> assertThat(q.path("coins").asInt()).isEqualTo(10));
        assertThat(change.path("bonusJustCompleted").asBoolean()).isFalse();
        assertThat(change.path("coinsAwarded").asInt()).isEqualTo(20);
        assertThat(change.path("coinsToday").asInt()).isEqualTo(20);
        assertThat(change.path("dailyCap").asInt()).isEqualTo(50);
        assertThat(change.path("wallet").path("coins").asInt()).isEqualTo(20);

        next();
        JsonNode listen = whole(listen(user, listened).andExpect(status().isOk()));
        assertThat(listen.path("message").asText())
                .isEqualTo("Đã ghi nhận. Xong nhiệm vụ “Nghe câu tiếng Nhật hôm nay” · +10 xu.");
        JsonNode last = listen.path("data");
        assertThat(last.path("bonusJustCompleted").asBoolean()).isTrue();
        assertThat(last.path("bonusCoins").asInt()).isEqualTo(20);
        assertThat(last.path("coinsAwarded").asInt()).isEqualTo(30);
        assertThat(last.path("coinsToday").asInt()).isEqualTo(50);
        assertThat(last.path("wallet").path("coins").asInt()).isEqualTo(50);
        assertThat(last.path("streak").path("current").asInt()).isEqualTo(1);
        assertThat(last.path("streak").path("todayDone").asBoolean()).isTrue();

        JsonNode today = today(user);
        today.path("quests").forEach(q -> {
            assertThat(q.path("completed").asBoolean()).isTrue();
            assertThat(q.path("coins").asInt()).isEqualTo(10);
            assertThat(q.path("progress").asInt()).isEqualTo(q.path("target").asInt());
            assertThat(q.path("completedAt").isNull()).isFalse();
        });
        assertThat(today.path("bonus").path("coins").asInt()).isEqualTo(20);
        assertThat(today.path("bonus").path("completed").asBoolean()).isTrue();
        assertThat(today.path("coinsToday").asInt()).isEqualTo(50);
        assertThat(today.path("wallet").path("coins").asInt()).isEqualTo(50);

        // The book: one DAILY_QUEST line per quest (its title and id), one DAILY_QUEST_ALL line for the day
        Map<String, Long> questIds = new HashMap<>();
        Map<String, String> titles = new HashMap<>();
        today.path("quests").forEach(q -> {
            questIds.put(q.path("code").asText(), q.path("id").asLong());
            titles.put(q.path("code").asText(), q.path("title").asText());
        });
        List<Map<String, Object>> lines = jdbc.queryForList("""
                SELECT delta, reason, title, ref_type, ref_id, quest_date FROM coin_transactions
                WHERE user_id = ? ORDER BY id""", user);
        assertThat(lines).hasSize(4);
        for (Map<String, Object> line : lines.subList(0, 3)) {
            assertThat(line.get("reason")).isEqualTo(CoinLedger.DAILY_QUEST);
            assertThat(line.get("delta")).isEqualTo(10);
            assertThat(line.get("ref_type")).isEqualTo("QUEST");
            assertThat(String.valueOf(line.get("quest_date"))).isEqualTo(day.toString());
        }
        assertThat(lines.subList(0, 3).stream().map(l -> ((Number) l.get("ref_id")).longValue()).toList())
                .containsExactlyInAnyOrderElementsOf(questIds.values());
        assertThat(lines.subList(0, 3).stream().map(l -> (String) l.get("title")).toList())
                .containsExactlyInAnyOrder("Ôn 1 thẻ ghi nhớ", "Thuộc 1 mục mới", "Nghe câu tiếng Nhật hôm nay");
        assertThat(titles.values())
                .containsExactlyInAnyOrder("Ôn 1 thẻ ghi nhớ", "Thuộc 1 mục mới", "Nghe câu tiếng Nhật hôm nay");
        Map<String, Object> bonus = lines.get(3);
        long dayId = jdbc.queryForObject("SELECT id FROM user_quest_days WHERE user_id = ? AND quest_date = ?",
                Long.class, user, Date.valueOf(day));
        assertThat(bonus.get("reason")).isEqualTo(CoinLedger.DAILY_QUEST_ALL);
        assertThat(bonus.get("delta")).isEqualTo(20);
        assertThat(bonus.get("title")).isEqualTo("Nhiệm vụ ngày " + day.format(DateTimeFormatter.ofPattern("dd/MM")));
        assertThat(bonus.get("ref_type")).isEqualTo("QUEST_DAY");
        assertThat(((Number) bonus.get("ref_id")).longValue()).isEqualTo(dayId);
        assertThat(String.valueOf(bonus.get("quest_date"))).isEqualTo(day.toString());

        // Xu are not rank points
        assertThat(coinsOf(user)).isEqualTo(50);
        assertThat(pointsOf(user)).isZero();
        assertThat(rows("SELECT COUNT(*) FROM point_transactions WHERE user_id = ?", user)).isZero();
        assertThat(today.path("wallet").path("points").asInt()).isZero();
    }

    @Test
    void theDailyCapLimitsQuestXuAndTheBonusButQuestsStillComplete() throws Exception {
        LocalDate day = freshDay();
        isolateLibrary();
        long user = member();
        // 45 xu of quests received today already (e.g. quests made again by the test-only API)
        long earlier = 8_000_000_000L + ThreadLocalRandom.current().nextLong(1_000_000_000L);
        inTransaction(() -> ledger.add(user, 45, CoinLedger.DAILY_QUEST, "Ôn 10 thẻ ghi nhớ", CoinLedger.REF_QUEST,
                earlier, day));
        long only = tinyDay(user, day);
        long listened = quest(today(user), "PHRASE_OF_DAY").path("phrase").path("id").asLong();

        JsonNode change = answer(user, only, true).path("quests");
        List<Integer> paid = new ArrayList<>();
        change.path("justCompleted").forEach(q -> paid.add(q.path("coins").asInt()));
        assertThat(paid).containsExactlyInAnyOrder(5, 0);
        assertThat(change.path("coinsAwarded").asInt()).isEqualTo(5);
        assertThat(change.path("coinsToday").asInt()).isEqualTo(50);

        next();
        JsonNode last = data(listen(user, listened).andExpect(status().isOk()));
        assertThat(last.path("justCompleted").get(0).path("coins").asInt()).isZero();
        assertThat(last.path("bonusJustCompleted").asBoolean()).isTrue();
        assertThat(last.path("bonusCoins").asInt()).isZero();
        assertThat(last.path("coinsAwarded").asInt()).isZero();
        assertThat(last.path("coinsToday").asInt()).isEqualTo(50);

        JsonNode today = today(user);
        List<Integer> coins = new ArrayList<>();
        today.path("quests").forEach(q -> {
            assertThat(q.path("completed").asBoolean()).isTrue();
            coins.add(q.path("coins").asInt());
        });
        assertThat(coins).containsExactlyInAnyOrder(5, 0, 0);
        assertThat(today.path("bonus").path("coins").asInt()).isZero();
        assertThat(today.path("bonus").path("completed").asBoolean()).isTrue();
        assertThat(today.path("coinsToday").asInt()).isEqualTo(50);
        assertThat(coinsOf(user)).isEqualTo(50);
        // Nothing paid is not written: the earlier 45 and the 5
        assertThat(rows("SELECT COUNT(*) FROM coin_transactions WHERE user_id = ?", user)).isEqualTo(2);
        assertThat(rows("SELECT COUNT(*) FROM user_quest_days WHERE user_id = ? AND all_done_at IS NOT NULL", user))
                .isEqualTo(1);
        assertThat(pointsOf(user)).isZero();
    }

    @Test
    void aQuestOfAnotherDayDoesNotCountTowardTodaysCap() throws Exception {
        LocalDate day = freshDay();
        isolateLibrary();
        long user = member();
        long earlier = 8_000_000_000L + ThreadLocalRandom.current().nextLong(1_000_000_000L);
        inTransaction(() -> ledger.add(user, 50, CoinLedger.DAILY_QUEST, "Ôn 10 thẻ ghi nhớ", CoinLedger.REF_QUEST,
                earlier, day.minusDays(1)));
        long only = phrases(1).get(0);
        force(user, "FLASH_REVIEW");

        JsonNode change = answer(user, only, true).path("quests");

        assertThat(change.path("justCompleted").get(0).path("coins").asInt()).isEqualTo(10);
        assertThat(change.path("bonusCoins").asInt()).isEqualTo(20);
        assertThat(change.path("coinsToday").asInt()).isEqualTo(30);
        assertThat(coinsOf(user)).isEqualTo(80);
    }
}
