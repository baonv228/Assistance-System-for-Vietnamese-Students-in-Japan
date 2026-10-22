package com.sep490.vsa.feature.admin;

import com.sep490.vsa.common.exception.ApiException;
import com.sep490.vsa.common.security.AppUserPrincipal;
import com.sep490.vsa.common.text.VietnameseText;
import com.sep490.vsa.feature.admin.AdminDtos.BannedKeywordItem;
import com.sep490.vsa.feature.admin.AdminDtos.SystemSettingItem;
import com.sep490.vsa.feature.moderation.ContentFilter;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Dịch vụ cấu hình hệ thống và quản lý danh mục từ khóa cấm.
 * Hỗ trợ quản trị viên điều chỉnh các tham số vận hành (ngưỡng báo cáo, giới hạn đăng tin, thời gian khóa)
 * và quản lý danh sách từ khóa đen để bộ lọc tự động kiểm duyệt nội dung.
 * <p>
 * Chỉ các cấu hình đã áp dụng (hệ thống thật sự đọc chúng) mới sửa được, và giá trị phải là số nguyên trong khoảng cho
 * phép của từng cấu hình (bảng {@link #SETTING_METADATA}). Các cấu hình khác được giữ cho chức năng làm sau: trang chỉ
 * hiện chúng với nhãn "Chưa áp dụng".
 */
@Service
public class AdminConfigService {

    static final String NOT_APPLIED = "Cài đặt này chưa được áp dụng trong hệ thống.";
    /** Từ khóa cấm phải có ít nhất chừng này chữ cái/chữ số (sau khi bỏ dấu, khoảng trắng, ký hiệu). Cần chốt. */
    static final int KEYWORD_MIN_LETTERS = 3;
    /** Từ khóa có chữ Hán, Hiragana hoặc Katakana: 2 chữ đã là một từ (詐欺 "lừa đảo"). Cần chốt. */
    static final int KEYWORD_MIN_CJK = 2;
    /** Các chữ viết liền không cách giữa các từ (như UNSPACED_SCRIPTS của ContentFilter, phần tiếng Nhật/Trung). */
    private static final Set<Character.UnicodeScript> CJK_SCRIPTS = EnumSet.of(Character.UnicodeScript.HAN,
            Character.UnicodeScript.HIRAGANA, Character.UnicodeScript.KATAKANA);
    static final int KEYWORD_MAX_LENGTH = 100;
    /** Chỉ chữ số ASCII; 9 chữ số luôn nằm trong kiểu int. */
    private static final Pattern WHOLE_NUMBER = Pattern.compile("[0-9]{1,9}");

    private final SystemSettingRepository settingRepository;
    private final BannedKeywordRepository keywordRepository;
    private final ActivityLogService logService;
    private final JdbcTemplate jdbc;
    /** Bộ lọc nội dung giữ danh sách từ khóa cấm trong bộ nhớ: báo cho nó khi danh sách đổi. */
    private final ContentFilter contentFilter;

    /** Bảng mô tả thông tin tham số cấu hình hiển thị tiếng Việt */
    private static final Map<String, SettingMeta> SETTING_METADATA = new LinkedHashMap<>();

    /**
     * Mô tả một tham số cấu hình. {@code wired}: hệ thống đang đọc tham số này, nên Admin sửa được và giá trị phải là
     * số nguyên từ {@code min} đến {@code max}. Tham số chưa áp dụng chỉ được hiển thị, không sửa được.
     */
    private record SettingMeta(String label, String group, String description, boolean wired, int min, int max) {

        static SettingMeta applied(String label, String group, String description, int min, int max) {
            return new SettingMeta(label, group, description, true, min, max);
        }

        static SettingMeta notApplied(String label, String group, String description) {
            return new SettingMeta(label, group, description, false, 0, 0);
        }
    }

    // Khoảng giá trị cho phép của các tham số đã áp dụng: cần chốt.
    static {
        // Nhóm: Báo cáo & Kiểm duyệt
        SETTING_METADATA.put("report_hide_threshold", SettingMeta.applied(
                "Ngưỡng ẩn nội dung", "Báo cáo & Kiểm duyệt",
                "Khi số báo cáo vượt quá ngưỡng này, bài viết/bình luận bị tự động ẩn và chuyển mức Khẩn chờ kiểm duyệt",
                1, 100));
        SETTING_METADATA.put("false_report_max", SettingMeta.notApplied(
                "Số lần báo cáo sai tối đa", "Báo cáo & Kiểm duyệt",
                "Số lần báo cáo sai sự thật tối đa trước khi người dùng bị tạm khóa quyền gửi báo cáo"));
        SETTING_METADATA.put("mute_days", SettingMeta.notApplied(
                "Số ngày tạm cấm báo cáo", "Báo cáo & Kiểm duyệt",
                "Thời gian tài khoản bị cấm gửi báo cáo sau khi vượt quá số lần báo cáo sai quy định"));
        SETTING_METADATA.put("closed_report_threshold", SettingMeta.notApplied(
                "Ngưỡng xác nhận đóng cửa", "Báo cáo & Kiểm duyệt",
                "Số lượng người dùng báo cáo một địa điểm đã đóng cửa để hệ thống chuyển trạng thái"));

        // Nhóm: Giới hạn hoạt động người dùng
        SETTING_METADATA.put("new_account_days", SettingMeta.applied(
                "Thời gian tài khoản mới (ngày)", "Giới hạn hoạt động",
                "Khoảng thời gian kể từ khi đăng ký mà tài khoản chịu các giới hạn đăng tin nghiêm ngặt hơn"
                        + " (0 = không áp giới hạn tài khoản mới)",
                0, 365));
        SETTING_METADATA.put("posts_per_day", SettingMeta.applied(
                "Số bài viết tối đa/ngày", "Giới hạn hoạt động",
                "Giới hạn số bài viết và câu hỏi một tài khoản mới có thể đăng trong một ngày",
                1, 50));
        SETTING_METADATA.put("pins_per_day", SettingMeta.applied(
                "Số ghim địa điểm tối đa/ngày", "Giới hạn hoạt động",
                "Giới hạn số lượng yêu cầu tạo địa điểm mới một tài khoản mới có thể gửi trong một ngày",
                1, 50));
        SETTING_METADATA.put("videos_per_day", SettingMeta.applied(
                "Số video tải lên tối đa/ngày", "Giới hạn hoạt động",
                "Giới hạn số video một tài khoản mới có thể đăng trong một ngày (0 = không cho đăng video)",
                0, 10));

        // Nhóm: Quản trị & Bảo mật
        SETTING_METADATA.put("max_moderators", SettingMeta.applied(
                "Số lượng Moderator tối đa", "Quản trị & Bảo mật",
                "Giới hạn số lượng tài khoản Moderator hoạt động cùng một thời điểm trong hệ thống",
                1, 3));
        SETTING_METADATA.put("password_min_length", SettingMeta.applied(
                "Độ dài mật khẩu tối thiểu", "Quản trị & Bảo mật",
                "Số ký tự tối thiểu của mật khẩu khi người dùng đăng ký bằng email",
                8, 72));
        SETTING_METADATA.put("lock_attempts", SettingMeta.applied(
                "Số lần đăng nhập sai tối đa", "Quản trị & Bảo mật",
                "Số lần nhập sai mật khẩu liên tiếp trước khi tài khoản bị tạm khóa bảo vệ",
                3, 20));
        SETTING_METADATA.put("lock_minutes", SettingMeta.applied(
                "Thời gian tạm khóa (phút)", "Quản trị & Bảo mật",
                "Thời gian tài khoản bị tạm khóa sau khi vượt quá số lần đăng nhập sai",
                1, 1440));
        SETTING_METADATA.put("email_code_minutes", SettingMeta.applied(
                "Thời hạn mã xác thực email (phút)", "Quản trị & Bảo mật",
                "Thời gian mã 6 số gửi qua email khi đăng ký còn dùng được",
                1, 60));
        SETTING_METADATA.put("email_code_attempts", SettingMeta.applied(
                "Số lần nhập sai mã xác thực", "Quản trị & Bảo mật",
                "Số lần nhập sai mã xác thực email trước khi người dùng phải bấm Gửi lại mã",
                3, 20));
        SETTING_METADATA.put("email_code_resend_seconds", SettingMeta.applied(
                "Thời gian chờ gửi lại mã (giây)", "Quản trị & Bảo mật",
                "Khoảng chờ tối thiểu giữa hai lần gửi mã xác thực đến cùng một hộp thư",
                0, 600));
        SETTING_METADATA.put("register_ticket_minutes", SettingMeta.applied(
                "Thời hạn hoàn tất đăng ký (phút)", "Quản trị & Bảo mật",
                "Thời gian người dùng có để đặt tên và mật khẩu sau khi đã xác thực email",
                5, 1440));
        SETTING_METADATA.put("verify_token_hours", SettingMeta.notApplied(
                "Thời hạn link xác thực email (giờ)", "Quản trị & Bảo mật",
                "Thời gian hết hạn của liên kết xác thực tài khoản qua email"));
        SETTING_METADATA.put("reset_token_minutes", SettingMeta.notApplied(
                "Thời hạn link đổi mật khẩu (phút)", "Quản trị & Bảo mật",
                "Thời gian hết hạn của liên kết đặt lại mật khẩu gửi qua email"));
        SETTING_METADATA.put("log_keep_days", SettingMeta.applied(
                "Lưu trữ nhật ký (ngày)", "Quản trị & Bảo mật",
                "Nhật ký hoạt động cũ hơn số ngày này được tự động xóa lúc 3:30 mỗi ngày (giờ Nhật)",
                30, 3650));
        SETTING_METADATA.put("proof_keep_days", SettingMeta.notApplied(
                "Lưu minh chứng tag (ngày)", "Quản trị & Bảo mật",
                "Thời gian lưu trữ file ảnh minh chứng cấp thẻ định danh (thẻ SV, JLPT...)"));
        SETTING_METADATA.put("removed_keep_days", SettingMeta.notApplied(
                "Lưu dữ liệu đã xóa (ngày)", "Quản trị & Bảo mật",
                "Thời gian lưu giữ dữ liệu bị xóa mềm trước khi loại bỏ vĩnh viễn"));

        // Nhóm: Đề xuất & Bản đồ
        SETTING_METADATA.put("featured_top_n", SettingMeta.applied(
                "Số bài viết nổi bật", "Đề xuất & Bản đồ",
                "Số bài nổi bật của tuần và của tháng hiển thị ở Blog và trang chủ",
                1, 3));
        SETTING_METADATA.put("featured_min_score", SettingMeta.applied(
                "Điểm tối thiểu bài nổi bật", "Đề xuất & Bản đồ",
                "Điểm tương tác tối thiểu để bài viết đủ điều kiện xét nổi bật",
                0, 1000));
        SETTING_METADATA.put("duplicate_warn_radius_m", SettingMeta.notApplied(
                "Bán kính cảnh báo trùng (mét)", "Đề xuất & Bản đồ",
                "Khoảng cách tối thiểu giữa 2 địa điểm để phát hiện trùng lặp tiềm năng"));
        SETTING_METADATA.put("merge_suggest_radius_m", SettingMeta.notApplied(
                "Bán kính đề xuất gộp (mét)", "Đề xuất & Bản đồ",
                "Khoảng cách gợi ý moderator gộp hai địa điểm giống nhau"));
    }

    public AdminConfigService(SystemSettingRepository settingRepository,
                              BannedKeywordRepository keywordRepository,
                              ActivityLogService logService,
                              JdbcTemplate jdbc,
                              ContentFilter contentFilter) {
        this.settingRepository = settingRepository;
        this.keywordRepository = keywordRepository;
        this.logService = logService;
        this.jdbc = jdbc;
        this.contentFilter = contentFilter;
    }

    /**
     * Giá trị một tham số đã áp dụng, giữ trong khoảng cho phép của nó (cho nơi đọc tham số trong feature admin, phòng
     * khi giá trị trong cơ sở dữ liệu bị sửa ngoài trang quản trị). Tham số không có trong bảng: giữ nguyên.
     */
    static int clampToRange(String settingKey, int value) {
        SettingMeta meta = SETTING_METADATA.get(settingKey);
        if (meta == null || !meta.wired()) {
            return value;
        }
        return Math.max(meta.min(), Math.min(meta.max(), value));
    }

    /**
     * Lấy toàn bộ danh sách cấu hình hệ thống kèm thông tin nhóm, mô tả chi tiết và trạng thái áp dụng.
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
            boolean wired = meta != null && meta.wired();

            results.add(new SystemSettingItem(
                    key,
                    setting.getSettingValue(),
                    label,
                    group,
                    description,
                    setting.getUpdatedAt(),
                    wired,
                    wired ? meta.min() : null,
                    wired ? meta.max() : null
            ));
        }
        return results;
    }

    /**
     * Cập nhật một tham số cấu hình hệ thống và ghi nhận kiểm toán.
     * Dòng cấu hình được khóa trước mọi thao tác khác (cùng thứ tự khóa với {@link AdminUserService}: system_settings
     * trước users), nên hai lần sửa cùng lúc chạy lần lượt.
     */
    @Transactional
    public void updateSetting(String settingKey, String newValue, AppUserPrincipal me) {
        if (settingKey == null || settingKey.isBlank()) {
            throw ApiException.badRequest("Mã cấu hình không được để trống.");
        }

        List<String> current = jdbc.query(
                "SELECT setting_value FROM system_settings WHERE setting_key = ? FOR UPDATE",
                (rs, rowNum) -> rs.getString(1), settingKey);
        if (current.isEmpty()) {
            throw ApiException.badRequest("Không tìm thấy cấu hình với mã: " + settingKey);
        }
        SettingMeta meta = SETTING_METADATA.get(settingKey);
        if (meta == null || !meta.wired()) {
            throw ApiException.badRequest(NOT_APPLIED);
        }
        String value = checkedValue(newValue, meta);

        String oldValue = current.get(0);
        if (oldValue.equals(value)) {
            return; // Không đổi
        }

        Long actorId = me != null ? me.id() : null;
        jdbc.update("UPDATE system_settings SET setting_value = ?, updated_by = ?, updated_at = ? WHERE setting_key = ?",
                value, actorId, Timestamp.from(Instant.now()), settingKey);

        // Ghi nhận nhật ký kiểm toán
        logService.recordLog(
                actorId,
                "SETTING_UPDATE",
                "SETTING",
                null,
                Map.of(settingKey, oldValue),
                Map.of(settingKey, value),
                "Cập nhật cấu hình [" + meta.label() + "] từ \"" + oldValue + "\" thành \"" + value + "\""
        );
    }

    /**
     * Giá trị mới đã kiểm tra, ở dạng chuẩn ("007" → "7"): bỏ khoảng trắng hai đầu, chỉ chữ số ASCII (không "+", không
     * chữ số toàn chiều rộng), trong khoảng cho phép; sai thì 400 nêu khoảng đó.
     */
    private static String checkedValue(String raw, SettingMeta meta) {
        String text = raw == null ? "" : raw.trim();
        if (text.isEmpty()) {
            throw ApiException.badRequest("Giá trị cấu hình không được để trống.");
        }
        String range = "Giá trị của “" + meta.label() + "” phải là số nguyên từ " + meta.min() + " đến "
                + meta.max() + ".";
        if (!WHOLE_NUMBER.matcher(text).matches()) {
            throw ApiException.badRequest(range);
        }
        int value = Integer.parseInt(text);
        if (value < meta.min() || value > meta.max()) {
            throw ApiException.badRequest(range);
        }
        return Integer.toString(value);
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
            params.add("%" + AdminUserService.escapeLike(search.trim().toLowerCase(Locale.ROOT)) + "%");
        }
        sql += " ORDER BY bk.created_at DESC, bk.id DESC ";

        return jdbc.query(sql, (rs, rowNum) -> new BannedKeywordItem(
                rs.getLong("id"),
                rs.getString("keyword"),
                rs.getString("creator_name") != null ? rs.getString("creator_name") : "Hệ thống",
                toInstant(rs.getTimestamp("created_at"))
        ), params.toArray());
    }

    /**
     * Thêm một từ khóa cấm mới vào danh mục kiểm duyệt.
     * Từ khóa phải có ít nhất {@value #KEYWORD_MIN_LETTERS} chữ cái/chữ số, hoặc {@value #KEYWORD_MIN_CJK} chữ khi có
     * chữ Hán/Hiragana/Katakana (một chữ "a" sẽ chặn gần hết bài viết, còn "!!!" không bao giờ khớp). Từ khóa không
     * được trùng một từ khóa đã có khi viết thường và bỏ khoảng trắng, ký hiệu nhưng giữ dấu ("vay-nóng" trùng
     * "vay nóng"; "vậy nóng" và "vay nong" là từ khác), như bộ lọc phân biệt chúng.
     * Riêng cột keyword là UNIQUE theo collation utf8mb4_0900_ai_ci (không phân biệt dấu): MySQL vẫn không lưu được
     * "vậy nóng" khi đã có "vay nóng", nên trường hợp đó cũng được báo là trùng.
     */
    @Transactional
    public BannedKeywordItem addBannedKeyword(String rawKeyword, AppUserPrincipal me) {
        if (rawKeyword == null || rawKeyword.isBlank()) {
            throw ApiException.badRequest("Từ khóa không được để trống.");
        }
        String cleanKeyword = rawKeyword.trim().toLowerCase(Locale.ROOT);
        if (cleanKeyword.length() > KEYWORD_MAX_LENGTH) {
            throw ApiException.badRequest("Từ khóa không được vượt quá " + KEYWORD_MAX_LENGTH + " ký tự.");
        }
        String squashed = VietnameseText.squash(cleanKeyword);
        if (squashed.isEmpty()) {
            throw ApiException.badRequest("Từ khóa phải có chữ hoặc số.");
        }
        int letters = squashed.codePointCount(0, squashed.length());
        if (squashed.codePoints().anyMatch(cp -> CJK_SCRIPTS.contains(Character.UnicodeScript.of(cp)))) {
            if (letters < KEYWORD_MIN_CJK) {
                throw ApiException.badRequest(
                        "Từ khóa tiếng Nhật hoặc tiếng Trung phải có ít nhất " + KEYWORD_MIN_CJK + " chữ.");
            }
        } else if (letters < KEYWORD_MIN_LETTERS) {
            throw ApiException.badRequest("Từ khóa phải có ít nhất " + KEYWORD_MIN_LETTERS + " chữ cái hoặc chữ số.");
        }
        String sameWords = VietnameseText.squashKeepingMarks(cleanKeyword);
        for (String existing : jdbc.queryForList("SELECT keyword FROM banned_keywords ORDER BY id", String.class)) {
            if (VietnameseText.squashKeepingMarks(existing).equals(sameWords)) {
                throw duplicateKeyword(existing);
            }
        }
        // Cột keyword là UNIQUE theo collation của MySQL (không phân biệt dấu, chữ toàn chiều rộng…): báo trước,
        // nêu đúng từ khóa đã có, thay vì để câu INSERT bị từ chối
        List<String> sameInDatabase = jdbc.queryForList(
                "SELECT keyword FROM banned_keywords WHERE keyword = ? LIMIT 1", String.class, cleanKeyword);
        if (!sameInDatabase.isEmpty()) {
            throw duplicateKeyword(sameInDatabase.get(0));
        }

        Long creatorId = me != null ? me.id() : null;
        BannedKeyword saved;
        try {
            saved = keywordRepository.saveAndFlush(new BannedKeyword(cleanKeyword, creatorId));
        } catch (DataIntegrityViolationException e) {
            // Một Admin khác vừa thêm đúng từ này
            throw duplicateKeyword(cleanKeyword);
        }
        contentFilter.keywordsChanged();   // bộ lọc đọc lại danh sách ngay ở bài đăng sau

        logService.recordLog(
                creatorId,
                "KEYWORD_ADD",
                "BANNED_KEYWORD",
                saved.getId(),
                null,
                Map.of("keyword", cleanKeyword),
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

        // Một Admin khác (hoặc tab khác) có thể vừa xóa đúng từ này: khi đó không còn gì để xóa, báo như từ không có
        if (jdbc.update("DELETE FROM banned_keywords WHERE id = ?", id) == 0) {
            throw ApiException.badRequest("Không tìm thấy từ khóa cấm với ID: " + id);
        }
        contentFilter.keywordsChanged();

        logService.recordLog(
                me != null ? me.id() : null,
                "KEYWORD_DELETE",
                "BANNED_KEYWORD",
                id,
                Map.of("keyword", kw.getKeyword()),
                null,
                "Xóa từ khóa cấm khỏi danh mục: \"" + kw.getKeyword() + "\""
        );
    }

    private static ApiException duplicateKeyword(String existing) {
        return ApiException.badRequest("Từ khóa này trùng với “" + existing + "” đã có.");
    }

    private static Instant toInstant(Timestamp ts) {
        return ts != null ? ts.toInstant() : null;
    }
}
