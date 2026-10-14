package com.sep490.vsa.feature.legal;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.Optional;

public interface LegalDocumentRepository extends JpaRepository<LegalDocument, Long> {

    /** The version in force at {@code now}. */
    Optional<LegalDocument> findFirstByDocTypeAndEffectiveAtLessThanEqualOrderByEffectiveAtDesc(String docType, Instant now);
}
