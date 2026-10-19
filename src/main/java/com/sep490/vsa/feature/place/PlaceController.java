package com.sep490.vsa.feature.place;

import com.sep490.vsa.common.dto.ApiResponse;
import com.sep490.vsa.common.exception.ApiException;
import com.sep490.vsa.common.security.AppUserPrincipal;
import com.sep490.vsa.feature.place.PlaceDtos.PlaceSearch;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class PlaceController {

    private final PlaceService service;

    public PlaceController(PlaceService service) {
        this.service = service;
    }

    /**
     * "Địa điểm liên quan" search box of Write Post and Đặt câu hỏi: ASVSJ places, then OpenStreetMap places in Japan
     * and Vietnam. Signed-in users only, so the free public map services are used fairly (see PlaceService).
     */
    @GetMapping("/api/places/search")
    public ApiResponse<PlaceSearch> search(@RequestParam(defaultValue = "") String q,
                                           @AuthenticationPrincipal AppUserPrincipal me) {
        if (me == null) {
            throw ApiException.unauthorized("Bạn cần đăng nhập để tìm địa điểm.");
        }
        return ApiResponse.ok("OK", service.search(me.id(), q));
    }
}
