package com.sep490.vsa.feature.admin;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * Kho lưu trữ dữ liệu nhật ký kiểm toán hệ thống (activity_logs).
 */
@Repository
public interface ActivityLogRepository extends JpaRepository<ActivityLog, Long> {
}
