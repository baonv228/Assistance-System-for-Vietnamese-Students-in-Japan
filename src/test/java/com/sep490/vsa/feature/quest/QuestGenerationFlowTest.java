package com.sep490.vsa.feature.quest;

import com.fasterxml.jackson.databind.JsonNode;
import com.sep490.vsa.feature.phrase.PhraseOfDayService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.sql.Date;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Making the day's quests (contract 3.2, 3.3): once per Member and Japan day, the same however often it is asked,
 * the fixed quest first, only quests the Member can do, targets lowered for a tiny library, a choice seeded by
 * (Member, day) that prefers codes not given yesterday, TOPIC never given any more (23/11/2026), and the topic of a
 * TOPIC the test-only API still makes.
 */
class QuestGenerationFlowTest extends QuestFlowSupport {

    @Autowired
    private QuestSettings settings;

    /** A library where every rotating quest is possible: 12 entries in one topic, 5 of them "Chưa thuộc". */
    private List<Long> everyQuestPossible(long user) {
        String topic = topic("Chủ đề thử");
        List<Long> ids = phrases(12, topic);
        for (int i = 0; i < 5; i++) {
            mark(user, ids.get(i), false);
        }
        return ids;
    }

    @Test
    void theSameDayGivesTheSameQuestsAndTheNextDayNewOnes() throws Exception {
        LocalDate day = freshDay();
        isolateLibrary();
        long user = member();
        everyQuestPossible(user);

        JsonNode first = today(user);
        JsonNode again = today(user);

        assertThat(first.path("date").asText()).isEqualTo(day.toString());
        assertThat(codes(first)).hasSize(3).first().isEqualTo("FLASH_REVIEW");
        assertThat(ids(again)).isEqualTo(ids(first));
        assertThat(codes(again)).isEqualTo(codes(first));
        assertThat(questRows(user)).isEqualTo(3);

        clock.advance(Duration.ofDays(1));
        JsonNode tomorrow = today(user);
        assertThat(tomorrow.path("date").asText()).isEqualTo(day.plusDays(1).toString());
        assertThat(codes(tomorrow)).hasSize(3).first().isEqualTo("FLASH_REVIEW");
        assertThat(ids(tomorrow)).doesNotContainAnyElementsOf(ids(first));
        assertThat(questRows(user)).isEqualTo(6);
        // Yesterday's quests were not touched
        assertThat(rows("SELECT COUNT(*) FROM user_daily_quests WHERE user_id = ? AND quest_date = ?", user,
                Date.valueOf(day))).isEqualTo(3);
    }

