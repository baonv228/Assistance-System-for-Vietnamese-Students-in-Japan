package com.sep490.vsa.feature.auth;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;

public interface EmailVerificationCodeRepository extends JpaRepository<EmailVerificationCode, Long> {

    Optional<EmailVerificationCode> findFirstByEmailAndPurposeOrderByCreatedAtDesc(String email, String purpose);

    Optional<EmailVerificationCode> findByTicketHash(String ticketHash);

    long countByEmailAndPurposeAndCreatedAtAfter(String email, String purpose, Instant since);

    /** Invalidates every unused code of this email before a new one is sent. */
    @Modifying
    @Query("update EmailVerificationCode c set c.usedAt = :now "
            + "where c.email = :email and c.purpose = :purpose and c.usedAt is null")
    int invalidateOpenCodes(@Param("email") String email, @Param("purpose") String purpose, @Param("now") Instant now);
}
