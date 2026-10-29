package com.sep490.vsa.feature.user;

import com.sep490.vsa.common.dto.ApiResponse;
import com.sep490.vsa.common.security.AppUserPrincipal;
import com.sep490.vsa.feature.user.PointHistoryService.PointPage;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** UC-U10 "Lịch sử điểm" on Tôi. /api/me/** needs a signed-in user (SecurityConfig). */
@RestController
public class PointHistoryController {

    private final PointHistoryService history;

    public PointHistoryController(PointHistoryService history) {
        this.history = history;
    }

    @GetMapping("/api/me/points")
    public ApiResponse<PointPage> points(@AuthenticationPrincipal AppUserPrincipal me,
                                         @RequestParam(defaultValue = "0") int page) {
        return ApiResponse.ok("OK", history.history(me.id(), page));
    }
}
