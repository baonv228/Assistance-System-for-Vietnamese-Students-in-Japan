package com.sep490.vsa.feature.guide;

import com.sep490.vsa.common.dto.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/guides")
public class GuideController {

    private final GuideService guideService;

    public GuideController(GuideService guideService) {
        this.guideService = guideService;
    }

    @GetMapping
    public ApiResponse<List<GuideResponse>> list(@RequestParam(required = false) String category) {
        return ApiResponse.ok("Guides loaded", guideService.listPublished(category));
    }
}
