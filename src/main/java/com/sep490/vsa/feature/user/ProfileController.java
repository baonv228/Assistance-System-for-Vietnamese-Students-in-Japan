package com.sep490.vsa.feature.user;

import com.sep490.vsa.common.dto.ApiResponse;
import com.sep490.vsa.common.security.AppUserPrincipal;
import com.sep490.vsa.feature.user.ProfileDtos.AreaResponse;
import com.sep490.vsa.feature.user.ProfileDtos.ProfileResponse;
import com.sep490.vsa.feature.user.ProfileDtos.UpdateProfileRequest;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** The signed-in user's own page ("Tôi"). /api/me/** requires login (see SecurityConfig). */
@RestController
public class ProfileController {

    private final ProfileService profileService;

    public ProfileController(ProfileService profileService) {
        this.profileService = profileService;
    }

    @GetMapping("/api/me/profile")
    public ApiResponse<ProfileResponse> profile(@AuthenticationPrincipal AppUserPrincipal me) {
        return ApiResponse.ok("OK", profileService.get(me.id()));
    }

    /** Area and stage are optional; send null to clear them. */
    @PutMapping("/api/me/profile")
    public ApiResponse<ProfileResponse> update(@AuthenticationPrincipal AppUserPrincipal me,
                                               @Valid @RequestBody UpdateProfileRequest body) {
        return ApiResponse.ok("Đã lưu khu vực và giai đoạn.", profileService.update(me.id(), body.areaId(), body.stage()));
    }

    @DeleteMapping("/api/me")
    public ApiResponse<Void> deleteAccount(@AuthenticationPrincipal AppUserPrincipal me, HttpServletRequest request) {
        profileService.deleteAccount(me.id());
        HttpSession session = request.getSession(false);
        if (session != null) {
            session.invalidate();
        }
        SecurityContextHolder.clearContext();
        return ApiResponse.ok("Đã xóa tài khoản.", null);
    }

    @GetMapping("/api/areas")
    public ApiResponse<List<AreaResponse>> areas() {
        return ApiResponse.ok("OK", profileService.areas());
    }
}
