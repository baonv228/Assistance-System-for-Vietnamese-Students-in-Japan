package com.sep490.vsa.feature.quest.dev;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sep490.vsa.feature.quest.QuestTestSupport;
import jakarta.servlet.http.Cookie;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.sql.Date;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Base of the flow tests of the test-only API with the API ON: their own database {@value #DB_URL} on this machine and
 * {@code app.quests.dev-api.enabled=true}, one Spring context for every class extending it. Requests of any method,
 * role and body, the four calls of the API, and the rows they touch.
 */
@TestPropertySource(properties = {
        "spring.datasource.url=" + DevApiTestSupport.DB_URL,
        "app.quests.dev-api.enabled=true"
})
abstract class DevApiTestSupport extends QuestTestSupport {

    /** The database of these tests (and of the API-off test): local, so the API may be on. */
    static final String DB_URL = "jdbc:mysql://localhost:3306/asvsj_questdev_test?createDatabaseIfNotExist=true"
            + "&useUnicode=true&characterEncoding=utf8&connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true";

    static final String MSG18 = "Bạn không có quyền thực hiện thao tác này.";

    @Autowired
    protected ObjectMapper json;

    // ---------------------------------------------------------------- requests

    /** A request of any method; {@code userId} null = a guest; with the CSRF cookie and header. */
    protected ResultActions call(HttpMethod method, String url, Long userId, String role, String body)
            throws Exception {
        MockHttpServletRequestBuilder builder = request(method, url)
                .cookie(new Cookie("XSRF-TOKEN", CSRF)).header("X-XSRF-TOKEN", CSRF);
        if (userId != null) {
            builder.session(session(userId, role));
        }
        if (body != null) {
            builder.contentType(MediaType.APPLICATION_JSON).content(body);
        }
        return mvc.perform(builder);
    }

    protected ResultActions clearToday(long user) throws Exception {
        return call(HttpMethod.DELETE, "/api/dev/quests/today", user, "USER", null);
    }

    protected ResultActions history(long user, String body) throws Exception {
        return call(HttpMethod.POST, "/api/dev/quests/history", user, "USER", body);
    }

    protected ResultActions history(long user, int doneDays) throws Exception {
        return history(user, "{\"doneDays\":" + doneDays + "}");
    }

    protected ResultActions forceToday(long user, String... codes) throws Exception {
        String list = java.util.Arrays.stream(codes).map(c -> "\"" + c + "\"").collect(Collectors.joining(","));
        return call(HttpMethod.PUT, "/api/dev/quests/today", user, "USER", "{\"codes\":[" + list + "]}");
    }

    protected ResultActions setCoins(long user, String body) throws Exception {
        return call(HttpMethod.PUT, "/api/dev/coins", user, "USER", body);
    }

    protected ResultActions exchange(long user, int points) throws Exception {
        return postAs(user, "/api/me/coins/exchange", "{\"points\":" + points + "}");
    }

    /** GET /api/me/quests/today (200), its data. */
    protected JsonNode today(long user) throws Exception {
        return data(getAs(user, "/api/me/quests/today").andExpect(status().isOk()));
    }

    /** A flash card answer (200), its data. */
    protected JsonNode answer(long user, long phrase, boolean learned) throws Exception {
        return data(flash(user, phrase, learned).andExpect(status().isOk()));
    }

    /** "Nghe" of a phrase (POST /api/me/quests/events), 200, its data (a QuestChange). */
    protected JsonNode listen(long user, long phraseId) throws Exception {
        return data(postAs(user, "/api/me/quests/events", "{\"type\":\"LISTEN\",\"phraseId\":" + phraseId + "}")
                .andExpect(status().isOk()));
    }

    /** Moves the clock past the minimum spacing of counted answers. */
    protected void next() {
        clock.advance(Duration.ofSeconds(2));
    }

    protected JsonNode data(ResultActions result) throws Exception {
        return json.readTree(body(result)).path("data");
    }

    protected String message(ResultActions result) throws Exception {
        return json.readTree(body(result)).path("message").asText();
    }

    // ---------------------------------------------------------------- reading answers

    protected static JsonNode quest(JsonNode today, String code) {
        for (JsonNode q : today.path("quests")) {
            if (code.equals(q.path("code").asText())) {
                return q;
            }
        }
        throw new AssertionError("No quest " + code + " in " + today);
    }

    protected static List<String> codes(JsonNode today) {
        return StreamSupport.stream(today.path("quests").spliterator(), false).map(q -> q.path("code").asText())
                .toList();
    }

    protected static List<Long> ids(JsonNode today) {
        return StreamSupport.stream(today.path("quests").spliterator(), false).map(q -> q.path("id").asLong())
                .toList();
    }

    // ---------------------------------------------------------------- rows

    protected int rows(String sql, Object... args) {
        Integer n = jdbc.queryForObject(sql, Integer.class, args);
        return n == null ? 0 : n;
    }

    /** The user's flash card marks: phrase id → "learned" / "learning" / "none" (a row with both off). */
    protected Map<Long, String> marks(long user) {
        Map<Long, String> marks = new TreeMap<>();
        jdbc.query("SELECT phrase_id, is_learned, is_learning FROM user_phrase_marks WHERE user_id = ?",
                (rs, i) -> marks.put(rs.getLong(1),
                        rs.getBoolean(2) ? "learned" : rs.getBoolean(3) ? "learning" : "none"), user);
        return marks;
    }

    /** The streak kept for each day of the user: date → streak. */
    protected Map<LocalDate, Integer> doneDays(long user) {
        Map<LocalDate, Integer> days = new TreeMap<>();
        jdbc.query("SELECT quest_date, streak FROM user_quest_days WHERE user_id = ? AND all_done_at IS NOT NULL",
                (rs, i) -> days.put(rs.getObject(1, LocalDate.class), rs.getInt(2)), user);
        return days;
    }

    /** The balance must always be the sum of the book of xu. */
    protected int bookOf(long user) {
        return rows("SELECT COALESCE(SUM(delta), 0) FROM coin_transactions WHERE user_id = ?", user);
    }

    protected List<String> coinReasonsOn(long user, LocalDate day) {
        return new ArrayList<>(jdbc.queryForList(
                "SELECT reason FROM coin_transactions WHERE user_id = ? AND quest_date = ? ORDER BY id", String.class,
                user, Date.valueOf(day)));
    }

    /** Forgets the phrase of a (random, far) day, so it is chosen again from the library the test made. */
    protected void clearPhraseOfDay(LocalDate day) {
        jdbc.update("DELETE FROM daily_phrases WHERE phrase_date = ?", Date.valueOf(day));
    }
}
