package com.sep490.vsa.feature.admin;

import com.sep490.vsa.common.exception.ApiException;
import com.sep490.vsa.common.security.AppUserPrincipal;
import com.sep490.vsa.feature.admin.AdminDtos.BannedKeywordItem;
import com.sep490.vsa.feature.admin.AdminDtos.SystemSettingItem;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Dịch vụ cấu hình hệ thống và quản lý danh mục từ khóa cấm.
 * Hỗ trợ quản trị viên điều chỉnh các tham số vận hành (ngưỡng báo cáo, giới hạn đăng tin, thời gian khóa)
 * và quản lý danh sách từ khóa đen để bộ lọc tự động kiểm duyệt nội dung.
 */
@Service
public class AdminConfigService {

    private final SystemSettingRepository settingRepository;
    private final BannedKeywordRepository keywordRepository;
    private final ActivityLogService logService;
    private final JdbcTemplate jdbc;

    /** Bảng mô tả thông tin tham số cấu hình hiển thị tiếng Việt */
    private static final Map<String, SettingMeta> SETTING_METADATA = new LinkedHashMap<>();

    private record SettingMeta(String label, String group, String description) {}

    static {
        // Nhóm: Báo cáo & Kiểm duyệt
        SETTING_METADATA.put("report_hide_threshold", new SettingMeta(
                "Ngưỡng ẩn nội dung", "Báo cáo & Kiểm duyệt",
                "Số lượng báo cáo để hệ thống tự động ẩn tạm thời bài viết/bình luận chờ kiểm duyệt"));
        SETTING_METADATA.put("false_report_max", new SettingMeta(
                "Số lần báo cáo sai tối đa", "Báo cáo & Kiểm duyệt",
                "Số lần báo cáo sai sự thật tối đa trước khi người dùng bị tạm khóa quyền gửi báo cáo"));
        SETTING_METADATA.put("mute_days", new SettingMeta(
                "Số ngày tạm cấm báo cáo", "Báo cáo & Kiểm duyệt",
                "Thời gian tài khoản bị cấm gửi báo cáo sau khi vượt quá số lần báo cáo sai quy định"));
        SETTING_METADATA.put("closed_report_threshold", new SettingMeta(
                "Ngưỡng xác nhận đóng cửa", "Báo cáo & Kiểm duyệt",
                "Số lượng người dùng báo cáo một địa điểm đã đóng cửa để hệ thống chuyển trạng thái"));

        // Nhóm: Giới hạn hoạt động người dùng
        SETTING_METADATA.put("new_account_days", new SettingMeta(
                "Thời gian tài khoản mới (ngày)", "Giới hạn hoạt động",
                "Khoảng thời gian kể từ khi đăng ký mà tài khoản chịu các giới hạn đăng tin nghiêm ngặt hơn"));
        SETTING_METADATA.put("posts_per_day", new SettingMeta(
                "Số bài viết tối đa/ngày", "Giới hạn hoạt động",
                "Giới hạn số lượng bài blog một tài khoản mới có thể xuất bản trong một ngày"));
        SETTING_METADATA.put("pins_per_day", new SettingMeta(
                "Số ghim địa điểm tối đa/ngày", "Giới hạn hoạt động",
                "Giới hạn số lượng yêu cầu tạo địa điểm mới người dùng có thể gửi trong một ngày"));
        SETTING_METADATA.put("videos_per_day", new SettingMeta(
                "Số video tải lên tối đa/ngày", "Giới hạn hoạt động",
                "Giới hạn số lượt đăng video đánh giá mỗi ngày của người dùng"));

        // Nhóm: Quản trị & Bảo mật
        SETTING_METADATA.put("max_moderators", new SettingMeta(
                "Số lượng Moderator tối đa", "Quản trị & Bảo mật",
                "Giới hạn số lượng tài khoản Moderator hoạt động cùng một thời điểm trong hệ thống"));
        SETTING_METADATA.put("password_min_length", new SettingMeta(
                "Độ dài mật khẩu tối thiểu", "Quản trị & Bảo mật",
                "Số ký tự tối thiểu khi người dùng đăng ký hoặc đổi mật khẩu"));
        SETTING_METADATA.put("lock_attempts", new SettingMeta(
                "Số lần đăng nhập sai tối đa", "Quản trị & Bảo mật",
                "Số lần nhập sai mật khẩu liên tiếp trước khi tài khoản bị tạm khóa bảo vệ"));
        SETTING_METADATA.put("lock_minutes", new SettingMeta(
                "Thời gian tạm khóa (phút)", "Quản trị & Bảo mật",
                "Thời gian tài khoản bị tạm khóa sau khi vượt quá số lần đăng nhập sai"));
        SETTING_METADATA.put("verify_token_hours", new SettingMeta(
                "Thời hạn link xác thực email (giờ)", "Quản trị & Bảo mật",
                "Thời gian hết hạn của liên kết xác thực tài khoản qua email"));
        SETTING_METADATA.put("reset_token_minutes", new SettingMeta(
                "Thời hạn link đổi mật khẩu (phút)", "Quản trị & Bảo mật",
                "Thời gian hết hạn của liên kết đặt lại mật khẩu gửi qua email"));
        SETTING_METADATA.put("log_keep_days", new SettingMeta(
                "Lưu trữ nhật ký (ngày)", "Quản trị & Bảo mật",
                "Thời gian lưu trữ nhật ký kiểm toán activity_logs trước khi dọn dẹp"));
        SETTING_METADATA.put("proof_keep_days", new SettingMeta(
                "Lưu minh chứng tag (ngày)", "Quản trị & Bảo mật",
                "Thời gian lưu trữ file ảnh minh chứng cấp thẻ định danh (thẻ SV, JLPT...)"));
        SETTING_METADATA.put("removed_keep_days", new SettingMeta(
                "Lưu dữ liệu đã xóa (ngày)", "Quản trị & Bảo mật",
                "Thời gian lưu giữ dữ liệu bị xóa mềm trước khi loại bỏ vĩnh viễn"));

        // Nhóm: Đề xuất & Bản đồ
        SETTING_METADATA.put("featured_top_n", new SettingMeta(
                "Số bài viết nổi bật", "Đề xuất & Bản đồ",
                "Số lượng bài viết chất lượng cao được chọn lọc hiển thị trang chủ"));
        SETTING_METADATA.put("featured_min_score", new SettingMeta(
                "Điểm tối thiểu bài nổi bật", "Đề xuất & Bản đồ",
                "Điểm tương tác tối thiểu để bài viết đủ điều kiện xét nổi bật"));
        SETTING_METADATA.put("duplicate_warn_radius_m", new SettingMeta(
                "Bán kính cảnh báo trùng (mét)", "Đề xuất & Bản đồ",
                "Khoảng cách tối thiểu giữa 2 địa điểm để phát hiện trùng lặp tiềm năng"));
        SETTING_METADATA.put("merge_suggest_radius_m", new SettingMeta(
                "Bán kính đề xuất gộp (mét)", "Đề xuất & Bản đồ",
                "Khoảng cách gợi ý moderator gộp hai địa điểm giống nhau"));
    }

