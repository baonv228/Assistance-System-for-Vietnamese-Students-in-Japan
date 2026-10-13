package com.sep490.vsa.feature.scholarship;

import java.time.LocalDate;

public record ScholarshipResponse(
        Long id,
        String title,
        String description,
        String provider,
        LocalDate deadline
) {
    static ScholarshipResponse from(Scholarship scholarship) {
        return new ScholarshipResponse(
                scholarship.getId(),
                scholarship.getTitle(),
                scholarship.getDescription(),
                scholarship.getProvider(),
                scholarship.getDeadline()
        );
    }
}
