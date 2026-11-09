package com.sep490.vsa.feature.home;

import com.sep490.vsa.common.dto.ApiResponse;
import com.sep490.vsa.common.security.AppUserPrincipal;
import com.sep490.vsa.feature.home.HomeDtos.InterestSettings;
import com.sep490.vsa.feature.home.HomeDtos.UpdateInterestsRequest;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * "Chủ đề quan tâm" a Member chooses for "Dành cho bạn" (Tôi, and "Điều chỉnh" on the Home). /api/me/** requires
 * login (see SecurityConfig); Members only (MSG18 for staff).
 */
@RestController
public class InterestController {

    private final InterestService service;

    public InterestController(InterestService service) {
        this.service = service;
    }

    @GetMapping("/api/me/interests")
    public ApiResponse<InterestSettings> interests(@AuthenticationPrincipal AppUserPrincipal me) {
        return ApiResponse.ok("OK", service.settings(me.id()));
    }

    /** The whole new choice: {@code topicIds} (empty clears it), at most {@link InterestService#CHOSEN_MAX}. */
    @PutMapping("/api/me/interests")
    public ApiResponse<InterestSettings> update(@AuthenticationPrincipal AppUserPrincipal me,
                                                @RequestBody UpdateInterestsRequest body) {
        return ApiResponse.ok("Đã lưu chủ đề quan tâm.", service.update(me.id(), body.topicIds()));
    }
}
