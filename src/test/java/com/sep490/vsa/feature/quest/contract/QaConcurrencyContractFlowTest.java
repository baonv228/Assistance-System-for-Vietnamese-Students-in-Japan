package com.sep490.vsa.feature.quest.contract;

import com.fasterxml.jackson.databind.JsonNode;
import org.assertj.core.api.SoftAssertions;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

import java.time.LocalDate;
import java.util.List;

/**
 * 3.7 with the default 1500 ms rule: two devices, double taps, parallel exchanges. Every call of a run starts at the
 * same moment of the application clock.
 */
@TestPropertySource(properties = QaContractSupport.QA_DB)
class QaConcurrencyContractFlowTest extends QaContractSupport {

    @Test
    void parallelFirstOpeningsMakeOneSet() throws Exception {
        LocalDate day = freshDay();
        long m = member();
        payLibrary(6, m);

        List<Reply> replies = parallel(() -> getAs(m, "/api/me/quests/today"), () -> getAs(m, "/api/me/quests/today"),
                () -> getAs(m, "/api/me/quests/today"), () -> getAs(m, "/api/me/quests/today"));
        SoftAssertions s = new SoftAssertions();
        JsonNode first = null;
        for (Reply r : replies) {
            s.assertThat(r.status()).as("status; body " + r.body()).isEqualTo(200);
            if (r.status() == 200) {
                if (first == null) {
                    first = r.data().path("quests");
                } else {
                    s.assertThat(r.data().path("quests")).as("same set for every opening").isEqualTo(first);
                }
            }
        }
        s.assertThat(count("SELECT COUNT(*) FROM user_daily_quests WHERE user_id = ? AND quest_date = ?", m,
                day.toString())).isEqualTo(3);
        s.assertAll();
    }

    @Test
    void anOpeningAndAnAnswerAtOnceMakeOneSetAndCount() throws Exception {
        LocalDate day = freshDay();
        long m = member();
        List<Long> lib = payLibrary(6, m);

        List<Reply> replies = parallel(() -> getAs(m, "/api/me/quests/today"), () -> flash(m, lib.get(0), true));
        SoftAssertions s = new SoftAssertions();
        for (Reply r : replies) {
            s.assertThat(r.status()).as("status; body " + r.body()).isEqualTo(200);
        }
        s.assertThat(count("SELECT COUNT(*) FROM user_daily_quests WHERE user_id = ? AND quest_date = ?", m,
                day.toString())).isEqualTo(3);
        JsonNode t = today(m);
        s.assertThat(progress(t, "FLASH_REVIEW")).isEqualTo(1);
        s.assertThat(progress(t, "TOPIC")).isEqualTo(1);
        s.assertAll();
    }

    /** Two devices answer two different cards at the very same moment: 0 ms apart, so only one may count. */
    @Test
    void twoDevicesAnsweringAtTheSameMomentCountOnce() throws Exception {
        freshDay();
        SoftAssertions s = new SoftAssertions();
        long[] members = {member(), member(), member()};
        List<Long> lib = payLibrary(6, members);
        for (int round = 0; round < 3; round++) {
            long m = members[round];
            today(m);
            clock.advance(GAP);
            List<Reply> replies = parallel(() -> flash(m, lib.get(0), false), () -> flash(m, lib.get(1), false));
            int counted = 0;
            for (Reply r : replies) {
                s.assertThat(r.status()).as("status; body " + r.body()).isEqualTo(200);
                if (r.data() != null && r.data().path("quests").path("changed").size() > 0) {
                    counted++;
                }
            }
            s.assertThat(counted).as("round " + round + ": answers counted").isEqualTo(1);
            JsonNode t = today(m);
            s.assertThat(progress(t, "FLASH_REVIEW")).as("round " + round + ": FLASH progress").isEqualTo(1);
            s.assertThat(count("SELECT COUNT(*) FROM phrase_answer_log WHERE user_id = ?", m))
                    .as("both answers logged").isEqualTo(2);
            s.assertThat(count("SELECT COUNT(*) FROM user_phrase_marks WHERE user_id = ? AND is_learning", m))
                    .as("both marks saved").isEqualTo(2);
            clock.advance(GAP);
        }
        s.assertAll();
    }

