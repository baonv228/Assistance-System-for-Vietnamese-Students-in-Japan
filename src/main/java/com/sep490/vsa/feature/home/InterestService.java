package com.sep490.vsa.feature.home;

import com.sep490.vsa.common.exception.ApiException;
import com.sep490.vsa.feature.home.HomeDtos.InterestSettings;
import com.sep490.vsa.feature.home.HomeDtos.InterestTopic;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * "Chủ đề quan tâm" of a Member, as "Dành cho bạn" ({@link HomeService}) reads them: the blog topics the Member chose
 * ({@code user_interest_topics}, V21: on Tôi and in "Điều chỉnh" on the Home) and the topics learnt from what the
 * Member did on posts and questions. Only active topics other than "Khác" are offered, chosen or learnt; the Blog's
 * topic table belongs to the blog feature and is only read here.
 */
@Service
public class InterestService {

    /** At most 5 chosen topics ("cần chốt"). */
    static final int CHOSEN_MAX = 5;
    /**
     * Learnt topics ("cần chốt"): topics of what the user did in the last 90 days, weighted like 1, save 2, comment 2
     * (once per post), own post or question 3; the 3 heaviest with at least 2 points, so one like is not enough. A
     * topic the user chose is never learnt as well, so the learnt ones always add something.
     */
    static final int LEARNT_DAYS = 90;
    static final int LEARNT_MIN_WEIGHT = 2;
    static final int LEARNT_TOPICS = 3;
    static final String NO_PERMISSION = "Bạn không có quyền thực hiện thao tác này.";   // MSG18
    static final String INVALID_LIST = "Danh sách chủ đề không hợp lệ.";
    static final String TOO_MANY = "Bạn chọn được tối đa " + CHOSEN_MAX + " chủ đề.";
    static final String NOT_OFFERED = "Có chủ đề không còn để chọn. Hãy tải lại trang rồi chọn lại.";

    /** One of the user's interests: {@code chosen} by the user, else learnt from what they did. */
    public record Interest(long id, String name, boolean chosen) {
    }

    private final JdbcTemplate jdbc;
    private final NamedParameterJdbcTemplate named;
    private final Clock clock;

    public InterestService(JdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.named = new NamedParameterJdbcTemplate(jdbc);
        this.clock = clock;
    }

    /** What "Dành cho bạn" reads: the chosen topics in the Blog's topic order, then the learnt ones, heaviest first. */
    @Transactional(readOnly = true)
    public List<Interest> interests(long userId) {
        List<Interest> chosen = chosen(userId);
        List<Interest> all = new ArrayList<>(chosen);
        all.addAll(learnt(userId, chosen));
        return List.copyOf(all);
    }

    /**
     * {@code GET /api/me/interests}: every topic a Member can choose, in the Blog's order, each saying whether it is
     * chosen or learnt (a suggestion), and how many can be chosen. Members only (role USER, account ACTIVE): MSG18 for
     * staff, who have no "Dành cho bạn".
     */
    @Transactional(readOnly = true)
    public InterestSettings settings(long userId) {
        assertMember(userId, false);
        return read(userId);
    }

    /**
     * {@code PUT /api/me/interests}: the Member's whole new choice (empty = none), at most {@value #CHOSEN_MAX}
     * different topics, each active and not "Khác"; repeats count once. Topics kept keep the date they were first
     * chosen. The users row is locked first, so two saves of one Member run one after the other and the last one wins.
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public InterestSettings update(long userId, List<Long> topicIds) {
        assertMember(userId, true);
        if (topicIds == null || topicIds.stream().anyMatch(Objects::isNull)) {
            throw ApiException.badRequest(INVALID_LIST);
        }
        Set<Long> wanted = new LinkedHashSet<>(topicIds);
        if (wanted.size() > CHOSEN_MAX) {
            throw ApiException.badRequest(TOO_MANY);
        }
        MapSqlParameterSource params = new MapSqlParameterSource("userId", userId).addValue("ids", wanted);
        if (!wanted.isEmpty()) {
            Integer offered = named.queryForObject("""
                    SELECT COUNT(*) FROM blog_topics WHERE id IN (:ids) AND is_active AND NOT is_other""",
                    params, Integer.class);
            if (offered == null || offered != wanted.size()) {
                throw ApiException.badRequest(NOT_OFFERED);
            }
        }
        named.update("DELETE FROM user_interest_topics WHERE user_id = :userId"
                + (wanted.isEmpty() ? "" : " AND topic_id NOT IN (:ids)"), params);
        jdbc.batchUpdate("""
                INSERT INTO user_interest_topics (user_id, topic_id) VALUES (?, ?)
                ON DUPLICATE KEY UPDATE user_id = user_id""",
                wanted.stream().map(id -> new Object[] {userId, id}).toList());
        return read(userId);
    }

    /** The ids of the topics the user chose that are still offered, in the Blog's topic order. */
    @Transactional(readOnly = true)
    public List<Long> chosenTopicIds(long userId) {
        return chosen(userId).stream().map(Interest::id).toList();
    }

