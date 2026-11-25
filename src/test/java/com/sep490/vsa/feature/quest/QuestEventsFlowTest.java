package com.sep490.vsa.feature.quest;

import com.fasterxml.jackson.databind.JsonNode;
import com.sep490.vsa.feature.quest.QuestDtos.HomeQuests;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * POST /api/me/quests/events (contract 5.3), who may use the quest API (3.8), what stays as it was for the flash card
 * API (5.2) and the read-only block of the member Home (5.6).
 */
class QuestEventsFlowTest extends QuestFlowSupport {

    /** Today = FLASH_REVIEW (3 entries) + PHRASE_OF_DAY; the phrase of the day's id. */
    private long listenDay(long user, LocalDate day) throws Exception {
        clearPhraseOfDay(day);
        phrases(3);
        force(user, "FLASH_REVIEW", "PHRASE_OF_DAY");
        return quest(today(user), "PHRASE_OF_DAY").path("phrase").path("id").asLong();
    }

    @Test
    void listeningToThePhraseOfTheDayCompletesItOnce() throws Exception {
        LocalDate day = freshDay();
        isolateLibrary();
        long user = member();
        long phrase = listenDay(user, day);
        long other = phrase("konbini", "PHRASE");

        // Another phrase (e.g. yesterday's, just after midnight): 200, nothing changes
        JsonNode wrong = data(listen(user, other).andExpect(status().isOk()));
        assertThat(wrong.path("changed")).isEmpty();
        assertThat(wrong.path("justCompleted")).isEmpty();
        assertThat(wrong.path("coinsAwarded").asInt()).isZero();

        JsonNode right = whole(listen(user, phrase).andExpect(status().isOk()));
        assertThat(right.path("success").asBoolean()).isTrue();
        assertThat(right.path("message").asText())
                .isEqualTo("Đã ghi nhận. Xong nhiệm vụ “Nghe câu tiếng Nhật hôm nay” · +10 xu.");
        JsonNode change = right.path("data");
        assertThat(change.path("changed")).hasSize(1);
        assertThat(change.path("changed").get(0).path("code").asText()).isEqualTo("PHRASE_OF_DAY");
        assertThat(change.path("changed").get(0).path("progress").asInt()).isEqualTo(1);
        assertThat(change.path("changed").get(0).path("target").asInt()).isEqualTo(1);
        assertThat(change.path("changed").get(0).path("completed").asBoolean()).isTrue();
        assertThat(change.path("justCompleted").get(0).path("title").asText()).isEqualTo("Nghe câu tiếng Nhật hôm nay");
        assertThat(change.path("justCompleted").get(0).path("coins").asInt()).isEqualTo(10);
        assertThat(change.path("bonusJustCompleted").asBoolean()).isFalse();
        assertThat(change.path("coinsAwarded").asInt()).isEqualTo(10);
        assertThat(change.path("wallet").path("coins").asInt()).isEqualTo(10);

        // Again: idempotent
        JsonNode again = whole(listen(user, phrase).andExpect(status().isOk()));
        assertThat(again.path("message").asText()).isEqualTo("Đã ghi nhận.");
        assertThat(again.path("data").path("changed")).isEmpty();
        assertThat(again.path("data").path("coinsAwarded").asInt()).isZero();
        assertThat(rows("SELECT COUNT(*) FROM coin_transactions WHERE user_id = ?", user)).isEqualTo(1);
        assertThat(coinsOf(user)).isEqualTo(10);
        JsonNode listened = quest(today(user), "PHRASE_OF_DAY");
        assertThat(listened.path("completed").asBoolean()).isTrue();
        assertThat(listened.path("progress").asInt()).isEqualTo(1);
    }

    @Test
    void anEventWithoutPhraseOfDayTodayChangesNothing() throws Exception {
        freshDay();
        isolateLibrary();
        long user = member();
        long phrase = phrases(3).get(0);
        force(user, "FLASH_REVIEW");

        JsonNode change = data(listen(user, phrase).andExpect(status().isOk()));

        assertThat(change.path("changed")).isEmpty();
        assertThat(change.path("coinsAwarded").asInt()).isZero();
        assertThat(quest(today(user), "FLASH_REVIEW").path("progress").asInt()).isZero();
    }

