package com.sep490.vsa.feature.chat.notify;

import com.sep490.vsa.common.exception.ApiException;
import com.sep490.vsa.common.security.AppUserPrincipal;
import com.sep490.vsa.feature.chat.notify.NotifyDtos.EndpointRequest;
import com.sep490.vsa.feature.chat.notify.NotifyDtos.PushKey;
import com.sep490.vsa.feature.chat.notify.NotifyDtos.Settings;
import com.sep490.vsa.feature.chat.notify.NotifyDtos.SettingsUpdate;
import com.sep490.vsa.feature.chat.notify.NotifyDtos.SubscriptionRequest;
import com.sep490.vsa.feature.chat.notify.NotifyRepository.Account;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.List;

/**
 * The member's notification choices and their browsers' push subscriptions. Members only (role USER, status ACTIVE),
 * like "Tin nhắn" itself: staff get MSG18, guests 401.
 * <p>
 * A subscription is checked before it is stored: the endpoint must be a known push service ({@link PushEndpointPolicy},
 * the server later POSTs to it), p256dh a point of P-256 and auth 16 bytes. A member keeps at most
 * {@value #MAX_SUBSCRIPTIONS} browsers; a new one drops the oldest.
 */
@Service
@Transactional(isolation = Isolation.READ_COMMITTED)
public class NotifyService {

    static final String NO_PERMISSION = "Bạn không có quyền thực hiện thao tác này.";            // MSG18
    static final String BAD_ENDPOINT = "Trình duyệt này gửi địa chỉ nhận thông báo không hợp lệ.";
    static final String BAD_KEY = "Khóa nhận thông báo của trình duyệt không hợp lệ.";
    static final String BAD_AUTH = "Mã xác thực nhận thông báo của trình duyệt không hợp lệ.";
    /** "cần chốt": browsers (phones, computers) one member may receive push notifications on. */
    static final int MAX_SUBSCRIPTIONS = 10;
    private static final int AUTH_LENGTH = 16;

    private final NotifyRepository repository;
    private final PushSubscriptionRepository subscriptions;
    private final WebPushKeyStore keys;
    private final PushEndpointPolicy policy;
    private final Clock clock;

    public NotifyService(NotifyRepository repository, PushSubscriptionRepository subscriptions, WebPushKeyStore keys,
                         PushEndpointPolicy policy, Clock clock) {
        this.repository = repository;
        this.subscriptions = subscriptions;
        this.keys = keys;
        this.policy = policy;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public Settings settings(AppUserPrincipal me) {
        return repository.settings(member(me).id());
    }

    public Settings updateSettings(AppUserPrincipal me, SettingsUpdate body) {
        long userId = member(me).id();
        if (body != null && (body.emailEnabled() != null || body.pushEnabled() != null)) {
            repository.saveSettings(userId, body.emailEnabled(), body.pushEnabled(), clock.instant());
        }
        return repository.settings(userId);
    }

    /**
     * Made the first time any member asks (see {@link WebPushKeyStore}); outside a transaction, so the new row is
     * committed at once and a request racing this one reads it.
     */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public PushKey pushKey(AppUserPrincipal me) {
        member(me);
        return new PushKey(keys.keys().publicKeyBase64Url());
    }

    public void subscribe(AppUserPrincipal me, SubscriptionRequest body) {
        long userId = member(me).id();
        String endpoint = body == null || body.endpoint() == null ? null : body.endpoint().strip();
        if (policy.check(endpoint).isEmpty()) {
            throw ApiException.badRequest(BAD_ENDPOINT);
        }
        String p256dh = body.keys() == null ? null : body.keys().p256dh();
        String auth = body.keys() == null ? null : body.keys().auth();
        byte[] key;
        try {
            key = P256.decode(p256dh);
            P256.publicKey(key);
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest(BAD_KEY);
        }
        byte[] secret;
        try {
            secret = P256.decode(auth);
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest(BAD_AUTH);
        }
        if (secret.length != AUTH_LENGTH) {
            throw ApiException.badRequest(BAD_AUTH);
        }
        repository.lockSettings(userId);
        subscriptions.save(userId, endpoint, P256.encode(key), P256.encode(secret), clock.instant());
        List<Long> ids = subscriptions.idsNewestFirst(userId);
        for (Long id : ids.subList(Math.min(ids.size(), MAX_SUBSCRIPTIONS), ids.size())) {
            subscriptions.delete(id);
        }
    }

    /** This browser no longer wants push notifications (nothing happens for an endpoint the member does not have). */
    public void unsubscribe(AppUserPrincipal me, EndpointRequest body) {
        long userId = member(me).id();
        String endpoint = body == null || body.endpoint() == null ? "" : body.endpoint().strip();
        if (endpoint.isEmpty() || endpoint.length() > PushEndpointPolicy.MAX_LENGTH) {
            throw ApiException.badRequest(BAD_ENDPOINT);
        }
        subscriptions.deleteOwn(userId, endpoint);
    }

    /** The signed-in Member. Guests get 401; staff roles and locked accounts get MSG18. */
    private Account member(AppUserPrincipal me) {
        if (me == null) {
            throw ApiException.unauthorized("Bạn chưa đăng nhập.");
        }
        Account account = repository.account(me.id())
                .orElseThrow(() -> ApiException.unauthorized("Bạn chưa đăng nhập."));
        if (!account.member()) {
            throw new ApiException(HttpStatus.FORBIDDEN, NO_PERMISSION);
        }
        return account;
    }
}
