package com.sep490.vsa.feature.legal;

import com.sep490.vsa.common.exception.ApiException;
import com.sep490.vsa.feature.user.User;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

@Service
public class LegalService {

    /** Documents every new user must accept at sign-up. */
    private static final List<String> REQUIRED = List.of(LegalDocument.TOS, LegalDocument.PRIVACY, LegalDocument.COMMUNITY);

    private final LegalDocumentRepository documents;
    private final UserConsentRepository consents;
    private final Clock clock;

    public LegalService(LegalDocumentRepository documents, UserConsentRepository consents, Clock clock) {
        this.documents = documents;
        this.consents = consents;
        this.clock = clock;
    }

    public LegalDocument current(String docType) {
        return documents.findFirstByDocTypeAndEffectiveAtLessThanEqualOrderByEffectiveAtDesc(docType, clock.instant())
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Không tìm thấy văn bản " + docType));
    }

    /** Stores the user's acceptance of the current version of each required document. */
    @Transactional
    public void recordSignUpConsents(User user) {
        Instant now = clock.instant();
        for (String type : REQUIRED) {
            consents.save(new UserConsent(user, current(type), now));
        }
    }
}
