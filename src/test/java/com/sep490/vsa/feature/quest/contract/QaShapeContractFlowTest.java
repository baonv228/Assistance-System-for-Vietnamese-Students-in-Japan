package com.sep490.vsa.feature.quest.contract;

import com.fasterxml.jackson.databind.JsonNode;
import org.assertj.core.api.SoftAssertions;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * JSON shapes of every new or extended endpoint against the examples of WEB_DAILY_QUESTS_PROMPT.md section 5: exact
 * key sets, JSON types, instants in ISO-8601 UTC ending in Z, dates yyyy-MM-dd.
 */
@TestPropertySource(properties = QaContractSupport.QA_DB)
class QaShapeContractFlowTest extends QaContractSupport {

    /** 5.1 for a day of FLASH_REVIEW + TOPIC "Thanh toán" + PHRASE_OF_DAY, just made. */
    @Test
    void todayOfATopicAndListenDayHasTheContractShape() throws Exception {
        LocalDate day = freshDay();
        long m = member();
        List<Long> lib = payLibrary(6, m);

        JsonNode t = today(m);
        long phraseOfDay = homePhraseId();

        SoftAssertions s = new SoftAssertions();
        exactKeys(s, "today", t, TODAY_KEYS);
        s.assertThat(t.get("interests") != null && t.get("interests").isArray()).as("today.interests is a list")
                .isTrue();
        s.assertThat(t.path("interests").size()).as("no interests: a Member who did nothing yet").isZero();
        for (JsonNode q : t.path("quests")) {
            String code = q.path("code").asText();
            if ("TOPIC".equals(code)) {
                // stage ARR → app.quests.stage-topics[ARR] starts with "pay": the topic comes from the stage
                exactKeys(s, "TOPIC.reason", q.get("reason"), "code", "label", "detail");
                s.assertThat(q.path("reason").path("code").asText()).isEqualTo("STAGE");
                s.assertThat(q.path("reason").path("label").asText()).isEqualTo("Hợp giai đoạn của bạn");
                isNullValue(s, "TOPIC.reason.detail", q.path("reason").get("detail"));
            } else {
                s.assertThat(q.has("reason") && q.get("reason").isNull()).as(code + ".reason null").isTrue();
            }
        }
        isDate(s, "today.date", t.get("date"));
        s.assertThat(t.path("date").asText()).as("date").isEqualTo(day.toString());
        isInstant(s, "today.resetAt", t.get("resetAt"));
        s.assertThat(instant(t.get("resetAt"))).as("resetAt = next 00:00 JST")
                .isEqualTo(japanMidnight(day.plusDays(1)));
        s.assertThat(t.path("quests").size()).as("3 quests").isEqualTo(3);
        s.assertThat(t.path("quests").path(0).path("code").asText()).as("fixed quest first").isEqualTo("FLASH_REVIEW");
        s.assertThat(codes(t.path("quests"))).as("codes")
                .containsExactlyInAnyOrder("FLASH_REVIEW", "TOPIC", "PHRASE_OF_DAY");

        int i = 0;
        for (JsonNode q : t.path("quests")) {
            String p = "quests[" + i++ + "]";
            exactKeys(s, p, q, QUEST_KEYS);
            isInt(s, p + ".id", q.get("id"));
            isText(s, p + ".code", q.get("code"));
            isText(s, p + ".title", q.get("title"));
            isText(s, p + ".description", q.get("description"));
            isInt(s, p + ".target", q.get("target"));
            isInt(s, p + ".progress", q.get("progress"));
            isInt(s, p + ".coins", q.get("coins"));
            isBool(s, p + ".completed", q.get("completed"));
            isNullValue(s, p + ".completedAt", q.get("completedAt"));
            exactKeys(s, p + ".action", q.get("action"), ACTION_KEYS);
            for (String f : ACTION_KEYS) {
                isText(s, p + ".action." + f, q.path("action").get(f));
            }
            s.assertThat(q.path("progress").asInt()).as(p + ".progress").isZero();
            s.assertThat(q.path("coins").asInt()).as(p + ".coins (will receive)").isEqualTo(10);
            s.assertThat(q.path("completed").asBoolean()).as(p + ".completed").isFalse();
            Long rowId = jdbc.queryForObject(
                    "SELECT id FROM user_daily_quests WHERE user_id = ? AND quest_date = ? AND code = ?", Long.class,
                    m, day.toString(), q.path("code").asText());
            s.assertThat(q.path("id").asLong()).as(p + ".id = user_daily_quests.id").isEqualTo(rowId);
        }

        JsonNode flash = requireQuest(t, "FLASH_REVIEW");
        s.assertThat(flash.path("target").asInt()).as("FLASH target = min(10, 6 entries)").isEqualTo(6);
        s.assertThat(flash.path("title").asText()).isEqualTo("Ôn 6 thẻ ghi nhớ");
        s.assertThat(flash.path("description").asText())
                .isEqualTo("Trả lời 6 thẻ khác nhau, Chưa thuộc hay Đã thuộc đều được.");
        s.assertThat(actionOf(flash)).as("FLASH action")
                .isEqualTo(Map.of("type", "PRACTICE", "by", "", "group", "", "groupName", "", "kind", "", "status", ""));
        isNullValue(s, "FLASH.phrase", flash.get("phrase"));

        JsonNode topic = requireQuest(t, "TOPIC");
        s.assertThat(topic.path("target").asInt()).isEqualTo(5);
        s.assertThat(topic.path("title").asText()).isEqualTo("Ôn 5 thẻ chủ đề “Thanh toán”");
        s.assertThat(topic.path("description").asText()).isEqualTo("Trả lời 5 thẻ khác nhau của chủ đề Thanh toán.");
        s.assertThat(actionOf(topic)).as("TOPIC action").isEqualTo(Map.of("type", "PRACTICE", "by", "topic",
                "group", "pay", "groupName", "Thanh toán", "kind", "", "status", ""));
        isNullValue(s, "TOPIC.phrase", topic.get("phrase"));

        JsonNode pod = requireQuest(t, "PHRASE_OF_DAY");
        s.assertThat(pod.path("target").asInt()).isEqualTo(1);
        s.assertThat(pod.path("title").asText()).isEqualTo("Nghe câu tiếng Nhật hôm nay");
        s.assertThat(pod.path("description").asText()).isEqualTo("Bấm Nghe để nghe câu hôm nay một lần.");
        s.assertThat(actionOf(pod)).as("PHRASE_OF_DAY action")
                .isEqualTo(Map.of("type", "LISTEN", "by", "", "group", "", "groupName", "", "kind", "", "status", ""));
        JsonNode phrase = pod.get("phrase");
        exactKeys(s, "PHRASE_OF_DAY.phrase", phrase, "id", "japanese", "romaji", "meaningVi");
        isInt(s, "phrase.id", phrase.get("id"));
        s.assertThat(phrase.path("id").asLong()).as("phrase.id = Home phrase.id").isEqualTo(phraseOfDay);
        s.assertThat(lib).as("phrase of the day is in the visible library").contains(phrase.path("id").asLong());
        Map<String, Object> row = jdbc.queryForMap("SELECT japanese, romaji, meaning_vi FROM phrases WHERE id = ?",
                phrase.path("id").asLong());
        s.assertThat(phrase.path("japanese").asText()).isEqualTo(row.get("japanese"));
        s.assertThat(phrase.path("romaji").asText()).isEqualTo(row.get("romaji"));
        s.assertThat(phrase.path("meaningVi").asText()).isEqualTo(row.get("meaning_vi"));

        exactKeys(s, "bonus", t.get("bonus"), "coins", "completed");
        isInt(s, "bonus.coins", t.path("bonus").get("coins"));
        isBool(s, "bonus.completed", t.path("bonus").get("completed"));
        s.assertThat(t.path("bonus").path("coins").asInt()).isEqualTo(20);
        s.assertThat(t.path("bonus").path("completed").asBoolean()).isFalse();

        exactKeys(s, "streak", t.get("streak"), "current", "best", "todayDone", "every", "milestoneCoins");
        for (String f : new String[]{"current", "best", "every", "milestoneCoins"}) {
            isInt(s, "streak." + f, t.path("streak").get(f));
        }
        isBool(s, "streak.todayDone", t.path("streak").get("todayDone"));
        s.assertThat(t.path("streak").path("current").asInt()).isZero();
        s.assertThat(t.path("streak").path("best").asInt()).isZero();
        s.assertThat(t.path("streak").path("every").asInt()).isEqualTo(7);
        s.assertThat(t.path("streak").path("milestoneCoins").asInt()).isEqualTo(50);

        JsonNode days = t.path("recentDays");
        s.assertThat(days.size()).as("recentDays has exactly 7").isEqualTo(7);
        for (int d = 0; d < days.size(); d++) {
            exactKeys(s, "recentDays[" + d + "]", days.get(d), "date", "done");
            isDate(s, "recentDays[" + d + "].date", days.get(d).get("date"));
            isBool(s, "recentDays[" + d + "].done", days.get(d).get("done"));
            s.assertThat(days.get(d).path("date").asText()).as("recentDays[" + d + "] oldest first")
                    .isEqualTo(day.minusDays(6 - d).toString());
            s.assertThat(days.get(d).path("done").asBoolean()).isFalse();
        }

        isInt(s, "coinsToday", t.get("coinsToday"));
        isInt(s, "dailyCap", t.get("dailyCap"));
        s.assertThat(t.path("coinsToday").asInt()).isZero();
        s.assertThat(t.path("dailyCap").asInt()).isEqualTo(50);

        JsonNode w = t.get("wallet");
        walletShape(s, "wallet", w);
        s.assertThat(w.path("coins").asInt()).isZero();
        s.assertThat(w.path("rate").asInt()).isEqualTo(50);
        s.assertThat(w.path("monthCap").asInt()).isEqualTo(10);
        s.assertThat(w.path("exchangedThisMonth").asInt()).isZero();
        s.assertThat(w.path("exchangeableNow").asInt()).isZero();
        s.assertThat(instant(w.get("monthResetAt"))).as("monthResetAt = 00:00 JST on the 1st of next month")
                .isEqualTo(japanMidnight(day.withDayOfMonth(1).plusMonths(1)));
        s.assertThat(w.path("points").asInt()).isZero();
        s.assertThat(w.path("rankName").asText()).isEqualTo("Người mới");
        s.assertAll();
    }

