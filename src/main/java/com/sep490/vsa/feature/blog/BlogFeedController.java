package com.sep490.vsa.feature.blog;

import com.sep490.vsa.common.dto.ApiResponse;
import com.sep490.vsa.common.security.AppUserPrincipal;
import com.sep490.vsa.feature.blog.BlogDtos.BlogPage;
import com.sep490.vsa.feature.blog.BlogDtos.Overview;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Blog Feed (UC-09), posts only (questions are listed by feature/qa at /api/questions). Both endpoints are public
 * GETs; a signed-in author also sees their own posts in PROCESSING.
 */
@RestController
@RequestMapping("/api/blogs")
public class BlogFeedController {

    private final BlogFeedService service;

    public BlogFeedController(BlogFeedService service) {
        this.service = service;
    }

    /**
     * GET /api/blogs?q=&topicIds=1,2&sort=NEWEST|LIKES|COMMENTS&page=0&size=10
     * ({@code page} starts at 0).
     */
    @GetMapping
    public ApiResponse<BlogPage> list(@RequestParam(required = false) String q,
                                      @RequestParam(required = false) List<Long> topicIds,
                                      @RequestParam(required = false) String sort,
                                      @RequestParam(required = false) Integer page,
                                      @RequestParam(required = false) Integer size,
                                      @AuthenticationPrincipal AppUserPrincipal me) {
        BlogFeedQuery query = BlogFeedQuery.of(q, topicIds, sort, page, size, me == null ? null : me.id());
        return ApiResponse.ok("OK", service.list(query));
    }

    /** Featured posts, the topic list for the filter, and the side statistics. */
    @GetMapping("/overview")
    public ApiResponse<Overview> overview() {
        return ApiResponse.ok("OK", service.overview());
    }
}
