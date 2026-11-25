package com.sep490.vsa.feature.place;

import com.sep490.vsa.common.exception.ApiException;
import com.sep490.vsa.common.settings.SystemSettings;
import com.sep490.vsa.feature.admin.ActivityLogService;
import com.sep490.vsa.feature.review.PinService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * "Duyệt ghim tự động" on the Moderator's overview (user decision 20/11/2026: the Moderators answer for the pins,
 * so they set the rule, not the Admin): on/off, the points a member needs, and how many of one member's pins a Japan day
 * go on the map at once. Read by {@link PinService}; the three {@code pin_auto_*} rows of {@code system_settings} (V23)
 * are also listed on the Admin's settings page. Every change is written to the activity log.
 */
@Service
public class PinSettingsService {

    public static final int MIN_POINTS_MAX = 100_000;
    public static final int PER_DAY_MAX = 50;

    /** The rule as the page shows it, with the ranks (to say who passes) and the pins still waiting. */
    public record PinSettings(boolean autoApprove, int minPoints, int perDay, List<RankRef> ranks, int waiting) {
    }

    public record RankRef(String name, int minPoints) {
    }

    public record PinSettingsForm(Boolean autoApprove, Integer minPoints, Integer perDay) {
    }

    private final JdbcTemplate jdbc;
    private final SystemSettings settings;
    private final ActivityLogService activityLog;
    private final Clock clock;

    public PinSettingsService(JdbcTemplate jdbc, SystemSettings settings, ActivityLogService activityLog, Clock clock) {
        this.jdbc = jdbc;
        this.settings = settings;
        this.activityLog = activityLog;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public PinSettings get() {
        List<RankRef> ranks = jdbc.query("SELECT name, min_points FROM ranks ORDER BY min_points",
                (rs, i) -> new RankRef(rs.getString(1), rs.getInt(2)));
        Integer waiting = jdbc.queryForObject(
                "SELECT COUNT(*) FROM places WHERE status = 'PENDING' AND source = 'USER'", Integer.class);
        return new PinSettings(settings.getInt(SystemSettings.PIN_AUTO_APPROVE, PinService.AUTO_DEFAULT_ON) == 1,
                settings.getInt(SystemSettings.PIN_AUTO_MIN_POINTS, PinService.AUTO_DEFAULT_MIN_POINTS),
                settings.getInt(SystemSettings.PIN_AUTO_PER_DAY, PinService.AUTO_DEFAULT_PER_DAY), ranks,
                waiting == null ? 0 : waiting);
    }

    /** Saves the three values together (all errors at once in {@code fields}); an unchanged value is not logged. */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public PinSettings update(long moderatorId, PinSettingsForm form) {
        Map<String, String> errors = new LinkedHashMap<>();
        if (form == null || form.autoApprove() == null) {
            errors.put("autoApprove", "Hãy chọn bật hoặc tắt tự động duyệt.");
        }
        if (form == null || form.minPoints() == null || form.minPoints() < 0 || form.minPoints() > MIN_POINTS_MAX) {
            errors.put("minPoints", "Điểm tối thiểu phải từ 0 đến " + MIN_POINTS_MAX + ".");
        }
        if (form == null || form.perDay() == null || form.perDay() < 1 || form.perDay() > PER_DAY_MAX) {
            errors.put("perDay", "Số ghim tự duyệt mỗi ngày phải từ 1 đến " + PER_DAY_MAX + ".");
        }
        if (!errors.isEmpty()) {
            throw ApiException.invalid(errors.values().iterator().next(), errors);
        }
        Map<String, String> wanted = new LinkedHashMap<>();
        wanted.put(SystemSettings.PIN_AUTO_APPROVE, form.autoApprove() ? "1" : "0");
        wanted.put(SystemSettings.PIN_AUTO_MIN_POINTS, String.valueOf(form.minPoints()));
        wanted.put(SystemSettings.PIN_AUTO_PER_DAY, String.valueOf(form.perDay()));
        Timestamp now = Timestamp.from(clock.instant());
        for (Map.Entry<String, String> e : wanted.entrySet()) {
            // the row is locked first, as the Admin's settings page does, so two saves run one after the other
            List<String> current = jdbc.query(
                    "SELECT setting_value FROM system_settings WHERE setting_key = ? FOR UPDATE",
                    (rs, i) -> rs.getString(1), e.getKey());
            String before = current.isEmpty() ? null : current.get(0);
            if (e.getValue().equals(before)) {
                continue;
            }
            jdbc.update("""
                    INSERT INTO system_settings (setting_key, setting_value, updated_by, updated_at)
                    VALUES (?, ?, ?, ?)
                    ON DUPLICATE KEY UPDATE setting_value = VALUES(setting_value),
                        updated_by = VALUES(updated_by), updated_at = VALUES(updated_at)""",
                    e.getKey(), e.getValue(), moderatorId, now);
            Map<String, String> oldValue = new LinkedHashMap<>();
            oldValue.put("value", before);
            activityLog.recordLog(moderatorId, "SETTING_UPDATE", "SETTING", null, oldValue,
                    Map.of("value", e.getValue()),
                    "Moderator đổi “" + e.getKey() + "”: " + before + " → " + e.getValue());
        }
        return get();
    }
}
