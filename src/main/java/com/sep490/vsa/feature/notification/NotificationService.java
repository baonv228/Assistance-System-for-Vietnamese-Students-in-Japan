package com.sep490.vsa.feature.notification;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.Clock;

/**
 * Writes in-app notifications ({@code notifications}). The Notification List screen that shows them is a
 * separate feature; other features only call {@link #send}.
 */
@Service
public class NotificationService {

    public static final String NEW_COMMENT = "NEW_COMMENT";
    public static final String BEST_ANSWER = "BEST_ANSWER";

    private static final int MAX_CONTENT = 500;

    private final JdbcTemplate jdbc;
    private final Clock clock;

    public NotificationService(JdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    /** @param refType what a click on the notification opens (e.g. BLOG), with {@code refId} */
    public void send(long userId, String type, String content, String refType, Long refId) {
        String text = content.length() <= MAX_CONTENT ? content : content.substring(0, MAX_CONTENT - 1) + "…";
        jdbc.update("""
                INSERT INTO notifications (user_id, type, content, ref_type, ref_id, created_at)
                VALUES (?, ?, ?, ?, ?, ?)""", userId, type, text, refType, refId, Timestamp.from(clock.instant()));
    }
}
