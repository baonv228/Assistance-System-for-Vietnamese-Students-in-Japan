package com.sep490.vsa.feature.blog;

import com.jayway.jsonpath.JsonPath;
import com.sep490.vsa.common.security.AppUserPrincipal;
import com.sep490.vsa.feature.admin.AdminUserService;
import com.sep490.vsa.feature.blog.BlogDtos.TopicCount;
import com.sep490.vsa.feature.blog.BlogFeedRepository.Candidate;
import com.sep490.vsa.feature.moderation.ImageModerator;
import com.sep490.vsa.feature.moderation.ImageModerator.Verdict;
import com.sep490.vsa.feature.qa.QaRepository;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

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
 * Content of LOCKED and DELETED accounts (user decision): their posts, questions, comments and answers disappear
 * from everything Members and Guests read, and come back when a locked account is unlocked. End to end against MySQL
 * ({@code asvsj_test}, like the other flow tests); the account changes go through the real Admin and "Xóa tài khoản"
 * APIs.
 * <p>
 * Writer X has a scam-warning post (featured, in V's area), a question, two comments on O's post (one with V's reply)
 * and the best answer of one of O's questions, the only answer of another. V saved X's and O's posts and questions.
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:mysql://localhost:3306/asvsj_test?createDatabaseIfNotExist=true&useUnicode=true&characterEncoding=utf8&connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true",
        "app.media.local-dir=target/test-uploads",
        "app.moderation.retry-initial-delay-ms=3600000",
        "app.places.osm.enabled=false"
})
@AutoConfigureMockMvc
@ActiveProfiles("local")
class HiddenAuthorFlowTest {

    private static final String CSRF = "test-csrf-token";
    private static final String MSG19 = BlogDetailService.NOT_FOUND;
    private static final String SCAM = "Cảnh báo lừa đảo";
    private static final String VISA = "Visa và cư trú";
    /** Far above any real post: X's post leads "Bài nổi bật" and the most-liked sort. */
    private static final int HUGE = 1_500_000_000;

    @Autowired
    private MockMvc mvc;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private BlogDetailRepository detail;
    @Autowired
    private BlogFeedRepository feed;
    @Autowired
    private QaRepository questions;
    @Autowired
    private FeaturedSnapshotService snapshots;
    @Autowired
    private PlatformTransactionManager txManager;
    @Autowired
    private AdminUserService adminUsers;
    @MockBean
    private ImageModerator moderator;

    private final List<Long> users = new ArrayList<>();
    private String word;
    private long area;
    private long place;
    private long admin;
    private long viewer;
    private long other;

    /** What X wrote, and what others wrote around it. */
    private record World(long x, long postX, long questionX, long postO, long cX1, long cX2, long rV, long cV,
                         long questionO, long aX, long aV, long questionO2, long aX2) {
    }

    /** Totals over the whole (shared) test database; only their change is checked. */
    private record Counts(long blogTotal, long scamTopicPosts, long qaTotal, long qaUnanswered, long qaSolved,
                          long visaTopicQuestions) {
    }

    @BeforeEach
    void setUp() {
        when(moderator.engine()).thenReturn("OPENAI");
        when(moderator.check(any(byte[].class))).thenReturn(new Verdict("PASS", "{}"));
        word = "ha" + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        jdbc.update("INSERT INTO areas (ward, station, display_name, is_active) VALUES ('Test', 'Test', ?, TRUE)",
                "Hidden test – " + word);
        area = jdbc.queryForObject("SELECT id FROM areas WHERE display_name = ?", Long.class, "Hidden test – " + word);
        admin = user("ADMIN");
        viewer = user("USER");
        other = user("USER");
        jdbc.update("UPDATE users SET area_id = ?, stage = 'ARR' WHERE id = ?", area, viewer);
        jdbc.update("""
                INSERT INTO places (name_vi, category_id, area_id, lat, lng, source, status, created_by)
                VALUES (?, (SELECT MIN(id) FROM place_categories), ?, 35.7, 139.7, 'TEAM', 'ACTIVE', ?)""",
                "Hidden place " + word, area, other);
        place = jdbc.queryForObject("SELECT id FROM places WHERE name_vi = ?", Long.class, "Hidden place " + word);
    }

