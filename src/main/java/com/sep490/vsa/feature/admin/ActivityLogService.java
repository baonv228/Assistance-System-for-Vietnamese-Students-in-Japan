package com.sep490.vsa.feature.admin;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sep490.vsa.common.settings.SystemSettings;
import com.sep490.vsa.feature.admin.AdminDtos.ActivityLogItem;
import com.sep490.vsa.feature.admin.AdminDtos.PageResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Dịch vụ ghi nhận và tra cứu nhật ký kiểm toán hệ thống.
 * Mọi thao tác quan trọng của quản trị viên (đổi vai trò, khóa tài khoản, sửa cấu hình, đổi từ khóa cấm)
 * đều được ghi lại để phục vụ việc kiểm tra và bảo mật.
 * Nhật ký cũ hơn log_keep_days ngày được xóa tự động lúc 3:30 mỗi ngày (giờ Nhật).
 */
@Service
public class ActivityLogService {

    private static final Logger log = LoggerFactory.getLogger(ActivityLogService.class);

    /** Cột description là VARCHAR(1000). */
    static final int DESCRIPTION_MAX = 1000;
    /** Trang lớn nhất được đọc: số trang quá lớn không làm OFFSET bị tràn số. */
    static final int MAX_PAGE = 100_000;
    /** Mặc định của log_keep_days khi thiếu cấu hình (bằng giá trị khởi tạo trong V1). */
    static final int LOG_KEEP_DEFAULT_DAYS = 90;
    /** Số dòng xóa trong mỗi giao dịch ngắn của việc dọn nhật ký. */
    static final int PURGE_BATCH = 1000;

    private final ActivityLogRepository logRepository;
    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final SystemSettings settings;
    private final TransactionTemplate tx;
    private final Clock clock;

    public ActivityLogService(ActivityLogRepository logRepository, JdbcTemplate jdbc, ObjectMapper objectMapper,
                              SystemSettings settings, PlatformTransactionManager transactionManager, Clock clock) {
        this.logRepository = logRepository;
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.settings = settings;
        this.tx = new TransactionTemplate(transactionManager);
        this.clock = clock;
    }

    /**
     * Ghi nhận một thao tác vào nhật ký kiểm toán. Giá trị cũ/mới được Jackson chuyển thành JSON, nên dấu ", \ hay
     * thẻ HTML trong giá trị được lưu và đọc lại nguyên vẹn; mô tả dài quá 1000 ký tự được cắt bớt.
     *
     * @param actorId     ID của người dùng thực hiện
     * @param action      Mã hành động
     * @param targetType  Loại đối tượng chịu tác động (USER, SETTING, BANNED_KEYWORD)
     * @param targetId    ID đối tượng chịu tác động (nếu có)
     * @param oldValue    Giá trị trước khi thay đổi (null nếu không có)
     * @param newValue    Giá trị sau khi thay đổi (null nếu không có)
     * @param description Mô tả tóm tắt hành động
     */
    @Transactional
    public void recordLog(Long actorId, String action, String targetType, Long targetId,
                          Map<String, ?> oldValue, Map<String, ?> newValue, String description) {
        ActivityLog log = new ActivityLog(actorId, action, targetType, targetId, toJson(oldValue), toJson(newValue),
                cut(description, DESCRIPTION_MAX));
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
            ORDER BY l.created_at DESC, l.id DESC
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
     * Thứ tự created_at rồi id (mới trước) cố định, nên các bản ghi cùng giây không nhảy giữa các trang.
     */
    @Transactional(readOnly = true)
    public PageResult<ActivityLogItem> getLogs(int page, int size, String action) {
        int pageNumber = Math.max(1, Math.min(MAX_PAGE, page));
        int pageSize = Math.max(1, Math.min(100, size));
        long offset = (long) (pageNumber - 1) * pageSize;

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
        """ + whereClause + " ORDER BY l.created_at DESC, l.id DESC LIMIT ? OFFSET ? ";

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

    /**
     * Dọn nhật ký: xóa các bản ghi cũ hơn log_keep_days ngày (không dưới 30 ngày, xem
     * {@link AdminConfigService#clampToRange}). Xóa từng đợt {@value #PURGE_BATCH} dòng, mỗi đợt một giao dịch ngắn,
     * nên không giữ khóa bảng lâu.
     */
    @Scheduled(cron = "0 30 3 * * *", zone = "Asia/Tokyo")
    public void purgeOldLogs() {
        int keepDays = AdminConfigService.clampToRange(SystemSettings.LOG_KEEP_DAYS,
                settings.getInt(SystemSettings.LOG_KEEP_DAYS, LOG_KEEP_DEFAULT_DAYS));
        Timestamp before = Timestamp.from(clock.instant().minus(Duration.ofDays(keepDays)));
        int total = 0;
        int removed;
        do {
            removed = tx.execute(status -> jdbc.update(
                    "DELETE FROM activity_logs WHERE created_at < ? ORDER BY id LIMIT " + PURGE_BATCH, before));
            total += removed;
        } while (removed == PURGE_BATCH);
        log.info("Activity log clean-up: {} rows older than {} days removed", total, keepDays);
    }

    /** {@code text} cắt còn tối đa {@code max} ký tự, không cắt đôi một ký tự ngoài BMP (emoji). */
    static String cut(String text, int max) {
        if (text == null || text.length() <= max) {
            return text;
        }
        int end = Character.isHighSurrogate(text.charAt(max - 1)) ? max - 1 : max;
        return text.substring(0, end);
    }

    private String toJson(Map<String, ?> value) {
        if (value == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Activity log value is not JSON-serialisable", e);
        }
    }

    private static Instant toInstant(Timestamp ts) {
        return ts != null ? ts.toInstant() : null;
    }
}
