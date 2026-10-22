package com.sep490.vsa.feature.guide;

import com.sep490.vsa.common.dto.ApiResponse;
import com.sep490.vsa.common.exception.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * The guide ("Cẩm nang", four stages) is not built yet. {@link Guide}, {@link GuideRepository} and {@link GuideService}
 * are the starter code: they map to a table {@code guide} that the schema does not have (the guide tables are
 * {@code guide_stages} and {@code guide_items}), so every call failed with a server error. Until a teammate rewrites
 * them, the list answers 404 "coming soon" without touching the database.
 */
@RestController
@RequestMapping("/api/guides")
public class GuideController {

    static final String COMING_SOON = "Tính năng này sắp ra mắt.";

    @GetMapping
    public ApiResponse<List<GuideResponse>> list() {
        throw new ApiException(HttpStatus.NOT_FOUND, COMING_SOON);
    }
}
