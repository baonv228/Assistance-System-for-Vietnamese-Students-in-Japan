package com.sep490.vsa.feature.quest;

import com.sep490.vsa.common.security.AppUserPrincipal;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.AfterEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicReference;

import static org.springframework.security.web.context.HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * Base of the "Nhiệm vụ hằng ngày" / "xu" flow tests: MySQL ({@code asvsj_quest_test}, made by Flyway on the first run),
 * MockMvc with sessions and the CSRF header, and an application clock the test sets and moves by hand
 * ({@link MovableClock}, shared by every thread).
 * <p>
 * Isolation: every test starts on a fresh, random Japan day far in the future ({@link #freshDay}), so rows left by
 * earlier runs (daily_phrases of a date, quests of a day) never meet it. Users, phrases and topics made through the
 * helpers are deleted afterwards (their quests, marks, xu and points go with them by cascade). {@link #isolateLibrary}
 * hides every other phrase for the test (soft delete, put back afterwards), so the library is exactly what the test
 * made. A subclass may point at its own database with {@code @TestPropertySource(properties = "spring.datasource.url=…")}.
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:mysql://localhost:3306/asvsj_quest_test?createDatabaseIfNotExist=true&useUnicode=true&characterEncoding=utf8&connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true",
        "app.media.local-dir=target/test-uploads",
        "app.moderation.retry-initial-delay-ms=3600000",
        "app.places.osm.enabled=false"
})
@AutoConfigureMockMvc
@ActiveProfiles("local")
@Import(QuestTestSupport.ClockConfig.class)
public abstract class QuestTestSupport {

    public static final String CSRF = "test-csrf-token";
    public static final ZoneId JAPAN = ZoneId.of("Asia/Tokyo");

    @TestConfiguration
    public static class ClockConfig {
        @Bean
        @Primary
        public MovableClock movableClock() {
            return new MovableClock();
        }
    }

    @Autowired
    protected MockMvc mvc;
    @Autowired
    protected JdbcTemplate jdbc;
    @Autowired
    protected MovableClock clock;
    @Autowired
    protected PlatformTransactionManager transactionManager;

    private final List<Long> users = new ArrayList<>();
    private final List<Long> phrases = new ArrayList<>();
    private final List<String> topics = new ArrayList<>();
    private final List<Long> hidden = new ArrayList<>();
    private Long writer;

    @AfterEach
    void cleanUpQuestFixtures() {
        if (!hidden.isEmpty()) {
            for (long id : hidden) {
                jdbc.update("UPDATE phrases SET deleted_at = NULL WHERE id = ?", id);
            }
            hidden.clear();
        }
        for (long id : phrases) {
            jdbc.update("DELETE FROM phrases WHERE id = ?", id);
        }
        phrases.clear();
        for (String id : topics) {
            jdbc.update("DELETE FROM phrase_topic_links WHERE topic_id = ?", id);
            jdbc.update("DELETE FROM learning_topics WHERE id = ?", id);
        }
        topics.clear();
        for (long id : users) {
            jdbc.update("DELETE FROM point_transactions WHERE user_id = ?", id);
            jdbc.update("DELETE FROM notifications WHERE user_id = ?", id);
            jdbc.update("DELETE FROM users WHERE id = ?", id);
        }
        users.clear();
        writer = null;
    }

    // ---------------------------------------------------------------- time

    /** Sets the clock to noon (Japan time) of a random day between 2100 and 2400 and returns that Japan date. */
    protected LocalDate freshDay() {
        LocalDate day = LocalDate.of(2100, 1, 1).plusDays(ThreadLocalRandom.current().nextInt(0, 300 * 365));
        setJapanTime(day, LocalTime.NOON);
        return day;
    }

    /** Sets the clock to this Japan date and time. */
    protected void setJapanTime(LocalDate day, LocalTime time) {
        clock.set(day.atTime(time).atZone(JAPAN).toInstant());
    }

    /** The Japan date of the clock. */
    protected LocalDate japanToday() {
        return LocalDate.now(clock.withZone(JAPAN));
    }

    // ---------------------------------------------------------------- people

    /** A Member (role USER, ACTIVE) made for this test. */
    protected long member() {
        return user("USER", "ACTIVE");
    }

    protected long user(String role, String status) {
        String email = "quest-" + UUID.randomUUID() + "@test.local";
        jdbc.update("""
                INSERT INTO users (email, display_name, role, status, is_adult_confirmed, created_at)
                VALUES (?, 'Quest Test', ?, ?, TRUE, ?)""",
                email, role, status, Timestamp.from(Instant.now().minus(Duration.ofDays(30))));
        long id = jdbc.queryForObject("SELECT id FROM users WHERE email = ?", Long.class, email);
        users.add(id);
        return id;
    }

    protected void setStage(long userId, String stage) {
        jdbc.update("UPDATE users SET stage = ? WHERE id = ?", stage, userId);
    }

    protected int coinsOf(long userId) {
        return jdbc.queryForObject("SELECT coins FROM users WHERE id = ?", Integer.class, userId);
    }

    protected int pointsOf(long userId) {
        return jdbc.queryForObject("SELECT points FROM users WHERE id = ?", Integer.class, userId);
    }

    public static MockHttpSession session(long userId, String role) {
        AppUserPrincipal me = new AppUserPrincipal(userId, "u" + userId + "@test.local", "Quest Test", role, "/");
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(SPRING_SECURITY_CONTEXT_KEY, new SecurityContextImpl(new UsernamePasswordAuthenticationToken(
                me, null, List.of(new SimpleGrantedAuthority("ROLE_" + role)))));
        return session;
    }

    // ---------------------------------------------------------------- library

    /** A learning topic made for this test (id "qt" + random). */
    protected String topic(String name) {
        String id = "qt" + Long.toString(ThreadLocalRandom.current().nextLong(1L << 40), 36);
        jdbc.update("INSERT INTO learning_topics (id, name, icon) VALUES (?, ?, 'label')", id, name);
        topics.add(id);
        return id;
    }

    /** A phrase or word (kind PHRASE / VOCAB) of a place category (e.g. "konbini"), linked to these topics. */
    protected long phrase(String categoryId, String kind, String... topicIds) {
        String japanese = "テスト" + UUID.randomUUID().toString().substring(0, 8);
        jdbc.update("""
                INSERT INTO phrases (category_id, kind, japanese, romaji, meaning_vi, created_by)
                VALUES (?, ?, ?, 'tesuto', 'Câu thử', ?)""", categoryId, kind, japanese, writer());
        long id = jdbc.queryForObject("SELECT id FROM phrases WHERE japanese = ?", Long.class, japanese);
        for (String topicId : topicIds) {
            jdbc.update("INSERT INTO phrase_topic_links (phrase_id, topic_id) VALUES (?, ?)", id, topicId);
        }
        phrases.add(id);
        return id;
    }

    /** {@code count} phrases of kind PHRASE in "konbini" linked to these topics. */
    protected List<Long> phrases(int count, String... topicIds) {
        List<Long> ids = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            ids.add(phrase("konbini", "PHRASE", topicIds));
        }
        return ids;
    }

    /**
     * Hides (soft-deletes) every phrase not made by this test until it ends, so the library is exactly the test's own.
     * Call it before making the day's quests.
     */
    protected void isolateLibrary() {
        List<Long> visible = jdbc.queryForList("SELECT id FROM phrases WHERE deleted_at IS NULL", Long.class);
        for (long id : visible) {
            if (!phrases.contains(id)) {
                jdbc.update("UPDATE phrases SET deleted_at = ? WHERE id = ?", Timestamp.from(Instant.now()), id);
                hidden.add(id);
            }
        }
    }

    /** Sets a flash card mark directly: true = "Đã thuộc", false = "Chưa thuộc", null = none (no row). */
    protected void mark(long userId, long phraseId, Boolean learned) {
        jdbc.update("DELETE FROM user_phrase_marks WHERE user_id = ? AND phrase_id = ?", userId, phraseId);
        if (learned != null) {
            jdbc.update("INSERT INTO user_phrase_marks (user_id, phrase_id, is_learned, is_learning) VALUES (?, ?, ?, ?)",
                    userId, phraseId, learned, !learned);
        }
    }

    private long writer() {
        if (writer == null) {
            writer = user("ACADEMIC_MANAGER", "ACTIVE");
        }
        return writer;
    }

    // ---------------------------------------------------------------- requests

    protected ResultActions getAs(long userId, String url) throws Exception {
        return getAs(userId, "USER", url);
    }

    protected ResultActions getAs(long userId, String role, String url) throws Exception {
        return mvc.perform(get(url).session(session(userId, role)));
    }

    protected ResultActions guestGet(String url) throws Exception {
        return mvc.perform(get(url));
    }

    protected ResultActions postAs(long userId, String url, String json) throws Exception {
        return postAs(userId, "USER", url, json);
    }

    protected ResultActions postAs(long userId, String role, String url, String json) throws Exception {
        return mvc.perform(post(url).session(session(userId, role))
                .cookie(new Cookie("XSRF-TOKEN", CSRF)).header("X-XSRF-TOKEN", CSRF)
                .contentType(MediaType.APPLICATION_JSON).content(json == null ? "{}" : json));
    }

    protected ResultActions guestPost(String url, String json) throws Exception {
        return mvc.perform(post(url).cookie(new Cookie("XSRF-TOKEN", CSRF)).header("X-XSRF-TOKEN", CSRF)
                .contentType(MediaType.APPLICATION_JSON).content(json == null ? "{}" : json));
    }

    protected ResultActions putAs(long userId, String url, String json) throws Exception {
        return mvc.perform(put(url).session(session(userId, "USER"))
                .cookie(new Cookie("XSRF-TOKEN", CSRF)).header("X-XSRF-TOKEN", CSRF)
                .contentType(MediaType.APPLICATION_JSON).content(json == null ? "{}" : json));
    }

    protected ResultActions deleteAs(long userId, String url) throws Exception {
        return mvc.perform(delete(url).session(session(userId, "USER"))
                .cookie(new Cookie("XSRF-TOKEN", CSRF)).header("X-XSRF-TOKEN", CSRF));
    }

    /** A flash card answer through the API ({@code learned} true = "Đã thuộc"). */
    protected ResultActions flash(long userId, long phraseId, boolean learned) throws Exception {
        return postAs(userId, "/api/phrases/" + phraseId + "/flash", "{\"learned\":" + learned + "}");
    }

    protected static String body(ResultActions result) throws Exception {
        return result.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    /** Runs code in a read-write transaction (services with Propagation.MANDATORY, e.g. CoinLedger.add). */
    protected <T> T inTransaction(java.util.function.Supplier<T> work) {
        return new TransactionTemplate(transactionManager).execute(status -> work.get());
    }

    // ---------------------------------------------------------------- clock

    /** The application clock in these tests: set and moved by hand, shared by every thread. */
    public static final class MovableClock extends Clock {

        private final AtomicReference<Instant> now;
        private final ZoneId zone;

        public MovableClock() {
            this(new AtomicReference<>(Instant.now()), ZoneOffset.UTC);
        }

        private MovableClock(AtomicReference<Instant> now, ZoneId zone) {
            this.now = now;
            this.zone = zone;
        }

        public void set(Instant instant) {
            now.set(instant);
        }

        public void advance(Duration duration) {
            now.updateAndGet(i -> i.plus(duration));
        }

        @Override
        public ZoneId getZone() {
            return zone;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return new MovableClock(now, zone);
        }

        @Override
        public Instant instant() {
            return now.get();
        }
    }
}
