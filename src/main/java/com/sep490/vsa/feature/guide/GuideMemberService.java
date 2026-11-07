package com.sep490.vsa.feature.guide;

import com.sep490.vsa.common.exception.ApiException;
import com.sep490.vsa.common.security.AppUserPrincipal;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/**
 * What a Member does on a guide page ("Cẩm nang"): "Lưu" (the guide is then listed in "Đã lưu", {@code saved_guides})
 * and "Đánh dấu đã xong" for a guide without a checklist ({@code user_guide_progress}). Whether a guide is done is the
 * rule of {@link GuideProgressService}: a guide with a checklist is done when every task is ticked, so it cannot be
 * marked by hand.
 * <p>
 * Only published guides that are not deleted can be read or acted on; any other id answers 404. Actions are for
 * Members (role USER, account ACTIVE): guests get 401, staff and locked accounts MSG18. Each action locks the guide
 * row first, like ticking a task ({@link GuidePreparationService#check}), so it runs after a Moderator's edit or delete
 * of the same guide, never beside it; the writes are idempotent, so a double click or two tabs store one row.
 */
@Service
@Transactional(isolation = Isolation.READ_COMMITTED)
public class GuideMemberService {

    static final String NOT_FOUND = "Không tìm thấy cẩm nang.";
    static final String NO_PERMISSION = "Bạn không có quyền thực hiện thao tác này.";          // MSG18
    static final String HAS_CHECKLIST =
            "Cẩm nang này có danh sách chuẩn bị. Hãy đánh dấu từng việc trong tab “Hướng dẫn chuẩn bị”.";

    /** Guests have no progress: their state is read for this id, which no account has (ids start at 1). */
    private static final long NO_USER = 0L;

    /**
     * Where the viewer stands on the guide, from {@link GuideProgressService.State}.
     *
     * @param percent  0–100, see {@link GuideProgressService.State#percent()}
     * @param nextTask title of the first task not ticked yet; null without a checklist or when all are ticked
     */
    public record Progress(boolean checklist, int tasksDone, int tasksTotal, int percent, boolean done,
                           String nextTask) {

        static Progress of(GuideProgressService.State state) {
            return new Progress(state.checklist(), state.tasksDone(), state.tasksTotal(), state.percent(),
                    state.done(), state.nextTask());
        }
    }

    /**
     * The guide page's view of the signed-in person.
     *
     * @param member   a Member who may save the guide and mark it done; false for guests and staff
     * @param saved    the Member saved the guide (always false for others)
     * @param progress the viewer's progress; for a guest nothing is done (the checklist and its size are real)
     */
    public record Viewer(boolean signedIn, boolean member, boolean saved, Progress progress) {
    }

    public record SaveState(boolean saved) {
    }

    private record Account(String role, String status) {

        boolean member() {
            return "USER".equals(role) && "ACTIVE".equals(status);
        }
    }

    private final JdbcTemplate jdbc;
    private final GuideProgressService progress;

    public GuideMemberService(JdbcTemplate jdbc, GuideProgressService progress) {
        this.jdbc = jdbc;
        this.progress = progress;
    }

    /**
     * Saved flag and progress for the guide page. Staff see their own ticks (the checklist lets any signed-in account
     * tick) but are not Members, so they cannot save or mark the guide.
     */
    @Transactional(readOnly = true)
    public Viewer viewer(long guideId, AppUserPrincipal me) {
        visible(guideId, false);
        if (me == null) {
            return new Viewer(false, false, false, Progress.of(progress.state(NO_USER, guideId)));
        }
        boolean member = account(me.id()).map(Account::member).orElse(false);
        boolean saved = member && Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM saved_guides WHERE user_id = ? AND guide_id = ?)",
                Boolean.class, me.id(), guideId));
        return new Viewer(true, member, saved, Progress.of(progress.state(me.id(), guideId)));
    }

    /** "Lưu" / "Bỏ lưu". Saving twice keeps one row and its first time; unsaving an unsaved guide is fine. */
    public SaveState save(long guideId, AppUserPrincipal me, boolean on) {
        long userId = member(me);
        visible(guideId, true);
        if (on) {
            jdbc.update("""
                    INSERT INTO saved_guides (user_id, guide_id) VALUES (?, ?)
                    ON DUPLICATE KEY UPDATE created_at = created_at""", userId, guideId);
        } else {
            jdbc.update("DELETE FROM saved_guides WHERE user_id = ? AND guide_id = ?", userId, guideId);
        }
        return new SaveState(on);
    }

    /**
     * "Đánh dấu đã xong" / undo it, for a guide without a checklist only: a guide with one is done when every task is
     * ticked, so the request is refused (400) and nothing is written. Read after the guide row is locked, so a
     * checklist a Moderator just added is seen.
     */
    public Progress markDone(long guideId, AppUserPrincipal me, boolean on) {
        long userId = member(me);
        visible(guideId, true);
        if (progress.state(userId, guideId).checklist()) {
            throw ApiException.badRequest(HAS_CHECKLIST);
        }
        if (on) {
            jdbc.update("""
                    INSERT INTO user_guide_progress (user_id, item_id) VALUES (?, ?)
                    ON DUPLICATE KEY UPDATE done_at = done_at""", userId, guideId);
        } else {
            jdbc.update("DELETE FROM user_guide_progress WHERE user_id = ? AND item_id = ?", userId, guideId);
        }
        return Progress.of(progress.state(userId, guideId));
    }

    // ---------------------------------------------------------------- checks

    /** The guide is published and not deleted, else 404; {@code lock} takes the guide row FOR UPDATE. */
    private void visible(long guideId, boolean lock) {
        var found = jdbc.query("SELECT id FROM guide_items WHERE id = ? AND published = TRUE AND deleted_at IS NULL"
                + (lock ? " FOR UPDATE" : ""), (rs, n) -> rs.getLong(1), guideId);
        if (found.isEmpty()) {
            throw new ApiException(HttpStatus.NOT_FOUND, NOT_FOUND);
        }
    }

    /** The signed-in Member's id. Guests get 401; staff roles and locked accounts get MSG18. */
    private long member(AppUserPrincipal me) {
        if (me == null) {
            throw ApiException.unauthorized("Bạn cần đăng nhập để thực hiện thao tác này.");
        }
        Account account = account(me.id()).orElseThrow(() -> ApiException.unauthorized("Bạn chưa đăng nhập."));
        if (!account.member()) {
            throw new ApiException(HttpStatus.FORBIDDEN, NO_PERMISSION);
        }
        return me.id();
    }

    private Optional<Account> account(long userId) {
        return jdbc.query("SELECT role, status FROM users WHERE id = ?",
                (rs, n) -> new Account(rs.getString(1), rs.getString(2)), userId).stream().findFirst();
    }
}
