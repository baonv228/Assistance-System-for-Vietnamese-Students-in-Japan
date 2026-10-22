package com.sep490.vsa.feature.auth;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

/**
 * Removes old sign-up codes every day at 03:30 Japan time, so email_verification_codes does not grow forever. Only rows
 * older than {@link #KEEP_FOR} that are used, replaced or expired go (see
 * {@link EmailVerificationCodeRepository#deleteFinishedBefore}); the sending limits look back 24 hours only.
 */
@Component
public class EmailCodeCleanup {

    private static final Logger log = LoggerFactory.getLogger(EmailCodeCleanup.class);
    /** Finished codes are kept this long (for questions about a sign-up). Cần chốt. */
    static final Duration KEEP_FOR = Duration.ofDays(7);

    private final EmailVerificationCodeRepository codes;
    private final TransactionTemplate tx;
    private final Clock clock;

    public EmailCodeCleanup(EmailVerificationCodeRepository codes, PlatformTransactionManager transactionManager,
                            Clock clock) {
        this.codes = codes;
        this.tx = new TransactionTemplate(transactionManager);
        this.clock = clock;
    }

    /** One short transaction: a single DELETE. */
    @Scheduled(cron = "0 30 3 * * *", zone = "Asia/Tokyo")
    public void purgeOldCodes() {
        Instant now = clock.instant();
        Integer removed = tx.execute(status -> codes.deleteFinishedBefore(now.minus(KEEP_FOR), now));
        log.info("Sign-up code clean-up: {} rows older than {} days removed", removed, KEEP_FOR.toDays());
    }
}
