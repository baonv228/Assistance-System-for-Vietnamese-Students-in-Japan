package com.sep490.vsa.feature.chat.notify;

import com.sep490.vsa.common.dto.ApiResponse;
import com.sep490.vsa.common.security.AppUserPrincipal;
import com.sep490.vsa.feature.chat.notify.NotifyDtos.EndpointRequest;
import com.sep490.vsa.feature.chat.notify.NotifyDtos.PushKey;
import com.sep490.vsa.feature.chat.notify.NotifyDtos.Settings;
import com.sep490.vsa.feature.chat.notify.NotifyDtos.SettingsUpdate;
import com.sep490.vsa.feature.chat.notify.NotifyDtos.SubscriptionRequest;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * New-message notifications of "Tin nhắn": the member's choices and the browsers that receive Web Push.
 * /api/me/** requires login; {@link NotifyService} allows Members only.
 */
@RestController
@RequestMapping("/api/me/chats/notify")
public class NotifyController {

    private final NotifyService service;

    public NotifyController(NotifyService service) {
        this.service = service;
    }

    @GetMapping("/settings")
    public ApiResponse<Settings> settings(@AuthenticationPrincipal AppUserPrincipal me) {
        return ApiResponse.ok("OK", service.settings(me));
    }

    /** {@code { emailEnabled?, pushEnabled? }}: a missing field keeps its value. */
    @PutMapping("/settings")
    public ApiResponse<Settings> updateSettings(@AuthenticationPrincipal AppUserPrincipal me,
                                                @RequestBody SettingsUpdate body) {
        return ApiResponse.ok("Đã lưu cài đặt thông báo.", service.updateSettings(me, body));
    }

    @GetMapping("/push-key")
    public ApiResponse<PushKey> pushKey(@AuthenticationPrincipal AppUserPrincipal me) {
        return ApiResponse.ok("OK", service.pushKey(me));
    }

    /** {@code PushSubscription.toJSON()}: {@code { endpoint, keys: { p256dh, auth } }}. */
    @PostMapping("/push-subscriptions")
    public ApiResponse<Void> subscribe(@AuthenticationPrincipal AppUserPrincipal me,
                                       @RequestBody SubscriptionRequest body) {
        service.subscribe(me, body);
        return ApiResponse.ok("Đã bật thông báo trên trình duyệt này.", null);
    }

    @DeleteMapping("/push-subscriptions")
    public ApiResponse<Void> unsubscribe(@AuthenticationPrincipal AppUserPrincipal me,
                                         @RequestBody EndpointRequest body) {
        service.unsubscribe(me, body);
        return ApiResponse.ok("Đã tắt thông báo trên trình duyệt này.", null);
    }
}
