package com.sep490.vsa.feature.admin;

import com.sep490.vsa.common.exception.ApiException;
import com.sep490.vsa.common.live.LiveEvents;
import com.sep490.vsa.common.security.AppUserPrincipal;
import com.sep490.vsa.common.settings.SystemSettings;
import com.sep490.vsa.feature.admin.AdminDtos.PageResult;
import com.sep490.vsa.feature.admin.AdminDtos.TagChip;
import com.sep490.vsa.feature.admin.AdminDtos.UserItem;
import com.sep490.vsa.feature.blog.ActiveAuthors;
import com.sep490.vsa.feature.blog.AuthorStatusSync;
import com.sep490.vsa.feature.user.Role;
import com.sep490.vsa.feature.user.UserStatus;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Dịch vụ quản lý tài khoản người dùng và phân quyền dành cho Quản trị viên (Admin).
 * Phụ trách tìm kiếm, lọc, cập nhật vai trò, khóa/mở khóa tài khoản với các ràng buộc nghiệp vụ.
 * <p>
 * Mọi lần đổi vai trò hay trạng thái bắt đầu bằng việc khóa dòng max_moderators trong system_settings
 * (SELECT … FOR UPDATE): các lần đổi chạy lần lượt, nên hai Admin thăng chức hay mở khóa Moderator cùng lúc không thể
 * vượt giới hạn. Thứ tự khóa: system_settings → các bài viết (AuthorStatusSync) → users.
 */
@Service
public class AdminUserService {

    static final String DELETED_ACCOUNT = "Tài khoản này đã bị xóa, không thể thay đổi vai trò hay trạng thái.";
    /** Mặc định của max_moderators khi thiếu cấu hình (quy định: tối đa 3 Moderator). */
    static final int MAX_MODERATORS_DEFAULT = 3;
    /** Lý do khóa/mở khóa được lưu tối đa chừng này ký tự (mô tả nhật ký là VARCHAR(1000)). */
    static final int REASON_MAX = 500;

    /** Mặc định của new_account_days khi thiếu cấu hình (bằng giá trị khởi tạo trong V1). */
    static final int NEW_ACCOUNT_DAYS_DEFAULT = 7;
    static final String ADMIN_PROTECTED = "Không thể thực hiện thao tác này với tài khoản đã chọn.";
    static final String STALE_ACCOUNT = "Tài khoản này vừa được một quản trị viên khác thay đổi. Dữ liệu đã được làm mới.";

    /** Cột của một dòng người dùng trong danh sách và chi tiết (xem {@link #mapUser}). */
    private static final String USER_COLUMNS = """
            SELECT u.id, u.email, u.display_name, u.avatar_url, u.role, u.status, u.points,
                   u.false_report_count, u.report_muted_until, u.created_at, u.last_login_at
            FROM users u
            """;

    private final JdbcTemplate jdbc;
    private final ActivityLogService logService;
    private final AuthorStatusSync authorStatusSync;
    private final SystemSettings settings;
    private final LiveEvents live;

    public AdminUserService(JdbcTemplate jdbc, ActivityLogService logService,
                            AuthorStatusSync authorStatusSync, SystemSettings settings, LiveEvents live) {
        this.jdbc = jdbc;
        this.logService = logService;
        this.authorStatusSync = authorStatusSync;
        this.settings = settings;
        this.live = live;
    }

