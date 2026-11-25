package com.sep490.vsa.feature.quest.contract;

import com.fasterxml.jackson.databind.JsonNode;
import org.assertj.core.api.SoftAssertions;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.ResultActions;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * 3.8 permissions, 5.3 event errors and idempotency, the rate limit, backward compatibility (section 0 "Chỉ thêm"),
 * the /quests address and "Xóa tài khoản" (section 4).
 */
@TestPropertySource(properties = QaContractSupport.QA_DB)
class QaAccessAndCompatContractFlowTest extends QaContractSupport {

    // ------------------------------------------------------------ 3.8

    @Test
    void guestsGet401OnEveryNewEndpoint() throws Exception {
        freshDay();
        SoftAssertions s = new SoftAssertions();
        s.assertThat(status(guestGet("/api/me/quests/today"))).as("GET today").isEqualTo(401);
        s.assertThat(status(guestPost("/api/me/quests/events", "{\"type\":\"LISTEN\",\"phraseId\":1}")))
                .as("POST events").isEqualTo(401);
        s.assertThat(status(guestGet("/api/me/coins"))).as("GET coins").isEqualTo(401);
        s.assertThat(status(guestGet("/api/me/coins?page=1"))).as("GET coins page 1").isEqualTo(401);
        s.assertThat(status(guestPost("/api/me/coins/exchange", "{\"points\":1}"))).as("POST exchange").isEqualTo(401);
        s.assertAll();
    }

    @Test
    void staffAccountsGet403Msg18OnEveryNewEndpoint() throws Exception {
        freshDay();
        SoftAssertions s = new SoftAssertions();
        for (String role : new String[]{"MODERATOR", "ACADEMIC_MANAGER", "ADMIN"}) {
            long staff = user(role, "ACTIVE");
            seedCoins(staff, 500);
            expect403(s, role + " GET today", getAs(staff, role, "/api/me/quests/today"));
            expect403(s, role + " POST events",
                    postAs(staff, role, "/api/me/quests/events", "{\"type\":\"LISTEN\",\"phraseId\":1}"));
            expect403(s, role + " GET coins", getAs(staff, role, "/api/me/coins"));
            expect403(s, role + " POST exchange", postAs(staff, role, "/api/me/coins/exchange", "{\"points\":1}"));
            s.assertThat(count("SELECT COUNT(*) FROM user_daily_quests WHERE user_id = ?", staff))
                    .as(role + " gets no quests").isZero();
            s.assertThat(pointsOf(staff)).as(role + " exchanged nothing").isZero();
        }
        s.assertAll();
    }

    @Test
    void aLockedAccountGets401() throws Exception {
        freshDay();
        long locked = user("USER", "LOCKED");
        SoftAssertions s = new SoftAssertions();
        s.assertThat(status(getAs(locked, "/api/me/quests/today"))).isEqualTo(401);
        s.assertThat(status(getAs(locked, "/api/me/coins"))).isEqualTo(401);
        s.assertThat(status(postAs(locked, "/api/me/coins/exchange", "{\"points\":1}"))).isEqualTo(401);
        s.assertThat(status(postAs(locked, "/api/me/quests/events", "{\"type\":\"LISTEN\",\"phraseId\":1}")))
                .isEqualTo(401);
        s.assertAll();
    }

    // ------------------------------------------------------------ 5.3

    @Test
    void eventErrorsAreFieldErrorsOnTypeAndPhraseId() throws Exception {
        freshDay();
        long m = member();
        SoftAssertions s = new SoftAssertions();
        expectFieldError(s, postAs(m, "/api/me/quests/events", "{\"type\":\"WATCH\",\"phraseId\":5}"), "type",
                "unknown type");
        expectFieldError(s, postAs(m, "/api/me/quests/events", "{\"phraseId\":5}"), "type", "missing type");
        expectFieldError(s, postAs(m, "/api/me/quests/events", "{\"type\":\"LISTEN\"}"), "phraseId",
                "missing phraseId");
        expectFieldError(s, postAs(m, "/api/me/quests/events", "{\"type\":\"LISTEN\",\"phraseId\":null}"), "phraseId",
                "null phraseId");
        s.assertThat(status(postAs(m, "/api/me/quests/events", "{\"type\":\"LISTEN\",\"phraseId\":\"abc\"}")))
                .as("phraseId not a number").isEqualTo(400);
        s.assertAll();
    }

