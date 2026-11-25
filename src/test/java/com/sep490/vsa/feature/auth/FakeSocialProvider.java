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
import org.springframework.test.context.DynamicPropertyRegistry;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * A Google and a Facebook that live on this PC, for the flow tests of the social sign-in
 * ({@link SocialLoginOAuthFlowTest}, {@link SocialAppLoginFlowTest}): the token, key and profile addresses of both
 * providers, answering what the test wants them to say. The authorize address is never called (the test reads the
 * redirect to it and comes back with a code itself). Nothing here reaches the internet.
 */
final class FakeSocialProvider {

    static final String GOOGLE_ISSUER = "https://accounts.google.com";

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

    private FakeSocialProvider(HttpServer server, RSAKey key) {
        this.server = server;
        this.key = key;
    }

    static FakeSocialProvider start() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
            FakeSocialProvider provider = new FakeSocialProvider(server,
                    new RSAKeyGenerator(2048).keyID("test-key").generate());
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

    /** The provider addresses Spring Security calls, pointed at this fake (for a {@code @DynamicPropertySource}). */
    void register(DynamicPropertyRegistry registry) {
        String url = url();
        registry.add("spring.security.oauth2.client.provider.google.authorization-uri", () -> url + "/google/authorize");
        registry.add("spring.security.oauth2.client.provider.google.token-uri", () -> url + "/google/token");
        registry.add("spring.security.oauth2.client.provider.google.jwk-set-uri", () -> url + "/google/jwks");
        registry.add("spring.security.oauth2.client.provider.google.user-info-uri", () -> url + "/google/userinfo");
        registry.add("spring.security.oauth2.client.provider.facebook.authorization-uri", () -> url + "/facebook/authorize");
        registry.add("spring.security.oauth2.client.provider.facebook.token-uri", () -> url + "/facebook/token");
        registry.add("spring.security.oauth2.client.provider.facebook.user-info-uri", () -> url + "/facebook/me?fields=id,name,email");
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
