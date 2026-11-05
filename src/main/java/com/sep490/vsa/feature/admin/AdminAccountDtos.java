package com.sep490.vsa.feature.admin;

import com.sep490.vsa.feature.admin.AdminDtos.ActivityLogItem;
import com.sep490.vsa.feature.admin.AdminDtos.TagChip;

import java.time.Instant;
import java.util.List;

/**
 * Dữ liệu trả về cho danh sách và chi tiết tài khoản của trang quản trị (bổ sung cho {@link AdminDtos.UserItem}).
 */
public final class AdminAccountDtos {

    private AdminAccountDtos() {
    }

    /** Số Moderator đang hoạt động so với giới hạn cho phép. */
    public record ModeratorCapacity(long active, int max) {}

    /** Hạng của người dùng theo tổng điểm (bảng ranks). */
    public record RankInfo(String name, int minPoints) {}

    /** Nhóm "Thông tin cơ bản" của trang chi tiết. */
    public record BasicIdentity(
            Long id,
            String displayName,
            String email,
            String avatarUrl,
            Instant registeredAt,
            Instant lastLoginAt,
            long accountAgeDays,
            boolean newAccount,
            String status
    ) {}

    /** Nhóm "Vai trò và định danh": vai trò hiện tại, tag chỉ đọc, điểm và hạng. */
    public record AccessAndCredentials(
            String role,
            String roleLabel,
            List<TagChip> tags,
            int points,
            RankInfo rank
    ) {}

    /** Nhóm "An toàn và kiểm duyệt": báo cáo sai, khóa báo cáo, nội dung đã bị gỡ, nhật ký liên quan. */
    public record SafetyMetrics(
            int falseReportCount,
            Instant reportMutedUntil,
            long removedContentCount,
            List<ActivityLogItem> recentActivity
    ) {}

    /** Toàn bộ chi tiết một tài khoản cùng các số liệu trang cần để vẽ khối đổi vai trò. */
    public record AccountDetail(
            BasicIdentity basic,
            AccessAndCredentials access,
            SafetyMetrics safety,
            ModeratorCapacity moderatorCapacity,
            int newAccountDays
    ) {}

    /** Dữ liệu cho thanh lọc và đầu trang danh sách: các tag để lọc, bộ đếm Moderator, số ngày "tài khoản mới". */
    public record AccountListOptions(
            List<TagChip> tags,
            ModeratorCapacity moderatorCapacity,
            int newAccountDays
    ) {}
}
