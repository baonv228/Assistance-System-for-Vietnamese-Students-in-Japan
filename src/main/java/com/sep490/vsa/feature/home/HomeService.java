package com.sep490.vsa.feature.home;

import com.sep490.vsa.feature.blog.ActiveAuthors;
import com.sep490.vsa.feature.blog.FeaturedSnapshotService;
import com.sep490.vsa.feature.home.HomeDtos.EventItem;
import com.sep490.vsa.feature.home.HomeDtos.Featured;
import com.sep490.vsa.feature.home.HomeDtos.FeedPost;
import com.sep490.vsa.feature.home.HomeDtos.FeedReason;
import com.sep490.vsa.feature.home.HomeDtos.GuestHome;
import com.sep490.vsa.feature.home.HomeDtos.GuideNext;
import com.sep490.vsa.feature.home.HomeDtos.GuideProgress;
import com.sep490.vsa.feature.home.HomeDtos.MemberHome;
import com.sep490.vsa.feature.home.HomeDtos.Phrase;
import com.sep490.vsa.feature.home.HomeDtos.Quote;
import com.sep490.vsa.feature.home.HomeDtos.ReviewItem;
import com.sep490.vsa.feature.home.HomeDtos.Stats;
import com.sep490.vsa.feature.home.HomeDtos.Tag;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Date;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Read-only data for the homepage. Places, reviews, events, blogs and the guide are owned by other
 * features, so this service only reads their tables with SQL and never writes to them.
 */
@Service
@Transactional(readOnly = true)
public class HomeService {

    private static final ZoneId JAPAN = ZoneId.of("Asia/Tokyo");
    private static final int EVENT_DAYS = 14;
    /** "Dành cho bạn": up to 5 posts, those of the last 30 days first, older ones fill the rest. */
    private static final int FOR_YOU_SIZE = 5;
    private static final int FOR_YOU_DAYS = 30;
    /**
     * "Chủ đề bạn quan tâm" ("cần chốt"): topics of what the user did in the last 90 days, weighted like 1, save 2,
     * comment 2 (once per post), own post or question 3; the 3 heaviest with at least 2 points, so one like is not enough.
     */
    static final int INTEREST_DAYS = 90;
    static final int INTEREST_MIN_WEIGHT = 2;
    static final int INTEREST_TOPICS = 3;
    static final String REASON_AREA = "Gần khu vực của bạn";
    static final String REASON_PREFECTURE = "Cùng tỉnh với bạn";
    static final String REASON_STAGE = "Hợp giai đoạn của bạn";
    static final String REASON_INTEREST = "Chủ đề bạn quan tâm";
    /** Blog topics that suit each stage, by name so the ids do not matter. */
    static final Map<String, List<String>> STAGE_TOPICS = Map.of(
            "PRE", List.of("Thủ tục giấy tờ", "Visa và cư trú", "Nhà ở", "Học tiếng Nhật"),
            "ARR", List.of("Kinh nghiệm", "Nhà ở", "Việc làm thêm", "Đi lại", "Ăn uống", "Y tế", "Ngân hàng và tiền",
                    "Cảnh báo lừa đảo"),
            "LEAVE", List.of("Thủ tục giấy tờ", "Visa và cư trú", "Ngân hàng và tiền", "Nhà ở"),
            "HOME", List.of("Sau khi về nước", "Kinh nghiệm"));

    private final JdbcTemplate jdbc;
    private final NamedParameterJdbcTemplate named;
    private final Clock clock;
    private final FeaturedSnapshotService featuredPosts;

    public HomeService(JdbcTemplate jdbc, Clock clock, FeaturedSnapshotService featuredPosts) {
        this.jdbc = jdbc;
        this.named = new NamedParameterJdbcTemplate(jdbc);
        this.clock = clock;
        this.featuredPosts = featuredPosts;
    }

