package com.sep490.vsa.feature.guide;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface GuideRepository extends JpaRepository<Guide, Long> {
    List<Guide> findByPublishedTrue();

    List<Guide> findByPublishedTrueAndCategoryIgnoreCase(String category);
}
