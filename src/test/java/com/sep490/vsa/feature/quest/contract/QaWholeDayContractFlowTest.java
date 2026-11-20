package com.sep490.vsa.feature.quest.contract;

import com.fasterxml.jackson.databind.JsonNode;
import org.assertj.core.api.SoftAssertions;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

/**
 * A whole day as the mobile app lives it: open the quests, practise with each quest's own filters, listen to the
 * phrase of the day, get the bonus, read the Home and the xu history, then "Đổi" xu into a rank point.
 */
@TestPropertySource(properties = QaContractSupport.QA_DB)
class QaWholeDayContractFlowTest extends QaContractSupport {

    @Test
    void aTopicAndListenDayFromOpeningToExchange() throws Exception {
        LocalDate day = qaDay();
        long m = member();
        payLibrary(6, m);
        jdbc.update("UPDATE users SET points = 99 WHERE id = ?", m);

        JsonNode opened = today(m);
        List<JsonNode> changes = playTheDay(m, opened);

        SoftAssertions s = new SoftAssertions();
        int awarded = changes.stream().mapToInt(c -> c.path("coinsAwarded").asInt()).sum();
        s.assertThat(awarded).as("xu awarded over the day: 3 × 10 + 20").isEqualTo(50);
        JsonNode last = changes.get(changes.size() - 1);
        s.assertThat(last.path("bonusJustCompleted").asBoolean()).as("last change completes the day").isTrue();
        s.assertThat(last.path("bonusCoins").asInt()).isEqualTo(20);
        s.assertThat(last.path("streak").path("current").asInt()).isEqualTo(1);
        s.assertThat(last.path("streak").path("todayDone").asBoolean()).isTrue();
        s.assertThat(last.path("streakMilestone").isNull()).isTrue();
        s.assertThat(changes.stream().filter(c -> c.path("bonusJustCompleted").asBoolean()).count())
                .as("bonus completed once").isEqualTo(1);

        JsonNode t = today(m);
        Map<String, JsonNode> byTitle = new HashMap<>();
        for (JsonNode q : t.path("quests")) {
            s.assertThat(q.path("completed").asBoolean()).as(q.path("code") + " completed").isTrue();
            s.assertThat(q.path("progress").asInt()).as(q.path("code") + " progress = target")
                    .isEqualTo(q.path("target").asInt());
            s.assertThat(q.path("coins").asInt()).as(q.path("code") + " coins received").isEqualTo(10);
            isInstant(s, q.path("code") + ".completedAt", q.get("completedAt"));
            byTitle.put(q.path("title").asText(), q);
        }
        s.assertThat(t.path("bonus").path("completed").asBoolean()).isTrue();
        s.assertThat(t.path("streak").path("current").asInt()).isEqualTo(1);
        s.assertThat(t.path("streak").path("best").asInt()).isEqualTo(1);
        s.assertThat(t.path("streak").path("todayDone").asBoolean()).isTrue();
        JsonNode days = t.path("recentDays");
        s.assertThat(days.get(6).path("date").asText()).isEqualTo(day.toString());
        s.assertThat(days.get(6).path("done").asBoolean()).as("today done in recentDays").isTrue();
        for (int i = 0; i < 6; i++) {
            s.assertThat(days.get(i).path("done").asBoolean()).as("recentDays[" + i + "]").isFalse();
        }
        s.assertThat(t.path("coinsToday").asInt()).isEqualTo(50);
        JsonNode w = t.path("wallet");
        s.assertThat(w.path("coins").asInt()).isEqualTo(50);
        s.assertThat(w.path("exchangeableNow").asInt()).isEqualTo(1);
        s.assertThat(w.path("points").asInt()).as("xu never add rank points").isEqualTo(99);
        s.assertThat(w.path("rankName").asText()).isEqualTo("Người mới");
        s.assertThat(pointsOf(m)).isEqualTo(99);
        s.assertThat(count("SELECT COUNT(*) FROM point_transactions WHERE user_id = ?", m))
                .as("no point_transactions for xu").isZero();
        s.assertThat(coinsOf(m)).isEqualTo(50);

        JsonNode home = ok(getAs(m, "/api/me/home"));
        JsonNode hq = home.path("quests");
        s.assertThat(hq.path("generated").asBoolean()).isTrue();
        s.assertThat(hq.path("done").asInt()).isEqualTo(3);
        s.assertThat(hq.path("total").asInt()).isEqualTo(3);
        s.assertThat(hq.path("bonusDone").asBoolean()).isTrue();
        s.assertThat(hq.path("streak").asInt()).isEqualTo(1);
        s.assertThat(hq.path("todayDone").asBoolean()).isTrue();
        s.assertThat(hq.path("coinsToday").asInt()).isEqualTo(50);
        s.assertThat(hq.path("dailyCap").asInt()).isEqualTo(50);
        s.assertThat(hq.path("coins").asInt()).isEqualTo(50);
        s.assertThat(hq.path("exchangeableNow").asInt()).isEqualTo(1);
        s.assertThat(home.path("points").asInt()).isEqualTo(99);
        s.assertThat(home.path("rankName").asText()).isEqualTo("Người mới");

        JsonNode coins = ok(getAs(m, "/api/me/coins"));
        s.assertThat(coins.path("total").asInt()).isEqualTo(4);
        List<String> reasons = new ArrayList<>();
        Instant previous = null;
        for (JsonNode line : coins.path("items")) {
            reasons.add(line.path("reason").asText());
            s.assertThat(line.path("link").asText()).as("link of " + line).isEqualTo("/quests");
            Instant at = instant(line.get("createdAt"));
            if (previous != null) {
                s.assertThat(at).as("newest first").isBeforeOrEqualTo(previous);
            }
            previous = at;
            if ("DAILY_QUEST".equals(line.path("reason").asText())) {
                s.assertThat(line.path("reasonLabel").asText()).isEqualTo("Nhiệm vụ hằng ngày");
                s.assertThat(line.path("delta").asInt()).isEqualTo(10);
                JsonNode q = byTitle.get(line.path("title").asText());
                s.assertThat(q).as("DAILY_QUEST title is a quest title: " + line.path("title")).isNotNull();
                if (q != null) {
                    s.assertThat(at).as("DAILY_QUEST createdAt = quest completedAt")
                            .isEqualTo(instant(q.get("completedAt")));
                }
            } else {
                s.assertThat(line.path("reasonLabel").asText()).isEqualTo("Xong mọi nhiệm vụ trong ngày");
                s.assertThat(line.path("title").asText()).isEqualTo("Nhiệm vụ ngày " + ddMM(day));
                s.assertThat(line.path("delta").asInt()).isEqualTo(20);
            }
        }
        s.assertThat(reasons).containsExactlyInAnyOrder("DAILY_QUEST", "DAILY_QUEST", "DAILY_QUEST",
                "DAILY_QUEST_ALL");
        s.assertAll();

        clock.advance(GAP);
        JsonNode ex = json(exchangeRaw(m, "1"));
        SoftAssertions e = new SoftAssertions();
        e.assertThat(ex.path("success").asBoolean()).as("exchange: " + ex).isTrue();
        e.assertThat(ex.path("message").asText()).isEqualTo("Đã đổi 50 xu lấy 1 điểm.");
        JsonNode d = ex.path("data");
        e.assertThat(d.path("pointsAdded").asInt()).isEqualTo(1);
        e.assertThat(d.path("coinsSpent").asInt()).isEqualTo(50);
        e.assertThat(d.path("rankBefore").asText()).isEqualTo("Người mới");
        e.assertThat(d.path("rankAfter").asText()).isEqualTo("Người chỉ đường");
        e.assertThat(d.path("wallet").path("coins").asInt()).isZero();
        e.assertThat(d.path("wallet").path("points").asInt()).isEqualTo(100);
        e.assertThat(d.path("wallet").path("exchangedThisMonth").asInt()).isEqualTo(1);
        e.assertThat(d.path("wallet").path("exchangeableNow").asInt()).isZero();
        e.assertThat(d.path("wallet").path("rankName").asText()).isEqualTo("Người chỉ đường");

        JsonNode points = ok(getAs(m, "/api/me/points"));
        JsonNode pl = points.path("items").path(0);
        e.assertThat(points.path("points").asInt()).isEqualTo(100);
        e.assertThat(pl.path("reason").asText()).isEqualTo("COIN_EXCHANGE");
        e.assertThat(pl.path("delta").asInt()).isEqualTo(1);
        e.assertThat(pl.path("reasonLabel").asText()).isEqualTo("Đổi xu nhiệm vụ");
        e.assertThat(pl.path("title").asText()).isEqualTo("Đổi 50 xu");
        e.assertThat(pl.path("link").asText()).isEqualTo("/quests");

        JsonNode profile = ok(getAs(m, "/api/me/profile"));
        e.assertThat(profile.path("coins").asInt()).isZero();
        e.assertThat(profile.path("points").asInt()).isEqualTo(100);
        e.assertThat(ok(getAs(m, "/api/me/home")).path("rankName").asText()).isEqualTo("Người chỉ đường");

        JsonNode after = ok(getAs(m, "/api/me/coins"));
        e.assertThat(after.path("total").asInt()).isEqualTo(5);
        JsonNode exLine = after.path("items").path(0);
        e.assertThat(exLine.path("reason").asText()).isEqualTo("EXCHANGE");
        e.assertThat(exLine.path("delta").asInt()).isEqualTo(-50);
        e.assertThat(exLine.path("reasonLabel").asText()).isEqualTo("Đổi sang điểm cấp bậc");
        e.assertThat(exLine.path("title").asText()).isEqualTo("Đổi lấy 1 điểm");
        e.assertThat(exLine.path("link").asText()).isEqualTo("/profile#points");
        e.assertThat(count("""
                SELECT COUNT(*) FROM point_transactions p JOIN coin_transactions c ON c.id = p.ref_id
                WHERE p.user_id = ? AND p.reason = 'COIN_EXCHANGE' AND p.ref_type = 'COIN' AND c.reason = 'EXCHANGE'""", m))
                .as("point line refers to the EXCHANGE xu line").isEqualTo(1);
        e.assertAll();
    }

