package com.sep490.vsa.feature.guide;
import com.sep490.vsa.common.dto.ApiResponse;
import com.sep490.vsa.common.security.AppUserPrincipal;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.multipart.MultipartFile;
import java.util.List;
@RestController public class GuideController {
 private final GuideService service;
 private final GuideUploadService uploads;
 public GuideController(GuideService service, GuideUploadService uploads){this.service=service;this.uploads=uploads;}
 @GetMapping({"/api/guides","/api/moderator/guides"})
 public ApiResponse<List<GuideResponse>> list(@RequestParam(required=false) String category,@RequestParam(required=false) String q){return ApiResponse.ok("Danh sách cẩm nang",service.list(category,q));}
 @GetMapping({"/api/guides/topics","/api/moderator/guides/topics"})
 public ApiResponse<List<GuideService.Topic>> topics(){return ApiResponse.ok("Chủ đề cẩm nang",service.topics());}
 @GetMapping("/api/guides/browse")
 public ApiResponse<GuideService.BrowsePage> browse(@RequestParam(required=false) String category,
        @RequestParam(required=false) String q, @RequestParam(defaultValue="NEWEST") String sort,
        @RequestParam(defaultValue="0") int page, @RequestParam(defaultValue="12") int size) {
  return ApiResponse.ok("Danh sách cẩm nang công khai", service.browse(category, q, sort, page, size));
 }
 @GetMapping({"/api/guides/{id}","/api/moderator/guides/{id}"})
 public ApiResponse<GuideResponse> detail(@PathVariable long id){return ApiResponse.ok("Chi tiết cẩm nang",service.detail(id));}
 @PostMapping(value="/api/moderator/guides", consumes=MediaType.APPLICATION_JSON_VALUE) @ResponseStatus(HttpStatus.CREATED)
 public ApiResponse<GuideResponse> create(@Valid @RequestBody GuideRequest request,@AuthenticationPrincipal AppUserPrincipal me){return ApiResponse.ok("Đã lưu và xuất bản cẩm nang.",service.create(request,me));}
 @PostMapping(value="/api/moderator/guides", consumes=MediaType.MULTIPART_FORM_DATA_VALUE)
 @ResponseStatus(HttpStatus.CREATED)
 public ApiResponse<GuideResponse> upload(@Valid @RequestPart("guide") GuideRequest request,
        @RequestPart(value="images", required=false) List<MultipartFile> images,
        @AuthenticationPrincipal AppUserPrincipal me) {
  return ApiResponse.ok("Đã lưu và xuất bản cẩm nang.", uploads.create(request, images, me));
 }
}
