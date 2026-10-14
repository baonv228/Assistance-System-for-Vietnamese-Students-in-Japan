package com.sep490.vsa.feature.home;

import com.sep490.vsa.feature.home.HomeDtos.EventItem;
import com.sep490.vsa.feature.home.HomeDtos.Featured;
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
import java.util.List;

/**
 * Read-only data for the homepage. Places, reviews, events, blogs and the guide are owned by other
 * features, so this service only reads their tables with SQL and never writes to them.
 */
@Service
@Transactional(readOnly = true)
public class HomeService {

    private static final ZoneId JAPAN = ZoneId.of("Asia/Tokyo");
    private static final int EVENT_DAYS = 14;
    /** Featured posts: score = likes + 2 × comments + rank bonus, at least 10. */
    private static final int FEATURED_MIN_SCORE = 10;

    private final JdbcTemplate jdbc;
    private final Clock clock;

    public HomeService(JdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    public GuestHome guest() {
        Stats stats = new Stats(
                count("""
                        SELECT COUNT(DISTINCT p.id) FROM places p
                        JOIN reviews r ON r.place_id = p.id AND r.status = 'PUBLISHED'
                        WHERE p.status = 'ACTIVE'"""),
                count("""
                        SELECT COUNT(*) FROM reviews r JOIN places p ON p.id = r.place_id
                        WHERE r.status = 'PUBLISHED' AND p.status = 'ACTIVE'"""),
                count("SELECT COUNT(*) FROM phrases WHERE deleted_at IS NULL"));
        // Reviews with more than 10 likes are marked "Hữu ích"
        List<Quote> quotes = jdbc.query("""
                SELECT r.content, u.id, u.display_name, p.name_vi FROM reviews r
                JOIN users u ON u.id = r.author_id JOIN places p ON p.id = r.place_id
                WHERE r.status = 'PUBLISHED' AND p.status = 'ACTIVE' AND r.like_count > 10
                ORDER BY r.like_count DESC, r.created_at DESC LIMIT 2""",
                (rs, i) -> new Quote(rs.getString(1), rs.getString(3), tagsOf(rs.getLong(2)), rs.getString(4)));
        return new GuestHome(stats, quotes, phraseOfToday());
    }

    public MemberHome member(Long userId) {
        var me = jdbc.queryForMap("""
                SELECT u.display_name, u.stage, u.area_id, a.display_name AS area_name, u.points
                FROM users u LEFT JOIN areas a ON a.id = u.area_id WHERE u.id = ?""", userId);
        String stage = (String) me.get("stage");
        Long areaId = me.get("area_id") == null ? null : ((Number) me.get("area_id")).longValue();
        int points = ((Number) me.get("points")).intValue();
        String rankName = jdbc.query("SELECT name FROM ranks WHERE min_points <= ? ORDER BY min_points DESC LIMIT 1",
                (rs, i) -> rs.getString(1), points).stream().findFirst().orElse("Người mới");

        return new MemberHome((String) me.get("display_name"), stage, (String) me.get("area_name"), points, rankName,
                phraseOfToday(), guide(userId, stage == null ? "ARR" : stage), events(), reviews(userId, areaId),
                featured());
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
                SELECT title, start_date, end_date, location_name FROM events
                WHERE deleted_at IS NULL AND end_date >= ? AND start_date <= ?
                ORDER BY start_date LIMIT 3""",
                (rs, i) -> {
                    LocalDate start = rs.getDate(2).toLocalDate();
                    return new EventItem(rs.getString(1), start, rs.getDate(3).toLocalDate(), rs.getString(4),
                            Math.max(0, ChronoUnit.DAYS.between(today, start)));
                },
                Date.valueOf(today), Date.valueOf(today.plusDays(EVENT_DAYS)));
    }

    /** Newest reviews by other people, those in the user's area first. */
    private List<ReviewItem> reviews(Long userId, Long areaId) {
        return jdbc.query("""
                SELECT p.name_vi, c.icon, r.rating, u.display_name, r.content FROM reviews r
                JOIN places p ON p.id = r.place_id JOIN place_categories c ON c.id = p.category_id
                JOIN users u ON u.id = r.author_id
                WHERE r.status = 'PUBLISHED' AND p.status = 'ACTIVE' AND r.author_id <> ?
                ORDER BY CASE WHEN p.area_id = ? THEN 0 ELSE 1 END, r.created_at DESC LIMIT 3""",
                (rs, i) -> new ReviewItem(rs.getString(1), rs.getString(2), rs.getInt(3), rs.getString(4),
                        excerpt(rs.getString(5), 80)),
                userId, areaId);
    }

    /** Top post of the last 7 days, or of the last 30 days if the week has none. */
    private Featured featured() {
        for (var period : List.of(new Object[]{"WEEK", 7}, new Object[]{"MONTH", 30})) {
            Timestamp since = Timestamp.from(clock.instant().minus(Duration.ofDays((int) period[1])));
            List<Featured> top = jdbc.query("""
                    SELECT b.id, b.title, b.content, u.id, u.display_name,
                           b.like_count + 2 * b.comment_count
                             + COALESCE((SELECT MAX(r.featured_bonus) FROM ranks r WHERE r.min_points <= u.points), 0)
                             AS score
                    FROM blogs b JOIN users u ON u.id = b.author_id
                    WHERE b.status = 'PUBLISHED' AND b.created_at >= ?
                    HAVING score >= ? ORDER BY score DESC, b.created_at DESC LIMIT 1""",
                    (rs, i) -> {
                        String content = rs.getString(3);
                        int words = content.split("\\s+").length;
                        return new Featured(rs.getLong(1), rs.getString(2), excerpt(content, 140), rs.getString(5),
                                tagsOf(rs.getLong(4)), Math.max(1, words / 200), (String) period[0]);
                    },
                    since, FEATURED_MIN_SCORE);
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

    private List<Tag> tagsOf(long userId) {
        return new ArrayList<>(jdbc.query("""
                SELECT t.name, t.color FROM user_tags ut JOIN tag_types t ON t.id = ut.tag_type_id
                WHERE ut.user_id = ? AND ut.revoked_at IS NULL""",
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