    /**
     * Numbers, two "Hữu ích" reviews and the phrase of the day. Reviews of a locked or deleted account count nowhere
     * on Home and are never quoted ({@link ActiveAuthors}, on {@code reviews.author_id}).
     */
    public GuestHome guest() {
        Stats stats = new Stats(
                count("""
                        SELECT COUNT(DISTINCT p.id) FROM places p
                        JOIN reviews r ON r.place_id = p.id AND r.status = 'PUBLISHED' AND %s
                        WHERE p.status = 'ACTIVE'""".formatted(ActiveAuthors.of("r"))),
                count("""
                        SELECT COUNT(*) FROM reviews r JOIN places p ON p.id = r.place_id
                        WHERE r.status = 'PUBLISHED' AND p.status = 'ACTIVE' AND %s""".formatted(ActiveAuthors.of("r"))),
                count("SELECT COUNT(*) FROM phrases WHERE deleted_at IS NULL"));
        // Reviews with more than 10 likes are marked "Hữu ích"
        List<Quote> quotes = jdbc.query("""
                SELECT r.content, u.id, u.display_name, p.name_vi FROM reviews r
                JOIN users u ON u.id = r.author_id JOIN places p ON p.id = r.place_id
                WHERE r.status = 'PUBLISHED' AND p.status = 'ACTIVE' AND r.like_count > 10 AND %s
                ORDER BY r.like_count DESC, r.created_at DESC LIMIT 2""".formatted(ActiveAuthors.of("r")),
                (rs, i) -> new Quote(rs.getString(1), rs.getString(3), tagsOf(rs.getLong(2)), rs.getString(4)));
        return new GuestHome(stats, quotes, phraseOfToday());
    }

    public MemberHome member(Long userId) {
        var me = jdbc.queryForMap("""
                SELECT u.display_name, u.stage, u.area_id, a.display_name AS area_name, a.prefecture_code, u.points
                FROM users u LEFT JOIN areas a ON a.id = u.area_id WHERE u.id = ?""", userId);
        String stage = (String) me.get("stage");
        Long areaId = me.get("area_id") == null ? null : ((Number) me.get("area_id")).longValue();
        Area area = areaId == null ? null : new Area(areaId, (String) me.get("prefecture_code"));
        int points = ((Number) me.get("points")).intValue();
        String rankName = jdbc.query("SELECT name FROM ranks WHERE min_points <= ? ORDER BY min_points DESC LIMIT 1",
                (rs, i) -> rs.getString(1), points).stream().findFirst().orElse("Người mới");

        List<Topic> interests = interests(userId);
        return new MemberHome((String) me.get("display_name"), stage, areaId, (String) me.get("area_name"), points,
                rankName,
                phraseOfToday(), guide(userId, stage == null ? "ARR" : stage), events(), reviews(userId, area),
                featured(), forYou(userId, area, stage, interests), interests.stream().map(Topic::name).toList());
    }

    /** The user's area and its prefecture (null for an area without one). */
    private record Area(long id, String prefectureCode) {
    }

    private record Topic(long id, String name) {
    }

    /**
     * Topics the user cares about, learnt from what they did on posts and questions in the last {@link #INTEREST_DAYS}
     * days (likes, saves, published comments, own published posts and questions), heaviest first. "Khác" and topics
     * the Admin switched off are left out.
     */
    private List<Topic> interests(Long userId) {
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("userId", userId)
                .addValue("since", Timestamp.from(clock.instant().minus(Duration.ofDays(INTEREST_DAYS))))
                .addValue("minWeight", INTEREST_MIN_WEIGHT)
                .addValue("limit", INTEREST_TOPICS);
        return named.query("""
                SELECT t.id, t.name FROM (
                    SELECT l.blog_id, 1 AS weight FROM blog_likes l WHERE l.user_id = :userId AND l.created_at >= :since
                    UNION ALL
                    SELECT s.blog_id, 2 FROM saved_blogs s WHERE s.user_id = :userId AND s.created_at >= :since
                    UNION ALL
                    SELECT DISTINCT c.blog_id, 2 FROM comments c
                    WHERE c.author_id = :userId AND c.status = 'PUBLISHED' AND c.created_at >= :since
                    UNION ALL
                    SELECT b.id, 3 FROM blogs b
                    WHERE b.author_id = :userId AND b.status = 'PUBLISHED' AND b.created_at >= :since
                ) acts
                JOIN blog_topic_links btl ON btl.blog_id = acts.blog_id
                JOIN blog_topics t ON t.id = btl.topic_id AND t.is_active AND NOT t.is_other
                GROUP BY t.id, t.name, t.sort_order
                HAVING SUM(acts.weight) >= :minWeight
                ORDER BY SUM(acts.weight) DESC, t.sort_order, t.name
                LIMIT :limit""", params, (rs, i) -> new Topic(rs.getLong(1), rs.getString(2)));
    }

