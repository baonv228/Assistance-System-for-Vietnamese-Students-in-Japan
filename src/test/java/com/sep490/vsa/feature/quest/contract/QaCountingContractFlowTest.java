package com.sep490.vsa.feature.quest.contract;

import com.fasterxml.jackson.databind.JsonNode;
import org.assertj.core.api.SoftAssertions;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Section 3 from the outside: when quests are made, what an answer counts for, the 1500 ms rule, the Japan day
 * boundary, the phrase of the day.
 */
@TestPropertySource(properties = QaContractSupport.QA_DB)
class QaCountingContractFlowTest extends QaContractSupport {

    // ------------------------------------------------------------ 3.2 generation

    @Test
    void theSameSetAllDayEvenWhenTheLibraryChangesAndANewSetTheNextDay() throws Exception {
        LocalDate day = freshDay();
        long m = member();
        List<Long> lib = payLibrary(6, m);

        JsonNode first = today(m);
        // Library changes during the day: more entries of "pay", two deleted.
        for (int i = 0; i < 6; i++) {
            long id = phrase("konbini", "PHRASE", "pay");
            mark(m, id, true);
        }
        jdbc.update("UPDATE phrases SET deleted_at = ? WHERE id IN (?, ?)", Timestamp.from(Instant.now()),
                lib.get(0), lib.get(1));
        clock.advance(Duration.ofHours(3));
        JsonNode again = today(m);

        SoftAssertions s = new SoftAssertions();
        s.assertThat(again.path("quests")).as("same quests (ids, targets, titles) all day").isEqualTo(first.path("quests"));
        s.assertThat(count("SELECT COUNT(*) FROM user_daily_quests WHERE user_id = ? AND quest_date = ?", m,
                day.toString())).isEqualTo(3);

        setJapanTime(day.plusDays(1), LocalTime.of(9, 0));
        JsonNode next = today(m);
        s.assertThat(next.path("date").asText()).isEqualTo(day.plusDays(1).toString());
        List<Long> oldIds = new ArrayList<>();
        first.path("quests").forEach(q -> oldIds.add(q.path("id").asLong()));
        for (JsonNode q : next.path("quests")) {
            s.assertThat(oldIds).as("a new set the next day").doesNotContain(q.path("id").asLong());
            s.assertThat(q.path("progress").asInt()).isZero();
        }
        s.assertThat(next.path("quests").path(0).path("code").asText()).isEqualTo("FLASH_REVIEW");
        s.assertThat(requireQuest(next, "FLASH_REVIEW").path("target").asInt())
                .as("next day: 10 visible entries now").isEqualTo(10);
        s.assertAll();
    }

    @Test
    void deletedEntriesAreNeverCountedAsFeasible() throws Exception {
        freshDay();
        long m = member();
        List<Long> visible = payLibrary(4, m);
        List<Long> gone = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            long id = phrase("konbini", "PHRASE", "pay");
            mark(m, id, true);
            gone.add(id);
        }
        for (long id : gone) {
            jdbc.update("UPDATE phrases SET deleted_at = ? WHERE id = ?", Timestamp.from(Instant.now()), id);
        }

