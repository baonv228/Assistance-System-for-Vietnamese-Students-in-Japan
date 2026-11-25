package com.sep490.vsa.feature.quest;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Counting flash card answers (contract 3.3, 3.7): each entry once per quest, "Bỏ đánh dấu" never takes anything back,
 * LEARN_NEW once per entry ever, REVIEW_LEARNING and LEARN_NEW judged on the marks right before the answer, TOPIC only
 * for its topic, one answer for several quests, never past the target, and answers too close together not counted.
 */
class QuestCountingFlowTest extends QuestFlowSupport {

    private int progress(long user, String code) throws Exception {
        return quest(today(user), code).path("progress").asInt();
    }

    @Test
    void theSameEntryAnsweredTwiceCountsOnce() throws Exception {
        freshDay();
        isolateLibrary();
        long user = member();
        List<Long> ids = phrases(3);
        force(user, "FLASH_REVIEW");

        JsonNode first = answer(user, ids.get(0), true).path("quests");
        next();
        JsonNode second = answer(user, ids.get(0), false).path("quests");

        assertThat(changedCodes(first)).containsExactly("FLASH_REVIEW");
        assertThat(first.path("changed").get(0).path("progress").asInt()).isEqualTo(1);
        assertThat(first.path("changed").get(0).path("target").asInt()).isEqualTo(3);
        assertThat(first.path("changed").get(0).path("completed").asBoolean()).isFalse();
        assertThat(second.path("changed")).isEmpty();
        assertThat(second.path("justCompleted")).isEmpty();
        assertThat(progress(user, "FLASH_REVIEW")).isEqualTo(1);
        assertThat(rows("""
                SELECT COUNT(*) FROM user_quest_items i JOIN user_daily_quests q ON q.id = i.user_daily_quest_id
                WHERE q.user_id = ?""", user)).isEqualTo(1);
    }

    @Test
    void boDanhDauNeverLowersTheProgress() throws Exception {
        freshDay();
        isolateLibrary();
        long user = member();
        List<Long> ids = phrases(3);
        force(user, "FLASH_REVIEW", "LEARN_NEW");
        answer(user, ids.get(0), true);

        deleteAs(user, "/api/phrases/" + ids.get(0) + "/flash").andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Đã bỏ đánh dấu."))
                .andExpect(jsonPath("$.data.learning").value(false))
                .andExpect(jsonPath("$.data.learned").value(false));

        JsonNode today = today(user);
        assertThat(quest(today, "FLASH_REVIEW").path("progress").asInt()).isEqualTo(1);
        assertThat(quest(today, "LEARN_NEW").path("progress").asInt()).isEqualTo(1);
    }

    @Test
    void flippingLearnedAndBackDoesNotAddAnything() throws Exception {
        freshDay();
        isolateLibrary();
        long user = member();
        List<Long> ids = phrases(3);
        force(user, "FLASH_REVIEW", "LEARN_NEW");

        assertThat(changedCodes(answer(user, ids.get(0), true).path("quests")))
                .containsExactlyInAnyOrder("FLASH_REVIEW", "LEARN_NEW");
        next();
        assertThat(answer(user, ids.get(0), false).path("quests").path("changed")).isEmpty();
        next();
        deleteAs(user, "/api/phrases/" + ids.get(0) + "/flash").andExpect(status().isOk());
        next();
        assertThat(answer(user, ids.get(0), true).path("quests").path("changed")).isEmpty();

        JsonNode today = today(user);
        assertThat(quest(today, "FLASH_REVIEW").path("progress").asInt()).isEqualTo(1);
        assertThat(quest(today, "LEARN_NEW").path("progress").asInt()).isEqualTo(1);
    }

    @Test
    void learnNewNeverCountsAnEntryCountedOnAnEarlierDay() throws Exception {
        freshDay();
        isolateLibrary();
        long user = member();
        List<Long> ids = phrases(4);
        force(user, "LEARN_NEW");
        assertThat(changedCodes(answer(user, ids.get(0), true).path("quests"))).containsExactly("LEARN_NEW");
        // "Bỏ đánh dấu": the entry is not "Đã thuộc" any more, but it was counted for LEARN_NEW once already
        deleteAs(user, "/api/phrases/" + ids.get(0) + "/flash").andExpect(status().isOk());

        clock.advance(Duration.ofDays(1));
        JsonNode tomorrow = force(user, "LEARN_NEW");
        assertThat(quest(tomorrow, "LEARN_NEW").path("target").asInt()).isEqualTo(3);   // 3 entries still qualify

        assertThat(answer(user, ids.get(0), true).path("quests").path("changed")).isEmpty();
        next();
        assertThat(changedCodes(answer(user, ids.get(1), true).path("quests"))).containsExactly("LEARN_NEW");
        assertThat(progress(user, "LEARN_NEW")).isEqualTo(1);
    }

    @Test
    void learnNewNeedsDaThuocForAnEntryThatWasNotDaThuoc() throws Exception {
        freshDay();
        isolateLibrary();
        long user = member();
        List<Long> ids = phrases(4);
        mark(user, ids.get(3), true);                // already "Đã thuộc" before the day
        force(user, "LEARN_NEW");

        assertThat(answer(user, ids.get(0), false).path("quests").path("changed")).isEmpty();   // "Chưa thuộc"
        next();
        assertThat(answer(user, ids.get(3), true).path("quests").path("changed")).isEmpty();    // was "Đã thuộc"
        next();
        assertThat(changedCodes(answer(user, ids.get(0), true).path("quests"))).containsExactly("LEARN_NEW");
        assertThat(progress(user, "LEARN_NEW")).isEqualTo(1);
    }

