package com.sep490.vsa.feature.scholarship;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ScholarshipRepository extends JpaRepository<Scholarship, Long> {
    List<Scholarship> findByActiveTrueOrderByDeadlineAsc();
}
