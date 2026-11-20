package com.sep490.vsa.feature.home;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sep490.vsa.feature.notification.NotificationService;
import com.sep490.vsa.feature.phrase.PhraseOfDayService;
import com.sep490.vsa.feature.phrase.PhraseOfDayService.PhraseOfDay;
import com.sep490.vsa.feature.quest.QuestTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.ResultActions;

import java.sql.Date;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.head;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The Home's side of "Nhiệm vụ hằng ngày": "Câu tiếng Nhật hôm nay" read from the kept phrase of the day (with its
 * {@code id}, the one the quest PHRASE_OF_DAY listens for) on both Homes, the member Home's read-only {@code quests}
 * block, every field the two answers had before, notification links of the quests and the web address {@code /quests}.
 */
@TestPropertySource(properties = "spring.datasource.url=jdbc:mysql://localhost:3306/asvsj_quest_home_test?createDatabaseIfNotExist=true&useUnicode=true&characterEncoding=utf8&connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true")
class HomeQuestFlowTest extends QuestTestSupport {

    /** The fields of GET /api/home before the quests (HomeDtos.GuestHome, Stats and Phrase at the baseline). */
    private static final Set<String> GUEST_FIELDS = Set.of("stats", "quotes", "phrase");
    private static final Set<String> STATS_FIELDS = Set.of("placesWithReviews", "reviews", "phrases");
    private static final Set<String> PHRASE_FIELDS = Set.of("japanese", "romaji", "meaningVi", "categoryId",
            "categoryName", "categoryIcon", "topicId");
    /** The fields of GET /api/me/home before the quests (HomeDtos.MemberHome and GuideProgress at the baseline). */
    private static final Set<String> MEMBER_FIELDS = Set.of("displayName", "stage", "areaId", "areaName", "points",
            "rankName", "phrase", "guide", "events", "reviews", "featured", "forYou", "interests", "chosenInterests",
            "stageGuides");
    private static final Set<String> GUIDE_FIELDS = Set.of("stageCode", "stageTitle", "done", "total", "next");
    /** The "quests" block of the member Home (contract 5.6). */
    private static final Set<String> QUEST_FIELDS = Set.of("generated", "done", "total", "bonusDone", "streak",
            "todayDone", "coinsToday", "dailyCap", "coins", "exchangeableNow");

    private static final ObjectMapper JSON = new ObjectMapper();

    @Autowired
    private PhraseOfDayService phraseOfDay;
    @Autowired
    private NotificationService notifications;

    private final List<Long> blogs = new ArrayList<>();

    /** Runs before the base class removes its users: their posts first. */
    @AfterEach
    void removePosts() {
        for (long id : blogs) {
            jdbc.update("DELETE FROM blogs WHERE id = ?", id);
        }
        blogs.clear();
    }

    // ---------------------------------------------------------------- "Câu tiếng Nhật hôm nay"

    @Test
    void bothHomesShowTheKeptPhraseOfTheDayWithItsId() throws Exception {
        freshDay();
        String topic = topic("Thanh toán thử");
        phrase("konbini", "PHRASE", topic);
        phrases(2);
        long user = member();

        PhraseOfDay expected = phraseOfDay.phraseOfToday().orElseThrow();
        Map<String, Object> row = jdbc.queryForMap(
                "SELECT japanese, romaji, meaning_vi FROM phrases WHERE id = ?", expected.id());

        for (JsonNode phrase : List.of(data(guestGet("/api/home")).get("phrase"),
                data(getAs(user, "/api/me/home")).get("phrase"))) {
            assertThat(phrase.get("id").asLong()).isEqualTo(expected.id());
            assertThat(phrase.get("japanese").asText()).isEqualTo(row.get("japanese")).isEqualTo(expected.japanese());
            assertThat(phrase.get("romaji").asText()).isEqualTo(row.get("romaji")).isEqualTo(expected.romaji());
            assertThat(phrase.get("meaningVi").asText()).isEqualTo(row.get("meaning_vi"))
                    .isEqualTo(expected.meaningVi());
            assertThat(phrase.get("categoryId").asText()).isEqualTo(expected.categoryId());
            assertThat(phrase.get("categoryName").asText()).isEqualTo(expected.categoryName());
            assertThat(phrase.get("categoryIcon").asText()).isEqualTo(expected.categoryIcon());
            assertThat(phrase.get("topicId").isNull() ? null : phrase.get("topicId").asText())
                    .isEqualTo(expected.topicId());
        }
    }

