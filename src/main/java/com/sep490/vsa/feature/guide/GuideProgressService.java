package com.sep490.vsa.feature.guide;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A Member's progress through guides ("Cẩm nang"): the one rule read by the guide page, the member Home ("Việc tiếp
 * theo", "Dành cho bạn") and "Tôi".
 * <ul>
 *   <li>A guide with a checklist ("Hướng dẫn chuẩn bị": {@code preparation_enabled} and at least one task) is done
 *   when every task is ticked ({@code guide_preparation_progress}). A row in {@code user_guide_progress} does not
 *   count for it.</li>
 *   <li>Any other guide is done when the Member pressed "Đánh dấu đã xong" ({@code user_guide_progress}).</li>
 * </ul>
 * Read only: ticks are written by {@link GuidePreparationService}, the "đã xong" mark by the guide page's API.
 */
@Service
@Transactional(readOnly = true)
public class GuideProgressService {

    /**
     * Where one Member stands on one guide.
     *
     * @param checklist      the guide has a checklist with at least one task
     * @param tasksDone      ticked tasks (0 without a checklist)
     * @param tasksTotal     tasks of the checklist (0 without one)
     * @param done           see the class comment
     * @param nextTask       title of the first task not ticked yet, in the checklist's order; null when there is none
     * @param lastActivityAt latest tick or "đã xong" mark that counts for this guide; null when there is none
     */
    public record State(long guideId, boolean checklist, int tasksDone, int tasksTotal, boolean done, String nextTask,
                        Instant lastActivityAt) {

        /** 0–100: ticked share of the checklist; without a checklist 100 when done, else 0. */
        public int percent() {
            if (!checklist) {
                return done ? 100 : 0;
            }
            return tasksTotal == 0 ? 0 : tasksDone * 100 / tasksTotal;
        }

        /** A checklist with some, not all, tasks ticked ("đang làm dở"). */
        public boolean inProgress() {
            return checklist && !done && tasksDone > 0;
        }
    }

    private final NamedParameterJdbcTemplate named;

    public GuideProgressService(NamedParameterJdbcTemplate named) {
        this.named = named;
    }

    /**
     * The state of each given guide for {@code userId}, in no particular order. Ids that are not guides are left
     * out; whether a guide is published or deleted is not checked here (callers choose the guides they show).
     */
    public Map<Long, State> states(long userId, Collection<Long> guideIds) {
        Map<Long, State> states = new LinkedHashMap<>();
        if (guideIds == null || guideIds.isEmpty()) {
            return states;
        }
        var params = new MapSqlParameterSource().addValue("user", userId).addValue("ids", guideIds);
        named.query("""
                SELECT g.id,
                       (g.preparation_enabled AND COUNT(t.id) > 0) AS checklist,
                       COUNT(p.task_id) AS tasks_done,
                       COUNT(t.id) AS tasks_total,
                       MAX(p.completed_at) AS last_tick,
                       u.done_at AS marked_at,
                       (SELECT nt.title FROM guide_preparation_tasks nt
                        LEFT JOIN guide_preparation_progress np ON np.task_id = nt.id AND np.user_id = :user
                        WHERE nt.guide_id = g.id AND g.preparation_enabled AND np.task_id IS NULL
                        ORDER BY nt.sort_order, nt.id LIMIT 1) AS next_task
                FROM guide_items g
                LEFT JOIN guide_preparation_tasks t ON t.guide_id = g.id AND g.preparation_enabled
                LEFT JOIN guide_preparation_progress p ON p.task_id = t.id AND p.user_id = :user
                LEFT JOIN user_guide_progress u ON u.item_id = g.id AND u.user_id = :user
                WHERE g.id IN (:ids)
                GROUP BY g.id, g.preparation_enabled, u.done_at""", params, rs -> {
            long id = rs.getLong("id");
            boolean checklist = rs.getBoolean("checklist");
            int tasksDone = rs.getInt("tasks_done");
            int tasksTotal = rs.getInt("tasks_total");
            Instant lastTick = instant(rs.getTimestamp("last_tick"));
            Instant markedAt = instant(rs.getTimestamp("marked_at"));
            boolean done = checklist ? tasksDone == tasksTotal : markedAt != null;
            states.put(id, new State(id, checklist, checklist ? tasksDone : 0, checklist ? tasksTotal : 0, done,
                    checklist ? rs.getString("next_task") : null, checklist ? lastTick : markedAt));
        });
        return states;
    }

    /** {@link #states} for one guide; a guide id that does not exist gives a state with nothing done. */
    public State state(long userId, long guideId) {
        return states(userId, java.util.List.of(guideId)).getOrDefault(guideId,
                new State(guideId, false, 0, 0, false, null, null));
    }

    private static Instant instant(Timestamp ts) {
        return ts == null ? null : ts.toInstant();
    }
}
