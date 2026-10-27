package com.sep490.vsa.feature.place;

import com.sep490.vsa.common.dto.ApiResponse;
import com.sep490.vsa.common.exception.ApiException;
import com.sep490.vsa.common.security.AppUserPrincipal;
import com.sep490.vsa.feature.place.PlaceDtos.PlaceSearch;
import com.sep490.vsa.feature.place.PlaceModerationDtos.ApproveRequest;
import com.sep490.vsa.feature.place.PlaceModerationDtos.ClosedLabel;
import com.sep490.vsa.feature.place.PlaceModerationDtos.ClosedQueue;
import com.sep490.vsa.feature.place.PlaceModerationDtos.DismissRequest;
import com.sep490.vsa.feature.place.PlaceModerationDtos.Done;
import com.sep490.vsa.feature.place.PlaceModerationDtos.MergeCompare;
import com.sep490.vsa.feature.place.PlaceModerationDtos.MergeDecision;
import com.sep490.vsa.feature.place.PlaceModerationDtos.MergePick;
import com.sep490.vsa.feature.place.PlaceModerationDtos.MergeQueue;
import com.sep490.vsa.feature.place.PlaceModerationDtos.MergeRequest;
import com.sep490.vsa.feature.place.PlaceModerationDtos.OsmPick;
import com.sep490.vsa.feature.place.PlaceModerationDtos.PinDetail;
import com.sep490.vsa.feature.place.PlaceModerationDtos.PinQueue;
import com.sep490.vsa.feature.place.PlaceModerationDtos.PlaceForm;
import com.sep490.vsa.feature.place.PlaceModerationDtos.PlaceInfo;
import com.sep490.vsa.feature.place.PlaceModerationDtos.PlacePage;
import com.sep490.vsa.feature.place.PlaceModerationDtos.PlaceSaved;
import com.sep490.vsa.feature.place.PlaceModerationDtos.RejectRequest;
import com.sep490.vsa.feature.place.PlaceModerationDtos.RemoveRequest;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * The Moderator's place pages: "Ghim mới" /moderator/pins (UC-M05, UC-M06), "Báo đóng cửa" /moderator/closed
 * (UC-M04 AF4/AF5) and "Địa điểm" /moderator/places (UC-M07, UC-M08). /api/moderator/** needs the MODERATOR role
 * (SecurityConfig).
 */
@RestController
public class ModeratorPlaceController {

    private final PinModerationService pins;
    private final PlaceAdminService admin;
    private final PlaceMergeService merges;

    public ModeratorPlaceController(PinModerationService pins, PlaceAdminService admin, PlaceMergeService merges) {
        this.pins = pins;
        this.admin = admin;
        this.merges = merges;
    }

    @GetMapping("/api/moderator/pins")
    public ApiResponse<PinQueue> pins(@RequestParam(required = false) String category,
                                      @RequestParam(defaultValue = "false") boolean duplicates) {
        return ApiResponse.ok("OK", pins.pins(category, duplicates));
    }

    @GetMapping("/api/moderator/pins/{id:\\d+}")
    public ApiResponse<PinDetail> pin(@PathVariable long id) {
        return ApiResponse.ok("OK", pins.pin(id));
    }

    @PostMapping("/api/moderator/pins/{id:\\d+}/approve")
    public ApiResponse<Done> approve(@PathVariable long id, @RequestBody(required = false) ApproveRequest body,
                                     @AuthenticationPrincipal AppUserPrincipal me) {
        return done(pins.approve(me.id(), id, body));
    }

    @PostMapping("/api/moderator/pins/{id:\\d+}/reject")
    public ApiResponse<Done> reject(@PathVariable long id, @RequestBody(required = false) RejectRequest body,
                                    @AuthenticationPrincipal AppUserPrincipal me) {
        return done(pins.reject(me.id(), id, body));
    }

    @PostMapping("/api/moderator/pins/{id:\\d+}/merge")
    public ApiResponse<Done> merge(@PathVariable long id, @RequestBody(required = false) MergeRequest body,
                                   @AuthenticationPrincipal AppUserPrincipal me) {
        return done(pins.merge(me.id(), id, body));
    }

    @GetMapping("/api/moderator/closed")
    public ApiResponse<ClosedQueue> closed() {
        return ApiResponse.ok("OK", pins.closedQueue());
    }

    /** {"closed": true} "Xác nhận, gắn nhãn đã đóng cửa"; {"closed": false} "Gỡ nhãn, địa điểm đã mở lại". */
    @PutMapping("/api/moderator/places/{id:\\d+}/closed")
    public ApiResponse<Done> setClosed(@PathVariable long id, @RequestBody ClosedLabel body,
                                       @AuthenticationPrincipal AppUserPrincipal me) {
        if (body == null || body.closed() == null) {
            throw ApiException.badRequest("Thiếu trạng thái đóng cửa.");
        }
        return done(pins.setClosed(me.id(), id, body.closed()));
    }

