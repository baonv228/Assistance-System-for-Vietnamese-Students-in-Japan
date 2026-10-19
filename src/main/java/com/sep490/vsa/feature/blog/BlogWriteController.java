package com.sep490.vsa.feature.blog;

import com.sep490.vsa.common.dto.ApiResponse;
import com.sep490.vsa.common.security.AppUserPrincipal;
import com.sep490.vsa.feature.blog.BlogWriteDtos.ComposeOptions;
import com.sep490.vsa.feature.blog.BlogWriteDtos.CreateRequest;
import com.sep490.vsa.feature.blog.BlogWriteDtos.Created;
import com.sep490.vsa.feature.blog.BlogWriteDtos.ScamWarning;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/**
 * Write Post (UC-11), blog posts only; questions are created at POST /api/questions (feature/qa). The page sends
 * one multipart request: a JSON part "post" plus up to 5 "images" and one "video". A post without files may also
 * be sent as plain JSON. The "Địa điểm liên quan" search box uses GET /api/places/search (feature/place).
 */
@RestController
public class BlogWriteController {

    private final BlogWriteService service;

    public BlogWriteController(BlogWriteService service) {
        this.service = service;
    }

    /** Topics, limits and today's new-account usage for the form. Signed-in Users only (401 / 403 otherwise). */
    @GetMapping("/api/blogs/compose")
    public ApiResponse<ComposeOptions> compose(@AuthenticationPrincipal AppUserPrincipal me) {
        return ApiResponse.ok("OK", service.compose(me));
    }

    /** Places with a published "Cảnh báo lừa đảo" post, for the "!" mark on the Map (BR-13). Public. */
    @GetMapping("/api/blogs/scam-warnings")
    public ApiResponse<List<ScamWarning>> scamWarnings(@RequestParam(required = false) Long placeId) {
        return ApiResponse.ok("OK", service.scamWarnings(placeId));
    }

    @PostMapping(value = "/api/blogs", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<Created> create(@RequestPart("post") CreateRequest request,
                                       @RequestPart(value = "images", required = false) List<MultipartFile> images,
                                       @RequestPart(value = "video", required = false) MultipartFile video,
                                       @AuthenticationPrincipal AppUserPrincipal me) {
        Created created = service.create(me, BlogDetailController.KIND, request, images, video);
        return ApiResponse.ok(message(created), created);
    }

    @PostMapping(value = "/api/blogs", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ApiResponse<Created> createJson(@RequestBody CreateRequest request,
                                           @AuthenticationPrincipal AppUserPrincipal me) {
        Created created = service.create(me, BlogDetailController.KIND, request, List.of(), null);
        return ApiResponse.ok(message(created), created);
    }

    private static String message(Created created) {
        if ("PROCESSING".equals(created.status())) {
            return "Đã gửi. Ảnh và video đang được quét, trong lúc này chỉ bạn thấy bài.";
        }
        return "Bài viết của bạn đã được đăng.";                                               // MSG34
    }
}
