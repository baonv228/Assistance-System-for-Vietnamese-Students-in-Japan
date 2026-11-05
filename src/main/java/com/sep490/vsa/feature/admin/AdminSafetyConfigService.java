package com.sep490.vsa.feature.admin;

import com.sep490.vsa.common.exception.ApiException;
import com.sep490.vsa.common.security.AppUserPrincipal;
import com.sep490.vsa.feature.admin.AdminSafetyDtos.SafetyGroup;
import com.sep490.vsa.feature.admin.AdminSafetyDtos.SafetyParam;
import com.sep490.vsa.feature.admin.AdminSafetyDtos.SafetySaveResult;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Pattern;

/**
 * Hai thẻ tham số của "Cấu hình và từ khóa": giới hạn cho tài khoản mới, báo cáo và minh chứng.
 * <p>
 * Khác với sửa từng tham số ({@link AdminConfigService#updateSetting}), các ô được gửi và lưu cùng lúc: mọi ô sai được
 * báo một lần, hoặc tất cả ô hợp lệ được lưu trong một giao dịch với một dòng nhật ký ghi giá trị cũ và mới của các
 * ô thật sự đổi. Khoảng cho phép và nhãn lấy từ {@link AdminConfigService}, không lặp lại ở đây.
 */
@Service
public class AdminSafetyConfigService {

    static final String INVALID_VALUE = "Hãy nhập giá trị hợp lệ cho cài đặt này.";
    /** Chỉ chữ số ASCII; 9 chữ số luôn nằm trong kiểu int. */
    private static final Pattern WHOLE_NUMBER = Pattern.compile("[0-9]{1,9}");

    /** Các nhóm theo thứ tự hiển thị: mã nhóm, tiêu đề, khóa các tham số trong nhóm. */
    private static final Map<String, Group> GROUPS = new LinkedHashMap<>();

    private record Group(String title, List<String> keys) {}

    static {
        GROUPS.put("NEW_ACCOUNT", new Group("Giới hạn cho tài khoản mới",
                List.of("new_account_days", "posts_per_day", "pins_per_day", "videos_per_day")));
        GROUPS.put("REPORTS", new Group("Báo cáo và minh chứng",
                List.of("report_hide_threshold", "false_report_max", "mute_days", "closed_report_threshold",
                        "proof_keep_days", "removed_keep_days")));
    }

    private final JdbcTemplate jdbc;
    private final ActivityLogService logService;

    public AdminSafetyConfigService(JdbcTemplate jdbc, ActivityLogService logService) {
        this.jdbc = jdbc;
        this.logService = logService;
    }

    /** Giá trị hiện tại của các tham số, chia theo nhóm. Đọc mỗi lần, không lưu tạm. */
    @Transactional(readOnly = true)
    public List<SafetyGroup> getGroups() {
        return buildGroups(readValues(false));
    }

    /**
     * Lưu các ô đã sửa. {@code input} chỉ chứa các ô người dùng sửa (khóa là mã tham số).
     * Quy tắc: khóa lạ hoặc tham số chưa áp dụng bị từ chối (400); mỗi giá trị phải là số nguyên ASCII trong khoảng của
     * tham số; mọi ô sai được báo cùng lúc trong {@code fields} và không ô nào được lưu. Ô bằng giá trị đang lưu bị
     * bỏ qua; nếu không còn ô nào thì không ghi gì.
     */
    @Transactional
    public SafetySaveResult save(Map<String, Object> input, AppUserPrincipal me) {
        Map<String, Integer> wanted = validate(input == null ? Map.of() : input);
        if (wanted.isEmpty()) {
            return new SafetySaveResult(false, getGroups());
        }

        // Khóa các dòng theo thứ tự khóa để hai Admin lưu cùng lúc chạy lần lượt và không bế tắc
        Map<String, Integer> current = readValues(true, wanted.keySet());
        Map<String, Object> oldValues = new LinkedHashMap<>();
        Map<String, Object> newValues = new LinkedHashMap<>();
        Long actorId = me != null ? me.id() : null;
        Timestamp now = Timestamp.from(Instant.now());
        for (Map.Entry<String, Integer> entry : new TreeMap<>(wanted).entrySet()) {
            Integer old = current.get(entry.getKey());
            if (old == null) {
                throw ApiException.badRequest("Không tìm thấy cấu hình với mã: " + entry.getKey());
            }
            if (old.equals(entry.getValue())) {
                continue;
            }
            jdbc.update("UPDATE system_settings SET setting_value = ?, updated_by = ?, updated_at = ?"
                    + " WHERE setting_key = ?", Integer.toString(entry.getValue()), actorId, now, entry.getKey());
            oldValues.put(entry.getKey(), old);
            newValues.put(entry.getKey(), entry.getValue());
        }
        if (newValues.isEmpty()) {
            return new SafetySaveResult(false, getGroups());
        }

        List<String> parts = new ArrayList<>();
        newValues.forEach((key, value) -> parts.add(AdminConfigService.labelOf(key) + ": "
                + oldValues.get(key) + " → " + value));
        logService.recordLog(actorId, "SETTING_UPDATE", "SETTING", null, oldValues, newValues,
                "Cập nhật cấu hình chống spam và báo cáo: " + String.join("; ", parts));
        return new SafetySaveResult(true, buildGroups(readValues(false)));
    }

