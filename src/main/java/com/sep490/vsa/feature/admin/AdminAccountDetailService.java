package com.sep490.vsa.feature.admin;

import com.sep490.vsa.feature.admin.AdminAccountDtos.AccessAndCredentials;
import com.sep490.vsa.feature.admin.AdminAccountDtos.AccountDetail;
import com.sep490.vsa.feature.admin.AdminAccountDtos.AccountListOptions;
import com.sep490.vsa.feature.admin.AdminAccountDtos.BasicIdentity;
import com.sep490.vsa.feature.admin.AdminAccountDtos.ModeratorCapacity;
import com.sep490.vsa.feature.admin.AdminAccountDtos.RankInfo;
import com.sep490.vsa.feature.admin.AdminAccountDtos.SafetyMetrics;
import com.sep490.vsa.feature.admin.AdminDtos.TagChip;
import com.sep490.vsa.feature.admin.AdminDtos.UserItem;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Ghép dữ liệu cho trang chi tiết tài khoản và cho thanh lọc của danh sách tài khoản. Chỉ đọc: việc đổi vai trò và
 * khóa/mở khóa nằm ở {@link AdminUserService}.
 */
@Service
public class AdminAccountDetailService {

    /** Số bản ghi nhật ký liên quan hiển thị ở chi tiết tài khoản. */
    static final int RECENT_LOG_LIMIT = 10;

    private final JdbcTemplate jdbc;
    private final AdminUserService userService;
    private final ActivityLogService logService;

    public AdminAccountDetailService(JdbcTemplate jdbc, AdminUserService userService, ActivityLogService logService) {
        this.jdbc = jdbc;
        this.userService = userService;
        this.logService = logService;
    }

    /** Chi tiết một tài khoản; 404 nếu không còn (do {@link AdminUserService#getUser}). */
    @Transactional(readOnly = true)
    public AccountDetail getDetail(long userId) {
        UserItem user = userService.getUser(userId);

        BasicIdentity basic = new BasicIdentity(user.id(), user.displayName(), user.email(), user.avatarUrl(),
                user.createdAt(), user.lastLoginAt(), user.accountAgeDays(), user.newAccount(), user.status());
        AccessAndCredentials access = new AccessAndCredentials(user.role(), user.roleLabel(), user.tags(),
                user.points(), rankOf(user.points()));
        SafetyMetrics safety = new SafetyMetrics(user.falseReportCount(), user.reportMutedUntil(),
                removedContentCount(userId), logService.getRecentLogsForUser(userId, RECENT_LOG_LIMIT));

        return new AccountDetail(basic, access, safety, capacity(), userService.newAccountDays());
    }

    /** Dữ liệu cho thanh lọc (các tag đang dùng) và đầu trang danh sách. */
    @Transactional(readOnly = true)
    public AccountListOptions listOptions() {
        List<TagChip> tags = jdbc.query(
                "SELECT id, name, color FROM tag_types WHERE is_active ORDER BY name",
                (rs, i) -> new TagChip(rs.getLong(1), rs.getString(2), rs.getString(3)));
        return new AccountListOptions(tags, capacity(), userService.newAccountDays());
    }

    /** Moderator đang hoạt động so với giới hạn (chỉ tài khoản ACTIVE được tính, như khi đổi vai trò). */
    ModeratorCapacity capacity() {
        Long active = jdbc.queryForObject(
                "SELECT COUNT(*) FROM users WHERE role = 'MODERATOR' AND status = 'ACTIVE'", Long.class);
        return new ModeratorCapacity(active == null ? 0 : active, userService.getMaxModeratorsAllowed());
    }

    /** Hạng cao nhất mà tổng điểm đã đạt; null nếu bảng ranks trống. */
    private RankInfo rankOf(int points) {
        List<RankInfo> ranks = jdbc.query(
                "SELECT name, min_points FROM ranks WHERE min_points <= ? ORDER BY min_points DESC LIMIT 1",
                (rs, i) -> new RankInfo(rs.getString(1), rs.getInt(2)), points);
        return ranks.isEmpty() ? null : ranks.get(0);
    }

    /**
     * Số bài viết, câu hỏi và review của người này đã bị gỡ (REMOVED). Bình luận không tính: bình luận tác giả tự xóa
     * mà còn trả lời bên dưới cũng mang trạng thái REMOVED nên không phân biệt được với bình luận bị Moderator gỡ.
     */
    private long removedContentCount(long userId) {
        Long count = jdbc.queryForObject("""
                SELECT (SELECT COUNT(*) FROM blogs WHERE author_id = ? AND status = 'REMOVED')
                     + (SELECT COUNT(*) FROM reviews WHERE author_id = ? AND status = 'REMOVED')""",
                Long.class, userId, userId);
        return count == null ? 0 : count;
    }
}
