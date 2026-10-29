package com.sep490.vsa.feature.notification;

import com.sep490.vsa.common.live.LiveEvents;
import com.sep490.vsa.feature.notification.NotificationDtos.NotificationItem;
import com.sep490.vsa.feature.notification.NotificationDtos.NotificationPage;
import com.sep490.vsa.feature.notification.NotificationDtos.NotificationSummary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Clock;
import java.util.List;
import java.util.Map;

/**
 * In-app notifications ({@code notifications}). Other features only call {@link #send}; the bell in the site header
 * and the "Thông báo" page read them through {@link NotificationController}.
 */
@Service
public class NotificationService {

    public static final String NEW_COMMENT = "NEW_COMMENT";
    public static final String BEST_ANSWER = "BEST_ANSWER";
    /** A post (or review) whose photos/video failed the automatic scan and was not published. */
    public static final String CONTENT_REJECTED = "CONTENT_REJECTED";
    /** A post (or review) whose photos/video passed the automatic scan and that is published now. */
    public static final String CONTENT_PUBLISHED = "CONTENT_PUBLISHED";

    static final int PAGE_SIZE = 20;
    /** At most this many new notifications are handed to the bell at once (it shows the scan results among them). */
    static final int FRESH_MAX = 5;

    private static final int MAX_CONTENT = 500;

    private final JdbcTemplate jdbc;
    private final NotificationRepository repository;
    private final LiveEvents live;
    private final Clock clock;

    public NotificationService(JdbcTemplate jdbc, NotificationRepository repository, LiveEvents live, Clock clock) {
        this.jdbc = jdbc;
        this.repository = repository;
        this.live = live;
        this.clock = clock;
    }

    /**
     * @param refType what a click on the notification opens (e.g. BLOG), with {@code refId}. The user's open pages are
     *                told at once (after the commit), so the bell does not wait for its next check.
     */
    public void send(long userId, String type, String content, String refType, Long refId) {
        String text = content.length() <= MAX_CONTENT ? content : content.substring(0, MAX_CONTENT - 1) + "…";
        jdbc.update("""
                INSERT INTO notifications (user_id, type, content, ref_type, ref_id, created_at)
                VALUES (?, ?, ?, ?, ?, ?)""", userId, type, text, refType, refId, Timestamp.from(clock.instant()));
        live.publish(userId, "notification", Map.of());
    }

    /** Page {@code page} (from 0) of the user's notifications, newest first. */
    @Transactional(readOnly = true)
    public NotificationPage list(long userId, int page) {
        int offset = Math.max(0, page) * PAGE_SIZE;
        List<NotificationItem> rows = repository.page(userId, offset, PAGE_SIZE + 1);
        boolean hasMore = rows.size() > PAGE_SIZE;
        return new NotificationPage(hasMore ? rows.subList(0, PAGE_SIZE) : rows, hasMore, repository.unread(userId));
    }

    /**
     * The bell: unread count, newest id, the user's content still being scanned and, when {@code afterId} is given,
     * the notifications that came after it. One read-only transaction, so a scan finishing in between is seen either
     * entirely (its notification and the content no longer processing) or not at all.
     */
    @Transactional(readOnly = true)
    public NotificationSummary summary(long userId, Long afterId) {
        List<NotificationItem> fresh = afterId == null ? List.of() : repository.after(userId, afterId, FRESH_MAX);
        return new NotificationSummary(repository.unread(userId), repository.latestId(userId),
                repository.processing(userId), fresh);
    }

    /** Marks the user's notifications read (up to {@code upToId} when given); returns the unread left. */
    @Transactional
    public int markRead(long userId, Long upToId) {
        repository.markRead(userId, upToId);
        return repository.unread(userId);
    }
}
