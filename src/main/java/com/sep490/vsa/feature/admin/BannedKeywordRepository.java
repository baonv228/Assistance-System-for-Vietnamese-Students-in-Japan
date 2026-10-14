package com.sep490.vsa.feature.admin;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * Kho lưu trữ dữ liệu từ khóa cấm (banned_keywords).
 */
@Repository
public interface BannedKeywordRepository extends JpaRepository<BannedKeyword, Long> {

    boolean existsByKeyword(String keyword);

    Optional<BannedKeyword> findByKeyword(String keyword);

    List<BannedKeyword> findByKeywordContainingIgnoreCaseOrderByCreatedAtDesc(String keyword);

    List<BannedKeyword> findAllByOrderByCreatedAtDesc();
}