    /**
     * Published posts (not questions) by other people for "Dành cho bạn", never those of a locked or deleted account
     * ({@link ActiveAuthors}). Posts the user already liked, saved or commented on only fill the list, then posts of
     * the last 30 days come first and older ones fill the rest; within each group: linked to a place in the user's
     * area, then in the same prefecture, then a topic that suits the user's stage, then one of their interests, then
     * likes + 2 × comments, then newest. Each post says which of these reasons matched and what matched.
     */
    private List<FeedPost> forYou(Long userId, Area area, String stage, List<Topic> interests) {
        List<String> stageTopics = stage == null ? List.of() : STAGE_TOPICS.getOrDefault(stage, List.of());
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("userId", userId)
                .addValue("since", Timestamp.from(clock.instant().minus(Duration.ofDays(FOR_YOU_DAYS))))
                .addValue("limit", FOR_YOU_SIZE);
        // 2 = a linked place in the user's area, 1 = in the same prefecture, 0 = elsewhere or no place; and that
        // place's name (a merged place counts as the place it was merged into)
        String near = "0";
        String nearPlace = "NULL";
        if (area != null) {
            params.addValue("areaId", area.id()).addValue("prefecture", area.prefectureCode());
            String linked = """
                    FROM blog_place_links bpl JOIN places p ON p.id = bpl.place_id
                    JOIN places shown ON shown.id = CASE WHEN p.status = 'MERGED' THEN p.merged_into_id ELSE p.id END
                    LEFT JOIN areas sa ON sa.id = shown.area_id
                    WHERE bpl.blog_id = b.id AND shown.status = 'ACTIVE'""";
            near = """
                    COALESCE((SELECT MAX(CASE WHEN shown.area_id = :areaId THEN 2
                                              WHEN sa.prefecture_code = :prefecture THEN 1 ELSE 0 END)
                              %s), 0)""".formatted(linked);
            nearPlace = """
                    (SELECT shown.name_vi %s AND (shown.area_id = :areaId OR sa.prefecture_code = :prefecture)
                     ORDER BY shown.area_id = :areaId DESC, shown.name_vi LIMIT 1)""".formatted(linked);
        }
        String stageTopic = "NULL";
        if (!stageTopics.isEmpty()) {
            params.addValue("stageTopics", stageTopics);
            stageTopic = """
                    (SELECT t.name FROM blog_topic_links btl JOIN blog_topics t ON t.id = btl.topic_id
                     WHERE btl.blog_id = b.id AND t.is_active AND t.name IN (:stageTopics)
                     ORDER BY t.is_other, t.sort_order, t.name LIMIT 1)""";
        }
        // The user's strongest interest among the post's topics
        String interestTopic = "NULL";
        if (!interests.isEmpty()) {
            params.addValue("interestIds", interests.stream().map(Topic::id).toList());
            interestTopic = """
                    (SELECT t.name FROM blog_topic_links btl JOIN blog_topics t ON t.id = btl.topic_id
                     WHERE btl.blog_id = b.id AND t.id IN (:interestIds) ORDER BY FIELD(t.id, :interestIds) LIMIT 1)""";
        }
        return named.query("""
                SELECT * FROM (
                    SELECT b.id, b.title, LEFT(b.content, 400) AS content_start, u.display_name, b.like_count,
                           b.comment_count, b.created_at, b.created_at >= :since AS recent,
                           (SELECT t.name FROM blog_topic_links btl JOIN blog_topics t ON t.id = btl.topic_id
                            WHERE btl.blog_id = b.id AND t.is_active ORDER BY t.is_other, t.sort_order, t.name LIMIT 1)
                             AS topic_name,
                           %s AS near, %s AS near_place, %s AS stage_topic, %s AS interest_topic,
                           (EXISTS (SELECT 1 FROM blog_likes l WHERE l.blog_id = b.id AND l.user_id = :userId)
                            OR EXISTS (SELECT 1 FROM saved_blogs s WHERE s.blog_id = b.id AND s.user_id = :userId)
                            OR EXISTS (SELECT 1 FROM comments c WHERE c.blog_id = b.id AND c.author_id = :userId))
                             AS engaged
                    FROM blogs b JOIN users u ON u.id = b.author_id
                    WHERE b.status = 'PUBLISHED' AND b.blog_type = 'POST' AND b.author_id <> :userId AND %s
                ) f
                ORDER BY engaged, recent DESC, near DESC, stage_topic IS NOT NULL DESC, interest_topic IS NOT NULL DESC,
                         like_count + 2 * comment_count DESC, created_at DESC, id DESC
                LIMIT :limit""".formatted(near, nearPlace, stageTopic, interestTopic, ActiveAuthors.of("b")),
                params,
                (rs, i) -> {
                    List<FeedReason> reasons = new ArrayList<>();
                    int nearRank = rs.getInt("near");
                    if (nearRank > 0) {
                        reasons.add(nearRank == 2
                                ? new FeedReason("AREA", REASON_AREA, rs.getString("near_place"))
                                : new FeedReason("PREFECTURE", REASON_PREFECTURE, rs.getString("near_place")));
                    }
                    if (rs.getString("stage_topic") != null) {
                        reasons.add(new FeedReason("STAGE", REASON_STAGE, rs.getString("stage_topic")));
                    }
                    if (rs.getString("interest_topic") != null) {
                        reasons.add(new FeedReason("INTEREST", REASON_INTEREST, rs.getString("interest_topic")));
                    }
                    return new FeedPost(rs.getLong("id"), rs.getString("title"),
                            excerpt(rs.getString("content_start"), 140), rs.getString("display_name"),
                            rs.getString("topic_name"), rs.getInt("like_count"), rs.getInt("comment_count"),
                            rs.getTimestamp("created_at").toInstant(),
                            reasons.isEmpty() ? null : reasons.get(0).label(), List.copyOf(reasons));
                });
    }

