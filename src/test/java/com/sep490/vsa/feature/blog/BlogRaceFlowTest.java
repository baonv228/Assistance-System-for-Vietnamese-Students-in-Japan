package com.sep490.vsa.feature.blog;

import com.sep490.vsa.common.exception.ApiException;
import com.sep490.vsa.common.security.AppUserPrincipal;
import com.sep490.vsa.common.settings.SystemSettings;
import com.sep490.vsa.feature.blog.BlogDetailDtos.ReportRequest;
import com.sep490.vsa.feature.moderation.ImageModerator;
import com.sep490.vsa.feature.moderation.ImageModerator.Verdict;
import com.sep490.vsa.feature.report.ContentReportService;
import com.sep490.vsa.feature.report.ContentReportService.Target;
import com.sep490.vsa.feature.user.PointService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Actions sent at the same moment (two browser tabs, a double click, two reporters): best answer, deletes, likes,
 * saves and reports, against MySQL ({@code asvsj_test}, like CommentEditFlowTest). The service is called from separate threads,
 * so every call runs in its own real transaction. Each race is repeated a few times on fresh data.
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:mysql://localhost:3306/asvsj_test?createDatabaseIfNotExist=true&useUnicode=true&characterEncoding=utf8&connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true",
        "app.media.local-dir=target/test-uploads",
        "app.moderation.retry-initial-delay-ms=3600000",
        "app.places.osm.enabled=false"
})
@AutoConfigureMockMvc
@ActiveProfiles("local")
class BlogRaceFlowTest {

    private static final int ROUNDS = 5;

    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private BlogActionService actions;
    @Autowired
    private ContentReportService reports;
    @Autowired
    private SystemSettings settings;
    @Autowired
    private PlatformTransactionManager txManager;
    @MockBean
    private ImageModerator moderator;

    private final List<Long> users = new ArrayList<>();
    private long reason;

    @BeforeEach
    void setUp() {
        when(moderator.engine()).thenReturn("OPENAI");
        when(moderator.check(any(byte[].class))).thenReturn(new Verdict("PASS", "{}"));
        reason = jdbc.queryForObject("SELECT MIN(id) FROM reason_options WHERE scope = 'REPORT' AND is_active", Long.class);
    }

    @AfterEach
    void cleanUp() {
        for (long id : users) {
            jdbc.update("DELETE FROM blogs WHERE author_id = ?", id);
        }
        for (long id : users) {
            jdbc.update("DELETE FROM users WHERE id = ?", id);
        }
    }

    // ---------------------------------------------------------------- best answer (points)

