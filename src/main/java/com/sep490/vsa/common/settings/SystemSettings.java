package com.sep490.vsa.common.settings;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Reads the Admin's safety settings from {@code system_settings} (report_hide_threshold, mute_days…).
 * Values are read on every call, so a change in Safety Configuration applies to the next action at once.
 */
@Component
public class SystemSettings {

    public static final String REPORT_HIDE_THRESHOLD = "report_hide_threshold";
    public static final String FALSE_REPORT_MAX = "false_report_max";
    public static final String MUTE_DAYS = "mute_days";

    private final JdbcTemplate jdbc;

    public SystemSettings(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** The setting as a number, or {@code fallback} when it is missing or not a number. */
    public int getInt(String key, int fallback) {
        List<String> values = jdbc.query("SELECT setting_value FROM system_settings WHERE setting_key = ?",
                (rs, i) -> rs.getString(1), key);
        if (values.isEmpty()) {
            return fallback;
        }
        try {
            return Integer.parseInt(values.get(0).strip());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
