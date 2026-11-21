package com.sep490.vsa.feature.quest;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.sql.Date;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Japan days (contract 3.1: 0:00 JST is the boundary, resetAt), recentDays and the streak (3.6): today's when today is
 * done, yesterday's while today is still open, 0 after a missed day; every 7th day in a row pays its milestone once
 * and tells the Member.
 */
class QuestTimeAndStreakFlowTest extends QuestFlowSupport {

    @Test
    void answersAt235959And000000BelongToTwoJapanDays() throws Exception {
        LocalDate day = freshDay();
        isolateLibrary();
        List<Long> ids = phrases(3);

        long late = member();
        setJapanTime(day, LocalTime.of(23, 59, 59));
        answer(late, ids.get(0), true);
        long early = member();
        setJapanTime(day.plusDays(1), LocalTime.MIDNIGHT);
        answer(early, ids.get(0), true);

        assertThat(flashProgress(late, day)).isEqualTo(1);
        assertThat(rows("SELECT COUNT(*) FROM user_daily_quests WHERE user_id = ? AND quest_date <> ?",
                late, Date.valueOf(day))).isZero();
        assertThat(flashProgress(early, day.plusDays(1))).isEqualTo(1);
        assertThat(rows("SELECT COUNT(*) FROM user_daily_quests WHERE user_id = ? AND quest_date <> ?",
                early, Date.valueOf(day.plusDays(1)))).isZero();

        // The same Member across midnight: 23:59:59 counts for the old day, 00:00:00.5 for the new one
        long both = member();
        setJapanTime(day, LocalTime.of(23, 59, 59));
        answer(both, ids.get(0), true);
        assertThat(today(both).path("date").asText()).isEqualTo(day.toString());
        setJapanTime(day.plusDays(1), LocalTime.of(0, 0, 0, 500_000_000));
        JsonNode change = answer(both, ids.get(1), true).path("quests");
        assertThat(changedCodes(change)).contains("FLASH_REVIEW");
        JsonNode next = today(both);
        assertThat(next.path("date").asText()).isEqualTo(day.plusDays(1).toString());
        assertThat(quest(next, "FLASH_REVIEW").path("progress").asInt()).isEqualTo(1);
        assertThat(flashProgress(both, day)).isEqualTo(1);
    }

    private int flashProgress(long user, LocalDate day) {
        return rows("""
                SELECT progress FROM user_daily_quests WHERE user_id = ? AND quest_date = ? AND code = 'FLASH_REVIEW'""",
                user, Date.valueOf(day));
    }

    @Test
    void resetAtIsTheNextMidnightInJapan() throws Exception {
        LocalDate day = freshDay();
        isolateLibrary();
        phrases(2);
        long user = member();

        assertThat(today(user).path("resetAt").asText()).isEqualTo(day + "T15:00:00Z");
        setJapanTime(day, LocalTime.of(23, 59, 59));
        assertThat(today(user).path("resetAt").asText()).isEqualTo(day + "T15:00:00Z");
        setJapanTime(day.plusDays(1), LocalTime.MIDNIGHT);
        JsonNode next = today(user);
        assertThat(next.path("date").asText()).isEqualTo(day.plusDays(1).toString());
        assertThat(next.path("resetAt").asText()).isEqualTo(day.plusDays(1) + "T15:00:00Z");
    }

    @Test
    void recentDaysAreTheLastSevenDaysOldestFirst() throws Exception {
        LocalDate day = freshDay();
        isolateLibrary();
        phrases(2);
        long user = member();
        doneDay(user, day.minusDays(7), 1);          // outside the 7 days
        doneDay(user, day.minusDays(6), 1);
        doneDay(user, day.minusDays(3), 1);
        doneDay(user, day.minusDays(1), 1);
        jdbc.update("INSERT INTO user_quest_days (user_id, quest_date) VALUES (?, ?)", user,
                Date.valueOf(day.minusDays(2)));     // a row without all_done_at is not a day done

        JsonNode recent = today(user).path("recentDays");

        assertThat(recent).hasSize(7);
        List<String> dates = new ArrayList<>();
        List<Boolean> done = new ArrayList<>();
        recent.forEach(d -> {
            dates.add(d.path("date").asText());
            done.add(d.path("done").asBoolean());
        });
        List<String> expected = new ArrayList<>();
        for (int back = 6; back >= 0; back--) {
            expected.add(day.minusDays(back).toString());
        }
        assertThat(dates).isEqualTo(expected);
        assertThat(done).containsExactly(true, false, false, true, false, true, false);
    }

    @Test
    void streakWhenTodayIsDone() throws Exception {
        LocalDate day = freshDay();
        isolateLibrary();
        long only = phrases(1).get(0);
        long user = member();
        doneDay(user, day.minusDays(30), 12);
        doneDay(user, day.minusDays(2), 1);
        doneDay(user, day.minusDays(1), 2);
        force(user, "FLASH_REVIEW");

        JsonNode change = answer(user, only, true).path("quests");

        assertThat(change.path("bonusJustCompleted").asBoolean()).isTrue();
        assertThat(change.path("streak").path("current").asInt()).isEqualTo(3);
        assertThat(change.path("streak").path("todayDone").asBoolean()).isTrue();
        assertThat(change.path("streakMilestone").isNull()).isTrue();
        JsonNode streak = today(user).path("streak");
        assertThat(streak.path("current").asInt()).isEqualTo(3);
        assertThat(streak.path("best").asInt()).isEqualTo(12);
        assertThat(streak.path("todayDone").asBoolean()).isTrue();
        assertThat(streak.path("every").asInt()).isEqualTo(7);
        assertThat(streak.path("milestoneCoins").asInt()).isEqualTo(50);
        assertThat(rows("SELECT streak FROM user_quest_days WHERE user_id = ? AND quest_date = ?", user,
                Date.valueOf(day))).isEqualTo(3);
    }