    private GuideProgress guide(Long userId, String stageCode) {
        String title = jdbc.query("SELECT title FROM guide_stages WHERE code = ?", (rs, i) -> rs.getString(1), stageCode)
                .stream().findFirst().orElse(stageCode);
        record Item(String title, String due, String place, Long placeId, boolean done) {
        }
        List<Item> items = jdbc.query("""
                SELECT gi.title, gi.due_note, p.name_vi, p.id, (ugp.item_id IS NOT NULL) FROM guide_items gi
                LEFT JOIN places p ON p.id = gi.place_id AND p.status = 'ACTIVE'
                LEFT JOIN user_guide_progress ugp ON ugp.item_id = gi.id AND ugp.user_id = ?
                WHERE gi.stage_code = ? AND gi.published = TRUE AND gi.deleted_at IS NULL ORDER BY gi.sort_order, gi.id""",
                (rs, i) -> new Item(rs.getString(1), rs.getString(2), rs.getString(3), rs.getObject(4, Long.class),
                        rs.getBoolean(5)),
                userId, stageCode);
        int done = (int) items.stream().filter(Item::done).count();
        GuideNext next = items.stream().filter(it -> !it.done()).findFirst()
                .map(it -> new GuideNext(it.title(), it.due(), it.place(), it.placeId())).orElse(null);
        return new GuideProgress(stageCode, title, done, items.size(), next);
    }

    private List<EventItem> events() {
        LocalDate today = LocalDate.now(clock.withZone(JAPAN));
        return jdbc.query("""
                SELECT id, title, start_date, end_date, location_name FROM events
                WHERE deleted_at IS NULL AND end_date >= ? AND start_date <= ?
                ORDER BY start_date, id LIMIT 3""",
                (rs, i) -> {
                    LocalDate start = rs.getDate(3).toLocalDate();
                    return new EventItem(rs.getLong(1), rs.getString(2), start, rs.getDate(4).toLocalDate(),
                            rs.getString(5),
                            Math.max(0, ChronoUnit.DAYS.between(today, start)));
                },
                Date.valueOf(today), Date.valueOf(today.plusDays(EVENT_DAYS)));
    }

