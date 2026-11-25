package com.sep490.vsa.feature.quest.contract;

import com.fasterxml.jackson.databind.JsonNode;
import org.assertj.core.api.SoftAssertions;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

import java.time.LocalDate;
import java.util.List;

/** 3.4 xu and the daily cap, 3.6 streak and its milestones, 5.8 the milestone notification. */
@TestPropertySource(properties = QaContractSupport.QA_DB)
class QaCoinsAndStreakContractFlowTest extends QaContractSupport {

    @Test
    void theDailyCapLimitsQuestAndBonusXuButQuestsStillComplete() throws Exception {
        freshDay();
        long m = member();
        List<Long> lib = payLibrary(5, m);
        seedQuestCoinsToday(m, 45);

        JsonNode t0 = today(m);
        SoftAssertions s = new SoftAssertions();
        s.assertThat(t0.path("coinsToday").asInt()).as("xu of quests already received today").isEqualTo(45);

        List<JsonNode> changes = finishPayDay(m, lib);
        JsonNode listen = changes.get(0);
        s.assertThat(codes(listen.path("justCompleted"))).containsExactly("PHRASE_OF_DAY");
        s.assertThat(listen.path("justCompleted").path(0).path("coins").asInt()).as("only 5 left under the cap")
                .isEqualTo(5);
        s.assertThat(listen.path("coinsAwarded").asInt()).isEqualTo(5);
        s.assertThat(listen.path("coinsToday").asInt()).isEqualTo(50);
        s.assertThat(listen.path("wallet").path("coins").asInt()).isEqualTo(50);

        JsonNode last = changes.get(changes.size() - 1);
        s.assertThat(codes(last.path("justCompleted"))).containsExactlyInAnyOrder("FLASH_REVIEW", "TOPIC");
        for (JsonNode j : last.path("justCompleted")) {
            s.assertThat(j.path("coins").asInt()).as(j.path("code") + " received over the cap").isZero();
        }
        s.assertThat(last.path("bonusJustCompleted").asBoolean()).isTrue();
        s.assertThat(last.path("bonusCoins").asInt()).as("bonus received over the cap").isZero();
        s.assertThat(last.path("coinsAwarded").asInt()).isZero();
        s.assertThat(last.path("coinsToday").asInt()).isEqualTo(50);
        s.assertThat(last.path("wallet").path("coins").asInt()).isEqualTo(50);
        s.assertThat(last.path("streak").path("todayDone").asBoolean()).isTrue();

        JsonNode t = today(m);
        s.assertThat(requireQuest(t, "PHRASE_OF_DAY").path("coins").asInt()).isEqualTo(5);
        s.assertThat(requireQuest(t, "FLASH_REVIEW").path("coins").asInt()).isZero();
        s.assertThat(requireQuest(t, "TOPIC").path("coins").asInt()).isZero();
        for (JsonNode q : t.path("quests")) {
            s.assertThat(q.path("completed").asBoolean()).as(q.path("code") + " completed").isTrue();
        }
        s.assertThat(t.path("bonus").path("completed").asBoolean()).isTrue();
        s.assertThat(t.path("coinsToday").asInt()).isEqualTo(50);
        s.assertThat(t.path("streak").path("todayDone").asBoolean()).isTrue();
        s.assertThat(coinsOf(m)).isEqualTo(50);
        s.assertThat(count("SELECT COALESCE(SUM(delta), 0) FROM coin_transactions WHERE user_id = ?", m))
                .as("users.coins follows the ledger").isEqualTo(coinsOf(m));
        s.assertThat(pointsOf(m)).isZero();
        s.assertThat(count("SELECT COUNT(*) FROM point_transactions WHERE user_id = ?", m)).isZero();
        s.assertAll();
    }

