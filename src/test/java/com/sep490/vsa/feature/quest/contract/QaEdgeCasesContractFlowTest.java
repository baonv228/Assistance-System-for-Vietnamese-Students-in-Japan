package com.sep490.vsa.feature.quest.contract;

import com.fasterxml.jackson.databind.JsonNode;
import org.assertj.core.api.SoftAssertions;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ThreadLocalRandom;

import static org.assertj.core.api.Assertions.assertThat;

/** Second round: edges of section 3 the first round did not reach. */
@TestPropertySource(properties = QaContractSupport.QA_DB)
class QaEdgeCasesContractFlowTest extends QaContractSupport {

    // ------------------------------------------------------------ 3.3 TOPIC choice

    /** "Lấy chủ đề đầu tiên khả thi trong danh sách": ARR = pay, train, …; pay has 3 entries (< 5), train 5. */
    @Test
    void topicIsTheFirstFeasibleTopicOfTheStage() throws Exception {
        freshDay();
        long m = member();
        List<Long> all = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            all.add(phrase("konbini", "PHRASE", "pay"));
        }
        for (int i = 0; i < 3; i++) {
            all.add(phrase("station", "PHRASE", "train"));
        }
        for (int i = 0; i < 2; i++) {
            all.add(phrase("station", "VOCAB", "train"));
        }
        isolateLibrary();
        setStage(m, "ARR");
        for (long id : all) {
            mark(m, id, true);
        }

