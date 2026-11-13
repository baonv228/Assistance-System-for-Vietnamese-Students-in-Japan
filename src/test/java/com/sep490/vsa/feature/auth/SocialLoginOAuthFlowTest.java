package com.sep490.vsa.feature.auth;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.util.UriComponentsBuilder;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The whole Google / Facebook sign-in as Spring Security runs it on a server, against MySQL ({@code asvsj_test}) and
 * a fake Google/Facebook on a local port: the page sends the person to the provider with the redirect_uri taken from
 * {@code app.public-url} (not from the request, so a proxy that hides the real address changes nothing), the provider
 * answers with a code, the server exchanges it (the same redirect_uri, the client id and secret), reads the signed
 * id token (Google) or the profile (Facebook) and then signs in, links by email, asks a new person to finish sign-up, or
 * sends the person back with the right message. Nothing here reaches the internet.
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:mysql://localhost:3306/asvsj_test?createDatabaseIfNotExist=true&useUnicode=true&characterEncoding=utf8&connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true",
        "app.media.local-dir=target/test-uploads",
        "app.moderation.retry-initial-delay-ms=3600000",
        "app.places.osm.enabled=false",
        // What a server sets (APP_PUBLIC_URL); the trailing "/" is a typical slip
        "app.public-url=https://senpai-test.example.org/",
        "spring.security.oauth2.client.registration.google.client-id=google-test-client",
        "spring.security.oauth2.client.registration.google.client-secret=google-test-secret",
        "spring.security.oauth2.client.registration.google.scope=openid,profile,email",
        "spring.security.oauth2.client.registration.facebook.client-id=facebook-test-app",
        "spring.security.oauth2.client.registration.facebook.client-secret=facebook-test-secret",
        "spring.security.oauth2.client.registration.facebook.scope=email,public_profile"
})
@AutoConfigureMockMvc
@ActiveProfiles("local")
class SocialLoginOAuthFlowTest {

    private static final String CSRF = "test-csrf-token";
    private static final String BASE = "https://senpai-test.example.org";
    private static final String GOOGLE_ISSUER = "https://accounts.google.com";

    private static final FakeProvider PROVIDER = FakeProvider.start();

    @DynamicPropertySource
    static void providerAddresses(DynamicPropertyRegistry registry) {
        String url = PROVIDER.url();
        registry.add("spring.security.oauth2.client.provider.google.authorization-uri", () -> url + "/google/authorize");
        registry.add("spring.security.oauth2.client.provider.google.token-uri", () -> url + "/google/token");
        registry.add("spring.security.oauth2.client.provider.google.jwk-set-uri", () -> url + "/google/jwks");
        registry.add("spring.security.oauth2.client.provider.google.user-info-uri", () -> url + "/google/userinfo");
        registry.add("spring.security.oauth2.client.provider.facebook.authorization-uri", () -> url + "/facebook/authorize");
        registry.add("spring.security.oauth2.client.provider.facebook.token-uri", () -> url + "/facebook/token");
        registry.add("spring.security.oauth2.client.provider.facebook.user-info-uri", () -> url + "/facebook/me?fields=id,name,email");
    }

    @AfterAll
    static void stopProvider() {
        PROVIDER.stop();
    }

    @Autowired
    private MockMvc mvc;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private SocialLoginReport report;

    private final String tag = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
    private final List<String> emails = new ArrayList<>();

    @BeforeEach
    void reset() {
        PROVIDER.reset();
    }

    @AfterEach
    void cleanUp() {
        for (String email : emails) {
            jdbc.update("DELETE FROM users WHERE email = ?", email);   // its provider links go with it (ON DELETE CASCADE)
        }
    }

    // ---------------------------------------------------------------- the page

