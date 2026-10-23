package com.sep490.vsa.feature.review;

import com.sep490.vsa.common.dto.ApiResponse;
import com.sep490.vsa.common.security.AppUserPrincipal;
import com.sep490.vsa.feature.review.ReviewDtos.Duplicate;
import com.sep490.vsa.feature.review.ReviewDtos.PinCompose;
import com.sep490.vsa.feature.review.ReviewDtos.PinRequest;
import com.sep490.vsa.feature.review.ReviewDtos.PinSaved;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.Arrays;
import java.util.List;

/**
 * UC-U19 "Cắm ghim" from the Map: form options, the "Có thể trùng" check and saving the pin with its first review.
 * Multipart: JSON part "pin" + up to 5 "images"; plain JSON without photos. Signed-in Users only.
 */
@RestController
public class PinController {

    private final PinService service;

    public PinController(PinService service) {
        this.service = service;
    }

    @GetMapping("/api/map/pins/compose")
    public ApiResponse<PinCompose> compose(@AuthenticationPrincipal AppUserPrincipal me) {
        return ApiResponse.ok("OK", service.compose(me));
    }

    /** @param names the pin's names separated by "|" */
    @GetMapping("/api/map/pins/duplicates")
    public ApiResponse<List<Duplicate>> duplicates(@RequestParam double lat, @RequestParam double lng,
                                                   @RequestParam(defaultValue = "") String names) {
        return ApiResponse.ok("OK", service.duplicates(lat, lng, Arrays.asList(names.split("\\|"))));
    }

    @PostMapping(value = "/api/map/pins", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<PinSaved> create(@RequestPart("pin") PinRequest request,
                                        @RequestPart(value = "images", required = false) List<MultipartFile> images,
                                        @AuthenticationPrincipal AppUserPrincipal me) {
        return ApiResponse.ok(message(), service.create(me, request, images));
    }

    @PostMapping(value = "/api/map/pins", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ApiResponse<PinSaved> createJson(@RequestBody PinRequest request,
                                            @AuthenticationPrincipal AppUserPrincipal me) {
        return ApiResponse.ok(message(), service.create(me, request, List.of()));
    }

    private static String message() {
        return "Đã gửi ghim kèm review. Chờ Moderator duyệt.";
    }
}
