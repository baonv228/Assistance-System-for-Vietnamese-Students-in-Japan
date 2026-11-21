package com.sep490.vsa.feature.quest;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

/**
 * Plain SQL of what a Member did on the Blog and in the Cẩm nang, summed by topic, for the quests that follow the
 * Member's interests ({@link QuestInterests}). The tables belong to the blog, home and guide features and are only read
 * here. Weights: see {@link QuestInterests}.
 */
@Repository
class QuestInterestRepository {

    /**
     * A topic and the weight of what the Member did with it. {@code key}: the blog topic's name, or the guide topic's
     * code; {@code name}: what the Member reads (the blog topic's name, the guide topic's title); {@code chosen}: the
     * Member chose this blog topic in "Chủ đề quan tâm".
     */
    record TopicWeight(String key, String name, int weight, boolean chosen) {
    }

    private final NamedParameterJdbcTemplate named;

    QuestInterestRepository(JdbcTemplate jdbc) {
        this.named = new NamedParameterJdbcTemplate(jdbc);
    }

    /**
     * Blog topics (active, not "Khác") the Member chose (never expires) or met since {@code since}: likes, saves,
     * published comments (once per post) and own published posts and questions, heaviest first.
     */
    List<TopicWeight> blogTopics(long userId, Instant since, int chosenWeight) {
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("userId", userId)
                .addValue("since", Timestamp.from(since))
                .addValue("chosen", chosenWeight)
                .addValue("like", QuestInterests.LIKE)
                .addValue("save", QuestInterests.SAVE)
                .addValue("comment", QuestInterests.COMMENT)
                .addValue("own", QuestInterests.OWN);
        return named.query("""
                SELECT t.name, SUM(acts.weight), MAX(acts.chosen)
                FROM (
                    SELECT ui.topic_id, :chosen AS weight, 1 AS chosen
                    FROM user_interest_topics ui WHERE ui.user_id = :userId
                    UNION ALL
                    SELECT btl.topic_id, a.weight, 0
                    FROM (
                        SELECT l.blog_id, :like AS weight FROM blog_likes l
                        WHERE l.user_id = :userId AND l.created_at >= :since
                        UNION ALL
                        SELECT s.blog_id, :save FROM saved_blogs s
                        WHERE s.user_id = :userId AND s.created_at >= :since
                        UNION ALL
                        SELECT DISTINCT c.blog_id, :comment FROM comments c
                        WHERE c.author_id = :userId AND c.status = 'PUBLISHED' AND c.created_at >= :since
                        UNION ALL
                        SELECT b.id, :own FROM blogs b
                        WHERE b.author_id = :userId AND b.status = 'PUBLISHED' AND b.created_at >= :since
                    ) a
                    JOIN blog_topic_links btl ON btl.blog_id = a.blog_id
                ) acts
                JOIN blog_topics t ON t.id = acts.topic_id AND t.is_active AND NOT t.is_other
                GROUP BY t.id, t.name, t.sort_order
                ORDER BY SUM(acts.weight) DESC, t.sort_order, t.name""", params,
                (rs, i) -> new TopicWeight(rs.getString(1), rs.getString(1), rs.getInt(2), rs.getInt(3) == 1));
    }

    /**
     * Guide topics of the published, not deleted guides the Member saved, worked on (a checklist task ticked, once per
     * guide) or marked done since {@code since}, heaviest first. Guides without a topic are left out.
     */
    List<TopicWeight> guideTopics(long userId, Instant since) {
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("userId", userId)
                .addValue("since", Timestamp.from(since))
                .addValue("save", QuestInterests.GUIDE_SAVE)
                .addValue("task", QuestInterests.GUIDE_TASK)
                .addValue("done", QuestInterests.GUIDE_DONE);
        return named.query("""
                SELECT t.code, t.title, SUM(acts.weight)
                FROM (
                    SELECT s.guide_id, :save AS weight FROM saved_guides s
                    WHERE s.user_id = :userId AND s.created_at >= :since
                    UNION ALL
                    SELECT DISTINCT pt.guide_id, :task FROM guide_preparation_progress pp
                    JOIN guide_preparation_tasks pt ON pt.id = pp.task_id
                    WHERE pp.user_id = :userId AND pp.completed_at >= :since
                    UNION ALL
                    SELECT p.item_id, :done FROM user_guide_progress p
                    WHERE p.user_id = :userId AND p.done_at >= :since
                ) acts
                JOIN guide_items g ON g.id = acts.guide_id AND g.deleted_at IS NULL AND g.published
                JOIN guide_topics t ON t.code = g.topic_code
                GROUP BY t.code, t.title, t.sort_order
                ORDER BY SUM(acts.weight) DESC, t.sort_order, t.code""", params,
                (rs, i) -> new TopicWeight(rs.getString(1), rs.getString(2), rs.getInt(3), false));
    }
}
