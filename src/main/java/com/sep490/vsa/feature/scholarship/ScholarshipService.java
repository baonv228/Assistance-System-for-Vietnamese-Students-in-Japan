package com.sep490.vsa.feature.scholarship;

import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class ScholarshipService {

    private final ScholarshipRepository scholarshipRepository;

    public ScholarshipService(ScholarshipRepository scholarshipRepository) {
        this.scholarshipRepository = scholarshipRepository;
    }

    public List<ScholarshipResponse> listActive() {
        return scholarshipRepository.findByActiveTrueOrderByDeadlineAsc()
                .stream()
                .map(ScholarshipResponse::from)
                .toList();
    }
}
