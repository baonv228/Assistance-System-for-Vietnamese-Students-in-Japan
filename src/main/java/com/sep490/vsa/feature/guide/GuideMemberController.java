package com.sep490.vsa.feature.guide;

import com.sep490.vsa.common.dto.ApiResponse;
import com.sep490.vsa.common.security.AppUserPrincipal;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * A Member's actions on a guide page: "Lưu" and "Đánh dấu đã xong" ({@link GuideMemberService}). The viewer state is
 * public (GET); the actions need a session (SecurityConfig) and a Member account (the service).
 */
@RestController
public class GuideMemberController {

    private final GuideMemberService service;

    public GuideMemberController(GuideMemberService service) {
        this.service = service;
    }

    @GetMapping("/api/guides/{id}/viewer")
    public ApiResponse<GuideMemberService.Viewer> viewer(@PathVariable long id,
                                                         @AuthenticationPrincipal AppUserPrincipal me) {
        return ApiResponse.ok("Trạng thái của bạn với cẩm nang", service.viewer(id, me));
    }

    @PostMapping("/api/guides/{id}/save")
    public ApiResponse<GuideMemberService.SaveState> save(@PathVariable long id,
                                                          @AuthenticationPrincipal AppUserPrincipal me) {
        return ApiResponse.ok("Đã lưu cẩm nang.", service.save(id, me, true));
    }

    @DeleteMapping("/api/guides/{id}/save")
    public ApiResponse<GuideMemberService.SaveState> unsave(@PathVariable long id,
                                                            @AuthenticationPrincipal AppUserPrincipal me) {
        return ApiResponse.ok("Đã bỏ lưu cẩm nang.", service.save(id, me, false));
    }

    @PostMapping("/api/guides/{id}/done")
    public ApiResponse<GuideMemberService.Progress> done(@PathVariable long id,
                                                         @AuthenticationPrincipal AppUserPrincipal me) {
        return ApiResponse.ok("Đã đánh dấu xong cẩm nang.", service.markDone(id, me, true));
    }

    @DeleteMapping("/api/guides/{id}/done")
    public ApiResponse<GuideMemberService.Progress> undone(@PathVariable long id,
                                                           @AuthenticationPrincipal AppUserPrincipal me) {
        return ApiResponse.ok("Đã bỏ đánh dấu đã xong.", service.markDone(id, me, false));
    }
}
