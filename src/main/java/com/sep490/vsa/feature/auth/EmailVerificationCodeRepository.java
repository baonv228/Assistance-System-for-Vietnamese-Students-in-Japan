package com.sep490.vsa.feature.auth;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface EmailVerificationCodeRepository extends JpaRepository<EmailVerificationCode, Long> {

    /** What the sending limits need to know of a code. */
    interface SentCode {
        String getEmail();

        String getPurpose();

        Instant getCreatedAt();
    }

    /**
     * The newest code of this email, locked until the transaction ends (SELECT … FOR UPDATE): guesses sent at the same
     * moment wait for each other, so every wrong attempt is counted.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<EmailVerificationCode> findFirstByEmailAndPurposeOrderByCreatedAtDescIdDesc(String email, String purpose);

    Optional<EmailVerificationCode> findByTicketHash(String ticketHash);

    /** Codes sent since {@code since}, of every email: the per-mailbox and whole-site sending limits. */
    List<SentCode> findByCreatedAtAfter(Instant since);

    /**
     * Closes the older unused codes of this email once a new one has been sent. Only older ones: a code saved after
     * {@code keepId} (another tab, while this email waited on a slow mail server) is newer and stays open.
     */
    @Modifying
    @Query("update EmailVerificationCode c set c.usedAt = :now "
            + "where c.email = :email and c.purpose = :purpose and c.usedAt is null and c.id < :keepId")
    int invalidateOlderOpenCodes(@Param("email") String email, @Param("purpose") String purpose,
                                 @Param("keepId") long keepId, @Param("now") Instant now);

    /**
     * Deletes the codes created before {@code before} that nobody can use any more: used (or replaced by a newer code,
     * which sets used_at too), or expired with no ticket still open. A code or ticket that is still valid stays.
     */
    @Modifying
    @Query("delete from EmailVerificationCode c where c.createdAt < :before and (c.usedAt is not null "
            + "or (c.expiresAt <= :now and (c.ticketExpiresAt is null or c.ticketExpiresAt <= :now)))")
    int deleteFinishedBefore(@Param("before") Instant before, @Param("now") Instant now);
}