    public AdminConfigService(SystemSettingRepository settingRepository,
                              BannedKeywordRepository keywordRepository,
                              ActivityLogService logService,
                              JdbcTemplate jdbc) {
        this.settingRepository = settingRepository;
        this.keywordRepository = keywordRepository;
        this.logService = logService;
        this.jdbc = jdbc;
    }

    /**
     * Lấy toàn bộ danh sách cấu hình hệ thống kèm thông tin nhóm và mô tả chi tiết.
     */
    @Transactional(readOnly = true)
    public List<SystemSettingItem> getAllSettings() {
        List<SystemSetting> list = settingRepository.findAll();
        List<SystemSettingItem> results = new ArrayList<>();

        for (SystemSetting setting : list) {
            String key = setting.getSettingKey();
            SettingMeta meta = SETTING_METADATA.get(key);
            String label = meta != null ? meta.label() : key;
            String group = meta != null ? meta.group() : "Khác";
            String description = meta != null ? meta.description() : "";

            results.add(new SystemSettingItem(
                    key,
                    setting.getSettingValue(),
                    label,
                    group,
                    description,
                    setting.getUpdatedAt()
            ));
        }
        return results;
    }

    /**
     * Cập nhật một tham số cấu hình hệ thống và ghi nhận kiểm toán.
     */
    @Transactional
    public void updateSetting(String settingKey, String newValue, AppUserPrincipal me) {
        if (settingKey == null || settingKey.isBlank()) {
            throw ApiException.badRequest("Mã cấu hình không được để trống.");
        }
        if (newValue == null || newValue.isBlank()) {
            throw ApiException.badRequest("Giá trị cấu hình không được để trống.");
        }

        SystemSetting setting = settingRepository.findById(settingKey)
                .orElseThrow(() -> ApiException.badRequest("Không tìm thấy cấu hình với mã: " + settingKey));

        String oldValue = setting.getSettingValue();
        if (oldValue.equals(newValue.trim())) {
            return; // Không đổi
        }

        setting.setSettingValue(newValue.trim());
        setting.setUpdatedBy(me != null ? me.id() : null);
        setting.setUpdatedAt(Instant.now());
        settingRepository.save(setting);

        // Ghi nhận nhật ký kiểm toán
        SettingMeta meta = SETTING_METADATA.get(settingKey);
        String label = meta != null ? meta.label() : settingKey;
        logService.recordLog(
                me != null ? me.id() : null,
                "SETTING_UPDATE",
                "SETTING",
                null,
                "{\"" + settingKey + "\":\"" + oldValue + "\"}",
                "{\"" + settingKey + "\":\"" + newValue.trim() + "\"}",
                "Cập nhật cấu hình [" + label + "] từ \"" + oldValue + "\" thành \"" + newValue.trim() + "\""
        );
    }

