package com.sep490.vsa.feature.chat.notify;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** JSON of {@code /api/me/chats/notify/**}. */
public final class NotifyDtos {

    private NotifyDtos() {
    }

    /** The member's choice: e-mail digest of unread messages, Web Push on the browsers they turned it on. */
    public record Settings(boolean emailEnabled, boolean pushEnabled) {
    }

    /** A null field keeps the current value. */
    public record SettingsUpdate(Boolean emailEnabled, Boolean pushEnabled) {
    }

    /** The server's VAPID public key: base64url of the raw P-256 point, for {@code pushManager.subscribe}. */
    public record PushKey(String publicKey) {
    }

    /** {@code PushSubscription.toJSON()} of the browser ({@code expirationTime} is ignored). */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record SubscriptionRequest(String endpoint, Keys keys) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Keys(String p256dh, String auth) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record EndpointRequest(String endpoint) {
    }
}
