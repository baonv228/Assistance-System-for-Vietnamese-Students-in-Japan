package com.sep490.vsa.common.security;

import jakarta.servlet.http.HttpSession;
import jakarta.servlet.http.HttpSessionEvent;
import jakarta.servlet.http.HttpSessionListener;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The open sessions of each signed-in user, so a password change can sign the other devices out (UC-U06) and a
 * password reset can sign every device out (UC-U05). Sessions live in the server's memory (one server, no session
 * store), so this list in memory sees all of them; a restart ends them all anyway. Spring Boot registers this
 * {@link HttpSessionListener} with the web server, which reports every session that ends (logout, timeout).
 */
@Component
public class UserSessions implements HttpSessionListener {

    /** Session attribute holding the user id, so an ending session can be found again. */
    static final String USER_ID = "asvsj.userId";

    private final Map<Long, Set<HttpSession>> byUser = new ConcurrentHashMap<>();

    /** Called at every sign-in ({@code SessionSignIn}). */
    public void add(long userId, HttpSession session) {
        session.setAttribute(USER_ID, userId);
        byUser.computeIfAbsent(userId, id -> ConcurrentHashMap.newKeySet()).add(session);
    }

    /** Ends every session of the user except {@code keep} (may be null: all of them). @return how many ended */
    public int endOthers(long userId, HttpSession keep) {
        Set<HttpSession> sessions = byUser.get(userId);
        if (sessions == null) {
            return 0;
        }
        int ended = 0;
        for (HttpSession session : Set.copyOf(sessions)) {
            if (keep != null && session.getId().equals(keep.getId())) {
                continue;
            }
            sessions.remove(session);
            try {
                session.invalidate();
                ended++;
            } catch (IllegalStateException alreadyEnded) {
                // ended meanwhile (logout, timeout)
            }
        }
        return ended;
    }

    @Override
    public void sessionDestroyed(HttpSessionEvent event) {
        HttpSession session = event.getSession();
        if (session.getAttribute(USER_ID) instanceof Long userId) {
            Set<HttpSession> sessions = byUser.get(userId);
            if (sessions != null) {
                sessions.remove(session);
                if (sessions.isEmpty()) {
                    byUser.remove(userId, sessions);
                }
            }
        }
    }
}
