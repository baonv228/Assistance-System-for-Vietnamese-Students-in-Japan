package com.sep490.vsa.feature.home;

import com.sep490.vsa.common.dto.ApiResponse;
import com.sep490.vsa.common.security.AppUserPrincipal;
import com.sep490.vsa.feature.home.HomeDtos.GuestHome;
import com.sep490.vsa.feature.home.HomeDtos.MemberHome;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class HomeController {

    private final HomeService homeService;

    public HomeController(HomeService homeService) {
        this.homeService = homeService;
    }

    /** Homepage for guests (public). */
    @GetMapping("/api/home")
    public ApiResponse<GuestHome> guest() {
        return ApiResponse.ok("OK", homeService.guest());
    }

    /** Homepage for a signed-in user (requires login: /api/me/**). */
    @GetMapping("/api/me/home")
    public ApiResponse<MemberHome> member(@AuthenticationPrincipal AppUserPrincipal me) {
        return ApiResponse.ok("OK", homeService.member(me.id()));
    }
}
