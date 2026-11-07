package com.sep490.vsa.feature.home;

import com.sep490.vsa.common.exception.ApiException;
import com.sep490.vsa.common.text.VietnameseText;
import com.sep490.vsa.feature.blog.ActiveAuthors;
import com.sep490.vsa.feature.blog.FeaturedSnapshotService;
import com.sep490.vsa.feature.guide.GuideProgressService;
import com.sep490.vsa.feature.guide.GuideProgressService.State;
import com.sep490.vsa.feature.home.HomeDtos.EventItem;
import com.sep490.vsa.feature.home.HomeDtos.Featured;
import com.sep490.vsa.feature.home.HomeDtos.FeedPost;
import com.sep490.vsa.feature.home.HomeDtos.FeedReason;
import com.sep490.vsa.feature.home.HomeDtos.ForYouFeed;
import com.sep490.vsa.feature.home.HomeDtos.GuestHome;
import com.sep490.vsa.feature.home.HomeDtos.GuideNext;
import com.sep490.vsa.feature.home.HomeDtos.GuideProgress;
import com.sep490.vsa.feature.home.HomeDtos.MemberHome;
import com.sep490.vsa.feature.home.HomeDtos.Phrase;
import com.sep490.vsa.feature.home.HomeDtos.Quote;
import com.sep490.vsa.feature.home.HomeDtos.ReviewItem;
import com.sep490.vsa.feature.home.HomeDtos.ScopeCount;
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
 * features, so this service only reads their tables with SQL and never writes to them. Whether a guide is done is
 * the guide feature's rule, read through {@link GuideProgressService}.
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
     * Page "Dành cho bạn" ("Xem tất cả"): the same ranking 10 posts at a time, at most 200 pages deep, like "Review mới"
     * ("cần chốt").
     */
    static final int FOR_YOU_PAGE_SIZE = 10;
    static final int FOR_YOU_MAX_PAGE = 199;
    /** "Tìm trong bài dành cho bạn": as long as the Blog's search text. */
    static final int FOR_YOU_KEYWORD_MAX = 100;

    /** The parts of the page "Dành cho bạn": every post, or only those that matched one kind of reason. */
    enum ForYouScope { ALL, NEAR, STAGE, INTEREST }

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
    /** "Việc tiếp theo" for a user who has not chosen a stage yet. */
    static final String DEFAULT_GUIDE_STAGE = "ARR";

    private final JdbcTemplate jdbc;
    private final NamedParameterJdbcTemplate named;
    private final Clock clock;
    private final FeaturedSnapshotService featuredPosts;
    private final GuideProgressService guideProgress;

    public HomeService(JdbcTemplate jdbc, Clock clock, FeaturedSnapshotService featuredPosts,
                       GuideProgressService guideProgress) {
        this.jdbc = jdbc;
        this.named = new NamedParameterJdbcTemplate(jdbc);
        this.clock = clock;
        this.featuredPosts = featuredPosts;
        this.guideProgress = guideProgress;
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
        Me me = me(userId);
        String rankName = jdbc.query("SELECT name FROM ranks WHERE min_points <= ? ORDER BY min_points DESC LIMIT 1",
                (rs, i) -> rs.getString(1), me.points()).stream().findFirst().orElse("Người mới");

        List<Topic> interests = interests(userId);
        Ranking forYou = ranking(userId, me, interests, null);
        // "Việc tiếp theo" reads the user's stage (ARR until one is chosen)
        String guideStage = me.stage() == null ? DEFAULT_GUIDE_STAGE : me.stage();
        List<StageGuide> guides = stageGuides(guideStage);
        Map<Long, State> states = guideProgress.states(userId, guides.stream().map(StageGuide::id).toList());
        GuideProgress guide = guide(guideStage, guides, states);
        return new MemberHome(me.displayName(), me.stage(), me.area() == null ? null : me.area().id(), me.areaName(),
                me.points(), rankName,
                phraseOfToday(), guide, events(),
                reviews(userId, me.area()), featured(), posts(forYou, ForYouScope.ALL, FOR_YOU_SIZE, 0),
                interests.stream().map(Topic::name).toList());
    }

    /**
     * Page "Dành cho bạn" ("Xem tất cả" on the member Home): the whole personal feed, ranked exactly like the Home's
     * five posts ({@link #ranking}), so it starts with them and goes on with every other published post by others.
     * {@code scope} NEAR, STAGE or INTEREST keeps only the posts that matched that reason, in the same order; a scope
     * this member cannot use (no area, no stage, no interest yet), an unknown one or none is ALL. {@code page} is kept
     * within 0–{@value #FOR_YOU_MAX_PAGE}. {@code q} ("Tìm trong bài dành cho bạn") keeps only the posts whose title,
     * content or linked place holds it, as the Blog's search (accents and case ignored, at most
     * {@value #FOR_YOU_KEYWORD_MAX} characters, else 400); the order, the reasons and the scope counts stay those of
     * the feed, counted over the posts found.
     */
    public ForYouFeed forYouPage(Long userId, String scope, int page, String q) {
        String text = q == null ? "" : q.strip();
        if (text.length() > FOR_YOU_KEYWORD_MAX) {
            throw ApiException.badRequest("Từ khóa tìm kiếm tối đa " + FOR_YOU_KEYWORD_MAX + " ký tự.");
        }
        String keyword = VietnameseText.fold(text);
        Me me = me(userId);
        List<Topic> interests = interests(userId);
        Ranking ranking = ranking(userId, me, interests, keyword.isEmpty() ? null : keyword);
        Map<String, Object> counts = named.queryForMap("""
                SELECT COUNT(*) AS total, COALESCE(SUM(f.near > 0), 0) AS near,
                       COALESCE(SUM(f.stage_topic IS NOT NULL), 0) AS stage,
                       COALESCE(SUM(f.interest_topic IS NOT NULL), 0) AS interest
                FROM (%s) f""".formatted(ranking.select()), ranking.params());
        List<ScopeCount> scopes = new ArrayList<>();
        scopes.add(new ScopeCount(ForYouScope.ALL.name(), ((Number) counts.get("total")).longValue()));
        if (me.area() != null) {
            scopes.add(new ScopeCount(ForYouScope.NEAR.name(), ((Number) counts.get("near")).longValue()));
        }
        if (!stageTopics(me.stage()).isEmpty()) {
            scopes.add(new ScopeCount(ForYouScope.STAGE.name(), ((Number) counts.get("stage")).longValue()));
        }
        if (!interests.isEmpty()) {
            scopes.add(new ScopeCount(ForYouScope.INTEREST.name(), ((Number) counts.get("interest")).longValue()));
        }
        String chosen = scopes.stream().map(ScopeCount::code).filter(code -> code.equals(scope)).findFirst()
                .orElse(ForYouScope.ALL.name());

        int shownPage = Math.min(Math.max(0, page), FOR_YOU_MAX_PAGE);
        List<FeedPost> rows = posts(ranking, ForYouScope.valueOf(chosen), FOR_YOU_PAGE_SIZE + 1,
                shownPage * FOR_YOU_PAGE_SIZE);
        boolean hasMore = rows.size() > FOR_YOU_PAGE_SIZE && shownPage < FOR_YOU_MAX_PAGE;
        return new ForYouFeed(chosen, keyword.isEmpty() ? null : text, me.areaName(), me.stage(),
                interests.stream().map(Topic::name).toList(), scopes,
                shownPage, hasMore, rows.size() > FOR_YOU_PAGE_SIZE ? rows.subList(0, FOR_YOU_PAGE_SIZE) : rows);
    }

    /** What the member Home and "Dành cho bạn" read about the user. */
    private record Me(String displayName, String stage, Area area, String areaName, int points) {
    }

    private Me me(Long userId) {
        var row = jdbc.queryForMap("""
                SELECT u.display_name, u.stage, u.area_id, a.display_name AS area_name, a.prefecture_code, u.points
                FROM users u LEFT JOIN areas a ON a.id = u.area_id WHERE u.id = ?""", userId);
        Long areaId = row.get("area_id") == null ? null : ((Number) row.get("area_id")).longValue();
        return new Me((String) row.get("display_name"), (String) row.get("stage"),
                areaId == null ? null : new Area(areaId, (String) row.get("prefecture_code")),
                (String) row.get("area_name"), ((Number) row.get("points")).intValue());
    }

    /** The user's area and its prefecture (null for an area without one). */
    private record Area(long id, String prefectureCode) {
    }

    private record Topic(long id, String name) {
    }

    private static List<String> stageTopics(String stage) {
        return stage == null ? List.of() : STAGE_TOPICS.getOrDefault(stage, List.of());
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
     * The personal feed's candidates as one SELECT (read as {@code f} by the caller) with its parameters: every
     * published post (not question) by other people, never those of a locked or deleted account
     * ({@link ActiveAuthors}), each with what it matched: {@code near} (2 = a linked place in the user's area, 1 = in
     * the same prefecture), {@code stage_topic}, {@code interest_topic}, plus {@code recent} and {@code engaged}.
     */
    private record Ranking(String select, MapSqlParameterSource params) {
    }

    /**
     * How "Dành cho bạn" ranks (see {@link #posts}): posts the user already liked, saved or commented on only fill the
     * list, then posts of the last 30 days come first and older ones fill the rest; within each group: linked to a
     * place in the user's area, then in the same prefecture, then a topic that suits the user's stage, then one of
     * their interests, then likes + 2 × comments, then newest. Each post says which of these reasons matched and what
     * matched. {@code keyword} (already {@link VietnameseText#fold folded}, null for none) keeps only the posts whose
     * title, content or the name of an approved linked place holds it, the Blog's search rule.
     */
    private Ranking ranking(Long userId, Me me, List<Topic> interests, String keyword) {
        Area area = me.area();
        List<String> stageTopics = stageTopics(me.stage());
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("userId", userId)
                .addValue("since", Timestamp.from(clock.instant().minus(Duration.ofDays(FOR_YOU_DAYS))));
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
        // As BlogFeedRepository: MySQL's accent- and case-insensitive collation on the columns, the text folded here
        String search = "";
        if (keyword != null) {
            params.addValue("kw", "%" + escapeLike(keyword) + "%");
            search = """
                     AND (b.title COLLATE utf8mb4_0900_ai_ci LIKE :kw OR b.content COLLATE utf8mb4_0900_ai_ci LIKE :kw
                          OR EXISTS (SELECT 1 FROM blog_place_links kbl JOIN places kp ON kp.id = kbl.place_id
                                     WHERE kbl.blog_id = b.id AND kp.status = 'ACTIVE'
                                       AND (kp.name_vi COLLATE utf8mb4_0900_ai_ci LIKE :kw
                                            OR kp.name_ja COLLATE utf8mb4_0900_ai_ci LIKE :kw
                                            OR kp.name_en COLLATE utf8mb4_0900_ai_ci LIKE :kw)))""";
        }
        String select = """
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
                WHERE b.status = 'PUBLISHED' AND b.blog_type = 'POST' AND b.author_id <> :userId AND %s %s"""
                .formatted(near, nearPlace, stageTopic, interestTopic, ActiveAuthors.of("b"), search);
        return new Ranking(select, params);
    }

    static String escapeLike(String text) {
        return text.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    /**
     * {@code limit} posts of the ranked feed from {@code offset} on, all of them ({@code ALL}) or only those that
     * matched one reason. The order ends with the post id, so a page always follows the one before it.
     */
    private List<FeedPost> posts(Ranking ranking, ForYouScope scope, int limit, int offset) {
        String only = switch (scope) {
            case ALL -> "";
            case NEAR -> "WHERE f.near > 0";
            case STAGE -> "WHERE f.stage_topic IS NOT NULL";
            case INTEREST -> "WHERE f.interest_topic IS NOT NULL";
        };
        MapSqlParameterSource params = new MapSqlParameterSource(ranking.params().getValues())
                .addValue("limit", limit)
                .addValue("offset", offset);
        return named.query("""
                SELECT * FROM (%s) f %s
                ORDER BY engaged, recent DESC, near DESC, stage_topic IS NOT NULL DESC, interest_topic IS NOT NULL DESC,
                         like_count + 2 * comment_count DESC, created_at DESC, id DESC
                LIMIT :limit OFFSET :offset""".formatted(ranking.select(), only),
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
                            reasons.isEmpty() ? null : reasons.get(0).label(), List.copyOf(reasons),
                            rs.getBoolean("engaged"));
                });
    }

    /**
     * A guide members can read (published, not deleted) of one stage, with its place only while that place is ACTIVE.
     */
    private record StageGuide(long id, String title, String dueNote, String placeName, Long placeId) {
    }

    /** The stage's guides in guide order ({@code sort_order}, then id), as on the guide pages. */
    private List<StageGuide> stageGuides(String stageCode) {
        return jdbc.query("""
                SELECT gi.id, gi.title, gi.due_note, p.name_vi AS place_name, p.id AS place_id
                FROM guide_items gi
                LEFT JOIN places p ON p.id = gi.place_id AND p.status = 'ACTIVE'
                WHERE gi.stage_code = ? AND gi.published = TRUE AND gi.deleted_at IS NULL
                ORDER BY gi.sort_order, gi.id""",
                (rs, i) -> new StageGuide(rs.getLong("id"), rs.getString("title"), rs.getString("due_note"),
                        rs.getString("place_name"), rs.getObject("place_id", Long.class)),
                stageCode);
    }

    /**
     * "Việc tiếp theo": how many of the stage's guides the user finished (the rule of {@link GuideProgressService}:
     * every task of a checklist ticked, "Đánh dấu đã xong" on any other guide) and the guide to open next: the first
     * checklist the user already started, else the first guide not done, in guide order; null when none is left.
     */
    private GuideProgress guide(String stageCode, List<StageGuide> guides, Map<Long, State> states) {
        String title = jdbc.query("SELECT title FROM guide_stages WHERE code = ?", (rs, i) -> rs.getString(1), stageCode)
                .stream().findFirst().orElse(stageCode);
        int done = (int) guides.stream().filter(g -> state(states, g).done()).count();
        GuideNext next = guides.stream().filter(g -> state(states, g).inProgress()).findFirst()
                .or(() -> guides.stream().filter(g -> !state(states, g).done()).findFirst())
                .map(g -> {
                    State s = state(states, g);
                    return new GuideNext(g.id(), g.title(), g.dueNote(), g.placeName(), g.placeId(), s.checklist(),
                            s.tasksDone(), s.tasksTotal(), s.nextTask());
                })
                .orElse(null);
        return new GuideProgress(stageCode, title, done, guides.size(), next);
    }

    /** A guide the progress lookup did not return (deleted meanwhile) counts as not started. */
    private static State state(Map<Long, State> states, StageGuide guide) {
        return states.getOrDefault(guide.id(), new State(guide.id(), false, 0, 0, false, null, null));
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
     * "Review mới": newest reviews by other people ({@link HomeReviewService#SHOWN}), those in the user's area first,
     * then its prefecture, each saying where its place is; the page "Review mới" lists them all.
     */
    private List<ReviewItem> reviews(Long userId, Area area) {
        Long areaId = area == null ? null : area.id();
        String prefecture = area == null ? null : area.prefectureCode();
        return jdbc.query("""
                SELECT r.id, p.id, p.name_vi, c.icon, pa.display_name,
                       CASE WHEN p.area_id = ? THEN 'AREA' WHEN pa.prefecture_code = ? THEN 'PREFECTURE' END AS near,
                       r.rating, u.display_name, r.content, r.created_at FROM reviews r
                JOIN places p ON p.id = r.place_id JOIN place_categories c ON c.id = p.category_id
                JOIN users u ON u.id = r.author_id LEFT JOIN areas pa ON pa.id = p.area_id
                WHERE %s AND r.author_id <> ?
                ORDER BY CASE WHEN p.area_id = ? THEN 0 WHEN pa.prefecture_code = ? THEN 1 ELSE 2 END,
                         r.created_at DESC, r.id DESC LIMIT 3"""
                        .formatted(HomeReviewService.SHOWN),
                (rs, i) -> new ReviewItem(rs.getLong(1), rs.getLong(2), rs.getString(3), rs.getString(4),
                        rs.getString(5), rs.getString(6), rs.getInt(7), rs.getString(8), excerpt(rs.getString(9), 80),
                        rs.getTimestamp(10).toInstant()),
                areaId, prefecture, userId, areaId, prefecture);
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
                SELECT ph.japanese, ph.romaji, ph.meaning_vi, c.id, c.name_vi, c.icon,
                       (SELECT MIN(l.topic_id) FROM phrase_topic_links l WHERE l.phrase_id = ph.id)
                FROM phrases ph
                JOIN place_categories c ON c.id = ph.category_id
                WHERE ph.kind = 'PHRASE' AND ph.deleted_at IS NULL ORDER BY ph.id LIMIT 1 OFFSET ?""",
                (rs, i) -> new Phrase(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4),
                        rs.getString(5), rs.getString(6), rs.getString(7)), offset).stream().findFirst().orElse(null);
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

    /** At most {@code max} code points on one line, so an emoji at the cut is never split in half. */
    static String excerpt(String text, int max) {
        if (text == null) {
            return "";
        }
        String flat = text.replaceAll("\\s+", " ").trim();
        return flat.codePointCount(0, flat.length()) <= max ? flat
                : flat.substring(0, flat.offsetByCodePoints(0, max)).trim() + "…";
    }
}
