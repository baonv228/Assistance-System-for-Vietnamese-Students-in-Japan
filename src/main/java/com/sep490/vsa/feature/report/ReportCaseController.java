package com.sep490.vsa.feature.report;

import com.sep490.vsa.common.dto.ApiResponse;
import com.sep490.vsa.common.security.AppUserPrincipal;
import com.sep490.vsa.feature.report.ReportCaseDtos.CaseDetail;
import com.sep490.vsa.feature.report.ReportCaseDtos.CasePage;
import com.sep490.vsa.feature.report.ReportCaseDtos.Done;
import com.sep490.vsa.feature.report.ReportCaseDtos.RemoveRequest;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** "Báo cáo nội dung" (UC-M03, UC-M04). /api/moderator/** needs the MODERATOR role (see SecurityConfig). */
@RestController
@RequestMapping("/api/moderator/reports")
public class ReportCaseController {

    private final ReportCaseService service;

    public ReportCaseController(ReportCaseService service) {
        this.service = service;
    }

    /** @param tab URGENT (default), NORMAL or REMOVED; kind POST, QA, ANSWER, COMMENT, REVIEW or MESSAGE */
    @GetMapping
    public ApiResponse<CasePage> queue(@RequestParam(defaultValue = "URGENT") String tab,
                                       @RequestParam(required = false) String kind,
                                       @RequestParam(required = false) Long reason,
                                       @RequestParam(defaultValue = "0") int page) {
        return ApiResponse.ok("OK", service.queue(tab, kind, reason, page));
    }

    @GetMapping("/{id}")
    public ApiResponse<CaseDetail> detail(@PathVariable long id) {
        return ApiResponse.ok("OK", service.detail(id));
    }

    @PostMapping("/{id}/remove")
    public ApiResponse<Done> remove(@AuthenticationPrincipal AppUserPrincipal me, @PathVariable long id,
                                    @RequestBody(required = false) RemoveRequest body) {
        return ApiResponse.ok("OK", service.remove(me.id(), id, body));
    }

    @PostMapping("/{id}/restore")
    public ApiResponse<Done> restore(@AuthenticationPrincipal AppUserPrincipal me, @PathVariable long id) {
        return ApiResponse.ok("OK", service.restore(me.id(), id));
    }

    @PostMapping("/{id}/dismiss")
    public ApiResponse<Done> dismiss(@AuthenticationPrincipal AppUserPrincipal me, @PathVariable long id) {
        return ApiResponse.ok("OK", service.dismiss(me.id(), id));
    }
}