    @Test
    void listenIsIdempotentAndAWrongPhraseChangesNothing() throws Exception {
        freshDay();
        long m = member();
        List<Long> lib = payLibrary(5, m);
        JsonNode pod = requireQuest(today(m), "PHRASE_OF_DAY");
        long p = pod.path("phrase").path("id").asLong();
        long other = lib.stream().filter(id -> id != p).findFirst().orElseThrow();

        SoftAssertions s = new SoftAssertions();
        JsonNode wrong = listen(m, other);
        s.assertThat(wrong.path("changed").size()).as("wrong phrase: changed empty").isZero();
        s.assertThat(wrong.path("justCompleted").size()).isZero();
        s.assertThat(wrong.path("coinsAwarded").asInt()).isZero();
        JsonNode unknown = listen(m, 999_999_999L);
        s.assertThat(unknown.path("changed").size()).as("unknown phrase: changed empty").isZero();

        JsonNode first = listen(m, p);
        s.assertThat(first.path("coinsAwarded").asInt()).isEqualTo(10);
        for (int i = 0; i < 3; i++) {
            clock.advance(GAP);
            JsonNode again = listen(m, p);
            s.assertThat(again.path("changed").size()).as("repeat " + i + ": changed empty").isZero();
            s.assertThat(again.path("justCompleted").size()).isZero();
            s.assertThat(again.path("coinsAwarded").asInt()).isZero();
            s.assertThat(again.path("wallet").path("coins").asInt()).isEqualTo(10);
        }
        s.assertThat(coinsOf(m)).isEqualTo(10);
        s.assertThat(count("SELECT COUNT(*) FROM coin_transactions WHERE user_id = ?", m)).isEqualTo(1);
        s.assertAll();
    }

    @Test
    void listenOnADayWithoutPhraseOfTheDayQuestChangesNothing() throws Exception {
        freshDay();
        long m = member();
        Vocab v = vocabLibrary(5, 4, 1, m);
        JsonNode t = today(m);
        assertThat(quest(t, "PHRASE_OF_DAY")).isNull();
        JsonNode qc = listen(m, v.none().get(0));
        assertThat(qc.path("changed").size()).isZero();
        assertThat(qc.path("coinsAwarded").asInt()).isZero();
    }

    @Test
    void moreThan30EventsAMinuteAnswer429() throws Exception {
        freshDay();
        long m = member();
        payLibrary(5, m);
        SoftAssertions s = new SoftAssertions();
        for (int i = 0; i < 30; i++) {
            s.assertThat(status(listenRaw(m, 999_999_999L))).as("event " + (i + 1)).isEqualTo(200);
        }
        s.assertThat(status(listenRaw(m, 999_999_999L))).as("event 31 within the minute").isEqualTo(429);
        s.assertAll();
    }

    // ------------------------------------------------------------ backward compatibility