    /** 5.1 for a day of FLASH_REVIEW + LEARN_NEW + REVIEW_LEARNING. */
    @Test
    void todayOfALearnAndReviewDayHasTheContractActions() throws Exception {
        freshDay();
        long m = member();
        vocabLibrary(5, 4, 1, m);

        JsonNode t = today(m);
        SoftAssertions s = new SoftAssertions();
        s.assertThat(codes(t.path("quests"))).as("codes")
                .containsExactlyInAnyOrder("FLASH_REVIEW", "LEARN_NEW", "REVIEW_LEARNING");
        s.assertThat(t.path("quests").path(0).path("code").asText()).as("fixed quest first").isEqualTo("FLASH_REVIEW");
        JsonNode flash = requireQuest(t, "FLASH_REVIEW");
        s.assertThat(flash.path("target").asInt()).isEqualTo(10);
        s.assertThat(flash.path("title").asText()).isEqualTo("Ôn 10 thẻ ghi nhớ");

        JsonNode learn = requireQuest(t, "LEARN_NEW");
        s.assertThat(learn.path("target").asInt()).isEqualTo(3);
        s.assertThat(learn.path("title").asText()).isEqualTo("Thuộc 3 mục mới");
        s.assertThat(learn.path("description").asText()).isEqualTo("Chọn “Đã thuộc” cho 3 mục bạn chưa thuộc.");
        s.assertThat(actionOf(learn)).as("LEARN_NEW action, 4 entries never practised ≥ 3").isEqualTo(Map.of(
                "type", "PRACTICE", "by", "", "group", "", "groupName", "", "kind", "", "status", "new"));
        isNullValue(s, "LEARN_NEW.phrase", learn.get("phrase"));

        JsonNode review = requireQuest(t, "REVIEW_LEARNING");
        s.assertThat(review.path("target").asInt()).isEqualTo(5);
        s.assertThat(review.path("title").asText()).isEqualTo("Luyện lại 5 thẻ “Chưa thuộc”");
        s.assertThat(review.path("description").asText()).isEqualTo("Luyện lại 5 thẻ đang ở trạng thái Chưa thuộc.");
        s.assertThat(actionOf(review)).as("REVIEW_LEARNING action").isEqualTo(Map.of(
                "type", "PRACTICE", "by", "", "group", "", "groupName", "", "kind", "", "status", "learning"));
        isNullValue(s, "REVIEW_LEARNING.phrase", review.get("phrase"));
        s.assertAll();
    }

