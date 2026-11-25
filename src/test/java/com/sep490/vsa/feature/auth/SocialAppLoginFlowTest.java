package com.sep490.vsa.feature.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
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
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.security.web.context.HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.cookie;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Google / Facebook sign-in of the mobile app (contract: design/SOCIAL_LOGIN.md of the app repo), the whole way as
 * Spring Security runs it, against MySQL ({@code asvsj_test}) and the fake Google/Facebook of
 * {@link FakeSocialProvider}: the app opens {@code /oauth2/authorization/{id}?app_challenge=…} in the phone's browser
 * (here: a "browser" session), the provider sends the person back, the browser is sent to {@code senpaioi://auth} with
 * a one-time code or an error, and the app trades the code and its PKCE verifier for its own session (here: an "app"
 * session) at {@code POST /api/auth/social/app/exchange}. The browser is never signed in by the app's round, and a
 * member signed in to the website in that browser stays signed in. Nothing here reaches the internet.
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:mysql://localhost:3306/asvsj_test?createDatabaseIfNotExist=true&useUnicode=true&characterEncoding=utf8&connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true",
        "app.media.local-dir=target/test-uploads",
        "app.moderation.retry-initial-delay-ms=3600000",
        "app.places.osm.enabled=false",
        // What a server sets (APP_PUBLIC_URL): the app's round must ask for exactly the website's redirect_uri
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
class SocialAppLoginFlowTest {

    private static final String CSRF = "test-csrf-token";
    private static final String BASE = "https://senpai-test.example.org";
    /** app.mobile.social-callback is not set: the default. */
    private static final String APP = "senpaioi://auth";
    private static final String EXCHANGE = "/api/auth/social/app/exchange";
    private static final String EXPIRED = "Lần đăng nhập bằng Google hoặc Facebook này đã hết hạn. Vui lòng thử lại.";
    private static final String LOCKED = "Tài khoản này đã bị khóa. Vui lòng liên hệ quản trị viên.";
    private static final String SIGNED_IN = "Đăng nhập thành công.";
    private static final String SIGNUP = "Hãy hoàn tất đăng ký.";
    private static final String COMPLETE = "{\"displayName\":\"Ten Trong App\",\"adultConfirmed\":true,\"acceptTerms\":true}";

    private static final FakeSocialProvider PROVIDER = FakeSocialProvider.start();

    @DynamicPropertySource
    static void providerAddresses(DynamicPropertyRegistry registry) {
        PROVIDER.register(registry);
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
    private SocialAppCodes codes;
    @Autowired
    private Clock clock;

    private final ObjectMapper json = new ObjectMapper();
    private final String tag = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
    private final List<String> emails = new ArrayList<>();

    @BeforeEach
    void reset() {
        PROVIDER.reset();
    }

    @AfterEach
    void cleanUp() {
        codes.useClock(clock);
        for (String email : emails) {
            jdbc.update("DELETE FROM users WHERE email = ?", email);   // its provider links go with it (ON DELETE CASCADE)
        }
    }

    // ---------------------------------------------------------------- Google

    @Test
    void aNewPersonSignsUpWithGoogleInTheApp() throws Exception {
        String email = email("google-new");
        String sub = "g-app-" + tag;
        PROVIDER.google(sub, email, true, "Nguyen Van App");

        // 1. The app opens the Google button's address with its challenge: the browser goes to Google exactly as on
        //    the website (same client, scopes and registered redirect_uri); the challenge stays on the server
        Pkce first = Pkce.create();
        MvcResult started = start("google", first.challenge(), null);
        String toGoogle = started.getResponse().getRedirectedUrl();
        assertThat(toGoogle).startsWith(PROVIDER.url() + "/google/authorize?")
                .doesNotContain(first.challenge()).doesNotContain("app_challenge");
        Map<String, String> authorize = queryOf(toGoogle);
        Map<String, String> website = queryOf(start("google", null, null).getResponse().getRedirectedUrl());
        assertThat(authorize.get("redirect_uri")).isEqualTo(BASE + "/login/oauth2/code/google")
                .isEqualTo(website.get("redirect_uri"));
        assertThat(authorize.keySet()).isEqualTo(website.keySet());
        for (String same : List.of("client_id", "scope", "response_type")) {
            assertThat(authorize.get(same)).as(same).isEqualTo(website.get(same));
        }

        // 2. Google sends the person back: the browser goes to the app with a code. Nobody is signed in in the
        //    browser, no sign-up waits there, and nothing is written yet
        MvcResult back = comeBack("google", started);
        String code = codeFrom(back);
        assertThat(PROVIDER.tokenCalls).hasSize(1);
        assertThat(PROVIDER.tokenCalls.get(0).form().get("redirect_uri")).isEqualTo(BASE + "/login/oauth2/code/google");
        MockHttpSession browser = browserOf(back);
        assertBrowserHoldsNothing(browser);
        mvc.perform(get("/api/auth/me").session(browser)).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/auth/social/pending").session(browser)).andExpect(status().isNotFound());
        assertThat(users(email)).isZero();

        // 3. A wrong verifier (whoever caught the code on its way) is refused and uses the code up: the right
        //    verifier afterwards is too late
        exchange(code, Pkce.create().verifier(), new MockHttpSession())
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").value(EXPIRED));
        exchange(code, first.verifier(), new MockHttpSession())
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").value(EXPIRED));
        assertThat(users(email)).isZero();

        // 4. A fresh round: the profile waits in the app's own session, nobody is signed in yet
        Pkce second = Pkce.create();
        String secondCode = codeFrom(appRound("google", second));
        MockHttpSession app = new MockHttpSession();
        exchange(secondCode, second.verifier(), app)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.message").value(SIGNUP))
                .andExpect(jsonPath("$.data.status").value("SIGNUP_REQUIRED"))
                .andExpect(jsonPath("$.data", hasKey("user")))
                .andExpect(jsonPath("$.data.user").value(nullValue()))
                .andExpect(jsonPath("$.data.pending.provider").value("GOOGLE"))
                .andExpect(jsonPath("$.data.pending.email").value(email))
                .andExpect(jsonPath("$.data.pending.displayName").value("Nguyen Van App"));
        assertThat(users(email)).isZero();
        mvc.perform(get("/api/auth/me").session(app)).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/auth/social/pending").session(app))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.email").value(email));
        // the code is used: sent again, it is refused, and the waiting sign-up is not disturbed
        exchange(secondCode, second.verifier(), app)
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").value(EXPIRED));

        // 5. 18+ and the terms in the app (the existing API, in the app's session): the account exists with its
        //    Google link and the app is signed in; the browser still is not
        mvc.perform(csrf(post("/api/auth/social/complete").session(app).contentType(MediaType.APPLICATION_JSON)
                        .content(COMPLETE)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.email").value(email))
                .andExpect(jsonPath("$.data.displayName").value("Ten Trong App"));
        assertThat(links(email, "GOOGLE", sub)).isEqualTo(1);
        mvc.perform(get("/api/auth/me").session(app))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.email").value(email));
        mvc.perform(get("/api/auth/me").session(browser)).andExpect(status().isUnauthorized());

        // 6. Next time on another phone: straight in
        PROVIDER.reset();
        PROVIDER.google(sub, email, true, "Nguyen Van App");
        Pkce third = Pkce.create();
        MockHttpSession otherPhone = new MockHttpSession();
        exchange(codeFrom(appRound("google", third)), third.verifier(), otherPhone)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value(SIGNED_IN))
                .andExpect(jsonPath("$.data.status").value("SIGNED_IN"))
                .andExpect(jsonPath("$.data.user.email").value(email))
                .andExpect(jsonPath("$.data", hasKey("pending")))
                .andExpect(jsonPath("$.data.pending").value(nullValue()));
        mvc.perform(get("/api/auth/me").session(otherPhone)).andExpect(jsonPath("$.data.email").value(email));
        assertThat(users(email)).isEqualTo(1);
        assertThat(links(email, "GOOGLE", sub)).isEqualTo(1);
    }

    @Test
    void anExistingEmailAccountGetsItsGoogleLinkAndTheAppIsSignedIn() throws Exception {
        String email = email("google-existing");
        long id = user(email, "USER", "ACTIVE");
        String sub = "g-existing-" + tag;
        PROVIDER.google(sub, email.toUpperCase(), true, "Ten Khac");
        // a sign-up left unfinished in the app's session by an earlier try with another account
        MockHttpSession app = new MockHttpSession();
        app.setAttribute(PendingSocialSignup.SESSION_KEY,
                new PendingSocialSignup("FACEBOOK", "fb-left-" + tag, "left-" + tag + "@gmail.test", "Bo Do", null));
        String sessionIdBefore = app.getId();
        Pkce pkce = Pkce.create();
        String code = codeFrom(appRound("google", pkce));

        exchange(code, pkce.verifier(), app)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.message").value(SIGNED_IN))
                .andExpect(jsonPath("$.data.status").value("SIGNED_IN"))
                .andExpect(jsonPath("$.data.user.id").value(id))
                .andExpect(jsonPath("$.data.user.email").value(email))
                .andExpect(jsonPath("$.data.user.role").value("USER"))
                .andExpect(jsonPath("$.data.user.homePath").value("/"))
                .andExpect(jsonPath("$.data.pending").value(nullValue()))
                // as at every sign-in: a new session id, and the CSRF token from before is dropped
                .andExpect(cookie().maxAge("XSRF-TOKEN", 0));
        assertThat(app.getId()).isNotEqualTo(sessionIdBefore);
        assertThat(app.getAttribute(PendingSocialSignup.SESSION_KEY)).isNull();
        mvc.perform(get("/api/auth/me").session(app)).andExpect(jsonPath("$.data.id").value(id));
        assertThat(links(email, "GOOGLE", sub)).isEqualTo(1);
        assertThat(lastLogin(id)).isNotNull();
        // a code works once: the same exchange again is refused and changes nothing
        exchange(code, pkce.verifier(), new MockHttpSession())
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").value(EXPIRED));

        // Another day: found by its Google link this time
        PROVIDER.reset();
        PROVIDER.google(sub, email, true, "Ten Khac");
        Pkce again = Pkce.create();
        MockHttpSession nextTime = new MockHttpSession();
        exchange(codeFrom(appRound("google", again)), again.verifier(), nextTime)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("SIGNED_IN"))
                .andExpect(jsonPath("$.data.user.id").value(id));
        assertThat(links(email, "GOOGLE", sub)).isEqualTo(1);
        assertThat(users(email)).isEqualTo(1);

        // Then a new person in that same app session: signed out while the sign-up waits, as after the website's round
        String newcomer = email("google-newcomer");
        PROVIDER.reset();
        PROVIDER.google("g-newcomer-" + tag, newcomer, true, "Nguoi Moi");
        Pkce third = Pkce.create();
        exchange(codeFrom(appRound("google", third)), third.verifier(), nextTime)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("SIGNUP_REQUIRED"))
                .andExpect(jsonPath("$.data.pending.email").value(newcomer));
        mvc.perform(get("/api/auth/me").session(nextTime)).andExpect(status().isUnauthorized());
        assertThat(nextTime.getAttribute(SPRING_SECURITY_CONTEXT_KEY)).isNull();
    }

    @Test
    void aStaffAccountIsSignedInWithItsRole() throws Exception {
        String email = email("google-staff");
        long id = user(email, "ACADEMIC_MANAGER", "ACTIVE");
        PROVIDER.google("g-staff-" + tag, email, true, "Bien Tap Vien");
        Pkce pkce = Pkce.create();
        MockHttpSession app = new MockHttpSession();

        // The app itself signs staff out again ("Tài khoản quản lý chỉ dùng trên trang web…")
        exchange(codeFrom(appRound("google", pkce)), pkce.verifier(), app)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("SIGNED_IN"))
                .andExpect(jsonPath("$.data.user.id").value(id))
                .andExpect(jsonPath("$.data.user.role").value("ACADEMIC_MANAGER"))
                .andExpect(jsonPath("$.data.user.homePath").value("/academic"));
        mvc.perform(get("/api/auth/me").session(app)).andExpect(jsonPath("$.data.role").value("ACADEMIC_MANAGER"));
    }

    @Test
    void aLockedOrDeletedAccountIsRefusedAndNothingIsWritten() throws Exception {
        // LOCKED, found by its email
        String email = email("google-locked");
        long locked = user(email, "USER", "LOCKED");
        Instant lockedUntil = Instant.now().plus(Duration.ofMinutes(20)).truncatedTo(ChronoUnit.SECONDS);
        jdbc.update("UPDATE users SET failed_login_count = 3, locked_until = ? WHERE id = ?",
                Timestamp.from(lockedUntil), locked);
        PROVIDER.google("g-locked-" + tag, email, true, "Bi Khoa");
        Pkce pkce = Pkce.create();
        String code = codeFrom(appRound("google", pkce));
        MockHttpSession app = new MockHttpSession();

        exchange(code, pkce.verifier(), app)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value(LOCKED));
        assertThat(lastLogin(locked)).isNull();
        assertThat(jdbc.queryForObject("SELECT failed_login_count FROM users WHERE id = ?", Integer.class, locked))
                .isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT locked_until FROM users WHERE id = ?", Timestamp.class, locked)
                .toInstant()).isEqualTo(lockedUntil);
        assertThat(links(locked)).isZero();
        mvc.perform(get("/api/auth/me").session(app)).andExpect(status().isUnauthorized());
        assertThat(app.getAttribute(PendingSocialSignup.SESSION_KEY)).isNull();
        // the code is used up all the same
        exchange(code, pkce.verifier(), app)
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").value(EXPIRED));

        // DELETED, found by its Facebook link (Facebook now gives another email)
        String deletedEmail = email("facebook-deleted");
        long deleted = user(deletedEmail, "USER", "DELETED");
        String facebookId = "fb-deleted-" + tag;
        jdbc.update("INSERT INTO user_auth_providers (user_id, provider, provider_user_id) VALUES (?, 'FACEBOOK', ?)",
                deleted, facebookId);
        String otherEmail = email("facebook-deleted-other");
        PROVIDER.facebook(facebookId, otherEmail, "Da Xoa");
        Pkce fb = Pkce.create();

        exchange(codeFrom(appRound("facebook", fb)), fb.verifier(), new MockHttpSession())
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value(LOCKED));
        assertThat(lastLogin(deleted)).isNull();
        assertThat(links(deleted)).isEqualTo(1);
        assertThat(users(otherEmail)).isZero();
    }

    @Test
    void aGoogleEmailGoogleHasNotVerifiedGoesBackToTheAppAsNoEmail() throws Exception {
        String email = email("google-unverified");
        PROVIDER.google("g-unverified-" + tag, email, false, "Chua Xac Minh");

        MvcResult back = appRound("google", Pkce.create());

        assertThat(back.getResponse().getRedirectedUrl()).isEqualTo(APP + "?error=social_no_email");
        assertBrowserHoldsNothing(browserOf(back));
        assertThat(users(email)).isZero();
    }

    @Test
    void cancellingAtGoogleOrFacebookGoesBackToTheApp() throws Exception {
        MvcResult google = start("google", Pkce.create().challenge(), null);
        MvcResult googleBack = mvc.perform(get("/login/oauth2/code/google").param("error", "access_denied")
                .param("state", queryOf(google.getResponse().getRedirectedUrl()).get("state"))
                .session(browserOf(google))).andReturn();
        assertThat(googleBack.getResponse().getRedirectedUrl()).isEqualTo(APP + "?error=social_cancelled");

        // what Facebook sends back after "Hủy" / "Không phải bây giờ"
        MvcResult facebook = start("facebook", Pkce.create().challenge(), null);
        MvcResult facebookBack = mvc.perform(get("/login/oauth2/code/facebook").param("error", "access_denied")
                .param("error_code", "200").param("error_description", "Permissions error")
                .param("error_reason", "user_denied")
                .param("state", queryOf(facebook.getResponse().getRedirectedUrl()).get("state"))
                .session(browserOf(facebook))).andReturn();
        assertThat(facebookBack.getResponse().getRedirectedUrl()).isEqualTo(APP + "?error=social_cancelled");

        assertThat(PROVIDER.tokenCalls).isEmpty();
        assertBrowserHoldsNothing(browserOf(googleBack));
        assertBrowserHoldsNothing(browserOf(facebookBack));
    }

    @Test
    void aFailedExchangeWithTheProviderGoesBackToTheAppAsAnError() throws Exception {
        // what Google answers to a wrong client secret: invalid_client
        String email = email("google-fail");
        PROVIDER.google("g-fail-" + tag, email, true, "Loi");
        PROVIDER.tokenStatus = 401;
        MvcResult back = appRound("google", Pkce.create());
        assertThat(back.getResponse().getRedirectedUrl()).isEqualTo(APP + "?error=social");
        assertBrowserHoldsNothing(browserOf(back));

        // an id token made for another app
        PROVIDER.reset();
        PROVIDER.google("g-fail-" + tag, email, true, "Loi");
        PROVIDER.audience = "some-other-client";
        assertThat(appRound("google", Pkce.create()).getResponse().getRedirectedUrl()).isEqualTo(APP + "?error=social");

        // Facebook's token address failing
        PROVIDER.reset();
        PROVIDER.facebook("fb-fail-" + tag, email, "Loi");
        PROVIDER.tokenStatus = 500;
        assertThat(appRound("facebook", Pkce.create()).getResponse().getRedirectedUrl()).isEqualTo(APP + "?error=social");
        assertThat(users(email)).isZero();
    }

    // ---------------------------------------------------------------- Facebook

    @Test
    void aNewPersonSignsUpWithFacebookInTheApp() throws Exception {
        String email = email("facebook-new");
        String id = "fb-app-" + tag;
        PROVIDER.facebook(id, email, "Nguyen Van Facebook");
        Pkce pkce = Pkce.create();

        MvcResult started = start("facebook", pkce.challenge(), null);
        String toFacebook = started.getResponse().getRedirectedUrl();
        assertThat(toFacebook).startsWith(PROVIDER.url() + "/facebook/authorize?").doesNotContain(pkce.challenge());
        Map<String, String> authorize = queryOf(toFacebook);
        assertThat(authorize.get("redirect_uri")).isEqualTo(BASE + "/login/oauth2/code/facebook");
        assertThat(authorize.get("client_id")).isEqualTo("facebook-test-app");
        assertThat(authorize).doesNotContainKey("app_challenge");

        MvcResult back = comeBack("facebook", started);
        String code = codeFrom(back);
        FakeSocialProvider.TokenCall token = PROVIDER.tokenCalls.get(0);
        assertThat(token.form().get("redirect_uri")).isEqualTo(BASE + "/login/oauth2/code/facebook");
        assertThat(token.form().get("client_secret")).isEqualTo("facebook-test-secret");
        assertBrowserHoldsNothing(browserOf(back));

        MockHttpSession app = new MockHttpSession();
        exchange(code, pkce.verifier(), app)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value(SIGNUP))
                .andExpect(jsonPath("$.data.status").value("SIGNUP_REQUIRED"))
                .andExpect(jsonPath("$.data.user").value(nullValue()))
                .andExpect(jsonPath("$.data.pending.provider").value("FACEBOOK"))
                .andExpect(jsonPath("$.data.pending.email").value(email))
                .andExpect(jsonPath("$.data.pending.displayName").value("Nguyen Van Facebook"));
        mvc.perform(csrf(post("/api/auth/social/complete").session(app).contentType(MediaType.APPLICATION_JSON)
                        .content(COMPLETE)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.email").value(email));
        assertThat(links(email, "FACEBOOK", id)).isEqualTo(1);
        mvc.perform(get("/api/auth/me").session(app)).andExpect(jsonPath("$.data.email").value(email));
    }

    @Test
    void facebookWithoutAnEmailGoesBackToTheAppAsNoEmail() throws Exception {
        PROVIDER.facebook("fb-noemail-" + tag, null, "Khong Email");

        MvcResult back = appRound("facebook", Pkce.create());

        assertThat(back.getResponse().getRedirectedUrl()).isEqualTo(APP + "?error=social_no_email");
        assertBrowserHoldsNothing(browserOf(back));
    }

    // ---------------------------------------------------------------- the browser

    @Test
    void aMemberSignedInToTheWebsiteInThatBrowserStaysSignedIn() throws Exception {
        // A member signed in to the website in Chrome (by Google, the website's own round)
        String memberEmail = email("website-member");
        long member = user(memberEmail, "USER", "ACTIVE");
        PROVIDER.google("g-member-" + tag, memberEmail, true, "Thanh Vien");
        MvcResult website = comeBack("google", start("google", null, null));
        assertThat(website.getResponse().getRedirectedUrl()).isEqualTo("/");
        MockHttpSession browser = browserOf(website);
        mvc.perform(get("/api/auth/me").session(browser)).andExpect(jsonPath("$.data.id").value(member));

        // The app's round runs in that same Chrome, for someone else (a new person)
        String newcomer = email("app-newcomer");
        PROVIDER.reset();
        PROVIDER.google("g-newcomer-" + tag, newcomer, true, "Nguoi Moi");
        Pkce pkce = Pkce.create();
        MvcResult back = comeBack("google", start("google", pkce.challenge(), browser));
        String code = codeFrom(back);
        assertThat(browserOf(back)).isSameAs(browser);
        mvc.perform(get("/api/auth/me").session(browser))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.id").value(member));
        assertThat(browser.getAttribute(PendingSocialSignup.SESSION_KEY)).isNull();
        exchange(code, pkce.verifier(), new MockHttpSession())
                .andExpect(jsonPath("$.data.status").value("SIGNUP_REQUIRED"))
                .andExpect(jsonPath("$.data.pending.email").value(newcomer));

        // ... for the member's own account: the app is signed in, the website stays as it was
        PROVIDER.reset();
        PROVIDER.google("g-member-" + tag, memberEmail, true, "Thanh Vien");
        Pkce own = Pkce.create();
        String ownCode = codeFrom(comeBack("google", start("google", own.challenge(), browser)));
        exchange(ownCode, own.verifier(), new MockHttpSession())
                .andExpect(jsonPath("$.data.status").value("SIGNED_IN")).andExpect(jsonPath("$.data.user.id").value(member));
        mvc.perform(get("/api/auth/me").session(browser)).andExpect(jsonPath("$.data.id").value(member));

        // ... and a round cancelled at Google
        MvcResult cancelled = start("google", Pkce.create().challenge(), browser);
        MvcResult cancelledBack = mvc.perform(get("/login/oauth2/code/google").param("error", "access_denied")
                .param("state", queryOf(cancelled.getResponse().getRedirectedUrl()).get("state"))
                .session(browser)).andReturn();
        assertThat(cancelledBack.getResponse().getRedirectedUrl()).isEqualTo(APP + "?error=social_cancelled");
        mvc.perform(get("/api/auth/me").session(browser)).andExpect(jsonPath("$.data.id").value(member));
    }

    @Test
    void aMalformedChallengeNeverReachesTheProvider() throws Exception {
        String good = Pkce.create().challenge();
        List<String> malformed = List.of("", "short", good.substring(1), good + "A",
                good.substring(1) + "+", good.substring(1) + "/", good.substring(1) + "=", good.substring(1) + ".",
                good.substring(1) + " ", good.substring(1) + "á");
        for (String provider : List.of("google", "facebook")) {
            for (String challenge : malformed) {
                MvcResult result = mvc.perform(get("/oauth2/authorization/" + provider).param("app_challenge", challenge))
                        .andReturn();
                assertThat(result.getResponse().getStatus()).as(provider + " " + challenge).isEqualTo(404);
                assertThat(result.getResponse().getRedirectedUrl()).as(challenge).isNull();
                assertNoAuthorizationRequestSaved(result);
            }
            // two challenges, even well-formed ones, are not one challenge
            MvcResult twice = mvc.perform(get("/oauth2/authorization/" + provider)
                    .param("app_challenge", good, Pkce.create().challenge())).andReturn();
            assertThat(twice.getResponse().getStatus()).isEqualTo(404);
            assertThat(twice.getResponse().getRedirectedUrl()).isNull();
            assertNoAuthorizationRequestSaved(twice);
        }
        // a well-formed one goes on
        assertThat(start("google", good, null).getResponse().getRedirectedUrl()).startsWith(PROVIDER.url());
    }

    @Test
    void theWebsitesOwnSignInIsUnchanged() throws Exception {
        String email = email("website-new");
        PROVIDER.google("g-website-" + tag, email, true, "Nguoi Dung Web");

        MvcResult back = comeBack("google", start("google", null, null));

        assertThat(back.getResponse().getRedirectedUrl()).isEqualTo("/complete-signup");
        mvc.perform(get("/api/auth/social/pending").session(browserOf(back)))
                .andExpect(jsonPath("$.data.email").value(email));
        mvc.perform(csrf(post("/api/auth/social/complete").session(browserOf(back))
                        .contentType(MediaType.APPLICATION_JSON).content(COMPLETE)))
                .andExpect(status().isOk());

        PROVIDER.reset();
        PROVIDER.google("g-website-" + tag, email, true, "Nguoi Dung Web");
        MvcResult again = comeBack("google", start("google", null, null));
        assertThat(again.getResponse().getRedirectedUrl()).isEqualTo("/");
        mvc.perform(get("/api/auth/me").session(browserOf(again))).andExpect(jsonPath("$.data.email").value(email));

        MvcResult cancel = start("facebook", null, null);
        MvcResult cancelBack = mvc.perform(get("/login/oauth2/code/facebook").param("error", "access_denied")
                .param("state", queryOf(cancel.getResponse().getRedirectedUrl()).get("state"))
                .session(browserOf(cancel))).andReturn();
        assertThat(cancelBack.getResponse().getRedirectedUrl()).isEqualTo("/login?error=social_cancelled");

        PROVIDER.reset();
        PROVIDER.facebook("fb-website-noemail-" + tag, null, "Khong Email");
        assertThat(comeBack("facebook", start("facebook", null, null)).getResponse().getRedirectedUrl())
                .isEqualTo("/login?error=social_no_email");
    }

    // ---------------------------------------------------------------- the code

    @Test
    void aCodeExpiresAfterFiveMinutes() throws Exception {
        String email = email("google-slow");
        PROVIDER.google("g-slow-" + tag, email, true, "Cham Chap");
        Instant issuedAt = clock.instant();
        codes.useClock(Clock.fixed(issuedAt, ZoneOffset.UTC));
        Pkce early = Pkce.create();
        String earlyCode = codeFrom(appRound("google", early));
        Pkce late = Pkce.create();
        String lateCode = codeFrom(appRound("google", late));

        codes.useClock(Clock.fixed(issuedAt.plus(Duration.ofMinutes(5)).minusSeconds(1), ZoneOffset.UTC));
        exchange(earlyCode, early.verifier(), new MockHttpSession())
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("SIGNUP_REQUIRED"));

        codes.useClock(Clock.fixed(issuedAt.plus(Duration.ofMinutes(5)), ZoneOffset.UTC));
        exchange(lateCode, late.verifier(), new MockHttpSession())
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").value(EXPIRED));
        assertThat(users(email)).isZero();
    }

    @Test
    void whenTooManyCodesAreWaitingTheAppIsToldToTryAgain() throws Exception {
        String email = email("google-full");
        PROVIDER.google("g-full-" + tag, email, true, "Day Roi");
        MvcResult started = start("google", Pkce.create().challenge(), null);
        PendingSocialSignup filler = new PendingSocialSignup("GOOGLE", "filler", "filler@gmail.test", "Filler", null);
        try {
            int filled = 0;
            while (codes.issue(Pkce.create().challenge(), filler).isPresent()) {
                filled++;
            }
            assertThat(filled).isPositive().isLessThanOrEqualTo(SocialAppCodes.MAX_LIVE);

            MvcResult back = comeBack("google", started);

            assertThat(back.getResponse().getRedirectedUrl()).isEqualTo(APP + "?error=social");
            assertBrowserHoldsNothing(browserOf(back));
        } finally {
            emptyTheCodeStore();
        }
        // with room again, the next round works
        Pkce pkce = Pkce.create();
        exchange(codeFrom(appRound("google", pkce)), pkce.verifier(), new MockHttpSession())
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("SIGNUP_REQUIRED"));
    }

    @Test
    void theExchangeChecksWhatTheAppSends() throws Exception {
        String verifier = Pkce.create().verifier();
        exchangeRaw(Collections.singletonMap("verifier", verifier))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.data.code").value("Thiếu mã đăng nhập"));
        exchange("", verifier, null)
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.data.code").value("Thiếu mã đăng nhập"));
        exchange("a".repeat(101), verifier, null)
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.data.code").value("Mã đăng nhập không hợp lệ"));
        exchangeRaw(Collections.singletonMap("code", "abc"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.data.verifier").value("Thiếu mã xác minh"));
        for (String bad : List.of("", "a".repeat(42), "a".repeat(129), "a".repeat(42) + "+", "a".repeat(42) + "/",
                "a".repeat(42) + "=", "a".repeat(42) + " ", "a".repeat(42) + "á")) {
            exchange("abc", bad, null)
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.success").value(false))
                    .andExpect(jsonPath("$.data.verifier").value("Mã xác minh không hợp lệ"));
        }
        // the shortest and the longest verifier RFC 7636 allows, with every allowed sign, pass the check; the code
        // is unknown
        for (String good : List.of("a".repeat(39) + "._~-", "Z9".repeat(62) + "._~-")) {
            exchange("unknown-code", good, null)
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").value(EXPIRED));
        }
    }

    @Test
    void theExchangeNeedsTheCsrfTokenLikeEveryOtherCall() throws Exception {
        Pkce pkce = Pkce.create();
        PROVIDER.google("g-csrf-" + tag, email("google-csrf"), true, "Csrf");
        String code = codeFrom(appRound("google", pkce));

        mvc.perform(post(EXCHANGE).contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("code", code, "verifier", pkce.verifier()))))
                .andExpect(status().isForbidden());
        // refused before it was read, so the code still works
        exchange(code, pkce.verifier(), new MockHttpSession())
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("SIGNUP_REQUIRED"));
    }

    // ---------------------------------------------------------------- helpers

    /** What the app makes for every round: 32 random bytes as the verifier, base64url(SHA-256) as the challenge. */
    private record Pkce(String verifier, String challenge) {

        private static final SecureRandom RANDOM = new SecureRandom();

        static Pkce create() {
            try {
                byte[] bytes = new byte[32];
                RANDOM.nextBytes(bytes);
                String verifier = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
                byte[] digest = MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII));
                return new Pkce(verifier, Base64.getUrlEncoder().withoutPadding().encodeToString(digest));
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }
    }

    /** The provider button's address opened in a browser ({@code browser} null: one without any session yet). */
    private MvcResult start(String registrationId, String challenge, MockHttpSession browser) throws Exception {
        MockHttpServletRequestBuilder request = get("/oauth2/authorization/" + registrationId);
        if (challenge != null) {
            request.param("app_challenge", challenge);
        }
        if (browser != null) {
            request.session(browser);
        }
        MvcResult result = mvc.perform(request).andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(302);
        return result;
    }

    /** The provider sends the person back with a code, to the browser that started the round. */
    private MvcResult comeBack(String registrationId, MvcResult started) throws Exception {
        Map<String, String> authorize = queryOf(started.getResponse().getRedirectedUrl());
        PROVIDER.nonce = authorize.get("nonce");
        return mvc.perform(get("/login/oauth2/code/" + registrationId).param("code", "code-" + UUID.randomUUID())
                .param("state", authorize.get("state")).session(browserOf(started))).andReturn();
    }

    /** A whole round of the app in a fresh browser, up to the redirect to senpaioi://auth. */
    private MvcResult appRound(String registrationId, Pkce pkce) throws Exception {
        return comeBack(registrationId, start(registrationId, pkce.challenge(), null));
    }

    /** The one-time code in the redirect to the app. */
    private static String codeFrom(MvcResult back) {
        String location = back.getResponse().getRedirectedUrl();
        assertThat(location).startsWith(APP + "?code=");
        String code = location.substring((APP + "?code=").length());
        assertThat(code).matches("[A-Za-z0-9_-]{43}");
        return code;
    }

    private ResultActions exchange(String code, String verifier, MockHttpSession app) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", code);
        body.put("verifier", verifier);
        MockHttpServletRequestBuilder request = csrf(post(EXCHANGE)).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(body));
        if (app != null) {
            request.session(app);
        }
        return mvc.perform(request);
    }

    private ResultActions exchangeRaw(Map<String, Object> body) throws Exception {
        return mvc.perform(csrf(post(EXCHANGE)).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(body)));
    }

    private static MockHttpSession browserOf(MvcResult result) {
        MockHttpSession session = (MockHttpSession) result.getRequest().getSession(false);
        assertThat(session).isNotNull();
        return session;
    }

    /** Not signed in by the round, and no sign-up waiting in it. */
    private static void assertBrowserHoldsNothing(MockHttpSession browser) {
        assertThat(browser.getAttribute(SPRING_SECURITY_CONTEXT_KEY)).isNull();
        assertThat(browser.getAttribute(PendingSocialSignup.SESSION_KEY)).isNull();
    }

    private static void assertNoAuthorizationRequestSaved(MvcResult result) {
        var session = result.getRequest().getSession(false);
        if (session != null) {
            assertThat(Collections.list(session.getAttributeNames())).noneMatch(name -> name.contains("AUTHORIZATION_REQUEST"));
        }
    }

    /** Every waiting code expires (the store drops them at the next issue) and the clock goes back. */
    private void emptyTheCodeStore() {
        codes.useClock(Clock.offset(clock, SocialAppCodes.TTL.plusMinutes(1)));
        codes.issue(Pkce.create().challenge(),
                new PendingSocialSignup("GOOGLE", "purge", "purge@gmail.test", "Purge", null)).flatMap(codes::take);
        codes.useClock(clock);
    }

    private static MockHttpServletRequestBuilder csrf(MockHttpServletRequestBuilder request) {
        return request.cookie(new Cookie("XSRF-TOKEN", CSRF)).header("X-XSRF-TOKEN", CSRF);
    }

    private String email(String name) {
        String email = "social-app-" + name + "-" + tag + "@gmail.test";
        if (!emails.contains(email)) {
            emails.add(email);
        }
        return email;
    }

    private long user(String email, String role, String status) {
        jdbc.update("""
                INSERT INTO users (email, password_hash, display_name, role, status, is_adult_confirmed, deleted_at)
                VALUES (?, NULL, 'Social App Test', ?, ?, TRUE, ?)""",
                email, role, status, "DELETED".equals(status) ? Timestamp.from(Instant.now()) : null);
        return jdbc.queryForObject("SELECT id FROM users WHERE email = ?", Long.class, email);
    }

    private long users(String email) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM users WHERE email = ?", Long.class, email);
    }

    private long links(String email, String provider, String providerUserId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM user_auth_providers p JOIN users u ON u.id = p.user_id "
                + "WHERE u.email = ? AND p.provider = ? AND p.provider_user_id = ?", Long.class, email, provider,
                providerUserId);
    }

    private long links(long userId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM user_auth_providers WHERE user_id = ?", Long.class, userId);
    }

    private Timestamp lastLogin(long userId) {
        return jdbc.queryForObject("SELECT last_login_at FROM users WHERE id = ?", Timestamp.class, userId);
    }

    private static Map<String, String> queryOf(String url) {
        Map<String, String> query = new LinkedHashMap<>();
        UriComponentsBuilder.fromUriString(url).build().getQueryParams()
                .forEach((key, values) -> query.put(key, URLDecoder.decode(values.get(0), StandardCharsets.UTF_8)));
        return query;
    }
}