    /**
     * Tìm kiếm và phân trang danh sách người dùng theo từ khóa (tên, email), vai trò và trạng thái.
     * Ký tự %, _ và \ trong từ khóa được tìm đúng như gõ. {@code tagId} (không bắt buộc) chỉ giữ người đang có tag đó.
     * Mỗi dòng kèm tag định danh, tuổi tài khoản và thời hạn khóa báo cáo; tag của cả trang đọc bằng một truy vấn.
     */
    @Transactional(readOnly = true)
    public PageResult<UserItem> getUsers(String keyword, String role, String status, Long tagId, int page, int size) {
        int pageNumber = Math.max(1, Math.min(ActivityLogService.MAX_PAGE, page));
        int pageSize = Math.max(1, Math.min(100, size));
        long offset = (long) (pageNumber - 1) * pageSize;

        StringBuilder whereClause = new StringBuilder(" WHERE 1=1 ");
        List<Object> params = new ArrayList<>();

        if (keyword != null && !keyword.isBlank()) {
            String kw = "%" + escapeLike(keyword.trim()) + "%";
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

        if (tagId != null) {
            whereClause.append(" AND EXISTS (SELECT 1 FROM user_tags ut WHERE ut.user_id = u.id"
                    + " AND ut.tag_type_id = ? AND ut.revoked_at IS NULL) ");
            params.add(tagId);
        }

        String countSql = "SELECT COUNT(*) FROM users u " + whereClause;
        Long totalObj = jdbc.queryForObject(countSql, Long.class, params.toArray());
        long total = totalObj != null ? totalObj : 0;

        String selectSql = USER_COLUMNS + whereClause + " ORDER BY u.id DESC LIMIT ? OFFSET ? ";

        params.add(pageSize);
        params.add(offset);

        int newAccountDays = newAccountDays();
        List<UserItem> items = withTags(jdbc.query(selectSql, (rs, rowNum) -> mapUser(rs, newAccountDays),
                params.toArray()));

        int totalPages = (int) Math.ceil((double) total / pageSize);
        return new PageResult<>(items, pageNumber, pageSize, total, totalPages);
    }

    /**
     * Lấy thông tin chi tiết của một người dùng theo ID.
     */
    @Transactional(readOnly = true)
    public UserItem getUser(Long id) {
        int newAccountDays = newAccountDays();
        List<UserItem> list = jdbc.query(USER_COLUMNS + " WHERE u.id = ?",
                (rs, rowNum) -> mapUser(rs, newAccountDays), id);

        if (list.isEmpty()) {
            throw new ApiException(HttpStatus.NOT_FOUND, "Không tìm thấy người dùng với ID: " + id);
        }
        return withTags(list).get(0);
    }

    /** Số ngày một tài khoản còn được coi là mới (new_account_days; 0 = không có tài khoản mới). */
    int newAccountDays() {
        return AdminConfigService.clampToRange(SystemSettings.NEW_ACCOUNT_DAYS,
                settings.getInt(SystemSettings.NEW_ACCOUNT_DAYS, NEW_ACCOUNT_DAYS_DEFAULT));
    }

    /** Một dòng của {@link #USER_COLUMNS}: vai trò có nhãn tiếng Việt, tuổi tài khoản và thời hạn khóa báo cáo. */
    private static UserItem mapUser(ResultSet rs, int newAccountDays) throws SQLException {
        String roleCode = rs.getString("role");
        String roleLabel = roleCode;
        try {
            roleLabel = Role.valueOf(roleCode).label();
        } catch (IllegalArgumentException ignored) {
            // vai trò lạ trong cơ sở dữ liệu: hiện mã của nó
        }
        Instant created = toInstant(rs.getTimestamp("created_at"));
        long ageDays = created == null ? 0 : Math.max(0, Duration.between(created, Instant.now()).toDays());
        Instant mutedUntil = toInstant(rs.getTimestamp("report_muted_until"));
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
                created,
                toInstant(rs.getTimestamp("last_login_at")),
                ageDays,
                ageDays < newAccountDays,
                mutedUntil != null && mutedUntil.isAfter(Instant.now()) ? mutedUntil : null,
                List.of());
    }

    /** Gắn tag định danh còn hiệu lực vào các dòng, đọc một lần cho cả danh sách (không truy vấn từng người). */
    private List<UserItem> withTags(List<UserItem> items) {
        if (items.isEmpty()) {
            return items;
        }
        String marks = String.join(",", Collections.nCopies(items.size(), "?"));
        Map<Long, List<TagChip>> byUser = new HashMap<>();
        jdbc.query("""
                SELECT ut.user_id, t.id, t.name, t.color
                FROM user_tags ut JOIN tag_types t ON t.id = ut.tag_type_id
                WHERE ut.revoked_at IS NULL AND t.is_active AND ut.user_id IN (""" + marks + """
                ) ORDER BY ut.granted_at, t.id""",
                rs -> {
                    byUser.computeIfAbsent(rs.getLong(1), k -> new ArrayList<>())
                            .add(new TagChip(rs.getLong(2), rs.getString(3), rs.getString(4)));
                },
                items.stream().map(UserItem::id).toArray());
        return items.stream().map(item -> item.withTags(byUser.getOrDefault(item.id(), List.of()))).toList();
    }

