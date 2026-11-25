package com.sep490.vsa.feature.auth;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The app's Google sign-in over real HTTP on the embedded Tomcat (the rest is in {@link SocialAppLoginFlowTest}): the
 * browser really gets {@code Location: senpaioi://auth?code=…} untouched, and the app, with cookies of its own, trades
 * the code for its session with the CSRF token like the app's ApiClient. Local MySQL ({@code asvsj_test}) and the fake
 * Google of {@link FakeSocialProvider}; nothing here reaches the internet.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.datasource.url=jdbc:mysql://localhost:3306/asvsj_test?createDatabaseIfNotExist=true&useUnicode=true&characterEncoding=utf8&connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true",
        "app.media.local-dir=target/test-uploads",
        "app.moderation.retry-initial-delay-ms=3600000",
        "app.places.osm.enabled=false",
        "spring.security.oauth2.client.registration.google.client-id=google-test-client",
        "spring.security.oauth2.client.registration.google.client-secret=google-test-secret",
        "spring.security.oauth2.client.registration.google.scope=openid,profile,email"
})
@ActiveProfiles("local")
class SocialAppLoginHttpTest {

    private static final FakeSocialProvider PROVIDER = FakeSocialProvider.start();

    @DynamicPropertySource
    static void providerAddresses(DynamicPropertyRegistry registry) {
        PROVIDER.register(registry);
    }

    @AfterAll
    static void stopProvider() {
        PROVIDER.stop();
    }

    @LocalServerPort
    private int port;
    @Autowired
    private JdbcTemplate jdbc;

    private final ObjectMapper json = new ObjectMapper();
    private final String email = "social-app-http-" + UUID.randomUUID().toString().substring(0, 8) + "@gmail.test";

    @AfterEach
    void cleanUp() {
        jdbc.update("DELETE FROM users WHERE email = ?", email);
    }

    @Test
    void onTomcatTheBrowserGoesToTheAppAndTheAppGetsItsOwnSession() throws Exception {
        PROVIDER.google("g-http-" + email, email, true, "Http App");
        String verifier = randomVerifier();
        String challenge = Base64.getUrlEncoder().withoutPadding().encodeToString(
                MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII)));

        // The phone's browser (Chrome's cookies)
        HttpClient browser = client();
        HttpResponse<String> start = browser.send(get("/oauth2/authorization/google?app_challenge=" + challenge),
                HttpResponse.BodyHandlers.ofString());
        assertThat(start.statusCode()).isEqualTo(302);
        String toGoogle = start.headers().firstValue("Location").orElseThrow();
        assertThat(toGoogle).startsWith(PROVIDER.url() + "/google/authorize?").doesNotContain(challenge);
        var query = UriComponentsBuilder.fromUriString(toGoogle).build().getQueryParams();
        PROVIDER.nonce = URLDecoder.decode(query.getFirst("nonce"), StandardCharsets.UTF_8);
        String state = query.getFirst("state");

        HttpResponse<String> back = browser.send(get("/login/oauth2/code/google?code=http-code&state=" + state),
                HttpResponse.BodyHandlers.ofString());
        assertThat(back.statusCode()).isEqualTo(302);
        String location = back.headers().firstValue("Location").orElseThrow();
        assertThat(location).matches("senpaioi://auth\\?code=[A-Za-z0-9_-]{43}");
        String code = location.substring("senpaioi://auth?code=".length());
        assertThat(browser.send(get("/api/auth/me"), HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(401);

        // The app: cookies of its own, the CSRF token from /api/auth/csrf in X-XSRF-TOKEN
        HttpClient app = client();
        String token = json.readTree(app.send(get("/api/auth/csrf"), HttpResponse.BodyHandlers.ofString()).body())
                .path("data").path("token").asText();
        HttpResponse<String> exchanged = app.send(post("/api/auth/social/app/exchange", token,
                "{\"code\":\"" + code + "\",\"verifier\":\"" + verifier + "\"}"), HttpResponse.BodyHandlers.ofString());
        assertThat(exchanged.statusCode()).isEqualTo(200);
        JsonNode body = json.readTree(exchanged.body());
        assertThat(body.path("message").asText()).isEqualTo("Hãy hoàn tất đăng ký.");
        assertThat(body.path("data").path("status").asText()).isEqualTo("SIGNUP_REQUIRED");
        assertThat(body.path("data").path("user").isNull()).isTrue();
        assertThat(body.path("data").path("pending").path("email").asText()).isEqualTo(email);

        HttpResponse<String> completed = app.send(post("/api/auth/social/complete", token,
                "{\"displayName\":\"Http App\",\"adultConfirmed\":true,\"acceptTerms\":true}"),
                HttpResponse.BodyHandlers.ofString());
        assertThat(completed.statusCode()).isEqualTo(200);
        HttpResponse<String> me = app.send(get("/api/auth/me"), HttpResponse.BodyHandlers.ofString());
        assertThat(me.statusCode()).isEqualTo(200);
        assertThat(json.readTree(me.body()).path("data").path("email").asText()).isEqualTo(email);
        // the browser is still nobody's
        assertThat(browser.send(get("/api/auth/me"), HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(401);
    }

    private static HttpClient client() {
        return HttpClient.newBuilder().cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL))
                .followRedirects(HttpClient.Redirect.NEVER).build();
    }

    private HttpRequest get(String path) {
        return HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).GET().build();
    }

    private HttpRequest post(String path, String csrfToken, String body) {
        return HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Content-Type", "application/json").header("X-XSRF-TOKEN", csrfToken)
                .POST(HttpRequest.BodyPublishers.ofString(body)).build();
    }

    private static String randomVerifier() {
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
