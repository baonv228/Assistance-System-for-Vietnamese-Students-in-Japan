package com.sep490.vsa.feature.guide;

import com.sep490.vsa.common.exception.ApiException;
import com.sep490.vsa.feature.guide.GuideProgressService.State;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * "Cẩm nang của tôi" on Tôi: a Member's progress over every guide members can read (published, not deleted), by stage
 * and guide by guide. Whether a guide is done or started is the rule of {@link GuideProgressService}; this service only
 * chooses the guides and sums them up. Read only.
 */
@Service
@Transactional(readOnly = true)
public class MyGuidesService {

    static final String NO_PERMISSION = "Bạn không có quyền thực hiện thao tác này.";   // MSG18
    /** "Đang làm dở": the 10 checklists touched last. */
    static final int IN_PROGRESS_MAX = 10;
    /** "Đã xong gần đây": the 5 guides finished last. */
    static final int RECENTLY_DONE_MAX = 5;

    /**
     * The whole card. {@code stage}: the Member's stage code (null when not chosen); {@code stages}: the four guide
     * stages in their order; {@code inProgress}: checklists with some, not all, tasks ticked, touched last first;
     * {@code recentlyDone}: finished guides, finished last first.
     */
    public record MyGuides(String stage, List<StageProgress> stages, List<GuideInProgress> inProgress,
                           List<GuideDone> recentlyDone) {
    }

    /** {@code done} of the {@code total} readable guides of one stage; {@code current} = the Member's stage. */
    public record StageProgress(String code, String title, int done, int total, boolean current) {
    }

    /**
     * A checklist the Member started ({@code /guides/{id}?tab=preparation}). {@code stageCode} / {@code stageTitle}
     * are null for a guide without a stage; {@code nextTask} is the first task not ticked.
     */
    public record GuideInProgress(long id, String title, String topicName, String stageCode, String stageTitle,
                                  boolean checklist, int tasksDone, int tasksTotal, int percent, String nextTask,
                                  Instant lastActivityAt) {
    }

    /** A guide the Member finished: last task ticked, or "Đánh dấu đã xong" pressed, at {@code doneAt}. */
    public record GuideDone(long id, String title, String stageCode, String stageTitle, Instant doneAt) {
    }

    private record Guide(long id, String title, String topicName, String stageCode, String stageTitle) {
    }

    private record Stage(String code, String title) {
    }

    private final JdbcTemplate jdbc;
    private final GuideProgressService progress;

    public MyGuidesService(JdbcTemplate jdbc, GuideProgressService progress) {
        this.jdbc = jdbc;
        this.progress = progress;
    }

    /**
     * Members only (role USER, account ACTIVE), like saving: staff and locked accounts get MSG18. Guides without a
     * stage count in no stage row but may show in the two lists.
     */
    public MyGuides progress(long userId) {
        String stage = memberStage(userId);
        List<Guide> guides = jdbc.query("""
                SELECT g.id, g.title, COALESCE(t.title, s.title) AS topic_name, g.stage_code, s.title AS stage_title
                FROM guide_items g
                LEFT JOIN guide_stages s ON s.code = g.stage_code
                LEFT JOIN guide_topics t ON t.code = g.topic_code
                WHERE g.published = TRUE AND g.deleted_at IS NULL
                ORDER BY g.sort_order, g.id""",
                (rs, i) -> new Guide(rs.getLong("id"), rs.getString("title"), rs.getString("topic_name"),
                        rs.getString("stage_code"), rs.getString("stage_title")));
        Map<Long, State> states = progress.states(userId, guides.stream().map(Guide::id).toList());

        List<StageProgress> stages = jdbc.query("SELECT code, title FROM guide_stages ORDER BY sort_order",
                        (rs, i) -> new Stage(rs.getString(1), rs.getString(2))).stream()
                .map(s -> {
                    List<Guide> ofStage = guides.stream().filter(g -> s.code().equals(g.stageCode())).toList();
                    int done = (int) ofStage.stream().filter(g -> state(states, g).done()).count();
                    return new StageProgress(s.code(), s.title(), done, ofStage.size(), s.code().equals(stage));
                })
                .toList();

        // Newest activity first; equal times keep the guide order (the sort is stable)
        Comparator<State> latestFirst = Comparator.comparing(State::lastActivityAt,
                Comparator.nullsLast(Comparator.reverseOrder()));
        List<GuideInProgress> inProgress = guides.stream()
                .filter(g -> state(states, g).inProgress())
                .sorted(Comparator.comparing((Guide g) -> state(states, g), latestFirst))
                .limit(IN_PROGRESS_MAX)
                .map(g -> {
                    State s = state(states, g);
                    return new GuideInProgress(g.id(), g.title(), g.topicName(), g.stageCode(), g.stageTitle(),
                            s.checklist(), s.tasksDone(), s.tasksTotal(), s.percent(), s.nextTask(),
                            s.lastActivityAt());
                })
                .toList();
        List<GuideDone> recentlyDone = guides.stream()
                .filter(g -> state(states, g).done())
                .sorted(Comparator.comparing((Guide g) -> state(states, g), latestFirst))
                .limit(RECENTLY_DONE_MAX)
                .map(g -> new GuideDone(g.id(), g.title(), g.stageCode(), g.stageTitle(),
                        state(states, g).lastActivityAt()))
                .toList();
        return new MyGuides(stage, stages, inProgress, recentlyDone);
    }

    /** The Member's stage (null when not chosen); MSG18 for staff and accounts that are not ACTIVE. */
    private String memberStage(long userId) {
        record Account(String role, String status, String stage) {
        }
        Account account = jdbc.query("SELECT role, status, stage FROM users WHERE id = ?",
                        (rs, i) -> new Account(rs.getString(1), rs.getString(2), rs.getString(3)), userId)
                .stream().findFirst()
                .orElseThrow(() -> ApiException.unauthorized("Bạn chưa đăng nhập."));
        if (!"USER".equals(account.role()) || !"ACTIVE".equals(account.status())) {
            throw new ApiException(HttpStatus.FORBIDDEN, NO_PERMISSION);
        }
        return account.stage();
    }

    /** A guide the progress lookup did not return counts as not started. */
    private static State state(Map<Long, State> states, Guide guide) {
        return states.getOrDefault(guide.id(), new State(guide.id(), false, 0, 0, false, null, null));
    }
}
