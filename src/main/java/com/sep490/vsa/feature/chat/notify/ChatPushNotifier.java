package com.sep490.vsa.feature.chat.notify;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sep490.vsa.feature.chat.ChatMessageSent;
import com.sep490.vsa.feature.chat.ChatViewers;
import com.sep490.vsa.feature.chat.notify.NotifyRepository.Account;
import com.sep490.vsa.feature.chat.notify.PushSubscriptionRepository.Subscription;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Web Push for a new chat message, sent on a background thread once the message is committed. Pushed to every browser
 * of the recipient when the recipient is an ACTIVE Member with push on ({@code chat_notify_settings}, no row = on), at
 * most once per recipient and conversation every {@code app.chat.notify.push-spacing-seconds} (30 s): a burst of
 * messages gives one notification, which the browser replaces by its tag anyway.
 * <p>
 * The payload names the writer only ("Tin nhắn mới từ Lan"), never the text: an encrypted message cannot be read here,
 * and a plain one must not travel through Google's or Apple's servers either. Failures are logged, never thrown.
 */
@Component
public class ChatPushNotifier {

    private static final Logger log = LoggerFactory.getLogger(ChatPushNotifier.class);
    static final String TITLE = "Senpai ơi";
    private static final int NAME_MAX = 60;

    private final NotifyRepository repository;
    private final PushSubscriptionRepository subscriptions;
    private final WebPushSender sender;
    private final ChatViewers viewers;
    private final ObjectMapper json;
    private final Clock clock;
    private final Duration spacing;

    public ChatPushNotifier(NotifyRepository repository, PushSubscriptionRepository subscriptions, WebPushSender sender,
                            ChatViewers viewers, ObjectMapper json, Clock clock,
                            @Value("${app.chat.notify.push-spacing-seconds:30}") long spacingSeconds) {
        this.repository = repository;
        this.subscriptions = subscriptions;
        this.sender = sender;
        this.viewers = viewers;
        this.json = json;
        this.clock = clock;
        this.spacing = Duration.ofSeconds(spacingSeconds);
    }

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onMessageSent(ChatMessageSent event) {
        try {
            push(event);
        } catch (RuntimeException e) {
            log.warn("Push for chat message {} failed: {}", event.messageId(), e.getMessage());
        }
    }

    void push(ChatMessageSent event) {
        Account recipient = repository.account(event.recipientId()).orElse(null);
        if (recipient == null || !recipient.member() || !repository.settings(recipient.id()).pushEnabled()) {
            return;
        }
        // Reading the conversation right now: the page shows the message within seconds. Not pushing beats a push the
        // service worker would hide (Safari cancels subscriptions that show nothing)
        if (viewers.viewing(event.conversationId(), recipient.id())) {
            return;
        }
        List<Subscription> browsers = subscriptions.ofUser(recipient.id());
        if (browsers.isEmpty()) {
            return;
        }
        Instant now = clock.instant();
        if (!repository.claimPush(event.conversationId(), recipient.id(), now, now.minus(spacing))) {
            return;
        }
        byte[] payload = payload(event);
        for (Subscription browser : browsers) {
            try {
                sender.send(browser, payload);
            } catch (RuntimeException e) {
                log.warn("Push to subscription {} failed: {}", browser.id(), e.getMessage());
            }
        }
    }

    /** {@code { title, body, url, tag }}, read by {@code static/sw.js}. */
    byte[] payload(ChatMessageSent event) {
        String name = event.senderName() == null || event.senderName().isBlank() ? "một thành viên"
                : event.senderName().strip();
        if (name.length() > NAME_MAX) {
            name = name.substring(0, NAME_MAX - 1) + "…";
        }
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("title", TITLE);
        message.put("body", event.request() ? name + " muốn nhắn tin với bạn." : "Tin nhắn mới từ " + name);
        message.put("url", "/messages/" + event.conversationId());
        message.put("tag", "chat-" + event.conversationId());
        try {
            return json.writeValueAsBytes(message);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }
}