    /**
     * "Review mới": newest reviews by other people, those in the user's area first, then its prefecture; never those of
     * a locked or deleted account ({@link ActiveAuthors}).
     */
    private List<ReviewItem> reviews(Long userId, Area area) {
        return jdbc.query("""
                SELECT p.name_vi, c.icon, r.rating, u.display_name, r.content FROM reviews r
                JOIN places p ON p.id = r.place_id JOIN place_categories c ON c.id = p.category_id
                JOIN users u ON u.id = r.author_id LEFT JOIN areas pa ON pa.id = p.area_id
                WHERE r.status = 'PUBLISHED' AND p.status = 'ACTIVE' AND r.author_id <> ? AND %s
                ORDER BY CASE WHEN p.area_id = ? THEN 0 WHEN pa.prefecture_code = ? THEN 1 ELSE 2 END,
                         r.created_at DESC LIMIT 3"""
                        .formatted(ActiveAuthors.of("r")),
                (rs, i) -> new ReviewItem(rs.getString(1), rs.getString(2), rs.getInt(3), rs.getString(4),
                        excerpt(rs.getString(5), 80)),
                userId, area == null ? null : area.id(), area == null ? null : area.prefectureCode());
    }

    /**
     * Top post (not a question) of the last 7 days, or of the last 30 days if the week has none. The score, the
     * minimum (featured_min_score) and the authors counted are those of the Blog's "Bài nổi bật"
     * ({@link FeaturedSnapshotService#best}: posts and comments of active accounts only), computed now rather than
     * read from the hourly snapshot.
     */
    private Featured featured() {
        for (var period : List.of(new Object[]{"WEEK", 7}, new Object[]{"MONTH", 30})) {
            var best = featuredPosts.best(clock.instant().minus(Duration.ofDays((int) period[1])));
            if (best.isEmpty()) {
                continue;
            }
            List<Featured> top = jdbc.query("""
                    SELECT b.id, b.title, b.content, u.id, u.display_name
                    FROM blogs b JOIN users u ON u.id = b.author_id WHERE b.id = ?""",
                    (rs, i) -> {
                        String content = rs.getString(3);
                        int words = content.split("\\s+").length;
                        return new Featured(rs.getLong(1), rs.getString(2), excerpt(content, 140), rs.getString(5),
                                tagsOf(rs.getLong(4)), Math.max(1, words / 200), (String) period[0]);
                    },
                    best.get().blogId());
            if (!top.isEmpty()) {
                return top.get(0);
            }
        }
        return null;
    }

    /** The same phrase for everyone on a given day; it changes every day. */
    private Phrase phraseOfToday() {
        long total = count("SELECT COUNT(*) FROM phrases WHERE kind = 'PHRASE' AND deleted_at IS NULL");
        if (total == 0) {
            return null;
        }
        long offset = LocalDate.now(clock.withZone(JAPAN)).toEpochDay() % total;
        return jdbc.query("""
                SELECT ph.japanese, ph.romaji, ph.meaning_vi, c.name_vi, c.icon FROM phrases ph
                JOIN place_categories c ON c.id = ph.category_id
                WHERE ph.kind = 'PHRASE' AND ph.deleted_at IS NULL ORDER BY ph.id LIMIT 1 OFFSET ?""",
                (rs, i) -> new Phrase(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4),
                        rs.getString(5)), offset).stream().findFirst().orElse(null);
    }

    /** Approved, not revoked identity tags whose tag type is still active (like the Blog's tags). */
    private List<Tag> tagsOf(long userId) {
        return new ArrayList<>(jdbc.query("""
                SELECT t.name, t.color FROM user_tags ut JOIN tag_types t ON t.id = ut.tag_type_id
                WHERE ut.user_id = ? AND ut.revoked_at IS NULL AND t.is_active""",
                (rs, i) -> new Tag(rs.getString(1), rs.getString(2)), userId));
    }

    private long count(String sql) {
        Long value = jdbc.queryForObject(sql, Long.class);
        return value == null ? 0 : value;
    }

    static String excerpt(String text, int max) {
        if (text == null) {
            return "";
        }
        String flat = text.replaceAll("\\s+", " ").trim();
        return flat.length() <= max ? flat : flat.substring(0, max).trim() + "…";
    }
}
