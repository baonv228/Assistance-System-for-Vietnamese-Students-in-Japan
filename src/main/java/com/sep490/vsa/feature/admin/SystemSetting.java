package com.sep490.vsa.feature.admin;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * Thực thể ánh xạ bảng system_settings trong cơ sở dữ liệu.
 * Dùng để lưu trữ các tham số cấu hình vận hành của toàn bộ hệ thống.
 */
@Entity
@Table(name = "system_settings")
public class SystemSetting {

    /** Khóa định danh cấu hình (ví dụ: max_moderators, report_hide_threshold...) */
    @Id
    @Column(name = "setting_key", length = 50, nullable = false)
    private String settingKey;

    /** Giá trị của cấu hình */
    @Column(name = "setting_value", length = 255, nullable = false)
    private String settingValue;

    /** ID của người dùng (Admin) thực hiện cập nhật gần nhất */
    @Column(name = "updated_by")
    private Long updatedBy;

    /** Thời gian cập nhật gần nhất */
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    public SystemSetting() {
    }

    public SystemSetting(String settingKey, String settingValue, Long updatedBy) {
        this.settingKey = settingKey;
        this.settingValue = settingValue;
        this.updatedBy = updatedBy;
        this.updatedAt = Instant.now();
    }

    public String getSettingKey() {
        return settingKey;
    }

    public void setSettingKey(String settingKey) {
        this.settingKey = settingKey;
    }

    public String getSettingValue() {
        return settingValue;
    }

    public void setSettingValue(String settingValue) {
        this.settingValue = settingValue;
    }

    public Long getUpdatedBy() {
        return updatedBy;
    }

    public void setUpdatedBy(Long updatedBy) {
        this.updatedBy = updatedBy;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }
}
