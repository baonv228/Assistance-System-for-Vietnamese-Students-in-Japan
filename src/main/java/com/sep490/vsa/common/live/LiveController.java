package com.sep490.vsa.common.live;

import com.sep490.vsa.common.security.AppUserPrincipal;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;

/**
 * The live update stream of the signed-in user ({@link LiveEvents}), any role. /api/me/** requires login; the page opens
 * it with {@code EventSource} (assets/js/lib/live.js) while it is in front.
 */
@RestController
public class LiveController {

    private static final Logger log = LoggerFactory.getLogger(LiveController.class);

    private final LiveEvents events;

    public LiveController(LiveEvents events) {
        this.events = events;
    }

    @GetMapping(value = "/api/me/live", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter live(@AuthenticationPrincipal AppUserPrincipal me, HttpServletResponse response) {
        // Never cached or held back: nginx keeps a response until it ends unless told otherwise
        response.setHeader("Cache-Control", "no-cache, no-transform");
        response.setHeader("X-Accel-Buffering", "no");
        return events.open(me.id());
    }

    /**
     * The page went away while an event was on its way (tab closed or reloaded, phone asleep). LiveEvents has dropped
     * the stream already and the browser opens a new one, so this is no error; without it Tomcat logged every such
     * disconnect as an ERROR with a stack trace. Only for this stream: other I/O failures stay errors.
     */
    @ExceptionHandler(IOException.class)
    public void disconnected(IOException e) {
        log.debug("Live stream closed by the browser: {}", e.getMessage());
    }
}
