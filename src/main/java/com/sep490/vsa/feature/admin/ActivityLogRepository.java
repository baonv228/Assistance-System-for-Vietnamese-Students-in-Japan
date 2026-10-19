package com.sep490.vsa.feature.admin;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * Kho lưu trữ dữ liệu nhật ký kiểm toán hệ thống (activity_logs).
 */
@Repository
public interface ActivityLogRepository extends JpaRepository<ActivityLog, Long> {

    Page<ActivityLog> findAllByOrderByCreatedAtDesc(Pageable pageable);

    Page<ActivityLog> findByActionOrderByCreatedAtDesc(String action, Pageable pageable);

    List<ActivityLog> findTop10ByOrderByCreatedAtDesc();
}
