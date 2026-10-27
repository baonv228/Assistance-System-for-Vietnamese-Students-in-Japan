package com.sep490.vsa.feature.user;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Clock;

/**
 * Adds and withdraws points. Every change is a row in {@code point_transactions};
 * {@code users.points} is the running total kept in step with it.
 * Point rules (SRS 1.1 (8)): review +10, approved pin +15, best answer +5, removed content −20, and a user
 * who deletes their own content loses the points that content earned.
 */
@Service
@Transactional(propagation = Propagation.MANDATORY)
public class PointService {

    public static final int BEST_ANSWER = 5;
    /** A community pin approved by a Moderator (reason PIN_APPROVED, ref PLACE), SRS point list. */
    public static final int PIN_APPROVED = 15;
    /** A published place or event review (reason REVIEW_CREATED, ref REVIEW), SRS 1.1 (8), paid the first time it is published. */
    public static final int REVIEW = 10;
    /**
     * A published blog post or question (reason BLOG_CREATED). The SRS point list has no value for posts yet;
     * +10 like a review until the team confirms it.
     */
    public static final int BLOG_POST = 10;

    private final JdbcTemplate jdbc;
    private final Clock clock;

    public PointService(JdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    /** Records one change. {@code reason} is e.g. BEST_ANSWER, CONTENT_DELETED; refType BLOG, COMMENT… */
    public void add(long userId, int delta, String reason, String refType, Long refId) {
        if (delta == 0) {
            return;
        }
        jdbc.update("""
                INSERT INTO point_transactions (user_id, delta, reason, ref_type, ref_id, created_at)
                VALUES (?, ?, ?, ?, ?, ?)""", userId, delta, reason, refType, refId, Timestamp.from(clock.instant()));
        jdbc.update("UPDATE users SET points = points + ? WHERE id = ?", delta, userId);
    }

    /**
     * Points this user currently holds from one piece of content (sum of its transactions). A locking read: it sees
     * the latest committed rows (not the transaction's snapshot) and keeps them locked, so two withdrawals of the
     * same content at the same moment cannot both take the points back.
     */
    public int balanceFor(long userId, String refType, long refId) {
        Integer sum = jdbc.queryForObject("""
                SELECT COALESCE(SUM(delta), 0) FROM point_transactions
                WHERE user_id = ? AND ref_type = ? AND ref_id = ? FOR UPDATE""", Integer.class, userId, refType, refId);
        return sum == null ? 0 : sum;
    }

    /** Takes back whatever the content still gives this user (nothing happens when the balance is 0 or less). */
    public void withdrawAll(long userId, String refType, long refId, String reason) {
        int balance = balanceFor(userId, refType, refId);
        if (balance > 0) {
            add(userId, -balance, reason, refType, refId);
        }
    }
}
