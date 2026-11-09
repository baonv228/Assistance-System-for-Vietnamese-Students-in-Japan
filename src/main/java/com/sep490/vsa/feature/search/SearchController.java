package com.sep490.vsa.feature.search;

import com.sep490.vsa.common.dto.ApiResponse;
import com.sep490.vsa.common.security.AppUserPrincipal;
import com.sep490.vsa.feature.search.SearchDtos.SiteSearch;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** "Tìm kiếm" on the Home and the page /search (public, like every list it searches). */
@RestController
public class SearchController {

    private final SearchService service;

    public SearchController(SearchService service) {
        this.service = service;
    }

    /** GET /api/search?q= : places, posts, questions, guides, events, phrases and FAQ entries matching {@code q}. */
    @GetMapping("/api/search")
    public ApiResponse<SiteSearch> search(@RequestParam(required = false) String q,
                                          @AuthenticationPrincipal AppUserPrincipal me) {
        return ApiResponse.ok("OK", service.search(me, q));
    }
}
