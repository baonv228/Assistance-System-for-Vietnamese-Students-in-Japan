package com.sep490.vsa.feature.legal;

import com.sep490.vsa.common.dto.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Locale;

@RestController
@RequestMapping("/api/legal")
public class LegalController {

    private final LegalService legalService;

    public LegalController(LegalService legalService) {
        this.legalService = legalService;
    }

    /** GET /api/legal/tos, /api/legal/privacy, /api/legal/community */
    @GetMapping("/{type}")
    public ApiResponse<LegalDocumentResponse> current(@PathVariable String type) {
        LegalDocument doc = legalService.current(type.toUpperCase(Locale.ROOT));
        return ApiResponse.ok("OK", new LegalDocumentResponse(doc.getDocType(), doc.getVersion(), doc.getContent()));
    }

    public record LegalDocumentResponse(String type, String version, String content) {
    }
}
