package com.sep490.vsa.feature.admin;

import com.sep490.vsa.feature.admin.AdminAccountDtos.ModeratorCapacity;
import com.sep490.vsa.feature.admin.AdminSafetyDtos.SafetyGroup;

import java.util.List;

/**
 * Dữ liệu của trang "Tổng quan hệ thống": số liệu tài khoản, xu hướng review, hạn mức dịch vụ, cấu hình và nhân sự.
 */
public final class AdminOverviewDtos {

    private AdminOverviewDtos() {
    }

    /** Khoảng ngày đã chọn (ngày theo giờ Nhật, định dạng yyyy-MM-dd, gồm cả hai đầu). */
    public record Period(String preset, String from, String to) {}

    /** Các ô đếm tài khoản (không tính tài khoản đã xóa). {@code newAccounts} chỉ đếm vai trò Người dùng còn mới. */
    public record AccountTiles(
            long totalAccounts,
            long newAccounts,
            int newAccountDays,
            long totalUsers,
            long totalModerators,
            long totalAcademicManagers,
            long totalAdmins,
            long totalLocked
    ) {}

    /** Số review tạo trong một ngày. */
    public record ReviewDay(String date, long count) {}

    /** Biểu đồ review mỗi ngày và số báo cáo bị bác trong khoảng đã chọn. */
    public record ReviewTrend(List<ReviewDay> days, long totalReviews, long dismissedReports) {}

    /**
     * Mức dùng một dịch vụ ngoài hoặc dung lượng. {@code percent} tối đa 100 (độ rộng thanh);
     * {@code alert} = đã dùng từ 80% hạn mức.
     */
    public record QuotaMeter(
            String key,
            String label,
            double used,
            double limit,
            String unit,
            String window,
            double percent,
            boolean alert
    ) {}

    /** Số nhân sự đang hoạt động theo vai trò. */
    public record StaffCounts(ModeratorCapacity moderators, long academicManagers, long admins) {}

    /** Toàn bộ dữ liệu trang tổng quan. {@code empty} = khoảng đã chọn không có review lẫn báo cáo bị bác. */
    public record Overview(
            Period period,
            AccountTiles accounts,
            ReviewTrend reviewTrend,
            List<QuotaMeter> quotas,
            List<SafetyGroup> configuration,
            StaffCounts staff,
            boolean empty
    ) {}
}