    /** 3.3: LEARN_NEW asks for "learning" when fewer entries than its target were never practised. */
    @Test
    void learnNewActionAsksForLearningWhenTooFewEntriesAreUnpractised() throws Exception {
        freshDay();
        long m = member();
        vocabLibrary(6, 2, 0, m);

        JsonNode learn = requireQuest(today(m), "LEARN_NEW");
        assertThat(actionOf(learn)).isEqualTo(Map.of(
                "type", "PRACTICE", "by", "", "group", "", "groupName", "", "kind", "", "status", "learning"));
    }

    /** 5.2: the flash answer keeps {learning, learned} and adds the QuestChange "quests". */
    @Test
    void flashAnswerHasTheContractShape() throws Exception {
        freshDay();
        long m = member();
        List<Long> lib = payLibrary(5, m);
        today(m);

        clock.advance(GAP);
        JsonNode first = json(flash(m, lib.get(0), true));
        SoftAssertions s = new SoftAssertions();
        exactKeys(s, "response", first, "success", "message", "data", "timestamp");
        s.assertThat(first.path("message").asText()).startsWith("Đã lưu: Đã thuộc.");
        JsonNode data = first.get("data");
        exactKeys(s, "data", data, "learning", "learned", "quests");
        isBool(s, "data.learning", data.get("learning"));
        isBool(s, "data.learned", data.get("learned"));
        s.assertThat(data.path("learning").asBoolean()).isFalse();
        s.assertThat(data.path("learned").asBoolean()).isTrue();
        JsonNode qc = data.get("quests");
        questChangeShape(s, "data.quests", qc);
        s.assertThat(changedCodes(qc)).containsExactlyInAnyOrder("FLASH_REVIEW", "TOPIC");
        s.assertThat(qc.path("justCompleted").size()).isZero();
        s.assertThat(qc.path("bonusJustCompleted").asBoolean()).isFalse();
        s.assertThat(qc.path("bonusCoins").asInt()).isZero();
        isNullValue(s, "data.quests.streakMilestone", qc.get("streakMilestone"));
        s.assertThat(qc.path("coinsAwarded").asInt()).isZero();
        s.assertThat(qc.path("coinsToday").asInt()).isZero();
        s.assertThat(qc.path("dailyCap").asInt()).isEqualTo(50);
        s.assertThat(qc.path("streak").path("current").asInt()).isZero();
        s.assertThat(qc.path("streak").path("todayDone").asBoolean()).isFalse();

        JsonNode last = null;
        for (int i = 1; i < 5; i++) {
            clock.advance(GAP);
            last = json(flash(m, lib.get(i), false));
        }
        s.assertThat(last.path("message").asText()).startsWith("Đã lưu: Chưa thuộc.");
        JsonNode done = last.path("data").get("quests");
        questChangeShape(s, "completing.quests", done);
        s.assertThat(codes(done.path("justCompleted"))).containsExactlyInAnyOrder("FLASH_REVIEW", "TOPIC");
        for (JsonNode j : done.path("justCompleted")) {
            s.assertThat(j.path("coins").asInt()).as("justCompleted coins").isEqualTo(10);
        }
        s.assertThat(changed(done, "TOPIC").path("completed").asBoolean()).isTrue();
        s.assertThat(changed(done, "TOPIC").path("progress").asInt()).isEqualTo(5);
        s.assertThat(changed(done, "TOPIC").path("target").asInt()).isEqualTo(5);
        s.assertThat(done.path("coinsAwarded").asInt()).isEqualTo(20);
        s.assertThat(done.path("coinsToday").asInt()).isEqualTo(20);
        s.assertThat(done.path("wallet").path("coins").asInt()).isEqualTo(20);
        s.assertAll();
    }