    @Test
    void aLearnAndReviewDayFollowingEachQuestsFilters() throws Exception {
        qaDay();
        long m = member();
        vocabLibrary(5, 4, 1, m);

        JsonNode opened = today(m);
        assertThat(codes(opened.path("quests"))).containsExactlyInAnyOrder("FLASH_REVIEW", "LEARN_NEW",
                "REVIEW_LEARNING");
        // The rotating quests first (with their filters), the fixed one last.
        List<JsonNode> order = new ArrayList<>();
        order.add(requireQuest(opened, "LEARN_NEW"));
        order.add(requireQuest(opened, "REVIEW_LEARNING"));
        order.add(requireQuest(opened, "FLASH_REVIEW"));
        List<JsonNode> changes = new ArrayList<>();
        for (JsonNode q : order) {
            changes.addAll(practise(m, q, "LEARN_NEW".equals(q.path("code").asText())));
        }

        JsonNode t = today(m);
        SoftAssertions s = new SoftAssertions();
        for (JsonNode q : t.path("quests")) {
            s.assertThat(q.path("completed").asBoolean()).as(q.path("code") + " completed").isTrue();
        }
        s.assertThat(changes.stream().mapToInt(c -> c.path("coinsAwarded").asInt()).sum()).isEqualTo(50);
        s.assertThat(t.path("bonus").path("completed").asBoolean()).isTrue();
        s.assertThat(t.path("coinsToday").asInt()).isEqualTo(50);
        s.assertThat(coinsOf(m)).isEqualTo(50);
        s.assertThat(t.path("streak").path("todayDone").asBoolean()).isTrue();
        s.assertAll();
    }

