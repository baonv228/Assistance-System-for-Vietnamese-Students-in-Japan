package com.sep490.vsa.feature.home;

import com.sep490.vsa.feature.blog.ActiveAuthors;
import com.sep490.vsa.feature.blog.FeaturedSnapshotService;
import com.sep490.vsa.feature.home.HomeDtos.EventItem;
import com.sep490.vsa.feature.home.HomeDtos.Featured;
import com.sep490.vsa.feature.home.HomeDtos.FeedPost;
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
import java.util.Collections;
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
    static final String REASON_AREA = "Gần khu vực của bạn";
    static final String REASON_PREFECTURE = "Cùng tỉnh với bạn";
    static final String REASON_STAGE = "Hợp giai đoạn của bạn";
    /** Blog topics that suit each stage, by name so the ids do not matter. */
    static final Map<String, List<String>> STAGE_TOPICS = Map.of(
            "PRE", List.of("Thủ tục giấy tờ", "Visa và cư trú", "Nhà ở", "Học tiếng Nhật"),
            "ARR", List.of("Kinh nghiệm", "Nhà ở", "Việc làm thêm", "Đi lại", "Ăn uống", "Y tế", "Ngân hàng và tiền",
                    "Cảnh báo lừa đảo"),
            "LEAVE", List.of("Thủ tục giấy tờ", "Visa và cư trú", "Ngân hàng và tiền", "Nhà ở"),
            "HOME", List.of("Sau khi về nước", "Kinh nghiệm"));

    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final FeaturedSnapshotService featuredPosts;

    public HomeService(JdbcTemplate jdbc, Clock clock, FeaturedSnapshotService featuredPosts) {
        this.jdbc = jdbc;
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

        return new MemberHome((String) me.get("display_name"), stage, areaId, (String) me.get("area_name"), points,
                rankName,
                phraseOfToday(), guide(userId, stage == null ? "ARR" : stage), events(), reviews(userId, area),
                featured(), forYou(userId, area, stage));
    }

    /** The user's area and its prefecture (null for an area without one). */
    private record Area(long id, String prefectureCode) {
    }

    /**
     * Published posts (not questions) by other people for "Dành cho bạn", never those of a locked or deleted account
     * ({@link ActiveAuthors}). Posts of the last 30 days come first and older ones only fill the list; within each
     * group: linked to a place in the user's area, then in the same prefecture, then a topic that suits the user's
     * stage, then likes + 2 × comments, then newest.
     */
    private List<FeedPost> forYou(Long userId, Area area, String stage) {
        List<String> topics = stage == null ? List.of() : STAGE_TOPICS.getOrDefault(stage, List.of());
        // 2 = a linked place in the user's area, 1 = in the same prefecture, 0 = elsewhere or no place
        String near = area == null ? "0" : """
                COALESCE((SELECT MAX(CASE WHEN shown.area_id = ? THEN 2 WHEN sa.prefecture_code = ? THEN 1 ELSE 0 END)
                          FROM blog_place_links bpl JOIN places p ON p.id = bpl.place_id
                          JOIN places shown ON shown.id = CASE WHEN p.status = 'MERGED' THEN p.merged_into_id ELSE p.id END
                          LEFT JOIN areas sa ON sa.id = shown.area_id
                          WHERE bpl.blog_id = b.id AND shown.status = 'ACTIVE'), 0)""";
        String fits = topics.isEmpty() ? "FALSE" : """
                EXISTS (SELECT 1 FROM blog_topic_links btl JOIN blog_topics t ON t.id = btl.topic_id
                        WHERE btl.blog_id = b.id AND t.is_active AND t.name IN (%s))"""
                .formatted(String.join(", ", Collections.nCopies(topics.size(), "?")));
        List<Object> params = new ArrayList<>();
        if (area != null) {
            params.add(area.id());
            params.add(area.prefectureCode());
        }
        params.addAll(topics);
        params.add(userId);
        params.add(Timestamp.from(clock.instant().minus(Duration.ofDays(FOR_YOU_DAYS))));
        params.add(FOR_YOU_SIZE);
        return jdbc.query("""
                SELECT b.id, b.title, LEFT(b.content, 400) AS content_start, u.display_name, b.like_count,
                       b.comment_count, b.created_at,
                       (SELECT t.name FROM blog_topic_links btl JOIN blog_topics t ON t.id = btl.topic_id
                        WHERE btl.blog_id = b.id AND t.is_active ORDER BY t.is_other, t.sort_order, t.name LIMIT 1)
                         AS topic_name,
                       %s AS near, %s AS fits
                FROM blogs b JOIN users u ON u.id = b.author_id
                WHERE b.status = 'PUBLISHED' AND b.blog_type = 'POST' AND b.author_id <> ? AND %s
                ORDER BY (b.created_at >= ?) DESC, near DESC, fits DESC, b.like_count + 2 * b.comment_count DESC,
                         b.created_at DESC, b.id DESC
                LIMIT ?""".formatted(near, fits, ActiveAuthors.of("b")),
                (rs, i) -> new FeedPost(rs.getLong("id"), rs.getString("title"),
                        excerpt(rs.getString("content_start"), 140), rs.getString("display_name"),
                        rs.getString("topic_name"), rs.getInt("like_count"), rs.getInt("comment_count"),
                        rs.getTimestamp("created_at").toInstant(),
                        rs.getInt("near") == 2 ? REASON_AREA : rs.getInt("near") == 1 ? REASON_PREFECTURE
                                : rs.getBoolean("fits") ? REASON_STAGE : null),
                params.toArray());
    }

    private GuideProgress guide(Long userId, String stageCode) {
        String title = jdbc.query("SELECT title FROM guide_stages WHERE code = ?", (rs, i) -> rs.getString(1), stageCode)
                .stream().findFirst().orElse(stageCode);
        record Item(String title, String due, String place, boolean done) {
        }
        List<Item> items = jdbc.query("""
                SELECT gi.title, gi.due_note, p.name_vi, (ugp.item_id IS NOT NULL) FROM guide_items gi
                LEFT JOIN places p ON p.id = gi.place_id AND p.status = 'ACTIVE'
                LEFT JOIN user_guide_progress ugp ON ugp.item_id = gi.id AND ugp.user_id = ?
                WHERE gi.stage_code = ? ORDER BY gi.sort_order, gi.id""",
                (rs, i) -> new Item(rs.getString(1), rs.getString(2), rs.getString(3), rs.getBoolean(4)),
                userId, stageCode);
        int done = (int) items.stream().filter(Item::done).count();
        GuideNext next = items.stream().filter(it -> !it.done()).findFirst()
                .map(it -> new GuideNext(it.title(), it.due(), it.place())).orElse(null);
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

    private static String excerpt(String text, int max) {
        if (text == null) {
            return "";
        }
        String flat = text.replaceAll("\\s+", " ").trim();
        return flat.length() <= max ? flat : flat.substring(0, max).trim() + "…";
    }
}