    @Test
    void streakWhenTodayIsNotDoneYetButYesterdayWas() throws Exception {
        LocalDate day = freshDay();
        isolateLibrary();
        phrases(3);
        long user = member();
        doneDay(user, day.minusDays(2), 3);
        doneDay(user, day.minusDays(1), 4);

        JsonNode streak = today(user).path("streak");

        assertThat(streak.path("current").asInt()).isEqualTo(4);
        assertThat(streak.path("best").asInt()).isEqualTo(4);
        assertThat(streak.path("todayDone").asBoolean()).isFalse();
    }

    @Test
    void streakAfterAMissedDayIsZeroAndStartsAgainAtOne() throws Exception {
        LocalDate day = freshDay();
        isolateLibrary();
        long only = phrases(1).get(0);
        long user = member();
        doneDay(user, day.minusDays(2), 5);          // yesterday was missed

        JsonNode streak = today(user).path("streak");
        assertThat(streak.path("current").asInt()).isZero();
        assertThat(streak.path("best").asInt()).isEqualTo(5);
        assertThat(streak.path("todayDone").asBoolean()).isFalse();

        jdbc.update("DELETE FROM user_daily_quests WHERE user_id = ?", user);
        force(user, "FLASH_REVIEW");
        JsonNode change = answer(user, only, true).path("quests");
        assertThat(change.path("streak").path("current").asInt()).isEqualTo(1);
        assertThat(change.path("streak").path("todayDone").asBoolean()).isTrue();
    }

    @Test
    void theSeventhDayInARowPaysTheMilestoneOnceAndTellsTheMember() throws Exception {
        LocalDate day = freshDay();
        isolateLibrary();
        long only = phrases(1).get(0);
        long user = member();
        for (int n = 1; n <= 6; n++) {
            doneDay(user, day.minusDays(7 - n), n);
        }
        force(user, "FLASH_REVIEW");

        JsonNode change = answer(user, only, true).path("quests");

        assertThat(change.path("streak").path("current").asInt()).isEqualTo(7);
        assertThat(change.path("streakMilestone").path("days").asInt()).isEqualTo(7);
        assertThat(change.path("streakMilestone").path("coins").asInt()).isEqualTo(50);
        assertThat(change.path("bonusJustCompleted").asBoolean()).isTrue();
        assertThat(change.path("bonusCoins").asInt()).isEqualTo(20);
        assertThat(change.path("coinsAwarded").asInt()).isEqualTo(80);
        assertThat(change.path("coinsToday").asInt()).isEqualTo(30);     // the milestone is outside the cap
        assertThat(change.path("wallet").path("coins").asInt()).isEqualTo(80);

        long dayId = jdbc.queryForObject("SELECT id FROM user_quest_days WHERE user_id = ? AND quest_date = ?",
                Long.class, user, Date.valueOf(day));
        List<Map<String, Object>> streakLines = jdbc.queryForList("""
                SELECT delta, title, ref_type, ref_id, quest_date FROM coin_transactions
                WHERE user_id = ? AND reason = 'QUEST_STREAK'""", user);
        assertThat(streakLines).hasSize(1);
        assertThat(streakLines.get(0).get("delta")).isEqualTo(50);
        assertThat(streakLines.get(0).get("title")).isEqualTo("Học 7 ngày liên tiếp");
        assertThat(streakLines.get(0).get("ref_type")).isEqualTo("QUEST_DAY");
        assertThat(((Number) streakLines.get(0).get("ref_id")).longValue()).isEqualTo(dayId);
        List<Map<String, Object>> notes = jdbc.queryForList(
                "SELECT type, content, ref_type, ref_id FROM notifications WHERE user_id = ?", user);
        assertThat(notes).hasSize(1);
        assertThat(notes.get(0).get("type")).isEqualTo("QUEST_STREAK");
        assertThat(notes.get(0).get("content")).isEqualTo("Bạn đã học 7 ngày liên tiếp. +50 xu.");
        assertThat(notes.get(0).get("ref_type")).isEqualTo("QUEST");
        assertThat(((Number) notes.get(0).get("ref_id")).longValue()).isEqualTo(dayId);

        // Nothing more that day: the milestone and its notification come once
        long another = phrase("konbini", "PHRASE");
        next();
        JsonNode after = answer(user, another, true).path("quests");
        assertThat(after.path("streakMilestone").isNull()).isTrue();
        assertThat(after.path("coinsAwarded").asInt()).isZero();
        assertThat(rows("SELECT COUNT(*) FROM coin_transactions WHERE user_id = ? AND reason = 'QUEST_STREAK'", user))
                .isEqualTo(1);
        assertThat(rows("SELECT COUNT(*) FROM notifications WHERE user_id = ?", user)).isEqualTo(1);
        assertThat(coinsOf(user)).isEqualTo(80);
    }

    @Test
    void aStreakThatIsNotAMultipleOfSevenPaysNoMilestone() throws Exception {
        LocalDate day = freshDay();
        isolateLibrary();
        long only = phrases(1).get(0);
        long user = member();
        for (int n = 1; n <= 7; n++) {
            doneDay(user, day.minusDays(8 - n), n);
        }
        force(user, "FLASH_REVIEW");

        JsonNode change = answer(user, only, true).path("quests");

        assertThat(change.path("streak").path("current").asInt()).isEqualTo(8);
        assertThat(change.path("streakMilestone").isNull()).isTrue();
        assertThat(rows("SELECT COUNT(*) FROM notifications WHERE user_id = ?", user)).isZero();
    }
}