    @Test
    void reviewLearningLooksAtTheMarkRightBeforeTheAnswer() throws Exception {
        freshDay();
        isolateLibrary();
        long user = member();
        List<Long> ids = phrases(4);
        long a = ids.get(0);
        long b = ids.get(1);
        mark(user, a, false);
        mark(user, ids.get(2), false);
        mark(user, ids.get(3), false);
        JsonNode day = force(user, "REVIEW_LEARNING");
        assertThat(quest(day, "REVIEW_LEARNING").path("target").asInt()).isEqualTo(3);

        // a was "Chưa thuộc": counts, whatever the answer
        assertThat(changedCodes(answer(user, a, true).path("quests"))).containsExactly("REVIEW_LEARNING");
        next();
        // b had no answer: "Chưa thuộc" now makes it one, but it was not one before
        assertThat(answer(user, b, false).path("quests").path("changed")).isEmpty();
        next();
        // b was "Chưa thuộc" right before this answer: counts
        assertThat(changedCodes(answer(user, b, false).path("quests"))).containsExactly("REVIEW_LEARNING");
        next();
        assertThat(answer(user, a, false).path("quests").path("changed")).isEmpty();      // counted already today
        assertThat(progress(user, "REVIEW_LEARNING")).isEqualTo(2);
    }

    @Test
    void topicCountsOnlyEntriesOfItsTopic() throws Exception {
        freshDay();
        isolateLibrary();
        long user = member();
        String topic = topic("Chủ đề đếm");
        List<Long> inTopic = phrases(5, topic);
        List<Long> others = phrases(3);
        force(user, "TOPIC");

        assertThat(answer(user, others.get(0), true).path("quests").path("changed")).isEmpty();
        next();
        assertThat(changedCodes(answer(user, inTopic.get(0), false).path("quests"))).containsExactly("TOPIC");
        assertThat(progress(user, "TOPIC")).isEqualTo(1);
    }

    @Test
    void oneAnswerMovesSeveralQuests() throws Exception {
        freshDay();
        isolateLibrary();
        long user = member();
        String topic = topic("Chủ đề chung");
        List<Long> ids = phrases(6, topic);
        force(user, "FLASH_REVIEW", "LEARN_NEW", "TOPIC");

        JsonNode change = answer(user, ids.get(0), true).path("quests");

        assertThat(changedCodes(change)).containsExactlyInAnyOrder("FLASH_REVIEW", "LEARN_NEW", "TOPIC");
        change.path("changed").forEach(c -> assertThat(c.path("progress").asInt()).isEqualTo(1));
        assertThat(change.path("justCompleted")).isEmpty();
        assertThat(change.path("coinsAwarded").asInt()).isZero();
    }

    @Test
    void progressNeverPassesTheTarget() throws Exception {
        freshDay();
        isolateLibrary();
        long user = member();
        List<Long> ids = phrases(2);
        force(user, "FLASH_REVIEW");
        answer(user, ids.get(0), true);
        next();
        JsonNode done = answer(user, ids.get(1), true).path("quests");
        assertThat(done.path("changed").get(0).path("progress").asInt()).isEqualTo(2);
        assertThat(done.path("changed").get(0).path("completed").asBoolean()).isTrue();

        // An entry added after the day was made: the quest is completed, nothing more is counted
        long later = phrase("konbini", "PHRASE");
        next();
        assertThat(answer(user, later, true).path("quests").path("changed")).isEmpty();
        JsonNode flash = quest(today(user), "FLASH_REVIEW");
        assertThat(flash.path("progress").asInt()).isEqualTo(2);
        assertThat(flash.path("target").asInt()).isEqualTo(2);
        assertThat(rows("SELECT COUNT(*) FROM user_daily_quests WHERE user_id = ? AND progress > target", user)).isZero();
    }

    @Test
    void answersTooCloseTogetherAreKeptAsMarksButNotCounted() throws Exception {
        freshDay();
        isolateLibrary();
        long user = member();
        List<Long> ids = phrases(4);
        force(user, "FLASH_REVIEW");

        answer(user, ids.get(0), true);
        clock.advance(Duration.ofMillis(1499));
        JsonNode fast = answer(user, ids.get(1), true);

        assertThat(fast.path("learned").asBoolean()).isTrue();
        assertThat(fast.path("quests").path("changed")).isEmpty();
        assertThat(rows("SELECT COUNT(*) FROM user_phrase_marks WHERE user_id = ? AND phrase_id = ? AND is_learned",
                user, ids.get(1))).isEqualTo(1);
        assertThat(progress(user, "FLASH_REVIEW")).isEqualTo(1);
        assertThat(rows("SELECT COUNT(*) FROM phrase_answer_log WHERE user_id = ?", user)).isEqualTo(2);

        // The spacing is measured from the previous answer, counted or not
        clock.advance(Duration.ofMillis(1499));
        assertThat(answer(user, ids.get(2), true).path("quests").path("changed")).isEmpty();
        assertThat(progress(user, "FLASH_REVIEW")).isEqualTo(1);
    }

    @Test
    void anAnswerExactly1500MillisecondsLaterCounts() throws Exception {
        freshDay();
        isolateLibrary();
        long user = member();
        List<Long> ids = phrases(3);
        force(user, "FLASH_REVIEW");

        answer(user, ids.get(0), false);
        clock.advance(Duration.ofMillis(1500));
        JsonNode change = answer(user, ids.get(1), false).path("quests");

        assertThat(changedCodes(change)).containsExactly("FLASH_REVIEW");
        assertThat(progress(user, "FLASH_REVIEW")).isEqualTo(2);
    }
}
