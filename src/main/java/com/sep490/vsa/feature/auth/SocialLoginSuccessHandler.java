package com.sep490.vsa.feature.auth;

import com.sep490.vsa.common.security.AppSocialLogin;
import com.sep490.vsa.feature.user.User;
import com.sep490.vsa.feature.user.UserStatus;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
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
 * <p>
 * A round started by the mobile app ({@link AppSocialLogin}) ends differently: the browser it ran in is not signed in
 * (it is not the app), nothing is looked up or written yet, and the browser goes to the app's address
 * ({@code app.mobile.social-callback}, never taken from the request) with a one-time code ({@link SocialAppCodes})
 * that the app trades in with its PKCE verifier ({@link SocialAppLoginService}).
 */
@Component
public class SocialLoginSuccessHandler implements AuthenticationSuccessHandler {

    static final String LOGIN = "/login";
    static final String COMPLETE_SIGNUP = "/complete-signup";

    private static final Logger log = LoggerFactory.getLogger(SocialLoginSuccessHandler.class);

    private final SocialLoginService socialLoginService;
    private final SessionSignIn sessionSignIn;
    private final SecurityContextRepository contextRepository;
    private final SocialAppCodes appCodes;
    private final String appCallback;

    public SocialLoginSuccessHandler(SocialLoginService socialLoginService, SessionSignIn sessionSignIn,
                                     SecurityContextRepository contextRepository, SocialAppCodes appCodes,
                                     @Value("${app.mobile.social-callback:senpaioi://auth}") String appCallback) {
        this.socialLoginService = socialLoginService;
        this.sessionSignIn = sessionSignIn;
        this.contextRepository = contextRepository;
        this.appCodes = appCodes;
        this.appCallback = appCallback;
    }

    @Override
    public void onAuthenticationSuccess(HttpServletRequest request, HttpServletResponse response,
                                        Authentication authentication) throws IOException {
        OAuth2AuthenticationToken token = (OAuth2AuthenticationToken) authentication;
        PendingSocialSignup profile = toProfile(token.getAuthorizedClientRegistrationId(), token.getPrincipal());

        Optional<String> appChallenge = AppSocialLogin.challenge(request);
        if (appChallenge.isPresent()) {
            backToApp(request, response, appChallenge.get(), profile);
            return;
        }

        // Drop the OAuth2 login; the session will hold our own user instead (or nobody yet)
        SecurityContextHolder.clearContext();
        contextRepository.saveContext(SecurityContextHolder.createEmptyContext(), request, response);

        if (profile.email() == null || profile.email().isBlank()) {
            response.sendRedirect(LOGIN + "?error=social_no_email");
            return;
        }
        Optional<User> existing;
        try {
            existing = socialLoginService.findOrLink(profile);
        } catch (DataIntegrityViolationException e) {
            // The same Google account signed in twice at once (two tabs): the other one has just linked it to the
            // account, so it is found by that link now
            existing = socialLoginService.findOrLink(profile);
        }
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

    /**
     * End of an app round. The browser (a Custom Tab, with Chrome's own cookies) gets back the sign-in it had before
     * the round, e.g. a member signed in to the website there, or nobody; it never keeps the Google/Facebook login or
     * a pending sign-up. The account is looked up only once the app proves it holds the verifier.
     */
    private void backToApp(HttpServletRequest request, HttpServletResponse response, String challenge,
                           PendingSocialSignup profile) throws IOException {
        SecurityContext previous = AppSocialLogin.previousContext(request)
                .orElseGet(SecurityContextHolder::createEmptyContext);
        SecurityContextHolder.setContext(previous);
        contextRepository.saveContext(previous, request, response);

        if (profile.email() == null || profile.email().isBlank()) {
            response.sendRedirect(AppSocialLogin.callback(appCallback, "error", "social_no_email"));
            return;
        }
        Optional<String> code = appCodes.issue(challenge, profile);
        if (code.isEmpty()) {
            log.warn("Social sign-in of the app with {} refused: {} sign-in codes are already waiting",
                    profile.provider(), SocialAppCodes.MAX_LIVE);
            response.sendRedirect(AppSocialLogin.callback(appCallback, "error", "social"));
            return;
        }
        response.sendRedirect(AppSocialLogin.callback(appCallback, "code", code.get()));
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
