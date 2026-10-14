package com.sep490.vsa.feature.user;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Duration;
import java.time.Instant;

/**
 * Maps the columns of table {@code users} needed so far. Columns that are not mapped
 * (area_id, stage, points…) keep their database defaults; map them when a feature needs them.
 */
@Entity
@Table(name = "users")
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String email;

    /** BCrypt hash. Null for accounts that only sign in with Google or Facebook. */
    @Column(name = "password_hash")
    private String passwordHash;

    @Column(name = "email_verified_at")
    private Instant emailVerifiedAt;

    @Column(name = "failed_login_count", nullable = false)
    private int failedLoginCount;

    @Column(name = "locked_until")
    private Instant lockedUntil;

    @Column(name = "display_name", nullable = false)
    private String displayName;

    @Column(name = "avatar_url")
    private String avatarUrl;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Role role = Role.USER;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private UserStatus status = UserStatus.ACTIVE;

    @Column(name = "is_adult_confirmed", nullable = false)
    private boolean adultConfirmed;

    /** Self-declared living area (ward – station), optional. */
    @Column(name = "area_id")
    private Long areaId;

    /** Self-declared stage: PRE, ARR, LEAVE, HOME. Optional. */
    @Column(name = "stage")
    private String stage;

    /** Total points, maintained from point_transactions. Read-only here. */
    @Column(name = "points", insertable = false, updatable = false)
    private int points;

    @Column(name = "report_muted_until", insertable = false, updatable = false)
    private Instant reportMutedUntil;

    @Column(name = "created_at", insertable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "last_login_at")
    private Instant lastLoginAt;

    @Column(name = "deleted_at")
    private Instant deletedAt;

    protected User() {
    }

    public void updateProfile(Long areaId, String stage) {
        this.areaId = areaId;
        this.stage = stage;
    }

    /**
     * Account deletion: personal data is removed and the email freed so it can register again.
     * What happens to the user's posts and reviews is still open (team decision pending).
     */
    public void anonymize(Instant now) {
        this.email = "deleted-" + id + "@deleted.invalid";
        this.displayName = "Tài khoản đã xóa";
        this.passwordHash = null;
        this.avatarUrl = null;
        this.areaId = null;
        this.stage = null;
        this.status = UserStatus.DELETED;
        this.deletedAt = now;
    }

    public static User register(String email, String displayName, String passwordHash, String avatarUrl, Instant verifiedAt) {
        User user = new User();
        user.email = email;
        user.displayName = displayName;
        user.passwordHash = passwordHash;
        user.avatarUrl = avatarUrl;
        user.emailVerifiedAt = verifiedAt;
        user.adultConfirmed = true;
        return user;
    }

    public boolean isLockedAt(Instant now) {
        return lockedUntil != null && lockedUntil.isAfter(now);
    }

    /** Counts a wrong password; after {@code maxAttempts} the account is locked for {@code lockFor}. */
    public void recordFailedLogin(Instant now, int maxAttempts, Duration lockFor) {
        failedLoginCount++;
        if (failedLoginCount >= maxAttempts) {
            lockedUntil = now.plus(lockFor);
            failedLoginCount = 0;
        }
    }

    public void recordSuccessfulLogin(Instant now) {
        failedLoginCount = 0;
        lockedUntil = null;
        lastLoginAt = now;
    }

    public Long getId() {
        return id;
    }

    public String getEmail() {
        return email;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public Instant getEmailVerifiedAt() {
        return emailVerifiedAt;
    }

    public int getFailedLoginCount() {
        return failedLoginCount;
    }

    public Instant getLockedUntil() {
        return lockedUntil;
    }

    public String getDisplayName() {
        return displayName;
    }

    public String getAvatarUrl() {
        return avatarUrl;
    }

    public Role getRole() {
        return role;
    }

    public UserStatus getStatus() {
        return status;
    }

    public boolean isAdultConfirmed() {
        return adultConfirmed;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getLastLoginAt() {
        return lastLoginAt;
    }

    public Long getAreaId() {
        return areaId;
    }

    public String getStage() {
        return stage;
    }

    public int getPoints() {
        return points;
    }

    public Instant getReportMutedUntil() {
        return reportMutedUntil;
    }

    public Instant getDeletedAt() {
        return deletedAt;
    }
}
