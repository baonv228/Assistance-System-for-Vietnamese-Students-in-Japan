package com.sep490.vsa.feature.auth;

import com.sep490.vsa.common.exception.ApiException;
import com.sep490.vsa.feature.legal.LegalService;
import com.sep490.vsa.feature.user.User;
import com.sep490.vsa.feature.user.UserAuthProvider;
import com.sep490.vsa.feature.user.UserAuthProviderRepository;
import com.sep490.vsa.feature.user.UserRepository;
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
     */
    @Transactional
    public Optional<User> findOrLink(PendingSocialSignup profile) {
        Instant now = clock.instant();
        Optional<User> linked = providers.findByProviderAndProviderUserId(profile.provider(), profile.providerUserId())
                .map(UserAuthProvider::getUser);
        if (linked.isPresent()) {
            linked.get().recordSuccessfulLogin(now);
            return linked;
        }
        Optional<User> byEmail = users.findByEmailIgnoreCase(profile.email());
        byEmail.ifPresent(user -> {
            providers.save(new UserAuthProvider(user, profile.provider(), profile.providerUserId(), now));
            user.recordSuccessfulLogin(now);
        });
        return byEmail;
    }

    @Transactional
    public User completeSignup(PendingSocialSignup profile, String displayName) {
        Instant now = clock.instant();
        String email = normalizeEmail(profile.email());
        if (users.existsByEmailIgnoreCase(email)) {
            throw ApiException.conflict("Email này đã có tài khoản. Hãy đăng nhập lại.");
        }
        User user = users.save(User.register(email, displayName.trim(), null, profile.avatarUrl(), now));
        providers.save(new UserAuthProvider(user, profile.provider(), profile.providerUserId(), now));
        legalService.recordSignUpConsents(user);
        user.recordSuccessfulLogin(now);
        return user;
    }
}