    /**
     * Cập nhật vai trò người dùng (USER, MODERATOR, ACADEMIC_MANAGER, ADMIN).
     * Ràng buộc:
     * - Admin không được tự đổi vai trò của chính mình.
     * - Không đổi vai trò của tài khoản đã xóa (DELETED).
     * - Không đổi vai trò của một Admin khác đang hoạt động ({@link #ADMIN_PROTECTED}); Admin đã bị khóa thì đổi được.
     *   Nhờ vậy luôn còn Admin đang hoạt động: người thực hiện chính là một Admin đang hoạt động.
     * - Số lượng tài khoản mang vai trò MODERATOR đang hoạt động không được vượt quá max_moderators (mặc định 3).
     * - {@code expectedRole} (nếu có) là vai trò trang đang hiển thị; khác vai trò hiện tại thì trả 409 vì một Admin
     *   khác vừa đổi tài khoản này.
     * Phiên đăng nhập của người được đổi vai trò nhận vai trò mới ngay ở yêu cầu kế tiếp (SessionUserRefreshFilter).
     * READ COMMITTED: số Moderator được đếm sau khi giữ khóa, nên thấy thay đổi vừa lưu của Admin khác.
     *
     * @return true nếu vai trò đã được đổi, false nếu tài khoản đã mang vai trò đó
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public boolean updateUserRole(Long targetUserId, String newRoleStr, String expectedRole, AppUserPrincipal me) {
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

        // Các lần đổi vai trò/trạng thái chạy lần lượt từ đây
        int maxModerators = lockModeratorLimit();

        // Lấy thông tin hiện tại của user mục tiêu
        UserItem targetUser = getUser(targetUserId);
        if (UserStatus.DELETED.name().equals(targetUser.status())) {
            throw ApiException.badRequest(DELETED_ACCOUNT);
        }
        assertNotStale(expectedRole, targetUser.role());
        if (targetUser.role().equalsIgnoreCase(newRole.name())) {
            return false; // Không có thay đổi
        }

        // Admin khác đang hoạt động không bị đổi vai trò qua màn hình quản trị
        assertNotActiveAdmin(targetUser);

        // Kiểm tra ràng buộc số lượng Moderator tối đa nếu muốn thăng chức thành MODERATOR
        if (newRole == Role.MODERATOR) {
            assertModeratorSlot(maxModerators);
        }

        // Cập nhật vai trò. "Xóa tài khoản" không chờ khóa ở trên: tài khoản vừa bị xóa trong lúc này thì không đổi gì
        if (jdbc.update("UPDATE users SET role = ?, updated_at = CURRENT_TIMESTAMP WHERE id = ? AND status <> 'DELETED'",
                newRole.name(), targetUserId) == 0) {
            throw ApiException.badRequest(DELETED_ACCOUNT);
        }

        // Ghi nhận nhật ký kiểm toán
        logService.recordLog(
                me != null ? me.id() : null,
                "USER_ROLE_CHANGE",
                "USER",
                targetUserId,
                Map.of("role", targetUser.role()),
                Map.of("role", newRole.name()),
                "Thay đổi vai trò của người dùng " + targetUser.displayName() + " (" + targetUser.email() + ") từ "
                        + targetUser.roleLabel() + " sang " + newRole.label()
        );
        return true;
    }

    /** 409 khi trang đang hiển thị giá trị khác giá trị hiện tại: một Admin khác đã đổi tài khoản trước. */
    private static void assertNotStale(String expected, String current) {
        if (expected != null && !expected.isBlank() && !expected.trim().equalsIgnoreCase(current)) {
            throw ApiException.conflict(STALE_ACCOUNT);
        }
    }

    /** 400 khi đích là một Admin đang hoạt động (người thực hiện không bao giờ là chính họ, đã chặn ở trên). */
    private static void assertNotActiveAdmin(UserItem target) {
        if (Role.ADMIN.name().equals(target.role()) && UserStatus.ACTIVE.name().equals(target.status())) {
            throw ApiException.badRequest(ADMIN_PROTECTED);
        }
    }

