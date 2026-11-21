package com.sep490.vsa.feature.quest.dev;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;

import java.sql.Date;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The test-only API with its flag on and a database of this machine (contract 5.9): clearing today's quests, the days
 * done before today, forced quests, and who may call it.
 */
class DevQuestsFlowTest extends DevApiTestSupport {

    private static final String DONE_DAYS_INVALID = "Số ngày đã xong phải là số nguyên từ 0 đến 365.";

    // ---------------------------------------------------------------- DELETE /api/dev/quests/today

    @Test
    void clearingTodayTakesBackTodaysXuOnlyAndKeepsEverythingElse() throws Exception {
        LocalDate day = freshDay();
        isolateLibrary();
        clearPhraseOfDay(day);
        List<Long> ids = phrases(2);
        long user = member();

        // Yesterday: FLASH_REVIEW only (target 2), both answered "Chưa thuộc": +10, and +20 for the whole day
        setJapanTime(day.minusDays(1), LocalTime.NOON);
        forceToday(user, "FLASH_REVIEW").andExpect(status().isOk());
        answer(user, ids.get(0), false);
        next();
        answer(user, ids.get(1), false);
        assertThat(coinsOf(user)).isEqualTo(30);

        // This morning: 100 xu given, then 1 point bought with 50 of them
        setJapanTime(day, LocalTime.of(9, 0));
        setCoins(user, "{\"coins\":130}").andExpect(status().isOk());
        exchange(user, 1).andExpect(status().isOk());
        assertThat(coinsOf(user)).isEqualTo(80);
        assertThat(pointsOf(user)).isEqualTo(1);

        // Noon: six days done before today, then today's three quests: the 7th day in a row
        setJapanTime(day, LocalTime.NOON);
        history(user, 6).andExpect(status().isOk());
        int coinsBefore = coinsOf(user);
        JsonNode set = data(forceToday(user, "FLASH_REVIEW", "REVIEW_LEARNING", "PHRASE_OF_DAY")
                .andExpect(status().isOk()));
        List<Long> oldIds = ids(set);
        long phraseOfDay = quest(set, "PHRASE_OF_DAY").path("phrase").path("id").asLong();
        answer(user, ids.get(0), true);
        next();
        answer(user, ids.get(1), true);
        next();
        JsonNode change = listen(user, phraseOfDay);
        assertThat(change.path("streakMilestone").path("days").asInt()).isEqualTo(7);
        assertThat(coinsOf(user)).isEqualTo(coinsBefore + 10 + 10 + 10 + 20 + 50);
        assertThat(coinReasonsOn(user, day))
                .containsExactlyInAnyOrder("DAILY_QUEST", "DAILY_QUEST", "DAILY_QUEST", "DAILY_QUEST_ALL", "QUEST_STREAK");
        assertThat(rows("SELECT COUNT(*) FROM notifications WHERE user_id = ? AND type = 'QUEST_STREAK'", user))
                .isEqualTo(1);
        Map<Long, String> marks = marks(user);
        assertThat(rows("SELECT COUNT(*) FROM user_quest_items i JOIN user_daily_quests q ON q.id = i.user_daily_quest_id"
                + " WHERE q.user_id = ? AND q.quest_date = ?", user, Date.valueOf(day))).isEqualTo(5);

        var result = clearToday(user).andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.message").value("Đã xóa nhiệm vụ hôm nay."));
        JsonNode wallet = data(result);

        // Today's xu are taken back to the coin, nothing else
        assertThat(wallet.path("coins").asInt()).isEqualTo(coinsBefore).isEqualTo(80);
        assertThat(coinsOf(user)).isEqualTo(coinsBefore);
        assertThat(bookOf(user)).isEqualTo(coinsBefore);
        assertThat(wallet.path("points").asInt()).isEqualTo(1);
        assertThat(wallet.path("exchangedThisMonth").asInt()).isEqualTo(1);
        assertThat(wallet.path("rate").asInt()).isEqualTo(50);
        assertThat(coinReasonsOn(user, day)).isEmpty();
        assertThat(coinReasonsOn(user, day.minusDays(1))).containsExactly("DAILY_QUEST", "DAILY_QUEST_ALL");
        assertThat(rows("SELECT COUNT(*) FROM coin_transactions WHERE user_id = ? AND reason = 'EXCHANGE'", user))
                .isEqualTo(1);
        assertThat(rows("SELECT COUNT(*) FROM coin_transactions WHERE user_id = ? AND reason = 'DEV_ADJUST'", user))
                .isEqualTo(1);
        assertThat(rows("SELECT COUNT(*) FROM point_transactions WHERE user_id = ? AND reason = 'COIN_EXCHANGE'", user))
                .isEqualTo(1);
        assertThat(pointsOf(user)).isEqualTo(1);
        // Today's quests, counted entries, day done and milestone notification are gone; other days stay
        assertThat(rows("SELECT COUNT(*) FROM user_daily_quests WHERE user_id = ? AND quest_date = ?", user,
                Date.valueOf(day))).isZero();
        assertThat(rows("SELECT COUNT(*) FROM user_quest_items i JOIN user_daily_quests q ON q.id = i.user_daily_quest_id"
                + " WHERE q.user_id = ? AND q.quest_date = ?", user, Date.valueOf(day))).isZero();
        assertThat(rows("SELECT COUNT(*) FROM user_daily_quests WHERE user_id = ? AND quest_date = ?", user,
                Date.valueOf(day.minusDays(1)))).isEqualTo(1);
        assertThat(doneDays(user)).containsOnlyKeys(day.minusDays(6), day.minusDays(5), day.minusDays(4),
                day.minusDays(3), day.minusDays(2), day.minusDays(1));
        assertThat(rows("SELECT COUNT(*) FROM notifications WHERE user_id = ? AND type = 'QUEST_STREAK'", user))
                .isZero();
        // Flash card marks and the phrase of the day are kept
        assertThat(marks(user)).isEqualTo(marks);
        assertThat(rows("SELECT phrase_id FROM daily_phrases WHERE phrase_date = ?", Date.valueOf(day)))
                .isEqualTo((int) phraseOfDay);

        // The next GET today makes a new set, from nothing
        JsonNode fresh = today(user);
        assertThat(ids(fresh)).isNotEmpty().doesNotContainAnyElementsOf(oldIds);
        fresh.path("quests").forEach(q -> {
            assertThat(q.path("progress").asInt()).isZero();
            assertThat(q.path("completed").asBoolean()).isFalse();
        });
        assertThat(fresh.path("coinsToday").asInt()).isZero();
        assertThat(fresh.path("bonus").path("completed").asBoolean()).isFalse();
        assertThat(fresh.path("streak").path("current").asInt()).isEqualTo(6);
        assertThat(fresh.path("streak").path("todayDone").asBoolean()).isFalse();
    }

