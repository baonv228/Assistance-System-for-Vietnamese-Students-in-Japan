package com.sep490.vsa.feature.area;

import com.sep490.vsa.common.dto.ApiResponse;
import com.sep490.vsa.common.exception.ApiException;
import com.sep490.vsa.feature.area.AreaDtos.AreaOption;
import com.sep490.vsa.feature.area.AreaDtos.Picker;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** The living-area picker of "Tôi" and the member Home (public GETs, nothing is stored here). */
@RestController
public class AreaController {

    private final AreaService areas;

    public AreaController(AreaService areas) {
        this.areas = areas;
    }

    /** The prefectures (and the fixed test position of "Vị trí của tôi", if any). */
    @GetMapping("/api/areas")
    public ApiResponse<Picker> picker() {
        return ApiResponse.ok("OK", areas.picker());
    }

    @GetMapping("/api/areas/prefectures/{code}")
    public ApiResponse<List<AreaOption>> inPrefecture(@PathVariable String code) {
        return ApiResponse.ok("OK", areas.inPrefecture(code));
    }

    @GetMapping("/api/areas/search")
    public ApiResponse<List<AreaOption>> search(@RequestParam(defaultValue = "") String q) {
        return ApiResponse.ok("OK", areas.search(q));
    }

    /** "Chọn trên bản đồ": the area of a point; the point is not stored or logged. */
    @GetMapping("/api/areas/nearest")
    public ApiResponse<AreaOption> nearest(@RequestParam double lat, @RequestParam double lng) {
        return ApiResponse.ok("OK", areas.nearest(lat, lng));
    }

    @GetMapping("/api/areas/{id}")
    public ApiResponse<AreaOption> area(@PathVariable long id) {
        return ApiResponse.ok("OK", areas.find(id)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Không tìm thấy khu vực này.")));
    }
}
