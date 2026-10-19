package com.sep490.vsa.feature.admin;

import com.sep490.vsa.feature.admin.AdminDtos.ActivityLogItem;
import com.sep490.vsa.feature.admin.AdminDtos.PageResult;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Dịch vụ ghi nhận và tra cứu nhật ký kiểm toán hệ thống.
 * Mọi thao tác quan trọng của quản trị viên (đổi vai trò, khóa tài khoản, sửa cấu hình, đổi từ khóa cấm)
 * đều được ghi lại để phục vụ việc kiểm tra và bảo mật.
 */
@Service
public class ActivityLogService {

    private final ActivityLogRepository logRepository;
    private final JdbcTemplate jdbc;

    public ActivityLogService(ActivityLogRepository logRepository, JdbcTemplate jdbc) {
        this.logRepository = logRepository;
        this.jdbc = jdbc;
    }

    /**
     * Ghi nhận một thao tác vào nhật ký kiểm toán.
     *
     * @param actorId     ID của người dùng thực hiện
     * @param action      Mã hành động
     * @param targetType  Loại đối tượng chịu tác động (USER, SETTING, BANNED_KEYWORD)
     * @param targetId    ID đối tượng chịu tác động (nếu có)
     * @param oldValue    Giá trị trước khi thay đổi (chuỗi JSON hoặc giá trị chuỗi)
     * @param newValue    Giá trị sau khi thay đổi (chuỗi JSON hoặc giá trị chuỗi)
     * @param description Mô tả tóm tắt hành động
     */
    @Transactional
    public void recordLog(Long actorId, String action, String targetType, Long targetId,
                          String oldValue, String newValue, String description) {
        ActivityLog log = new ActivityLog(actorId, action, targetType, targetId, oldValue, newValue, description);
        logRepository.save(log);
    }

    /**
     * Lấy danh sách 10 bản ghi nhật ký hoạt động mới nhất kèm thông tin người thực hiện.
     */
    @Transactional(readOnly = true)
    public List<ActivityLogItem> getRecentLogs(int limit) {
        String sql = """
            SELECT l.id, l.actor_id, u.display_name, u.email, l.action, l.target_type,
                   l.target_id, l.old_value, l.new_value, l.description, l.created_at
            FROM activity_logs l
            LEFT JOIN users u ON u.id = l.actor_id
            ORDER BY l.created_at DESC
            LIMIT ?
        """;
        return jdbc.query(sql, (rs, rowNum) -> new ActivityLogItem(
                rs.getLong("id"),
                rs.getObject("actor_id") != null ? rs.getLong("actor_id") : null,
                rs.getString("display_name") != null ? rs.getString("display_name") : "Hệ thống",
                rs.getString("email") != null ? rs.getString("email") : "-",
                rs.getString("action"),
                rs.getString("target_type"),
                rs.getObject("target_id") != null ? rs.getLong("target_id") : null,
                rs.getString("old_value"),
                rs.getString("new_value"),
                rs.getString("description"),
                toInstant(rs.getTimestamp("created_at"))
        ), limit);
    }

    /**
     * Truy vấn nhật ký hoạt động có phân trang và lọc theo loại hành vi.
     */
    @Transactional(readOnly = true)
    public PageResult<ActivityLogItem> getLogs(int page, int size, String action) {
        int pageNumber = Math.max(1, page);
        int pageSize = Math.max(1, Math.min(100, size));
        int offset = (pageNumber - 1) * pageSize;

        StringBuilder whereClause = new StringBuilder(" WHERE 1=1 ");
        List<Object> params = new ArrayList<>();

        if (action != null && !action.isBlank()) {
            whereClause.append(" AND l.action = ? ");
            params.add(action.trim());
        }

        String countSql = "SELECT COUNT(*) FROM activity_logs l " + whereClause;
        Long totalObj = jdbc.queryForObject(countSql, Long.class, params.toArray());
        long total = totalObj != null ? totalObj : 0;

        String selectSql = """
            SELECT l.id, l.actor_id, u.display_name, u.email, l.action, l.target_type,
                   l.target_id, l.old_value, l.new_value, l.description, l.created_at
            FROM activity_logs l
            LEFT JOIN users u ON u.id = l.actor_id
        """ + whereClause + " ORDER BY l.created_at DESC LIMIT ? OFFSET ? ";

        params.add(pageSize);
        params.add(offset);

        List<ActivityLogItem> items = jdbc.query(selectSql, (rs, rowNum) -> new ActivityLogItem(
                rs.getLong("id"),
                rs.getObject("actor_id") != null ? rs.getLong("actor_id") : null,
                rs.getString("display_name") != null ? rs.getString("display_name") : "Hệ thống",
                rs.getString("email") != null ? rs.getString("email") : "-",
                rs.getString("action"),
                rs.getString("target_type"),
                rs.getObject("target_id") != null ? rs.getLong("target_id") : null,
                rs.getString("old_value"),
                rs.getString("new_value"),
                rs.getString("description"),
                toInstant(rs.getTimestamp("created_at"))
        ), params.toArray());

        int totalPages = (int) Math.ceil((double) total / pageSize);
        return new PageResult<>(items, pageNumber, pageSize, total, totalPages);
    }

    private static Instant toInstant(Timestamp ts) {
        return ts != null ? ts.toInstant() : null;
    }
}