    @Test
    void theSeventhDayPaysTheMilestoneOnceOutsideTheCapAndNotifies() throws Exception {
        LocalDate day = freshDay();
        long m = member();
        List<Long> lib = payLibrary(5, m);
        for (int k = 1; k <= 6; k++) {
            seedDoneDay(m, day.minusDays(7 - k), k);
        }

        JsonNode t0 = today(m);
        SoftAssertions s = new SoftAssertions();
        s.assertThat(t0.path("streak").path("current").asInt()).as("today not done yet: run up to yesterday")
                .isEqualTo(6);
        s.assertThat(t0.path("streak").path("best").asInt()).isEqualTo(6);
        s.assertThat(t0.path("streak").path("todayDone").asBoolean()).isFalse();
        for (int i = 0; i < 6; i++) {
            s.assertThat(t0.path("recentDays").path(i).path("done").asBoolean()).as("recentDays[" + i + "]").isTrue();
        }
        s.assertThat(t0.path("recentDays").path(6).path("done").asBoolean()).isFalse();

        List<JsonNode> changes = finishPayDay(m, lib);
        JsonNode last = changes.get(changes.size() - 1);
        s.assertThat(last.path("bonusJustCompleted").asBoolean()).isTrue();
        s.assertThat(last.path("streak").path("current").asInt()).isEqualTo(7);
        s.assertThat(last.path("streak").path("todayDone").asBoolean()).isTrue();
        s.assertThat(last.path("streakMilestone").path("days").asInt()).isEqualTo(7);
        s.assertThat(last.path("streakMilestone").path("coins").asInt()).isEqualTo(50);
        s.assertThat(last.path("coinsAwarded").asInt()).as("10 + 10 + bonus 20 + milestone 50").isEqualTo(90);
        s.assertThat(last.path("coinsToday").asInt()).as("milestone not in coinsToday").isEqualTo(50);
        s.assertThat(last.path("wallet").path("coins").asInt()).isEqualTo(100);
        s.assertThat(changes.stream().mapToInt(c -> c.path("coinsAwarded").asInt()).sum()).isEqualTo(100);
        s.assertThat(changes.stream().filter(c -> !c.path("streakMilestone").isNull()).count())
                .as("milestone reached once").isEqualTo(1);

        JsonNode t = today(m);
        s.assertThat(t.path("streak").path("current").asInt()).isEqualTo(7);
        s.assertThat(t.path("streak").path("best").asInt()).isEqualTo(7);
        s.assertThat(t.path("streak").path("todayDone").asBoolean()).isTrue();
        s.assertThat(t.path("coinsToday").asInt()).isEqualTo(50);
        s.assertThat(t.path("wallet").path("coins").asInt()).isEqualTo(100);
        for (int i = 0; i < 7; i++) {
            s.assertThat(t.path("recentDays").path(i).path("done").asBoolean()).as("recentDays[" + i + "]").isTrue();
        }
        s.assertThat(jdbc.queryForObject("SELECT streak FROM user_quest_days WHERE user_id = ? AND quest_date = ?",
                Integer.class, m, day.toString())).isEqualTo(7);

        JsonNode streakLine = null;
        for (JsonNode line : ok(getAs(m, "/api/me/coins")).path("items")) {
            if ("QUEST_STREAK".equals(line.path("reason").asText())) {
                s.assertThat(streakLine).as("one QUEST_STREAK line").isNull();
                streakLine = line;
            }
        }
        s.assertThat(streakLine).as("QUEST_STREAK line in /api/me/coins").isNotNull();
        if (streakLine != null) {
            s.assertThat(streakLine.path("delta").asInt()).isEqualTo(50);
            s.assertThat(streakLine.path("reasonLabel").asText()).isEqualTo("Chuỗi ngày học");
            s.assertThat(streakLine.path("title").asText()).isEqualTo("Học 7 ngày liên tiếp");
            s.assertThat(streakLine.path("link").asText()).isEqualTo("/quests");
        }

        List<JsonNode> notes = notifications(m, "QUEST_STREAK");
        s.assertThat(notes).as("exactly one QUEST_STREAK notification").hasSize(1);
        if (!notes.isEmpty()) {
            s.assertThat(notes.get(0).path("content").asText()).isEqualTo("Bạn đã học 7 ngày liên tiếp. +50 xu.");
            s.assertThat(notes.get(0).path("link").asText()).isEqualTo("/quests");
        }

        // Nothing more that day.
        JsonNode again = answer(m, lib.get(0), false).path("quests");
        s.assertThat(again.path("coinsAwarded").asInt()).isZero();
        s.assertThat(again.path("streakMilestone").isNull()).isTrue();
        clock.advance(GAP);
        JsonNode relisten = listen(m, requireQuest(t, "PHRASE_OF_DAY").path("phrase").path("id").asLong());
        s.assertThat(relisten.path("coinsAwarded").asInt()).isZero();
        s.assertThat(relisten.path("streakMilestone").isNull()).isTrue();
        today(m);
        s.assertThat(coinsOf(m)).isEqualTo(100);
        s.assertThat(count("SELECT COUNT(*) FROM coin_transactions WHERE user_id = ? AND reason = 'QUEST_STREAK'", m))
                .isEqualTo(1);
        s.assertThat(notifications(m, "QUEST_STREAK")).hasSize(1);
        s.assertAll();
    }

