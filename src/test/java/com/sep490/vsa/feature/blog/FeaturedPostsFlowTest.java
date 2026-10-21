package com.sep490.vsa.feature.blog;

import com.sep490.vsa.common.settings.SystemSettings;
import com.sep490.vsa.feature.blog.BlogDtos.BlogCard;
import com.sep490.vsa.feature.blog.BlogFeedRepository.Candidate;
import com.sep490.vsa.feature.home.HomeService;
import com.sep490.vsa.feature.moderation.ImageModerator;
import com.sep490.vsa.feature.moderation.ImageModerator.Verdict;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
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
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * "Bài nổi bật" on the Blog and the member Home, against MySQL ({@code asvsj_test}, like CommentEditFlowTest): only
 * comments by others count toward the score, and the Admin settings featured_top_n and featured_min_score decide
 * how many posts are featured and from which score. The settings are put back afterwards.
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:mysql://localhost:3306/asvsj_test?createDatabaseIfNotExist=true&useUnicode=true&characterEncoding=utf8&connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true",
        "app.media.local-dir=target/test-uploads",
        "app.moderation.retry-initial-delay-ms=3600000",
        "app.places.osm.enabled=false"
})
@AutoConfigureMockMvc
@ActiveProfiles("local")
class FeaturedPostsFlowTest {

    /** Far above any real post, so these posts are the top three of the week whatever the database holds. */
    private static final int HUGE = 1_500_000_000;

    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private BlogFeedRepository repository;
    @Autowired
    private BlogFeedService feed;
    @Autowired
    private FeaturedSnapshotService snapshots;
    @Autowired
    private HomeService home;
    @Autowired
    private PlatformTransactionManager txManager;
    @MockBean
    private ImageModerator moderator;

    private final List<Long> users = new ArrayList<>();
    private String topN;
    private String minScore;

    @BeforeEach
    void setUp() {
        when(moderator.engine()).thenReturn("OPENAI");
        when(moderator.check(any(byte[].class))).thenReturn(new Verdict("PASS", "{}"));
        topN = setting(SystemSettings.FEATURED_TOP_N);
        minScore = setting(SystemSettings.FEATURED_MIN_SCORE);
    }

    @AfterEach
    void cleanUp() {
        setSetting(SystemSettings.FEATURED_TOP_N, topN);
        setSetting(SystemSettings.FEATURED_MIN_SCORE, minScore);
        for (long id : users) {
            jdbc.update("DELETE FROM blogs WHERE author_id = ?", id);
        }
        for (long id : users) {
            jdbc.update("DELETE FROM users WHERE id = ?", id);
        }
        refresh();
    }

    @Test
    void onlyPublishedCommentsByOthersCount() {
        long author = user();
        long reader = user();
        Instant since = Instant.now().minus(Duration.ofHours(2));
        long blog = blog(author, 0);
        // Five comments by the author used to reach the minimum score 10 on their own
        for (int i = 0; i < 5; i++) {
            comment(blog, author, "PUBLISHED");
        }
        assertThat(score(blog, since)).contains(0);
        assertThat(repository.featuredCandidates(since, 10)).extracting(Candidate::blogId).doesNotContain(blog);

        for (int i = 0; i < 4; i++) {
            comment(blog, reader, "PUBLISHED");
        }
        comment(blog, reader, "HIDDEN");
        comment(blog, reader, "REMOVED");
        assertThat(score(blog, since)).contains(8);
        comment(blog, reader, "PUBLISHED");
        assertThat(score(blog, since)).contains(10);
        assertThat(repository.featuredCandidates(since, 10)).extracting(Candidate::blogId).contains(blog);
        // The comment count on the cards still counts every published comment
        assertThat(jdbc.queryForObject("SELECT comment_count FROM blogs WHERE id = ?", Integer.class, blog)).isEqualTo(10);
    }

