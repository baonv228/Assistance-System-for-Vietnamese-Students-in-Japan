package com.sep490.vsa.feature.notification;

import java.time.Instant;
import java.util.List;

/** JSON shapes of the bell and the "Thông báo" page: /api/me/notifications. */
public final class NotificationDtos {

    private NotificationDtos() {
    }

    /**
     * One notification.
     *
     * @param type CONTENT_PUBLISHED, CONTENT_REJECTED, NEW_COMMENT, BEST_ANSWER…
     * @param link the page it opens ("/blog/12", "/qa/7", "/map?place=3", "/events/5"); null when what it is about
     *             is gone (a deleted post or review, a deleted event)
     */
    public record NotificationItem(long id, String type, String content, String link, boolean read, Instant createdAt) {
    }

    /** A page of the list, newest first. {@code unread} counts all the user's unread notifications. */
    public record NotificationPage(List<NotificationItem> items, boolean hasMore, int unread) {
    }

    /**
     * What the bell needs, read in one snapshot.
     *
     * @param unread     unread notifications
     * @param latestId   the newest notification's id (0 when none)
     * @param processing the user's own posts, questions and reviews whose photos/video are still being scanned: while
     *                   there are some, the page asks again soon
     * @param fresh      notifications newer than the {@code after} id asked for, newest first (empty without it)
     */
    public record NotificationSummary(int unread, long latestId, int processing, List<NotificationItem> fresh) {
    }

    /** Marks the user's notifications up to this id as read (the newest the page has shown; null: all of them). */
    public record ReadRequest(Long upToId) {
    }
}
