package com.sep490.vsa.feature.chat.notify;

import com.sep490.vsa.common.http.WindowsTrust;
import com.sep490.vsa.feature.chat.notify.PushSubscriptionRepository.Subscription;
import com.sep490.vsa.feature.chat.notify.WebPushKeyStore.VapidKeys;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/**
 * Sends one Web Push message to one browser: the payload encrypted for it ({@link WebPushEncryption}), a VAPID JWT
 * ({@link Vapid}), POSTed to the subscription's endpoint, which must be a known push service
 * ({@link PushEndpointPolicy}; anything else is refused before any request and the subscription deleted).
 * <p>
 * The push service answers 201 when it took the message. 404/410: the browser unsubscribed or the subscription
 * expired, the row is deleted. Anything else (429, 5xx, 4xx, no answer) counts as a failure; after
 * {@value #MAX_FAILURES} failures in a row the row is deleted, a success starts the count again. Redirects are never
 * followed (the request factory follows them for GET only).
 */
@Component
public class WebPushSender {

    private static final Logger log = LoggerFactory.getLogger(WebPushSender.class);

    public enum Result { SENT, GONE, FAILED, DROPPED, REFUSED }

    /** Failures in a row after which a subscription is dropped. */
    static final int MAX_FAILURES = 5;
    /** "cần chốt": how long the push service keeps a message for a browser that is offline (the e-mail comes later). */
    static final Duration TTL = Duration.ofDays(1);
    static final String DEFAULT_SUBJECT = "https://github.com/baonv228/Assistance-System-for-Vietnamese-Students-in-Japan";

    private final PushSubscriptionRepository subscriptions;
    private final WebPushKeyStore keys;
    private final PushEndpointPolicy policy;
    private final String subject;
    private final Clock clock;
    private final RestClient client;

    public WebPushSender(PushSubscriptionRepository subscriptions, WebPushKeyStore keys, PushEndpointPolicy policy,
                         @Value("${app.push.subject:" + DEFAULT_SUBJECT + "}") String subject, Clock clock) {
        this.subscriptions = subscriptions;
        this.keys = keys;
        this.policy = policy;
        this.subject = subject;
        this.clock = clock;
        this.client = RestClient.builder().requestFactory(WindowsTrust.requestFactory(5_000, 10_000)).build();
    }

    /** The outcome, with the subscription row updated; a failed request is a result, only a database error throws. */
    public Result send(Subscription subscription, byte[] payload) {
        Optional<URI> endpoint = policy.check(subscription.endpoint());
        if (endpoint.isEmpty()) {
            log.warn("Push subscription {} has an endpoint that is not an allowed push service: deleted",
                    subscription.id());
            subscriptions.delete(subscription.id());
            return Result.REFUSED;
        }
        URI uri = endpoint.get();
        byte[] body;
        try {
            body = WebPushEncryption.encrypt(payload, P256.decode(subscription.p256dh()),
                    P256.decode(subscription.auth()));
        } catch (IllegalArgumentException e) {
            log.warn("Push subscription {} has unusable keys ({}): deleted", subscription.id(), e.getMessage());
            subscriptions.delete(subscription.id());
            return Result.REFUSED;
        }
        int status;
        try {
            VapidKeys vapid = keys.keys();
            Instant now = clock.instant();
            String jwt = Vapid.jwt(Vapid.audience(uri), now.plus(Vapid.LIFETIME), subject, vapid.privateKey());
            status = client.post().uri(uri)
                    .header("TTL", String.valueOf(TTL.toSeconds()))
                    .header("Content-Encoding", "aes128gcm")
                    .header("Urgency", "high")
                    .header(HttpHeaders.AUTHORIZATION, Vapid.authorization(jwt, vapid.publicRaw()))
                    .contentType(MediaType.APPLICATION_OCTET_STREAM)
                    .body(body)
                    .exchange((request, response) -> response.getStatusCode().value());
        } catch (RestClientException | IllegalStateException e) {
            log.warn("Push to {} failed: {}", uri.getHost(), e.getMessage());
            status = -1;
        }
        if (status >= 200 && status < 300) {
            subscriptions.markSent(subscription.id(), clock.instant());
            return Result.SENT;
        }
        if (status == 404 || status == 410) {
            subscriptions.delete(subscription.id());
            return Result.GONE;
        }
        if (status > 0) {
            log.warn("Push to {} answered {}", uri.getHost(), status);
        }
        if (subscriptions.recordFailure(subscription.id()) >= MAX_FAILURES) {
            subscriptions.delete(subscription.id());
            return Result.DROPPED;
        }
        return Result.FAILED;
    }
}
