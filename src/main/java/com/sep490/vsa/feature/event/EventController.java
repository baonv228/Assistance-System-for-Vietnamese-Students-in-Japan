package com.sep490.vsa.feature.event;

import com.sep490.vsa.common.dto.ApiResponse;
import com.sep490.vsa.common.security.AppUserPrincipal;
import com.sep490.vsa.feature.event.EventDtos.EventCard;
import com.sep490.vsa.feature.event.EventDtos.EventDetail;
import com.sep490.vsa.feature.event.EventDtos.LikeState;
import com.sep490.vsa.feature.event.EventDtos.ReportRequest;
import com.sep490.vsa.feature.event.EventDtos.ReviewForm;
import com.sep490.vsa.feature.event.EventDtos.ReviewRequest;
import com.sep490.vsa.feature.event.EventDtos.ReviewSaved;
import com.sep490.vsa.feature.event.EventDtos.ReviewUpdateRequest;
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
 * "Lễ hội và sự kiện" for Members and Guests. GET calls are public (SecurityConfig); writing, liking and reporting
 * reviews need a signed-in User. Review calls live under /api/event-reviews so place reviews can take /api/reviews.
 */
@RestController
public class EventController {

    private final EventService events;
    private final EventReviewService reviews;

    public EventController(EventService events, EventReviewService reviews) {
        this.events = events;
        this.reviews = reviews;
    }

    /** Every event, soonest first; {@code q} matches the name, the place or the cost (accents ignored). */
    @GetMapping("/api/events")
    public ApiResponse<List<EventCard>> list(@RequestParam(required = false) String q) {
        return ApiResponse.ok("OK", events.list(q));
    }

    /** Event pins for the Map: happening now or starting within {@code days} (15 or 30). */
    @GetMapping("/api/events/pins")
    public ApiResponse<List<EventCard>> pins(@RequestParam(defaultValue = "30") int days) {
        return ApiResponse.ok("OK", events.pins(days));
    }

    @GetMapping("/api/events/{id:\\d+}")
    public ApiResponse<EventDetail> detail(@PathVariable long id, @AuthenticationPrincipal AppUserPrincipal me) {
        return ApiResponse.ok("OK", events.detail(id, me));
    }

    // ---------------------------------------------------------------- write review

    /** The form: event, limits, today's usage and the viewer's own review of this year (to edit). Members only. */
    @GetMapping("/api/events/{id:\\d+}/review-form")
    public ApiResponse<ReviewForm> form(@PathVariable long id, @AuthenticationPrincipal AppUserPrincipal me) {
        return ApiResponse.ok("OK", reviews.form(me, id));
    }

    /** Multipart: JSON part "review" ({rating, content}) + up to 5 "images" + one "video". */
    @PostMapping(value = "/api/events/{id:\\d+}/reviews", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<ReviewSaved> create(@PathVariable long id, @RequestPart("review") ReviewRequest request,
                                           @RequestPart(value = "images", required = false) List<MultipartFile> images,
                                           @RequestPart(value = "video", required = false) MultipartFile video,
                                           @AuthenticationPrincipal AppUserPrincipal me) {
        ReviewSaved saved = reviews.create(me, id, request, images, video);
        return ApiResponse.ok(createdMessage(saved), saved);
    }

    @PostMapping(value = "/api/events/{id:\\d+}/reviews", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ApiResponse<ReviewSaved> createJson(@PathVariable long id, @RequestBody ReviewRequest request,
                                               @AuthenticationPrincipal AppUserPrincipal me) {
        ReviewSaved saved = reviews.create(me, id, request, List.of(), null);
        return ApiResponse.ok(createdMessage(saved), saved);
    }

    /** Multipart: JSON part "review" ({rating, content, keepMediaIds}) + new files. */
    @PutMapping(value = "/api/event-reviews/{id:\\d+}", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<ReviewSaved> update(@PathVariable long id, @RequestPart("review") ReviewUpdateRequest request,
                                           @RequestPart(value = "images", required = false) List<MultipartFile> images,
                                           @RequestPart(value = "video", required = false) MultipartFile video,
                                           @AuthenticationPrincipal AppUserPrincipal me) {
        ReviewSaved saved = reviews.update(me, id, request, images, video);
        return ApiResponse.ok(updatedMessage(saved), saved);
    }

    @PutMapping(value = "/api/event-reviews/{id:\\d+}", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ApiResponse<ReviewSaved> updateJson(@PathVariable long id, @RequestBody ReviewUpdateRequest request,
                                               @AuthenticationPrincipal AppUserPrincipal me) {
        ReviewSaved saved = reviews.update(me, id, request, List.of(), null);
        return ApiResponse.ok(updatedMessage(saved), saved);
    }

    @DeleteMapping("/api/event-reviews/{id:\\d+}")
    public ApiResponse<Void> delete(@PathVariable long id, @AuthenticationPrincipal AppUserPrincipal me) {
        reviews.delete(me, id);
        return ApiResponse.ok("Đã xóa review. Điểm của review này được thu lại.", null);
    }

    @PostMapping("/api/event-reviews/{id:\\d+}/like")
    public ApiResponse<LikeState> like(@PathVariable long id, @AuthenticationPrincipal AppUserPrincipal me) {
        return ApiResponse.ok("OK", reviews.like(me, id, true));
    }

    @DeleteMapping("/api/event-reviews/{id:\\d+}/like")
    public ApiResponse<LikeState> unlike(@PathVariable long id, @AuthenticationPrincipal AppUserPrincipal me) {
        return ApiResponse.ok("OK", reviews.like(me, id, false));
    }

    @PostMapping("/api/event-reviews/{id:\\d+}/report")
    public ApiResponse<Void> report(@PathVariable long id, @RequestBody(required = false) ReportRequest body,
                                    @AuthenticationPrincipal AppUserPrincipal me) {
        reviews.report(me, id, body);
        return ApiResponse.ok("Cảm ơn bạn. Báo cáo đã được gửi.", null);                    // MSG38
    }

    private static String createdMessage(ReviewSaved saved) {
        return "PROCESSING".equals(saved.status())
                ? "Đã gửi. Ảnh và video đang được quét, trong lúc này chỉ bạn thấy review."
                : "Đã đăng review. +10 điểm.";
    }

    private static String updatedMessage(ReviewSaved saved) {
        return "PROCESSING".equals(saved.status())
                ? "Đã lưu thay đổi. Ảnh và video đang được quét, trong lúc này chỉ bạn thấy review."
                : "Đã lưu thay đổi.";
    }
}
