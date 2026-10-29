package com.sep490.vsa.feature.guide;

import com.sep490.vsa.common.exception.ApiException;
import com.sep490.vsa.common.security.AppUserPrincipal;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashSet;
import java.util.List;

@Service
@Transactional(readOnly = true)
public class GuidePreparationService {
    public record Task(Long id, String title, String notes, boolean completed) {}
    public record Progress(List<Task> tasks, int completed, int total, int percent, boolean signedIn) {}
    private final JdbcTemplate jdbc;

    public GuidePreparationService(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public List<Task> tasks(long guideId) { return tasks(guideId, null); }

    private List<Task> tasks(long guideId, Long userId) {
        return jdbc.query("""
                SELECT t.id, t.title, t.notes, p.task_id IS NOT NULL AS completed
                FROM guide_preparation_tasks t
                LEFT JOIN guide_preparation_progress p ON p.task_id=t.id AND p.user_id=?
                WHERE t.guide_id=? ORDER BY t.sort_order, t.id
                """, (rs, n) -> new Task(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getBoolean(4)), userId, guideId);
    }

    private void checkVisible(long guideId, boolean lock) {
        var found = jdbc.query("""
                SELECT id FROM guide_items WHERE id=? AND published=TRUE
                AND deleted_at IS NULL AND preparation_enabled=TRUE
                """ + (lock ? " FOR UPDATE" : ""), (rs, n) -> rs.getLong(1), guideId);
        if (found.isEmpty()) throw new ApiException(HttpStatus.NOT_FOUND, "Không tìm thấy danh sách chuẩn bị.");
    }

    public Progress progress(long guideId, AppUserPrincipal me) {
        checkVisible(guideId, false);
        var tasks = tasks(guideId, me == null ? null : me.id());
        int completed = (int) tasks.stream().filter(Task::completed).count();
        return new Progress(tasks, completed, tasks.size(), tasks.isEmpty() ? 0 : completed * 100 / tasks.size(), me != null);
    }

    @Transactional
    public Progress check(long guideId, long taskId, boolean completed, AppUserPrincipal me) {
        if (me == null) throw new ApiException(HttpStatus.UNAUTHORIZED, "Vui lòng đăng nhập để lưu tiến độ.");
        // Serialize with moderator changes so removed tasks cannot be checked using a stale page.
        checkVisible(guideId, true);
        Integer found = jdbc.queryForObject("SELECT COUNT(*) FROM guide_preparation_tasks WHERE id=? AND guide_id=?",
                Integer.class, taskId, guideId);
        if (found == null || found == 0) throw new ApiException(HttpStatus.NOT_FOUND, "Công việc không thuộc cẩm nang này hoặc đã bị xóa.");
        if (completed) {
            jdbc.update("""
                    INSERT INTO guide_preparation_progress(user_id, task_id) VALUES (?, ?)
                    ON DUPLICATE KEY UPDATE completed_at=completed_at
                    """, me.id(), taskId);
        } else {
            jdbc.update("DELETE FROM guide_preparation_progress WHERE user_id=? AND task_id=?", me.id(), taskId);
        }
        return progress(guideId, me);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void saveTasks(long guideId, GuideRequest request) {
        var requested = request.preparationEnabled() && request.preparationTasks() != null ? request.preparationTasks() : List.<GuideRequest.PreparationTask>of();
        if (request.preparationEnabled() && request.publishing() && requested.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Danh sách chuẩn bị cần ít nhất một công việc trước khi đăng công khai.");
        }
        var existing = new HashSet<>(jdbc.query("SELECT id FROM guide_preparation_tasks WHERE guide_id=?", (rs, n) -> rs.getLong(1), guideId));
        var kept = new HashSet<Long>();
        for (var task : requested) {
            if (task.id() != null && (!existing.contains(task.id()) || !kept.add(task.id()))) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "Công việc được cập nhật không thuộc cẩm nang hoặc bị trùng lặp.");
            }
        }
        for (long removed : existing) if (!kept.contains(removed)) {
            jdbc.update("DELETE FROM guide_preparation_tasks WHERE id=? AND guide_id=?", removed, guideId);
        }
        for (int i = 0; i < requested.size(); i++) {
            var task = requested.get(i);
            if (task.id() == null) {
                jdbc.update("INSERT INTO guide_preparation_tasks(guide_id,title,notes,sort_order) VALUES (?,?,?,?)",
                        guideId, task.title().trim(), task.notes(), i);
            } else {
                jdbc.update("UPDATE guide_preparation_tasks SET title=?,notes=?,sort_order=? WHERE id=? AND guide_id=?",
                        task.title().trim(), task.notes(), i, task.id(), guideId);
            }
        }
        jdbc.update("UPDATE guide_items SET preparation_enabled=? WHERE id=?", request.preparationEnabled(), guideId);
    }
}
