package com.sep490.vsa.feature.place;

import com.sep490.vsa.common.dto.ApiResponse;
import com.sep490.vsa.common.security.AppUserPrincipal;
import com.sep490.vsa.feature.place.PinSettingsService.PinSettings;
import com.sep490.vsa.feature.place.PinSettingsService.PinSettingsForm;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** "Duyệt ghim tự động" (Moderator overview). {@code /api/moderator/**} is for the MODERATOR role (SecurityConfig). */
@RestController
public class PinSettingsController {

    private final PinSettingsService service;

    public PinSettingsController(PinSettingsService service) {
        this.service = service;
    }

    @GetMapping("/api/moderator/pin-settings")
    public ApiResponse<PinSettings> get() {
        return ApiResponse.ok("OK", service.get());
    }

    @PutMapping("/api/moderator/pin-settings")
    public ApiResponse<PinSettings> update(@RequestBody(required = false) PinSettingsForm body,
                                           @AuthenticationPrincipal AppUserPrincipal me) {
        return ApiResponse.ok("Đã lưu cấu hình duyệt ghim.", service.update(me.id(), body));
    }
}
