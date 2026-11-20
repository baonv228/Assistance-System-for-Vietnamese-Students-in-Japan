package com.sep490.vsa.feature.quest.contract;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sep490.vsa.feature.quest.QuestTestSupport;
import org.assertj.core.api.SoftAssertions;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

/**
 * Helpers of the QA contract tests of "Nhiệm vụ hằng ngày" / "xu": they only use the HTTP API the mobile app uses
 * (WEB_DAILY_QUESTS_PROMPT.md, section 5) and plain SQL for setting up a state and reading rows back.
 */
abstract class QaContractSupport extends QuestTestSupport {

    /** Own database of the QA tests (never asvsj_test / asvsj_quest_test of the other engineers). */
    static final String QA_DB = "spring.datasource.url=jdbc:mysql://localhost:3306/asvsj_quest_qa_test?createDatabaseIfNotExist=true&useUnicode=true&characterEncoding=utf8&connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true";

    static final String MSG18 = "Bạn không có quyền thực hiện thao tác này.";
    static final ObjectMapper JSON = new ObjectMapper();
    /** ISO-8601 UTC as the contract shows it ("2026-11-18T15:00:00Z"); a fraction of a second is tolerated. */
    static final Pattern ISO_UTC = Pattern.compile("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(\\.\\d{1,9})?Z");
    static final Pattern ISO_DATE = Pattern.compile("\\d{4}-\\d{2}-\\d{2}");
    /** Time left between two answers that must both count (min-answer-interval-ms is 1500 in tests). */
    static final Duration GAP = Duration.ofSeconds(2);

    static final String[] TODAY_KEYS = {"date", "resetAt", "quests", "bonus", "streak", "recentDays", "coinsToday",
            "dailyCap", "wallet"};
    static final String[] QUEST_KEYS = {"id", "code", "title", "description", "target", "progress", "coins",
            "completed", "completedAt", "action", "phrase"};
    static final String[] ACTION_KEYS = {"type", "by", "group", "groupName", "kind", "status"};
    static final String[] WALLET_KEYS = {"coins", "rate", "monthCap", "exchangedThisMonth", "exchangeableNow",
            "monthResetAt", "points", "rankName"};
    static final String[] CHANGE_KEYS = {"changed", "justCompleted", "bonusJustCompleted", "bonusCoins", "streak",
            "streakMilestone", "coinsAwarded", "coinsToday", "dailyCap", "wallet"};
    static final String[] HOME_QUEST_KEYS = {"generated", "done", "total", "bonusDone", "streak", "todayDone",
            "coinsToday", "dailyCap", "coins", "exchangeableNow"};
    static final String[] COIN_LINE_KEYS = {"id", "delta", "reason", "reasonLabel", "title", "link", "createdAt"};

    // ---------------------------------------------------------------- days

    /**
     * {@link #freshDay()} first (isolation), then noon of a random date of that year whose day and month both have
     * two digits, so a "dd/MM" title cannot depend on leading zeros.
     */
    protected LocalDate qaDay() {
        LocalDate fresh = freshDay();
        ThreadLocalRandom r = ThreadLocalRandom.current();
        LocalDate day = LocalDate.of(fresh.getYear(), r.nextInt(10, 13), r.nextInt(10, 29));
        setJapanTime(day, LocalTime.NOON);
        return day;
    }

    static Instant japanMidnight(LocalDate day) {
        return day.atStartOfDay(JAPAN).toInstant();
    }

    static String ddMM(LocalDate day) {
        return day.format(DateTimeFormatter.ofPattern("dd/MM"));
    }

    // ---------------------------------------------------------------- library scenarios