    @Test
    void theHomePhraseStaysAllDayWhenPhrasesAreAddedOrDeletedAndTheNextDayReadsItsOwn() throws Exception {
        LocalDate day = freshDay();
        isolateLibrary();
        List<Long> library = phrases(3);
        long user = member();

        long first = data(guestGet("/api/home")).get("phrase").get("id").asLong();
        assertThat(library).contains(first);
        assertThat(keptOn(day)).isEqualTo(first);

        // The Japanese content editor adds two phrases and deletes another one: the Home keeps today's phrase
        phrases(2);
        long other = library.stream().filter(id -> id != first).findFirst().orElseThrow();
        softDelete(other);
        assertThat(homePhraseIds(user)).containsOnly(first);

        // Even the phrase itself deleted later that day: still today's phrase, on both Homes, until the day ends
        softDelete(first);
        assertThat(homePhraseIds(user)).containsOnly(first);
        setJapanTime(day, LocalTime.of(23, 59, 59));
        assertThat(homePhraseIds(user)).containsOnly(first);

        // 00:00 in Japan: a new day reads its own phrase, chosen from the phrases not deleted and kept from then on
        LocalDate next = day.plusDays(1);
        setJapanTime(next, LocalTime.MIDNIGHT);
        List<Long> visible = jdbc.queryForList(
                "SELECT id FROM phrases WHERE kind = 'PHRASE' AND deleted_at IS NULL ORDER BY id", Long.class);
        long expected = visible.get(Math.floorMod(next.toEpochDay(), visible.size()));
        assertThat(homePhraseIds(user)).containsOnly(expected);
        assertThat(keptOn(next)).isEqualTo(expected);
        assertThat(phraseOfDay.phraseOf(next).orElseThrow().id()).isEqualTo(expected);
        phrases(1);
        assertThat(homePhraseIds(user)).containsOnly(expected);
        // The day before keeps its own row
        assertThat(keptOn(day)).isEqualTo(first);
    }

