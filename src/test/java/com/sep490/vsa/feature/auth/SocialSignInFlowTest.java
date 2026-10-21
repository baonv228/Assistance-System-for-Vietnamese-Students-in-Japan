package com.sep490.vsa.feature.auth;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.test.context.ActiveProfiles;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.web.context.HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY;

/**
 * Google sign-in after Google has confirmed the person ({@link SocialLoginSuccessHandler}, with a made-up Google
 * profile), against MySQL ({@code asvsj_test}): a locked or deleted account is refused and nothing is written for it
 * (no login time, the password lock and wrong-password count stay, no Google link is added); an active account signs
 * in as before.
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:mysql://localhost:3306/asvsj_test?createDatabaseIfNotExist=true&useUnicode=true&characterEncoding=utf8&connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true",
        "app.media.local-dir=target/test-uploads",
        "app.moderation.retry-initial-delay-ms=3600000",
        "app.places.osm.enabled=false"
})
@AutoConfigureMockMvc
@ActiveProfiles("local")
class SocialSignInFlowTest {

    @Autowired
    private SocialLoginSuccessHandler handler;
    @Autowired
    private JdbcTemplate jdbc;

    private final String tag = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
    private final List<Long> users = new ArrayList<>();

    @AfterEach
    void cleanUp() {
        for (long id : users) {
            jdbc.update("DELETE FROM users WHERE id = ?", id);   // its Google links go with it (ON DELETE CASCADE)
        }
    }

    @Test
    void aLockedAccountFoundByEmailIsRefusedAndNothingIsRecorded() throws Exception {
        Instant lockedUntil = Instant.now().plus(Duration.ofMinutes(20)).truncatedTo(ChronoUnit.SECONDS);
        long id = user("locked", "LOCKED", 3, lockedUntil);

        MockHttpServletResponse response = signInWithGoogle("g-locked-" + tag, email(id));

        assertThat(response.getRedirectedUrl()).isEqualTo("/login?error=account_locked");
        assertUntouched(id, 3, lockedUntil);
        assertThat(googleLinks(id)).isZero();
    }

    @Test
    void aLockedOrDeletedAccountFoundByItsGoogleLinkIsRefusedAndNothingIsRecorded() throws Exception {
        for (String status : new String[] {"LOCKED", "DELETED"}) {
            long id = user("linked" + status, status, 2, null);
            String googleId = "g-linked-" + status + tag;
            jdbc.update("INSERT INTO user_auth_providers (user_id, provider, provider_user_id) VALUES (?, 'GOOGLE', ?)",
                    id, googleId);

            MockHttpServletResponse response = signInWithGoogle(googleId, "other" + status + tag + "@gmail.test");

            assertThat(response.getRedirectedUrl()).as(status).isEqualTo("/login?error=account_locked");
            assertUntouched(id, 2, null);
            assertThat(googleLinks(id)).as(status).isEqualTo(1);
        }
    }

    @Test
    void anActiveAccountSignsInAndGetsItsGoogleLink() throws Exception {
        long id = user("active", "ACTIVE", 2, null);

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/login/oauth2/code/google");
        MockHttpServletResponse response = signInWithGoogle(request, "g-active-" + tag, email(id));

        assertThat(response.getRedirectedUrl()).isEqualTo("/");
        assertThat(request.getSession(false).getAttribute(SPRING_SECURITY_CONTEXT_KEY)).isNotNull();
        Map<String, Object> row = jdbc.queryForMap(
                "SELECT last_login_at, failed_login_count FROM users WHERE id = ?", id);
        assertThat(row.get("last_login_at")).isNotNull();
        assertThat(((Number) row.get("failed_login_count")).intValue()).isZero();
        assertThat(googleLinks(id)).isEqualTo(1);
    }

    // ---------------------------------------------------------------- helpers

    private MockHttpServletResponse signInWithGoogle(String googleId, String email) throws Exception {
        return signInWithGoogle(new MockHttpServletRequest("GET", "/login/oauth2/code/google"), googleId, email);
    }

    /** What Spring Security hands the handler once Google has confirmed the person. */
    private MockHttpServletResponse signInWithGoogle(MockHttpServletRequest request, String googleId, String email)
            throws Exception {
        OAuth2User google = new DefaultOAuth2User(List.of(new SimpleGrantedAuthority("OAUTH2_USER")),
                Map.of("sub", googleId, "email", email, "email_verified", true, "name", "Google Test"), "sub");
        OAuth2AuthenticationToken token = new OAuth2AuthenticationToken(google, google.getAuthorities(), "google");
        MockHttpServletResponse response = new MockHttpServletResponse();
        handler.onAuthenticationSuccess(request, response, token);
        return response;
    }

    private void assertUntouched(long id, int failedCount, Instant lockedUntil) {
        Map<String, Object> row = jdbc.queryForMap(
                "SELECT last_login_at, failed_login_count, locked_until FROM users WHERE id = ?", id);
        assertThat(row.get("last_login_at")).isNull();
        assertThat(((Number) row.get("failed_login_count")).intValue()).isEqualTo(failedCount);
        Timestamp storedLock = jdbc.queryForObject("SELECT locked_until FROM users WHERE id = ?", Timestamp.class, id);
        if (lockedUntil == null) {
            assertThat(storedLock).isNull();
        } else {
            assertThat(storedLock.toInstant()).isEqualTo(lockedUntil);
        }
    }

    private long googleLinks(long userId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM user_auth_providers WHERE user_id = ?", Long.class, userId);
    }

    private String email(long id) {
        return jdbc.queryForObject("SELECT email FROM users WHERE id = ?", String.class, id);
    }

    private long user(String name, String status, int failedCount, Instant lockedUntil) {
        String email = "social-" + name.toLowerCase() + "-" + tag + "@gmail.test";
        jdbc.update("""
                INSERT INTO users (email, password_hash, display_name, role, status, is_adult_confirmed,
                                   failed_login_count, locked_until, deleted_at)
                VALUES (?, NULL, 'Social Test', 'USER', ?, TRUE, ?, ?, ?)""",
                email, status, failedCount, lockedUntil == null ? null : Timestamp.from(lockedUntil),
                "DELETED".equals(status) ? Timestamp.from(Instant.now()) : null);
        long id = jdbc.queryForObject("SELECT id FROM users WHERE email = ?", Long.class, email);
        users.add(id);
        return id;
    }
}