    @Test
    void flashAndUnmarkKeepTheirOldAnswers() throws Exception {
        freshDay();
        long m = member();
        List<Long> lib = payLibrary(5, m);
        today(m);

        SoftAssertions s = new SoftAssertions();
        clock.advance(GAP);
        JsonNode learned = json(flash(m, lib.get(0), true));
        s.assertThat(learned.path("message").asText()).startsWith("Đã lưu: Đã thuộc.");
        s.assertThat(learned.path("data").path("learning").isBoolean()).isTrue();
        s.assertThat(learned.path("data").path("learned").asBoolean()).isTrue();
        clock.advance(GAP);
        JsonNode learning = json(flash(m, lib.get(1), false));
        s.assertThat(learning.path("message").asText()).startsWith("Đã lưu: Chưa thuộc.");
        s.assertThat(learning.path("data").path("learning").asBoolean()).isTrue();
        s.assertThat(learning.path("data").path("learned").asBoolean()).isFalse();

        clock.advance(GAP);
        JsonNode unmark = json(deleteAs(m, "/api/phrases/" + lib.get(0) + "/flash"));
        s.assertThat(unmark.path("success").asBoolean()).isTrue();
        s.assertThat(unmark.path("message").asText()).isEqualTo("Đã bỏ đánh dấu.");
        s.assertThat(keys(unmark.path("data"))).as("DELETE flash data unchanged").containsExactlyInAnyOrder("learning",
                "learned");
        s.assertThat(unmark.path("data").path("learning").asBoolean()).isFalse();
        s.assertThat(unmark.path("data").path("learned").asBoolean()).isFalse();
        JsonNode t = today(m);
        s.assertThat(progress(t, "FLASH_REVIEW")).isEqualTo(2);
        s.assertThat(progress(t, "TOPIC")).isEqualTo(2);
        s.assertAll();
    }

    /** Every field of the pre-feature records (git HEAD: HomeDtos, ProfileDtos) is still there, plus the new ones. */
    @Test
    void homeAndProfileKeepEveryOldField() throws Exception {
        freshDay();
        long m = member();
        payLibrary(5, m);

        SoftAssertions s = new SoftAssertions();
        JsonNode guest = ok(guestGet("/api/home"));
        s.assertThat(keys(guest)).as("GET /api/home").containsAll(List.of("stats", "quotes", "phrase"));
        s.assertThat(keys(guest.path("phrase"))).as("GET /api/home phrase").containsAll(List.of("id", "japanese",
                "romaji", "meaningVi", "categoryId", "categoryName", "categoryIcon", "topicId"));
        s.assertThat(keys(guest.path("stats"))).containsAll(List.of("placesWithReviews", "reviews", "phrases"));

        JsonNode home = ok(getAs(m, "/api/me/home"));
        s.assertThat(keys(home)).as("GET /api/me/home").containsAll(List.of("displayName", "stage", "areaId",
                "areaName", "points", "rankName", "phrase", "guide", "events", "reviews", "featured", "forYou",
                "interests", "chosenInterests", "stageGuides", "quests"));
        s.assertThat(keys(home.path("phrase"))).as("GET /api/me/home phrase").containsAll(List.of("id", "japanese",
                "romaji", "meaningVi", "categoryId", "categoryName", "categoryIcon", "topicId"));

        JsonNode profile = ok(getAs(m, "/api/me/profile"));
        s.assertThat(keys(profile)).as("GET /api/me/profile").containsAll(List.of("id", "email", "displayName",
                "avatarUrl", "role", "roleLabel", "points", "ranks", "tags", "pendingTag", "areaId", "areaName", "stage",
                "createdAt", "accountAgeDays", "newAccount", "newAccountDays", "today", "reportMutedUntil",
                "hasPassword", "linkedProviders", "savedCount", "coins"));
        s.assertThat(keys(profile.path("today"))).containsAll(List.of("posts", "maxPosts", "pins", "maxPins", "videos",
                "maxVideos"));
        s.assertAll();
    }

    // ------------------------------------------------------------ 3.4 /quests

    @Test
    void theQuestsAddressMovesPermanentlyToPhrases() throws Exception {
        freshDay();
        long m = member();
        SoftAssertions s = new SoftAssertions();
        var guest = mvc.perform(get("/quests")).andReturn().getResponse();
        s.assertThat(guest.getStatus()).as("guest GET /quests").isEqualTo(301);
        s.assertThat(guest.getHeader("Location")).isEqualTo("/phrases");
        var member = mvc.perform(get("/quests").session(session(m, "USER"))).andReturn().getResponse();
        s.assertThat(member.getStatus()).as("member GET /quests").isEqualTo(301);
        s.assertThat(member.getHeader("Location")).isEqualTo("/phrases");
        s.assertAll();
    }

    // ------------------------------------------------------------ 3.9 / 5.9 test API switched off (tests: flag false)

