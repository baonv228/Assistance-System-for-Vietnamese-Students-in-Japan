package com.sep490.vsa.feature.home;

import com.jayway.jsonpath.JsonPath;
import com.sep490.vsa.common.exception.ApiException;
import com.sep490.vsa.common.security.AppUserPrincipal;
import com.sep490.vsa.feature.home.HomeDtos.InterestSettings;
import com.sep490.vsa.feature.home.HomeDtos.InterestTopic;
import com.sep490.vsa.feature.home.InterestService.Interest;
import com.sep490.vsa.feature.user.ProfileService;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.hamcrest.Matchers.contains;
import static org.springframework.security.web.context.HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * "Chủ đề quan tâm" a Member chooses ({@code /api/me/interests}, table {@code user_interest_topics}), end to end
 * against MySQL ({@code asvsj_test}). Not one transaction: the saves commit, as two saves at once must; every test
 * deletes its members (their choices go with them) and posts afterwards. How the choice ranks "Dành cho bạn" is
 * checked in HomeFeedFlowTest.
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:mysql://localhost:3306/asvsj_test?createDatabaseIfNotExist=true&useUnicode=true&characterEncoding=utf8&connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true",
        "app.media.local-dir=target/test-uploads",
        "app.moderation.retry-initial-delay-ms=3600000",
        "app.places.osm.enabled=false"
})
@AutoConfigureMockMvc
@ActiveProfiles("local")
class InterestFlowTest {

    private static final String CSRF = "test-csrf-token";

    @Autowired
    private MockMvc mvc;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private InterestService interests;
    @Autowired
    private HomeService home;
    @Autowired
    private ProfileService profiles;

    private final List<Long> users = new ArrayList<>();
    private long member;
    private long author;

    @BeforeEach
    void setUp() {
        member = user("USER", "ACTIVE");
        author = user("USER", "ACTIVE");
    }

    @AfterEach
    void cleanUp() {
        jdbc.update("UPDATE blog_topics SET is_active = TRUE WHERE name IN ('Nhà ở', 'Y tế')");
        for (long id : users) {
            jdbc.update("DELETE FROM blogs WHERE author_id = ?", id);
        }
        for (long id : users) {
            jdbc.update("DELETE FROM users WHERE id = ?", id);
        }
    }

    @Test
    void aNewMemberSeesEveryTopicOfferedNoneChosen() throws Exception {
        String body = getInterests(member).andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsString(StandardCharsets.UTF_8);
        assertThat((Integer) JsonPath.read(body, "$.data.max")).isEqualTo(InterestService.CHOSEN_MAX);
        List<String> names = JsonPath.read(body, "$.data.topics[*].name");
        // Every active topic but "Khác", in the Blog's order
        assertThat(names).isEqualTo(jdbc.queryForList(
                "SELECT name FROM blog_topics WHERE is_active AND NOT is_other ORDER BY sort_order, name", String.class));
        assertThat(names).doesNotContain("Khác").contains("Nhà ở", "Học tiếng Nhật");
        assertThat((List<?>) JsonPath.read(body, "$.data.topics[?(@.chosen == true)]")).isEmpty();
        assertThat((List<?>) JsonPath.read(body, "$.data.topics[?(@.learnt == true)]")).isEmpty();
        assertThat(interests.interests(member)).isEmpty();
    }

