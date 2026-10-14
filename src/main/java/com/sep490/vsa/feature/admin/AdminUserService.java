package com.sep490.vsa.feature.admin;

import com.sep490.vsa.common.exception.ApiException;
import com.sep490.vsa.common.security.AppUserPrincipal;
import com.sep490.vsa.feature.admin.AdminDtos.PageResult;
import com.sep490.vsa.feature.admin.AdminDtos.UserItem;
import com.sep490.vsa.feature.user.Role;
import com.sep490.vsa.feature.user.UserStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Dịch vụ quản lý tài khoản người dùng và phân quyền dành cho Quản trị viên (Admin).
 * Phụ trách tìm kiếm, lọc, cập nhật vai trò, khóa/mở khóa tài khoản với các ràng buộc nghiệp vụ.
 */
@Service
public class AdminUserService {

    private final JdbcTemplate jdbc;
    private final ActivityLogService logService;

    public AdminUserService(JdbcTemplate jdbc, ActivityLogService logService) {
        this.jdbc = jdbc;
        this.logService = logService;
    }

    /**
     * Tìm kiếm và phân trang danh sách người dùng theo từ khóa (tên, email), vai trò và trạng thái.
     */
    @Transactional(readOnly = true)
    public PageResult<UserItem> getUsers(String keyword, String role, String status, int page, int size) {
        int pageNumber = Math.max(1, page);
        int pageSize = Math.max(1, Math.min(100, size));
        int offset = (pageNumber - 1) * pageSize;

        StringBuilder whereClause = new StringBuilder(" WHERE 1=1 ");
        List<Object> params = new ArrayList<>();

        if (keyword != null && !keyword.isBlank()) {
            String kw = "%" + keyword.trim() + "%";
            whereClause.append(" AND (u.email LIKE ? OR u.display_name LIKE ?) ");
            params.add(kw);
            params.add(kw);
        }

        if (role != null && !role.isBlank() && !"ALL".equalsIgnoreCase(role)) {
            whereClause.append(" AND u.role = ? ");
            params.add(role.trim().toUpperCase());
        }

        if (status != null && !status.isBlank() && !"ALL".equalsIgnoreCase(status)) {
            whereClause.append(" AND u.status = ? ");
            params.add(status.trim().toUpperCase());
        }

        String countSql = "SELECT COUNT(*) FROM users u " + whereClause;
        Long totalObj = jdbc.queryForObject(countSql, Long.class, params.toArray());
        long total = totalObj != null ? totalObj : 0;

        String selectSql = """
            SELECT u.id, u.email, u.display_name, u.avatar_url, u.role, u.status,
                   u.points, u.false_report_count, u.created_at, u.last_login_at
            FROM users u
        """ + whereClause + " ORDER BY u.id DESC LIMIT ? OFFSET ? ";

        params.add(pageSize);
        params.add(offset);

        List<UserItem> items = jdbc.query(selectSql, (rs, rowNum) -> {
            String roleCode = rs.getString("role");
            String roleLabel = roleCode;
            try {
                roleLabel = Role.valueOf(roleCode).label();
            } catch (Exception ignored) {
            }
            return new UserItem(
                    rs.getLong("id"),
                    rs.getString("email"),
                    rs.getString("display_name"),
                    rs.getString("avatar_url"),
                    roleCode,
                    roleLabel,
                    rs.getString("status"),
                    rs.getInt("points"),
                    rs.getInt("false_report_count"),
                    toInstant(rs.getTimestamp("created_at")),
                    toInstant(rs.getTimestamp("last_login_at"))
            );
        }, params.toArray());

        int totalPages = (int) Math.ceil((double) total / pageSize);
        return new PageResult<>(items, pageNumber, pageSize, total, totalPages);
    }

    /**
     * Lấy thông tin chi tiết của một người dùng theo ID.
     */
    @Transactional(readOnly = true)
    public UserItem getUser(Long id) {
        String sql = """
            SELECT u.id, u.email, u.display_name, u.avatar_url, u.role, u.status,
                   u.points, u.false_report_count, u.created_at, u.last_login_at
            FROM users u
            WHERE u.id = ?
        """;
        List<UserItem> list = jdbc.query(sql, (rs, rowNum) -> {
            String roleCode = rs.getString("role");
            String roleLabel = roleCode;
            try {
                roleLabel = Role.valueOf(roleCode).label();
            } catch (Exception ignored) {
            }
            return new UserItem(
                    rs.getLong("id"),
                    rs.getString("email"),
                    rs.getString("display_name"),
                    rs.getString("avatar_url"),
                    roleCode,
                    roleLabel,
                    rs.getString("status"),
                    rs.getInt("points"),
                    rs.getInt("false_report_count"),
                    toInstant(rs.getTimestamp("created_at")),
                    toInstant(rs.getTimestamp("last_login_at"))
            );
        }, id);

        if (list.isEmpty()) {
            throw ApiException.badRequest("Không tìm thấy người dùng với ID: " + id);
        }
        return list.get(0);
    }