    @Test
    void theTestApiAnswers404WhenSwitchedOff() throws Exception {
        freshDay();
        long m = member();
        payLibrary(5, m);
        today(m);
        SoftAssertions s = new SoftAssertions();
        s.assertThat(status(deleteAs(m, "/api/dev/quests/today"))).as("DELETE today").isEqualTo(404);
        s.assertThat(status(postAs(m, "/api/dev/quests/history", "{\"doneDays\":6}"))).as("POST history").isEqualTo(404);
        s.assertThat(status(putAs(m, "/api/dev/quests/today", "{\"codes\":[\"FLASH_REVIEW\"]}"))).as("PUT today")
                .isEqualTo(404);
        s.assertThat(status(putAs(m, "/api/dev/coins", "{\"coins\":600,\"clearMonthExchanges\":true}")))
                .as("PUT coins").isEqualTo(404);
        s.assertThat(status(getAs(m, "/api/dev/quests/today"))).as("GET").isEqualTo(404);
        s.assertThat(status(guestGet("/api/dev/quests/today"))).as("guest GET").isEqualTo(404);
        s.assertThat(coinsOf(m)).isZero();
        s.assertThat(count("SELECT COUNT(*) FROM user_daily_quests WHERE user_id = ?", m)).isEqualTo(3);
        s.assertThat(count("SELECT COUNT(*) FROM user_quest_days WHERE user_id = ?", m)).isZero();
        s.assertAll();
    }

    // ------------------------------------------------------------ section 4 "Xóa tài khoản"

    @Test
    void deletingTheAccountEmptiesXuAndQuestDataButKeepsTheLedger() throws Exception {
        LocalDate day = freshDay();
        long m = member();
        List<Long> lib = payLibrary(5, m);
        seedDoneDay(m, day.minusDays(1), 1);
        JsonNode pod = requireQuest(today(m), "PHRASE_OF_DAY");
        listen(m, pod.path("phrase").path("id").asLong());
        answer(m, lib.get(0), true);
        int ledger = count("SELECT COUNT(*) FROM coin_transactions WHERE user_id = ?", m);
        assertThat(coinsOf(m)).isEqualTo(10);

        clock.advance(GAP);
        ok(deleteAs(m, "/api/me"));

        SoftAssertions s = new SoftAssertions();
        s.assertThat(coinsOf(m)).as("users.coins").isZero();
        s.assertThat(count("SELECT COUNT(*) FROM user_daily_quests WHERE user_id = ?", m)).isZero();
        s.assertThat(count("""
                SELECT COUNT(*) FROM user_quest_items i JOIN phrases p ON p.id = i.phrase_id
                WHERE i.phrase_id IN (?, ?, ?, ?, ?)""", lib.toArray())).isZero();
        s.assertThat(count("SELECT COUNT(*) FROM user_quest_days WHERE user_id = ?", m)).isZero();
        s.assertThat(count("SELECT COUNT(*) FROM phrase_answer_log WHERE user_id = ?", m)).isZero();
        s.assertThat(count("SELECT COUNT(*) FROM coin_transactions WHERE user_id = ?", m)).as("ledger kept")
                .isEqualTo(ledger);
        s.assertAll();
    }

    // ------------------------------------------------------------ helpers

    private static int status(ResultActions r) {
        return r.andReturn().getResponse().getStatus();
    }

    private static void expect403(SoftAssertions s, String what, ResultActions r) throws Exception {
        JsonNode body = json(r);
        s.assertThat(status(r)).as(what + " status; body " + body).isEqualTo(403);
        s.assertThat(body.path("message").asText(null)).as(what + " message").isEqualTo(MSG18);
    }

    private static void expectFieldError(SoftAssertions s, ResultActions r, String field, String what)
            throws Exception {
        JsonNode body = json(r);
        s.assertThat(status(r)).as(what + " status; body " + body).isEqualTo(400);
        s.assertThat(body.path("success").asBoolean(true)).as(what + " success").isFalse();
        s.assertThat(body.path("data").path(field).isTextual() && !body.path("data").path(field).asText().isBlank())
                .as(what + ": data." + field + " holds the message; body " + body).isTrue();
    }
}
