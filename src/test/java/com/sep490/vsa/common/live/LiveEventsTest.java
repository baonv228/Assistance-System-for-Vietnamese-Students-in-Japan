package com.sep490.vsa.common.live;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Streams kept per user: at most a few (the oldest closed), all closed for a locked or deleted account. */
class LiveEventsTest {

    private final LiveEvents live = new LiveEvents();

    @AfterEach
    void stop() {
        live.shutdown();
    }

    @Test
    void theOldestStreamClosesWhenAUserOpensTooMany() {
        List<SseEmitter> opened = new ArrayList<>();
        for (int i = 0; i < LiveEvents.STREAMS_PER_USER + 2; i++) {
            opened.add(live.open(7));
        }
        assertThat(live.openStreams(7)).isEqualTo(LiveEvents.STREAMS_PER_USER);
        // the two oldest were closed: sending on them fails, on the newest it does not
        assertThat(sendFails(opened.get(0))).isTrue();
        assertThat(sendFails(opened.get(1))).isTrue();
        assertThat(sendFails(opened.get(opened.size() - 1))).isFalse();
        assertThat(live.openStreams(8)).isZero();
    }

    @Test
    void closingAUserEndsAllTheirStreamsOnly() {
        live.open(7);
        live.open(7);
        live.open(8);
        live.close(7);
        assertThat(live.openStreams(7)).isZero();
        assertThat(live.openStreams(8)).isEqualTo(1);
        // nobody listening: publishing is a no-op, never an error
        live.publish(7, "chat", Map.of("kind", "message"));
        live.publish(99, "notification", Map.of());
    }

    private static boolean sendFails(SseEmitter emitter) {
        try {
            emitter.send(SseEmitter.event().comment("x"));
            return false;
        } catch (Exception e) {
            return true;
        }
    }
}
