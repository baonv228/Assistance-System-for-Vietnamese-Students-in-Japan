package com.sep490.vsa.feature.chat.notify;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;

/**
 * Plain SQL over {@code push_subscriptions}: one row per browser that asked for push notifications. The endpoint is
 * unique ({@code endpoint_hash} = SHA-256 hex of it), so a browser saved again by another account moves to that
 * account: it is the one signed in there now.
 */
@Repository
public class PushSubscriptionRepository {

    /**
     * @param p256dh the browser's P-256 key (base64url, as the browser gave it)
     * @param auth   the browser's 16-byte auth secret (base64url)
     */
    public record Subscription(long id, long userId, String endpoint, String p256dh, String auth) {
    }

    private static final RowMapper<Subscription> ROW = (rs, i) -> new Subscription(rs.getLong(1), rs.getLong(2),
            rs.getString(3), rs.getString(4), rs.getString(5));

    private final JdbcTemplate jdbc;

    public PushSubscriptionRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<Subscription> ofUser(long userId) {
        return jdbc.query("""
                SELECT id, user_id, endpoint, p256dh, auth FROM push_subscriptions WHERE user_id = ? ORDER BY id""",
                ROW, userId);
    }

    /** Saves the browser for the user; saved again, it counts as new (keys replaced, failures forgotten). */
    public void save(long userId, String endpoint, String p256dh, String auth, Instant now) {
        jdbc.update("""
                INSERT INTO push_subscriptions (user_id, endpoint, endpoint_hash, p256dh, auth, created_at)
                VALUES (?, ?, ?, ?, ?, ?)
                ON DUPLICATE KEY UPDATE user_id = VALUES(user_id), endpoint = VALUES(endpoint), p256dh = VALUES(p256dh),
                    auth = VALUES(auth), created_at = VALUES(created_at), failure_count = 0""",
                userId, endpoint, hash(endpoint), p256dh, auth, Timestamp.from(now));
    }

    /** Ids of the user's subscriptions, newest first. */
    public List<Long> idsNewestFirst(long userId) {
        return jdbc.queryForList("""
                SELECT id FROM push_subscriptions WHERE user_id = ? ORDER BY created_at DESC, id DESC""",
                Long.class, userId);
    }

    /** Removes the user's own subscription of this endpoint; another account's is left alone. */
    public boolean deleteOwn(long userId, String endpoint) {
        return jdbc.update("DELETE FROM push_subscriptions WHERE endpoint_hash = ? AND user_id = ?",
                hash(endpoint), userId) > 0;
    }

    public void delete(long id) {
        jdbc.update("DELETE FROM push_subscriptions WHERE id = ?", id);
    }

    /** The push service took the message: failures start again from 0. */
    public void markSent(long id, Instant now) {
        jdbc.update("UPDATE push_subscriptions SET last_used_at = ?, failure_count = 0 WHERE id = ?",
                Timestamp.from(now), id);
    }

    /** One more failure in a row; the new count (0 when the row is gone). */
    public int recordFailure(long id) {
        jdbc.update("UPDATE push_subscriptions SET failure_count = failure_count + 1 WHERE id = ?", id);
        List<Integer> count = jdbc.queryForList("SELECT failure_count FROM push_subscriptions WHERE id = ?",
                Integer.class, id);
        return count.isEmpty() ? 0 : count.get(0);
    }

    static String hash(String endpoint) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(endpoint.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
