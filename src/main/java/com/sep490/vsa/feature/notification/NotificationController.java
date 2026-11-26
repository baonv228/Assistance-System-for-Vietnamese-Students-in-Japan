package com.sep490.vsa.feature.notification;

import com.sep490.vsa.common.dto.ApiResponse;
import com.sep490.vsa.common.security.AppUserPrincipal;
import com.sep490.vsa.feature.notification.NotificationDtos.NotificationPage;
import com.sep490.vsa.feature.notification.NotificationDtos.NotificationSummary;
import com.sep490.vsa.feature.notification.NotificationDtos.ReadRequest;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * The signed-in user's own notifications: the bell (its panel in the site header) and the "Thông báo" page.
 * /api/me/** requires login.
 */
@RestController
@RequestMapping("/api/me/notifications")
public class NotificationController {

    private final NotificationService service;

    public NotificationController(NotificationService service) {
        this.service = service;
    }

    /** {@code unread=true}: the unread ones only (tab "Chưa đọc"). */
    @GetMapping
    public ApiResponse<NotificationPage> list(@AuthenticationPrincipal AppUserPrincipal me,
                                              @RequestParam(defaultValue = "0") int page,
                                              @RequestParam(defaultValue = "false") boolean unread) {
        return ApiResponse.ok("OK", service.list(me.id(), page, unread));
    }

    /** {@code after}: the newest id this tab has dealt with (pop-ups); {@code seen}: the newest the panel has shown. */
    @GetMapping("/summary")
    public ApiResponse<NotificationSummary> summary(@AuthenticationPrincipal AppUserPrincipal me,
                                                    @RequestParam(required = false) Long after,
                                                    @RequestParam(required = false) Long seen) {
        return ApiResponse.ok("OK", service.summary(me.id(), after, seen));
    }

    @PostMapping("/read")
    public ApiResponse<Map<String, Integer>> read(@AuthenticationPrincipal AppUserPrincipal me,
                                                  @RequestBody(required = false) ReadRequest request) {
        int unread = service.markRead(me.id(), request == null ? null : request.upToId());
        return ApiResponse.ok("OK", Map.of("unread", unread));
    }

    /** One notification clicked in the panel or on the page. */
    @PostMapping("/{id}/read")
    public ApiResponse<Map<String, Integer>> readOne(@AuthenticationPrincipal AppUserPrincipal me,
                                                     @PathVariable long id) {
        return ApiResponse.ok("OK", Map.of("unread", service.markOneRead(me.id(), id)));
    }
}
