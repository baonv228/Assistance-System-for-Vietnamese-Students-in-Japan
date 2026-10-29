package com.sep490.vsa.feature.chat.notify;

import com.sep490.vsa.feature.chat.notify.NotifyDtos.Settings;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Plain SQL for the new-message notifications: the member's choice ({@code chat_notify_settings}, no row = both on),
 * the spacing columns of {@code chat_members} and the e-mail digest's selection. Reads the chat tables of
 * {@code feature/chat} and writes only {@code notified_up_to_id}, {@code emailed_at} and {@code pushed_at} there.
 */
@Repository
public class NotifyRepository {

    public record Account(long id, String email, String displayName, String role, String status) {

        /** An ACTIVE Member (role USER), the only kind of account that chats. */
        boolean member() {
            return "USER".equals(role) && "ACTIVE".equals(status);
        }
    }

    /**
     * One conversation of the digest: a member ({@code userId}) has unread messages from {@code peerName}, the newest
     * being {@code newestId}. {@code request}: the member has not accepted the conversation yet.
     */
    public record DigestRow(long userId, String email, String userName, long conversationId, boolean request,
                            String peerName, long newestId) {
    }

    private final JdbcTemplate jdbc;

    public NotifyRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<Account> account(long userId) {
        return jdbc.query("SELECT id, email, display_name, role, status FROM users WHERE id = ?",
                (rs, i) -> new Account(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4),
                        rs.getString(5)), userId).stream().findFirst();
    }

    public Settings settings(long userId) {
        return jdbc.query("SELECT email_enabled, push_enabled FROM chat_notify_settings WHERE user_id = ?",
                        (rs, i) -> new Settings(rs.getBoolean(1), rs.getBoolean(2)), userId)
                .stream().findFirst().orElse(new Settings(true, true));
    }

    /** Changes the given choices (null keeps the current one; a member without a row starts from "both on"). */
    public void saveSettings(long userId, Boolean emailEnabled, Boolean pushEnabled, Instant now) {
        jdbc.update("""
                INSERT INTO chat_notify_settings (user_id, email_enabled, push_enabled, updated_at)
                VALUES (?, COALESCE(?, TRUE), COALESCE(?, TRUE), ?)
                ON DUPLICATE KEY UPDATE email_enabled = COALESCE(?, email_enabled),
                    push_enabled = COALESCE(?, push_enabled), updated_at = VALUES(updated_at)""",
                userId, emailEnabled, pushEnabled, Timestamp.from(now), emailEnabled, pushEnabled);
    }

    /**
     * Locks the member's settings row (made with the defaults when missing, which means the same as no row) until the
     * transaction ends, so one member's subscriptions are saved and counted one after the other.
     */
    public void lockSettings(long userId) {
        jdbc.update("""
                INSERT INTO chat_notify_settings (user_id) VALUES (?)
                ON DUPLICATE KEY UPDATE user_id = user_id""", userId);
    }

    /**
     * Takes the member's push turn in the conversation: true (and {@code pushed_at} = now) when the last push about it
     * went out at or before {@code lastAllowed}. One statement, so two messages at the same moment push once.
     */
    public boolean claimPush(long conversationId, long userId, Instant now, Instant lastAllowed) {
        return jdbc.update("""
                UPDATE chat_members SET pushed_at = ?
                WHERE conversation_id = ? AND user_id = ? AND (pushed_at IS NULL OR pushed_at <= ?)""",
                Timestamp.from(now), conversationId, userId, Timestamp.from(lastAllowed)) > 0;
    }

    /**
     * The conversations to announce by e-mail, grouped by member (by user id, then newest message first): the member is
     * an ACTIVE Member with e-mail on; the conversation is in their inbox or requests and was not e-mailed after
     * {@code emailedBefore}; it holds a message from the other member that is shown to them (its writer's account
     * ACTIVE, as {@code ChatRepository.SHOWN}), not recalled, newer than what they read, cleared and were already told
     * about, and at least one such message was written at or before {@code writtenBefore}. A member the member blocked
     * is left out. Never reads the messages' text.
     */
    public List<DigestRow> digestRows(Instant writtenBefore, Instant emailedBefore, int limit) {
        return jdbc.query("""
                SELECT me.user_id, u.email, u.display_name, me.conversation_id, me.state, p.display_name,
                       MAX(m.id) AS newest_id
                FROM chat_members me
                JOIN users u ON u.id = me.user_id
                LEFT JOIN chat_notify_settings s ON s.user_id = me.user_id
                JOIN chat_members pm ON pm.conversation_id = me.conversation_id AND pm.user_id <> me.user_id
                JOIN users p ON p.id = pm.user_id
                JOIN chat_messages m ON m.conversation_id = me.conversation_id AND m.sender_id = pm.user_id
                WHERE u.role = 'USER' AND u.status = 'ACTIVE'
                  AND (s.user_id IS NULL OR s.email_enabled)
                  AND me.state IN ('ACCEPTED', 'REQUEST')
                  AND (me.emailed_at IS NULL OR me.emailed_at <= ?)
                  AND p.status = 'ACTIVE'
                  AND m.id > GREATEST(me.last_read_id, me.cleared_id, me.notified_up_to_id)
                  AND m.recalled_at IS NULL
                  AND NOT EXISTS (SELECT 1 FROM chat_blocks b WHERE b.blocker_id = me.user_id AND b.blocked_id = p.id)
                GROUP BY me.user_id, u.email, u.display_name, me.conversation_id, me.state, p.display_name
                HAVING MIN(m.created_at) <= ?
                ORDER BY me.user_id, newest_id DESC
                LIMIT ?""",
                (rs, i) -> new DigestRow(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getLong(4),
                        "REQUEST".equals(rs.getString(5)), rs.getString(6), rs.getLong(7)),
                Timestamp.from(emailedBefore), Timestamp.from(writtenBefore), limit);
    }

    /** The member was told about the conversation's messages up to {@code upToId} now. */
    public void markEmailed(long conversationId, long userId, long upToId, Instant now) {
        jdbc.update("""
                UPDATE chat_members SET notified_up_to_id = GREATEST(notified_up_to_id, ?), emailed_at = ?
                WHERE conversation_id = ? AND user_id = ?""", upToId, Timestamp.from(now), conversationId, userId);
    }
}
