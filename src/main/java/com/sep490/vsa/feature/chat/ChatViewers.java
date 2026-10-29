package com.sep490.vsa.feature.chat;

import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Who is reading a conversation right now: the chat page asks for its updates ({@code GET /{id}/updates}) every 4 s
 * while it is shown. A new message is not pushed to a member who is reading that conversation (their page shows it
 * within seconds), so the service worker can show every push it gets — Safari takes a push that shows nothing as
 * "silent" and cancels the subscription after a few. In memory only, like {@link ChatTyping}.
 */
@Component
public class ChatViewers {

    /** "cần chốt": an updates call this recent means the member is looking at the conversation. */
    static final Duration FRESH = Duration.ofSeconds(10);
    /** Old entries are dropped once the map holds this many, so it never grows without bound. */
    private static final int PRUNE_AT = 5000;

    private record Key(long conversationId, long userId) {
    }

    private final Map<Key, Instant> seen = new ConcurrentHashMap<>();
    private final Clock clock;

    public ChatViewers(Clock clock) {
        this.clock = clock;
    }

    void seen(long conversationId, long userId) {
        Instant now = clock.instant();
        if (seen.size() >= PRUNE_AT) {
            seen.values().removeIf(at -> !at.plus(FRESH).isAfter(now));
        }
        seen.put(new Key(conversationId, userId), now);
    }

    /** True while the member's page asked for this conversation's updates within {@link #FRESH}. */
    public boolean viewing(long conversationId, long userId) {
        Instant at = seen.get(new Key(conversationId, userId));
        return at != null && at.plus(FRESH).isAfter(clock.instant());
    }
}
