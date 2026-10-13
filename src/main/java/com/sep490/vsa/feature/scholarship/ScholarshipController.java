package com.sep490.vsa.feature.scholarship;

import com.sep490.vsa.common.dto.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/scholarships")
public class ScholarshipController {

    private final ScholarshipService scholarshipService;

    public ScholarshipController(ScholarshipService scholarshipService) {
        this.scholarshipService = scholarshipService;
    }

    @GetMapping
    public ApiResponse<List<ScholarshipResponse>> list() {
        return ApiResponse.ok("Scholarships loaded", scholarshipService.listActive());
    }
}
