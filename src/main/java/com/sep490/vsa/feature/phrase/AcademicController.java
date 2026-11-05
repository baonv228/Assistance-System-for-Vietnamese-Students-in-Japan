package com.sep490.vsa.feature.phrase;

import com.sep490.vsa.common.dto.ApiResponse;
import com.sep490.vsa.common.security.AppUserPrincipal;
import com.sep490.vsa.feature.phrase.AcademicDtos.LibraryPage;
import com.sep490.vsa.feature.phrase.AcademicDtos.LibraryRow;
import com.sep490.vsa.feature.phrase.AcademicDtos.Options;
import com.sep490.vsa.feature.phrase.AcademicDtos.Overview;
import com.sep490.vsa.feature.phrase.AcademicDtos.PhraseRequest;
import com.sep490.vsa.feature.phrase.AcademicDtos.ReportCase;
import com.sep490.vsa.feature.phrase.AcademicDtos.SaveResult;
import com.sep490.vsa.feature.phrase.AcademicDtos.TopicRequest;
import com.sep490.vsa.feature.phrase.AcademicDtos.TopicRow;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** The Academic Manager's API; /api/academic/** needs the ACADEMIC_MANAGER role (SecurityConfig). */
@RestController
@RequestMapping("/api/academic")
public class AcademicController {

    private final AcademicService service;

    public AcademicController(AcademicService service) {
        this.service = service;
    }

    @GetMapping("/overview")
    public ApiResponse<Overview> overview(@AuthenticationPrincipal AppUserPrincipal me) {
        return ApiResponse.ok("OK", service.overview(me.id()));
    }

    // ---------------------------------------------------------------- learning topics

    @GetMapping("/topics")
    public ApiResponse<List<TopicRow>> topics() {
        return ApiResponse.ok("OK", service.topics());
    }

    @PostMapping("/topics")
    public ApiResponse<Void> createTopic(@RequestBody TopicRequest request, @AuthenticationPrincipal AppUserPrincipal me) {
        service.createTopic(me.id(), request);
        return ApiResponse.ok("Đã thêm chủ đề học.", null);
    }

    @PutMapping("/topics/{id:[A-Za-z0-9_-]{1,20}}")
    public ApiResponse<Void> updateTopic(@PathVariable String id, @RequestBody TopicRequest request,
                                             @AuthenticationPrincipal AppUserPrincipal me) {
        service.updateTopic(me.id(), id, request);
        return ApiResponse.ok("Đã lưu thay đổi.", null);
    }

    @DeleteMapping("/topics/{id:[A-Za-z0-9_-]{1,20}}")
    public ApiResponse<Void> deleteTopic(@PathVariable String id, @AuthenticationPrincipal AppUserPrincipal me) {
        service.deleteTopic(me.id(), id);
        return ApiResponse.ok("Đã xóa chủ đề học. Mẫu câu vẫn giữ nguyên.", null);
    }

    // ---------------------------------------------------------------- library

    @GetMapping("/options")
    public ApiResponse<Options> options() {
        return ApiResponse.ok("OK", service.options());
    }

    @GetMapping("/phrases")
    public ApiResponse<LibraryPage> library(@RequestParam(required = false) String category,
                                            @RequestParam(required = false) String topic,
                                            @RequestParam(required = false) String kind,
                                            @RequestParam(required = false) String q,
                                            @RequestParam(defaultValue = "0") int page) {
        return ApiResponse.ok("OK", service.library(category, topic, kind, q, page));
    }

    @GetMapping("/phrases/{id:\\d+}")
    public ApiResponse<LibraryRow> phrase(@PathVariable long id) {
        return ApiResponse.ok("OK", service.phrase(id));
    }

    @PostMapping("/phrases")
    public ApiResponse<SaveResult> createPhrase(@RequestBody PhraseRequest request,
                                                @AuthenticationPrincipal AppUserPrincipal me) {
        SaveResult result = service.createPhrase(me.id(), request);
        return ApiResponse.ok(result.message(), result);
    }

    @PutMapping("/phrases/{id:\\d+}")
    public ApiResponse<SaveResult> updatePhrase(@PathVariable long id, @RequestBody PhraseRequest request,
                                                @AuthenticationPrincipal AppUserPrincipal me) {
        SaveResult result = service.updatePhrase(me.id(), id, request);
        return ApiResponse.ok(result.message(), result);
    }

    @DeleteMapping("/phrases/{id:\\d+}")
    public ApiResponse<Void> deletePhrase(@PathVariable long id, @AuthenticationPrincipal AppUserPrincipal me) {
        service.deletePhrase(me.id(), id);
        return ApiResponse.ok("Đã xóa mẫu câu.", null);
    }

    // ---------------------------------------------------------------- reports

    /** Open reports on phrases (the only kind the Academic Manager handles now that practice sentences are gone). */
    @GetMapping("/reports")
    public ApiResponse<List<ReportCase>> reports() {
        return ApiResponse.ok("OK", service.reports());
    }

    @PostMapping("/reports/{caseId:\\d+}/dismiss")
    public ApiResponse<Void> dismiss(@PathVariable long caseId, @AuthenticationPrincipal AppUserPrincipal me) {
        service.dismissReport(me.id(), caseId);
        return ApiResponse.ok("Đã đóng báo cáo. Nội dung được giữ nguyên.", null);
    }
}