    @Test
    void theFourteenthDayIsAMilestoneToo() throws Exception {
        LocalDate day = freshDay();
        long m = member();
        List<Long> lib = payLibrary(5, m);
        for (int k = 1; k <= 13; k++) {
            seedDoneDay(m, day.minusDays(14 - k), k);
        }

        List<JsonNode> changes = finishPayDay(m, lib);
        JsonNode last = changes.get(changes.size() - 1);
        SoftAssertions s = new SoftAssertions();
        s.assertThat(last.path("streak").path("current").asInt()).isEqualTo(14);
        s.assertThat(last.path("streakMilestone").path("days").asInt()).isEqualTo(14);
        s.assertThat(last.path("streakMilestone").path("coins").asInt()).isEqualTo(50);
        s.assertThat(jdbc.queryForList(
                "SELECT title FROM coin_transactions WHERE user_id = ? AND reason = 'QUEST_STREAK'", String.class, m))
                .containsExactly("Học 14 ngày liên tiếp");
        List<JsonNode> notes = notifications(m, "QUEST_STREAK");
        s.assertThat(notes).hasSize(1);
        if (!notes.isEmpty()) {
            s.assertThat(notes.get(0).path("content").asText()).isEqualTo("Bạn đã học 14 ngày liên tiếp. +50 xu.");
        }
        s.assertAll();
    }

    @Test
    void streakInItsThreeCases() throws Exception {
        LocalDate day = freshDay();
        long kept = member();      // done yesterday and the day before, best run 12 long ago
        long missed = member();    // last done the day before yesterday, after a run of 6
        seedDoneDay(kept, day.minusDays(30), 12);
        seedDoneDay(kept, day.minusDays(2), 1);
        seedDoneDay(kept, day.minusDays(1), 2);
        seedDoneDay(missed, day.minusDays(3), 5);
        seedDoneDay(missed, day.minusDays(2), 6);
        List<Long> lib = payLibrary(5, kept, missed);

        SoftAssertions s = new SoftAssertions();
        JsonNode homeKept = ok(getAs(kept, "/api/me/home")).path("quests");
        s.assertThat(homeKept.path("generated").asBoolean()).isFalse();
        s.assertThat(homeKept.path("streak").asInt()).as("Home streak before the quests are made").isEqualTo(2);
        s.assertThat(homeKept.path("todayDone").asBoolean()).isFalse();
        s.assertThat(ok(getAs(missed, "/api/me/home")).path("quests").path("streak").asInt()).isZero();

        JsonNode tk = today(kept);
        s.assertThat(tk.path("streak").path("current").asInt()).as("today not done, yesterday done").isEqualTo(2);
        s.assertThat(tk.path("streak").path("best").asInt()).isEqualTo(12);
        s.assertThat(tk.path("streak").path("todayDone").asBoolean()).isFalse();
        boolean[] expected = {false, false, false, false, true, true, false};
        for (int i = 0; i < 7; i++) {
            s.assertThat(tk.path("recentDays").path(i).path("done").asBoolean())
                    .as("recentDays[" + i + "] " + tk.path("recentDays").path(i).path("date")).isEqualTo(expected[i]);
        }
        JsonNode tm = today(missed);
        s.assertThat(tm.path("streak").path("current").asInt()).as("yesterday and today not done").isZero();
        s.assertThat(tm.path("streak").path("best").asInt()).isEqualTo(6);

        List<JsonNode> keptChanges = finishPayDay(kept, lib);
        JsonNode lastKept = keptChanges.get(keptChanges.size() - 1);
        s.assertThat(lastKept.path("streak").path("current").asInt()).isEqualTo(3);
        s.assertThat(lastKept.path("streakMilestone").isNull()).isTrue();
        tk = today(kept);
        s.assertThat(tk.path("streak").path("current").asInt()).as("today done").isEqualTo(3);
        s.assertThat(tk.path("streak").path("best").asInt()).isEqualTo(12);
        s.assertThat(tk.path("streak").path("todayDone").asBoolean()).isTrue();
        s.assertThat(jdbc.queryForObject("SELECT streak FROM user_quest_days WHERE user_id = ? AND quest_date = ?",
                Integer.class, kept, day.toString())).isEqualTo(3);
        s.assertThat(ok(getAs(kept, "/api/me/home")).path("quests").path("streak").asInt()).isEqualTo(3);

        List<JsonNode> missedChanges = finishPayDay(missed, lib);
        JsonNode lastMissed = missedChanges.get(missedChanges.size() - 1);
        s.assertThat(lastMissed.path("streak").path("current").asInt()).as("a new run after a missed day")
                .isEqualTo(1);
        s.assertThat(lastMissed.path("streakMilestone").isNull()).isTrue();
        tm = today(missed);
        s.assertThat(tm.path("streak").path("current").asInt()).isEqualTo(1);
        s.assertThat(tm.path("streak").path("best").asInt()).isEqualTo(6);
        s.assertThat(count("SELECT COUNT(*) FROM coin_transactions WHERE user_id IN (?, ?) AND reason = 'QUEST_STREAK'",
                kept, missed)).isZero();
        s.assertAll();
    }
}
