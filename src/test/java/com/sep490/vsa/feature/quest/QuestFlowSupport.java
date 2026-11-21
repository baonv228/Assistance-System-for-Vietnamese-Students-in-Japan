package com.sep490.vsa.feature.quest;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sep490.vsa.common.security.AppUserPrincipal;
import com.sep490.vsa.feature.coin.CoinLedger;
import com.sep490.vsa.feature.phrase.PhraseOfDayService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.ResultActions;

import java.sql.Date;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.StreamSupport;

import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Helpers shared by the "Nhiệm vụ hằng ngày" flow tests on top of {@link QuestTestSupport}: today's quests through the
 * API, a day made from chosen codes (as the test-only API does), answers, events and rows of the quest tables.
 */
abstract class QuestFlowSupport extends QuestTestSupport {

    @Autowired
    protected ObjectMapper json;
    @Autowired
    protected QuestService quests;
    @Autowired
    protected CoinLedger ledger;
    @Autowired
    protected PhraseOfDayService phraseOfDay;

    protected static AppUserPrincipal principal(long id) {
        return new AppUserPrincipal(id, "u" + id + "@test.local", "Quest Test", "USER", "/");
    }

    /** The {@code data} of an answer. */
    protected JsonNode data(ResultActions result) throws Exception {
        return json.readTree(body(result)).path("data");
    }

    protected JsonNode whole(ResultActions result) throws Exception {
        return json.readTree(body(result));
    }

    /** GET /api/me/quests/today (200). */
    protected JsonNode today(long user) throws Exception {
        return data(getAs(user, "/api/me/quests/today").andExpect(status().isOk()));
    }

    /** Today's quests made from exactly these codes (targets lowered to the library), as the test-only API does. */
    protected JsonNode force(long user, String... codes) {
        return json.valueToTree(quests.forceToday(principal(user), List.of(codes)));
    }

    /** A flash card answer through the API (200), its {@code data}. */
    protected JsonNode answer(long user, long phrase, boolean learned) throws Exception {
        return data(flash(user, phrase, learned).andExpect(status().isOk()));
    }

    /** POST /api/me/quests/events {type: LISTEN, phraseId}. */
    protected ResultActions listen(long user, long phraseId) throws Exception {
        return postAs(user, "/api/me/quests/events", "{\"type\":\"LISTEN\",\"phraseId\":" + phraseId + "}");
    }

    /** Moves the clock past the minimum spacing of counted answers. */
    protected void next() {
        clock.advance(Duration.ofSeconds(2));
    }

    protected static JsonNode quest(JsonNode today, String code) {
        for (JsonNode q : today.path("quests")) {
            if (code.equals(q.path("code").asText())) {
                return q;
            }
        }
        return null;
    }

    protected static List<String> codes(JsonNode today) {
        return StreamSupport.stream(today.path("quests").spliterator(), false).map(q -> q.path("code").asText())
                .toList();
    }

    protected static List<Long> ids(JsonNode today) {
        return StreamSupport.stream(today.path("quests").spliterator(), false).map(q -> q.path("id").asLong())
                .toList();
    }

    /** Codes of {@code changed} in a QuestChange. */
    protected static List<String> changedCodes(JsonNode change) {
        List<String> codes = new ArrayList<>();
        change.path("changed").forEach(c -> codes.add(c.path("code").asText()));
        return codes;
    }

    /** A day done before (as the history of the test-only API): all_done_at set and this streak. */
    protected long doneDay(long user, LocalDate date, int streak) {
        jdbc.update("""
                INSERT INTO user_quest_days (user_id, quest_date, all_done_at, streak) VALUES (?, ?, ?, ?)""",
                user, Date.valueOf(date), Timestamp.from(date.atTime(20, 0).atZone(JAPAN).toInstant()), streak);
        return jdbc.queryForObject("SELECT id FROM user_quest_days WHERE user_id = ? AND quest_date = ?", Long.class,
                user, Date.valueOf(date));
    }

    /** Forgets the phrase of a (random, far) day, so it is chosen again from the library the test made. */
    protected void clearPhraseOfDay(LocalDate day) {
        jdbc.update("DELETE FROM daily_phrases WHERE phrase_date = ?", Date.valueOf(day));
    }

    protected int rows(String sql, Object... args) {
        Integer n = jdbc.queryForObject(sql, Integer.class, args);
        return n == null ? 0 : n;
    }

    protected int questRows(long user) {
        return rows("SELECT COUNT(*) FROM user_daily_quests WHERE user_id = ?", user);
    }
}