    @Test
    void savingReplacesTheWholeChoiceAndKeepsTheDateOfTopicsKept() throws Exception {
        long housing = topic("Nhà ở");
        long japanese = topic("Học tiếng Nhật");
        long health = topic("Y tế");
        putInterests(member, List.of(japanese, housing, japanese))          // a repeat counts once
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Đã lưu chủ đề quan tâm."))
                .andExpect(jsonPath("$.data.topics[?(@.chosen == true)].name").value(contains("Nhà ở", "Học tiếng Nhật")));
        assertThat(chosenIds(member)).containsExactlyInAnyOrder(housing, japanese);
        // The Blog's topic order, whatever order they were picked in
        assertThat(interests.interests(member)).extracting(Interest::name).containsExactly("Nhà ở", "Học tiếng Nhật");
        assertThat(interests.interests(member)).allMatch(Interest::chosen);

        // DATETIME keeps whole seconds
        Timestamp first = Timestamp.from(Instant.now().minus(Duration.ofDays(3)).truncatedTo(ChronoUnit.SECONDS));
        jdbc.update("UPDATE user_interest_topics SET created_at = ? WHERE user_id = ?", first, member);
        putInterests(member, List.of(housing, health)).andExpect(status().isOk());
        assertThat(chosenIds(member)).containsExactlyInAnyOrder(housing, health);
        assertThat(jdbc.queryForObject("SELECT created_at FROM user_interest_topics WHERE user_id = ? AND topic_id = ?",
                Timestamp.class, member, housing)).isEqualTo(first);

        String body = getInterests(member).andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsString(StandardCharsets.UTF_8);
        assertThat((List<String>) JsonPath.read(body, "$.data.topics[?(@.chosen == true)].name"))
                .containsExactly("Nhà ở", "Y tế");

        // An empty choice clears it
        putInterests(member, List.of()).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.topics[?(@.chosen == true)]").isEmpty());
        assertThat(chosenIds(member)).isEmpty();
    }

    @Test
    void atMostFiveOfferedTopicsAndNothingChangesOnARefusal() throws Exception {
        List<Long> offered = jdbc.queryForList(
                "SELECT id FROM blog_topics WHERE is_active AND NOT is_other ORDER BY sort_order LIMIT 6", Long.class);
        assertThat(offered).hasSize(6);
        putInterests(member, offered.subList(0, 2)).andExpect(status().isOk());

        // Six different topics
        putInterests(member, offered).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(InterestService.TOO_MANY));
        // Five different ones written six times: fine
        List<Long> five = new ArrayList<>(offered.subList(0, 5));
        five.add(offered.get(0));
        putInterests(member, five).andExpect(status().isOk());
        assertThat(chosenIds(member)).containsExactlyInAnyOrderElementsOf(offered.subList(0, 5));
        putInterests(member, offered.subList(0, 2)).andExpect(status().isOk());