    @Test
    void choosingTheSameBestAnswerTwiceAtOncePaysOnce() throws Exception {
        for (int round = 0; round < ROUNDS; round++) {
            long asker = user();
            long helper = user();
            long question = blog(asker, "QA");
            long answer = comment(question, helper, null);

            List<Object> results = CommentRulesFlowTest.together(
                    () -> choose(asker, question, answer), () -> choose(asker, question, answer));
            assertThat(results).containsOnly("OK");
            assertThat(points(helper)).isEqualTo(PointService.BEST_ANSWER);
            assertThat(balance(helper, answer)).isEqualTo(PointService.BEST_ANSWER);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM notifications WHERE user_id = ? AND type = 'BEST_ANSWER'",
                    Long.class, helper)).isEqualTo(1);
            assertThat(best(question)).isEqualTo(answer);
        }
    }

    @Test
    void movingTheBestAnswerAwayAndBackMovesThePoints() {
        long asker = user();
        long first = user();
        long second = user();
        long question = blog(asker, "QA");
        long a = comment(question, first, null);
        long b = comment(question, second, null);

        actions.chooseBest(principal(asker), question, a);
        actions.chooseBest(principal(asker), question, b);
        actions.chooseBest(principal(asker), question, a);
        assertThat(points(first)).isEqualTo(PointService.BEST_ANSWER);
        assertThat(points(second)).isZero();
        assertThat(balance(first, a)).isEqualTo(PointService.BEST_ANSWER);
        assertThat(balance(second, b)).isZero();
        assertThat(best(question)).isEqualTo(a);
    }

    @Test
    void movingTheBestAnswerFromTwoTabsAtOnceKeepsThePointsRight() throws Exception {
        for (int round = 0; round < ROUNDS; round++) {
            long asker = user();
            long first = user();
            long second = user();
            long question = blog(asker, "QA");
            long a = comment(question, first, null);
            long b = comment(question, second, null);
            actions.chooseBest(principal(asker), question, a);

            CommentRulesFlowTest.together(() -> choose(asker, question, b), () -> choose(asker, question, b));
            assertThat(points(first)).isZero();
            assertThat(points(second)).isEqualTo(PointService.BEST_ANSWER);
            assertThat(best(question)).isEqualTo(b);
        }
    }

    // ---------------------------------------------------------------- deletes (points taken back once)

    @Test
    void deletingTheSamePostTwiceAtOnceTakesThePointsBackOnce() throws Exception {
        for (int round = 0; round < ROUNDS; round++) {
            long author = user();
            long blog = blog(author, "POST");
            jdbc.update("""
                    INSERT INTO point_transactions (user_id, delta, reason, ref_type, ref_id) VALUES (?, 10, 'BLOG_CREATED', 'BLOG', ?)""",
                    author, blog);
            jdbc.update("UPDATE users SET points = 10 WHERE id = ?", author);

            List<Object> results = CommentRulesFlowTest.together(
                    () -> deletePost(author, blog), () -> deletePost(author, blog));
            assertThat(results).containsExactlyInAnyOrder("OK", HttpStatus.NOT_FOUND);
            assertThat(points(author)).isZero();
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM point_transactions WHERE user_id = ? AND reason = 'CONTENT_DELETED'",
                    Long.class, author)).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM blogs WHERE id = ?", Long.class, blog)).isZero();
        }
    }

    @Test
    void deletingTheSameBestAnswerTwiceAtOnceTakesThePointsBackOnce() throws Exception {
        for (int round = 0; round < ROUNDS; round++) {
            long asker = user();
            long helper = user();
            long question = blog(asker, "QA");
            long answer = comment(question, helper, null);
            actions.chooseBest(principal(asker), question, answer);

            List<Object> results = CommentRulesFlowTest.together(
                    () -> deleteComment(helper, answer), () -> deleteComment(helper, answer));
            assertThat(results).containsExactlyInAnyOrder("OK", HttpStatus.NOT_FOUND);
            assertThat(points(helper)).isZero();
            assertThat(balance(helper, answer)).isZero();
            assertThat(best(question)).isNull();
        }
    }

    // ---------------------------------------------------------------- likes and saves

    @Test
    void twoLikesOrSavesOnOneItemAtOnceAreBothCounted() throws Exception {
        for (int round = 0; round < ROUNDS; round++) {
            long author = user();
            long one = user();
            long two = user();
            long blog = blog(author, "POST");
            long comment = comment(blog, author, null);

            assertThat(CommentRulesFlowTest.together(() -> likePost(one, blog, true), () -> likePost(two, blog, true)))
                    .containsOnly("OK");
            assertThat(likeCount("blogs", blog)).isEqualTo(2);
            assertThat(CommentRulesFlowTest.together(() -> likeComment(one, comment, true),
                    () -> likeComment(two, comment, true))).containsOnly("OK");
            assertThat(likeCount("comments", comment)).isEqualTo(2);
            assertThat(CommentRulesFlowTest.together(() -> savePost(one, blog), () -> savePost(two, blog),
                    () -> likePost(one, blog, false))).containsOnly("OK");
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM saved_blogs WHERE blog_id = ?", Long.class, blog))
                    .isEqualTo(2);
            assertThat(CommentRulesFlowTest.together(() -> likePost(two, blog, false),
                    () -> likeComment(one, comment, false), () -> likeComment(two, comment, false))).containsOnly("OK");
            assertThat(likeCount("blogs", blog)).isZero();
            assertThat(likeCount("comments", comment)).isZero();
        }
    }

    // ---------------------------------------------------------------- reports

    @Test
    void twoFirstReportsAtOnceAreBothCounted() throws Exception {
        for (int round = 0; round < ROUNDS; round++) {
            long author = user();
            long one = user();
            long two = user();
            long blog = blog(author, "POST");
            long comment = comment(blog, author, null);

            List<Object> onPost = CommentRulesFlowTest.together(
                    () -> reportPost(one, blog), () -> reportPost(two, blog));
            assertThat(onPost).containsOnly("OK");
            assertThat(caseOf("BLOG", blog)).isEqualTo(2);

            List<Object> onComment = CommentRulesFlowTest.together(
                    () -> reportComment(one, comment), () -> reportComment(two, comment));
            assertThat(onComment).containsOnly("OK");
            assertThat(caseOf("COMMENT", comment)).isEqualTo(2);
        }
    }

    @Test
    void theReportPastTheThresholdHidesThePostOrComment() {
        int threshold = settings.getInt(SystemSettings.REPORT_HIDE_THRESHOLD, 5);
        long author = user();
        long blog = blog(author, "POST");
        long other = blog(author, "POST");
        long comment = comment(other, author, null);
        for (int i = 1; i <= threshold; i++) {
            long reporter = user();
            reportPost(reporter, blog);
            reportComment(reporter, comment);
        }
        assertThat(status("blogs", blog)).isEqualTo("PUBLISHED");
        assertThat(status("comments", comment)).isEqualTo("PUBLISHED");
        assertThat(priority("BLOG", blog)).isEqualTo("NORMAL");

        long last = user();
        reportPost(last, blog);
        reportComment(last, comment);
        assertThat(status("blogs", blog)).isEqualTo("HIDDEN");
        assertThat(priority("BLOG", blog)).isEqualTo("URGENT");
        assertThat(status("comments", comment)).isEqualTo("HIDDEN");
        assertThat(priority("COMMENT", comment)).isEqualTo("URGENT");
        assertThat(jdbc.queryForObject("SELECT comment_count FROM blogs WHERE id = ?", Integer.class, other)).isZero();
    }

    @Test
    void aPostNotPublishedWhenItsCaseTurnedUrgentIsHiddenByTheNextReport() {
        int threshold = settings.getInt(SystemSettings.REPORT_HIDE_THRESHOLD, 5);
        long author = user();
        long blog = blog(author, "POST");
        for (int i = 1; i <= threshold; i++) {
            reportPost(user(), blog);
        }
        // The report past the threshold arrives while an edit has the post in PROCESSING (media being scanned):
        // the case turns URGENT but there is nothing published to hide
        jdbc.update("UPDATE blogs SET status = 'PROCESSING' WHERE id = ?", blog);
        long late = user();
        new TransactionTemplate(txManager).executeWithoutResult(tx -> {
            var outcome = reports.report(new Target("BLOG", blog, author), late, reason, null);
            assertThat(outcome.hide()).isTrue();
        });
        assertThat(priority("BLOG", blog)).isEqualTo("URGENT");

        // Published again after the scan: the next report hides it
        jdbc.update("UPDATE blogs SET status = 'PUBLISHED' WHERE id = ?", blog);
        reportPost(user(), blog);
        assertThat(status("blogs", blog)).isEqualTo("HIDDEN");
        assertThat(caseOf("BLOG", blog)).isEqualTo(threshold + 2);
    }

    @Test
    void reportsNeedAPublishedItemAndOneReportPerPerson() {
        long author = user();
        long reporter = user();
        long blog = blog(author, "POST");
        reportPost(reporter, blog);
        assertThatThrownBy(() -> reportPost(reporter, blog))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT));
        jdbc.update("UPDATE blogs SET status = 'HIDDEN' WHERE id = ?", blog);
        assertThatThrownBy(() -> reportPost(user(), blog))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.NOT_FOUND));
        assertThatThrownBy(() -> reportPost(author, blog(author, "POST")))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST));
    }

    // ---------------------------------------------------------------- helpers

    private Object choose(long asker, long question, long answer) {
        actions.chooseBest(principal(asker), question, answer);
        return "OK";
    }

    private Object deletePost(long author, long blog) {
        return outcome(() -> actions.deleteBlog(principal(author), "POST", blog));
    }

    private Object deleteComment(long author, long comment) {
        return outcome(() -> actions.deleteComment(principal(author), comment));
    }

    /** "OK", or the status of the ApiException the action threw (any other exception is thrown on). */
    private static Object outcome(Runnable action) {
        try {
            action.run();
            return "OK";
        } catch (ApiException e) {
            return e.getStatus();
        }
    }

    private Object likePost(long user, long blog, boolean on) {
        actions.likeBlog(principal(user), "POST", blog, on);
        return "OK";
    }

    private Object likeComment(long user, long comment, boolean on) {
        actions.likeComment(principal(user), comment, on);
        return "OK";
    }

    private Object savePost(long user, long blog) {
        actions.saveBlog(principal(user), "POST", blog, true);
        return "OK";
    }

    private int likeCount(String table, long id) {
        return jdbc.queryForObject("SELECT like_count FROM " + table + " WHERE id = ?", Integer.class, id);
    }

    private Object reportPost(long reporter, long blog) {
        actions.reportBlog(principal(reporter), "POST", blog, new ReportRequest(reason, null));
        return "OK";
    }

    private Object reportComment(long reporter, long comment) {
        actions.reportComment(principal(reporter), comment, new ReportRequest(reason, null));
        return "OK";
    }

    private long user() {
        String email = "race-" + UUID.randomUUID() + "@test.local";
        jdbc.update("""
                INSERT INTO users (email, display_name, role, status, is_adult_confirmed, created_at)
                VALUES (?, 'Race Test', 'USER', 'ACTIVE', TRUE, ?)""",
                email, Timestamp.from(Instant.now().minus(Duration.ofDays(30))));
        long id = jdbc.queryForObject("SELECT id FROM users WHERE email = ?", Long.class, email);
        users.add(id);
        return id;
    }

    private long blog(long authorId, String type) {
        KeyHolder key = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement("""
                    INSERT INTO blogs (author_id, blog_type, title, content, status)
                    VALUES (?, ?, 'Bài thử cùng lúc', 'nội dung', 'PUBLISHED')""", Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, authorId);
            ps.setString(2, type);
            return ps;
        }, key);
        return key.getKey().longValue();
    }

    /** A published comment (an answer on a question) inserted directly, an hour ago. */
    private long comment(long blogId, long authorId, Long parentId) {
        Timestamp at = Timestamp.from(Instant.now().minus(Duration.ofHours(1)));
        KeyHolder key = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement("""
                    INSERT INTO comments (blog_id, author_id, parent_id, content, status, created_at, updated_at)
                    VALUES (?, ?, ?, 'Câu trả lời có sẵn', 'PUBLISHED', ?, ?)""", Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, blogId);
            ps.setLong(2, authorId);
            ps.setObject(3, parentId);
            ps.setTimestamp(4, at);
            ps.setTimestamp(5, at);
            return ps;
        }, key);
        long id = key.getKey().longValue();
        jdbc.update("UPDATE blogs SET comment_count = comment_count + 1 WHERE id = ?", blogId);
        return id;
    }

    private static AppUserPrincipal principal(long userId) {
        return new AppUserPrincipal(userId, "u" + userId + "@test.local", "Race Test", "USER", "/");
    }

    private int points(long userId) {
        return jdbc.queryForObject("SELECT points FROM users WHERE id = ?", Integer.class, userId);
    }

    private int balance(long userId, long commentId) {
        return jdbc.queryForObject("""
                SELECT COALESCE(SUM(delta), 0) FROM point_transactions WHERE user_id = ? AND ref_type = 'COMMENT' AND ref_id = ?""",
                Integer.class, userId, commentId);
    }

    private Long best(long question) {
        return jdbc.queryForObject("SELECT best_comment_id FROM blogs WHERE id = ?", Long.class, question);
    }

    private String status(String table, long id) {
        return jdbc.queryForObject("SELECT status FROM " + table + " WHERE id = ?", String.class, id);
    }

    private int caseOf(String type, long id) {
        return jdbc.queryForObject("""
                SELECT report_count FROM report_cases WHERE target_type = ? AND target_id = ? AND status = 'OPEN'""",
                Integer.class, type, id);
    }

    private String priority(String type, long id) {
        return jdbc.queryForObject("""
                SELECT priority FROM report_cases WHERE target_type = ? AND target_id = ? AND status = 'OPEN'""",
                String.class, type, id);
    }
}
