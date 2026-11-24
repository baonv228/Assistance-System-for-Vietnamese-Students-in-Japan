package com.sep490.vsa.feature.quest.contract;

import com.fasterxml.jackson.databind.JsonNode;
import org.assertj.core.api.SoftAssertions;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

import java.util.List;

/**
 * 3.7 "Không cộng xu hai lần khi có hai thao tác cùng lúc" with the 1500 ms rule switched off (as a developer machine
 * may run, app.quests.min-answer-interval-ms=0), so parallel answers really race on the quest rows and only the
 * unique keys and the users-row lock keep the payments single.
 */
@TestPropertySource(properties = {QaContractSupport.QA_DB, "app.quests.min-answer-interval-ms=0"})
class QaRaceWithoutIntervalContractFlowTest extends QaContractSupport {

    @Test
    void twoDevicesCompletingTheLastQuestTogetherPayEverythingOnce() throws Exception {
        freshDay();
        SoftAssertions s = new SoftAssertions();
        long[] members = {member(), member(), member()};
        List<Long> lib = payLibrary(5, members);
        for (int round = 0; round < 3; round++) {
            long m = members[round];
            JsonNode pod = requireQuest(today(m), "PHRASE_OF_DAY");
            listen(m, pod.path("phrase").path("id").asLong());
            for (int i = 0; i < 3; i++) {
                answer(m, lib.get(i), true);
            }
            clock.advance(GAP);
            List<Reply> replies = parallel(() -> flash(m, lib.get(3), true), () -> flash(m, lib.get(4), true));
            int bonus = 0;
            int awarded = 0;
            for (Reply r : replies) {
                s.assertThat(r.status()).as("round " + round + " status; body " + r.body()).isEqualTo(200);
                JsonNode qc = r.data() == null ? null : r.data().path("quests");
                if (qc != null) {
                    bonus += qc.path("bonusJustCompleted").asBoolean() ? 1 : 0;
                    awarded += qc.path("coinsAwarded").asInt();
                    s.assertThat(qc.path("changed").size()).as("round " + round + ": each answer counted")
                            .isGreaterThan(0);
                }
            }
            s.assertThat(bonus).as("round " + round + ": bonus once").isEqualTo(1);
            s.assertThat(awarded).as("round " + round + ": 10 + 10 + 20").isEqualTo(40);
            JsonNode t = today(m);
            s.assertThat(progress(t, "FLASH_REVIEW")).isEqualTo(5);
            s.assertThat(progress(t, "TOPIC")).isEqualTo(5);
            s.assertThat(t.path("bonus").path("completed").asBoolean()).isTrue();
            s.assertThat(coinsOf(m)).as("round " + round + ": xu").isEqualTo(50);
            s.assertThat(count("SELECT COUNT(*) FROM coin_transactions WHERE user_id = ? AND reason = 'DAILY_QUEST'", m))
                    .isEqualTo(3);
            s.assertThat(count(
                    "SELECT COUNT(*) FROM coin_transactions WHERE user_id = ? AND reason = 'DAILY_QUEST_ALL'", m))
                    .isEqualTo(1);
            s.assertThat(count("SELECT COUNT(*) FROM user_quest_days WHERE user_id = ?", m)).isEqualTo(1);
        }
        s.assertAll();
    }

    @Test
    void theSameCardAnsweredOnTwoDevicesAtOnceCountsOnce() throws Exception {
        freshDay();
        long m = member();
        List<Long> lib = payLibrary(6, m);
        today(m);
        clock.advance(GAP);
        List<Reply> replies = parallel(() -> flash(m, lib.get(0), true), () -> flash(m, lib.get(0), false),
                () -> flash(m, lib.get(0), true));
        SoftAssertions s = new SoftAssertions();
        int counted = 0;
        for (Reply r : replies) {
            s.assertThat(r.status()).as("status; body " + r.body()).isEqualTo(200);
            if (r.data() != null && r.data().path("quests").path("changed").size() > 0) {
                counted++;
            }
        }
        s.assertThat(counted).isEqualTo(1);
        JsonNode t = today(m);
        s.assertThat(progress(t, "FLASH_REVIEW")).isEqualTo(1);
        s.assertThat(progress(t, "TOPIC")).isEqualTo(1);
        s.assertAll();
    }

    @Test
    void progressNeverPassesTheTargetUnderARace() throws Exception {
        freshDay();
        long m = member();
        List<Long> lib = payLibrary(8, m);
        today(m);
        for (int i = 0; i < 4; i++) {
            answer(m, lib.get(i), true);
        }
        clock.advance(GAP);
        List<Reply> replies = parallel(() -> flash(m, lib.get(4), true), () -> flash(m, lib.get(5), true),
                () -> flash(m, lib.get(6), true));
        SoftAssertions s = new SoftAssertions();
        int topicDone = 0;
        for (Reply r : replies) {
            s.assertThat(r.status()).as("status; body " + r.body()).isEqualTo(200);
            if (r.data() != null) {
                for (JsonNode j : r.data().path("quests").path("justCompleted")) {
                    if ("TOPIC".equals(j.path("code").asText())) {
                        topicDone++;
                    }
                }
            }
        }
        s.assertThat(topicDone).as("TOPIC completed once").isEqualTo(1);
        JsonNode t = today(m);
        s.assertThat(progress(t, "TOPIC")).isEqualTo(5);
        s.assertThat(progress(t, "FLASH_REVIEW")).isEqualTo(7);
        s.assertThat(coinsOf(m)).isEqualTo(10);
        s.assertAll();
    }
}
