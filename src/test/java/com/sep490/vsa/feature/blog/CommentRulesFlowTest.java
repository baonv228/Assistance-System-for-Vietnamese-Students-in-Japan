package com.sep490.vsa.feature.blog;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jayway.jsonpath.JsonPath;
import com.sep490.vsa.common.exception.ApiException;
import com.sep490.vsa.common.security.AppUserPrincipal;
import com.sep490.vsa.feature.blog.BlogDetailDtos.CommentRequest;
import com.sep490.vsa.feature.moderation.ImageModerator;
import com.sep490.vsa.feature.moderation.ImageModerator.Verdict;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.nio.charset.StandardCharsets;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.security.web.context.HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Comment limits against spam (15 seconds, 20 a day for new accounts, the same text on many posts), the reply depth,
 * and deleting a comment on a post that was hidden meanwhile; end to end against MySQL ({@code asvsj_test}, like
 * CommentEditFlowTest). The application clock is a clock the tests set and move by hand, so the waits, the Japan
 * day and the 24 hours are exact. Every test makes its own users and posts and deletes them afterwards.
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:mysql://localhost:3306/asvsj_test?createDatabaseIfNotExist=true&useUnicode=true&characterEncoding=utf8&connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true",
        "app.media.local-dir=target/test-uploads",
        "app.moderation.retry-initial-delay-ms=3600000",
        "app.places.osm.enabled=false"
})
@AutoConfigureMockMvc
@ActiveProfiles("local")
class CommentRulesFlowTest {

    private static final String CSRF = "test-csrf-token";
    private static final ZoneId JAPAN = ZoneId.of("Asia/Tokyo");
    private static final String TOO_FAST = "Bạn bình luận hơi nhanh. Vui lòng đợi %d giây rồi thử lại.";
    private static final String DAILY_LIMIT = "Bạn đã đạt giới hạn bình luận trong ngày của tài khoản mới "
            + "(dưới 7 ngày: tối đa 20 bình luận mỗi ngày). Vui lòng thử lại vào ngày mai.";
    private static final String REPEATED = "Bạn đã gửi nội dung này ở nhiều bài khác. Hãy viết bình luận riêng cho bài này.";
    private static final String TOO_DEEP = "Chuỗi trả lời đã quá dài. Hãy trả lời một bình luận ở phía trên.";

    @TestConfiguration
    static class TestClock {
        @Bean
        @Primary
        MovableClock movableClock() {
            return new MovableClock();
        }
    }

    @Autowired
    private MockMvc mvc;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private MovableClock clock;
    @Autowired
    private BlogActionService actions;
    @MockBean
    private ImageModerator moderator;

    private final ObjectMapper json = new ObjectMapper();
    private final List<Long> users = new ArrayList<>();
    private long writer;

    @BeforeEach
    void setUp() {
        when(moderator.engine()).thenReturn("OPENAI");
        when(moderator.check(any(byte[].class))).thenReturn(new Verdict("PASS", "{}"));
        // Noon in Japan: every test starts at a known time of day, far from midnight
        clock.set(LocalDate.now(JAPAN).atTime(12, 0).atZone(JAPAN).toInstant());
        writer = user(30);
    }

    @AfterEach
    void cleanUp() {
        for (long id : users) {
            // Newest first, like "Xóa bài": a cascade down a long reply chain would stop at 15 levels
            jdbc.update("DELETE FROM comments WHERE blog_id IN (SELECT id FROM blogs WHERE author_id = ?) ORDER BY id DESC", id);
            jdbc.update("DELETE FROM blogs WHERE author_id = ?", id);
        }
        for (long id : users) {
            jdbc.update("DELETE FROM users WHERE id = ?", id);
        }
    }

    // ---------------------------------------------------------------- one comment every 15 seconds

