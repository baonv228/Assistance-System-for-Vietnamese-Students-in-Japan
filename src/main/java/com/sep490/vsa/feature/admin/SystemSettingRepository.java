package com.sep490.vsa.feature.admin;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * Kho lưu trữ dữ liệu cấu hình hệ thống (system_settings).
 */
@Repository
public interface SystemSettingRepository extends JpaRepository<SystemSetting, String> {
}