    /**
     * {@code count} entries (kind PHRASE, "Cửa hàng tiện lợi") of the sample topic "pay" ("Thanh toán"); every other
     * phrase hidden; each member gets stage ARR (whose first topic is "pay") and "Đã thuộc" on every entry, so
     * LEARN_NEW (nothing left to learn) and REVIEW_LEARNING (no "Chưa thuộc") cannot be given: the day is
     * FLASH_REVIEW + TOPIC "pay" + PHRASE_OF_DAY.
     */
    protected List<Long> payLibrary(int count, long... members) {
        List<Long> ids = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            ids.add(phrase("konbini", "PHRASE", "pay"));
        }
        isolateLibrary();
        for (long member : members) {
            setStage(member, "ARR");
            for (long id : ids) {
                mark(member, id, true);
            }
        }
        return ids;
    }

    /** Entries of a vocabulary-only library (no PHRASE: no phrase of the day; no topic: no TOPIC). */
    protected record Vocab(List<Long> learning, List<Long> none, List<Long> learned) {
        List<Long> all() {
            List<Long> all = new ArrayList<>(learning);
            all.addAll(none);
            all.addAll(learned);
            return all;
        }
    }

    /**
     * A vocabulary-only library, every other phrase hidden: {@code learning} entries marked "Chưa thuộc",
     * {@code none} without a mark, {@code learned} marked "Đã thuộc" for each member. With learning ≥ 5 the day is
     * FLASH_REVIEW + LEARN_NEW + REVIEW_LEARNING.
     */
    protected Vocab vocabLibrary(int learning, int none, int learned, long... members) {
        Vocab v = new Vocab(new ArrayList<>(), new ArrayList<>(), new ArrayList<>());
        for (int i = 0; i < learning; i++) {
            v.learning().add(phrase("konbini", "VOCAB"));
        }
        for (int i = 0; i < none; i++) {
            v.none().add(phrase("konbini", "VOCAB"));
        }
        for (int i = 0; i < learned; i++) {
            v.learned().add(phrase("konbini", "VOCAB"));
        }
        isolateLibrary();
        for (long member : members) {
            for (long id : v.learning()) {
                mark(member, id, false);
            }
            for (long id : v.learned()) {
                mark(member, id, true);
            }
        }
        return v;
    }

    // ---------------------------------------------------------------- SQL set-up

    /** Gives xu as a DEV_ADJUST line (no ref) and moves users.coins with it. */
    protected void seedCoins(long userId, int coins) {
        jdbc.update("""
                INSERT INTO coin_transactions (user_id, delta, reason, title, created_at)
                VALUES (?, ?, 'DEV_ADJUST', 'Điều chỉnh khi thử', ?)""", userId, coins,
                Timestamp.from(clock.instant().minusSeconds(30)));
        jdbc.update("UPDATE users SET coins = coins + ? WHERE id = ?", coins, userId);
    }

    /** Xu of quests already received today (reason DAILY_QUEST, quest_date today, a ref that matches no quest). */
    protected void seedQuestCoinsToday(long userId, int coins) {
        jdbc.update("""
                INSERT INTO coin_transactions (user_id, delta, reason, title, ref_type, ref_id, quest_date, created_at)
                VALUES (?, ?, 'DAILY_QUEST', 'QA: xu nhiệm vụ trước đó', 'QUEST', ?, ?, ?)""", userId, coins,
                -ThreadLocalRandom.current().nextLong(1, 1L << 40), japanToday().toString(),
                Timestamp.from(clock.instant().minusSeconds(30)));
        jdbc.update("UPDATE users SET coins = coins + ? WHERE id = ?", coins, userId);
    }

    /** A done Japan day of the past with the streak reached that day. */
    protected void seedDoneDay(long userId, LocalDate day, int streak) {
        jdbc.update("INSERT INTO user_quest_days (user_id, quest_date, all_done_at, streak) VALUES (?, ?, ?, ?)",
                userId, day.toString(), Timestamp.from(day.atTime(21, 0).atZone(JAPAN).toInstant()), streak);
    }

    /**
     * A LEARN_NEW quest of an earlier day that counted these entries (section 4: user_quest_items tells whether an
     * entry was ever counted for LEARN_NEW).
     */
    protected void seedLearnNewHistory(long userId, LocalDate day, List<Long> phraseIds) {
        int n = phraseIds.size();
        Timestamp at = Timestamp.from(day.atTime(10, 0).atZone(JAPAN).toInstant());
        jdbc.update("""
                INSERT INTO user_daily_quests (user_id, quest_date, code, sort_order, title, target, progress, coins,
                                               completed_at)
                VALUES (?, ?, 'LEARN_NEW', 1, ?, ?, ?, 10, ?)""", userId, day.toString(), "Thuộc " + n + " mục mới",
                n, n, at);
        Long questId = jdbc.queryForObject(
                "SELECT id FROM user_daily_quests WHERE user_id = ? AND quest_date = ? AND code = 'LEARN_NEW'",
                Long.class, userId, day.toString());
        for (long p : phraseIds) {
            jdbc.update("INSERT INTO user_quest_items (user_daily_quest_id, phrase_id, counted_at) VALUES (?, ?, ?)",
                    questId, p, at);
        }
    }

    protected int count(String sql, Object... args) {
        Integer n = jdbc.queryForObject(sql, Integer.class, args);
        return n == null ? 0 : n;
    }

    // ---------------------------------------------------------------- requests

    protected static JsonNode json(ResultActions result) throws Exception {
        return JSON.readTree(body(result));
    }

    /** Asserts HTTP 200 + success and returns {@code data}. */
    protected static JsonNode ok(ResultActions result) throws Exception {
        MockHttpServletResponse response = result.andReturn().getResponse();
        String body = response.getContentAsString(StandardCharsets.UTF_8);
        assertThat(response.getStatus()).as("HTTP status of %s", body).isEqualTo(200);
        JsonNode node = JSON.readTree(body);
        assertThat(node.path("success").asBoolean()).as("success of %s", body).isTrue();
        return node.get("data");
    }

    protected JsonNode today(long userId) throws Exception {
        return ok(getAs(userId, "/api/me/quests/today"));
    }

    /** A flash card answer 2 s after the previous one (so it may count), answered 200. Returns data. */
    protected JsonNode answer(long userId, long phraseId, boolean learned) throws Exception {
        clock.advance(GAP);
        return ok(flash(userId, phraseId, learned));
    }

    protected ResultActions listenRaw(long userId, long phraseId) throws Exception {
        return postAs(userId, "/api/me/quests/events", "{\"type\":\"LISTEN\",\"phraseId\":" + phraseId + "}");
    }

    protected JsonNode listen(long userId, long phraseId) throws Exception {
        return ok(listenRaw(userId, phraseId));
    }

    protected ResultActions exchangeRaw(long userId, String pointsJson) throws Exception {
        return postAs(userId, "/api/me/coins/exchange", "{\"points\":" + pointsJson + "}");
    }

    /** The phrase of today as the guest Home shows it. */
    protected long homePhraseId() throws Exception {
        JsonNode phrase = ok(guestGet("/api/home")).path("phrase");
        assertThat(phrase.path("id").isIntegralNumber()).as("phrase.id of GET /api/home: %s", phrase).isTrue();
        return phrase.path("id").asLong();
    }

    /**
     * Finishes a day made by {@link #payLibrary} with 5 entries (FLASH_REVIEW 5, TOPIC 5, PHRASE_OF_DAY): LISTEN first,
     * then the 5 entries, 2 s apart. Returns every QuestChange, the one that completes the day last.
     */
    protected List<JsonNode> finishPayDay(long userId, List<Long> lib) throws Exception {
        List<JsonNode> changes = new ArrayList<>();
        JsonNode pod = requireQuest(today(userId), "PHRASE_OF_DAY");
        clock.advance(GAP);
        changes.add(listen(userId, pod.path("phrase").path("id").asLong()));
        for (long id : lib) {
            changes.add(answer(userId, id, true).path("quests"));
        }
        return changes;
    }

    /** The Member's notifications of a type (GET /api/me/notifications, first page). */
    protected List<JsonNode> notifications(long userId, String type) throws Exception {
        List<JsonNode> found = new ArrayList<>();
        for (JsonNode n : ok(getAs(userId, "/api/me/notifications")).path("items")) {
            if (type.equals(n.path("type").asText())) {
                found.add(n);
            }
        }
        return found;
    }

    // ---------------------------------------------------------------- reading answers

    protected static JsonNode quest(JsonNode today, String code) {
        for (JsonNode q : today.path("quests")) {
            if (code.equals(q.path("code").asText())) {
                return q;
            }
        }
        return null;
    }

    protected static JsonNode requireQuest(JsonNode today, String code) {
        JsonNode q = quest(today, code);
        if (q == null) {
            fail("No quest " + code + " today; quests: " + codes(today.path("quests")));
        }
        return q;
    }

    protected static List<String> codes(JsonNode array) {
        List<String> codes = new ArrayList<>();
        for (JsonNode n : array) {
            codes.add(n.path("code").asText());
        }
        return codes;
    }

    /** Codes in QuestChange.changed of a flash answer (data.quests) or an event (data). */
    protected static Set<String> changedCodes(JsonNode questChange) {
        return new TreeSet<>(codes(questChange.path("changed")));
    }

    protected static JsonNode changed(JsonNode questChange, String code) {
        for (JsonNode n : questChange.path("changed")) {
            if (code.equals(n.path("code").asText())) {
                return n;
            }
        }
        return null;
    }

    protected static int progress(JsonNode today, String code) {
        return requireQuest(today, code).path("progress").asInt();
    }

    protected static Set<String> keys(JsonNode node) {
        Set<String> keys = new TreeSet<>();
        if (node != null) {
            Iterator<String> it = node.fieldNames();
            it.forEachRemaining(keys::add);
        }
        return keys;
    }

    /**
     * The entries a Member would practise for this quest's {@code action}: the library (GET /api/phrases) filtered
     * by by/group/kind/status exactly as the app does it.
     */
    protected List<Long> cardsFor(long userId, JsonNode action) throws Exception {
        JsonNode cards = ok(getAs(userId, "/api/phrases"));
        String by = action.path("by").asText();
        String group = action.path("group").asText();
        String kind = action.path("kind").asText();
        String status = action.path("status").asText();
        List<Long> ids = new ArrayList<>();
        for (JsonNode card : cards) {
            if ("topic".equals(by)) {
                boolean in = false;
                for (JsonNode t : card.path("topics")) {
                    in |= group.equals(t.path("id").asText());
                }
                if (!in) {
                    continue;
                }
            } else if ("category".equals(by) && !group.equals(card.path("category").path("id").asText())) {
                continue;
            }
            if (!kind.isEmpty() && !kind.equals(card.path("kind").asText())) {
                continue;
            }
            boolean learning = card.path("learning").asBoolean();
            boolean learned = card.path("learned").asBoolean();
            if ("learning".equals(status) && !learning || "learned".equals(status) && !learned
                    || "new".equals(status) && (learning || learned)) {
                continue;
            }
            ids.add(card.path("id").asLong());
        }
        return ids;
    }

    // ---------------------------------------------------------------- shapes

    static void exactKeys(SoftAssertions s, String path, JsonNode node, String... expected) {
        if (node == null || !node.isObject()) {
            s.fail(path + " should be an object, is: " + node);
            return;
        }
        s.assertThat(keys(node)).as(path + " keys").containsExactlyInAnyOrder(expected);
    }

    static void isInt(SoftAssertions s, String path, JsonNode node) {
        s.assertThat(node != null && node.isIntegralNumber()).as(path + " should be a whole number, is: " + node)
                .isTrue();
    }

    static void isText(SoftAssertions s, String path, JsonNode node) {
        s.assertThat(node != null && node.isTextual()).as(path + " should be a string, is: " + node).isTrue();
    }

    static void isBool(SoftAssertions s, String path, JsonNode node) {
        s.assertThat(node != null && node.isBoolean()).as(path + " should be a boolean, is: " + node).isTrue();
    }

    static void isNullValue(SoftAssertions s, String path, JsonNode node) {
        s.assertThat(node != null && node.isNull()).as(path + " should be present and null, is: " + node).isTrue();
    }

    static void isInstant(SoftAssertions s, String path, JsonNode node) {
        s.assertThat(node != null && node.isTextual() && ISO_UTC.matcher(node.asText()).matches())
                .as(path + " should be an ISO-8601 UTC instant ending in Z, is: " + node).isTrue();
    }

    static void isDate(SoftAssertions s, String path, JsonNode node) {
        s.assertThat(node != null && node.isTextual() && ISO_DATE.matcher(node.asText()).matches())
                .as(path + " should be a yyyy-MM-dd date, is: " + node).isTrue();
    }

    static Instant instant(JsonNode node) {
        return node == null || !node.isTextual() ? null : Instant.parse(node.asText());
    }

    /** Shape of the shared wallet (5.0). */
    static void walletShape(SoftAssertions s, String path, JsonNode w) {
        exactKeys(s, path, w, WALLET_KEYS);
        if (w == null) {
            return;
        }
        for (String f : new String[]{"coins", "rate", "monthCap", "exchangedThisMonth", "exchangeableNow", "points"}) {
            isInt(s, path + "." + f, w.get(f));
        }
        isInstant(s, path + ".monthResetAt", w.get("monthResetAt"));
        isText(s, path + ".rankName", w.get("rankName"));
    }

    /** Shape of a QuestChange (5.2 / 5.3). */
    static void questChangeShape(SoftAssertions s, String path, JsonNode c) {
        exactKeys(s, path, c, CHANGE_KEYS);
        if (c == null) {
            return;
        }
        s.assertThat(c.path("changed").isArray()).as(path + ".changed is an array").isTrue();
        int i = 0;
        for (JsonNode n : c.path("changed")) {
            String p = path + ".changed[" + i++ + "]";
            exactKeys(s, p, n, "id", "code", "progress", "target", "completed");
            isInt(s, p + ".id", n.get("id"));
            isText(s, p + ".code", n.get("code"));
            isInt(s, p + ".progress", n.get("progress"));
            isInt(s, p + ".target", n.get("target"));
            isBool(s, p + ".completed", n.get("completed"));
        }
        s.assertThat(c.path("justCompleted").isArray()).as(path + ".justCompleted is an array").isTrue();
        i = 0;
        for (JsonNode n : c.path("justCompleted")) {
            String p = path + ".justCompleted[" + i++ + "]";
            exactKeys(s, p, n, "id", "code", "title", "coins");
            isInt(s, p + ".id", n.get("id"));
            isText(s, p + ".code", n.get("code"));
            isText(s, p + ".title", n.get("title"));
            isInt(s, p + ".coins", n.get("coins"));
        }
        isBool(s, path + ".bonusJustCompleted", c.get("bonusJustCompleted"));
        isInt(s, path + ".bonusCoins", c.get("bonusCoins"));
        exactKeys(s, path + ".streak", c.get("streak"), "current", "todayDone");
        isInt(s, path + ".streak.current", c.path("streak").get("current"));
        isBool(s, path + ".streak.todayDone", c.path("streak").get("todayDone"));
        JsonNode m = c.get("streakMilestone");
        if (m == null || !m.isNull()) {
            exactKeys(s, path + ".streakMilestone", m, "days", "coins");
            if (m != null) {
                isInt(s, path + ".streakMilestone.days", m.get("days"));
                isInt(s, path + ".streakMilestone.coins", m.get("coins"));
            }
        }
        isInt(s, path + ".coinsAwarded", c.get("coinsAwarded"));
        isInt(s, path + ".coinsToday", c.get("coinsToday"));
        isInt(s, path + ".dailyCap", c.get("dailyCap"));
        walletShape(s, path + ".wallet", c.get("wallet"));
    }

    // ---------------------------------------------------------------- concurrency

    /** One finished request of a parallel run. */
    protected record Reply(int status, JsonNode body) {
        JsonNode data() {
            return body.get("data");
        }
    }

    /** Runs the calls at the same moment from as many threads and waits for all of them. */
    @SafeVarargs
    protected final List<Reply> parallel(Callable<ResultActions>... calls) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(calls.length);
        CountDownLatch ready = new CountDownLatch(calls.length);
        CountDownLatch go = new CountDownLatch(1);
        try {
            List<Future<Reply>> futures = new ArrayList<>();
            for (Callable<ResultActions> call : calls) {
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    go.await();
                    MvcResult r = call.call().andReturn();
                    String body = r.getResponse().getContentAsString(StandardCharsets.UTF_8);
                    return new Reply(r.getResponse().getStatus(), body.isEmpty() ? JSON.nullNode() : JSON.readTree(body));
                }));
            }
            ready.await(10, TimeUnit.SECONDS);
            go.countDown();
            List<Reply> replies = new ArrayList<>();
            for (Future<Reply> f : futures) {
                replies.add(f.get(60, TimeUnit.SECONDS));
            }
            return replies;
        } finally {
            pool.shutdownNow();
        }
    }
}
