package com.sep490.vsa.feature.quest.dev;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.List;

/**
 * Sample flash card marks for the test account {@value #ACCOUNT} (DevDataSeeder, password 123456), so every quest of
 * "Nhiệm vụ hằng ngày" can be tried with it at its full target (contract 5.9): REVIEW_LEARNING needs entries "Chưa
 * thuộc", LEARN_NEW entries not "Đã thuộc" ("new" ones without any answer for its "Luyện tập" link).
 * <p>
 * Only while the test-only API is on ({@link DevApiGuard}: the flag AND a database on this machine), and only for an
 * account without any mark yet, so a developer's own answers are never touched and a restart changes nothing. Runs once
 * the application is ready, i.e. after DevDataSeeder made the account (runners come before ApplicationReadyEvent).
 * Not in dev/sample-data.sql: that file is loaded only into an empty database, and the default profile points at the
 * shared one.
 * <p>
 * The visible library (not deleted, both kinds) in id order is dealt like cards: "Đã thuộc", "Chưa thuộc", no mark,
 * "Đã thuộc"… With the 48 sample entries that is 16 of each.
 */
@Component
@Conditional(DevApiGuard.On.class)
public class DevSampleMarks {

    static final String ACCOUNT = "user@gmail.com";

    private static final Logger log = LoggerFactory.getLogger(DevSampleMarks.class);

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;

    public DevSampleMarks(JdbcTemplate jdbc, PlatformTransactionManager transactions) {
        this.jdbc = jdbc;
        this.transaction = new TransactionTemplate(transactions);
        this.transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }

    /**
     * At start, in one transaction of its own (a call to this bean's own methods skips their @Transactional, so the
     * users row lock would end at once). A failure is logged, never stops the application.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        try {
            transaction.executeWithoutResult(status -> giveSampleMarks());
        } catch (RuntimeException e) {
            log.warn("Sample flash card marks for {} were not given: {}", ACCOUNT, e.getMessage());
        }
    }

    /** Marks for {@value #ACCOUNT} when it exists and has none; the number of marks written. */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public int giveSampleMarks() {
        List<Long> account = jdbc.queryForList("SELECT id FROM users WHERE email = ?", Long.class, ACCOUNT);
        if (account.isEmpty()) {
            return 0;
        }
        int written = markLibrary(account.get(0));
        if (written > 0) {
            log.info("Gave {} sample flash card marks to {} for the daily quests (test-only API on).", written, ACCOUNT);
        }
        return written;
    }

    /**
     * Deals the visible library to this user as described above, when the user has no mark at all (users row locked
     * first, so two starts at the same moment write once); the number of marks written ("Đã thuộc" + "Chưa thuộc").
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public int markLibrary(long userId) {
        jdbc.queryForList("SELECT id FROM users WHERE id = ? FOR UPDATE", Long.class, userId);
        Integer marks = jdbc.queryForObject("SELECT COUNT(*) FROM user_phrase_marks WHERE user_id = ?", Integer.class,
                userId);
        if (marks != null && marks > 0) {
            return 0;
        }
        List<Long> library = jdbc.queryForList("SELECT id FROM phrases WHERE deleted_at IS NULL ORDER BY id", Long.class);
        List<Object[]> rows = new ArrayList<>();
        for (int i = 0; i < library.size(); i++) {
            int hand = i % 3;
            if (hand < 2) {                       // 0 = "Đã thuộc", 1 = "Chưa thuộc", 2 = no mark ("Chưa luyện")
                rows.add(new Object[] {userId, library.get(i), hand == 0, hand == 1});
            }
        }
        if (!rows.isEmpty()) {
            jdbc.batchUpdate("""
                    INSERT INTO user_phrase_marks (user_id, phrase_id, is_learned, is_learning) VALUES (?, ?, ?, ?)""",
                    rows);
        }
        return rows.size();
    }
}