    @Test
    void reviewLearningIsNeverGivenWithoutChuaThuocEntriesAndFlashReviewAlwaysComesFirst() throws Exception {
        LocalDate day = freshDay();
        isolateLibrary();
        long user = member();
        phrases(12, topic("Chủ đề thử"));             // no "Chưa thuộc" entry at all

        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 8; i++) {
            setJapanTime(day.plusDays(i), LocalTime.NOON);
            JsonNode today = today(user);
            List<String> codes = codes(today);
            assertThat(codes).hasSize(3).first().isEqualTo("FLASH_REVIEW");
            assertThat(codes).doesNotContain("REVIEW_LEARNING");
            assertThat(today.path("quests").get(0).path("title").asText()).isEqualTo("Ôn 10 thẻ ghi nhớ");
            seen.addAll(codes);
        }
        assertThat(seen).containsExactlyInAnyOrder("FLASH_REVIEW", "LEARN_NEW", "PHRASE_OF_DAY");
    }

    @Test
    void aTinyLibraryLowersTheTargets() throws Exception {
        LocalDate day = freshDay();
        isolateLibrary();
        clearPhraseOfDay(day);
        long user = member();
        phrases(2);                                  // two entries, no topic, no answer yet

        JsonNode today = today(user);

        assertThat(codes(today)).containsExactlyInAnyOrder("FLASH_REVIEW", "LEARN_NEW", "PHRASE_OF_DAY");
        assertThat(codes(today).get(0)).isEqualTo("FLASH_REVIEW");
        JsonNode flash = quest(today, "FLASH_REVIEW");
        assertThat(flash.path("target").asInt()).isEqualTo(2);
        assertThat(flash.path("title").asText()).isEqualTo("Ôn 2 thẻ ghi nhớ");
        assertThat(flash.path("description").asText())
                .isEqualTo("Trả lời 2 thẻ khác nhau, Chưa thuộc hay Đã thuộc đều được.");
        assertThat(flash.path("action").path("type").asText()).isEqualTo("PRACTICE");
        JsonNode learnNew = quest(today, "LEARN_NEW");
        assertThat(learnNew.path("target").asInt()).isEqualTo(2);
        assertThat(learnNew.path("title").asText()).isEqualTo("Thuộc 2 mục mới");
        assertThat(learnNew.path("description").asText()).isEqualTo("Chọn “Đã thuộc” cho 2 mục bạn chưa thuộc.");
        assertThat(learnNew.path("action").path("status").asText()).isEqualTo("new");
        assertThat(quest(today, "PHRASE_OF_DAY").path("target").asInt()).isEqualTo(1);
    }

    @Test
    void deletedEntriesDoNotMakeAQuestPossible() throws Exception {
        freshDay();
        isolateLibrary();
        long user = member();
        List<Long> ids = phrases(3);
        for (long id : ids) {
            mark(user, id, false);
        }
        // Two of the three "Chưa thuộc" entries are deleted: REVIEW_LEARNING may only count the one left
        jdbc.update("UPDATE phrases SET deleted_at = NOW() WHERE id IN (?, ?)", ids.get(0), ids.get(1));

        JsonNode today = force(user, "FLASH_REVIEW", "REVIEW_LEARNING");

        assertThat(quest(today, "FLASH_REVIEW").path("target").asInt()).isEqualTo(1);
        assertThat(quest(today, "REVIEW_LEARNING").path("target").asInt()).isEqualTo(1);
        assertThat(quest(today, "REVIEW_LEARNING").path("title").asText()).isEqualTo("Luyện lại 1 thẻ “Chưa thuộc”");
        assertThat(quest(today, "REVIEW_LEARNING").path("description").asText())
                .isEqualTo("Luyện lại 1 thẻ đang ở trạng thái Chưa thuộc.");
        assertThat(quest(today, "REVIEW_LEARNING").path("action").path("status").asText()).isEqualTo("learning");
    }

    @Test
    void answeringBeforeOpeningTheQuestsMakesTheDayAndCounts() throws Exception {
        LocalDate day = freshDay();
        isolateLibrary();
        long user = member();
        List<Long> ids = phrases(3);

        JsonNode answer = answer(user, ids.get(0), true);

        JsonNode change = answer.path("quests");
        assertThat(changedCodes(change)).contains("FLASH_REVIEW");
        JsonNode today = today(user);
        assertThat(today.path("date").asText()).isEqualTo(day.toString());
        JsonNode flash = quest(today, "FLASH_REVIEW");
        assertThat(flash.path("progress").asInt()).isEqualTo(1);
        long changedId = -1;
        for (JsonNode c : change.path("changed")) {
            if ("FLASH_REVIEW".equals(c.path("code").asText())) {
                changedId = c.path("id").asLong();
            }
        }
        assertThat(changedId).isEqualTo(flash.path("id").asLong());
    }

    @Test
    void theChoiceIsTheSameForTheSameMemberAndDay() throws Exception {
        LocalDate day = freshDay();
        isolateLibrary();
        long user = member();
        everyQuestPossible(user);

        List<String> first = codes(today(user));
        // Made again from scratch (no quests yesterday either time): the same codes in the same order
        jdbc.update("DELETE FROM user_daily_quests WHERE user_id = ? AND quest_date = ?", user, Date.valueOf(day));
        List<String> second = codes(today(user));

        assertThat(second).isEqualTo(first);
        List<String> expected = QuestGenerator.shuffled(user, day, settings.getRotating()).subList(0, 2);
        assertThat(first.subList(1, 3)).isEqualTo(expected);
        assertThat(QuestGenerator.shuffled(user, day, List.of("A", "B", "C", "D")))
                .isEqualTo(QuestGenerator.shuffled(user, day, List.of("A", "B", "C", "D")));
    }

    @Test
    void theDayNeverRepeatsYesterdaysSetWhenAnotherIsPossible() throws Exception {
        LocalDate day = freshDay();
        isolateLibrary();
        for (int n = 0; n < 3; n++) {
            long user = member();
            everyQuestPossible(user);
            // Yesterday's choice is today's natural one: today must still differ
            setJapanTime(day.minusDays(1), LocalTime.NOON);
            jdbc.update("DELETE FROM user_daily_quests WHERE user_id = ?", user);
            List<String> natural = QuestGenerator.shuffled(user, day, settings.getRotating()).subList(0, 2);
            jdbc.update("""
                    INSERT INTO user_daily_quests (user_id, quest_date, code, sort_order, title, target)
                    VALUES (?, ?, 'FLASH_REVIEW', 0, 'x', 1), (?, ?, ?, 1, 'x', 1), (?, ?, ?, 2, 'x', 1)""",
                    user, Date.valueOf(day.minusDays(1)), user, Date.valueOf(day.minusDays(1)), natural.get(0),
                    user, Date.valueOf(day.minusDays(1)), natural.get(1));
            setJapanTime(day, LocalTime.NOON);
            List<String> today = codes(today(user)).subList(1, 3);
            assertThat(Set.copyOf(today)).isNotEqualTo(Set.copyOf(natural));
            // Only one quest gives way: one of yesterday's may come back next to a new one
            assertThat(today).containsAnyElementsOf(natural);
        }
    }

    @Test
    void aMemberWhoPlaysEveryDayMeetsMoreThanTwoPairsAndNeverTheSameTwiceInARow() throws Exception {
        LocalDate day = freshDay();
        isolateLibrary();
        long user = member();
        everyQuestPossible(user);
        List<Set<String>> sets = new ArrayList<>();
        for (int d = 0; d < 12; d++) {
            setJapanTime(day.plusDays(d), LocalTime.NOON);
            sets.add(Set.copyOf(codes(today(user)).subList(1, 3)));
        }
        for (int d = 1; d < sets.size(); d++) {
            assertThat(sets.get(d)).as("day %d", d).isNotEqualTo(sets.get(d - 1));
        }
        assertThat(Set.copyOf(sets)).hasSizeGreaterThan(2);
    }

    @Test
    void topicFollowsTheStageThenTheTopicWithMostEntriesNotLearned() throws Exception {
        freshDay();
        isolateLibrary();
        assertThat(rows("SELECT COUNT(*) FROM learning_topics WHERE id IN ('pay', 'train')")).isEqualTo(2);
        String own = topic("Chủ đề lớn");
        List<Long> train = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            train.add(phrase("konbini", "PHRASE", "train"));
        }
        List<Long> big = phrases(8, own);

        // Stage ARR = pay, train, paper, health: "pay" has no entry left in this library, "train" has 5
        long arrived = member();
        setStage(arrived, "ARR");
        JsonNode stage = quest(force(arrived, "TOPIC"), "TOPIC");
        assertThat(stage.path("target").asInt()).isEqualTo(5);
        assertThat(stage.path("title").asText()).isEqualTo("Ôn 5 thẻ chủ đề “Đi tàu”");
        assertThat(stage.path("description").asText()).isEqualTo("Trả lời 5 thẻ khác nhau của chủ đề Đi tàu.");
        JsonNode action = stage.path("action");
        assertThat(action.path("type").asText()).isEqualTo("PRACTICE");
        assertThat(action.path("by").asText()).isEqualTo("topic");
        assertThat(action.path("group").asText()).isEqualTo("train");
        assertThat(action.path("groupName").asText()).isEqualTo("Đi tàu");
        assertThat(action.path("kind").asText()).isEmpty();
        assertThat(action.path("status").asText()).isEmpty();

        // No stage: the topic with the most entries the Member has not learned
        long noStage = member();
        JsonNode fallback = quest(force(noStage, "TOPIC"), "TOPIC");
        assertThat(fallback.path("action").path("group").asText()).isEqualTo(own);
        assertThat(fallback.path("title").asText()).isEqualTo("Ôn 5 thẻ chủ đề “Chủ đề lớn”");

        // Same library, but this Member learned every entry of the big topic: "Đi tàu" has more left to learn
        long learnt = member();
        for (long id : big) {
            mark(learnt, id, true);
        }
        assertThat(quest(force(learnt, "TOPIC"), "TOPIC").path("action").path("group").asText()).isEqualTo("train");
        assertThat(train).hasSize(5);
    }

    @Test
    void topicIsNeverGivenEvenWhenItIsTheOnlyRotatingQuestPossible() throws Exception {
        LocalDate day = freshDay();
        isolateLibrary();
        long user = member();
        String topic = topic("Chủ đề từ vựng");
        // Words only (never the phrase of the day), all "Đã thuộc": no LEARN_NEW, REVIEW_LEARNING or PHRASE_OF_DAY.
        // Only TOPIC was possible (the day was FLASH_REVIEW + TOPIC until 23/11/2026): now the fixed quest alone.
        for (int i = 0; i < 6; i++) {
            mark(user, phrase("konbini", "VOCAB", topic), true);
        }

        for (int d = 0; d < 5; d++) {
            clearPhraseOfDay(day.plusDays(d));
            setJapanTime(day.plusDays(d), LocalTime.NOON);
            JsonNode today = today(user);
            assertThat(codes(today)).as("day %d", d).containsExactly("FLASH_REVIEW");
            assertThat(quest(today, "FLASH_REVIEW").path("target").asInt()).isEqualTo(6);
        }
    }

    /**
     * TOPIC is not offered any more (user decision 23/11/2026): over many Members and days where every quest is
     * possible, the day is always the fixed quest + 2 of LEARN_NEW, REVIEW_LEARNING and PHRASE_OF_DAY, and each of
     * these still turns up.
     */
    @Test
    void topicIsNeverGivenWhenEveryQuestIsPossible() throws Exception {
        LocalDate day = freshDay();
        isolateLibrary();
        Set<String> seen = new HashSet<>();
        for (int n = 0; n < 6; n++) {
            long user = member();
            everyQuestPossible(user);
            for (int d = 0; d < 15; d++) {
                setJapanTime(day.plusDays(d), LocalTime.NOON);
                List<String> codes = codes(today(user));
                assertThat(codes).as("member %d, day %d", n, d).hasSize(3).doesNotContain("TOPIC")
                        .first().isEqualTo("FLASH_REVIEW");
                seen.addAll(codes);
            }
            assertThat(rows("SELECT COUNT(*) FROM user_daily_quests WHERE user_id = ? AND code = 'TOPIC'", user))
                    .isZero();
            assertThat(questRows(user)).isEqualTo(45);
        }
        assertThat(seen).containsExactlyInAnyOrder("FLASH_REVIEW", "LEARN_NEW", "REVIEW_LEARNING", "PHRASE_OF_DAY");
    }

    @Test
    void phraseOfTheDayCarriesThePhraseOfTheHome() throws Exception {
        LocalDate day = freshDay();
        isolateLibrary();
        clearPhraseOfDay(day);
        long user = member();
        for (long id : phrases(2)) {
            mark(user, id, true);                   // no LEARN_NEW, no topic: only PHRASE_OF_DAY can rotate in
        }

        JsonNode today = today(user);

        assertThat(codes(today)).containsExactly("FLASH_REVIEW", "PHRASE_OF_DAY");
        PhraseOfDayService.PhraseOfDay expected = phraseOfDay.phraseOf(day).orElseThrow();
        JsonNode listen = quest(today, "PHRASE_OF_DAY");
        assertThat(listen.path("title").asText()).isEqualTo("Nghe câu tiếng Nhật hôm nay");
        assertThat(listen.path("description").asText()).isEqualTo("Bấm Nghe để nghe câu hôm nay một lần.");
        assertThat(listen.path("target").asInt()).isEqualTo(1);
        assertThat(listen.path("action").path("type").asText()).isEqualTo("LISTEN");
        assertThat(listen.path("action").path("by").asText()).isEmpty();
        assertThat(listen.path("phrase").path("id").asLong()).isEqualTo(expected.id());
        assertThat(listen.path("phrase").path("japanese").asText()).isEqualTo(expected.japanese());
        assertThat(listen.path("phrase").path("romaji").asText()).isEqualTo(expected.romaji());
        assertThat(listen.path("phrase").path("meaningVi").asText()).isEqualTo(expected.meaningVi());
        assertThat(quest(today, "FLASH_REVIEW").path("phrase").isNull()).isTrue();

        // Deleted later that day: the quest still shows it
        jdbc.update("UPDATE phrases SET deleted_at = NOW() WHERE id = ?", expected.id());
        try {
            assertThat(quest(today(user), "PHRASE_OF_DAY").path("phrase").path("id").asLong()).isEqualTo(expected.id());
        } finally {
            jdbc.update("UPDATE phrases SET deleted_at = NULL WHERE id = ?", expected.id());
        }
    }

    @Test
    void firstRequestsAtTheSameMomentMakeTheDayOnce() throws Exception {
        freshDay();
        isolateLibrary();
        long user = member();
        everyQuestPossible(user);

        int threads = 4;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<List<Long>>> results = new ArrayList<>();
        try {
            for (int i = 0; i < threads; i++) {
                Callable<List<Long>> call = () -> {
                    start.await();
                    return ids(today(user));
                };
                results.add(pool.submit(call));
            }
            start.countDown();
            List<Long> first = results.get(0).get(30, TimeUnit.SECONDS);
            for (Future<List<Long>> result : results) {
                assertThat(result.get(30, TimeUnit.SECONDS)).isEqualTo(first);
            }
        } finally {
            pool.shutdownNow();
        }
        assertThat(questRows(user)).isEqualTo(3);
    }
}
