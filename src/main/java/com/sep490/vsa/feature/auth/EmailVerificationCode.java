package com.sep490.vsa.feature.auth;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Duration;
import java.time.Instant;

/**
 * A 6-digit code emailed during sign-up (table email_verification_codes).
 * Lifecycle: created → verified (a one-time ticket is issued) → used when the account is created.
 */
@Entity
@Table(name = "email_verification_codes")
public class EmailVerificationCode {

    public static final String PURPOSE_REGISTER = "REGISTER";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String email;

    @Column(nullable = false)
    private String purpose;

    @Column(name = "code_hash", nullable = false)
    private String codeHash;

    @Column(nullable = false)
    private int attempts;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "verified_at")
    private Instant verifiedAt;

    @Column(name = "ticket_hash")
    private String ticketHash;

    @Column(name = "ticket_expires_at")
    private Instant ticketExpiresAt;

    @Column(name = "used_at")
    private Instant usedAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected EmailVerificationCode() {
    }

    public EmailVerificationCode(String email, String purpose, String codeHash, Instant now, Duration validFor) {
        this.email = email;
        this.purpose = purpose;
        this.codeHash = codeHash;
        this.createdAt = now;
        this.expiresAt = now.plus(validFor);
    }

    public boolean isExpired(Instant now) {
        return !expiresAt.isAfter(now);
    }

    public boolean isUsed() {
        return usedAt != null;
    }

    public boolean isVerified() {
        return verifiedAt != null;
    }

    public boolean isTicketValid(Instant now) {
        return ticketExpiresAt != null && ticketExpiresAt.isAfter(now) && usedAt == null;
    }

    public void recordWrongAttempt() {
        attempts++;
    }

    public void markVerified(Instant now, String ticketHash, Duration ticketValidFor) {
        this.verifiedAt = now;
        this.ticketHash = ticketHash;
        this.ticketExpiresAt = now.plus(ticketValidFor);
    }

    public void markUsed(Instant now) {
        this.usedAt = now;
    }

    public Long getId() {
        return id;
    }

    public String getEmail() {
        return email;
    }

    public String getCodeHash() {
        return codeHash;
    }

    public int getAttempts() {
        return attempts;
    }

    public Instant getVerifiedAt() {
        return verifiedAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
