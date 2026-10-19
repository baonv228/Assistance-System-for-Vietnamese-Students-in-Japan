package com.sep490.vsa.feature.saved;

import com.sep490.vsa.common.dto.ApiResponse;
import com.sep490.vsa.common.security.AppUserPrincipal;
import com.sep490.vsa.feature.saved.SavedDtos.SavedResponse;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** "Đã lưu" on the Tôi page. /api/me/** requires login (see SecurityConfig). */
@RestController
public class SavedController {

    private final SavedService savedService;

    public SavedController(SavedService savedService) {
        this.savedService = savedService;
    }

    @GetMapping("/api/me/saved")
    public ApiResponse<SavedResponse> saved(@AuthenticationPrincipal AppUserPrincipal me) {
        return ApiResponse.ok("OK", savedService.list(me.id()));
    }
}