    @Test
    void clearingADayWithoutQuestsChangesNothing() throws Exception {
        freshDay();
        long user = member();
        setCoins(user, "{\"coins\":40}").andExpect(status().isOk());

        JsonNode wallet = data(clearToday(user).andExpect(status().isOk()));

        assertThat(wallet.path("coins").asInt()).isEqualTo(40);
        assertThat(coinsOf(user)).isEqualTo(40);
        assertThat(rows("SELECT COUNT(*) FROM user_daily_quests WHERE user_id = ?", user)).isZero();
    }

    // ---------------------------------------------------------------- POST /api/dev/quests/history

    @Test
    void sixDaysDoneBeforeTodayThenTodayReachTheSevenDayMilestoneOnce() throws Exception {
        LocalDate day = freshDay();
        isolateLibrary();
        List<Long> ids = phrases(2);
        long user = member();

        var result = history(user, 6).andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Đã tạo 6 ngày đã xong liền trước hôm nay."));
        JsonNode history = data(result);

        assertThat(history.path("doneDays").asInt()).isEqualTo(6);
        assertThat(history.path("streak").asInt()).isEqualTo(6);
        Map<LocalDate, Integer> expected = new TreeMap<>();
        for (int streak = 1; streak <= 6; streak++) {
            expected.put(day.minusDays(7 - streak), streak);       // oldest day 1, yesterday 6
        }
        assertThat(doneDays(user)).isEqualTo(expected);
        Timestamp noon = Timestamp.from(day.minusDays(6).atTime(LocalTime.NOON).atZone(JAPAN).toInstant());
        assertThat(jdbc.queryForObject("SELECT all_done_at FROM user_quest_days WHERE user_id = ? AND quest_date = ?",
                Timestamp.class, user, Date.valueOf(day.minusDays(6)))).isEqualTo(noon);
        assertThat(rows("SELECT COUNT(*) FROM coin_transactions WHERE user_id = ?", user)).isZero();
        assertThat(rows("SELECT COUNT(*) FROM user_daily_quests WHERE user_id = ?", user)).isZero();
        assertThat(coinsOf(user)).isZero();
        JsonNode before = today(user);
        assertThat(before.path("streak").path("current").asInt()).isEqualTo(6);
        assertThat(before.path("streak").path("best").asInt()).isEqualTo(6);
        List<Boolean> recent = new ArrayList<>();
        before.path("recentDays").forEach(d -> recent.add(d.path("done").asBoolean()));
        assertThat(recent).containsExactly(true, true, true, true, true, true, false);

        forceToday(user, "FLASH_REVIEW").andExpect(status().isOk());
        answer(user, ids.get(0), true);
        next();
        JsonNode change = answer(user, ids.get(1), true).path("quests");

        assertThat(change.path("bonusJustCompleted").asBoolean()).isTrue();
        assertThat(change.path("streak").path("current").asInt()).isEqualTo(7);
        assertThat(change.path("streakMilestone").path("days").asInt()).isEqualTo(7);
        assertThat(change.path("streakMilestone").path("coins").asInt()).isEqualTo(50);
        assertThat(change.path("coinsAwarded").asInt()).isEqualTo(10 + 20 + 50);
        assertThat(coinsOf(user)).isEqualTo(80);

        // Answering again on the day done pays nothing more
        next();
        answer(user, ids.get(0), false);
        assertThat(coinsOf(user)).isEqualTo(80);
        assertThat(rows("SELECT COUNT(*) FROM coin_transactions WHERE user_id = ? AND reason = 'QUEST_STREAK'", user))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT title FROM coin_transactions WHERE user_id = ? AND reason = 'QUEST_STREAK'",
                String.class, user)).isEqualTo("Học 7 ngày liên tiếp");
        assertThat(jdbc.queryForList("SELECT content FROM notifications WHERE user_id = ? AND type = 'QUEST_STREAK'",
                String.class, user)).containsExactly("Bạn đã học 7 ngày liên tiếp. +50 xu.");
        JsonNode after = today(user);
        assertThat(after.path("streak").path("current").asInt()).isEqualTo(7);
        assertThat(after.path("streak").path("best").asInt()).isEqualTo(7);
        assertThat(after.path("streak").path("todayDone").asBoolean()).isTrue();
    }

    @Test
    void zeroDoneDaysRemovesTheDaysBeforeTodayAndKeepsToday() throws Exception {
        LocalDate day = freshDay();
        isolateLibrary();
        long only = phrases(1).get(0);
        long user = member();
        jdbc.update("INSERT INTO user_quest_days (user_id, quest_date, all_done_at, streak) VALUES (?, ?, ?, 9)", user,
                Date.valueOf(day.minusDays(40)), Timestamp.from(day.minusDays(40).atStartOfDay(JAPAN).toInstant()));

        assertThat(data(history(user, 3).andExpect(status().isOk())).path("streak").asInt()).isEqualTo(3);
        assertThat(doneDays(user)).hasSize(3).doesNotContainKey(day.minusDays(40));

        var result = history(user, 0).andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Đã xóa các ngày trước hôm nay."));
        assertThat(data(result).path("doneDays").asInt()).isZero();
        assertThat(data(result).path("streak").asInt()).isZero();
        assertThat(doneDays(user)).isEmpty();
        assertThat(today(user).path("streak").path("current").asInt()).isZero();

        // A day done already keeps its row and streak: the call is meant before today's quests are done
        history(user, 6).andExpect(status().isOk());
        clearToday(user).andExpect(status().isOk());
        forceToday(user, "FLASH_REVIEW").andExpect(status().isOk());
        assertThat(answer(user, only, true).path("quests").path("streakMilestone").path("days").asInt()).isEqualTo(7);
        JsonNode cleared = data(history(user, 0).andExpect(status().isOk()));
        assertThat(cleared.path("streak").asInt()).isEqualTo(7);
        assertThat(doneDays(user)).containsExactlyEntriesOf(Map.of(day, 7));

        // Clearing today as well: the streak starts again at 1, without a milestone
        clearToday(user).andExpect(status().isOk());
        assertThat(today(user).path("streak").path("current").asInt()).isZero();
        clearToday(user).andExpect(status().isOk());
        forceToday(user, "FLASH_REVIEW").andExpect(status().isOk());
        next();
        JsonNode again = answer(user, only, false).path("quests");
        assertThat(again.path("streak").path("current").asInt()).isEqualTo(1);
        assertThat(again.path("streakMilestone").isNull()).isTrue();
        assertThat(doneDays(user)).containsExactlyEntriesOf(Map.of(day, 1));
    }

    @Test
    void doneDaysOutside0To365OrNotAWholeNumberAreRefusedOnTheField() throws Exception {
        LocalDate day = freshDay();
        long user = member();
        history(user, 2).andExpect(status().isOk());

        for (String body : List.of("{\"doneDays\":-1}", "{\"doneDays\":366}", "{}", "{\"doneDays\":null}",
                "{\"doneDays\":\"6\"}", "{\"doneDays\":2.5}", "{\"doneDays\":true}", "{\"doneDays\":99999999999}",
                "[]", "6")) {
            history(user, body).andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.success").value(false))
                    .andExpect(jsonPath("$.message").value(DONE_DAYS_INVALID))
                    .andExpect(jsonPath("$.data.doneDays").value(DONE_DAYS_INVALID));
        }
        call(HttpMethod.POST, "/api/dev/quests/history", user, "USER", null)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.data.doneDays").value(DONE_DAYS_INVALID));
        // Nothing changed
        assertThat(doneDays(user)).containsOnlyKeys(day.minusDays(2), day.minusDays(1));

        assertThat(data(history(user, 365).andExpect(status().isOk())).path("streak").asInt()).isEqualTo(365);
        assertThat(doneDays(user)).hasSize(365).containsEntry(day.minusDays(365), 1).containsEntry(day.minusDays(1), 365);
    }

    // ---------------------------------------------------------------- PUT /api/dev/quests/today

    @Test
    void forcedCodesComeInTheOrderAskedWithTheFixedQuestFirst() throws Exception {
        LocalDate day = freshDay();
        isolateLibrary();
        clearPhraseOfDay(day);
        String topic = topic("Thử ép nhiệm vụ");
        phrases(5, topic);
        long user = member();

        var result = forceToday(user, "FLASH_REVIEW", "TOPIC", "PHRASE_OF_DAY").andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Đã đặt nhiệm vụ hôm nay."));
        JsonNode set = data(result);

        assertThat(codes(set)).containsExactly("FLASH_REVIEW", "TOPIC", "PHRASE_OF_DAY");
        assertThat(quest(set, "FLASH_REVIEW").path("target").asInt()).isEqualTo(5);
        assertThat(quest(set, "TOPIC").path("target").asInt()).isEqualTo(5);
        assertThat(quest(set, "TOPIC").path("action").path("group").asText()).isEqualTo(topic);
        assertThat(quest(set, "TOPIC").path("title").asText()).isEqualTo("Ôn 5 thẻ chủ đề “Thử ép nhiệm vụ”");
        assertThat(quest(set, "PHRASE_OF_DAY").path("phrase").isObject()).isTrue();
        // The same answer as GET /api/me/quests/today
        assertThat(set.path("date").asText()).isEqualTo(day.toString());
        assertThat(set.path("recentDays")).hasSize(7);
        assertThat(set.path("wallet").path("coins").asInt()).isZero();
        assertThat(today(user)).isEqualTo(set);

        JsonNode reordered = data(forceToday(user, "PHRASE_OF_DAY", "TOPIC", "FLASH_REVIEW")
                .andExpect(status().isOk()));
        assertThat(codes(reordered)).containsExactly("FLASH_REVIEW", "PHRASE_OF_DAY", "TOPIC");
        assertThat(ids(reordered)).doesNotContainAnyElementsOf(ids(set));

        assertThat(codes(data(forceToday(user, "TOPIC").andExpect(status().isOk())))).containsExactly("TOPIC");
    }

    @org.springframework.beans.factory.annotation.Autowired
    private com.sep490.vsa.feature.quest.QuestSettings questSettings;

    /**
     * The forced codes are made as for a Member without interests (the contract's "bỏ qua random"), so the mobile
     * app's tests of the quests never depend on what the sample account is interested in; only
     * {@code "followInterests": true} makes them follow the interests (QUEST_INTERESTS.md).
     */
    @Test
    void forcedCodesFollowTheInterestsOnlyWhenAsked() throws Exception {
        freshDay();
        isolateLibrary();
        String mine = topic("Sở thích ép");
        String other = topic("Nhiều thẻ hơn");
        phrases(5, mine);
        phrases(8, other);
        long user = member();
        String name = "Dev quan tâm " + java.util.UUID.randomUUID().toString().substring(0, 8);
        jdbc.update("INSERT INTO blog_topics (name, is_other, sort_order, is_active) VALUES (?, FALSE, 70, TRUE)", name);
        long topicId = jdbc.queryForObject("SELECT id FROM blog_topics WHERE name = ?", Long.class, name);
        Map<String, List<String>> before = new java.util.LinkedHashMap<>(questSettings.getInterest().getBlogTopics());
        Map<String, List<String>> mapping = new java.util.LinkedHashMap<>(before);
        mapping.put(name, List.of("topic:" + mine));
        questSettings.getInterest().setBlogTopics(mapping);
        try {
            jdbc.update("INSERT INTO user_interest_topics (user_id, topic_id) VALUES (?, ?)", user, topicId);

            JsonNode plain = data(forceToday(user, "TOPIC").andExpect(status().isOk()));
            assertThat(quest(plain, "TOPIC").path("action").path("group").asText()).as("the most to learn")
                    .isEqualTo(other);
            assertThat(quest(plain, "TOPIC").get("reason").isNull()).isTrue();
            assertThat(plain.path("interests").path(0).path("name").asText()).as("interests still listed")
                    .isEqualTo(name);

            JsonNode following = data(call(HttpMethod.PUT, "/api/dev/quests/today", user, "USER",
                    "{\"codes\":[\"TOPIC\"],\"followInterests\":true}").andExpect(status().isOk()));
            assertThat(quest(following, "TOPIC").path("action").path("group").asText()).isEqualTo(mine);
            assertThat(quest(following, "TOPIC").path("reason").path("code").asText()).isEqualTo("INTEREST");

            for (String value : List.of("false", "\"true\"", "1", "null")) {
                JsonNode other2 = data(call(HttpMethod.PUT, "/api/dev/quests/today", user, "USER",
                        "{\"codes\":[\"TOPIC\"],\"followInterests\":" + value + "}").andExpect(status().isOk()));
                assertThat(quest(other2, "TOPIC").path("action").path("group").asText()).as(value).isEqualTo(other);
            }
        } finally {
            questSettings.getInterest().setBlogTopics(before);
            jdbc.update("DELETE FROM user_interest_topics WHERE topic_id = ?", topicId);
            jdbc.update("DELETE FROM blog_topics WHERE id = ?", topicId);
        }
    }

    @Test
    void forcingReplacesTodaysSetAndTheXuItPaid() throws Exception {
        freshDay();
        isolateLibrary();
        long only = phrases(1).get(0);
        long user = member();
        JsonNode first = data(forceToday(user, "FLASH_REVIEW").andExpect(status().isOk()));
        answer(user, only, true);
        assertThat(coinsOf(user)).isEqualTo(30);

        JsonNode second = data(forceToday(user, "FLASH_REVIEW").andExpect(status().isOk()));

        assertThat(ids(second)).doesNotContainAnyElementsOf(ids(first));
        assertThat(quest(second, "FLASH_REVIEW").path("progress").asInt()).isZero();
        assertThat(second.path("bonus").path("completed").asBoolean()).isFalse();
        assertThat(second.path("coinsToday").asInt()).isZero();
        assertThat(second.path("wallet").path("coins").asInt()).isZero();
        assertThat(coinsOf(user)).isZero();
        assertThat(rows("SELECT COUNT(*) FROM user_quest_days WHERE user_id = ?", user)).isZero();
    }

    @Test
    void aCodeTheMemberCannotDoOrDoesNotExistIsRefusedAndChangesNothing() throws Exception {
        freshDay();
        isolateLibrary();
        List<Long> phrases = phrases(3);
        long user = member();
        JsonNode set = data(forceToday(user, "FLASH_REVIEW").andExpect(status().isOk()));
        answer(user, phrases.get(0), true);

        String impossible = "Nhiệm vụ REVIEW_LEARNING không làm được với dữ liệu hiện tại.";
        forceToday(user, "FLASH_REVIEW", "REVIEW_LEARNING").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(impossible))
                .andExpect(jsonPath("$.data.codes").value(impossible));
        String unknown = "Mã nhiệm vụ không hợp lệ: QUIZ.";
        forceToday(user, "FLASH_REVIEW", "QUIZ").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(unknown))
                .andExpect(jsonPath("$.data.codes").value(unknown));
        String none = "Hãy chọn ít nhất một mã nhiệm vụ.";
        for (String body : List.of("{\"codes\":[]}", "{}", "{\"codes\":\"TOPIC\"}", "{\"codes\":null}")) {
            call(HttpMethod.PUT, "/api/dev/quests/today", user, "USER", body).andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.data.codes").value(none));
        }

        // The refused calls cleared nothing: same quests, same progress, same xu
        JsonNode now = today(user);
        assertThat(ids(now)).isEqualTo(ids(set));
        assertThat(quest(now, "FLASH_REVIEW").path("progress").asInt()).isEqualTo(1);
        assertThat(coinsOf(user)).isEqualTo(set.path("wallet").path("coins").asInt());
    }

    // ---------------------------------------------------------------- who may call it

    @Test
    void guestsGet401AndStaffGetMsg18() throws Exception {
        freshDay();
        long moderator = user("MODERATOR", "ACTIVE");
        long academic = user("ACADEMIC_MANAGER", "ACTIVE");
        record Call(HttpMethod method, String url, String body) {
        }
        List<Call> calls = List.of(
                new Call(HttpMethod.DELETE, "/api/dev/quests/today", null),
                new Call(HttpMethod.POST, "/api/dev/quests/history", "{\"doneDays\":6}"),
                new Call(HttpMethod.PUT, "/api/dev/quests/today", "{\"codes\":[\"FLASH_REVIEW\"]}"),
                new Call(HttpMethod.PUT, "/api/dev/coins", "{\"coins\":600}"));
        for (Call c : calls) {
            call(c.method(), c.url(), null, null, c.body()).andExpect(status().isUnauthorized());
            call(c.method(), c.url(), moderator, "MODERATOR", c.body()).andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.message").value(MSG18));
            call(c.method(), c.url(), academic, "ACADEMIC_MANAGER", c.body()).andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.message").value(MSG18));
        }
        assertThat(rows("SELECT COUNT(*) FROM coin_transactions WHERE user_id IN (?, ?)", moderator, academic)).isZero();
        assertThat(rows("SELECT COUNT(*) FROM user_quest_days WHERE user_id IN (?, ?)", moderator, academic)).isZero();
    }

    @Test
    void anAddressTheApiDoesNotHaveIsA404WhileItIsOn() throws Exception {
        freshDay();
        long user = member();
        call(HttpMethod.DELETE, "/api/dev/quests/nothing", user, "USER", null).andExpect(status().isNotFound());
        call(HttpMethod.POST, "/api/dev/other", user, "USER", "{}").andExpect(status().isNotFound());
    }
}
