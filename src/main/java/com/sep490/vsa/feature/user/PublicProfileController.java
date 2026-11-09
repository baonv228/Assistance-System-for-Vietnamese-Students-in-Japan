package com.sep490.vsa.feature.user;

import com.sep490.vsa.common.dto.ApiResponse;
import com.sep490.vsa.common.security.AppUserPrincipal;
import com.sep490.vsa.feature.user.PublicProfileDtos.BlogLine;
import com.sep490.vsa.feature.user.PublicProfileDtos.Page;
import com.sep490.vsa.feature.user.PublicProfileDtos.PublicProfile;
import com.sep490.vsa.feature.user.PublicProfileDtos.ReviewLine;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Public profile (UC-U07, page /users/{id}). Public GETs, like the posts and reviews they list. */
@RestController
public class PublicProfileController {

    private final PublicProfileService service;

    public PublicProfileController(PublicProfileService service) {
        this.service = service;
    }

    @GetMapping("/api/users/{id}")
    public ApiResponse<PublicProfile> profile(@PathVariable long id, @AuthenticationPrincipal AppUserPrincipal me) {
        return ApiResponse.ok("OK", service.profile(id, me));
    }

    @GetMapping("/api/users/{id}/reviews")
    public ApiResponse<Page<ReviewLine>> reviews(@PathVariable long id, @RequestParam(defaultValue = "0") int page) {
        return ApiResponse.ok("OK", service.reviews(id, page));
    }

    @GetMapping("/api/users/{id}/posts")
    public ApiResponse<Page<BlogLine>> posts(@PathVariable long id, @RequestParam(defaultValue = "0") int page) {
        return ApiResponse.ok("OK", service.blogs(id, "POST", page));
    }

    @GetMapping("/api/users/{id}/questions")
    public ApiResponse<Page<BlogLine>> questions(@PathVariable long id, @RequestParam(defaultValue = "0") int page) {
        return ApiResponse.ok("OK", service.blogs(id, "QA", page));
    }
}
