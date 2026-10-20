package com.sep490.vsa.feature.review;

import com.sep490.vsa.common.dto.ApiResponse;
import com.sep490.vsa.common.security.AppUserPrincipal;
import com.sep490.vsa.feature.review.ReviewDtos.Compose;
import com.sep490.vsa.feature.review.ReviewDtos.CreateRequest;
import com.sep490.vsa.feature.review.ReviewDtos.EditData;
import com.sep490.vsa.feature.review.ReviewDtos.Saved;
import com.sep490.vsa.feature.review.ReviewDtos.UpdateRequest;
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
 * Write / Edit / Delete Review (UC-U16, UC-U17, UC-U18), opened from the Map's place details. Signed-in Users only.
 * Multipart: JSON part "review" + up to 5 "images" + 1 "video"; plain JSON is accepted when there are no files.
 */
@RestController
public class ReviewController {

    private final ReviewService service;

    public ReviewController(ReviewService service) {
        this.service = service;
    }

    @GetMapping("/api/reviews/compose")
    public ApiResponse<Compose> compose(@RequestParam(required = false) Long placeId,
                                        @AuthenticationPrincipal AppUserPrincipal me) {
        return ApiResponse.ok("OK", service.compose(me, placeId));
    }

    @PostMapping(value = "/api/reviews", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<Saved> create(@RequestPart("review") CreateRequest request,
                                     @RequestPart(value = "images", required = false) List<MultipartFile> images,
                                     @RequestPart(value = "video", required = false) MultipartFile video,
                                     @AuthenticationPrincipal AppUserPrincipal me) {
        Saved saved = service.create(me, request, images, video);
        return ApiResponse.ok(createdMessage(saved), saved);
    }

    @PostMapping(value = "/api/reviews", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ApiResponse<Saved> createJson(@RequestBody CreateRequest request,
                                         @AuthenticationPrincipal AppUserPrincipal me) {
        Saved saved = service.create(me, request, List.of(), null);
        return ApiResponse.ok(createdMessage(saved), saved);
    }

    @GetMapping("/api/reviews/{id:\\d+}/edit")
    public ApiResponse<EditData> editData(@PathVariable long id, @AuthenticationPrincipal AppUserPrincipal me) {
        return ApiResponse.ok("OK", service.editData(me, id));
    }

    @PutMapping(value = "/api/reviews/{id:\\d+}", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<Saved> update(@PathVariable long id, @RequestPart("review") UpdateRequest request,
                                     @RequestPart(value = "images", required = false) List<MultipartFile> images,
                                     @RequestPart(value = "video", required = false) MultipartFile video,
                                     @AuthenticationPrincipal AppUserPrincipal me) {
        Saved saved = service.update(me, id, request, images, video);
        return ApiResponse.ok(updatedMessage(saved), saved);
    }

    @PutMapping(value = "/api/reviews/{id:\\d+}", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ApiResponse<Saved> updateJson(@PathVariable long id, @RequestBody UpdateRequest request,
                                         @AuthenticationPrincipal AppUserPrincipal me) {
        Saved saved = service.update(me, id, request, List.of(), null);
        return ApiResponse.ok(updatedMessage(saved), saved);
    }

    @DeleteMapping("/api/reviews/{id:\\d+}")
    public ApiResponse<Void> delete(@PathVariable long id, @AuthenticationPrincipal AppUserPrincipal me) {
        service.delete(me, id);
        return ApiResponse.ok("Đã xóa review. Điểm của review này được thu lại.", null);
    }

    private static String createdMessage(Saved saved) {
        return "PROCESSING".equals(saved.status())
                ? "Đã gửi. Ảnh/video đang được quét, review hiện cho mọi người sau khi đạt."
                : "Đã đăng review. +10 điểm.";
    }

    private static String updatedMessage(Saved saved) {
        return "PROCESSING".equals(saved.status())
                ? "Đã lưu thay đổi. Ảnh/video đang được quét, trong lúc này chỉ bạn thấy review."
                : "Đã lưu thay đổi.";
    }
}