    /**
     * Cập nhật vai trò người dùng (USER, MODERATOR, ACADEMIC_MANAGER, ADMIN).
     * Ràng buộc:
     * - Admin không được tự đổi vai trò của chính mình.
     * - Số lượng tài khoản mang vai trò MODERATOR đang hoạt động không được vượt quá max_moderators (mặc định 3).
     */
    @Transactional
    public void updateUserRole(Long targetUserId, String newRoleStr, AppUserPrincipal me) {
        if (targetUserId == null) {
            throw ApiException.badRequest("Mã người dùng không hợp lệ.");
        }
        if (me != null && targetUserId.equals(me.id())) {
            throw ApiException.badRequest("Không thể tự thay đổi vai trò của chính tài khoản đang đăng nhập.");
        }

        Role newRole;
        try {
            newRole = Role.valueOf(newRoleStr.trim().toUpperCase());
        } catch (Exception e) {
            throw ApiException.badRequest("Vai trò không hợp lệ: " + newRoleStr);
        }

        // Lấy thông tin hiện tại của user mục tiêu
        UserItem targetUser = getUser(targetUserId);
        if (targetUser.role().equalsIgnoreCase(newRole.name())) {
            return; // Không có thay đổi
        }

        // Kiểm tra ràng buộc số lượng Moderator tối đa nếu muốn thăng chức thành MODERATOR
        if (newRole == Role.MODERATOR) {
            int maxModerators = getMaxModeratorsAllowed();
            Long currentActiveModerators = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM users WHERE role = 'MODERATOR' AND status = 'ACTIVE'", Long.class);
            long count = currentActiveModerators != null ? currentActiveModerators : 0;
            if (count >= maxModerators) {
                throw ApiException.badRequest(
                        "Hệ thống chỉ cho phép tối đa " + maxModerators + " Moderator đang hoạt động. Hiện đã đủ chỉ tiêu.");
            }
        }

        // Cập nhật vai trò
        jdbc.update("UPDATE users SET role = ?, updated_at = CURRENT_TIMESTAMP WHERE id = ?", newRole.name(), targetUserId);

        // Ghi nhận nhật ký kiểm toán
        logService.recordLog(
                me != null ? me.id() : null,
                "USER_ROLE_CHANGE",
                "USER",
                targetUserId,
                "{\"role\":\"" + targetUser.role() + "\"}",
                "{\"role\":\"" + newRole.name() + "\"}",
                "Thay đổi vai trò của người dùng " + targetUser.displayName() + " (" + targetUser.email() + ") từ "
                        + targetUser.roleLabel() + " sang " + newRole.label()
        );
    }

    /**
     * Cập nhật trạng thái người dùng (ACTIVE <-> LOCKED).
     * Ràng buộc:
     * - Admin không được tự khóa tài khoản của chính mình.
     */
    @Transactional
    public void updateUserStatus(Long targetUserId, String newStatusStr, String reason, AppUserPrincipal me) {
        if (targetUserId == null) {
            throw ApiException.badRequest("Mã người dùng không hợp lệ.");
        }
        if (me != null && targetUserId.equals(me.id()) && "LOCKED".equalsIgnoreCase(newStatusStr)) {
            throw ApiException.badRequest("Không thể tự khóa tài khoản của chính mình.");
        }

        UserStatus newStatus;
        try {
            newStatus = UserStatus.valueOf(newStatusStr.trim().toUpperCase());
        } catch (Exception e) {
            throw ApiException.badRequest("Trạng thái không hợp lệ: " + newStatusStr);
        }

        UserItem targetUser = getUser(targetUserId);
        if (targetUser.status().equalsIgnoreCase(newStatus.name())) {
            return; // Trạng thái đã là trạng thái mục tiêu
        }

        // Cập nhật trạng thái
        jdbc.update("UPDATE users SET status = ?, updated_at = CURRENT_TIMESTAMP WHERE id = ?", newStatus.name(), targetUserId);

        String note = (reason != null && !reason.isBlank()) ? " Lý do: " + reason.trim() : "";
        logService.recordLog(
                me != null ? me.id() : null,
                "USER_STATUS_CHANGE",
                "USER",
                targetUserId,
                "{\"status\":\"" + targetUser.status() + "\"}",
                "{\"status\":\"" + newStatus.name() + "\"}",
                "Chuyển trạng thái người dùng " + targetUser.displayName() + " (" + targetUser.email() + ") thành "
                        + newStatus.name() + "." + note
        );
    }

    /**
     * Đọc giá trị giới hạn số Moderator từ bảng system_settings (mặc định 3 nếu không cấu hình).
     */
    public int getMaxModeratorsAllowed() {
        try {
            String val = jdbc.queryForObject(
                    "SELECT setting_value FROM system_settings WHERE setting_key = 'max_moderators'", String.class);
            if (val != null) {
                return Integer.parseInt(val.trim());
            }
        } catch (Exception ignored) {
        }
        return 3;
    }

    private static Instant toInstant(Timestamp ts) {
        return ts != null ? ts.toInstant() : null;
    }
}