    /**
     * Cập nhật trạng thái người dùng (ACTIVE <-> LOCKED).
     * Ràng buộc:
     * - Admin không được tự đổi trạng thái của chính mình (khóa hay mở khóa).
     * - Chỉ nhận ACTIVE hoặc LOCKED; tài khoản chỉ bị xóa (DELETED) qua "Xóa tài khoản" của chính người dùng.
     * - Không khóa/mở khóa tài khoản đã xóa (DELETED).
     * - Không khóa một Admin khác đang hoạt động ({@link #ADMIN_PROTECTED}); mở khóa Admin đã bị khóa thì được.
     * - Mở khóa một Moderator không được làm số Moderator đang hoạt động vượt quá max_moderators.
     * - {@code expectedStatus} (nếu có) là trạng thái trang đang hiển thị; khác trạng thái hiện tại thì trả 409.
     * Tài khoản bị khóa bị đăng xuất ngay ở yêu cầu kế tiếp (SessionUserRefreshFilter).
     * Bài viết, câu hỏi, bình luận và câu trả lời của tài khoản bị khóa được ẩn ({@link ActiveAuthors}) và hiện lại khi
     * mở khóa; số bình luận của các bài người đó đã bình luận được đếm lại trong cùng giao dịch, và "Bài nổi bật" được
     * tính lại sau khi lưu ({@link AuthorStatusSync}).
     * Lý do được lưu tối đa {@value #REASON_MAX} ký tự.
     *
     * @return true nếu trạng thái đã được đổi, false nếu tài khoản đã ở trạng thái đó
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public boolean updateUserStatus(Long targetUserId, String newStatusStr, String reason, String expectedStatus,
                                    AppUserPrincipal me) {
        if (targetUserId == null) {
            throw ApiException.badRequest("Mã người dùng không hợp lệ.");
        }
        if (me != null && targetUserId.equals(me.id())) {
            throw ApiException.badRequest("Không thể tự thay đổi trạng thái của chính tài khoản đang đăng nhập.");
        }

        UserStatus newStatus;
        try {
            newStatus = UserStatus.valueOf(newStatusStr.trim().toUpperCase());
        } catch (Exception e) {
            throw ApiException.badRequest("Trạng thái không hợp lệ: " + newStatusStr);
        }
        if (newStatus != UserStatus.ACTIVE && newStatus != UserStatus.LOCKED) {
            throw ApiException.badRequest("Trạng thái không hợp lệ: " + newStatusStr
                    + ". Chỉ có thể khóa (LOCKED) hoặc mở khóa (ACTIVE) tài khoản.");
        }

        // Các lần đổi vai trò/trạng thái chạy lần lượt từ đây
        int maxModerators = lockModeratorLimit();

        UserItem targetUser = getUser(targetUserId);
        if (UserStatus.DELETED.name().equals(targetUser.status())) {
            throw ApiException.badRequest(DELETED_ACCOUNT);
        }
        assertNotStale(expectedStatus, targetUser.status());
        if (targetUser.status().equalsIgnoreCase(newStatus.name())) {
            return false; // Trạng thái đã là trạng thái mục tiêu
        }

        // Không khóa một Admin khác đang hoạt động; mở khóa Admin đã bị khóa thì được
        if (newStatus == UserStatus.LOCKED) {
            assertNotActiveAdmin(targetUser);
        }

        // Mở khóa một Moderator: người đó lại được tính vào giới hạn
        if (newStatus == UserStatus.ACTIVE && Role.MODERATOR.name().equals(targetUser.role())) {
            assertModeratorSlot(maxModerators);
        }

        // Khóa trước các bài người này đã bình luận (cùng thứ tự khóa với thao tác bình luận: bài trước, users sau)
        authorStatusSync.beforeStatusChange(targetUserId);

        // Cập nhật trạng thái, rồi đếm lại số bình luận của các bài đó. Chỉ khi trạng thái vẫn như lúc đọc: người dùng có
        // thể vừa "Xóa tài khoản" (không chờ khóa ở trên), và một tài khoản đã xóa không bao giờ bị khóa/mở lại
        if (jdbc.update("UPDATE users SET status = ?, updated_at = CURRENT_TIMESTAMP WHERE id = ? AND status = ?",
                newStatus.name(), targetUserId, targetUser.status()) == 0) {
            throw ApiException.badRequest(DELETED_ACCOUNT);
        }
        authorStatusSync.afterStatusChange(targetUserId);
        if (newStatus == UserStatus.LOCKED) {
            live.close(targetUserId);                   // its open pages get no more live updates (after the commit)
        }

        String cleanReason = reason != null && !reason.isBlank()
                ? ActivityLogService.cut(reason.trim(), REASON_MAX) : null;
        Map<String, Object> newValue = new LinkedHashMap<>();
        newValue.put("status", newStatus.name());
        if (cleanReason != null) {
            newValue.put("reason", cleanReason);
        }
        logService.recordLog(
                me != null ? me.id() : null,
                "USER_STATUS_CHANGE",
                "USER",
                targetUserId,
                Map.of("status", targetUser.status()),
                newValue,
                "Chuyển trạng thái người dùng " + targetUser.displayName() + " (" + targetUser.email() + ") thành "
                        + newStatus.name() + "." + (cleanReason != null ? " Lý do: " + cleanReason : "")
        );
        return true;
    }

    /**
     * Kiểm tra (chỉ đọc, không khóa dòng nào, không ghi gì) một tài khoản có thể bị khóa hoặc mở khóa hay không, để
     * trang báo lỗi ngay khi bấm nút, trước hộp thoại xác nhận. Cùng quy tắc với {@link #updateUserStatus}; việc đổi
     * thật vẫn kiểm tra lại trong giao dịch. Trả về trạng thái hiện tại của tài khoản.
     */
    @Transactional(readOnly = true)
    public String checkStatusChange(Long targetUserId, String targetStatusStr, AppUserPrincipal me) {
        if (targetUserId == null) {
            throw ApiException.badRequest("Mã người dùng không hợp lệ.");
        }
        if (me != null && targetUserId.equals(me.id())) {
            throw ApiException.badRequest(ADMIN_PROTECTED);
        }
        UserStatus target;
        try {
            target = UserStatus.valueOf(targetStatusStr.trim().toUpperCase());
        } catch (Exception e) {
            throw ApiException.badRequest("Trạng thái không hợp lệ: " + targetStatusStr);
        }
        if (target != UserStatus.ACTIVE && target != UserStatus.LOCKED) {
            throw ApiException.badRequest("Trạng thái không hợp lệ: " + targetStatusStr);
        }
        UserItem user = getUser(targetUserId);
        if (UserStatus.DELETED.name().equals(user.status())) {
            throw ApiException.badRequest(DELETED_ACCOUNT);
        }
        if (target == UserStatus.LOCKED) {
            assertNotActiveAdmin(user);
        } else if (Role.MODERATOR.name().equals(user.role()) && !UserStatus.ACTIVE.name().equals(user.status())) {
            assertModeratorSlot(getMaxModeratorsAllowed());
        }
        return user.status();
    }