    /**
     * Lấy danh sách từ khóa cấm, có hỗ trợ tìm kiếm theo chuỗi con.
     */
    @Transactional(readOnly = true)
    public List<BannedKeywordItem> getBannedKeywords(String search) {
        String sql = """
            SELECT bk.id, bk.keyword, u.display_name AS creator_name, bk.created_at
            FROM banned_keywords bk
            LEFT JOIN users u ON u.id = bk.created_by
        """;
        List<Object> params = new ArrayList<>();
        if (search != null && !search.isBlank()) {
            sql += " WHERE bk.keyword LIKE ? ";
            params.add("%" + search.trim().toLowerCase() + "%");
        }
        sql += " ORDER BY bk.created_at DESC ";

        return jdbc.query(sql, (rs, rowNum) -> new BannedKeywordItem(
                rs.getLong("id"),
                rs.getString("keyword"),
                rs.getString("creator_name") != null ? rs.getString("creator_name") : "Hệ thống",
                toInstant(rs.getTimestamp("created_at"))
        ), params.toArray());
    }

    /**
     * Thêm một từ khóa cấm mới vào danh mục kiểm duyệt.
     */
    @Transactional
    public BannedKeywordItem addBannedKeyword(String rawKeyword, AppUserPrincipal me) {
        if (rawKeyword == null || rawKeyword.trim().isBlank()) {
            throw ApiException.badRequest("Từ khóa không được để trống.");
        }
        String cleanKeyword = rawKeyword.trim().toLowerCase();
        if (cleanKeyword.length() > 100) {
            throw ApiException.badRequest("Từ khóa không được vượt quá 100 ký tự.");
        }

        if (keywordRepository.existsByKeyword(cleanKeyword)) {
            throw ApiException.badRequest("Từ khóa cấm này đã tồn tại trong danh mục.");
        }

        Long creatorId = me != null ? me.id() : null;
        BannedKeyword saved = keywordRepository.save(new BannedKeyword(cleanKeyword, creatorId));

        logService.recordLog(
                creatorId,
                "KEYWORD_ADD",
                "BANNED_KEYWORD",
                saved.getId(),
                null,
                "{\"keyword\":\"" + cleanKeyword + "\"}",
                "Thêm từ khóa cấm mới: \"" + cleanKeyword + "\""
        );

        String creatorName = me != null ? me.displayName() : "Hệ thống";
        return new BannedKeywordItem(saved.getId(), saved.getKeyword(), creatorName, saved.getCreatedAt());
    }

    /**
     * Xóa một từ khóa cấm khỏi danh mục kiểm duyệt.
     */
    @Transactional
    public void deleteBannedKeyword(Long id, AppUserPrincipal me) {
        if (id == null) {
            throw ApiException.badRequest("ID từ khóa không hợp lệ.");
        }

        BannedKeyword kw = keywordRepository.findById(id)
                .orElseThrow(() -> ApiException.badRequest("Không tìm thấy từ khóa cấm với ID: " + id));

        keywordRepository.delete(kw);

        logService.recordLog(
                me != null ? me.id() : null,
                "KEYWORD_DELETE",
                "BANNED_KEYWORD",
                id,
                "{\"keyword\":\"" + kw.getKeyword() + "\"}",
                null,
                "Xóa từ khóa cấm khỏi danh mục: \"" + kw.getKeyword() + "\""
        );
    }

    private static Instant toInstant(Timestamp ts) {
        return ts != null ? ts.toInstant() : null;
    }
}