    /** Kiểm tra từng ô; trả về khóa → giá trị số hoặc ném 400 kèm lỗi của mọi ô sai. */
    private Map<String, Integer> validate(Map<String, Object> input) {
        Map<String, Integer> valid = new LinkedHashMap<>();
        Map<String, String> errors = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : input.entrySet()) {
            String key = entry.getKey();
            if (!isSafetyKey(key)) {
                throw ApiException.badRequest("Cài đặt không hợp lệ: " + key);
            }
            int[] range = AdminConfigService.rangeOf(key);
            if (range == null) {
                throw ApiException.badRequest(AdminConfigService.NOT_APPLIED);
            }
            Integer value = parseWholeNumber(entry.getValue());
            if (value == null || value < range[0] || value > range[1]) {
                errors.put(key, INVALID_VALUE + " (số nguyên từ " + range[0] + " đến " + range[1] + ")");
            } else {
                valid.put(key, value);
            }
        }
        if (!errors.isEmpty()) {
            throw ApiException.invalid(INVALID_VALUE, errors);
        }
        return valid;
    }

    /** Số nguyên từ số JSON hoặc chuỗi chữ số ASCII ("007" → 7); null nếu rỗng, thập phân, âm hoặc không phải số. */
    private static Integer parseWholeNumber(Object raw) {
        if (raw instanceof Integer || raw instanceof Long) {
            long number = ((Number) raw).longValue();
            return number < 0 || number > Integer.MAX_VALUE ? null : (int) number;
        }
        if (raw instanceof String text && WHOLE_NUMBER.matcher(text.trim()).matches()) {
            return Integer.parseInt(text.trim());
        }
        return null;
    }

    private static boolean isSafetyKey(String key) {
        return GROUPS.values().stream().anyMatch(group -> group.keys().contains(key));
    }

    private Map<String, Integer> readValues(boolean lock) {
        return readValues(lock, GROUPS.values().stream().flatMap(g -> g.keys().stream()).toList());
    }

    /** Giá trị các khóa đã cho; {@code lock} = SELECT … FOR UPDATE, theo thứ tự khóa. */
    private Map<String, Integer> readValues(boolean lock, java.util.Collection<String> keys) {
        List<String> ordered = keys.stream().sorted().toList();
        String marks = String.join(",", java.util.Collections.nCopies(ordered.size(), "?"));
        Map<String, Integer> values = new LinkedHashMap<>();
        jdbc.query("SELECT setting_key, setting_value FROM system_settings WHERE setting_key IN (" + marks
                        + ") ORDER BY setting_key" + (lock ? " FOR UPDATE" : ""),
                rs -> {
                    try {
                        values.put(rs.getString(1), Integer.parseInt(rs.getString(2).strip()));
                    } catch (NumberFormatException ignored) {
                        // giá trị hỏng trong cơ sở dữ liệu: coi như thiếu
                    }
                }, ordered.toArray());
        return values;
    }

    private List<SafetyGroup> buildGroups(Map<String, Integer> values) {
        List<SafetyGroup> groups = new ArrayList<>();
        GROUPS.forEach((code, group) -> {
            List<SafetyParam> params = new ArrayList<>();
            for (String key : group.keys()) {
                int[] range = AdminConfigService.rangeOf(key);
                params.add(new SafetyParam(key, AdminConfigService.labelOf(key),
                        AdminConfigService.descriptionOf(key), values.getOrDefault(key, 0), range != null,
                        range == null ? null : range[0], range == null ? null : range[1]));
            }
            groups.add(new SafetyGroup(code, group.title(), params));
        });
        return groups;
    }
}