        // "Khác", a switched-off topic and an id that does not exist are never offered
        long other = jdbc.queryForObject("SELECT id FROM blog_topics WHERE is_other LIMIT 1", Long.class);
        long missing = jdbc.queryForObject("SELECT MAX(id) + 1000 FROM blog_topics", Long.class);
        jdbc.update("UPDATE blog_topics SET is_active = FALSE WHERE name = 'Y tế'");
        for (long bad : List.of(other, missing, topic("Y tế"))) {
            putInterests(member, List.of(offered.get(0), bad)).andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.message").value(InterestService.NOT_OFFERED));
        }
        // No list, or a hole in it
        for (String json : List.of("{}", "{\"topicIds\":null}", "{\"topicIds\":[" + offered.get(0) + ",null]}")) {
            putJson(member, json).andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.message").value(InterestService.INVALID_LIST));
        }
        putJson(member, "{\"topicIds\":[\"abc\"]}").andExpect(status().isBadRequest());
        assertThat(chosenIds(member)).containsExactlyInAnyOrderElementsOf(offered.subList(0, 2));
    }

    @Test
    void aTopicSwitchedOffIsNeitherOfferedNorReadUntilSwitchedOnAgain() throws Exception {
        long housing = topic("Nhà ở");
        long japanese = topic("Học tiếng Nhật");
        putInterests(member, List.of(housing, japanese)).andExpect(status().isOk());

        jdbc.update("UPDATE blog_topics SET is_active = FALSE WHERE name = 'Nhà ở'");
        String body = getInterests(member).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat((List<String>) JsonPath.read(body, "$.data.topics[*].name")).doesNotContain("Nhà ở");
        assertThat(interests.interests(member)).extracting(Interest::name).containsExactly("Học tiếng Nhật");
        assertThat(home.member(member).chosenInterests()).containsExactly("Học tiếng Nhật");

        jdbc.update("UPDATE blog_topics SET is_active = TRUE WHERE name = 'Nhà ở'");
        assertThat(interests.interests(member)).extracting(Interest::name).containsExactly("Nhà ở", "Học tiếng Nhật");

        // Saving while it is off drops it: the page no longer showed it
        jdbc.update("UPDATE blog_topics SET is_active = FALSE WHERE name = 'Nhà ở'");
        putInterests(member, List.of(japanese)).andExpect(status().isOk());
        assertThat(chosenIds(member)).containsExactly(japanese);
    }

    @Test
    void learntTopicsAreSuggestionsNeverAlsoChosenAndTheNextOneMovesUp() throws Exception {
        Instant now = Instant.now();
        // Learnt from what the member did: "Ăn uống" 6, "Y tế" 4, "Đi lại" 3, "Mua sắm" 2
        save(member, post("Ăn uống", now), now);
        save(member, post("Ăn uống", now), now);
        save(member, post("Ăn uống", now), now);
        save(member, post("Y tế", now), now);
        save(member, post("Y tế", now), now);
        save(member, post("Đi lại", now), now);
        like(member, post("Đi lại", now), now);
        save(member, post("Mua sắm", now), now);

        InterestSettings before = interests.settings(member);
        assertThat(flagged(before, true)).isEmpty();
        assertThat(learnt(before)).containsExactlyInAnyOrder("Ăn uống", "Y tế", "Đi lại");
        assertThat(interests.interests(member)).extracting(Interest::name).containsExactly("Ăn uống", "Y tế", "Đi lại");

        // Choosing a learnt topic and another one: chosen first (Blog order); the next learnt topic moves up
        InterestSettings after = interests.update(member, List.of(topic("Y tế"), topic("Học tiếng Nhật")));
        assertThat(flagged(after, true)).containsExactly("Học tiếng Nhật", "Y tế");
        assertThat(learnt(after)).containsExactlyInAnyOrder("Ăn uống", "Đi lại", "Mua sắm");
        assertThat(after.topics()).noneMatch(t -> t.chosen() && t.learnt());
        assertThat(interests.interests(member)).extracting(Interest::name, Interest::chosen).containsExactly(
                tuple("Học tiếng Nhật", true), tuple("Y tế", true),
                tuple("Ăn uống", false), tuple("Đi lại", false), tuple("Mua sắm", false));
        // The Home names them in that order, the chosen ones apart
        var memberHome = home.member(member);
        assertThat(memberHome.interests()).containsExactly("Học tiếng Nhật", "Y tế", "Ăn uống", "Đi lại", "Mua sắm");
        assertThat(memberHome.chosenInterests()).containsExactly("Học tiếng Nhật", "Y tế");
    }

    @Test
    void guestsSignInStaffAndLockedAccountsMayNot() throws Exception {
        mvc.perform(get("/api/me/interests")).andExpect(status().isUnauthorized());
        mvc.perform(put("/api/me/interests").cookie(new Cookie("XSRF-TOKEN", CSRF)).header("X-XSRF-TOKEN", CSRF)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"topicIds\":[]}"))
                .andExpect(status().isUnauthorized());
        // Without the CSRF header: refused before the application runs
        mvc.perform(put("/api/me/interests").session(session(member, "USER"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"topicIds\":[]}"))
                .andExpect(status().isForbidden());

        long moderator = user("MODERATOR", "ACTIVE");
        getInterests(moderator, "MODERATOR").andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value(InterestService.NO_PERMISSION));
        putJson(moderator, "MODERATOR", "{\"topicIds\":[" + topic("Nhà ở") + "]}").andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value(InterestService.NO_PERMISSION));
        assertThat(chosenIds(moderator)).isEmpty();

        // A locked account's session ends at its next request (401); the service itself refuses it too
        long locked = user("USER", "LOCKED");
        getInterests(locked).andExpect(status().isUnauthorized());
        assertThatThrownBy(() -> interests.update(locked, List.of(topic("Nhà ở"))))
                .isInstanceOf(ApiException.class).hasMessage(InterestService.NO_PERMISSION);
        assertThatThrownBy(() -> interests.settings(locked))
                .isInstanceOf(ApiException.class).hasMessage(InterestService.NO_PERMISSION);
        assertThat(chosenIds(locked)).isEmpty();
    }

    @Test
    void deletingTheAccountForgetsTheChoice() throws Exception {
        putInterests(member, List.of(topic("Nhà ở"), topic("Y tế"))).andExpect(status().isOk());
        assertThat(chosenIds(member)).hasSize(2);
        mvc.perform(delete("/api/me").session(session(member, "USER"))
                        .cookie(new Cookie("XSRF-TOKEN", CSRF)).header("X-XSRF-TOKEN", CSRF))
                .andExpect(status().isOk());
        assertThat(chosenIds(member)).isEmpty();
        assertThat(jdbc.queryForObject("SELECT status FROM users WHERE id = ?", String.class, member))
                .isEqualTo("DELETED");
    }

    @Test
    void twoSavesAtOnceEndWithOneWholeChoice() throws Exception {
        List<Long> a = List.of(topic("Nhà ở"), topic("Y tế"), topic("Đi lại"));
        List<Long> b = List.of(topic("Học tiếng Nhật"), topic("Ăn uống"));
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            for (int round = 0; round < 15; round++) {
                CountDownLatch start = new CountDownLatch(1);
                Future<InterestSettings> first = pool.submit(() -> {
                    start.await();
                    return interests.update(member, a);
                });
                Future<InterestSettings> second = pool.submit(() -> {
                    start.await();
                    return interests.update(member, b);
                });
                start.countDown();
                first.get(20, TimeUnit.SECONDS);       // neither fails nor deadlocks
                second.get(20, TimeUnit.SECONDS);
                List<Long> now = chosenIds(member);
                assertThat(now).doesNotHaveDuplicates();
                assertThat(Set.copyOf(now)).isIn(Set.copyOf(a), Set.copyOf(b));
            }
        } finally {
            pool.shutdownNow();
        }
    }

    /**
     * Whichever runs first, no choice is left and neither call fails. Both lock the users row before the choice rows
     * (ProfileService.deleteAccount deletes them after its users UPDATE), which is what keeps them from deadlocking;
     * the window for the opposite order is too narrow for this test to hit it reliably.
     */
    @Test
    void deletingTheAccountWhileSavingTopicsLeavesNoChoice() throws Exception {
        List<Long> topics = List.of(topic("Nhà ở"), topic("Y tế"));
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            for (int round = 0; round < 10; round++) {
                long leaving = user("USER", "ACTIVE");
                interests.update(leaving, List.of(topic("Đi lại")));          // rows the deletion removes
                CountDownLatch start = new CountDownLatch(1);
                Future<?> deletion = pool.submit(() -> {
                    start.await();
                    profiles.deleteAccount(leaving);
                    return null;
                });
                Future<?> save = pool.submit(() -> {
                    start.await();
                    try {
                        interests.update(leaving, topics);
                    } catch (ApiException afterDeletion) {        // waited for the deletion, then saw DELETED
                        assertThat(afterDeletion).hasMessage(InterestService.NO_PERMISSION);
                    }
                    return null;
                });
                start.countDown();
                deletion.get(20, TimeUnit.SECONDS);                // neither fails nor deadlocks
                save.get(20, TimeUnit.SECONDS);
                assertThat(chosenIds(leaving)).isEmpty();
                assertThat(jdbc.queryForObject("SELECT status FROM users WHERE id = ?", String.class, leaving))
                        .isEqualTo("DELETED");
            }
        } finally {
            pool.shutdownNow();
        }
    }

    // ---------------------------------------------------------------- helpers

    private long user(String role, String status) {
        String email = "interest-" + UUID.randomUUID() + "@test.local";
        jdbc.update("""
                INSERT INTO users (email, display_name, role, status, is_adult_confirmed, created_at)
                VALUES (?, 'Interest Test', ?, ?, TRUE, ?)""",
                email, role, status, Timestamp.from(Instant.now().minus(Duration.ofDays(30))));
        long id = jdbc.queryForObject("SELECT id FROM users WHERE email = ?", Long.class, email);
        users.add(id);
        return id;
    }

    private long topic(String name) {
        return jdbc.queryForObject("SELECT id FROM blog_topics WHERE name = ?", Long.class, name);
    }

    private List<Long> chosenIds(long userId) {
        return jdbc.queryForList("SELECT topic_id FROM user_interest_topics WHERE user_id = ?", Long.class, userId);
    }

    private static List<String> flagged(InterestSettings settings, boolean chosen) {
        return settings.topics().stream().filter(t -> chosen ? t.chosen() : t.learnt()).map(InterestTopic::name).toList();
    }

    private static List<String> learnt(InterestSettings settings) {
        return flagged(settings, false);
    }

    /** A published post of the other member with one topic. */
    private long post(String topicName, Instant at) {
        String title = "Interest " + UUID.randomUUID();
        jdbc.update("""
                INSERT INTO blogs (author_id, blog_type, title, content, status, created_at)
                VALUES (?, 'POST', ?, 'Nội dung', 'PUBLISHED', ?)""", author, title, Timestamp.from(at));
        long id = jdbc.queryForObject("SELECT id FROM blogs WHERE title = ?", Long.class, title);
        jdbc.update("INSERT INTO blog_topic_links (blog_id, topic_id) VALUES (?, ?)", id, topic(topicName));
        return id;
    }

    private void save(long userId, long blogId, Instant at) {
        jdbc.update("INSERT INTO saved_blogs (blog_id, user_id, created_at) VALUES (?, ?, ?)", blogId, userId,
                Timestamp.from(at));
    }

    private void like(long userId, long blogId, Instant at) {
        jdbc.update("INSERT INTO blog_likes (blog_id, user_id, created_at) VALUES (?, ?, ?)", blogId, userId,
                Timestamp.from(at));
    }

    private static MockHttpSession session(long userId, String role) {
        AppUserPrincipal me = new AppUserPrincipal(userId, "u" + userId + "@test.local", "Interest Test", role, "/");
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(SPRING_SECURITY_CONTEXT_KEY, new SecurityContextImpl(new UsernamePasswordAuthenticationToken(
                me, null, List.of(new SimpleGrantedAuthority("ROLE_" + role)))));
        return session;
    }

    private ResultActions getInterests(long userId) throws Exception {
        return getInterests(userId, "USER");
    }

    private ResultActions getInterests(long userId, String role) throws Exception {
        return mvc.perform(get("/api/me/interests").session(session(userId, role)));
    }

    private ResultActions putInterests(long userId, List<Long> topicIds) throws Exception {
        return putJson(userId, "{\"topicIds\":" + topicIds + "}");
    }

    private ResultActions putJson(long userId, String json) throws Exception {
        return putJson(userId, "USER", json);
    }

    private ResultActions putJson(long userId, String role, String json) throws Exception {
        return mvc.perform(put("/api/me/interests").session(session(userId, role))
                .cookie(new Cookie("XSRF-TOKEN", CSRF)).header("X-XSRF-TOKEN", CSRF)
                .contentType(MediaType.APPLICATION_JSON).content(json));
    }
}
