package com.sep490.vsa.feature.legal;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/** One version of the Terms (TOS), Privacy Policy (PRIVACY) or Community Rules (COMMUNITY). */
@Entity
@Table(name = "legal_documents")
public class LegalDocument {

    public static final String TOS = "TOS";
    public static final String PRIVACY = "PRIVACY";
    public static final String COMMUNITY = "COMMUNITY";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "doc_type", nullable = false)
    private String docType;

    @Column(nullable = false)
    private String version;

    @Column(nullable = false)
    private String content;

    @Column(name = "effective_at", nullable = false)
    private Instant effectiveAt;

    protected LegalDocument() {
    }

    public Long getId() {
        return id;
    }

    public String getDocType() {
        return docType;
    }

    public String getVersion() {
        return version;
    }

    public String getContent() {
        return content;
    }

    public Instant getEffectiveAt() {
        return effectiveAt;
    }
}