    @Test
    void theAdminSettingsDecideHowManyPostsAndFromWhichScore() {
        long first = blog(user(), HUGE);
        long second = blog(user(), HUGE - 1);
        long third = blog(user(), HUGE - 2);
        long viewer = user();

        refresh();
        assertThat(featuredIds()).containsExactly(first, second, third);
        assertThat(home.member(viewer).featured().id()).isEqualTo(first);

        // featured_top_n = 1: the Blog shows one post at once, and the next snapshot keeps one
        setSetting(SystemSettings.FEATURED_TOP_N, "1");
        assertThat(featuredIds()).containsExactly(first);
        refresh();
        assertThat(repository.latestFeatured(FeaturedSnapshotService.WEEK, 0)).containsExactly(first);
        // Outside 1–3 (the snapshot keeps ranks 1 to 3) or not a number
        setSetting(SystemSettings.FEATURED_TOP_N, "7");
        refresh();
        assertThat(featuredIds()).containsExactly(first, second, third);
        setSetting(SystemSettings.FEATURED_TOP_N, "0");
        assertThat(snapshots.topN()).isEqualTo(1);
        setSetting(SystemSettings.FEATURED_TOP_N, "ba");
        assertThat(snapshots.topN()).isEqualTo(3);

        // featured_min_score raised above the second and third post: hidden at once on the Blog, and gone from the
        // next snapshot
        setSetting(SystemSettings.FEATURED_MIN_SCORE, String.valueOf(HUGE));
        assertThat(featuredIds()).containsExactly(first);
        refresh();
        assertThat(repository.latestFeatured(FeaturedSnapshotService.WEEK, 0)).containsExactly(first);
        assertThat(home.member(viewer).featured().id()).isEqualTo(first);

        // Above every post: nothing is featured, on the Blog or on Home
        setSetting(SystemSettings.FEATURED_MIN_SCORE, String.valueOf(Integer.MAX_VALUE));
        refresh();
        assertThat(feed.featured().posts()).isEmpty();
        assertThat(home.member(viewer).featured()).isNull();
        setSetting(SystemSettings.FEATURED_MIN_SCORE, "-5");
        assertThat(snapshots.minScore()).isZero();
    }

    // ---------------------------------------------------------------- helpers

    private List<Long> featuredIds() {
        var featured = feed.featured();
        assertThat(featured.period()).isEqualTo(FeaturedSnapshotService.WEEK);
        return featured.posts().stream().map(BlogCard::id).toList();
    }

    private Optional<Integer> score(long blog, Instant since) {
        return repository.featuredCandidates(since, 0).stream().filter(c -> c.blogId() == blog)
                .map(Candidate::score).findFirst();
    }

    private void refresh() {
        new TransactionTemplate(txManager).executeWithoutResult(tx -> snapshots.refresh());
    }

    private String setting(String key) {
        return jdbc.queryForObject("SELECT setting_value FROM system_settings WHERE setting_key = ?", String.class, key);
    }

    private void setSetting(String key, String value) {
        jdbc.update("UPDATE system_settings SET setting_value = ? WHERE setting_key = ?", value, key);
    }

    private long user() {
        String email = "feat-" + UUID.randomUUID() + "@test.local";
        jdbc.update("""
                INSERT INTO users (email, display_name, role, status, is_adult_confirmed, created_at)
                VALUES (?, 'Featured Test', 'USER', 'ACTIVE', TRUE, ?)""",
                email, Timestamp.from(Instant.now().minus(Duration.ofDays(30))));
        long id = jdbc.queryForObject("SELECT id FROM users WHERE email = ?", Long.class, email);
        users.add(id);
        return id;
    }

    /** A published post of an hour ago with this many likes. */
    private long blog(long authorId, int likes) {
        KeyHolder key = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement("""
                    INSERT INTO blogs (author_id, blog_type, title, content, status, like_count, created_at)
                    VALUES (?, 'POST', 'Bài thử nổi bật', 'nội dung', 'PUBLISHED', ?, ?)""",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, authorId);
            ps.setInt(2, likes);
            ps.setTimestamp(3, Timestamp.from(Instant.now().minus(Duration.ofHours(1))));
            return ps;
        }, key);
        return key.getKey().longValue();
    }

    /** A comment inserted directly; comment_count is kept like the app keeps it (published comments only). */
    private void comment(long blogId, long authorId, String status) {
        jdbc.update("""
                INSERT INTO comments (blog_id, author_id, content, status) VALUES (?, ?, 'Bình luận', ?)""",
                blogId, authorId, status);
        jdbc.update("""
                UPDATE blogs SET comment_count = (SELECT COUNT(*) FROM comments WHERE blog_id = ? AND status = 'PUBLISHED')
                WHERE id = ?""", blogId, blogId);
    }
}