    @Test
    void bothProvidersAreOnForTheButtons() throws Exception {
        mvc.perform(get("/api/auth/providers"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.google").value(true))
                .andExpect(jsonPath("$.data.facebook").value(true));
    }

    @Test
    void theStartUpLogTellsWhichRedirectUrisToRegister() {
        List<String> lines = report.lines();

        assertThat(lines).anyMatch(line -> line.contains("google") && line.endsWith(BASE + "/login/oauth2/code/google"));
        assertThat(lines).anyMatch(line -> line.contains("facebook") && line.endsWith(BASE + "/login/oauth2/code/facebook"));
        assertThat(lines).noneMatch(line -> line.startsWith("WARN "));   // https and a real address: nothing to warn about
    }

    // ---------------------------------------------------------------- Google

    @Test
    void aNewPersonSignsUpWithGoogleAndThenSignsInAgain() throws Exception {
        String email = email("google-new");
        String sub = "g-" + tag;
        PROVIDER.google(sub, email, true, "Nguyen Van Google");

        // 1. "Đăng nhập bằng Google": sent to Google, asking to come back to the registered address
        MvcResult start = mvc.perform(get("/oauth2/authorization/google")).andExpect(status().is3xxRedirection()).andReturn();
        Map<String, String> authorize = queryOf(start.getResponse().getRedirectedUrl());
        assertThat(start.getResponse().getRedirectedUrl()).startsWith(PROVIDER.url() + "/google/authorize?");
        assertThat(authorize.get("redirect_uri")).isEqualTo(BASE + "/login/oauth2/code/google");
        assertThat(authorize.get("client_id")).isEqualTo("google-test-client");
        assertThat(authorize.get("scope")).contains("openid", "email", "profile");
        assertThat(authorize.get("response_type")).isEqualTo("code");
        assertThat(authorize.get("state")).isNotBlank();
        PROVIDER.nonce = authorize.get("nonce");
        MockHttpSession session = (MockHttpSession) start.getRequest().getSession(false);
        assertThat(session).isNotNull();

        // 2. Google sends the person back with a code → the first time: finish the sign-up
        MvcResult back = mvc.perform(get("/login/oauth2/code/google").param("code", "code-1")
                .param("state", authorize.get("state")).session(session)).andReturn();
        assertThat(back.getResponse().getRedirectedUrl()).isEqualTo("/complete-signup");
        FakeProvider.TokenCall token = PROVIDER.tokenCalls.get(0);
        assertThat(token.form.get("redirect_uri")).isEqualTo(BASE + "/login/oauth2/code/google");
        assertThat(token.form.get("code")).isEqualTo("code-1");
        assertThat(token.form.get("grant_type")).isEqualTo("authorization_code");
        assertThat(token.basicAuth).isEqualTo(FakeProvider.basic("google-test-client", "google-test-secret"));
        session = (MockHttpSession) back.getRequest().getSession(false);

        mvc.perform(get("/api/auth/social/pending").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.provider").value("GOOGLE"))
                .andExpect(jsonPath("$.data.email").value(email))
                .andExpect(jsonPath("$.data.displayName").value("Nguyen Van Google"));
        // nobody is signed in yet
        assertThat(session.getAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY)).isNull();

        // 3. 18+ and the terms → the account exists, with its Google link, and the person is signed in
        mvc.perform(csrf(post("/api/auth/social/complete").session(session).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"displayName\":\"Van Google\",\"adultConfirmed\":true,\"acceptTerms\":true}")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.email").value(email));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM user_auth_providers p JOIN users u ON u.id = p.user_id "
                + "WHERE u.email = ? AND p.provider = 'GOOGLE' AND p.provider_user_id = ?", Long.class, email, sub)).isEqualTo(1);
        mvc.perform(get("/api/auth/me").session(session)).andExpect(jsonPath("$.data.email").value(email));

        // 4. another day, another browser: Google again → straight in, to the home page
        PROVIDER.reset();
        PROVIDER.google(sub, email, true, "Nguyen Van Google");
        MvcResult second = signInAgain("google");
        assertThat(second.getResponse().getRedirectedUrl()).isEqualTo("/");
        mvc.perform(get("/api/auth/me").session((MockHttpSession) second.getRequest().getSession(false)))
                .andExpect(jsonPath("$.data.email").value(email));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM users WHERE email = ?", Long.class, email)).isEqualTo(1);
    }

    @Test
    void anExistingEmailAccountGetsItsGoogleLinkAndSignsIn() throws Exception {
        String email = email("google-existing");
        jdbc.update("""
                INSERT INTO users (email, password_hash, display_name, role, status, is_adult_confirmed)
                VALUES (?, NULL, 'Da co tai khoan', 'USER', 'ACTIVE', TRUE)""", email);
        PROVIDER.google("g-existing-" + tag, email.toUpperCase(), true, "Ten Khac");

        MvcResult back = signInAgain("google");

        assertThat(back.getResponse().getRedirectedUrl()).isEqualTo("/");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM user_auth_providers p JOIN users u ON u.id = p.user_id "
                + "WHERE u.email = ? AND p.provider = 'GOOGLE'", Long.class, email)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM users WHERE email = ?", Long.class, email)).isEqualTo(1);
    }