    /** The last card of the day answered on two devices at once: the quest and the bonus are paid once. */
    @Test
    void twoDevicesFinishingTheDayAtOncePayOnce() throws Exception {
        freshDay();
        long m = member();
        List<Long> lib = payLibrary(5, m);
        JsonNode pod = requireQuest(today(m), "PHRASE_OF_DAY");
        listen(m, pod.path("phrase").path("id").asLong());
        for (int i = 0; i < 4; i++) {
            answer(m, lib.get(i), true);
        }
        clock.advance(GAP);
        long last = lib.get(4);
        List<Reply> replies = parallel(() -> flash(m, last, true), () -> flash(m, last, true));

        SoftAssertions s = new SoftAssertions();
        int bonus = 0;
        int awarded = 0;
        for (Reply r : replies) {
            s.assertThat(r.status()).as("status; body " + r.body()).isEqualTo(200);
            JsonNode qc = r.data() == null ? null : r.data().path("quests");
            if (qc != null && qc.path("bonusJustCompleted").asBoolean()) {
                bonus++;
            }
            awarded += qc == null ? 0 : qc.path("coinsAwarded").asInt();
        }
        s.assertThat(bonus).as("bonus completed once").isEqualTo(1);
        s.assertThat(awarded).as("FLASH 10 + TOPIC 10 + bonus 20, once").isEqualTo(40);
        s.assertThat(coinsOf(m)).isEqualTo(50);
        s.assertThat(count("SELECT COUNT(*) FROM coin_transactions WHERE user_id = ? AND reason = 'DAILY_QUEST'", m))
                .isEqualTo(3);
        s.assertThat(count("SELECT COUNT(*) FROM coin_transactions WHERE user_id = ? AND reason = 'DAILY_QUEST_ALL'", m))
                .isEqualTo(1);
        s.assertThat(count("SELECT COUNT(*) FROM user_quest_days WHERE user_id = ?", m)).isEqualTo(1);
        s.assertAll();
    }

    @Test
    void parallelListensPayOnce() throws Exception {
        freshDay();
        long m = member();
        payLibrary(5, m);
        long p = requireQuest(today(m), "PHRASE_OF_DAY").path("phrase").path("id").asLong();

        List<Reply> replies = parallel(() -> listenRaw(m, p), () -> listenRaw(m, p), () -> listenRaw(m, p),
                () -> listenRaw(m, p));
        SoftAssertions s = new SoftAssertions();
        int completed = 0;
        for (Reply r : replies) {
            s.assertThat(r.status()).as("status; body " + r.body()).isEqualTo(200);
            if (r.data() != null && r.data().path("justCompleted").size() > 0) {
                completed++;
            }
        }
        s.assertThat(completed).as("PHRASE_OF_DAY completed once").isEqualTo(1);
        s.assertThat(coinsOf(m)).isEqualTo(10);
        s.assertThat(count("SELECT COUNT(*) FROM coin_transactions WHERE user_id = ?", m)).isEqualTo(1);
        s.assertAll();
    }

    @Test
    void parallelExchangesNeverPassTheMonthCap() throws Exception {
        freshDay();
        long m = member();
        seedCoins(m, 1000);
        List<Reply> replies = parallel(() -> exchangeRaw(m, "4"), () -> exchangeRaw(m, "4"), () -> exchangeRaw(m, "4"),
                () -> exchangeRaw(m, "4"));
        SoftAssertions s = new SoftAssertions();
        int ok = 0;
        for (Reply r : replies) {
            if (r.status() == 200) {
                ok++;
            } else {
                s.assertThat(r.status()).as("refused; body " + r.body()).isEqualTo(400);
                s.assertThat(r.body().path("data").path("points").asText())
                        .isEqualTo("Tháng này bạn chỉ còn đổi được 2 điểm.");
            }
        }
        s.assertThat(ok).as("two exchanges of 4 fit under 10").isEqualTo(2);
        s.assertThat(pointsOf(m)).isEqualTo(8);
        s.assertThat(coinsOf(m)).isEqualTo(600);
        s.assertThat(count("SELECT COALESCE(SUM(delta), 0) FROM point_transactions WHERE user_id = ?", m)).isEqualTo(8);
        s.assertAll();
    }

