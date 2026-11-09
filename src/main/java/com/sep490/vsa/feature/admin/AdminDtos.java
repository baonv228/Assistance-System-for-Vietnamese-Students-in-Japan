package com.sep490.vsa.feature.admin;

import java.time.Instant;
import java.util.List;

/**
 * Tập hợp các DTO (Data Transfer Objects) trao đổi dữ liệu cho chức năng Quản trị hệ thống (Admin).
 */
public final class AdminDtos {

    private AdminDtos() {
    }

    /**
     * Dữ liệu thống kê tổng quan hệ thống hiển thị tại Dashboard.
     * Không tính tài khoản đã xóa; moderatorCount chỉ tính Moderator đang hoạt động (so với maxModerators).
     */
    public record DashboardStats(
            long totalUsers,
            long activeUsers,
            long lockedUsers,
            long userRoleCount,
            long moderatorCount,
            long academicManagerCount,
            long adminCount,
            long falseReportUsersCount,
            int maxModerators,
            List<ActivityLogItem> recentLogs
    ) {}

    /** Một tag định danh đang được cấp cho người dùng (chỉ đọc với Admin). */
    public record TagChip(Long id, String name, String color) {}

    /**
     * Thông tin tóm tắt người dùng trong danh sách quản lý người dùng.
     * {@code accountAgeDays} là số ngày trọn vẹn kể từ lúc đăng ký, {@code newAccount} là true khi tuổi đó nhỏ hơn
     * {@code new_account_days}; {@code reportMutedUntil} chỉ có giá trị khi người dùng đang bị khóa quyền báo cáo.
     */
    public record UserItem(
            Long id,
            String email,
            String displayName,
            String avatarUrl,
            String role,
            String roleLabel,
            String status,
            int points,
            int falseReportCount,
            Instant createdAt,
            Instant lastLoginAt,
            long accountAgeDays,
            boolean newAccount,
            Instant reportMutedUntil,
            List<TagChip> tags
    ) {

        /** Cùng bản ghi nhưng kèm danh sách tag (tag được đọc theo lô sau khi đã có các dòng người dùng). */
        public UserItem withTags(List<TagChip> newTags) {
            return new UserItem(id, email, displayName, avatarUrl, role, roleLabel, status, points, falseReportCount,
                    createdAt, lastLoginAt, accountAgeDays, newAccount, reportMutedUntil, newTags);
        }
    }

    /**
     * Yêu cầu cập nhật vai trò người dùng (USER, MODERATOR, ACADEMIC_MANAGER, ADMIN).
     * {@code expectedRole} (không bắt buộc) là vai trò trang đang hiển thị: khác vai trò hiện tại nghĩa là một Admin
     * khác vừa đổi tài khoản này.
     */
    public record UpdateUserRoleRequest(
            String role,
            String expectedRole
    ) {}

    /**
     * Yêu cầu cập nhật trạng thái người dùng (ACTIVE, LOCKED) kèm lý do.
     * {@code expectedStatus} (không bắt buộc) hoạt động như {@code expectedRole}.
     */
    public record UpdateUserStatusRequest(
            String status,
            String reason,
            String expectedStatus
    ) {}

    /**
     * Thông tin một tham số cấu hình hệ thống.
     * {@code wired}: hệ thống đang dùng tham số này (sửa được, giá trị nguyên từ {@code min} đến {@code max});
     * false = "Chưa áp dụng", chỉ xem, min và max là null.
     */
    public record SystemSettingItem(
            String settingKey,
            String settingValue,
            String label,
            String group,
            String description,
            Instant updatedAt,
            boolean wired,
            Integer min,
            Integer max
    ) {}

    /**
     * Yêu cầu cập nhật giá trị của tham số cấu hình
     */
    public record UpdateSettingRequest(
            String settingValue
    ) {}

    /**
     * Thông tin một từ khóa cấm trong danh mục kiểm duyệt tự động
     */
    public record BannedKeywordItem(
            Long id,
            String keyword,
            String creatorName,
            Instant createdAt
    ) {}

    /**
     * Yêu cầu thêm từ khóa cấm mới
     */
    public record CreateBannedKeywordRequest(
            String keyword
    ) {}

    /**
     * Thông tin chi tiết một bản ghi nhật ký hoạt động kiểm toán
     */
    public record ActivityLogItem(
            Long id,
            Long actorId,
            String actorName,
            String actorEmail,
            String action,
            String targetType,
            Long targetId,
            String oldValue,
            String newValue,
            String description,
            Instant createdAt
    ) {}

    /**
     * Kết quả phân trang dùng chung cho các danh sách trong trang quản trị
     */
    public record PageResult<T>(
            List<T> items,
            int page,
            int size,
            long total,
            int totalPages
    ) {}
}
