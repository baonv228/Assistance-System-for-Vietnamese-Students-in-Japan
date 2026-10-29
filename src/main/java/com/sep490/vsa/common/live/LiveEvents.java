package com.sep490.vsa.common.live;

import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Live updates for the pages a signed-in user has open, over Server-Sent Events ({@code GET /api/me/live},
 * {@link LiveController}): the server says at once that something changed and the page asks for it with the usual
 * API, instead of finding out on its next poll. An event carries ids and a kind only, never a text, so it tells the
 * page nothing it could not ask for itself.
 * <ul>
 *   <li>{@code chat} {@code {conversationId, kind, messageId?}} with kind {@code message}, {@code changed} (edited,
 *       recalled, reacted to), {@code read}, {@code typing} or {@code status} (feature/chat);</li>
 *   <li>{@code notification} {@code {}}: a new row in {@code notifications} (feature/notification).</li>
 * </ul>
 * Kept in memory, so it works on one server only (enough for the expected 100–200 users; several servers would need a
 * shared broker, "cần chốt"). An event sent inside a transaction goes out after the commit, and never for a rollback.
 * Sending runs on two threads of its own, so a slow connection never holds up the request that caused the event.
 */
@Component
public class LiveEvents {

    /** A stream ends after this long and the browser opens a new one, which checks the session again ("cần chốt"). */
    static final long STREAM_MS = 5 * 60_000L;
    /** The browser waits this long before opening a new stream after one ended or broke. */
    static final long RETRY_MS = 3000;
    /** Open streams kept per user (tabs, phone and computer); the oldest is closed when one more opens. */
    static final int STREAMS_PER_USER = 5;
    /** A comment line this often keeps proxies and tunnels (Cloudflare, Pinggy, nginx) from closing a quiet stream. */
    static final long HEARTBEAT_MS = 25_000;

    private static final Logger log = LoggerFactory.getLogger(LiveEvents.class);

    private final Map<Long, List<SseEmitter>> streams = new ConcurrentHashMap<>();
    private final ExecutorService sender = Executors.newFixedThreadPool(2, daemonThreads());

    /** A new stream for this user; the first event ("ready") goes out at once so proxies pass the stream on. */
    public SseEmitter open(long userId) {
        SseEmitter emitter = new SseEmitter(STREAM_MS);
        List<SseEmitter> extra = new ArrayList<>();
        // in one step with forget(), which drops the list once it is empty: the new stream never lands in a dropped list
        streams.compute(userId, (id, mine) -> {
            List<SseEmitter> list = mine == null ? new CopyOnWriteArrayList<>() : mine;
            list.add(emitter);
            while (list.size() > STREAMS_PER_USER) {
                extra.add(list.remove(0));
            }
            return list;
        });
        extra.forEach(SseEmitter::complete);
        Runnable forget = () -> forget(userId, emitter);
        emitter.onCompletion(forget);
        emitter.onTimeout(emitter::complete);
        emitter.onError(error -> forget.run());
        try {
            emitter.send(SseEmitter.event().name("ready").reconnectTime(RETRY_MS).data("{}"));
        } catch (IOException | IllegalStateException e) {
            emitter.completeWithError(e);
        }
        return emitter;
    }

    /**
     * Tells every open page of the user that {@code type} happened; after the commit when called inside a transaction.
     * {@code data} holds ids and kinds only.
     */
    public void publish(long userId, String type, Map<String, ?> data) {
        afterCommit(() -> dispatch(userId, type, data));
    }

    /** Closes the user's streams (account locked or deleted); after the commit when called inside a transaction. */
    public void close(long userId) {
        afterCommit(() -> {
            List<SseEmitter> mine = streams.remove(userId);
            if (mine != null) {
                mine.forEach(SseEmitter::complete);
            }
        });
    }

    /** Number of open streams of the user (tests). */
    int openStreams(long userId) {
        List<SseEmitter> mine = streams.get(userId);
        return mine == null ? 0 : mine.size();
    }

    @Scheduled(fixedDelay = HEARTBEAT_MS, initialDelay = HEARTBEAT_MS)
    void heartbeat() {
        streams.forEach((userId, mine) -> mine.forEach(emitter -> submit(() -> {
            try {
                emitter.send(SseEmitter.event().comment("ping"));
            } catch (IOException | IllegalStateException e) {
                drop(userId, emitter);
            }
        })));
    }

    @PreDestroy
    void shutdown() {
        sender.shutdownNow();
        streams.values().forEach(mine -> mine.forEach(SseEmitter::complete));
        streams.clear();
    }

    private void dispatch(long userId, String type, Map<String, ?> data) {
        List<SseEmitter> mine = streams.get(userId);
        if (mine == null || mine.isEmpty()) {
            return;
        }
        for (SseEmitter emitter : mine) {
            submit(() -> {
                try {
                    emitter.send(SseEmitter.event().name(type).data(data, MediaType.APPLICATION_JSON));
                } catch (IOException | IllegalStateException e) {
                    drop(userId, emitter);                  // the page is gone; its browser opens a new stream
                }
            });
        }
    }

    private void submit(Runnable task) {
        try {
            sender.execute(task);
        } catch (RejectedExecutionException e) {
            log.debug("Live event not sent while shutting down");
        }
    }

    private void drop(long userId, SseEmitter emitter) {
        forget(userId, emitter);
        try {
            emitter.complete();
        } catch (RuntimeException e) {
            // already closed
        }
    }

    private void forget(long userId, SseEmitter emitter) {
        streams.computeIfPresent(userId, (id, mine) -> {
            mine.remove(emitter);
            return mine.isEmpty() ? null : mine;
        });
    }

    private static void afterCommit(Runnable action) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    action.run();
                }
            });
        } else {
            action.run();
        }
    }

    private static ThreadFactory daemonThreads() {
        AtomicInteger count = new AtomicInteger();
        return task -> {
            Thread thread = new Thread(task, "live-events-" + count.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
    }
}
