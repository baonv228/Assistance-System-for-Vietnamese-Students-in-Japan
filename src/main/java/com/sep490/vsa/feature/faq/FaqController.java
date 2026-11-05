package com.sep490.vsa.feature.faq;
import com.sep490.vsa.common.dto.ApiResponse;
import com.sep490.vsa.common.security.AppUserPrincipal;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.http.HttpStatus;
import java.util.List;
import static com.sep490.vsa.feature.faq.FaqDtos.*;
@RestController
public class FaqController {
 private final FaqService service;
 public FaqController(FaqService service){this.service=service;}
 @GetMapping("/api/faqs") public ApiResponse<Page<Entry>> list(@RequestParam(required=false) String q,@RequestParam(required=false) String topic,@RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="20") int size){return ApiResponse.ok("FAQ",service.list(q,topic,"PUBLISHED",page,size,false));}
 @GetMapping("/api/faqs/topics") public ApiResponse<List<Topic>> topics(@RequestParam(required=false) String q){return ApiResponse.ok("Chủ đề FAQ",service.topics(q,false));}
 @GetMapping("/api/faqs/{id}") public ApiResponse<Entry> detail(@PathVariable long id){return ApiResponse.ok("FAQ",service.detail(id,false));}
 @GetMapping("/api/moderator/faqs") public ApiResponse<Page<Entry>> manage(@RequestParam(required=false) String q,@RequestParam(required=false) String topic,@RequestParam(defaultValue="ALL") String status,@RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="20") int size){return ApiResponse.ok("FAQ",service.list(q,topic,status,page,size,true));}
 @GetMapping("/api/moderator/faqs/topics") public ApiResponse<List<Topic>> manageTopics(){return ApiResponse.ok("Chủ đề FAQ",service.topics(null,true));}
 @GetMapping("/api/moderator/faqs/suggestions") public ApiResponse<Page<Suggestion>> suggestions(@RequestParam(required=false) String q,@RequestParam(defaultValue="false") boolean processed,@RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="20") int size){return ApiResponse.ok("Đề xuất FAQ",service.suggestions(q,processed,page,size));}
 @GetMapping("/api/moderator/faqs/{id}") public ApiResponse<Entry> edit(@PathVariable long id){return ApiResponse.ok("FAQ",service.detail(id,true));}
 @PostMapping("/api/moderator/faqs") @ResponseStatus(HttpStatus.CREATED) public ApiResponse<Entry> create(@Valid @RequestBody Write request,@AuthenticationPrincipal AppUserPrincipal me){return ApiResponse.ok("Đã lưu FAQ",service.save(null,request,me));}
 @PutMapping("/api/moderator/faqs/{id}") public ApiResponse<Entry> update(@PathVariable long id,@Valid @RequestBody Write request,@AuthenticationPrincipal AppUserPrincipal me){return ApiResponse.ok("Đã cập nhật FAQ",service.save(id,request,me));}
 @PutMapping("/api/moderator/faqs/{id}/visibility") public ApiResponse<Entry> visibility(@PathVariable long id,@RequestBody Visibility request,@AuthenticationPrincipal AppUserPrincipal me){return ApiResponse.ok("Đã cập nhật trạng thái",service.visibility(id,request.published(),me));}
}
