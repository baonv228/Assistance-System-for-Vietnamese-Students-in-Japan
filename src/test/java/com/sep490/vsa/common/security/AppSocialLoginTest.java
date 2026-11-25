package com.sep490.vsa.common.security;

import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.AuthorizationRequestRepository;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.web.context.HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY;

/** How the app's rounds are marked on the authorization request and found again on the provider's callback. */
class AppSocialLoginTest {

    /** RFC 7636 appendix B. */
    private static final String CHALLENGE = "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM";

    @Test
    void onlyFortyThreeBase64urlCharactersAreAChallenge() {
        assertThat(AppSocialLogin.isChallenge(CHALLENGE)).isTrue();
        assertThat(AppSocialLogin.isChallenge("_".repeat(43))).isTrue();

        assertThat(AppSocialLogin.isChallenge(null)).isFalse();
        assertThat(AppSocialLogin.isChallenge("")).isFalse();
        assertThat(AppSocialLogin.isChallenge(CHALLENGE.substring(1))).isFalse();
        assertThat(AppSocialLogin.isChallenge(CHALLENGE + "A")).isFalse();
        for (String sign : List.of("+", "/", "=", ".", "~", " ", "\n", "á")) {
            assertThat(AppSocialLogin.isChallenge(CHALLENGE.substring(1) + sign)).as(sign).isFalse();
        }
    }

    @Test
    void withoutAChallengeTheWebsitesRequestGoesOutAsItIs() {
        OAuth2AuthorizationRequest website = websiteRequest();
        OAuth2AuthorizationRequestResolver resolver = AppSocialLogin.resolver(fixed(website));

        assertThat(resolver.resolve(start())).isSameAs(website);
        assertThat(resolver.resolve(start(), "google")).isSameAs(website);
        // not the button's address: nothing to do either way
        MockHttpServletRequest page = new MockHttpServletRequest("GET", "/blog");
        page.addParameter(AppSocialLogin.PARAMETER, CHALLENGE);
        assertThat(AppSocialLogin.resolver(fixed(null)).resolve(page)).isNull();
    }

    @Test
    void aChallengeOnlyAddsAnAttributeTheProviderNeverSees() {
        OAuth2AuthorizationRequest website = websiteRequest();
        OAuth2AuthorizationRequestResolver resolver = AppSocialLogin.resolver(fixed(website));
        MockHttpServletRequest app = start();
        app.addParameter(AppSocialLogin.PARAMETER, CHALLENGE);

        for (OAuth2AuthorizationRequest request : List.of(resolver.resolve(app), resolver.resolve(app, "google"))) {
            assertThat(request.getAuthorizationRequestUri()).isEqualTo(website.getAuthorizationRequestUri())
                    .doesNotContain(CHALLENGE);
            assertThat(request.getAuthorizationUri()).isEqualTo(website.getAuthorizationUri());
            assertThat(request.getState()).isEqualTo(website.getState());
            assertThat(request.getRedirectUri()).isEqualTo("https://senpai.example.org/login/oauth2/code/google");
            assertThat(request.getClientId()).isEqualTo(website.getClientId());
            assertThat(request.getScopes()).isEqualTo(website.getScopes());
            assertThat(request.getAdditionalParameters()).isEqualTo(website.getAdditionalParameters());
            assertThat(request.getAttributes()).containsAllEntriesOf(website.getAttributes())
                    .containsEntry(AppSocialLogin.ATTRIBUTE, CHALLENGE)
                    .hasSize(website.getAttributes().size() + 1);
        }
    }

    @Test
    void aMalformedChallengeMeansNoRequestAtAll() {
        OAuth2AuthorizationRequestResolver resolver = AppSocialLogin.resolver(fixed(websiteRequest()));
        for (String challenge : List.of("", "abc", CHALLENGE + "A", CHALLENGE.substring(1) + "=")) {
            MockHttpServletRequest app = start();
            app.addParameter(AppSocialLogin.PARAMETER, challenge);
            assertThat(resolver.resolve(app)).as(challenge).isNull();
            assertThat(resolver.resolve(app, "google")).as(challenge).isNull();
        }
        MockHttpServletRequest twice = start();
        twice.addParameter(AppSocialLogin.PARAMETER, CHALLENGE, CHALLENGE);
        assertThat(resolver.resolve(twice)).isNull();
    }