        JsonNode t = today(m);
        SoftAssertions s = new SoftAssertions();
        s.assertThat(requireQuest(t, "FLASH_REVIEW").path("target").asInt())
                .as("FLASH target = min(10, 4 visible entries)").isEqualTo(4);
        JsonNode topic = quest(t, "TOPIC");
        if (topic != null) {
            s.assertThat(topic.path("target").asInt()).as("TOPIC never needs deleted entries").isLessThanOrEqualTo(4);
        }
        JsonNode pod = quest(t, "PHRASE_OF_DAY");
        if (pod != null) {
            s.assertThat(visible).as("phrase of the day is not a deleted entry")
                    .contains(pod.path("phrase").path("id").asLong());
        }
        s.assertAll();
    }

    @Test
    void answeringBeforeOpeningTheQuestsMakesThemAndCounts() throws Exception {
        LocalDate day = freshDay();
        long m = member();
        List<Long> lib = payLibrary(6, m);

        JsonNode qc = answer(m, lib.get(0), true).path("quests");
        SoftAssertions s = new SoftAssertions();
        s.assertThat(changedCodes(qc)).containsExactlyInAnyOrder("FLASH_REVIEW", "TOPIC");
        s.assertThat(count("SELECT COUNT(*) FROM user_daily_quests WHERE user_id = ? AND quest_date = ?", m,
                day.toString())).isEqualTo(3);
        JsonNode t = today(m);
        s.assertThat(progress(t, "FLASH_REVIEW")).isEqualTo(1);
        s.assertThat(progress(t, "TOPIC")).isEqualTo(1);
        s.assertThat(changed(qc, "FLASH_REVIEW").path("id").asLong())
                .isEqualTo(requireQuest(t, "FLASH_REVIEW").path("id").asLong());
        s.assertAll();
    }

    @Test
    void anEventBeforeOpeningTheQuestsMakesThemAndCounts() throws Exception {
        LocalDate day = freshDay();
        long m = member();
        payLibrary(6, m);
        long phraseOfDay = homePhraseId();

        JsonNode qc = listen(m, phraseOfDay);
        SoftAssertions s = new SoftAssertions();
        s.assertThat(count("SELECT COUNT(*) FROM user_daily_quests WHERE user_id = ? AND quest_date = ?", m,
                day.toString())).isEqualTo(3);
        s.assertThat(changedCodes(qc)).containsExactly("PHRASE_OF_DAY");
        s.assertThat(qc.path("coinsAwarded").asInt()).isEqualTo(10);
        s.assertThat(requireQuest(today(m), "PHRASE_OF_DAY").path("completed").asBoolean()).isTrue();
        s.assertAll();
    }

    @Test
    void theMemberHomeNeverMakesTheQuests() throws Exception {
        LocalDate day = freshDay();
        long m = member();
        payLibrary(6, m);

        JsonNode hq = ok(getAs(m, "/api/me/home")).path("quests");
        assertThat(hq.path("generated").asBoolean()).isFalse();
        assertThat(count("SELECT COUNT(*) FROM user_daily_quests WHERE user_id = ?", m)).isZero();
        assertThat(count("SELECT COUNT(*) FROM user_quest_days WHERE user_id = ?", m)).isZero();
        assertThat(count("SELECT COUNT(*) FROM coin_transactions WHERE user_id = ?", m)).isZero();
        assertThat(day).isEqualTo(japanToday());
    }

    // ------------------------------------------------------------ 3.3 / 3.7 counting

    @Test
    void anEntryCountsOncePerQuestPerDayAndUnmarkingNeverLowersProgress() throws Exception {
        freshDay();
        long m = member();
        List<Long> lib = payLibrary(6, m);
        today(m);

        long p = lib.get(0);
        SoftAssertions s = new SoftAssertions();
        s.assertThat(changedCodes(answer(m, p, true).path("quests"))).containsExactlyInAnyOrder("FLASH_REVIEW", "TOPIC");
        s.assertThat(changedCodes(answer(m, p, false).path("quests"))).as("same entry, other answer").isEmpty();
        JsonNode unmark = ok(deleteAs(m, "/api/phrases/" + p + "/flash"));
        s.assertThat(unmark.path("learned").asBoolean()).isFalse();
        JsonNode t = today(m);
        s.assertThat(progress(t, "FLASH_REVIEW")).as("after Bỏ đánh dấu").isEqualTo(1);
        s.assertThat(progress(t, "TOPIC")).as("after Bỏ đánh dấu").isEqualTo(1);
        s.assertThat(changedCodes(answer(m, p, true).path("quests"))).as("after Bỏ đánh dấu, Đã thuộc again").isEmpty();
        clock.advance(GAP);
        ok(deleteAs(m, "/api/phrases/" + p + "/flash"));
        s.assertThat(changedCodes(answer(m, p, false).path("quests"))).isEmpty();
        t = today(m);
        s.assertThat(progress(t, "FLASH_REVIEW")).isEqualTo(1);
        s.assertThat(progress(t, "TOPIC")).isEqualTo(1);
        s.assertAll();
    }

    @Test
    void answersCloserThan1500MsAreSavedButNotCounted() throws Exception {
        freshDay();
        long m = member();
        List<Long> lib = payLibrary(6, m);
        today(m);

        SoftAssertions s = new SoftAssertions();
        s.assertThat(changedCodes(answer(m, lib.get(0), true).path("quests"))).as("first answer").isNotEmpty();

        clock.advance(Duration.ofMillis(1000));
        JsonNode fast = ok(flash(m, lib.get(1), false));
        s.assertThat(fast.path("learning").asBoolean()).as("mark saved").isTrue();
        s.assertThat(changedCodes(fast.path("quests"))).as("1000 ms after the previous answer").isEmpty();

        clock.advance(Duration.ofMillis(1000));
        s.assertThat(changedCodes(ok(flash(m, lib.get(2), false)).path("quests")))
                .as("2000 ms after the last counted answer but 1000 ms after the previous logged one").isEmpty();

        clock.advance(Duration.ofMillis(1499));
        s.assertThat(changedCodes(ok(flash(m, lib.get(3), false)).path("quests"))).as("1499 ms").isEmpty();

        clock.advance(Duration.ofMillis(1500));
        s.assertThat(changedCodes(ok(flash(m, lib.get(4), false)).path("quests"))).as("exactly 1500 ms")
                .containsExactlyInAnyOrder("FLASH_REVIEW", "TOPIC");

        JsonNode t = today(m);
        s.assertThat(progress(t, "FLASH_REVIEW")).isEqualTo(2);
        s.assertThat(progress(t, "TOPIC")).isEqualTo(2);
        JsonNode cards = ok(getAs(m, "/api/phrases"));
        for (JsonNode card : cards) {
            long id = card.path("id").asLong();
            if (id == lib.get(1) || id == lib.get(2) || id == lib.get(3) || id == lib.get(4)) {
                s.assertThat(card.path("learning").asBoolean()).as("mark of " + id + " saved").isTrue();
            }
        }
        s.assertThat(count("SELECT COUNT(*) FROM phrase_answer_log WHERE user_id = ?", m))
                .as("every successful answer logged").isEqualTo(5);
        s.assertAll();
    }

    @Test
    void oneAnswerMovesSeveralQuestsTopicCountsOnlyItsTopicAndProgressNeverPassesTarget() throws Exception {
        freshDay();
        long m = member();
        List<Long> pay = payLibrary(11, m);
        long other = phrase("konbini", "PHRASE");
        mark(m, other, true);
        today(m);

        SoftAssertions s = new SoftAssertions();
        JsonNode t0 = today(m);
        s.assertThat(requireQuest(t0, "FLASH_REVIEW").path("target").asInt()).isEqualTo(10);

        JsonNode qc = answer(m, pay.get(0), true).path("quests");
        s.assertThat(changedCodes(qc)).as("a 'Thanh toán' entry").containsExactlyInAnyOrder("FLASH_REVIEW", "TOPIC");
        qc = answer(m, other, true).path("quests");
        s.assertThat(changedCodes(qc)).as("an entry of no topic").containsExactly("FLASH_REVIEW");
        for (int i = 1; i <= 3; i++) {
            answer(m, pay.get(i), true);
        }
        qc = answer(m, pay.get(4), true).path("quests");
        s.assertThat(codes(qc.path("justCompleted"))).containsExactly("TOPIC");
        s.assertThat(changed(qc, "TOPIC").path("progress").asInt()).isEqualTo(5);
        qc = answer(m, pay.get(5), true).path("quests");
        s.assertThat(changedCodes(qc)).as("TOPIC already at its target").containsExactly("FLASH_REVIEW");
        for (int i = 6; i <= 8; i++) {
            answer(m, pay.get(i), true);
        }
        // FLASH: pay 0..8 + other = 10
        JsonNode t = today(m);
        s.assertThat(progress(t, "FLASH_REVIEW")).isEqualTo(10);
        s.assertThat(requireQuest(t, "FLASH_REVIEW").path("completed").asBoolean()).isTrue();
        qc = answer(m, pay.get(9), true).path("quests");
        s.assertThat(changedCodes(qc)).as("11th entry").isEmpty();
        t = today(m);
        s.assertThat(progress(t, "FLASH_REVIEW")).as("never above target").isEqualTo(10);
        s.assertThat(progress(t, "TOPIC")).as("never above target").isEqualTo(5);
        s.assertAll();
    }

    @Test
    void learnNewAndReviewLearningReadTheStateBeforeTheAnswer() throws Exception {
        freshDay();
        long m = member();
        Vocab v = vocabLibrary(5, 4, 1, m);
        JsonNode t0 = today(m);
        assertThat(codes(t0.path("quests"))).containsExactlyInAnyOrder("FLASH_REVIEW", "LEARN_NEW", "REVIEW_LEARNING");
        long n1 = v.none().get(0);
        long n2 = v.none().get(1);
        long c1 = v.learning().get(0);
        long l1 = v.learned().get(0);

        SoftAssertions s = new SoftAssertions();
        s.assertThat(changedCodes(answer(m, n1, false).path("quests")))
                .as("new entry answered Chưa thuộc: no LEARN_NEW, was not Chưa thuộc before")
                .containsExactly("FLASH_REVIEW");
        s.assertThat(changedCodes(answer(m, n1, false).path("quests")))
                .as("same entry again: it WAS Chưa thuộc just before → REVIEW_LEARNING only")
                .containsExactly("REVIEW_LEARNING");
        s.assertThat(changedCodes(answer(m, l1, true).path("quests")))
                .as("entry already Đã thuộc answered Đã thuộc: no LEARN_NEW").containsExactly("FLASH_REVIEW");
        s.assertThat(changedCodes(answer(m, c1, true).path("quests")))
                .as("Chưa thuộc entry answered Đã thuộc: all three")
                .containsExactlyInAnyOrder("FLASH_REVIEW", "LEARN_NEW", "REVIEW_LEARNING");
        s.assertThat(changedCodes(answer(m, c1, true).path("quests"))).as("same entry again").isEmpty();
        clock.advance(GAP);
        ok(deleteAs(m, "/api/phrases/" + c1 + "/flash"));
        s.assertThat(changedCodes(answer(m, c1, true).path("quests")))
                .as("after Bỏ đánh dấu, LEARN_NEW already counted it today").isEmpty();
        s.assertThat(changedCodes(answer(m, n2, true).path("quests"))).as("new entry answered Đã thuộc")
                .containsExactlyInAnyOrder("FLASH_REVIEW", "LEARN_NEW");

        JsonNode t = today(m);
        s.assertThat(progress(t, "FLASH_REVIEW")).isEqualTo(4);
        s.assertThat(progress(t, "LEARN_NEW")).isEqualTo(2);
        s.assertThat(progress(t, "REVIEW_LEARNING")).isEqualTo(2);
        s.assertAll();
    }

    @Test
    void learnNewNeverCountsAnEntryAlreadyCountedOnAnEarlierDay() throws Exception {
        LocalDate day = freshDay();
        long m = member();
        Vocab v = vocabLibrary(6, 4, 0, m);
        assertThat(codes(today(m).path("quests"))).contains("LEARN_NEW");
        long c1 = v.learning().get(0);
        assertThat(changedCodes(answer(m, c1, true).path("quests"))).contains("LEARN_NEW");
        clock.advance(GAP);
        ok(deleteAs(m, "/api/phrases/" + c1 + "/flash"));

        setJapanTime(day.plusDays(1), LocalTime.NOON);
        JsonNode next = today(m);
        assertThat(codes(next.path("quests")))
                .as("only LEARN_NEW and REVIEW_LEARNING are feasible the next day")
                .containsExactlyInAnyOrder("FLASH_REVIEW", "LEARN_NEW", "REVIEW_LEARNING");
        JsonNode qc = answer(m, c1, true).path("quests");
        assertThat(changedCodes(qc)).as("counted for LEARN_NEW yesterday: never again").containsExactly("FLASH_REVIEW");
    }

    // ------------------------------------------------------------ 3.1 day boundary

    @Test
    void anAnswerAt235959JstBelongsToTheOldDayAndAt000000ToTheNewOne() throws Exception {
        LocalDate day = freshDay();
        long a = member();
        long b = member();
        List<Long> lib = payLibrary(6, a, b);

        setJapanTime(day, LocalTime.of(23, 59, 59));
        JsonNode late = ok(flash(a, lib.get(0), true)).path("quests");
        JsonNode lateToday = today(a);
        SoftAssertions s = new SoftAssertions();
        s.assertThat(lateToday.path("date").asText()).isEqualTo(day.toString());
        s.assertThat(instant(lateToday.get("resetAt"))).isEqualTo(japanMidnight(day.plusDays(1)));
        s.assertThat(changedCodes(late)).containsExactlyInAnyOrder("FLASH_REVIEW", "TOPIC");
        long oldFlash = requireQuest(lateToday, "FLASH_REVIEW").path("id").asLong();
        s.assertThat(changed(late, "FLASH_REVIEW").path("id").asLong()).isEqualTo(oldFlash);

        clock.set(japanMidnight(day.plusDays(1)));
        JsonNode first = ok(flash(b, lib.get(0), true)).path("quests");
        String dateOfB = jdbc.queryForObject(
                "SELECT DATE_FORMAT(quest_date, '%Y-%m-%d') FROM user_daily_quests WHERE id = ?", String.class,
                changed(first, "FLASH_REVIEW").path("id").asLong());
        s.assertThat(dateOfB).as("answer at 00:00:00 JST counts for the new day").isEqualTo(day.plusDays(1).toString());

        clock.set(japanMidnight(day.plusDays(1)).plusMillis(500));
        JsonNode early = ok(flash(a, lib.get(1), true)).path("quests");
        JsonNode newToday = today(a);
        s.assertThat(newToday.path("date").asText()).isEqualTo(day.plusDays(1).toString());
        s.assertThat(instant(newToday.get("resetAt"))).isEqualTo(japanMidnight(day.plusDays(2)));
        s.assertThat(changed(early, "FLASH_REVIEW")).as("counted the new day").isNotNull();
        if (changed(early, "FLASH_REVIEW") != null) {
            s.assertThat(changed(early, "FLASH_REVIEW").path("id").asLong()).isNotEqualTo(oldFlash);
            s.assertThat(changed(early, "FLASH_REVIEW").path("progress").asInt()).isEqualTo(1);
        }
        s.assertThat(progress(newToday, "FLASH_REVIEW")).isEqualTo(1);
        s.assertThat(jdbc.queryForObject("SELECT progress FROM user_daily_quests WHERE id = ?", Integer.class, oldFlash))
                .as("old day keeps its progress").isEqualTo(1);
        s.assertAll();
    }

    // ------------------------------------------------------------ 3.3 phrase of the day

    @Test
    void thePhraseOfTheDayStaysTheSameWhenTheLibraryChangesAndStillCounts() throws Exception {
        freshDay();
        long m = member();
        payLibrary(6, m);
        JsonNode morning = today(m);
        long p = homePhraseId();

        SoftAssertions s = new SoftAssertions();
        s.assertThat(requireQuest(morning, "PHRASE_OF_DAY").path("phrase").path("id").asLong()).isEqualTo(p);
        s.assertThat(ok(getAs(m, "/api/me/home")).path("phrase").path("id").asLong()).isEqualTo(p);
        for (int i = 0; i < 3; i++) {
            phrase("konbini", "PHRASE");
        }
        s.assertThat(homePhraseId()).as("after adding phrases").isEqualTo(p);
        jdbc.update("UPDATE phrases SET deleted_at = ? WHERE id = ?", Timestamp.from(Instant.now()), p);
        s.assertThat(homePhraseId()).as("after deleting the phrase of the day").isEqualTo(p);
        s.assertThat(ok(getAs(m, "/api/me/home")).path("phrase").path("id").asLong()).isEqualTo(p);
        s.assertThat(requireQuest(today(m), "PHRASE_OF_DAY").path("phrase").path("id").asLong()).isEqualTo(p);
        clock.advance(GAP);
        JsonNode qc = listen(m, p);
        s.assertThat(changedCodes(qc)).as("LISTEN of the (deleted) phrase of the day").containsExactly("PHRASE_OF_DAY");
        s.assertAll();
    }
}
