package com.sep490.vsa.feature.tag;

import com.sep490.vsa.common.dto.ApiResponse;
import com.sep490.vsa.common.security.AppUserPrincipal;
import com.sep490.vsa.feature.tag.TagDtos.Done;
import com.sep490.vsa.feature.tag.TagDtos.RejectForm;
import com.sep490.vsa.feature.tag.TagDtos.RequestDetail;
import com.sep490.vsa.feature.tag.TagDtos.RequestPage;
import com.sep490.vsa.feature.tag.TagDtos.Submitted;
import com.sep490.vsa.feature.tag.TagDtos.TagCompose;
import com.sep490.vsa.feature.tag.TagDtos.TagRequestForm;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * Identity tags. UC-U09 "Xin tag định danh" under /api/me/** (signed-in users); UC-M01 / UC-M02 under
 * /api/moderator/** (MODERATOR role, SecurityConfig).
 */
@RestController
public class TagController {

    private final TagService tags;

    public TagController(TagService tags) {
        this.tags = tags;
    }

    @GetMapping("/api/me/tag-requests/compose")
    public ApiResponse<TagCompose> compose(@AuthenticationPrincipal AppUserPrincipal me) {
        return ApiResponse.ok("OK", tags.compose(me.id()));
    }

    /** Multipart: part "request" (JSON {tagId, proofTypeId, consent}) + part "photo". */
    @PostMapping(value = "/api/me/tag-requests", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<Submitted> submit(@RequestPart(value = "request", required = false) TagRequestForm form,
                                         @RequestPart(value = "photo", required = false) MultipartFile photo,
                                         @AuthenticationPrincipal AppUserPrincipal me) {
        Submitted done = tags.submit(me.id(), form, photo);
        return ApiResponse.ok(done.message(), done);
    }

    // ---------------------------------------------------------------- Moderator

    @GetMapping("/api/moderator/tag-requests")
    public ApiResponse<RequestPage> queue(@RequestParam(required = false) Long tag,
                                          @RequestParam(required = false) String q,
                                          @RequestParam(defaultValue = "0") int page) {
        return ApiResponse.ok("OK", tags.queue(tag, q, page));
    }

    @GetMapping("/api/moderator/tag-requests/{id:\\d+}")
    public ApiResponse<RequestDetail> detail(@PathVariable long id) {
        return ApiResponse.ok("OK", tags.detail(id));
    }

    /**
     * The proof photo of a waiting request: never cached, shown inline (no download name), never sniffed. There is no
     * public address for it; it stops existing once the request is handled.
     */
    @GetMapping("/api/moderator/tag-requests/{id:\\d+}/photo")
    public ResponseEntity<byte[]> photo(@PathVariable long id) {
        TagService.Photo photo = tags.photo(id);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(photo.mimeType()))
                .cacheControl(CacheControl.noStore().cachePrivate())
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline")
                .header("X-Content-Type-Options", "nosniff")
                .body(photo.bytes());
    }

    @PostMapping("/api/moderator/tag-requests/{id:\\d+}/approve")
    public ApiResponse<Done> approve(@PathVariable long id, @AuthenticationPrincipal AppUserPrincipal me) {
        Done done = tags.approve(me.id(), id);
        return ApiResponse.ok(done.message(), done);
    }

    @PostMapping("/api/moderator/tag-requests/{id:\\d+}/reject")
    public ApiResponse<Done> reject(@PathVariable long id, @RequestBody(required = false) RejectForm body,
                                    @AuthenticationPrincipal AppUserPrincipal me) {
        Done done = tags.reject(me.id(), id, body == null ? null : body.reason());
        return ApiResponse.ok(done.message(), done);
    }
}
