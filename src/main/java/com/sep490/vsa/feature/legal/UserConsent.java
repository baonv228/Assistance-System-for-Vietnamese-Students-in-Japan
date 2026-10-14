package com.sep490.vsa.feature.legal;

import com.sep490.vsa.feature.user.User;
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

/** Records which document version a user accepted and when (table user_consents). */
@Entity
@Table(name = "user_consents")
public class UserConsent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id")
    private User user;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "document_id")
    private LegalDocument document;

    @Column(name = "accepted_at", nullable = false)
    private Instant acceptedAt;

    protected UserConsent() {
    }

    public UserConsent(User user, LegalDocument document, Instant acceptedAt) {
        this.user = user;
        this.document = document;
        this.acceptedAt = acceptedAt;
    }
}