    @Test
    void everyoneWritesOneNewCommentEvery15Seconds() throws Exception {
        long first = newPost("POST");
        long second = newPost("POST");
        long question = newPost("QA");
        long me = user(30);
        long other = user(30);

        comment(me, first, null, "Bình luận đầu tiên").andExpect(status().isOk());
        long mine = lastCommentOf(me);
        comment(me, second, null, "Bình luận thứ hai")
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.message").value(TOO_FAST.formatted(15)));

        clock.advance(Duration.ofSeconds(10));
        // Answers and replies count the same; the wait shrinks
        comment(me, question, null, "Câu trả lời").andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.message").value(TOO_FAST.formatted(5)));
        comment(me, first, mine, "Trả lời chính mình").andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.message").value(TOO_FAST.formatted(5)));
        // Editing is not a new comment
        mvc.perform(withCsrf(put("/api/comments/" + mine), me).contentType(MediaType.APPLICATION_JSON)
                        .content(body("Bình luận đầu tiên, đã sửa", null)))
                .andExpect(status().isOk());
        // Somebody else is not slowed down
        comment(other, second, null, "Mình cũng nghĩ vậy").andExpect(status().isOk());
        assertThat(commentsBy(me)).isEqualTo(1);

        clock.advance(Duration.ofSeconds(4));
        comment(me, second, null, "Bình luận thứ hai").andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.message").value(TOO_FAST.formatted(1)));
        clock.advance(Duration.ofSeconds(1));
        comment(me, second, null, "Bình luận thứ hai").andExpect(status().isOk());
        assertThat(commentsBy(me)).isEqualTo(2);
    }

    @Test
    void twoCommentsSentTogetherAreCountedOneAfterTheOther() throws Exception {
        long first = newPost("POST");
        long second = newPost("POST");
        for (int round = 0; round < 5; round++) {
            long me = user(30);
            List<Object> results = together(
                    () -> actions.comment(principal(me), "POST", first, new CommentRequest("Cùng lúc 1", null)),
                    () -> actions.comment(principal(me), "POST", second, new CommentRequest("Cùng lúc 2", null)));
            assertThat(results).filteredOn(r -> r instanceof Long).hasSize(1);
            assertThat(results).filteredOn(r -> r instanceof ApiException e
                    && e.getStatus() == HttpStatus.TOO_MANY_REQUESTS).hasSize(1);
            assertThat(commentsBy(me)).isEqualTo(1);
        }
    }

    // ---------------------------------------------------------------- new accounts: 20 a day

    @Test
    void aNewAccountWritesAtMost20CommentsAJapanDay() throws Exception {
        long blog = newPost("POST");
        long newbie = user(2);
        long veteran = user(30);
        // 19 written soon after midnight in Japan (still "yesterday" in UTC), 5 just before it (yesterday in Japan)
        Instant japanMidnight = LocalDate.ofInstant(clock.instant(), JAPAN).atStartOfDay(JAPAN).toInstant();
        insertComments(newbie, blog, 19, japanMidnight.plus(Duration.ofMinutes(30)));
        insertComments(newbie, blog, 5, japanMidnight.minusSeconds(60));

        comment(newbie, blog, null, "Bình luận thứ 20 hôm nay").andExpect(status().isOk());
        clock.advance(Duration.ofSeconds(15));
        comment(newbie, blog, null, "Bình luận thứ 21 hôm nay")
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.message").value(DAILY_LIMIT));
        assertThat(commentsBy(newbie)).isEqualTo(25);

        // Accounts older than new_account_days have no daily limit
        insertComments(veteran, blog, 30, clock.instant().minus(Duration.ofHours(1)));
        comment(veteran, blog, null, "Bình luận thứ 31 hôm nay").andExpect(status().isOk());

        // The next Japan day starts at midnight in Japan
        clock.set(LocalDate.ofInstant(clock.instant(), JAPAN).plusDays(1).atStartOfDay(JAPAN).toInstant());
        comment(newbie, blog, null, "Ngày mới rồi").andExpect(status().isOk());
    }

    // ---------------------------------------------------------------- the same text on many posts

