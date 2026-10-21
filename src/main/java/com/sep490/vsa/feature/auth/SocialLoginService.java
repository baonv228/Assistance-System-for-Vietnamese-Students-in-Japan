package com.sep490.vsa.feature.auth;

import com.sep490.vsa.common.exception.ApiException;
import com.sep490.vsa.feature.legal.LegalService;
import com.sep490.vsa.feature.user.User;
import com.sep490.vsa.feature.user.UserAuthProvider;
import com.sep490.vsa.feature.user.UserAuthProviderRepository;
import com.sep490.vsa.feature.user.UserRepository;
import com.sep490.vsa.feature.user.UserStatus;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;

import static com.sep490.vsa.feature.auth.AuthRules.normalizeEmail;

@Service
public class SocialLoginService {

    private final UserRepository users;
    private final UserAuthProviderRepository providers;
    private final LegalService legalService;
    private final Clock clock;

    public SocialLoginService(UserRepository users, UserAuthProviderRepository providers, LegalService legalService,
                              Clock clock) {
        this.users = users;
        this.providers = providers;
        this.legalService = legalService;
        this.clock = clock;
    }

    /**
     * Finds the account for a Google/Facebook profile: first by the linked provider id, then by email
     * (an existing email account gets the provider linked). Empty means a new person who must finish sign-up.
     * Only an ACTIVE account is signed in: for a locked or deleted one nothing is written (no login time, no new link)
     * and the caller refuses it.
     */
    @Transactional
    public Optional<User> findOrLink(PendingSocialSignup profile) {
        Instant now = clock.instant();
        Optional<User> linked = providers.findByProviderAndProviderUserId(profile.provider(), profile.providerUserId())
                .map(UserAuthProvider::getUser);
        if (linked.isPresent()) {
            if (linked.get().getStatus() == UserStatus.ACTIVE) {
                linked.get().recordSuccessfulLogin(now);
            }
            return linked;
        }
        Optional<User> byEmail = users.findByEmailIgnoreCase(profile.email());
        byEmail.filter(user -> user.getStatus() == UserStatus.ACTIVE).ifPresent(user -> {
            providers.save(new UserAuthProvider(user, profile.provider(), profile.providerUserId(), now));
            user.recordSuccessfulLogin(now);
        });
        return byEmail;
    }

    /**
     * Creates the account of a first-time Google/Facebook user. Two requests for the same email at once (two tabs): the
     * second meets the first account at the email's unique key and answers 409 instead of 500.
     */
    @Transactional
    public User completeSignup(PendingSocialSignup profile, String displayName) {
        Instant now = clock.instant();
        String email = normalizeEmail(profile.email());
        if (users.existsByEmailIgnoreCase(email)) {
            throw ApiException.conflict("Email này đã có tài khoản. Hãy đăng nhập lại.");
        }
        User user;
        try {
            user = users.saveAndFlush(User.register(email, displayName.trim(), null, profile.avatarUrl(), now));
        } catch (DataIntegrityViolationException e) {
            throw ApiException.conflict("Email này đã được đăng ký. Hãy đăng nhập.");
        }
        providers.save(new UserAuthProvider(user, profile.provider(), profile.providerUserId(), now));
        legalService.recordSignUpConsents(user);
        user.recordSuccessfulLogin(now);
        return user;
    }
}