    /**
     * Đọc giá trị giới hạn số Moderator từ bảng system_settings (mặc định 3 nếu không cấu hình), giữ trong khoảng 1–3.
     */
    public int getMaxModeratorsAllowed() {
        return AdminConfigService.clampToRange(SystemSettings.MAX_MODERATORS,
                settings.getInt(SystemSettings.MAX_MODERATORS, MAX_MODERATORS_DEFAULT));
    }

    /**
     * Khóa dòng max_moderators (SELECT … FOR UPDATE) đến hết giao dịch và trả về giới hạn. Mọi lần đổi vai trò hay
     * trạng thái đều gọi hàm này trước tiên, nên việc đếm Moderator và cập nhật của chúng chạy lần lượt.
     */
    private int lockModeratorLimit() {
        List<String> values = jdbc.query(
                "SELECT setting_value FROM system_settings WHERE setting_key = ? FOR UPDATE",
                (rs, rowNum) -> rs.getString(1), SystemSettings.MAX_MODERATORS);
        int limit = MAX_MODERATORS_DEFAULT;
        if (!values.isEmpty()) {
            try {
                limit = Integer.parseInt(values.get(0).strip());
            } catch (NumberFormatException ignored) {
                // giá trị hỏng: dùng mặc định
            }
        }
        return AdminConfigService.clampToRange(SystemSettings.MAX_MODERATORS, limit);
    }

    /** 400 khi số Moderator đang hoạt động đã bằng giới hạn (gọi sau {@link #lockModeratorLimit()}). */
    private void assertModeratorSlot(int maxModerators) {
        Long currentActiveModerators = jdbc.queryForObject(
                "SELECT COUNT(*) FROM users WHERE role = 'MODERATOR' AND status = 'ACTIVE'", Long.class);
        long count = currentActiveModerators != null ? currentActiveModerators : 0;
        if (count >= maxModerators) {
            throw ApiException.badRequest(
                    "Hệ thống chỉ cho phép tối đa " + maxModerators + " Moderator đang hoạt động. Hiện đã đủ chỉ tiêu.");
        }
    }

    /** Từ khóa dùng trong LIKE: \, % và _ được tìm đúng như gõ (cách thoát mặc định của MySQL là \). */
    static String escapeLike(String text) {
        return text.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    private static Instant toInstant(Timestamp ts) {
        return ts != null ? ts.toInstant() : null;
    }
}
