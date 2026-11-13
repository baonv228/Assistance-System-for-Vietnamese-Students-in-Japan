package com.sep490.vsa.common.security;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;

import static org.assertj.core.api.Assertions.assertThat;

class SocialRedirectUriTest {

    @Test
    void onlyARealAddressOfTheSiteIsUsed() {
        assertThat(SocialRedirectUri.publicBase("https://senpai.example.org")).contains("https://senpai.example.org");
        assertThat(SocialRedirectUri.publicBase("  https://senpai.example.org/  ")).contains("https://senpai.example.org");
        assertThat(SocialRedirectUri.publicBase("https://senpai.example.org:8443//")).contains("https://senpai.example.org:8443");
        assertThat(SocialRedirectUri.publicBase("http://203.0.113.7")).contains("http://203.0.113.7");

        // development defaults and mistakes: the request decides, as before
        assertThat(SocialRedirectUri.publicBase("http://localhost:8080")).isEmpty();
        assertThat(SocialRedirectUri.publicBase("http://127.0.0.1:8090")).isEmpty();
        assertThat(SocialRedirectUri.publicBase("http://[::1]:8080")).isEmpty();
        assertThat(SocialRedirectUri.publicBase("")).isEmpty();
        assertThat(SocialRedirectUri.publicBase(null)).isEmpty();
        assertThat(SocialRedirectUri.publicBase("senpai.example.org")).isEmpty();
        assertThat(SocialRedirectUri.publicBase("ftp://senpai.example.org")).isEmpty();
        assertThat(SocialRedirectUri.publicBase("https://bad host")).isEmpty();
    }

    @Test
    void theCallbackAddressIsTheOneToRegisterAtTheProvider() {
        assertThat(SocialRedirectUri.callback("https://senpai.example.org", "google"))
                .isEqualTo("https://senpai.example.org/login/oauth2/code/google");
    }

    @Test
    void withARealAddressTheProxyDoesNotChangeTheRedirectUri() {
        MockHttpServletRequest behindProxy = start();
        behindProxy.setScheme("http");           // what the server sees when the proxy does not pass https
        behindProxy.setServerName("10.0.0.5");   // ...nor the real name
        behindProxy.setServerPort(8080);

        OAuth2AuthorizationRequest request = SocialRedirectUri.resolver(registrations(), "https://senpai.example.org/")
                .resolve(behindProxy);

        assertThat(request.getRedirectUri()).isEqualTo("https://senpai.example.org/login/oauth2/code/google");
    }

    @Test
    void onALocalAddressTheRequestStillDecides() {
        MockHttpServletRequest local = start();
        local.setScheme("http");
        local.setServerName("localhost");
        local.setServerPort(8092);

        OAuth2AuthorizationRequest request = SocialRedirectUri.resolver(registrations(), "http://localhost:8080")
                .resolve(local);

        assertThat(request.getRedirectUri()).isEqualTo("http://localhost:8092/login/oauth2/code/google");
    }

    /** The "Google" button's request; the resolver matches the servlet path, as on the real server. */
    private static MockHttpServletRequest start() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/oauth2/authorization/google");
        request.setServletPath("/oauth2/authorization/google");
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