    /**
     * Everything a Member can do at once (finishing the day, "Đổi", LISTEN again, opening the screens): no deadlock,
     * every call answers, the balance follows the ledger.
     */
    @Test
    void everythingAtOnceNeitherDeadlocksNorLosesXu() throws Exception {
        freshDay();
        SoftAssertions s = new SoftAssertions();
        long[] members = {member(), member(), member()};
        List<Long> lib = payLibrary(5, members);
        for (long m : members) {
            long p = requireQuest(today(m), "PHRASE_OF_DAY").path("phrase").path("id").asLong();
            listen(m, p);
            for (int i = 0; i < 4; i++) {
                answer(m, lib.get(i), true);
            }
            seedCoins(m, 100);
            clock.advance(GAP);
            List<Reply> replies = parallel(() -> flash(m, lib.get(4), true), () -> exchangeRaw(m, "2"),
                    () -> listenRaw(m, p), () -> getAs(m, "/api/me/quests/today"), () -> getAs(m, "/api/me/coins"),
                    () -> getAs(m, "/api/me/home"));
            for (Reply r : replies) {
                s.assertThat(r.status()).as("member " + m + " status; body " + r.body()).isEqualTo(200);
            }
            s.assertThat(coinsOf(m)).as("110 + 40 − 100").isEqualTo(50);
            s.assertThat(pointsOf(m)).isEqualTo(2);
            s.assertThat(count("SELECT COALESCE(SUM(delta), 0) FROM coin_transactions WHERE user_id = ?", m))
                    .as("users.coins = ledger").isEqualTo(coinsOf(m));
            s.assertThat(today(m).path("bonus").path("completed").asBoolean()).isTrue();
        }
        s.assertAll();
    }

    /**
     * "Xóa tài khoản" while the last answers of the day are on their way: a DELETED account must end with 0 xu and
     * no quest data (section 4; only ACTIVE Members have quests and xu, 1).
     */
    @Test
    void deletingTheAccountWhileFinishingTheDayLeavesNoXuNorQuestData() throws Exception {
        freshDay();
        SoftAssertions s = new SoftAssertions();
        long[] members = new long[8];
        for (int i = 0; i < members.length; i++) {
            members[i] = member();
        }
        List<Long> lib = payLibrary(5, members);
        for (long m : members) {
            long p = requireQuest(today(m), "PHRASE_OF_DAY").path("phrase").path("id").asLong();
            for (int i = 0; i < 4; i++) {
                answer(m, lib.get(i), true);
            }
            clock.advance(GAP);
            List<Reply> replies = parallel(() -> flash(m, lib.get(4), true), () -> listenRaw(m, p),
                    () -> deleteAs(m, "/api/me"));
            for (Reply r : replies) {
                s.assertThat(r.status()).as("member " + m + " status; body " + r.body()).isIn(200, 401, 403, 404);
            }
            String status = jdbc.queryForObject("SELECT status FROM users WHERE id = ?", String.class, m);
            s.assertThat(status).isEqualTo("DELETED");
            s.assertThat(coinsOf(m)).as("member " + m + " xu after deletion; replies " + replies).isZero();
            s.assertThat(count("SELECT COUNT(*) FROM user_daily_quests WHERE user_id = ?", m))
                    .as("member " + m + " quests after deletion").isZero();
            s.assertThat(count("SELECT COUNT(*) FROM user_quest_days WHERE user_id = ?", m))
                    .as("member " + m + " done days after deletion").isZero();
            s.assertThat(count("SELECT COUNT(*) FROM phrase_answer_log WHERE user_id = ?", m))
                    .as("member " + m + " answer log after deletion").isZero();
        }
        s.assertAll();
    }

    @Test
    void parallelExchangesNeverPassTheBalance() throws Exception {
        freshDay();
        long m = member();
        seedCoins(m, 150);
        List<Reply> replies = parallel(() -> exchangeRaw(m, "2"), () -> exchangeRaw(m, "2"), () -> exchangeRaw(m, "2"));
        SoftAssertions s = new SoftAssertions();
        int ok = 0;
        for (Reply r : replies) {
            if (r.status() == 200) {
                ok++;
            } else {
                s.assertThat(r.status()).as("refused; body " + r.body()).isEqualTo(400);
                s.assertThat(r.body().path("data").path("points").asText())
                        .isEqualTo("Bạn cần 100 xu để đổi 2 điểm, hiện có 50 xu.");
            }
        }
        s.assertThat(ok).isEqualTo(1);
        s.assertThat(coinsOf(m)).isEqualTo(50);
        s.assertThat(pointsOf(m)).isEqualTo(2);
        s.assertAll();
    }
}