    @Test
    void takingOutAnAppRoundNotesItsChallengeAndTheBrowsersSignInFromBefore() {
        AuthorizationRequestRepository<OAuth2AuthorizationRequest> repository =
                AppSocialLogin.authorizationRequests(new HttpSessionSecurityContextRepository());
        OAuth2AuthorizationRequestResolver resolver =
                AppSocialLogin.resolver(SocialRedirectUri.resolver(registrations(), "https://senpai.example.org"));

        // a member is signed in to the website in this browser
        MockHttpSession browser = new MockHttpSession();
        SecurityContext member = new SecurityContextImpl(
                new UsernamePasswordAuthenticationToken("member", null, List.of()));
        browser.setAttribute(SPRING_SECURITY_CONTEXT_KEY, member);
        MockHttpServletRequest app = start();
        app.setSession(browser);
        app.addParameter(AppSocialLogin.PARAMETER, CHALLENGE);
        OAuth2AuthorizationRequest saved = resolver.resolve(app);
        repository.saveAuthorizationRequest(saved, app, new MockHttpServletResponse());

        // a callback with another state finds nothing, so nothing is marked
        MockHttpServletRequest wrongState = callback(browser, "not-the-state");
        assertThat(repository.removeAuthorizationRequest(wrongState, new MockHttpServletResponse())).isNull();
        assertThat(AppSocialLogin.challenge(wrongState)).isEmpty();
        assertThat(AppSocialLogin.previousContext(wrongState)).isEmpty();

        MockHttpServletRequest callback = callback(browser, saved.getState());
        assertThat(repository.loadAuthorizationRequest(callback)).isNotNull();
        assertThat(AppSocialLogin.challenge(callback)).isEmpty();   // loading alone marks nothing
        OAuth2AuthorizationRequest removed = repository.removeAuthorizationRequest(callback, new MockHttpServletResponse());

        assertThat(removed.getAttributes()).containsEntry(AppSocialLogin.ATTRIBUTE, CHALLENGE);
        assertThat(AppSocialLogin.challenge(callback)).contains(CHALLENGE);
        assertThat(AppSocialLogin.previousContext(callback)).containsSame(member);
        assertThat(repository.loadAuthorizationRequest(callback(browser, saved.getState()))).isNull();

        // nobody signed in before: an empty context
        MockHttpSession fresh = new MockHttpSession();
        MockHttpServletRequest freshApp = start();
        freshApp.setSession(fresh);
        freshApp.addParameter(AppSocialLogin.PARAMETER, CHALLENGE);
        OAuth2AuthorizationRequest freshSaved = resolver.resolve(freshApp);
        repository.saveAuthorizationRequest(freshSaved, freshApp, new MockHttpServletResponse());
        MockHttpServletRequest freshCallback = callback(fresh, freshSaved.getState());
        repository.removeAuthorizationRequest(freshCallback, new MockHttpServletResponse());
        assertThat(AppSocialLogin.challenge(freshCallback)).contains(CHALLENGE);
        assertThat(AppSocialLogin.previousContext(freshCallback)).hasValueSatisfying(
                context -> assertThat(context.getAuthentication()).isNull());
    }

    @Test
    void takingOutTheWebsitesOwnRoundMarksNothing() {
        AuthorizationRequestRepository<OAuth2AuthorizationRequest> repository =
                AppSocialLogin.authorizationRequests(new HttpSessionSecurityContextRepository());
        MockHttpSession browser = new MockHttpSession();
        MockHttpServletRequest website = start();
        website.setSession(browser);
        OAuth2AuthorizationRequest saved = AppSocialLogin.resolver(
                SocialRedirectUri.resolver(registrations(), "https://senpai.example.org")).resolve(website);
        repository.saveAuthorizationRequest(saved, website, new MockHttpServletResponse());

        MockHttpServletRequest callback = callback(browser, saved.getState());
        assertThat(repository.removeAuthorizationRequest(callback, new MockHttpServletResponse())).isNotNull();

        assertThat(AppSocialLogin.challenge(callback)).isEmpty();
        assertThat(AppSocialLogin.previousContext(callback)).isEmpty();
    }

    @Test
    void theAppsAddressGetsOneParameter() {
        assertThat(AppSocialLogin.callback("senpaioi://auth", "code", "Ab9_-" + "x".repeat(38)))
                .isEqualTo("senpaioi://auth?code=Ab9_-" + "x".repeat(38));
        assertThat(AppSocialLogin.callback("senpaioi://auth", "error", "social_no_email"))
                .isEqualTo("senpaioi://auth?error=social_no_email");
        assertThat(AppSocialLogin.callback("myapp://login/done?v=1", "error", "social"))
                .isEqualTo("myapp://login/done?v=1&error=social");
    }

    // ---------------------------------------------------------------- helpers

    /** A resolver that always answers the same request (the website's resolver makes a new state every time). */
    private static OAuth2AuthorizationRequestResolver fixed(OAuth2AuthorizationRequest request) {
        return new OAuth2AuthorizationRequestResolver() {
            @Override
            public OAuth2AuthorizationRequest resolve(HttpServletRequest servletRequest) {
                return request;
            }

            @Override
            public OAuth2AuthorizationRequest resolve(HttpServletRequest servletRequest, String clientRegistrationId) {
                return request;
            }
        };
    }

    private static OAuth2AuthorizationRequest websiteRequest() {
        return SocialRedirectUri.resolver(registrations(), "https://senpai.example.org").resolve(start());
    }

    /** The "Google" button's request; the resolver matches the servlet path, as on the real server. */
    private static MockHttpServletRequest start() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/oauth2/authorization/google");
        request.setServletPath("/oauth2/authorization/google");
        return request;
    }

    private static MockHttpServletRequest callback(MockHttpSession browser, String state) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/login/oauth2/code/google");
        request.setSession(browser);
        request.addParameter("code", "provider-code");
        request.addParameter("state", state);
        return request;
    }

    private static InMemoryClientRegistrationRepository registrations() {
        return new InMemoryClientRegistrationRepository(ClientRegistration.withRegistrationId("google")
                .clientId("id").clientSecret("secret")
                .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .redirectUri("{baseUrl}/login/oauth2/code/{registrationId}")
                .scope("openid", "email", "profile")
                .authorizationUri("https://accounts.example/authorize")
                .tokenUri("https://accounts.example/token")
                .build());
    }
}
