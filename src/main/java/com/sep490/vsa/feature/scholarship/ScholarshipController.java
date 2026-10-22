package com.sep490.vsa.feature.scholarship;

import com.sep490.vsa.common.dto.ApiResponse;
import com.sep490.vsa.common.exception.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Scholarships are not part of the agreed scope. {@link Scholarship}, {@link ScholarshipRepository} and
 * {@link ScholarshipService} are the starter code: they map to a table {@code scholarship} that the schema does not
 * have, so every call failed with a server error. The list answers 404 "coming soon" without touching the database.
 */
@RestController
@RequestMapping("/api/scholarships")
public class ScholarshipController {

    static final String COMING_SOON = "Tính năng này sắp ra mắt.";

    @GetMapping
    public ApiResponse<List<ScholarshipResponse>> list() {
        throw new ApiException(HttpStatus.NOT_FOUND, COMING_SOON);
    }
}