    /** 5.3: an event answers a QuestChange. */
    @Test
    void listenEventHasTheContractShape() throws Exception {
        freshDay();
        long m = member();
        payLibrary(5, m);
        JsonNode pod = requireQuest(today(m), "PHRASE_OF_DAY");

        JsonNode res = json(listenRaw(m, pod.path("phrase").path("id").asLong()));
        SoftAssertions s = new SoftAssertions();
        exactKeys(s, "response", res, "success", "message", "data", "timestamp");
        JsonNode qc = res.get("data");
        questChangeShape(s, "data", qc);
        s.assertThat(codes(qc.path("changed"))).containsExactly("PHRASE_OF_DAY");
        JsonNode c = changed(qc, "PHRASE_OF_DAY");
        s.assertThat(c.path("id").asLong()).isEqualTo(pod.path("id").asLong());
        s.assertThat(c.path("progress").asInt()).isEqualTo(1);
        s.assertThat(c.path("target").asInt()).isEqualTo(1);
        s.assertThat(c.path("completed").asBoolean()).isTrue();
        JsonNode j = qc.path("justCompleted").path(0);
        s.assertThat(j.path("id").asLong()).isEqualTo(pod.path("id").asLong());
        s.assertThat(j.path("code").asText()).isEqualTo("PHRASE_OF_DAY");
        s.assertThat(j.path("title").asText()).isEqualTo("Nghe câu tiếng Nhật hôm nay");
        s.assertThat(j.path("coins").asInt()).isEqualTo(10);
        s.assertThat(qc.path("coinsAwarded").asInt()).isEqualTo(10);
        s.assertThat(qc.path("coinsToday").asInt()).isEqualTo(10);
        s.assertThat(qc.path("wallet").path("coins").asInt()).isEqualTo(10);
        s.assertThat(qc.path("wallet").path("exchangeableNow").asInt()).isZero();
        s.assertAll();
    }

