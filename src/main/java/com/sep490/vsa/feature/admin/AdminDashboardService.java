package com.sep490.vsa.feature.admin;

import com.sep490.vsa.feature.admin.AdminDtos.ActivityLogItem;
import com.sep490.vsa.feature.admin.AdminDtos.DashboardStats;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Dịch vụ tổng hợp số liệu thống kê vận hành hệ thống cho trang chủ Quản trị viên (Dashboard).
 */
@Service
public class AdminDashboardService {

    private final JdbcTemplate jdbc;
    private final ActivityLogService logService;
    private final AdminUserService userService;

    public AdminDashboardService(JdbcTemplate jdbc, ActivityLogService logService, AdminUserService userService) {
        this.jdbc = jdbc;
        this.logService = logService;
        this.userService = userService;
    }

    /**
     * Lấy các chỉ số thống kê tổng hợp và danh sách nhật ký gần nhất.
     * Tài khoản đã xóa (DELETED) không được tính ở đâu cả. Số Moderator chỉ tính tài khoản đang hoạt động, như giới
     * hạn max_moderators.
     */
    @Transactional(readOnly = true)
    public DashboardStats getDashboardStats() {
        Long totalUsersObj = jdbc.queryForObject("SELECT COUNT(*) FROM users WHERE status <> 'DELETED'", Long.class);
        long totalUsers = totalUsersObj != null ? totalUsersObj : 0;

        Long activeUsersObj = jdbc.queryForObject("SELECT COUNT(*) FROM users WHERE status = 'ACTIVE'", Long.class);
        long activeUsers = activeUsersObj != null ? activeUsersObj : 0;

        Long lockedUsersObj = jdbc.queryForObject("SELECT COUNT(*) FROM users WHERE status = 'LOCKED'", Long.class);
        long lockedUsers = lockedUsersObj != null ? lockedUsersObj : 0;

        Long userRoleCountObj = jdbc.queryForObject(
                "SELECT COUNT(*) FROM users WHERE role = 'USER' AND status <> 'DELETED'", Long.class);
        long userRoleCount = userRoleCountObj != null ? userRoleCountObj : 0;

        Long moderatorCountObj = jdbc.queryForObject(
                "SELECT COUNT(*) FROM users WHERE role = 'MODERATOR' AND status = 'ACTIVE'", Long.class);
        long moderatorCount = moderatorCountObj != null ? moderatorCountObj : 0;

        Long academicCountObj = jdbc.queryForObject(
                "SELECT COUNT(*) FROM users WHERE role = 'ACADEMIC_MANAGER' AND status <> 'DELETED'", Long.class);
        long academicManagerCount = academicCountObj != null ? academicCountObj : 0;

        Long adminCountObj = jdbc.queryForObject(
                "SELECT COUNT(*) FROM users WHERE role = 'ADMIN' AND status <> 'DELETED'", Long.class);
        long adminCount = adminCountObj != null ? adminCountObj : 0;

        Long falseReportCountObj = jdbc.queryForObject(
                "SELECT COUNT(*) FROM users WHERE false_report_count > 0 AND status <> 'DELETED'", Long.class);
        long falseReportUsersCount = falseReportCountObj != null ? falseReportCountObj : 0;

        int maxModerators = userService.getMaxModeratorsAllowed();
        List<ActivityLogItem> recentLogs = logService.getRecentLogs(10);

        return new DashboardStats(
                totalUsers,
                activeUsers,
                lockedUsers,
                userRoleCount,
                moderatorCount,
                academicManagerCount,
                adminCount,
                falseReportUsersCount,
                maxModerators,
                recentLogs
        );
    }
}
