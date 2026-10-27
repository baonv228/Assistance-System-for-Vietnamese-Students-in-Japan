package com.sep490.vsa.feature.event;

import com.sep490.vsa.common.dto.ApiResponse;
import com.sep490.vsa.common.security.AppUserPrincipal;
import com.sep490.vsa.feature.event.EventDtos.EventRequest;
import com.sep490.vsa.feature.event.EventDtos.ModEvent;
import com.sep490.vsa.feature.event.EventDtos.ModSaved;
import com.sep490.vsa.feature.event.EventDtos.PlacePick;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/**
 * UC-M07 Lễ hội và sự kiện, page /moderator/events. /api/moderator/** needs the MODERATOR role (SecurityConfig).
 * "Đăng sự kiện" / "Sửa sự kiện" send a JSON part "event" plus an optional "cover" photo (plain JSON without a photo).
 */
@RestController
public class ModeratorEventController {

    private final EventService events;

    public ModeratorEventController(EventService events) {
        this.events = events;
    }

    @GetMapping("/api/moderator/events")
    public ApiResponse<List<ModEvent>> list() {
        return ApiResponse.ok("OK", events.moderatorList());
    }

    @PostMapping(value = "/api/moderator/events", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<ModSaved> create(@RequestPart("event") EventRequest body,
                                        @RequestPart(value = "cover", required = false) MultipartFile cover,
                                        @AuthenticationPrincipal AppUserPrincipal me) {
        ModSaved saved = events.create(me.id(), body, cover);
        return ApiResponse.ok(saved.message(), saved);
    }

    @PostMapping(value = "/api/moderator/events", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ApiResponse<ModSaved> createJson(@RequestBody EventRequest body,
                                            @AuthenticationPrincipal AppUserPrincipal me) {
        ModSaved saved = events.create(me.id(), body, null);
        return ApiResponse.ok(saved.message(), saved);
    }

    @PutMapping(value = "/api/moderator/events/{id:\\d+}", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<ModSaved> update(@PathVariable long id, @RequestPart("event") EventRequest body,
                                        @RequestPart(value = "cover", required = false) MultipartFile cover,
                                        @AuthenticationPrincipal AppUserPrincipal me) {
        ModSaved saved = events.update(me.id(), id, body, cover);
        return ApiResponse.ok(saved.message(), saved);
    }

    @PutMapping(value = "/api/moderator/events/{id:\\d+}", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ApiResponse<ModSaved> updateJson(@PathVariable long id, @RequestBody EventRequest body,
                                            @AuthenticationPrincipal AppUserPrincipal me) {
        ModSaved saved = events.update(me.id(), id, body, null);
        return ApiResponse.ok(saved.message(), saved);
    }

    @DeleteMapping("/api/moderator/events/{id:\\d+}")
    public ApiResponse<Void> delete(@PathVariable long id, @AuthenticationPrincipal AppUserPrincipal me) {
        events.delete(me.id(), id);
        return ApiResponse.ok("Đã xóa sự kiện.", null);
    }

    /** "Tìm nhanh địa điểm có sẵn": approved ASVSJ places with a position (at least 2 characters). */
    @GetMapping("/api/moderator/events/places")
    public ApiResponse<List<PlacePick>> places(@RequestParam(defaultValue = "") String q) {
        return ApiResponse.ok("OK", events.places(q));
    }
}