    /**
     * 5.4 / 5.5 / 5.7: the contract's own example (350 xu, 98 points, 2 points exchanged this month, then "Đổi" 3)
     * and the history lines it makes.
     */
    @Test
    void exchangeAndHistoriesHaveTheContractShape() throws Exception {
        LocalDate day = freshDay();
        long m = member();
        seedCoins(m, 450);
        jdbc.update("UPDATE users SET points = 96 WHERE id = ?", m);
        clock.advance(GAP);
        ok(exchangeRaw(m, "2"));
        clock.advance(GAP);

        JsonNode res = json(exchangeRaw(m, "3"));
        SoftAssertions s = new SoftAssertions();
        exactKeys(s, "response", res, "success", "message", "data", "timestamp");
        s.assertThat(res.path("success").asBoolean()).isTrue();
        s.assertThat(res.path("message").asText()).isEqualTo("Đã đổi 150 xu lấy 3 điểm.");
        JsonNode d = res.get("data");
        exactKeys(s, "data", d, "pointsAdded", "coinsSpent", "rankBefore", "rankAfter", "wallet");
        isInt(s, "pointsAdded", d.get("pointsAdded"));
        isInt(s, "coinsSpent", d.get("coinsSpent"));
        isText(s, "rankBefore", d.get("rankBefore"));
        isText(s, "rankAfter", d.get("rankAfter"));
        s.assertThat(d.path("pointsAdded").asInt()).isEqualTo(3);
        s.assertThat(d.path("coinsSpent").asInt()).isEqualTo(150);
        s.assertThat(d.path("rankBefore").asText()).isEqualTo("Người mới");
        s.assertThat(d.path("rankAfter").asText()).isEqualTo("Người chỉ đường");
        JsonNode w = d.get("wallet");
        walletShape(s, "data.wallet", w);
        s.assertThat(w.path("coins").asInt()).isEqualTo(200);
        s.assertThat(w.path("rate").asInt()).isEqualTo(50);
        s.assertThat(w.path("monthCap").asInt()).isEqualTo(10);
        s.assertThat(w.path("exchangedThisMonth").asInt()).isEqualTo(5);
        s.assertThat(w.path("exchangeableNow").asInt()).isEqualTo(4);
        s.assertThat(instant(w.get("monthResetAt"))).isEqualTo(japanMidnight(day.withDayOfMonth(1).plusMonths(1)));
        s.assertThat(w.path("points").asInt()).isEqualTo(101);
        s.assertThat(w.path("rankName").asText()).isEqualTo("Người chỉ đường");

        // 5.5
        JsonNode coins = ok(getAs(m, "/api/me/coins?page=0"));
        exactKeys(s, "coins", coins, "items", "page", "size", "total", "wallet");
        isInt(s, "coins.page", coins.get("page"));
        isInt(s, "coins.size", coins.get("size"));
        isInt(s, "coins.total", coins.get("total"));
        s.assertThat(coins.path("page").asInt()).isZero();
        s.assertThat(coins.path("size").asInt()).isEqualTo(20);
        s.assertThat(coins.path("total").asInt()).isEqualTo(3);
        walletShape(s, "coins.wallet", coins.get("wallet"));
        s.assertThat(coins.path("wallet")).isEqualTo(w);
        int i = 0;
        for (JsonNode line : coins.path("items")) {
            String p = "coins.items[" + i++ + "]";
            exactKeys(s, p, line, COIN_LINE_KEYS);
            isInt(s, p + ".id", line.get("id"));
            isInt(s, p + ".delta", line.get("delta"));
            isText(s, p + ".reason", line.get("reason"));
            isText(s, p + ".reasonLabel", line.get("reasonLabel"));
            isText(s, p + ".title", line.get("title"));
            isInstant(s, p + ".createdAt", line.get("createdAt"));
        }
        JsonNode newest = coins.path("items").path(0);
        s.assertThat(newest.path("delta").asInt()).isEqualTo(-150);
        s.assertThat(newest.path("reason").asText()).isEqualTo("EXCHANGE");
        s.assertThat(newest.path("reasonLabel").asText()).isEqualTo("Đổi sang điểm cấp bậc");
        s.assertThat(newest.path("title").asText()).isEqualTo("Đổi lấy 3 điểm");
        s.assertThat(newest.path("link").asText()).isEqualTo("/profile#points");
        s.assertThat(instant(newest.get("createdAt"))).as("EXCHANGE line createdAt = time of the exchange")
                .isEqualTo(clock.instant().truncatedTo(java.time.temporal.ChronoUnit.SECONDS));
        s.assertThat(coins.path("items").path(1).path("title").asText()).isEqualTo("Đổi lấy 2 điểm");
        s.assertThat(coins.path("items").path(1).path("delta").asInt()).isEqualTo(-100);

        // 5.7
        JsonNode points = ok(getAs(m, "/api/me/points"));
        exactKeys(s, "points", points, "items", "page", "size", "total", "points");
        JsonNode pl = points.path("items").path(0);
        exactKeys(s, "points.items[0]", pl, COIN_LINE_KEYS);
        isInt(s, "points.items[0].id", pl.get("id"));
        s.assertThat(pl.path("delta").asInt()).isEqualTo(3);
        s.assertThat(pl.path("reason").asText()).isEqualTo("COIN_EXCHANGE");
        s.assertThat(pl.path("reasonLabel").asText()).isEqualTo("Đổi xu nhiệm vụ");
        s.assertThat(pl.path("title").asText()).isEqualTo("Đổi 150 xu");
        s.assertThat(pl.path("link").asText()).isEqualTo("/quests");
        isInstant(s, "points.items[0].createdAt", pl.get("createdAt"));
        s.assertThat(points.path("items").path(1).path("title").asText()).isEqualTo("Đổi 100 xu");
        s.assertThat(points.path("points").asInt()).isEqualTo(101);
        s.assertAll();
    }