    private InterestSettings read(long userId) {
        List<Interest> chosen = chosen(userId);
        Set<Long> chosenIds = ids(chosen);
        Set<Long> learntIds = ids(learnt(userId, chosen));
        List<InterestTopic> topics = jdbc.query("""
                SELECT id, name FROM blog_topics WHERE is_active AND NOT is_other ORDER BY sort_order, name""",
                (rs, i) -> new InterestTopic(rs.getLong(1), rs.getString(2), chosenIds.contains(rs.getLong(1)),
                        learntIds.contains(rs.getLong(1))));
        return new InterestSettings(CHOSEN_MAX, topics);
    }

    private static Set<Long> ids(List<Interest> interests) {
        return interests.stream().map(Interest::id).collect(Collectors.toSet());
    }

    /** The topics the user chose that are still offered, in the Blog's topic order. */
    private List<Interest> chosen(long userId) {
        return jdbc.query("""
                SELECT t.id, t.name FROM user_interest_topics ui JOIN blog_topics t ON t.id = ui.topic_id
                WHERE ui.user_id = ? AND t.is_active AND NOT t.is_other
                ORDER BY t.sort_order, t.name LIMIT ?""",
                (rs, i) -> new Interest(rs.getLong(1), rs.getString(2), true), userId, CHOSEN_MAX);
    }

    /**
     * Topics learnt from what the user did on posts and questions in the last {@value #LEARNT_DAYS} days (likes,
     * saves, published comments, own published posts and questions), heaviest first, leaving out the chosen ones.
     */
    private List<Interest> learnt(long userId, List<Interest> chosen) {
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("userId", userId)
                .addValue("since", Timestamp.from(clock.instant().minus(Duration.ofDays(LEARNT_DAYS))))
                .addValue("minWeight", LEARNT_MIN_WEIGHT)
                .addValue("limit", LEARNT_TOPICS);
        String notChosen = "";
        if (!chosen.isEmpty()) {
            params.addValue("chosenIds", ids(chosen));
            notChosen = " AND t.id NOT IN (:chosenIds)";
        }
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
                JOIN blog_topics t ON t.id = btl.topic_id AND t.is_active AND NOT t.is_other%s
                GROUP BY t.id, t.name, t.sort_order
                HAVING SUM(acts.weight) >= :minWeight
                ORDER BY SUM(acts.weight) DESC, t.sort_order, t.name
                LIMIT :limit""".formatted(notChosen), params, (rs, i) -> new Interest(rs.getLong(1), rs.getString(2), false));
    }

    /**
     * MSG18 unless the account is a Member (role USER) and ACTIVE; {@code lock} reads the users row FOR UPDATE, so a
     * save waits for another save of the same Member and for an Admin's lock of the account.
     */
    private void assertMember(long userId, boolean lock) {
        record Account(String role, String status) {
        }
        Account account = jdbc.query("SELECT role, status FROM users WHERE id = ?" + (lock ? " FOR UPDATE" : ""),
                        (rs, i) -> new Account(rs.getString(1), rs.getString(2)), userId)
                .stream().findFirst()
                .orElseThrow(() -> ApiException.unauthorized("Bạn chưa đăng nhập."));
        if (!"USER".equals(account.role()) || !"ACTIVE".equals(account.status())) {
            throw ApiException.forbidden(NO_PERMISSION);
        }
    }
}