    @Test
    void aGoogleEmailGoogleHasNotVerifiedIsNotTrusted() throws Exception {
        PROVIDER.google("g-unverified-" + tag, email("google-unverified"), false, "Chua Xac Minh");

        MvcResult back = signInAgain("google");

        assertThat(back.getResponse().getRedirectedUrl()).isEqualTo("/login?error=social_no_email");
    }

    @Test
    void anIdTokenForAnotherAppIsRefused() throws Exception {
        PROVIDER.google("g-other-" + tag, email("google-other-app"), true, "App Khac");
        PROVIDER.audience = "some-other-client";

        MvcResult back = signInAgain("google");

        assertThat(back.getResponse().getRedirectedUrl()).isEqualTo("/login?error=social");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM users WHERE email = ?", Long.class, email("google-other-app"))).isZero();
    }

    @Test
    void pressingCancelAtGoogleComesBackWithAFriendlyMessage() throws Exception {
        MvcResult start = mvc.perform(get("/oauth2/authorization/google")).andReturn();
        Map<String, String> authorize = queryOf(start.getResponse().getRedirectedUrl());

        MvcResult back = mvc.perform(get("/login/oauth2/code/google").param("error", "access_denied")
                .param("state", authorize.get("state")).session((MockHttpSession) start.getRequest().getSession(false)))
                .andReturn();

        assertThat(back.getResponse().getRedirectedUrl()).isEqualTo("/login?error=social_cancelled");
        assertThat(PROVIDER.tokenCalls).isEmpty();
    }

    @Test
    void aCallbackWithAWrongStateIsRefusedAndNothingIsExchanged() throws Exception {
        MvcResult start = mvc.perform(get("/oauth2/authorization/google")).andReturn();

        MvcResult back = mvc.perform(get("/login/oauth2/code/google").param("code", "stolen").param("state", "not-the-state")
                .session((MockHttpSession) start.getRequest().getSession(false))).andReturn();

        assertThat(back.getResponse().getRedirectedUrl()).isEqualTo("/login?error=social");
        assertThat(PROVIDER.tokenCalls).isEmpty();
    }

    @Test
    void aCallbackWithoutTheSessionThatStartedItIsRefused() throws Exception {
        MvcResult start = mvc.perform(get("/oauth2/authorization/google")).andReturn();
        Map<String, String> authorize = queryOf(start.getResponse().getRedirectedUrl());

        MvcResult back = mvc.perform(get("/login/oauth2/code/google").param("code", "code-x")
                .param("state", authorize.get("state"))).andReturn();   // another browser: no session

        assertThat(back.getResponse().getRedirectedUrl()).isEqualTo("/login?error=social");
        assertThat(PROVIDER.tokenCalls).isEmpty();
    }

    @Test
    void aWrongClientSecretOrAnOfflineProviderSendsThePersonBackWithAnError() throws Exception {
        PROVIDER.google("g-fail-" + tag, email("google-fail"), true, "Loi");
        PROVIDER.tokenStatus = 401;   // what Google answers to a wrong client secret: invalid_client

        MvcResult back = signInAgain("google");

        assertThat(back.getResponse().getRedirectedUrl()).isEqualTo("/login?error=social");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM users WHERE email = ?", Long.class, email("google-fail"))).isZero();
    }

    // ---------------------------------------------------------------- Facebook