    /** Plays every quest of the day in the order given, as the app: PRACTICE with its filters, LISTEN its phrase. */
    private List<JsonNode> playTheDay(long m, JsonNode opened) throws Exception {
        List<JsonNode> changes = new ArrayList<>();
        for (JsonNode q : opened.path("quests")) {
            String type = q.path("action").path("type").asText();
            if ("LISTEN".equals(type)) {
                clock.advance(GAP);
                changes.add(listen(m, q.path("phrase").path("id").asLong()));
            } else if ("PRACTICE".equals(type)) {
                changes.addAll(practise(m, q, true));
            } else {
                fail("Unknown action type " + type + " of " + q);
            }
        }
        return changes;
    }

    /** Answers the cards the quest's action selects until the quest is completed; fails when they run out. */
    private List<JsonNode> practise(long m, JsonNode quest, boolean learned) throws Exception {
        List<JsonNode> changes = new ArrayList<>();
        String code = quest.path("code").asText();
        if (requireQuest(today(m), code).path("completed").asBoolean()) {
            return changes;
        }
        List<Long> cards = cardsFor(m, quest.path("action"));
        for (long card : cards) {
            JsonNode qc = answer(m, card, learned).path("quests");
            changes.add(qc);
            for (JsonNode j : qc.path("justCompleted")) {
                if (code.equals(j.path("code").asText())) {
                    return changes;
                }
            }
        }
        fail("Quest " + code + " cannot be completed with the cards its action " + quest.path("action")
                + " selects: " + cards + "; now " + requireQuest(today(m), code));
        return changes;
    }
}
