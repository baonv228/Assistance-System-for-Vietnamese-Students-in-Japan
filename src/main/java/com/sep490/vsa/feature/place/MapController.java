package com.sep490.vsa.feature.place;

import com.sep490.vsa.common.dto.ApiResponse;
import com.sep490.vsa.common.exception.ApiException;
import com.sep490.vsa.common.security.AppUserPrincipal;
import com.sep490.vsa.feature.place.MapDtos.LikeState;
import com.sep490.vsa.feature.place.MapDtos.MapEvent;
import com.sep490.vsa.feature.place.MapDtos.MapOverview;
import com.sep490.vsa.feature.place.MapDtos.MapSearch;
import com.sep490.vsa.feature.place.MapDtos.PhraseItem;
import com.sep490.vsa.feature.place.MapDtos.PlaceDetail;
import com.sep490.vsa.feature.place.MapDtos.ReportRequest;
import com.sep490.vsa.feature.place.MapDtos.ReviewPage;
import com.sep490.vsa.feature.place.MapDtos.SaveState;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * The Map page (/map): UC-U14 Browse Locations on Map, UC-U15 View Location Details, saving a place and liking or
 * reporting a review. GET calls are public; the others need a signed-in User.
 */
@RestController
public class MapController {

    private final MapService service;

    public MapController(MapService service) {
        this.service = service;
    }

    /** Categories with counts, every place pin, and where the map opens. */
    @GetMapping("/api/map")
    public ApiResponse<MapOverview> overview(@AuthenticationPrincipal AppUserPrincipal me) {
        return ApiResponse.ok("OK", service.overview(me));
    }

    /** Festival and event pins: events on now or starting within 15 or 30 days. */
    @GetMapping("/api/map/events")
    public ApiResponse<List<MapEvent>> events(@RequestParam(defaultValue = "30") int days) {
        return ApiResponse.ok("OK", service.events(days));
    }

    /**
     * Search on the Map. With the view on screen ({@code south, west, north, east}): the places inside it, or, when it
     * holds none, all of Japan nearest to it first (answer {@code scope} AREA / WIDE). Without: all of Japan.
     */
    @GetMapping("/api/map/search")
    public ApiResponse<MapSearch> search(@RequestParam(defaultValue = "") String q,
                                         @RequestParam(required = false) Double south,
                                         @RequestParam(required = false) Double west,
                                         @RequestParam(required = false) Double north,
                                         @RequestParam(required = false) Double east,
                                         @AuthenticationPrincipal AppUserPrincipal me) {
        return ApiResponse.ok("OK", service.search(me, q, view(south, west, north, east)));
    }

    static final String BAD_VIEW = "Vùng bản đồ không hợp lệ. Hãy tải lại trang.";

    /** The box on screen, or null when none was sent; a box that is not one answers 400. */
    static ExternalPlaces.Where view(Double south, Double west, Double north, Double east) {
        if (south == null && west == null && north == null && east == null) {
            return null;
        }
        if (south == null || west == null || north == null || east == null
                || !Double.isFinite(south) || !Double.isFinite(west) || !Double.isFinite(north) || !Double.isFinite(east)
                || south < -90 || north > 90 || south >= north
                || west < -180 || east > 180 || west >= east) {
            throw ApiException.badRequest(BAD_VIEW);
        }
        return new ExternalPlaces.Where(south, west, north, east, true);
    }

    /** Places around a point clicked on the map (signed-in people only). */
    @GetMapping("/api/map/nearby")
    public ApiResponse<MapSearch> nearby(@RequestParam double lat, @RequestParam double lng,
                                         @AuthenticationPrincipal AppUserPrincipal me) {
        return ApiResponse.ok("OK", service.nearby(me, lat, lng));
    }

    @GetMapping("/api/map/places/{id:\\d+}")
    public ApiResponse<PlaceDetail> detail(@PathVariable long id, @AuthenticationPrincipal AppUserPrincipal me) {
        return ApiResponse.ok("OK", service.detail(id, me));
    }

    @GetMapping("/api/map/places/{id:\\d+}/reviews")
    public ApiResponse<ReviewPage> reviews(@PathVariable long id, @RequestParam(defaultValue = "0") int page,
                                           @AuthenticationPrincipal AppUserPrincipal me) {
        return ApiResponse.ok("OK", service.reviews(id, page, me));
    }

    @GetMapping("/api/map/places/{id:\\d+}/phrases")
    public ApiResponse<List<PhraseItem>> phrases(@PathVariable long id, @AuthenticationPrincipal AppUserPrincipal me) {
        return ApiResponse.ok("OK", service.phrases(id, me));
    }

    @PostMapping("/api/map/places/{id:\\d+}/save")
    public ApiResponse<SaveState> save(@PathVariable long id, @AuthenticationPrincipal AppUserPrincipal me) {
        return ApiResponse.ok("Đã lưu địa điểm.", service.save(me, id, true));
    }

    @DeleteMapping("/api/map/places/{id:\\d+}/save")
    public ApiResponse<SaveState> unsave(@PathVariable long id, @AuthenticationPrincipal AppUserPrincipal me) {
        return ApiResponse.ok("Đã bỏ lưu địa điểm.", service.save(me, id, false));
    }

    /**
     * UC-U20 "Báo cáo địa điểm": {@code {kind: CLOSED | WRONG_INFO, note}} (user decision 20/11/2026; it was a plain
     * "Báo đã đóng cửa" click).
     */
    @PostMapping("/api/map/places/{id:\\d+}/report")
    public ApiResponse<MapDtos.ClosedState> reportPlace(@PathVariable long id,
                                                        @RequestBody(required = false) MapDtos.PlaceReportRequest body,
                                                        @AuthenticationPrincipal AppUserPrincipal me) {
        MapDtos.ClosedState state = service.reportPlace(me, id, body);
        String message;
        if (body != null && "WRONG_INFO".equals(body.kind())) {
            message = "Cảm ơn bạn. Moderator sẽ kiểm tra và sửa thông tin địa điểm.";
        } else if (state.queued()) {
            message = "Đủ " + state.threshold() + " báo cáo. Moderator sẽ kiểm tra trước khi gắn nhãn “Đã đóng cửa”.";
        } else {
            message = "Đã ghi nhận báo cáo. Khi đủ " + state.threshold() + " người báo, Moderator sẽ kiểm tra.";
        }
        return ApiResponse.ok(message, state);
    }

    @PostMapping("/api/reviews/{id:\\d+}/like")
    public ApiResponse<LikeState> like(@PathVariable long id, @AuthenticationPrincipal AppUserPrincipal me) {
        return ApiResponse.ok("OK", service.likeReview(me, id, true));
    }

    @DeleteMapping("/api/reviews/{id:\\d+}/like")
    public ApiResponse<LikeState> unlike(@PathVariable long id, @AuthenticationPrincipal AppUserPrincipal me) {
        return ApiResponse.ok("OK", service.likeReview(me, id, false));
    }

    @PostMapping("/api/reviews/{id:\\d+}/report")
    public ApiResponse<Void> report(@PathVariable long id, @Valid @RequestBody ReportRequest request,
                                    @AuthenticationPrincipal AppUserPrincipal me) {
        service.reportReview(me, id, request);
        return ApiResponse.ok("Đã gửi báo cáo cho Moderator.", null);
    }
}
