package com.sep490.vsa.feature.guide;

import com.sep490.vsa.common.dto.ApiResponse;
import com.sep490.vsa.common.security.AppUserPrincipal;
import com.sep490.vsa.feature.guide.MyGuidesService.MyGuides;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** "Cẩm nang của tôi" on Tôi. /api/me/** requires login (see SecurityConfig); Members only (MSG18 for staff). */
@RestController
public class MyGuidesController {

    private final MyGuidesService service;

    public MyGuidesController(MyGuidesService service) {
        this.service = service;
    }

    @GetMapping("/api/me/guides/progress")
    public ApiResponse<MyGuides> progress(@AuthenticationPrincipal AppUserPrincipal me) {
        return ApiResponse.ok("OK", service.progress(me.id()));
    }
}