    @Test
    void anEmptyLibraryGivesNoPhraseOnEitherHome() throws Exception {
        LocalDate day = freshDay();
        isolateLibrary();
        long user = member();

        assertNoPhrase(user);
        // Words ("Từ vựng") never become the phrase of the day
        phrase("konbini", "VOCAB");
        assertNoPhrase(user);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM daily_phrases WHERE phrase_date = ?", Integer.class,
                Date.valueOf(day))).isZero();
    }

    @Test
    void twoFirstHomesOfADayAtTheSameMomentShowTheSamePhrase() throws Exception {
        LocalDate day = freshDay();
        phrases(3);
        int threads = 4;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<Long>> answers = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                answers.add(pool.submit((Callable<Long>) () -> {
                    start.await();
                    return data(guestGet("/api/home")).get("phrase").get("id").asLong();
                }));
            }
            start.countDown();
            Set<Long> ids = new HashSet<>();
            for (Future<Long> answer : answers) {
                ids.add(answer.get(60, TimeUnit.SECONDS));
            }
            assertThat(ids).hasSize(1);
            assertThat(keptOn(day)).isEqualTo(ids.iterator().next());
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM daily_phrases WHERE phrase_date = ?", Integer.class,
                    Date.valueOf(day))).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
    }

    // ---------------------------------------------------------------- the "quests" block

    @Test
    void theMemberHomeHasTheQuestsBlockAndMakesNothing() throws Exception {
        LocalDate day = freshDay();
        phrases(1);
        long user = member();
        jdbc.update("UPDATE users SET coins = 340 WHERE id = ?", user);
        int phraseDaysBefore = jdbc.queryForObject("SELECT COUNT(*) FROM daily_phrases", Integer.class);

        for (int call = 0; call < 2; call++) {
            JsonNode quests = data(getAs(user, "/api/me/home")).get("quests");
            assertThat(fields(quests)).containsExactlyInAnyOrderElementsOf(QUEST_FIELDS);
            assertThat(quests.get("generated").asBoolean()).isFalse();
            assertThat(quests.get("done").asInt()).isZero();
            assertThat(quests.get("total").asInt()).isEqualTo(3);
            assertThat(quests.get("bonusDone").asBoolean()).isFalse();
            assertThat(quests.get("streak").asInt()).isZero();
            assertThat(quests.get("todayDone").asBoolean()).isFalse();
            assertThat(quests.get("coinsToday").asInt()).isZero();
            assertThat(quests.get("dailyCap").asInt()).isEqualTo(50);
            assertThat(quests.get("coins").asInt()).isEqualTo(340);
            // min(340 / 50, 10 − 0 exchanged this month)
            assertThat(quests.get("exchangeableNow").asInt()).isEqualTo(6);
        }

        // Read only: no quest, no day, no xu; only the day's phrase is kept (by PhraseOfDayService)
        assertThat(countFor("user_daily_quests", user)).isZero();
        assertThat(countFor("user_quest_days", user)).isZero();
        assertThat(countFor("coin_transactions", user)).isZero();
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM user_quest_items i JOIN user_daily_quests q ON q.id = i.user_daily_quest_id
                WHERE q.user_id = ?""", Integer.class, user)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM daily_phrases WHERE phrase_date = ?", Integer.class,
                Date.valueOf(day))).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM daily_phrases", Integer.class))
                .isLessThanOrEqualTo(phraseDaysBefore + 1);
        assertThat(coinsOf(user)).isEqualTo(340);
    }

    @Test
    void everyFieldTheTwoHomesHadBeforeIsStillThere() throws Exception {
        freshDay();
        phrases(1);
        long user = member();

        JsonNode guest = data(guestGet("/api/home"));
        assertThat(fields(guest)).containsExactlyInAnyOrderElementsOf(GUEST_FIELDS);
        assertThat(fields(guest.get("stats"))).containsExactlyInAnyOrderElementsOf(STATS_FIELDS);
        assertThat(guest.get("quotes").isArray()).isTrue();
        assertThat(fields(guest.get("phrase"))).containsAll(PHRASE_FIELDS).contains("id").hasSize(8);

        JsonNode home = data(getAs(user, "/api/me/home"));
        assertThat(fields(home)).containsAll(MEMBER_FIELDS).contains("quests").hasSize(MEMBER_FIELDS.size() + 1);
        assertThat(fields(home.get("phrase"))).containsAll(PHRASE_FIELDS).contains("id").hasSize(8);
        assertThat(fields(home.get("guide"))).containsExactlyInAnyOrderElementsOf(GUIDE_FIELDS);
        for (String list : List.of("events", "reviews", "forYou", "interests", "chosenInterests", "stageGuides")) {
            assertThat(home.get(list).isArray()).as(list).isTrue();
        }
        assertThat(home.get("displayName").asText()).isEqualTo("Quest Test");
        assertThat(home.get("rankName").asText()).isNotBlank();
    }

    // ---------------------------------------------------------------- notifications and /quests

    @Test
    void questNotificationsOpenTheQuestPageOthersKeepTheirLinks() throws Exception {
        freshDay();
        long user = member();
        long post = post(user, "POST");
        long question = post(user, "QA");
        long latest = jdbc.queryForObject("SELECT COALESCE(MAX(id), 0) FROM notifications WHERE user_id = ?",
                Long.class, user);
        notifications.send(user, "NEW_COMMENT", "Bình luận mới", "BLOG", post);
        notifications.send(user, "BEST_ANSWER", "Câu trả lời hay nhất", "QUESTION", question);
        notifications.send(user, "QUEST_STREAK", "Bạn đã học 7 ngày liên tiếp. +50 xu.", "QUEST", 123_456L);

        Map<String, String> listed = links(data(getAs(user, "/api/me/notifications")).get("items"));
        assertThat(listed).containsEntry("QUEST_STREAK", "/quests")
                .containsEntry("NEW_COMMENT", "/blog/" + post)
                .containsEntry("BEST_ANSWER", "/qa/" + question);

        JsonNode summary = data(getAs(user, "/api/me/notifications/summary?after=" + latest));
        Map<String, String> fresh = links(summary.get("fresh"));
        assertThat(fresh).containsEntry("QUEST_STREAK", "/quests")
                .containsEntry("NEW_COMMENT", "/blog/" + post)
                .containsEntry("BEST_ANSWER", "/qa/" + question);
        assertThat(summary.get("fresh").get(0).get("content").asText())
                .isEqualTo("Bạn đã học 7 ngày liên tiếp. +50 xu.");
    }

    @Test
    void theQuestsAddressGoesToNhatNgu() throws Exception {
        long user = member();
        for (ResultActions answer : List.of(guestGet("/quests"), guestGet("/quests/"), getAs(user, "/quests"),
                guestGet("/quests?from=notification"), mvc.perform(head("/quests")))) {
            MockHttpServletResponse response = answer.andExpect(status().isMovedPermanently()).andReturn().getResponse();
            assertThat(response.getHeader("Location")).isEqualTo("/phrases");
        }
        // Only the page address of a browser: other methods and other addresses are not redirected
        for (ResultActions answer : List.of(guestPost("/quests", "{}"), guestGet("/quests/today"),
                guestGet("/questsx"), guestGet("/api/me/quests/today"))) {
            MockHttpServletResponse response = answer.andReturn().getResponse();
            assertThat(response.getStatus()).isNotEqualTo(301);
            assertThat(response.getHeader("Location")).isNull();
        }
        mvc.perform(get("/phrases")).andExpect(status().isOk());
    }

    // ---------------------------------------------------------------- helpers

    private static JsonNode data(ResultActions result) throws Exception {
        result.andExpect(status().isOk());
        JsonNode root = JSON.readTree(body(result));
        assertThat(root.get("success").asBoolean()).isTrue();
        return root.get("data");
    }

    private static Set<String> fields(JsonNode node) {
        Set<String> names = new HashSet<>();
        for (Iterator<String> it = node.fieldNames(); it.hasNext(); ) {
            names.add(it.next());
        }
        return names;
    }

    /** The phrase id of the guest Home and of the member Home. */
    private List<Long> homePhraseIds(long user) throws Exception {
        return List.of(data(guestGet("/api/home")).get("phrase").get("id").asLong(),
                data(getAs(user, "/api/me/home")).get("phrase").get("id").asLong());
    }

    private void assertNoPhrase(long user) throws Exception {
        for (JsonNode home : List.of(data(guestGet("/api/home")), data(getAs(user, "/api/me/home")))) {
            assertThat(home.has("phrase")).isTrue();
            assertThat(home.get("phrase").isNull()).isTrue();
        }
    }

    private Long keptOn(LocalDate day) {
        return jdbc.query("SELECT phrase_id FROM daily_phrases WHERE phrase_date = ?", (rs, i) -> rs.getLong(1),
                Date.valueOf(day)).stream().findFirst().orElse(null);
    }

    private void softDelete(long phraseId) {
        jdbc.update("UPDATE phrases SET deleted_at = ? WHERE id = ?", Timestamp.from(clock.instant()), phraseId);
    }

    private int countFor(String table, long user) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE user_id = ?", Integer.class, user);
    }

    /** type → link of the notifications listed. */
    private static Map<String, String> links(JsonNode items) {
        Map<String, String> links = new java.util.HashMap<>();
        for (JsonNode item : items) {
            links.put(item.get("type").asText(), item.get("link").isNull() ? null : item.get("link").asText());
        }
        return links;
    }

    /** A published post (POST) or question (QA) of this user, deleted after the test. */
    private long post(long author, String type) {
        String title = "Quest home " + type + " " + System.nanoTime();
        jdbc.update("""
                INSERT INTO blogs (author_id, blog_type, title, content, status, like_count, comment_count, created_at)
                VALUES (?, ?, ?, 'Nội dung thử', 'PUBLISHED', 0, 0, ?)""",
                author, type, title, Timestamp.from(clock.instant()));
        long id = jdbc.queryForObject("SELECT id FROM blogs WHERE author_id = ? AND title = ?", Long.class, author,
                title);
        blogs.add(id);
        return id;
    }
}