    @Test
    void aNewPersonSignsUpWithFacebookAndThenSignsInAgain() throws Exception {
        String email = email("facebook-new");
        String id = "fb" + tag;
        PROVIDER.facebook(id, email, "Nguyen Van Facebook");

        MvcResult start = mvc.perform(get("/oauth2/authorization/facebook")).andExpect(status().is3xxRedirection()).andReturn();
        Map<String, String> authorize = queryOf(start.getResponse().getRedirectedUrl());
        assertThat(start.getResponse().getRedirectedUrl()).startsWith(PROVIDER.url() + "/facebook/authorize?");
        assertThat(authorize.get("redirect_uri")).isEqualTo(BASE + "/login/oauth2/code/facebook");
        assertThat(authorize.get("client_id")).isEqualTo("facebook-test-app");
        assertThat(authorize.get("scope")).contains("email", "public_profile");

        MvcResult back = mvc.perform(get("/login/oauth2/code/facebook").param("code", "fb-code")
                .param("state", authorize.get("state")).session((MockHttpSession) start.getRequest().getSession(false))).andReturn();
        assertThat(back.getResponse().getRedirectedUrl()).isEqualTo("/complete-signup");
        FakeProvider.TokenCall token = PROVIDER.tokenCalls.get(0);
        assertThat(token.form.get("redirect_uri")).isEqualTo(BASE + "/login/oauth2/code/facebook");
        assertThat(token.form.get("client_id")).isEqualTo("facebook-test-app");
        assertThat(token.form.get("client_secret")).isEqualTo("facebook-test-secret");   // Facebook wants it in the body
        MockHttpSession session = (MockHttpSession) back.getRequest().getSession(false);

        mvc.perform(get("/api/auth/social/pending").session(session))
                .andExpect(jsonPath("$.data.provider").value("FACEBOOK"))
                .andExpect(jsonPath("$.data.email").value(email));
        mvc.perform(csrf(post("/api/auth/social/complete").session(session).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"displayName\":\"Van Facebook\",\"adultConfirmed\":true,\"acceptTerms\":true}")))
                .andExpect(status().isOk());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM user_auth_providers p JOIN users u ON u.id = p.user_id "
                + "WHERE u.email = ? AND p.provider = 'FACEBOOK' AND p.provider_user_id = ?", Long.class, email, id)).isEqualTo(1);

        PROVIDER.reset();
        PROVIDER.facebook(id, email, "Nguyen Van Facebook");
        assertThat(signInAgain("facebook").getResponse().getRedirectedUrl()).isEqualTo("/");
    }

    @Test
    void pressingCancelAtFacebookComesBackWithAFriendlyMessage() throws Exception {
        MvcResult start = mvc.perform(get("/oauth2/authorization/facebook")).andReturn();
        Map<String, String> authorize = queryOf(start.getResponse().getRedirectedUrl());

        // what Facebook sends back after "Hủy" / "Không phải bây giờ"
        MvcResult back = mvc.perform(get("/login/oauth2/code/facebook").param("error", "access_denied")
                .param("error_code", "200").param("error_description", "Permissions error")
                .param("error_reason", "user_denied").param("state", authorize.get("state"))
                .session((MockHttpSession) start.getRequest().getSession(false))).andReturn();

        assertThat(back.getResponse().getRedirectedUrl()).isEqualTo("/login?error=social_cancelled");
        assertThat(PROVIDER.tokenCalls).isEmpty();
    }

    @Test
    void facebookWithoutAnEmailAsksForAnotherWay() throws Exception {
        PROVIDER.facebook("fb-noemail-" + tag, null, "Khong Email");

        MvcResult back = signInAgain("facebook");

        assertThat(back.getResponse().getRedirectedUrl()).isEqualTo("/login?error=social_no_email");
    }

    // ---------------------------------------------------------------- helpers

    /** Presses the button and comes back from the provider with a code, in a browser without any session. */
    private MvcResult signInAgain(String registrationId) throws Exception {
        MvcResult start = mvc.perform(get("/oauth2/authorization/" + registrationId)).andReturn();
        Map<String, String> authorize = queryOf(start.getResponse().getRedirectedUrl());
        PROVIDER.nonce = authorize.get("nonce");
        return mvc.perform(get("/login/oauth2/code/" + registrationId).param("code", "code-" + UUID.randomUUID())
                .param("state", authorize.get("state")).session((MockHttpSession) start.getRequest().getSession(false))).andReturn();
    }

    private static org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder csrf(
            org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request) {
        return request.cookie(new Cookie("XSRF-TOKEN", CSRF)).header("X-XSRF-TOKEN", CSRF);
    }

    private String email(String name) {
        String email = "social-" + name + "-" + tag + "@gmail.test";
        if (!emails.contains(email)) {
            emails.add(email);
        }
        return email;
    }

    private static Map<String, String> queryOf(String url) {
        Map<String, String> query = new LinkedHashMap<>();
        UriComponentsBuilder.fromUriString(url).build().getQueryParams()
                .forEach((key, values) -> query.put(key, URLDecoder.decode(values.get(0), StandardCharsets.UTF_8)));
        return query;
    }

    // ---------------------------------------------------------------- a Google and a Facebook that live on this PC

    /** The token, key and profile addresses of both providers, with what the test wants them to say. */
    private static final class FakeProvider {

        record TokenCall(Map<String, String> form, String basicAuth) {
        }

        private final HttpServer server;
        private final RSAKey key;
        final List<TokenCall> tokenCalls = new CopyOnWriteArrayList<>();
        volatile int tokenStatus = 200;
        volatile String nonce;
        volatile String audience;
        private volatile Map<String, Object> googleProfile;
        private volatile Map<String, Object> facebookProfile;

        private FakeProvider(HttpServer server, RSAKey key) {
            this.server = server;
            this.key = key;
        }

        static FakeProvider start() {
            try {
                HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
                FakeProvider provider = new FakeProvider(server, new RSAKeyGenerator(2048).keyID("test-key").generate());
                server.createContext("/google/jwks", exchange -> provider.json(exchange, 200,
                        new JWKSet(provider.key.toPublicJWK()).toString()));
                server.createContext("/google/token", provider::token);
                server.createContext("/google/userinfo", exchange -> provider.json(exchange, 200, toJson(provider.googleProfile)));
                server.createContext("/facebook/token", provider::token);
                server.createContext("/facebook/me", exchange -> provider.json(exchange, 200, toJson(provider.facebookProfile)));
                server.start();
                return provider;
            } catch (Exception e) {
                throw new IllegalStateException("The fake Google/Facebook could not start", e);
            }
        }

        String url() {
            return "http://127.0.0.1:" + server.getAddress().getPort();
        }

        void stop() {
            server.stop(0);
        }

        void reset() {
            tokenCalls.clear();
            tokenStatus = 200;
            nonce = null;
            audience = null;
            googleProfile = null;
            facebookProfile = null;
        }

        void google(String sub, String email, boolean verified, String name) {
            Map<String, Object> profile = new LinkedHashMap<>();
            profile.put("sub", sub);
            profile.put("email", email);
            profile.put("email_verified", verified);
            profile.put("name", name);
            profile.put("picture", "https://lh3.googleusercontent.com/a/test");
            googleProfile = profile;
        }

        void facebook(String id, String email, String name) {
            Map<String, Object> profile = new LinkedHashMap<>();
            profile.put("id", id);
            profile.put("name", name);
            if (email != null) {
                profile.put("email", email);
            }
            facebookProfile = profile;
        }

        static String basic(String id, String secret) {
            return "Basic " + java.util.Base64.getEncoder().encodeToString((id + ":" + secret).getBytes(StandardCharsets.UTF_8));
        }

        private void token(HttpExchange exchange) throws IOException {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            Map<String, String> form = new LinkedHashMap<>();
            for (String pair : body.split("&")) {
                int eq = pair.indexOf('=');
                if (eq > 0) {
                    form.put(URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8),
                            URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8));
                }
            }
            tokenCalls.add(new TokenCall(form, exchange.getRequestHeaders().getFirst("Authorization")));
            if (tokenStatus != 200) {
                json(exchange, tokenStatus, "{\"error\":\"invalid_client\",\"error_description\":\"Unauthorized\"}");
                return;
            }
            String json = "{\"access_token\":\"access-" + UUID.randomUUID() + "\",\"token_type\":\"Bearer\",\"expires_in\":3600";
            if (exchange.getRequestURI().getPath().startsWith("/google") && googleProfile != null) {
                json += ",\"scope\":\"openid email profile\",\"id_token\":\"" + idToken() + "\"";
            }
            json(exchange, 200, json + "}");
        }

        /** What Google signs: who the person is, for which app, with the nonce the server sent. */
        private String idToken() {
            try {
                Instant now = Instant.now();
                JWTClaimsSet.Builder claims = new JWTClaimsSet.Builder()
                        .issuer(GOOGLE_ISSUER)
                        .audience(audience != null ? audience : "google-test-client")
                        .subject((String) googleProfile.get("sub"))
                        .issueTime(Date.from(now))
                        .expirationTime(Date.from(now.plusSeconds(3600)))
                        .claim("email", googleProfile.get("email"))
                        .claim("email_verified", googleProfile.get("email_verified"))
                        .claim("name", googleProfile.get("name"));
                if (nonce != null) {
                    claims.claim("nonce", nonce);
                }
                SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(key.getKeyID()).build(), claims.build());
                jwt.sign(new RSASSASigner(key));
                return jwt.serialize();
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }

        private void json(HttpExchange exchange, int status, String body) throws IOException {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        }

        private static String toJson(Map<String, Object> map) {
            if (map == null) {
                return "{}";
            }
            StringBuilder out = new StringBuilder("{");
            map.forEach((key, value) -> {
                if (out.length() > 1) {
                    out.append(',');
                }
                out.append('"').append(key).append("\":");
                out.append(value instanceof Boolean ? value.toString() : "\"" + String.valueOf(value).replace("\"", "\\\"") + "\"");
            });
            return out.append('}').toString();
        }
    }
}
