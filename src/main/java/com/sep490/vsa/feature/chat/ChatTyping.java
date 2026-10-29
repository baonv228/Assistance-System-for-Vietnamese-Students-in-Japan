package com.sep490.vsa.feature.chat;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * "đang soạn tin": who pinged {@code POST /api/me/chats/{id}/typing} in the last {@link #TTL}. Kept in memory only (one
 * server; nothing worth a table), so a restart simply forgets who was typing. The page pings at most every 3 s while
 * the member types, and a sent message ends the ping at once.
 */
@Component
public class ChatTyping {

    /** "cần chốt": how long a ping counts. */
    static final Duration TTL = Duration.ofSeconds(6);
    /** Expired pings are dropped once the map holds this many, so it never grows without bound. */
    private static final int PRUNE_AT = 5000;

    private record Key(long conversationId, long userId) {
    }

    private final Map<Key, Instant> pings = new ConcurrentHashMap<>();
    private volatile Clock clock;

    @Autowired
    public ChatTyping(Clock clock) {
        this.clock = clock;
    }

    void ping(long conversationId, long userId) {
        Instant now = clock.instant();
        if (pings.size() >= PRUNE_AT) {
            pings.values().removeIf(at -> !at.plus(TTL).isAfter(now));
        }
        pings.put(new Key(conversationId, userId), now);
    }

    /** The member stopped typing (they sent the message). */
    void stop(long conversationId, long userId) {
        pings.remove(new Key(conversationId, userId));
    }

    boolean typing(long conversationId, long userId) {
        Instant at = pings.get(new Key(conversationId, userId));
        return at != null && at.plus(TTL).isAfter(clock.instant());
    }

    /** Tests: the clock pings are read with, so a test can move past {@link #TTL} without waiting. */
    void useClock(Clock clock) {
        this.clock = clock;
    }
}