    /** "Vẫn mở cửa, xóa các báo cáo". */
    @DeleteMapping("/api/moderator/places/{id:\\d+}/closed-reports")
    public ApiResponse<Done> keepOpen(@PathVariable long id, @AuthenticationPrincipal AppUserPrincipal me) {
        return done(pins.keepOpen(me.id(), id));
    }

    // ---------------------------------------------------------------- "Địa điểm" /moderator/places (UC-M07, UC-M08)

    @GetMapping("/api/moderator/places")
    public ApiResponse<PlacePage> places(@RequestParam(required = false) String q,
                                         @RequestParam(required = false) String category,
                                         @RequestParam(required = false) String source,
                                         @RequestParam(required = false) String status,
                                         @RequestParam(required = false) String prefecture,
                                         @RequestParam(defaultValue = "0") int page) {
        return ApiResponse.ok("OK", admin.list(q, category, source, status, prefecture, page));
    }

    @GetMapping("/api/moderator/places/{id:\\d+}")
    public ApiResponse<PlaceInfo> place(@PathVariable long id) {
        return ApiResponse.ok("OK", admin.detail(id));
    }

    /** "Thêm địa điểm" step 1: Senpai ơi places first, then OpenStreetMap places not in Senpai ơi yet. */
    @GetMapping("/api/moderator/places/osm-search")
    public ApiResponse<PlaceSearch> searchOsm(@RequestParam(defaultValue = "") String q,
                                              @AuthenticationPrincipal AppUserPrincipal me) {
        return ApiResponse.ok("OK", admin.searchOsm(me.id(), q));
    }

    /** "Chọn" an OpenStreetMap result ("N772389775"): the filled-in form, or the Senpai ơi place it already is. */
    @GetMapping("/api/moderator/places/osm/{externalId}")
    public ApiResponse<OsmPick> pickOsm(@PathVariable String externalId, @AuthenticationPrincipal AppUserPrincipal me) {
        return ApiResponse.ok("OK", admin.pickOsm(me.id(), externalId));
    }

    @PostMapping("/api/moderator/places")
    public ApiResponse<PlaceSaved> create(@RequestBody PlaceForm body, @AuthenticationPrincipal AppUserPrincipal me) {
        PlaceSaved saved = admin.create(me.id(), body);
        return ApiResponse.ok(saved.message(), saved);
    }

    @PutMapping("/api/moderator/places/{id:\\d+}")
    public ApiResponse<PlaceSaved> update(@PathVariable long id, @RequestBody PlaceForm body,
                                          @AuthenticationPrincipal AppUserPrincipal me) {
        PlaceSaved saved = admin.update(me.id(), id, body);
        return ApiResponse.ok(saved.message(), saved);
    }

    @PostMapping("/api/moderator/places/{id:\\d+}/remove")
    public ApiResponse<Done> remove(@PathVariable long id, @RequestBody(required = false) RemoveRequest body,
                                    @AuthenticationPrincipal AppUserPrincipal me) {
        return done(admin.remove(me.id(), id, body));
    }

    @PostMapping("/api/moderator/places/{id:\\d+}/restore")
    public ApiResponse<Done> restore(@PathVariable long id, @AuthenticationPrincipal AppUserPrincipal me) {
        return done(admin.restore(me.id(), id));
    }

    // ---------------------------------------------------------------- "Gộp trùng lặp" /moderator/merge (UC-M09)

    @GetMapping("/api/moderator/merges")
    public ApiResponse<MergeQueue> merges(@RequestParam(defaultValue = "0") int page) {
        return ApiResponse.ok("OK", merges.queue(page));
    }

    @GetMapping("/api/moderator/merges/compare")
    public ApiResponse<MergeCompare> compare(@RequestParam long a, @RequestParam long b) {
        return ApiResponse.ok("OK", merges.compare(a, b));
    }

    /** "Chọn địa điểm để so sánh" / "Gộp với địa điểm khác": approved places, nearest to {@code near} first. */
    @GetMapping("/api/moderator/merges/search")
    public ApiResponse<List<MergePick>> mergeSearch(@RequestParam(defaultValue = "") String q,
                                                    @RequestParam(required = false) Long near) {
        return ApiResponse.ok("OK", merges.search(q, near));
    }

    @PostMapping("/api/moderator/merges")
    public ApiResponse<Done> mergePlaces(@RequestBody MergeDecision body, @AuthenticationPrincipal AppUserPrincipal me) {
        return done(merges.merge(me.id(), body));
    }

    @PostMapping("/api/moderator/merges/dismiss")
    public ApiResponse<Done> dismiss(@RequestBody DismissRequest body, @AuthenticationPrincipal AppUserPrincipal me) {
        return done(merges.dismiss(me.id(), body));
    }

    private static ApiResponse<Done> done(Done done) {
        return ApiResponse.ok(done.message(), done);
    }
}