        JsonNode topic = requireQuest(today(m), "TOPIC");
        SoftAssertions s = new SoftAssertions();
        s.assertThat(topic.path("action").path("group").asText()).as("pay is not feasible (3 < 5): train")
                .isEqualTo("train");
        s.assertThat(topic.path("action").path("groupName").asText()).isEqualTo("Đi tàu");
        s.assertThat(topic.path("target").asInt()).isEqualTo(5);
        s.assertThat(topic.path("title").asText()).isEqualTo("Ôn 5 thẻ chủ đề “Đi tàu”");
        s.assertAll();
    }

    /**
     * Without a stage: "lấy chủ đề còn nhiều mục người dùng chưa thuộc nhất". T1: 6 entries all "Đã thuộc"; T2: 6
     * entries, 4 "Chưa thuộc". LEARN_NEW is made impossible (the 4 were counted for it on an earlier day), there is no
     * phrase of the day (vocabulary only), so TOPIC is surely given.
     */
    @Test
    void withoutAStageTopicIsTheOneWithMostEntriesNotLearned() throws Exception {
        LocalDate day = freshDay();
        long m = member();
        String t1 = topic("QA chủ đề một");
        String t2 = topic("QA chủ đề hai");
        List<Long> one = new ArrayList<>();
        List<Long> two = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            one.add(phrase("konbini", "VOCAB", t1));
            two.add(phrase("konbini", "VOCAB", t2));
        }
        isolateLibrary();
        one.forEach(id -> mark(m, id, true));
        for (int i = 0; i < 6; i++) {
            mark(m, two.get(i), i >= 4);
        }
        seedLearnNewHistory(m, day.minusDays(10), two.subList(0, 4));

        JsonNode t = today(m);
        JsonNode topic = requireQuest(t, "TOPIC");
        SoftAssertions s = new SoftAssertions();
        s.assertThat(topic.path("action").path("group").asText()).isEqualTo(t2);
        s.assertThat(topic.path("action").path("groupName").asText()).isEqualTo("QA chủ đề hai");
        s.assertThat(quest(t, "LEARN_NEW")).as("no entry left for LEARN_NEW").isNull();
        s.assertAll();
    }

    /** LEARN_NEW's feasibility counts only entries never counted for it: 2 left, so never a target of 3. */
    @Test
    void learnNewIsNeverGivenMoreThanTheEntriesNeverCountedForIt() throws Exception {
        LocalDate day = freshDay();
        long m = member();
        Vocab v = vocabLibrary(5, 0, 0, m);
        seedLearnNewHistory(m, day.minusDays(5), v.learning().subList(0, 3));

        JsonNode t = today(m);
        JsonNode learn = quest(t, "LEARN_NEW");
        SoftAssertions s = new SoftAssertions();
        if (learn != null) {
            s.assertThat(learn.path("target").asInt()).as("only 2 entries never counted for LEARN_NEW")
                    .isLessThanOrEqualTo(2);
            s.assertThat(learn.path("title").asText()).isEqualTo("Thuộc " + learn.path("target").asInt() + " mục mới");
            s.assertThat(learn.path("action").path("status").asText()).as("no entry never practised")
                    .isEqualTo("learning");
        }
        s.assertThat(quest(t, "REVIEW_LEARNING")).as("5 Chưa thuộc: REVIEW_LEARNING feasible").isNotNull();
        s.assertAll();
    }

    // ------------------------------------------------------------ 3.2 rotation

    /** "ưu tiên khác bộ của hôm qua": with all 4 rotating quests feasible, today's pair differs from yesterday's. */
    @Test
    void theRotatingPairDiffersFromYesterdaysWhenPossible() throws Exception {
        LocalDate day = freshDay();
        long[] members = new long[8];
        for (int i = 0; i < members.length; i++) {
            members[i] = member();
        }
        List<Long> lib = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            lib.add(phrase("konbini", "PHRASE", "pay"));
        }
        isolateLibrary();
        for (long m : members) {
            setStage(m, "ARR");
            for (long id : lib) {
                mark(m, id, false);
            }
        }
        SoftAssertions s = new SoftAssertions();
        List<Set<String>> first = new ArrayList<>();
        for (long m : members) {
            Set<String> pair = new TreeSet<>(codes(today(m).path("quests")));
            pair.remove("FLASH_REVIEW");
            s.assertThat(pair).as("2 rotating quests").hasSize(2);
            first.add(pair);
        }
        setJapanTime(day.plusDays(1), LocalTime.NOON);
        int disjoint = 0;
        for (int i = 0; i < members.length; i++) {
            Set<String> pair = new TreeSet<>(codes(today(members[i]).path("quests")));
            pair.remove("FLASH_REVIEW");
            s.assertThat(pair).as("member " + i + ": yesterday " + first.get(i)).isNotEqualTo(first.get(i));
            Set<String> both = new TreeSet<>(pair);
            both.retainAll(first.get(i));
            disjoint += both.isEmpty() ? 1 : 0;
        }
        System.out.println("QA: rotating pairs disjoint from yesterday's for " + disjoint + " of " + members.length);
        s.assertAll();
    }

    /** "ổn định theo (người dùng, ngày) (ví dụ random có seed)": made again from the same state, the same pair. */
    @Test
    void theRotatingChoiceIsStableForAMemberAndADay() throws Exception {
        LocalDate day = freshDay();
        long[] members = {member(), member(), member()};
        List<Long> lib = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            lib.add(phrase("konbini", "PHRASE", "pay"));
        }
        isolateLibrary();
        for (long m : members) {
            setStage(m, "ARR");
            lib.forEach(id -> mark(m, id, false));
        }
        SoftAssertions s = new SoftAssertions();
        for (long m : members) {
            List<String> first = codes(today(m).path("quests"));
            for (int i = 0; i < 5; i++) {
                jdbc.update("DELETE FROM user_daily_quests WHERE user_id = ? AND quest_date = ?", m, day.toString());
                s.assertThat(codes(today(m).path("quests"))).as("member " + m + " made again " + i).isEqualTo(first);
            }
        }
        s.assertAll();
    }

    // ------------------------------------------------------------ 3.3 action filters the app follows

    /** One entry only (a phrase, never practised): whatever is given can be finished through its own action. */
    @Test
    void aOneEntryLibraryGivesOnlyQuestsItsActionsCanFinish() throws Exception {
        freshDay();
        long m = member();
        phrase("konbini", "PHRASE");
        isolateLibrary();
        playThroughActions(m, true);
    }

    /** Two words, one "Chưa thuộc", one never practised. */
    @Test
    void aTwoWordLibraryGivesOnlyQuestsItsActionsCanFinish() throws Exception {
        freshDay();
        long m = member();
        Vocab v = vocabLibrary(1, 1, 0, m);
        assertThat(v.all()).hasSize(2);
        playThroughActions(m, true);
    }

    /**
     * Two never practised + two "Chưa thuộc": LEARN_NEW (target 3, 4 entries eligible) gets status "learning"
     * (only 2 never practised < 3), whose filter shows the 2 "Chưa thuộc" entries only.
     */
    @Test
    void learnNewCanBeFinishedThroughItsActionWhenFewEntriesAreNew() throws Exception {
        freshDay();
        long m = member();
        vocabLibrary(2, 2, 0, m);
        JsonNode t = today(m);
        JsonNode learn = quest(t, "LEARN_NEW");
        assertThat(learn).as("LEARN_NEW given: 4 entries not learned ≥ 3").isNotNull();
        // Rule of 3.3 as amended after this test found the flaw: neither the 2 never practised ("new") nor the 2
        // "Chưa thuộc" ("learning") reach the 3 left, so no filter (the old rule gave "learning": only 2 cards)
        assertThat(learn.path("action").path("status").asText()).as("2 new < 3 and 2 Chưa thuộc < 3")
                .isEqualTo("");
        List<Long> cards = cardsFor(m, learn.path("action"));
        assertThat(cards.size()).as("cards the app shows for LEARN_NEW (" + learn.path("action")
                + ") must be enough for its target " + learn.path("target")).isGreaterThanOrEqualTo(
                learn.path("target").asInt());
    }

    /**
     * The Member practises FLASH_REVIEW first and answers "Chưa thuộc" everywhere; LEARN_NEW's action (read again
     * from GET today, as the app does when the screen opens) must still lead to entries that finish it.
     */
    @Test
    void learnNewStaysReachableAfterTheMemberAnsweredChuaThuocInFlashReview() throws Exception {
        freshDay();
        long m = member();
        List<Long> lib = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            lib.add(phrase("konbini", "PHRASE"));
        }
        isolateLibrary();
        JsonNode t = today(m);
        assertThat(codes(t.path("quests"))).containsExactlyInAnyOrder("FLASH_REVIEW", "LEARN_NEW", "PHRASE_OF_DAY");
        assertThat(requireQuest(t, "LEARN_NEW").path("action").path("status").asText()).isEqualTo("new");
        for (long id : lib) {
            answer(m, id, false);
        }
        JsonNode again = today(m);
        JsonNode learn = requireQuest(again, "LEARN_NEW");
        assertThat(requireQuest(again, "FLASH_REVIEW").path("completed").asBoolean()).isTrue();
        List<Long> cards = cardsFor(m, learn.path("action"));
        assertThat(cards.size()).as("cards the app shows now for LEARN_NEW (" + learn.path("action")
                + "); every entry is Chưa thuộc and none was counted for LEARN_NEW").isGreaterThanOrEqualTo(3);
    }

    /** Plays the day as the app: each quest in turn, re-reading GET today, answering the cards its action selects. */
    private void playThroughActions(long m, boolean learned) throws Exception {
        JsonNode t = today(m);
        SoftAssertions s = new SoftAssertions();
        for (JsonNode given : t.path("quests")) {
            String code = given.path("code").asText();
            JsonNode q = requireQuest(today(m), code);
            s.assertThat(q.path("target").asInt()).as(code + " target ≥ 1").isGreaterThanOrEqualTo(1);
            if (q.path("completed").asBoolean()) {
                continue;
            }
            if ("LISTEN".equals(q.path("action").path("type").asText())) {
                clock.advance(GAP);
                listen(m, q.path("phrase").path("id").asLong());
            } else {
                for (long card : cardsFor(m, q.path("action"))) {
                    answer(m, card, learned);
                    if (requireQuest(today(m), code).path("completed").asBoolean()) {
                        break;
                    }
                }
            }
            JsonNode after = requireQuest(today(m), code);
            s.assertThat(after.path("completed").asBoolean())
                    .as(code + " (target " + after.path("target") + ", action " + after.path("action")
                            + ") finished through its own action; progress " + after.path("progress")).isTrue();
        }
        JsonNode end = today(m);
        s.assertThat(end.path("bonus").path("completed").asBoolean()).as("day done; quests " + codes(end.path("quests")))
                .isTrue();
        s.assertAll();
    }

    // ------------------------------------------------------------ 3.3 entries

    @Test
    void anEntryDeletedDuringTheDayNoLongerCounts() throws Exception {
        freshDay();
        long m = member();
        List<Long> lib = payLibrary(6, m);
        today(m);
        jdbc.update("UPDATE phrases SET deleted_at = ? WHERE id = ?", Timestamp.from(Instant.now()), lib.get(0));
        clock.advance(GAP);
        var r = flash(m, lib.get(0), true).andReturn().getResponse();
        SoftAssertions s = new SoftAssertions();
        s.assertThat(r.getStatus()).as("flash of a deleted entry").isIn(200, 404);
        if (r.getStatus() == 200) {
            JsonNode qc = JSON.readTree(r.getContentAsString(java.nio.charset.StandardCharsets.UTF_8))
                    .path("data").path("quests");
            s.assertThat(changedCodes(qc)).isEmpty();
        }
        JsonNode t = today(m);
        s.assertThat(progress(t, "FLASH_REVIEW")).isZero();
        s.assertThat(progress(t, "TOPIC")).isZero();
        s.assertAll();
    }

    @Test
    void aFlashAnswerOnThePhraseOfTheDayDoesNotCompletePhraseOfDay() throws Exception {
        freshDay();
        long m = member();
        payLibrary(6, m);
        long p = requireQuest(today(m), "PHRASE_OF_DAY").path("phrase").path("id").asLong();
        JsonNode qc = answer(m, p, true).path("quests");
        assertThat(changedCodes(qc)).containsExactlyInAnyOrder("FLASH_REVIEW", "TOPIC");
        assertThat(progress(today(m), "PHRASE_OF_DAY")).isZero();
    }

    @Test
    void thePhraseOfTheDayIsAPhraseAndTheSameForEveryone() throws Exception {
        freshDay();
        long a = member();
        long b = member();
        long only = phrase("konbini", "PHRASE", "pay");
        List<Long> words = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            words.add(phrase("konbini", "VOCAB", "pay"));
        }
        isolateLibrary();
        for (long m : new long[]{a, b}) {
            setStage(m, "ARR");
            mark(m, only, true);
            words.forEach(id -> mark(m, id, true));
        }
        SoftAssertions s = new SoftAssertions();
        s.assertThat(homePhraseId()).as("only kind PHRASE").isEqualTo(only);
        s.assertThat(requireQuest(today(a), "PHRASE_OF_DAY").path("phrase").path("id").asLong()).isEqualTo(only);
        s.assertThat(requireQuest(today(b), "PHRASE_OF_DAY").path("phrase").path("id").asLong()).isEqualTo(only);
        s.assertAll();
    }

    // ------------------------------------------------------------ 3.1 / 5.3 day boundary

    /** 5.3: "phraseId khác câu hôm nay (ví dụ bấm nghe lúc vừa qua 0:00 JST): 200, không đổi gì". */
    @Test
    void listeningToYesterdaysPhraseJustAfterMidnightChangesNothing() throws Exception {
        LocalDate day = freshDay();
        long m = member();
        payLibrary(6, m);
        JsonNode yesterdayPod = requireQuest(today(m), "PHRASE_OF_DAY");
        long p = yesterdayPod.path("phrase").path("id").asLong();

        clock.set(japanMidnight(day.plusDays(1)).plusSeconds(1));
        long q = homePhraseId();
        assertThat(q).as("consecutive days: other phrase (day number % count)").isNotEqualTo(p);
        JsonNode late = listen(m, p);
        SoftAssertions s = new SoftAssertions();
        s.assertThat(late.path("changed").size()).isZero();
        s.assertThat(late.path("coinsAwarded").asInt()).isZero();
        s.assertThat(jdbc.queryForObject("SELECT progress FROM user_daily_quests WHERE id = ?", Integer.class,
                yesterdayPod.path("id").asLong())).as("yesterday's quest untouched").isZero();
        JsonNode t = today(m);
        JsonNode pod = requireQuest(t, "PHRASE_OF_DAY");
        s.assertThat(pod.path("phrase").path("id").asLong()).isEqualTo(q);
        s.assertThat(pod.path("progress").asInt()).isZero();
        clock.advance(GAP);
        s.assertThat(changedCodes(listen(m, q))).containsExactly("PHRASE_OF_DAY");
        s.assertAll();
    }

    /** A day finished at 23:59:59 JST is yesterday's done day at 00:00:00; coinsToday starts again from 0. */
    @Test
    void aDayFinishedAt235959IsDoneAndTheNextDayStartsFresh() throws Exception {
        LocalDate day = freshDay();
        long m = member();
        List<Long> lib = payLibrary(5, m);
        today(m);
        setJapanTime(day, LocalTime.of(23, 59, 47));
        List<JsonNode> changes = finishPayDay(m, lib);
        SoftAssertions s = new SoftAssertions();
        s.assertThat(japanToday()).isEqualTo(day);
        s.assertThat(changes.get(changes.size() - 1).path("bonusJustCompleted").asBoolean()).isTrue();
        s.assertThat(count("SELECT COUNT(*) FROM coin_transactions WHERE user_id = ? AND quest_date = ?", m,
                day.toString())).as("xu lines of that day carry its quest_date").isEqualTo(4);

        clock.set(japanMidnight(day.plusDays(1)));
        JsonNode t = today(m);
        s.assertThat(t.path("date").asText()).isEqualTo(day.plusDays(1).toString());
        s.assertThat(t.path("streak").path("current").asInt()).as("yesterday done, today not yet").isEqualTo(1);
        s.assertThat(t.path("streak").path("best").asInt()).isEqualTo(1);
        s.assertThat(t.path("streak").path("todayDone").asBoolean()).isFalse();
        s.assertThat(t.path("recentDays").path(5).path("date").asText()).isEqualTo(day.toString());
        s.assertThat(t.path("recentDays").path(5).path("done").asBoolean()).isTrue();
        s.assertThat(t.path("recentDays").path(6).path("done").asBoolean()).isFalse();
        s.assertThat(t.path("coinsToday").asInt()).isZero();
        s.assertThat(t.path("bonus").path("completed").asBoolean()).isFalse();
        s.assertThat(t.path("wallet").path("coins").asInt()).isEqualTo(50);
        for (JsonNode q : t.path("quests")) {
            s.assertThat(q.path("progress").asInt()).isZero();
            s.assertThat(q.path("completed").asBoolean()).isFalse();
        }
        JsonNode hq = ok(getAs(m, "/api/me/home")).path("quests");
        s.assertThat(hq.path("coinsToday").asInt()).isZero();
        s.assertThat(hq.path("done").asInt()).isZero();
        s.assertThat(hq.path("streak").asInt()).isEqualTo(1);
        s.assertAll();
    }

    /** A day whose quests were made but not all finished is not a done day. */
    @Test
    void aStartedButUnfinishedDayIsNotDone() throws Exception {
        LocalDate day = freshDay();
        long m = member();
        payLibrary(5, m);
        listen(m, requireQuest(today(m), "PHRASE_OF_DAY").path("phrase").path("id").asLong());
        JsonNode hq = ok(getAs(m, "/api/me/home")).path("quests");
        assertThat(hq.path("done").asInt()).as("Home: 1 of 3 done").isEqualTo(1);
        assertThat(hq.path("generated").asBoolean()).isTrue();

        setJapanTime(day.plusDays(1), LocalTime.NOON);
        JsonNode t = today(m);
        SoftAssertions s = new SoftAssertions();
        s.assertThat(t.path("recentDays").path(5).path("done").asBoolean()).isFalse();
        s.assertThat(t.path("streak").path("current").asInt()).isZero();
        s.assertThat(t.path("streak").path("best").asInt()).isZero();
        s.assertAll();
    }

    // ------------------------------------------------------------ 3.4 cap by quest_date

    @Test
    void theDailyCapCountsByQuestDateNotByCreationTime() throws Exception {
        LocalDate day = freshDay();
        long yesterdaysXu = member();
        long todaysXu = member();
        payLibrary(5, yesterdaysXu, todaysXu);
        // 45 xu of yesterday's quests written just now, and 45 xu of today's quests written yesterday evening.
        insertQuestXu(yesterdaysXu, 45, day.minusDays(1), clock.instant());
        insertQuestXu(todaysXu, 45, day, japanMidnight(day).minusSeconds(3600));

        SoftAssertions s = new SoftAssertions();
        JsonNode a = today(yesterdaysXu);
        s.assertThat(a.path("coinsToday").asInt()).isZero();
        s.assertThat(listen(yesterdaysXu, requireQuest(a, "PHRASE_OF_DAY").path("phrase").path("id").asLong())
                .path("coinsAwarded").asInt()).as("yesterday's xu do not use today's cap").isEqualTo(10);
        JsonNode b = today(todaysXu);
        s.assertThat(b.path("coinsToday").asInt()).isEqualTo(45);
        s.assertThat(listen(todaysXu, requireQuest(b, "PHRASE_OF_DAY").path("phrase").path("id").asLong())
                .path("coinsAwarded").asInt()).as("today's quest_date counts whatever its created_at").isEqualTo(5);
        s.assertAll();
    }

    // ------------------------------------------------------------ 3.6 milestones of a later run

    /** "mỗi lần chuỗi chạm bội số của 7": a second run reaching 7 pays again (another day). */
    @Test
    void aLaterRunReachingSevenPaysTheMilestoneAgain() throws Exception {
        LocalDate day = freshDay();
        long m = member();
        List<Long> lib = payLibrary(5, m);
        for (int k = 1; k <= 7; k++) {
            seedDoneDay(m, day.minusDays(31 - k), k);
        }
        LocalDate oldSeventh = day.minusDays(24);
        Long oldDayId = jdbc.queryForObject("SELECT id FROM user_quest_days WHERE user_id = ? AND quest_date = ?",
                Long.class, m, oldSeventh.toString());
        jdbc.update("""
                INSERT INTO coin_transactions (user_id, delta, reason, title, ref_type, ref_id, quest_date, created_at)
                VALUES (?, 50, 'QUEST_STREAK', 'Học 7 ngày liên tiếp', 'QUEST_DAY', ?, ?, ?)""", m, oldDayId,
                oldSeventh.toString(), Timestamp.from(oldSeventh.atTime(21, 0).atZone(JAPAN).toInstant()));
        jdbc.update("UPDATE users SET coins = coins + 50 WHERE id = ?", m);
        for (int k = 1; k <= 6; k++) {
            seedDoneDay(m, day.minusDays(7 - k), k);
        }

        List<JsonNode> changes = finishPayDay(m, lib);
        JsonNode last = changes.get(changes.size() - 1);
        SoftAssertions s = new SoftAssertions();
        s.assertThat(last.path("streak").path("current").asInt()).isEqualTo(7);
        s.assertThat(last.path("streakMilestone").path("days").asInt()).as("milestone of the new run").isEqualTo(7);
        s.assertThat(count("SELECT COUNT(*) FROM coin_transactions WHERE user_id = ? AND reason = 'QUEST_STREAK'", m))
                .isEqualTo(2);
        s.assertThat(coinsOf(m)).isEqualTo(50 + 50 + 50);
        s.assertThat(today(m).path("streak").path("best").asInt()).isEqualTo(7);
        s.assertAll();
    }

    // ------------------------------------------------------------ 3.7 rate limit per Member

    @Test
    void theEventLimitIsPerMember() throws Exception {
        freshDay();
        long a = member();
        long b = member();
        payLibrary(5, a, b);
        for (int i = 0; i < 31; i++) {
            listenRaw(a, 999_999_999L);
        }
        assertThat(listenRaw(a, 999_999_999L).andReturn().getResponse().getStatus()).isEqualTo(429);
        assertThat(listenRaw(b, 999_999_999L).andReturn().getResponse().getStatus()).as("another Member").isEqualTo(200);
    }

    // ------------------------------------------------------------ 3.7 what the 1500 ms rule measures

    /**
     * Only successful POST …/flash are answers (4: "Mỗi lần POST …/flash thành công là một dòng"): a refused answer,
     * "Bỏ đánh dấu" and LISTEN never hide the next answer.
     */
    @Test
    void onlySuccessfulFlashAnswersStartThe1500MsWait() throws Exception {
        freshDay();
        long m = member();
        List<Long> lib = payLibrary(8, m);
        today(m);
        long phraseOfDay = requireQuest(today(m), "PHRASE_OF_DAY").path("phrase").path("id").asLong();
        SoftAssertions s = new SoftAssertions();

        answer(m, lib.get(0), true);
        clock.advance(GAP);
        int missing = postAs(m, "/api/phrases/999999999/flash", "{\"learned\":true}").andReturn().getResponse()
                .getStatus();
        s.assertThat(missing).as("unknown entry").isEqualTo(404);
        clock.advance(java.time.Duration.ofMillis(500));
        s.assertThat(changedCodes(ok(flash(m, lib.get(1), true)).path("quests")))
                .as("500 ms after a refused answer (unknown entry)").isNotEmpty();

        clock.advance(GAP);
        int invalid = postAs(m, "/api/phrases/" + lib.get(2) + "/flash", "{}").andReturn().getResponse().getStatus();
        s.assertThat(invalid).as("answer without learned").isEqualTo(400);
        clock.advance(java.time.Duration.ofMillis(500));
        s.assertThat(changedCodes(ok(flash(m, lib.get(3), true)).path("quests")))
                .as("500 ms after a refused answer (400)").isNotEmpty();

        clock.advance(GAP);
        ok(deleteAs(m, "/api/phrases/" + lib.get(0) + "/flash"));
        clock.advance(java.time.Duration.ofMillis(500));
        s.assertThat(changedCodes(ok(flash(m, lib.get(4), true)).path("quests")))
                .as("500 ms after Bỏ đánh dấu").isNotEmpty();

        clock.advance(GAP);
        listen(m, phraseOfDay);
        clock.advance(java.time.Duration.ofMillis(500));
        s.assertThat(changedCodes(ok(flash(m, lib.get(5), true)).path("quests")))
                .as("500 ms after LISTEN").isNotEmpty();

        s.assertThat(count("SELECT COUNT(*) FROM phrase_answer_log WHERE user_id = ?", m))
                .as("one log row per successful POST …/flash").isEqualTo(5);
        s.assertThat(progress(today(m), "FLASH_REVIEW")).isEqualTo(5);
        s.assertAll();
    }

    @Test
    void theWaitIsPerMember() throws Exception {
        freshDay();
        long a = member();
        long b = member();
        List<Long> lib = payLibrary(6, a, b);
        today(a);
        today(b);
        clock.advance(GAP);
        SoftAssertions s = new SoftAssertions();
        s.assertThat(changedCodes(ok(flash(a, lib.get(0), true)).path("quests"))).isNotEmpty();
        s.assertThat(changedCodes(ok(flash(b, lib.get(0), true)).path("quests")))
                .as("another Member at the same moment").isNotEmpty();
        s.assertAll();
    }

    // ------------------------------------------------------------ robustness of a made day

    /** The topic of today's TOPIC deleted by the editor during the day: the set stays and still answers. */
    @Test
    void theDaysSetSurvivesItsTopicBeingDeleted() throws Exception {
        LocalDate day = freshDay();
        long m = member();
        String t2 = topic("QA chủ đề sẽ xóa");
        List<Long> two = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            two.add(phrase("konbini", "VOCAB", t2));
        }
        isolateLibrary();
        for (int i = 0; i < 6; i++) {
            mark(m, two.get(i), i >= 4);
        }
        seedLearnNewHistory(m, day.minusDays(10), two.subList(0, 4));
        JsonNode before = today(m);
        JsonNode topic = requireQuest(before, "TOPIC");
        assertThat(topic.path("action").path("group").asText()).isEqualTo(t2);

        jdbc.update("DELETE FROM phrase_topic_links WHERE topic_id = ?", t2);
        jdbc.update("DELETE FROM learning_topics WHERE id = ?", t2);
        var r = getAs(m, "/api/me/quests/today").andReturn().getResponse();
        SoftAssertions s = new SoftAssertions();
        s.assertThat(r.getStatus()).as("GET today after the topic was deleted").isEqualTo(200);
        if (r.getStatus() == 200) {
            JsonNode after = JSON.readTree(r.getContentAsString(java.nio.charset.StandardCharsets.UTF_8)).path("data");
            JsonNode again = quest(after, "TOPIC");
            s.assertThat(again).as("TOPIC still today's").isNotNull();
            if (again != null) {
                s.assertThat(again.path("id").asLong()).isEqualTo(topic.path("id").asLong());
                s.assertThat(again.path("title").asText()).isEqualTo(topic.path("title").asText());
            }
        }
        clock.advance(GAP);
        s.assertThat(flash(m, two.get(4), true).andReturn().getResponse().getStatus()).as("flash still answers")
                .isEqualTo(200);
        s.assertThat(getAs(m, "/api/me/home").andReturn().getResponse().getStatus()).isEqualTo(200);
        s.assertAll();
    }

    @Test
    void withoutAnyPhraseTheHomesStillAnswerWithoutPhrase() throws Exception {
        freshDay();
        long m = member();
        vocabLibrary(5, 4, 1, m);
        SoftAssertions s = new SoftAssertions();
        JsonNode guest = ok(guestGet("/api/home"));
        s.assertThat(guest.has("phrase")).isTrue();
        s.assertThat(guest.path("phrase").isNull()).as("no kind PHRASE: no phrase of the day").isTrue();
        JsonNode home = ok(getAs(m, "/api/me/home"));
        s.assertThat(home.path("phrase").isNull()).isTrue();
        s.assertThat(home.path("quests").path("total").asInt()).isEqualTo(3);
        s.assertAll();
    }

    @Test
    void flashTargetCountsEntriesOfBothKinds() throws Exception {
        freshDay();
        long m = member();
        long only = phrase("konbini", "PHRASE", "pay");
        List<Long> words = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            words.add(phrase("konbini", "VOCAB", "pay"));
        }
        isolateLibrary();
        setStage(m, "ARR");
        mark(m, only, true);
        words.forEach(id -> mark(m, id, true));
        JsonNode t = today(m);
        assertThat(requireQuest(t, "FLASH_REVIEW").path("target").asInt()).isEqualTo(7);
        assertThat(requireQuest(t, "TOPIC").path("target").asInt()).as("1 phrase + 6 words of Thanh toán").isEqualTo(5);
    }

    /** The new COIN branch of "Lịch sử điểm" keeps the old lines' titles and links. */
    @Test
    void pointHistoryKeepsOldLinesNextToCoinExchanges() throws Exception {
        freshDay();
        long m = member();
        jdbc.update("""
                INSERT INTO places (name_vi, category_id, lat, lng, source, status, created_by)
                VALUES ('QA Quán Điểm', 'food', 35.7, 139.7, 'TEAM', 'ACTIVE', ?)""", m);
        long place = jdbc.queryForObject("SELECT MAX(id) FROM places WHERE created_by = ?", Long.class, m);
        try {
            jdbc.update("""
                    INSERT INTO point_transactions (user_id, delta, reason, ref_type, ref_id, created_at)
                    VALUES (?, 15, 'PIN_APPROVED', 'PLACE', ?, ?)""", m, place,
                    Timestamp.from(clock.instant().minusSeconds(60)));
            jdbc.update("UPDATE users SET points = 15 WHERE id = ?", m);
            seedCoins(m, 100);
            ok(exchangeRaw(m, "2"));

            JsonNode page = ok(getAs(m, "/api/me/points"));
            SoftAssertions s = new SoftAssertions();
            s.assertThat(page.path("total").asInt()).isEqualTo(2);
            s.assertThat(page.path("points").asInt()).isEqualTo(17);
            JsonNode exchange = page.path("items").path(0);
            JsonNode pin = page.path("items").path(1);
            s.assertThat(exchange.path("reason").asText()).isEqualTo("COIN_EXCHANGE");
            s.assertThat(exchange.path("title").asText()).isEqualTo("Đổi 100 xu");
            s.assertThat(exchange.path("link").asText()).isEqualTo("/quests");
            s.assertThat(pin.path("reason").asText()).isEqualTo("PIN_APPROVED");
            s.assertThat(pin.path("reasonLabel").asText()).isEqualTo("Ghim được duyệt");
            s.assertThat(pin.path("title").asText()).isEqualTo("QA Quán Điểm");
            s.assertThat(pin.path("link").asText()).isEqualTo("/map?place=" + place);
            s.assertAll();
        } finally {
            jdbc.update("DELETE FROM point_transactions WHERE user_id = ?", m);
            jdbc.update("DELETE FROM places WHERE id = ?", place);
        }
    }

    private void insertQuestXu(long userId, int coins, LocalDate questDate, Instant createdAt) {
        jdbc.update("""
                INSERT INTO coin_transactions (user_id, delta, reason, title, ref_type, ref_id, quest_date, created_at)
                VALUES (?, ?, 'DAILY_QUEST', 'QA: xu nhiệm vụ', 'QUEST', ?, ?, ?)""", userId, coins,
                -ThreadLocalRandom.current().nextLong(1, 1L << 40), questDate.toString(), Timestamp.from(createdAt));
        jdbc.update("UPDATE users SET coins = coins + ? WHERE id = ?", coins, userId);
    }
}
