package com.sep490.vsa.common.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.oauth2.client.web.AuthorizationRequestRepository;
import org.springframework.security.oauth2.client.web.HttpSessionOAuth2AuthorizationRequestRepository;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Google/Facebook sign-in of the mobile app (contract: design/SOCIAL_LOGIN.md of the app repo). Google refuses
 * sign-in inside a WebView, so the app opens the website's own {@code /oauth2/authorization/{google|facebook}} in the
 * phone's browser (a Chrome Custom Tab, RFC 8252) with {@code ?app_challenge=<base64url(SHA-256(verifier))>} (PKCE,
 * RFC 7636 S256). Everything up to the provider's callback is the website's flow (same client, same registered
 * redirect_uri). That browser keeps its own cookies, not the app's, so the server does not sign the browser in at the
 * end: it sends the app a one-time code through {@code senpaioi://auth?code=…}, which the app trades together with
 * its verifier for a session of its own ({@code POST /api/auth/social/app/exchange}). Any app can register that scheme,
 * so a code caught on the way is worthless without the verifier.
 * <p>
 * This class marks the app's rounds. The challenge rides in the saved OAuth2 authorization request as an attribute
 * (never sent to the provider); when the provider's callback takes that request back out of the browser session, the
 * challenge and the browser's sign-in from before are put on the request for {@code SocialLoginSuccessHandler} and
 * {@code SocialLoginFailureHandler}. A callback whose request carries no challenge is the website's own sign-in.
 */
public final class AppSocialLogin {

    /** Query parameter of {@code /oauth2/authorization/{id}} that starts an app round. */
    public static final String PARAMETER = "app_challenge";
    /** Attribute of the saved {@link OAuth2AuthorizationRequest} holding the challenge. */
    public static final String ATTRIBUTE = "app_challenge";

    /** base64url without "=" of a SHA-256 digest: exactly 43 characters. */
    private static final Pattern CHALLENGE = Pattern.compile("[A-Za-z0-9_-]{43}");
    private static final String CALLBACK_CHALLENGE = AppSocialLogin.class.getName() + ".challenge";
    private static final String CALLBACK_PREVIOUS_CONTEXT = AppSocialLogin.class.getName() + ".previousContext";

    private AppSocialLogin() {
    }

    /** Whether {@code value} has the form of an S256 challenge (43 characters of base64url). */
    public static boolean isChallenge(String value) {
        return value != null && CHALLENGE.matcher(value).matches();
    }

    /**
     * Wraps the website's resolver ({@link SocialRedirectUri#resolver}): without {@code app_challenge} its request
     * goes out unchanged; with a well-formed one the same request (same address, state, nonce and redirect_uri) also
     * carries the challenge; with a malformed one (or several) there is no request at all, so the browser is never
     * sent to the provider (the address answers 404) and the round can never end as a website sign-in.
     */
    public static OAuth2AuthorizationRequestResolver resolver(OAuth2AuthorizationRequestResolver website) {
        return new OAuth2AuthorizationRequestResolver() {
            @Override
            public OAuth2AuthorizationRequest resolve(HttpServletRequest request) {
                return withChallenge(request, website.resolve(request));
            }

            @Override
            public OAuth2AuthorizationRequest resolve(HttpServletRequest request, String clientRegistrationId) {
                return withChallenge(request, website.resolve(request, clientRegistrationId));
            }
        };
    }

    /**
     * Where the authorization request waits for the provider's callback: the browser session, as before. Used by both
     * the redirect filter and the login filter. Taking out a request of an app round also notes, for the handlers,
     * its challenge and the browser's sign-in from before (read now, before Spring puts the Google/Facebook login in
     * its place), so whoever was signed in to the website in that browser stays signed in.
     */
    public static AuthorizationRequestRepository<OAuth2AuthorizationRequest> authorizationRequests(
            SecurityContextRepository contextRepository) {
        HttpSessionOAuth2AuthorizationRequestRepository session = new HttpSessionOAuth2AuthorizationRequestRepository();
        return new AuthorizationRequestRepository<>() {
            @Override
            public OAuth2AuthorizationRequest loadAuthorizationRequest(HttpServletRequest request) {
                return session.loadAuthorizationRequest(request);
            }

            @Override
            public void saveAuthorizationRequest(OAuth2AuthorizationRequest authorizationRequest,
                                                 HttpServletRequest request, HttpServletResponse response) {
                session.saveAuthorizationRequest(authorizationRequest, request, response);
            }

            @Override
            public OAuth2AuthorizationRequest removeAuthorizationRequest(HttpServletRequest request,
                                                                         HttpServletResponse response) {
                OAuth2AuthorizationRequest removed = session.removeAuthorizationRequest(request, response);
                if (removed != null && removed.getAttributes().get(ATTRIBUTE) instanceof String challenge) {
                    // An empty context when nobody was signed in
                    request.setAttribute(CALLBACK_PREVIOUS_CONTEXT, contextRepository.loadDeferredContext(request).get());
                    request.setAttribute(CALLBACK_CHALLENGE, challenge);
                }
                return removed;
            }
        };
    }

    /** On the provider's callback: the challenge when this is an app round, empty for the website's own sign-in. */
    public static Optional<String> challenge(HttpServletRequest request) {
        return request.getAttribute(CALLBACK_CHALLENGE) instanceof String challenge
                ? Optional.of(challenge) : Optional.empty();
    }

    /**
     * On the callback of an app round: the browser's sign-in from before the round (an empty context when nobody was
     * signed in), to be put back instead of the Google/Facebook login.
     */
    public static Optional<SecurityContext> previousContext(HttpServletRequest request) {
        return request.getAttribute(CALLBACK_PREVIOUS_CONTEXT) instanceof SecurityContext context
                ? Optional.of(context) : Optional.empty();
    }

    /** The app's address ({@code app.mobile.social-callback}) with one query parameter, e.g. {@code ?code=…}. */
    public static String callback(String appCallback, String name, String value) {
        return UriComponentsBuilder.fromUriString(appCallback).queryParam(name, value).encode().build().toUriString();
    }

    private static OAuth2AuthorizationRequest withChallenge(HttpServletRequest request,
                                                            OAuth2AuthorizationRequest website) {
        String[] values = request.getParameterValues(PARAMETER);
        if (website == null || values == null) {
            return website;
        }
        if (values.length != 1 || !isChallenge(values[0])) {
            return null;
        }
        // from() rebuilds the address from the parameters; keep the website's own, so the provider sees no difference
        return OAuth2AuthorizationRequest.from(website)
                .authorizationRequestUri(website.getAuthorizationRequestUri())
                .attributes(attributes -> attributes.put(ATTRIBUTE, values[0]))
                .build();
    }
}