    @AfterEach
    void cleanUp() {
        for (long id : users) {
            jdbc.update("DELETE FROM blogs WHERE author_id = ?", id);
        }
        jdbc.update("DELETE FROM places WHERE id = ?", place);
        for (long id : users) {
            jdbc.update("DELETE FROM users WHERE id = ?", id);
        }
        jdbc.update("DELETE FROM areas WHERE id = ?", area);
        refreshFeatured();
    }

    // ---------------------------------------------------------------- the tests

    @Test
    void contentOfALockedAccountIsHiddenEverywhereAndBackWhenUnlocked() throws Exception {
        World w = world();
        Counts shown = counts();
        assertShown(w);

        setStatus(w.x(), "LOCKED");
        assertThat(statusOf(w.x())).isEqualTo("LOCKED");
        assertHidden(w);
        assertThat(counts()).isEqualTo(new Counts(shown.blogTotal() - 1, shown.scamTopicPosts() - 1,
                shown.qaTotal() - 1, shown.qaUnanswered(), shown.qaSolved() - 1, shown.visaTopicQuestions() - 1));

        // Acting on hidden content answers "not found", like on a hidden post
        perform(withCsrf(post("/api/blogs/" + w.postX() + "/like"), viewer, "USER")).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value(MSG19));
        perform(withCsrf(post("/api/blogs/" + w.postX() + "/save"), viewer, "USER")).andExpect(status().isNotFound());
        perform(withCsrf(post("/api/questions/" + w.questionX() + "/answers"), viewer, "USER")
                .contentType(MediaType.APPLICATION_JSON).content("{\"content\":\"Trả lời câu hỏi đã ẩn\"}"))
                .andExpect(status().isNotFound());
        perform(withCsrf(post("/api/blogs/" + w.postO() + "/comments"), viewer, "USER")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"content\":\"Trả lời bình luận đã ẩn\",\"parentId\":" + w.cX1() + "}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Bình luận bạn trả lời không còn nữa."));
        perform(withCsrf(post("/api/comments/" + w.cX2() + "/like"), viewer, "USER")).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Không tìm thấy bình luận này."));
        perform(withCsrf(put("/api/questions/" + w.questionO2() + "/best-answer"), other, "USER")
                .contentType(MediaType.APPLICATION_JSON).content("{\"answerId\":" + w.aX2() + "}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Không tìm thấy câu trả lời này."));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM comments WHERE author_id = ?", Long.class, viewer))
                .isEqualTo(3);     // still only rV, cV and aV: nothing was added

        setStatus(w.x(), "ACTIVE");
        assertShown(w);
        assertThat(counts()).isEqualTo(shown);
    }

    @Test
    void contentOfADeletedAccountIsHiddenEverywhere() throws Exception {
        World w = world();
        Counts shown = counts();
        assertShown(w);

        perform(withCsrf(delete("/api/me"), w.x(), "USER")).andExpect(status().isOk());
        assertThat(statusOf(w.x())).isEqualTo("DELETED");
        assertHidden(w);
        assertThat(counts()).isEqualTo(new Counts(shown.blogTotal() - 1, shown.scamTopicPosts() - 1,
                shown.qaTotal() - 1, shown.qaUnanswered(), shown.qaSolved() - 1, shown.visaTopicQuestions() - 1));
        // Nothing was deleted: the rows are still there
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM blogs WHERE author_id = ?", Long.class, w.x())).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM comments WHERE author_id = ?", Long.class, w.x())).isEqualTo(4);
    }

    @Test
    void featuredPostsLeaveOutALockedAuthorEvenBeforeTheNextSnapshot() throws Exception {
        World w = world();
        assertThat(featuredOnBlog()).contains(w.postX());

        // Locked behind the app's back (no snapshot refresh): the Blog's read-back still leaves the post out
        jdbc.update("UPDATE users SET status = 'LOCKED' WHERE id = ?", w.x());
        assertThat(feed.latestFeatured(FeaturedSnapshotService.WEEK, 0)).contains(w.postX());
        assertThat(featuredOnBlog()).doesNotContain(w.postX());
        jdbc.update("UPDATE users SET status = 'ACTIVE' WHERE id = ?", w.x());
        assertThat(featuredOnBlog()).contains(w.postX());

        // Through the Admin API the snapshot itself is recomputed after the change, both ways
        setStatus(w.x(), "LOCKED");
        assertThat(feed.latestFeatured(FeaturedSnapshotService.WEEK, 0)).doesNotContain(w.postX());
        setStatus(w.x(), "ACTIVE");
        assertThat(feed.latestFeatured(FeaturedSnapshotService.WEEK, 0)).contains(w.postX());
        assertThat(featuredOnBlog()).contains(w.postX());
    }

    @Test
    void countsAndRankingsFollowTheSameRule() throws Exception {
        World w = world();
        Instant since = Instant.now().minus(Duration.ofDays(1));
        comment(w.questionX(), viewer, null, Instant.now().minus(Duration.ofMinutes(5)), "Câu trả lời của V");
        long onPostX = comment(w.postX(), viewer, null, Instant.now().minus(Duration.ofMinutes(4)), "Bình luận của V");
        int score = score(w.postO(), since);
        assertThat(answerer(w.x(), since)).containsExactly(2L, 1L);     // aX (the best answer) and aX2
        assertThat(answerer(viewer, since)).containsExactly(2L, 0L);    // aV and the answer to X's question

        setStatus(w.x(), "LOCKED");
        assertThat(answerer(w.x(), since)).isEmpty();
        assertThat(answerer(viewer, since)).containsExactly(1L, 0L);    // X's question is hidden
        assertThat(score(w.postO(), since)).isEqualTo(score - 2 * 2);   // X's two comments no longer count
        // A new comment is counted with the same rule
        perform(withCsrf(post("/api/blogs/" + w.postO() + "/comments"), viewer, "USER")
                .contentType(MediaType.APPLICATION_JSON).content("{\"content\":\"Bình luận mới sau khi khóa\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.commentCount").value(3));
        assertThat(commentCount(w.postO())).isEqualTo(3);
        // V's own comment under X's hidden post cannot be edited: the post is not found
        perform(withCsrf(put("/api/comments/" + onPostX), viewer, "USER")
                .contentType(MediaType.APPLICATION_JSON).content("{\"content\":\"Bình luận đã sửa\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value(MSG19));
        assertThat(jdbc.queryForObject("SELECT content FROM comments WHERE id = ?", String.class, onPostX))
                .isEqualTo("Bình luận của V");

        setStatus(w.x(), "ACTIVE");
        assertThat(commentCount(w.postO())).isEqualTo(5);
        assertThat(score(w.postO(), since)).isEqualTo(score + 2);       // V's new comment counts too
        assertThat(answerer(w.x(), since)).containsExactly(2L, 1L);
        assertThat(answerer(viewer, since)).containsExactly(2L, 0L);
    }

    /**
     * An Admin locks X while V's comment action holds the lock of a post X commented on. The Admin's request waits
     * for that post before it writes X's users row (AuthorStatusSync.beforeStatusChange), so the comment action can
     * finish its recount (which reads X's users row): no deadlock, and the count ends right.
     */
    @Test
    void lockingAnAccountWhileSomeoneCommentsWhereItCommentedDoesNotDeadlock() throws Exception {
        AppUserPrincipal byAdmin = new AppUserPrincipal(admin, "u" + admin + "@test.local", "Hidden Test", "ADMIN",
                "/admin");
        TransactionTemplate readCommitted = new TransactionTemplate(txManager);
        readCommitted.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        for (int round = 0; round < 3; round++) {
            long x = user("USER");
            long blog = blog(other, "POST", "Bài khóa cùng lúc " + word, Instant.now().minus(Duration.ofHours(1)), 0);
            comment(blog, x, null, Instant.now().minus(Duration.ofMinutes(30)), "Bình luận của X " + word);
            detail.recountComments(blog);

            CountDownLatch postLocked = new CountDownLatch(1);
            CountDownLatch go = new CountDownLatch(1);
            ExecutorService pool = Executors.newFixedThreadPool(2);
            try {
                // BlogActionService.comment: lock the post, then insert and recount
                Future<Object> commenter = pool.submit(() -> readCommitted.execute(tx -> {
                    detail.lockBlog(blog);
                    postLocked.countDown();
                    try {
                        go.await(10, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        throw new IllegalStateException(e);
                    }
                    detail.insertComment(blog, viewer, null, "Bình luận cùng lúc", Instant.now());
                    detail.recountComments(blog);
                    return "OK";
                }));
                assertThat(postLocked.await(10, TimeUnit.SECONDS)).isTrue();
                Future<Object> lock = pool.submit(() -> {
                    adminUsers.updateUserStatus(x, "LOCKED", "thử", byAdmin);
                    return "OK";
                });
                Thread.sleep(300);      // the Admin's request reaches its first lock meanwhile
                go.countDown();
                assertThat(commenter.get(30, TimeUnit.SECONDS)).isEqualTo("OK");
                assertThat(lock.get(30, TimeUnit.SECONDS)).isEqualTo("OK");
            } finally {
                pool.shutdownNow();
            }
            assertThat(statusOf(x)).isEqualTo("LOCKED");
            assertThat(commentCount(blog)).isEqualTo(1);        // V's comment; X's is hidden
        }
    }

    // ---------------------------------------------------------------- what everyone sees

    private void assertShown(World w) throws Exception {
        // Blog list and search: the search finds both posts; X's post leads the most-liked sort
        assertThat(ids(body("/api/blogs?size=30&q=" + word, null), "$.data.items[*].id"))
                .containsExactlyInAnyOrder(w.postX(), w.postO());
        assertThat(ids(body("/api/blogs?sort=LIKES&size=1", null), "$.data.items[*].id")).containsExactly(w.postX());
        assertThat(card(body("/api/blogs?size=30&q=" + word, null), "$.data.items", w.postO()).get("commentCount"))
                .isEqualTo(4);
        assertThat(featuredOnBlog()).contains(w.postX());

        // Detail, for guests and members
        get200("/api/blogs/" + w.postX(), null);
        get200("/api/blogs/" + w.postX(), session(viewer, "USER"));
        get200("/api/questions/" + w.questionX(), null);
        String post = body("/api/blogs/" + w.postO(), null);
        assertThat(ids(post, "$.data.comments[*].id")).containsExactly(w.cX1(), w.cX2(), w.cV());
        assertThat(ids(post, "$.data.comments[1].replies[*].id")).containsExactly(w.rV());
        assertThat((Boolean) JsonPath.read(post, "$.data.comments[1].removed")).isFalse();
        assertThat((Integer) JsonPath.read(post, "$.data.commentCount")).isEqualTo(4);
        String solved = body("/api/questions/" + w.questionO(), null);
        assertThat(((Number) JsonPath.read(solved, "$.data.bestCommentId")).longValue()).isEqualTo(w.aX());
        assertThat(ids(solved, "$.data.comments[*].id")).containsExactly(w.aX(), w.aV());
        assertThat(ids(body("/api/questions/" + w.questionO2(), null), "$.data.comments[*].id")).containsExactly(w.aX2());

        // Hỏi-Đáp list and its filters
        assertThat(questionIds("ALL")).containsExactlyInAnyOrder(w.questionX(), w.questionO(), w.questionO2());
        assertThat(questionIds("UNANSWERED")).containsExactly(w.questionX());
        assertThat(questionIds("SOLVED")).containsExactly(w.questionO());
        assertThat(questionIds("UNSOLVED")).containsExactlyInAnyOrder(w.questionX(), w.questionO2());
        String qa = body("/api/questions?size=30&q=" + word, null);
        assertThat(card(qa, "$.data.items", w.questionO())).containsEntry("solved", true).containsEntry("answerCount", 2);
        assertThat(card(qa, "$.data.items", w.questionO2())).containsEntry("answerCount", 1);

        // "Đã lưu" of the member who saved them
        String saved = body("/api/me/saved", session(viewer, "USER"));
        assertThat(ids(saved, "$.data.posts[*].id")).containsExactlyInAnyOrder(w.postX(), w.postO());
        assertThat(ids(saved, "$.data.questions[*].id")).containsExactlyInAnyOrder(w.questionX(), w.questionO());
        assertThat(card(saved, "$.data.questions", w.questionO())).containsEntry("solved", true);
        assertThat((Integer) JsonPath.read(saved, "$.data.counts.total")).isEqualTo(4);
        assertThat(savedCount()).isEqualTo(4);

        // Home of that member: "Dành cho bạn" (X's post is in their area) and the featured post
        String home = body("/api/me/home", session(viewer, "USER"));
        assertThat(ids(home, "$.data.forYou[*].id")).startsWith(w.postX());
        assertThat(((Number) JsonPath.read(home, "$.data.featured.id")).longValue()).isEqualTo(w.postX());

        // The scam warning marks the place
        assertThat(ids(body("/api/blogs/scam-warnings?placeId=" + place, null), "$.data[*].blogId"))
                .containsExactly(w.postX());
        assertThat(ids(body("/api/blogs/scam-warnings", null), "$.data[*].blogId")).contains(w.postX());

        // The stored counts behind the cards
        assertThat(commentCount(w.postO())).isEqualTo(4);
        assertThat(commentCount(w.questionO())).isEqualTo(2);
        assertThat(commentCount(w.questionO2())).isEqualTo(1);
    }

    private void assertHidden(World w) throws Exception {
        assertThat(ids(body("/api/blogs?size=30&q=" + word, null), "$.data.items[*].id")).containsExactly(w.postO());
        assertThat(ids(body("/api/blogs?sort=LIKES&size=1", null), "$.data.items[*].id")).doesNotContain(w.postX());
        assertThat(card(body("/api/blogs?size=30&q=" + word, null), "$.data.items", w.postO()).get("commentCount"))
                .isEqualTo(2);
        assertThat(featuredOnBlog()).doesNotContain(w.postX());

        // Detail: 404 MSG19 like a hidden post, for everyone
        get404("/api/blogs/" + w.postX(), null);
        get404("/api/blogs/" + w.postX(), session(viewer, "USER"));
        get404("/api/questions/" + w.questionX(), null);
        get404("/api/questions/" + w.questionX(), session(other, "USER"));
        // X's comment without replies is gone; the one with V's reply is an empty placeholder
        String post = body("/api/blogs/" + w.postO(), null);
        assertThat(ids(post, "$.data.comments[*].id")).containsExactly(w.cX2(), w.cV());
        assertThat((Boolean) JsonPath.read(post, "$.data.comments[0].removed")).isTrue();
        assertThat((Object) JsonPath.read(post, "$.data.comments[0].author")).isNull();
        assertThat((String) JsonPath.read(post, "$.data.comments[0].content")).isEmpty();
        assertThat(ids(post, "$.data.comments[0].replies[*].id")).containsExactly(w.rV());
        assertThat((Integer) JsonPath.read(post, "$.data.commentCount")).isEqualTo(2);
        assertThat(post).doesNotContain("Bình luận của X");
        // X's best answer no longer counts: the question is open again
        String solved = body("/api/questions/" + w.questionO(), null);
        assertThat((Object) JsonPath.read(solved, "$.data.bestCommentId")).isNull();
        assertThat(ids(solved, "$.data.comments[*].id")).containsExactly(w.aV());
        assertThat(ids(body("/api/questions/" + w.questionO2(), null), "$.data.comments[*].id")).isEmpty();

        assertThat(questionIds("ALL")).containsExactlyInAnyOrder(w.questionO(), w.questionO2());
        assertThat(questionIds("UNANSWERED")).containsExactly(w.questionO2());
        assertThat(questionIds("SOLVED")).isEmpty();
        assertThat(questionIds("UNSOLVED")).containsExactlyInAnyOrder(w.questionO(), w.questionO2());
        String qa = body("/api/questions?size=30&q=" + word, null);
        assertThat(card(qa, "$.data.items", w.questionO())).containsEntry("solved", false).containsEntry("answerCount", 1);
        assertThat(card(qa, "$.data.items", w.questionO2())).containsEntry("answerCount", 0);

        String saved = body("/api/me/saved", session(viewer, "USER"));
        assertThat(ids(saved, "$.data.posts[*].id")).containsExactly(w.postO());
        assertThat(ids(saved, "$.data.questions[*].id")).containsExactly(w.questionO());
        assertThat(card(saved, "$.data.questions", w.questionO())).containsEntry("solved", false);
        assertThat((Integer) JsonPath.read(saved, "$.data.counts.total")).isEqualTo(2);
        assertThat(savedCount()).isEqualTo(2);
        // The saves themselves are kept (they come back on unlock)
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM saved_blogs WHERE user_id = ?", Long.class, viewer))
                .isEqualTo(4);

        String home = body("/api/me/home", session(viewer, "USER"));
        assertThat(ids(home, "$.data.forYou[*].id")).doesNotContain(w.postX());
        Map<String, Object> featured = JsonPath.read(home, "$.data.featured");
        assertThat(featured == null ? null : ((Number) featured.get("id")).longValue()).isNotEqualTo(w.postX());

        assertThat(ids(body("/api/blogs/scam-warnings?placeId=" + place, null), "$.data[*].blogId")).isEmpty();
        assertThat(ids(body("/api/blogs/scam-warnings", null), "$.data[*].blogId")).doesNotContain(w.postX());

        assertThat(commentCount(w.postO())).isEqualTo(2);
        assertThat(commentCount(w.questionO())).isEqualTo(1);
        assertThat(commentCount(w.questionO2())).isZero();
    }

    private Counts counts() throws Exception {
        String overview = body("/api/questions/overview", null);
        return new Counts(
                ((Number) JsonPath.read(body("/api/blogs", null), "$.data.totalItems")).longValue(),
                topicCount(feed.popularTopics(1000), SCAM),
                ((Number) JsonPath.read(overview, "$.data.stats.total")).longValue(),
                ((Number) JsonPath.read(overview, "$.data.stats.unanswered")).longValue(),
                ((Number) JsonPath.read(overview, "$.data.stats.solved")).longValue(),
                topicCount(questions.popularTopics(1000), VISA));
    }

    // ---------------------------------------------------------------- data

    private World world() throws Exception {
        long x = user("USER");
        Instant now = Instant.now();
        long postX = blog(x, "POST", "Bài X " + word, now.minus(Duration.ofHours(2)), HUGE);
        topic(postX, SCAM);
        jdbc.update("INSERT INTO blog_place_links (blog_id, place_id) VALUES (?, ?)", postX, place);
        long questionX = blog(x, "QA", "Câu hỏi X " + word, now.minus(Duration.ofMinutes(110)), 0);
        topic(questionX, VISA);

        long postO = blog(other, "POST", "Bài O " + word, now.minus(Duration.ofMinutes(100)), 0);
        topic(postO, VISA);
        long cX1 = comment(postO, x, null, now.minus(Duration.ofMinutes(90)), "Bình luận của X " + word);
        long cX2 = comment(postO, x, null, now.minus(Duration.ofMinutes(80)), "Bình luận của X " + word);
        long rV = comment(postO, viewer, cX2, now.minus(Duration.ofMinutes(70)), "Bình luận của V");
        long cV = comment(postO, viewer, null, now.minus(Duration.ofMinutes(60)), "Bình luận của V");

        long questionO = blog(other, "QA", "Câu hỏi O " + word, now.minus(Duration.ofMinutes(50)), 0);
        long aX = comment(questionO, x, null, now.minus(Duration.ofMinutes(40)), "Bình luận của X " + word);
        long aV = comment(questionO, viewer, null, now.minus(Duration.ofMinutes(30)), "Bình luận của V");
        jdbc.update("UPDATE blogs SET best_comment_id = ? WHERE id = ?", aX, questionO);
        long questionO2 = blog(other, "QA", "Câu hỏi O2 " + word, now.minus(Duration.ofMinutes(20)), 0);
        long aX2 = comment(questionO2, x, null, now.minus(Duration.ofMinutes(10)), "Bình luận của X " + word);
        for (long id : List.of(postO, questionO, questionO2)) {
            detail.recountComments(id);
        }

        // Saved through the real API (the only way a Member saves)
        perform(withCsrf(post("/api/blogs/" + postX + "/save"), viewer, "USER")).andExpect(status().isOk());
        perform(withCsrf(post("/api/blogs/" + postO + "/save"), viewer, "USER")).andExpect(status().isOk());
        perform(withCsrf(post("/api/questions/" + questionX + "/save"), viewer, "USER")).andExpect(status().isOk());
        perform(withCsrf(post("/api/questions/" + questionO + "/save"), viewer, "USER")).andExpect(status().isOk());
        refreshFeatured();
        return new World(x, postX, questionX, postO, cX1, cX2, rV, cV, questionO, aX, aV, questionO2, aX2);
    }

    private long user(String role) {
        String email = "hidden-" + UUID.randomUUID() + "@test.local";
        jdbc.update("""
                INSERT INTO users (email, display_name, role, status, is_adult_confirmed, created_at)
                VALUES (?, 'Hidden Test', ?, 'ACTIVE', TRUE, ?)""",
                email, role, Timestamp.from(Instant.now().minus(Duration.ofDays(30))));
        long id = jdbc.queryForObject("SELECT id FROM users WHERE email = ?", Long.class, email);
        users.add(id);
        return id;
    }

    private long blog(long authorId, String type, String title, Instant createdAt, int likes) {
        KeyHolder key = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement("""
                    INSERT INTO blogs (author_id, blog_type, title, content, status, like_count, created_at, updated_at)
                    VALUES (?, ?, ?, 'Nội dung thử ẩn bài', 'PUBLISHED', ?, ?, ?)""", Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, authorId);
            ps.setString(2, type);
            ps.setString(3, title);
            ps.setInt(4, likes);
            ps.setTimestamp(5, Timestamp.from(createdAt));
            ps.setTimestamp(6, Timestamp.from(createdAt));
            return ps;
        }, key);
        return key.getKey().longValue();
    }

    private void topic(long blogId, String name) {
        jdbc.update("INSERT INTO blog_topic_links (blog_id, topic_id) SELECT ?, id FROM blog_topics WHERE name = ?",
                blogId, name);
    }

    private long comment(long blogId, long authorId, Long parentId, Instant at, String text) {
        KeyHolder key = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement("""
                    INSERT INTO comments (blog_id, author_id, parent_id, content, status, created_at, updated_at)
                    VALUES (?, ?, ?, ?, 'PUBLISHED', ?, ?)""", Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, blogId);
            ps.setLong(2, authorId);
            ps.setObject(3, parentId);
            ps.setString(4, text);
            ps.setTimestamp(5, Timestamp.from(at));
            ps.setTimestamp(6, Timestamp.from(at));
            return ps;
        }, key);
        return key.getKey().longValue();
    }

    private void refreshFeatured() {
        new TransactionTemplate(txManager).executeWithoutResult(tx -> snapshots.refresh());
    }

    // ---------------------------------------------------------------- requests

    /** Locks or unlocks through the Admin API, as the Admin page does. */
    private void setStatus(long userId, String status) throws Exception {
        perform(withCsrf(put("/api/admin/users/" + userId + "/status"), admin, "ADMIN")
                .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"" + status + "\",\"reason\":\"thử\"}"))
                .andExpect(status().isOk());
    }

    private List<Long> questionIds(String status) throws Exception {
        return ids(body("/api/questions?size=30&status=" + status + "&q=" + word, null), "$.data.items[*].id");
    }

    private List<Long> featuredOnBlog() throws Exception {
        return ids(body("/api/blogs/overview", null), "$.data.featured.posts[*].id");
    }

    private int savedCount() throws Exception {
        return JsonPath.read(body("/api/me/profile", session(viewer, "USER")), "$.data.savedCount");
    }

    private int commentCount(long blogId) {
        return jdbc.queryForObject("SELECT comment_count FROM blogs WHERE id = ?", Integer.class, blogId);
    }

    private String statusOf(long userId) {
        return jdbc.queryForObject("SELECT status FROM users WHERE id = ?", String.class, userId);
    }

    /** The post's "Bài nổi bật" score now (likes + 2 × comments by others + rank bonus). */
    private int score(long blogId, Instant since) {
        return feed.featuredCandidates(since, 0).stream().filter(c -> c.blogId() == blogId)
                .mapToInt(Candidate::score).findFirst().orElseThrow();
    }

    /** [answers, best answers] of the user among the top answerers since {@code since}; empty when not listed. */
    private List<Long> answerer(long userId, Instant since) {
        return questions.topAnswerers(since, 1000).stream().filter(r -> r.author().id() == userId)
                .map(r -> List.of(r.answers(), r.best())).findFirst().orElse(List.of());
    }

    private static long topicCount(List<TopicCount> topics, String name) {
        return topics.stream().filter(t -> t.name().equals(name)).mapToLong(TopicCount::postCount).sum();
    }

    private ResultActions perform(MockHttpServletRequestBuilder request) throws Exception {
        return mvc.perform(request);
    }

    private String body(String url, MockHttpSession session) throws Exception {
        MockHttpServletRequestBuilder request = get(url);
        if (session != null) {
            request.session(session);
        }
        return mvc.perform(request).andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsString(StandardCharsets.UTF_8);
    }

    private void get200(String url, MockHttpSession session) throws Exception {
        body(url, session);
    }

    private void get404(String url, MockHttpSession session) throws Exception {
        MockHttpServletRequestBuilder request = get(url);
        if (session != null) {
            request.session(session);
        }
        mvc.perform(request).andExpect(status().isNotFound()).andExpect(jsonPath("$.message").value(MSG19));
    }

    private static List<Long> ids(String body, String path) {
        List<Object> raw = JsonPath.read(body, path);
        return raw.stream().map(v -> ((Number) v).longValue()).toList();
    }

    /** The JSON object with this id in the list at {@code listPath}. */
    private static Map<String, Object> card(String body, String listPath, long id) {
        List<Map<String, Object>> items = JsonPath.read(body, listPath);
        return items.stream().filter(m -> ((Number) m.get("id")).longValue() == id).findFirst().orElseThrow();
    }

    private static MockHttpSession session(long userId, String role) {
        AppUserPrincipal me = new AppUserPrincipal(userId, "u" + userId + "@test.local", "Hidden Test", role,
                "ADMIN".equals(role) ? "/admin" : "/");
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(SPRING_SECURITY_CONTEXT_KEY, new SecurityContextImpl(
                new UsernamePasswordAuthenticationToken(me, null, List.of(new SimpleGrantedAuthority("ROLE_" + role)))));
        return session;
    }

    private static MockHttpServletRequestBuilder withCsrf(MockHttpServletRequestBuilder request, long userId,
                                                          String role) {
        return request.session(session(userId, role)).cookie(new Cookie("XSRF-TOKEN", CSRF))
                .header("X-XSRF-TOKEN", CSRF);
    }
}