    @Test
    void theSameLongTextOnAFourthPostIsRefusedForADay() throws Exception {
        long me = user(30);
        List<Long> posts = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            posts.add(newPost("POST"));
        }
        String text = "Mình có kinh nghiệm làm thủ tục này rồi, bạn cứ nhắn hỏi mình nhé mọi người ơi";
        for (int i = 0; i < 3; i++) {
            comment(me, posts.get(i), null, text).andExpect(status().isOk());
            clock.advance(Duration.ofSeconds(15));
        }
        // Case, accents, spaces and punctuation do not make it a different text
        comment(me, posts.get(3), null, "MINH CO KINH NGHIEM lam thu tuc nay roi!!! Ban cu nhan hoi minh nhe, moi nguoi oi.")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(REPEATED));
        comment(me, posts.get(3), null, text).andExpect(status().isBadRequest());
        // The same post again is not "another post"
        comment(me, posts.get(0), null, text).andExpect(status().isOk());
        clock.advance(Duration.ofSeconds(15));

        // Short texts are never caught, however often
        for (long blog : posts) {
            comment(me, blog, null, "Cảm ơn bạn đã chia sẻ nhé!").andExpect(status().isOk());
            clock.advance(Duration.ofSeconds(15));
        }

        // 24 hours after the first three, the text is allowed again on a new post
        clock.set(commentTime(me, posts.get(2), text).plus(Duration.ofHours(24)).plusSeconds(1));
        comment(me, posts.get(3), null, text).andExpect(status().isOk());
    }

    // ---------------------------------------------------------------- reply depth

    @Test
    void repliesNestAtMost20Deep() throws Exception {
        long blog = newPost("POST");
        long me = user(30);
        List<Long> chain = chain(blog, writer, 20);              // depths 0 to 19

        comment(me, blog, chain.get(19), "Trả lời ở độ sâu 20").andExpect(status().isOk());
        long depth20 = lastCommentOf(me);
        clock.advance(Duration.ofSeconds(15));
        comment(me, blog, depth20, "Trả lời ở độ sâu 21")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(TOO_DEEP));
        comment(me, blog, chain.get(19), "Một trả lời khác ở độ sâu 20").andExpect(status().isOk());
        assertThat(commentsBy(me)).isEqualTo(2);

        // The author can still delete a post whose thread is as deep as allowed
        mvc.perform(withCsrf(delete("/api/blogs/" + blog), writer)).andExpect(status().isOk());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM comments WHERE blog_id = ?", Long.class, blog)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM blogs WHERE id = ?", Long.class, blog)).isZero();
    }

    @Test
    void aChainDeeperThanTheLimitStillOpensAndStaysShallow() throws Exception {
        for (String kind : List.of("POST", "QA")) {
            long blog = newPost(kind);
            List<Long> chain = chain(blog, writer, 26);          // depths 0 to 25, older data
            String path = ("QA".equals(kind) ? "/api/questions/" : "/api/blogs/") + blog;
            String body = mvc.perform(get(path)).andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

            List<Map<String, Object>> comments = JsonPath.read(body, "$.data.comments");
            assertThat(levels(comments)).isEqualTo(BlogActionService.MAX_REPLY_DEPTH + 2);
            assertThat(ids(comments)).containsExactlyInAnyOrderElementsOf(chain);
            Map<String, Object> node = comments.get(0);
            for (int depth = 1; depth <= 20; depth++) {
                node = replies(node).get(0);
                assertThat(id(node)).isEqualTo(chain.get(depth));
            }
            // Depths 21 to 25 are shown under the comment at depth 20, oldest first, keeping their real parent
            assertThat(replies(node)).extracting(CommentRulesFlowTest::id).containsExactlyElementsOf(chain.subList(21, 26));
            assertThat(replies(node)).allSatisfy(r -> assertThat(replies(r)).isEmpty());
            assertThat(((Number) replies(node).get(1).get("parentId")).longValue()).isEqualTo(chain.get(21));
        }

        // Without the limit, 2,000 nested replies made the page fail for everyone (JSON nested past 1,000 levels)
        long blog = newPost("POST");
        List<Long> chain = chain(blog, writer, 2000);
        String body = mvc.perform(get("/api/blogs/" + blog)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        List<Map<String, Object>> comments = JsonPath.read(body, "$.data.comments");
        assertThat(levels(comments)).isEqualTo(BlogActionService.MAX_REPLY_DEPTH + 2);
        assertThat(ids(comments)).hasSize(chain.size());
        // …and its author can delete it (a cascade down the chain would stop at MySQL's 15 levels)
        mvc.perform(withCsrf(delete("/api/blogs/" + blog), writer)).andExpect(status().isOk());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM comments WHERE blog_id = ?", Long.class, blog)).isZero();
    }

    // ---------------------------------------------------------------- delete after the post was hidden

    @Test
    void deletingACommentOnAPostHiddenMeanwhileSucceeds() throws Exception {
        long blog = newPost("POST");
        long me = user(30);
        comment(me, blog, null, "Sẽ xóa sau").andExpect(status().isOk());
        long mine = lastCommentOf(me);
        jdbc.update("UPDATE blogs SET status = 'HIDDEN' WHERE id = ?", blog);

        String body = mvc.perform(withCsrf(delete("/api/comments/" + mine), me))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Đã xóa bình luận."))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(body).contains("\"data\":null");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM comments WHERE id = ?", Long.class, mine)).isZero();

        // The author may still see their hidden post, so they get it back after deleting a comment there
        long own = insertComments(writer, blog, 1, clock.instant().minus(Duration.ofHours(1))).get(0);
        mvc.perform(withCsrf(delete("/api/comments/" + own), writer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(blog))
                .andExpect(jsonPath("$.data.status").value("HIDDEN"));
        // A comment that is already gone is still "not found"
        mvc.perform(withCsrf(delete("/api/comments/" + mine), me)).andExpect(status().isNotFound());
    }

    // ---------------------------------------------------------------- helpers

    private long user(int daysOld) {
        String email = "crule-" + UUID.randomUUID() + "@test.local";
        jdbc.update("""
                INSERT INTO users (email, display_name, role, status, is_adult_confirmed, created_at)
                VALUES (?, 'Rule Test', 'USER', 'ACTIVE', TRUE, ?)""",
                email, Timestamp.from(clock.instant().minus(Duration.ofDays(daysOld))));
        long id = jdbc.queryForObject("SELECT id FROM users WHERE email = ?", Long.class, email);
        users.add(id);
        return id;
    }

    /** A published post (or question) of {@code writer}, inserted directly. */
    private long newPost(String type) {
        KeyHolder key = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement("""
                    INSERT INTO blogs (author_id, blog_type, title, content, status, created_at)
                    VALUES (?, ?, 'Bài thử quy tắc bình luận', 'nội dung', 'PUBLISHED', ?)""",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, writer);
            ps.setString(2, type);
            ps.setTimestamp(3, Timestamp.from(clock.instant().minus(Duration.ofDays(1))));
            return ps;
        }, key);
        return key.getKey().longValue();
    }

    /** Published comments inserted directly, each one second after the previous; returns their ids. */
    private List<Long> insertComments(long authorId, long blogId, int count, Instant from) {
        List<Long> ids = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            ids.add(insertComment(blogId, authorId, null, from.plusSeconds(i)));
        }
        return ids;
    }

    /** A chain of replies inserted directly: the first is a top-level comment, each next one replies to it. */
    private List<Long> chain(long blogId, long authorId, int length) {
        List<Long> ids = new ArrayList<>();
        Instant at = clock.instant().minus(Duration.ofHours(2));
        Long parent = null;
        for (int i = 0; i < length; i++) {
            parent = insertComment(blogId, authorId, parent, at.plusMillis(1000L * i));
            ids.add(parent);
        }
        return ids;
    }

    private long insertComment(long blogId, long authorId, Long parentId, Instant at) {
        KeyHolder key = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement("""
                    INSERT INTO comments (blog_id, author_id, parent_id, content, status, created_at, updated_at)
                    VALUES (?, ?, ?, 'Bình luận có sẵn', 'PUBLISHED', ?, ?)""", Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, blogId);
            ps.setLong(2, authorId);
            ps.setObject(3, parentId);
            ps.setTimestamp(4, Timestamp.from(at));
            ps.setTimestamp(5, Timestamp.from(at));
            return ps;
        }, key);
        return key.getKey().longValue();
    }

    private ResultActions comment(long userId, long blogId, Long parentId, String content) throws Exception {
        String kind = jdbc.queryForObject("SELECT blog_type FROM blogs WHERE id = ?", String.class, blogId);
        String path = "QA".equals(kind) ? "/api/questions/" + blogId + "/answers" : "/api/blogs/" + blogId + "/comments";
        return mvc.perform(withCsrf(post(path), userId).contentType(MediaType.APPLICATION_JSON)
                .content(body(content, parentId)));
    }

    private String body(String content, Long parentId) throws Exception {
        return "{\"content\":" + json.writeValueAsString(content) + ",\"parentId\":" + parentId + "}";
    }

    private long lastCommentOf(long userId) {
        return jdbc.queryForObject("SELECT MAX(id) FROM comments WHERE author_id = ?", Long.class, userId);
    }

    private long commentsBy(long userId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM comments WHERE author_id = ?", Long.class, userId);
    }

    private Instant commentTime(long userId, long blogId, String content) {
        return jdbc.queryForObject("SELECT MAX(created_at) FROM comments WHERE author_id = ? AND blog_id = ? AND content = ?",
                Timestamp.class, userId, blogId, content).toInstant();
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> replies(Map<String, Object> node) {
        return (List<Map<String, Object>>) node.get("replies");
    }

    private static long id(Map<String, Object> node) {
        return ((Number) node.get("id")).longValue();
    }

    /** How many levels of comments the JSON nests. */
    private static int levels(List<Map<String, Object>> nodes) {
        int deepest = 0;
        for (Map<String, Object> n : nodes) {
            deepest = Math.max(deepest, 1 + levels(replies(n)));
        }
        return deepest;
    }

    private static List<Long> ids(List<Map<String, Object>> nodes) {
        List<Long> all = new ArrayList<>();
        for (Map<String, Object> n : nodes) {
            all.add(id(n));
            all.addAll(ids(replies(n)));
        }
        return all;
    }

    private static AppUserPrincipal principal(long userId) {
        return new AppUserPrincipal(userId, "u" + userId + "@test.local", "Rule Test", "USER", "/");
    }

    private static MockHttpSession session(long userId) {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(SPRING_SECURITY_CONTEXT_KEY, new SecurityContextImpl(new UsernamePasswordAuthenticationToken(
                principal(userId), null, List.of(new SimpleGrantedAuthority("ROLE_USER")))));
        return session;
    }

    private static MockHttpServletRequestBuilder withCsrf(MockHttpServletRequestBuilder request, long userId) {
        return request.session(session(userId)).cookie(new Cookie("XSRF-TOKEN", CSRF)).header("X-XSRF-TOKEN", CSRF);
    }

    /** Runs the actions on separate threads released at the same moment; each result or the exception it threw. */
    @SafeVarargs
    static List<Object> together(java.util.concurrent.Callable<Object>... calls) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(calls.length);
        try {
            CountDownLatch start = new CountDownLatch(1);
            List<Future<Object>> futures = new ArrayList<>();
            for (var call : calls) {
                futures.add(pool.submit(() -> {
                    start.await();
                    try {
                        return call.call();
                    } catch (Exception e) {
                        return e;
                    }
                }));
            }
            start.countDown();
            List<Object> results = new ArrayList<>();
            for (Future<Object> f : futures) {
                results.add(f.get(30, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            pool.shutdownNow();
        }
    }

    /** The application clock in these tests: set and moved by hand, shared by every thread. */
    static final class MovableClock extends Clock {

        private final AtomicReference<Instant> now;
        private final ZoneId zone;

        MovableClock() {
            this(new AtomicReference<>(Instant.now()), ZoneOffset.UTC);
        }

        private MovableClock(AtomicReference<Instant> now, ZoneId zone) {
            this.now = now;
            this.zone = zone;
        }

        void set(Instant instant) {
            now.set(instant);
        }

        void advance(Duration duration) {
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
