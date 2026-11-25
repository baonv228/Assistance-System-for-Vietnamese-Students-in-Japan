package com.sep490.vsa.feature.admin;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * Kho lưu trữ dữ liệu từ khóa cấm (banned_keywords).
 */
@Repository
public interface BannedKeywordRepository extends JpaRepository<BannedKeyword, Long> {
}