    /** 5.6: the Home's "quests" block and phrase.id; the profile's coins. */
    @Test
    void homeAndProfileHaveTheContractFields() throws Exception {
        freshDay();
        long m = member();
        payLibrary(5, m);
        seedCoins(m, 340);

        long guestPhrase = homePhraseId();
        JsonNode home = ok(getAs(m, "/api/me/home"));
        SoftAssertions s = new SoftAssertions();
        JsonNode hq = home.get("quests");
        exactKeys(s, "home.quests", hq, HOME_QUEST_KEYS);
        for (String f : new String[]{"done", "total", "streak", "coinsToday", "dailyCap", "coins", "exchangeableNow"}) {
            isInt(s, "home.quests." + f, hq.get(f));
        }
        for (String f : new String[]{"generated", "bonusDone", "todayDone"}) {
            isBool(s, "home.quests." + f, hq.get(f));
        }
        s.assertThat(hq.path("generated").asBoolean()).isFalse();
        s.assertThat(hq.path("done").asInt()).isZero();
        s.assertThat(hq.path("total").asInt()).isEqualTo(3);
        s.assertThat(hq.path("bonusDone").asBoolean()).isFalse();
        s.assertThat(hq.path("streak").asInt()).isZero();
        s.assertThat(hq.path("todayDone").asBoolean()).isFalse();
        s.assertThat(hq.path("coinsToday").asInt()).isZero();
        s.assertThat(hq.path("dailyCap").asInt()).isEqualTo(50);
        s.assertThat(hq.path("coins").asInt()).isEqualTo(340);
        s.assertThat(hq.path("exchangeableNow").asInt()).isEqualTo(6);

        JsonNode phrase = home.get("phrase");
        exactKeys(s, "home.phrase", phrase, "id", "japanese", "romaji", "meaningVi", "categoryId", "categoryName",
                "categoryIcon", "topicId");
        isInt(s, "home.phrase.id", phrase.get("id"));
        s.assertThat(phrase.path("id").asLong()).as("member and guest Home: same phrase.id").isEqualTo(guestPhrase);
        JsonNode guest = ok(guestGet("/api/home")).get("phrase");
        exactKeys(s, "guest.phrase", guest, "id", "japanese", "romaji", "meaningVi", "categoryId", "categoryName",
                "categoryIcon", "topicId");

        JsonNode t = today(m);
        s.assertThat(requireQuest(t, "PHRASE_OF_DAY").path("phrase").path("id").asLong())
                .as("PHRASE_OF_DAY phrase.id = Home phrase.id").isEqualTo(guestPhrase);
        JsonNode after = ok(getAs(m, "/api/me/home")).get("quests");
        s.assertThat(after.path("generated").asBoolean()).isTrue();
        s.assertThat(after.path("total").asInt()).isEqualTo(3);
        s.assertThat(after.path("done").asInt()).isZero();
        s.assertThat(ok(getAs(m, "/api/me/home")).path("rankName").asText())
                .as("wallet.rankName computed like Home rankName").isEqualTo(t.path("wallet").path("rankName").asText());

        JsonNode profile = ok(getAs(m, "/api/me/profile"));
        isInt(s, "profile.coins", profile.get("coins"));
        s.assertThat(profile.path("coins").asInt()).isEqualTo(340);
        s.assertThat(t.path("wallet").path("points").asInt()).as("wallet.points = profile.points")
                .isEqualTo(profile.path("points").asInt());
        s.assertAll();
    }

    private static Map<String, String> actionOf(JsonNode quest) {
        JsonNode a = quest.path("action");
        return Map.of("type", a.path("type").asText("<missing>"), "by", a.path("by").asText("<missing>"),
                "group", a.path("group").asText("<missing>"), "groupName", a.path("groupName").asText("<missing>"),
                "kind", a.path("kind").asText("<missing>"), "status", a.path("status").asText("<missing>"));
    }
}