    @Test
    void theFirstEventOfTheDayMakesTheDay() throws Exception {
        freshDay();
        isolateLibrary();
        long user = member();
        phrases(3);

        data(listen(user, 1).andExpect(status().isOk()));

        assertThat(questRows(user)).isPositive();
    }

    @Test
    void aWrongTypeOrAMissingPhraseIsRefusedOnItsField() throws Exception {
        freshDay();
        long user = member();

        postAs(user, "/api/me/quests/events", "{\"type\":\"PLAY\",\"phraseId\":1}").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value("Loại sự kiện không hợp lệ."))
                .andExpect(jsonPath("$.data.type").value("Loại sự kiện không hợp lệ."))
                .andExpect(jsonPath("$.data.phraseId").doesNotExist());
        postAs(user, "/api/me/quests/events", "{\"type\":\"LISTEN\"}").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Hãy gửi mã câu đã nghe."))
                .andExpect(jsonPath("$.data.phraseId").value("Hãy gửi mã câu đã nghe."))
                .andExpect(jsonPath("$.data.type").doesNotExist());
        postAs(user, "/api/me/quests/events", "{\"type\":\"LISTEN\",\"phraseId\":\"abc\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.data.phraseId").value("Hãy gửi mã câu đã nghe."));
        postAs(user, "/api/me/quests/events", "{}").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.data.type").value("Loại sự kiện không hợp lệ."))
                .andExpect(jsonPath("$.data.phraseId").value("Hãy gửi mã câu đã nghe."));
        assertThat(questRows(user)).isZero();
    }

    @Test
    void moreThanThirtyEventsAMinuteAreRefused() throws Exception {
        LocalDate day = freshDay();
        isolateLibrary();
        long user = member();
        long phrase = listenDay(user, day);

        for (int i = 0; i < 30; i++) {
            listen(user, phrase).andExpect(status().isOk());
        }
        listen(user, phrase).andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.message").value("Bạn thao tác quá nhanh. Vui lòng thử lại sau ít phút."));

        clock.advance(Duration.ofSeconds(61));
        listen(user, phrase).andExpect(status().isOk());
        assertThat(rows("SELECT COUNT(*) FROM coin_transactions WHERE user_id = ?", user)).isEqualTo(1);
    }

    @Test
    void theSameEventSentTogetherPaysOnce() throws Exception {
        LocalDate day = freshDay();
        isolateLibrary();
        long user = member();
        long phrase = listenDay(user, day);

        int threads = 4;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<JsonNode>> results = new ArrayList<>();
        try {
            for (int i = 0; i < threads; i++) {
                Callable<JsonNode> call = () -> {
                    start.await();
                    return data(listen(user, phrase).andExpect(status().isOk()));
                };
                results.add(pool.submit(call));
            }
            start.countDown();
            int completions = 0;
            for (Future<JsonNode> result : results) {
                completions += result.get(30, TimeUnit.SECONDS).path("justCompleted").size();
            }
            assertThat(completions).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
        long questId = quest(today(user), "PHRASE_OF_DAY").path("id").asLong();
        assertThat(rows("""
                SELECT COUNT(*) FROM coin_transactions WHERE user_id = ? AND reason = 'DAILY_QUEST' AND ref_id = ?""",
                user, questId)).isEqualTo(1);
        assertThat(coinsOf(user)).isEqualTo(10);
    }

    @Test
    void guestsAndStaffCannotUseTheQuests() throws Exception {
        freshDay();
        long moderator = user("MODERATOR", "ACTIVE");
        long manager = user("ACADEMIC_MANAGER", "ACTIVE");

        guestGet("/api/me/quests/today").andExpect(status().isUnauthorized());
        guestPost("/api/me/quests/events", "{\"type\":\"LISTEN\",\"phraseId\":1}").andExpect(status().isUnauthorized());
        getAs(moderator, "MODERATOR", "/api/me/quests/today").andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value(MemberGuard.NO_PERMISSION));
        postAs(moderator, "MODERATOR", "/api/me/quests/events", "{\"type\":\"LISTEN\",\"phraseId\":1}")
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value("Bạn không có quyền thực hiện thao tác này."));
        getAs(manager, "ACADEMIC_MANAGER", "/api/me/quests/today").andExpect(status().isForbidden());
        assertThat(questRows(moderator)).isZero();

        long user = member();
        getAs(user, "/api/me/quests/today").andExpect(status().isOk()).andExpect(jsonPath("$.success").value(true));
        postAs(user, "/api/me/quests/events", "{\"type\":\"LISTEN\",\"phraseId\":1}").andExpect(status().isOk());
    }

    @Test
    void theFlashCardApiKeepsWhatItAnsweredBefore() throws Exception {
        freshDay();
        isolateLibrary();
        long user = member();
        long phrase = phrases(3).get(0);

        flash(user, phrase, true).andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.message").value("Đã lưu: Đã thuộc."))
                .andExpect(jsonPath("$.data.learning").value(false))
                .andExpect(jsonPath("$.data.learned").value(true))
                .andExpect(jsonPath("$.data.quests.changed").isArray())
                .andExpect(jsonPath("$.data.quests.dailyCap").value(50))
                .andExpect(jsonPath("$.data.quests.wallet.rate").value(50));
        next();
        flash(user, phrase, false).andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Đã lưu: Chưa thuộc."))
                .andExpect(jsonPath("$.data.learning").value(true))
                .andExpect(jsonPath("$.data.learned").value(false));
        deleteAs(user, "/api/phrases/" + phrase + "/flash").andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Đã bỏ đánh dấu."))
                .andExpect(jsonPath("$.data.learning").value(false))
                .andExpect(jsonPath("$.data.learned").value(false))
                .andExpect(jsonPath("$.data.quests").doesNotExist());

        // Staff keep getting MSG18 on flash cards, guests 401
        long moderator = user("MODERATOR", "ACTIVE");
        postAs(moderator, "MODERATOR", "/api/phrases/" + phrase + "/flash", "{\"learned\":true}")
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value("Bạn không có quyền thực hiện thao tác này."));
        guestPost("/api/phrases/" + phrase + "/flash", "{\"learned\":true}").andExpect(status().isUnauthorized());
        assertThat(questRows(moderator)).isZero();
        assertThat(rows("SELECT COUNT(*) FROM phrase_answer_log WHERE user_id = ?", moderator)).isZero();
        // The old request rules too
        postAs(user, "/api/phrases/" + phrase + "/flash", "{}").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.data.learned").value("Hãy chọn Chưa thuộc hoặc Đã thuộc."));
    }

    @Test
    void theHomeOnlyReadsTheQuests() throws Exception {
        LocalDate day = freshDay();
        isolateLibrary();
        long user = member();
        long only = phrases(1).get(0);
        doneDay(user, day.minusDays(1), 2);

        HomeQuests before = quests.homeSummary(user);
        assertThat(before.generated()).isFalse();
        assertThat(before.done()).isZero();
        assertThat(before.total()).isEqualTo(3);
        assertThat(before.bonusDone()).isFalse();
        assertThat(before.streak()).isEqualTo(2);
        assertThat(before.todayDone()).isFalse();
        assertThat(before.coinsToday()).isZero();
        assertThat(before.dailyCap()).isEqualTo(50);
        assertThat(before.coins()).isZero();
        assertThat(questRows(user)).isZero();
        assertThat(rows("SELECT COUNT(*) FROM user_quest_days WHERE user_id = ? AND quest_date = ?", user,
                java.sql.Date.valueOf(day))).isZero();

        force(user, "FLASH_REVIEW", "LEARN_NEW");
        HomeQuests made = quests.homeSummary(user);
        assertThat(made.generated()).isTrue();
        assertThat(made.done()).isZero();
        assertThat(made.total()).isEqualTo(2);

        answer(user, only, false);                   // FLASH_REVIEW only ("Chưa thuộc" is not LEARN_NEW)
        HomeQuests half = quests.homeSummary(user);
        assertThat(half.done()).isEqualTo(1);
        assertThat(half.bonusDone()).isFalse();
        assertThat(half.coinsToday()).isEqualTo(10);
        assertThat(half.coins()).isEqualTo(10);
        assertThat(half.streak()).isEqualTo(2);

        next();
        answer(user, only, true);
        HomeQuests done = quests.homeSummary(user);
        assertThat(done.done()).isEqualTo(2);
        assertThat(done.total()).isEqualTo(2);
        assertThat(done.bonusDone()).isTrue();
        assertThat(done.todayDone()).isTrue();
        assertThat(done.streak()).isEqualTo(3);
        assertThat(done.coinsToday()).isEqualTo(40);
        assertThat(done.coins()).isEqualTo(40);
        assertThat(done.exchangeableNow()).isZero();
    }
}
