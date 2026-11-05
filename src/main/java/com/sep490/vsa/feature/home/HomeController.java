package com.sep490.vsa.feature.home;

import com.sep490.vsa.common.dto.ApiResponse;
import com.sep490.vsa.common.security.AppUserPrincipal;
import com.sep490.vsa.feature.home.HomeDtos.ForYouFeed;
import com.sep490.vsa.feature.home.HomeDtos.GuestHome;
import com.sep490.vsa.feature.home.HomeDtos.MemberHome;
import com.sep490.vsa.feature.home.HomeDtos.ReviewFeed;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class HomeController {

    private final HomeService homeService;
    private final HomeReviewService reviewService;

    public HomeController(HomeService homeService, HomeReviewService reviewService) {
        this.homeService = homeService;
        this.reviewService = reviewService;
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

    /**
     * Page "Dành cho bạn" (requires login: /api/me/**): the whole personal feed of the member Home, 10 posts a page;
     * {@code scope} ALL, NEAR, STAGE or INTEREST (ALL when left out or not available to this user).
     */
    @GetMapping("/api/me/for-you")
    public ApiResponse<ForYouFeed> forYou(@AuthenticationPrincipal AppUserPrincipal me,
                                          @RequestParam(required = false) String scope,
                                          @RequestParam(defaultValue = "0") int page) {
        return ApiResponse.ok("OK", homeService.forYouPage(me.id(), scope, page));
    }

    /**
     * Page "Review mới" (public): {@code scope} AREA, PREFECTURE or ALL (the first one holding reviews when left out);
     * the first two only for a signed-in user who chose an area.
     */
    @GetMapping("/api/home/reviews")
    public ApiResponse<ReviewFeed> reviews(@AuthenticationPrincipal AppUserPrincipal me,
                                           @RequestParam(required = false) String scope,
                                           @RequestParam(defaultValue = "0") int page) {
        return ApiResponse.ok("OK", reviewService.recent(me == null ? null : me.id(), scope, page));
    }
}
