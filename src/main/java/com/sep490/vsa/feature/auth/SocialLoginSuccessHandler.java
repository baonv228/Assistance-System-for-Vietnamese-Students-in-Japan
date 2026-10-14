package com.sep490.vsa.feature.auth;

import com.sep490.vsa.feature.user.User;
import com.sep490.vsa.feature.user.UserStatus;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Runs after Google/Facebook confirms the person. Replaces the OAuth2 login with our own session user,
 * or sends a first-time user to the "complete sign-up" page.
 */
@Component
public class SocialLoginSuccessHandler implements AuthenticationSuccessHandler {

    static final String LOGIN = "/pages/login.html";
    static final String COMPLETE_SIGNUP = "/pages/complete-signup.html";

    private final SocialLoginService socialLoginService;
    private final SessionSignIn sessionSignIn;
    private final SecurityContextRepository contextRepository;

    public SocialLoginSuccessHandler(SocialLoginService socialLoginService, SessionSignIn sessionSignIn,
                                     SecurityContextRepository contextRepository) {
        this.socialLoginService = socialLoginService;
        this.sessionSignIn = sessionSignIn;
        this.contextRepository = contextRepository;
    }

    @Override
    public void onAuthenticationSuccess(HttpServletRequest request, HttpServletResponse response,
                                        Authentication authentication) throws IOException {
        OAuth2AuthenticationToken token = (OAuth2AuthenticationToken) authentication;
        PendingSocialSignup profile = toProfile(token.getAuthorizedClientRegistrationId(), token.getPrincipal());

        // Drop the OAuth2 login; the session will hold our own user instead (or nobody yet)
        SecurityContextHolder.clearContext();
        contextRepository.saveContext(SecurityContextHolder.createEmptyContext(), request, response);

        if (profile.email() == null || profile.email().isBlank()) {
            response.sendRedirect(LOGIN + "?error=social_no_email");
            return;
        }
        Optional<User> existing = socialLoginService.findOrLink(profile);
        if (existing.isPresent()) {
            if (existing.get().getStatus() != UserStatus.ACTIVE) {
                response.sendRedirect(LOGIN + "?error=account_locked");
                return;
            }
            sessionSignIn.signIn(existing.get(), request, response);
            response.sendRedirect(existing.get().getRole().homePath());
            return;
        }
        request.getSession(true).setAttribute(PendingSocialSignup.SESSION_KEY, profile);
        response.sendRedirect(COMPLETE_SIGNUP);
    }

    private static PendingSocialSignup toProfile(String registrationId, OAuth2User user) {
        String provider = registrationId.toUpperCase(Locale.ROOT);
        Map<String, Object> attrs = user.getAttributes();
        String email = (String) attrs.get("email");
        String name = (String) attrs.get("name");
        if ("GOOGLE".equals(provider)) {
            // Only trust a Google email that Google itself has verified
            if (!Boolean.TRUE.equals(attrs.get("email_verified"))) {
                email = null;
            }
            return new PendingSocialSignup(provider, (String) attrs.get("sub"), AuthRules.normalizeEmail(email), name,
                    (String) attrs.get("picture"));
        }
        // Facebook: id, name, email (email is missing if the person did not share it)
        return new PendingSocialSignup(provider, String.valueOf(attrs.get("id")), AuthRules.normalizeEmail(email), name,
                null);
    }
}
