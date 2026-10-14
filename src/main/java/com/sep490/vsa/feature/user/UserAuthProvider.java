package com.sep490.vsa.feature.user;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import java.time.Instant;

/** A Google or Facebook account linked to a user (table user_auth_providers). */
@Entity
@Table(name = "user_auth_providers")
public class UserAuthProvider {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id")
    private User user;

    /** GOOGLE or FACEBOOK */
    @Column(nullable = false)
    private String provider;

    @Column(name = "provider_user_id", nullable = false)
    private String providerUserId;

    @Column(name = "linked_at", nullable = false)
    private Instant linkedAt;

    protected UserAuthProvider() {
    }

    public UserAuthProvider(User user, String provider, String providerUserId, Instant linkedAt) {
        this.user = user;
        this.provider = provider;
        this.providerUserId = providerUserId;
        this.linkedAt = linkedAt;
    }

    public User getUser() {
        return user;
    }

    public String getProvider() {
        return provider;
    }

    public String getProviderUserId() {
        return providerUserId;
    }
}
