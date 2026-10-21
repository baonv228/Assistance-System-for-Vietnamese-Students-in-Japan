package com.sep490.vsa.feature.admin;

import com.sep490.vsa.common.security.AppUserPrincipal;
import com.sep490.vsa.common.exception.ApiException;
import com.sep490.vsa.feature.user.ActiveAdminGuard;
import com.sep490.vsa.feature.user.UserRepository;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.web.context.HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.forwardedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrlPattern;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Account changes by an Admin, against MySQL ({@code asvsj_test}, like the other flow tests). Sessions come from the
 * real login API, so the session is the one a browser gets.
 * <ul>
 *   <li>S1: a lock or role change reaches a session that is already signed in on its next request
 *       (SessionUserRefreshFilter).</li>
 *   <li>Status rules: no change to one's own status or role, only ACTIVE/LOCKED, nothing on DELETED accounts.</li>
 *   <li>A4: the last ACTIVE Admin cannot be deleted, demoted or locked. Those tests lock every other Admin of the
 *       shared database, so they run in a transaction that is rolled back at the end.</li>
 * </ul>
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:mysql://localhost:3306/asvsj_test?createDatabaseIfNotExist=true&useUnicode=true&characterEncoding=utf8&connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true",
        "app.media.local-dir=target/test-uploads",
        "app.moderation.retry-initial-delay-ms=3600000",
        "app.places.osm.enabled=false"
})
@AutoConfigureMockMvc
@ActiveProfiles("local")
class AdminAccountSafetyFlowTest {

    private static final String CSRF = "test-csrf-token";
    private static final String PASSWORD = "matkhau123";
    private static final String HASH = new BCryptPasswordEncoder().encode(PASSWORD);

    @Autowired
    private MockMvc mvc;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private AdminUserService adminUsers;
    @Autowired
    private UserRepository users;

    // ---------------------------------------------------------------- S1: open sessions follow the database

    @Test
    void anAdminLockedByAnotherAdminIsSignedOutOnTheNextRequest() throws Exception {
        long a = user("ADMIN", "ACTIVE");
        long b = user("ADMIN", "ACTIVE");
        MockHttpSession adminA = signIn(a);
        MockHttpSession api = signIn(b);
        MockHttpSession me = signIn(b);
        MockHttpSession unlock = signIn(b);
        MockHttpSession page = signIn(b);
        mvc.perform(get("/api/admin/users").session(api)).andExpect(status().isOk());

        changeStatus(adminA, b, "LOCKED").andExpect(status().isOk());

        // Static files are not checked (no query per image): the session is still there…
        mvc.perform(get("/assets/css/tokens.css").session(api)).andExpect(status().isOk());
        assertThat(api.isInvalid()).isFalse();
        // …the next API call finds the lock: signed out, 401
        mvc.perform(get("/api/admin/users").session(api)).andExpect(status().isUnauthorized());
        assertThat(api.isInvalid()).isTrue();
        mvc.perform(get("/api/auth/me").session(me)).andExpect(status().isUnauthorized());
        assertThat(me.isInvalid()).isTrue();
        // The locked Admin cannot unlock themselves any more
        changeStatus(unlock, b, "ACTIVE").andExpect(status().isUnauthorized());
        assertThat(statusOf(b)).isEqualTo("LOCKED");
        // Admin pages send them to the login page, and signing in again is refused
        mvc.perform(get("/admin").session(page)).andExpect(redirectedUrlPattern("**/login"));
        login(email(b), PASSWORD).andExpect(status().isForbidden());

        // Unlocked again: a new sign-in works
        changeStatus(adminA, b, "ACTIVE").andExpect(status().isOk());
        mvc.perform(get("/api/admin/users").session(signIn(b))).andExpect(status().isOk());
    }

    @Test
    void aDemotedAdminLosesTheAdminApiOnTheNextRequest() throws Exception {
        long a = user("ADMIN", "ACTIVE");
        long b = user("ADMIN", "ACTIVE");
        MockHttpSession adminA = signIn(a);
        MockHttpSession session = signIn(b);
        mvc.perform(get("/api/admin/users").session(session)).andExpect(status().isOk());

        changeRole(adminA, b, "USER").andExpect(status().isOk());

        mvc.perform(get("/api/admin/users").session(session)).andExpect(status().isForbidden());
        assertThat(session.isInvalid()).isFalse();
        assertThat(signedIn(session).role()).isEqualTo("USER");
        assertThat(signedIn(session).homePath()).isEqualTo("/");
        assertThat(authorities(session)).containsExactly("ROLE_USER");
        // The Admin page now sends them to their new home page
        mvc.perform(get("/admin").session(session)).andExpect(redirectedUrl("/"));
        mvc.perform(get("/api/auth/me").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.role").value("USER"))
                .andExpect(jsonPath("$.data.homePath").value("/"));
    }

    @Test
    void aUserPromotedToModeratorGetsTheModeratorPagesWithoutSigningInAgain() throws Exception {
        long u = user("USER", "ACTIVE");
        MockHttpSession session = signIn(u);
        mvc.perform(get("/moderator").session(session)).andExpect(redirectedUrl("/"));
        mvc.perform(get("/api/moderator/reports").session(session)).andExpect(status().isForbidden());

        // Promoted in the database (the Moderator limit of the shared test database is not this test's concern)
        jdbc.update("UPDATE users SET role = 'MODERATOR' WHERE id = ?", u);

        mvc.perform(get("/moderator").session(session))
                .andExpect(status().isOk())
                .andExpect(forwardedUrl("/pages/moderator/index.html"));
        assertThat(signedIn(session).role()).isEqualTo("MODERATOR");
        assertThat(signedIn(session).homePath()).isEqualTo("/moderator");
        assertThat(authorities(session)).containsExactly("ROLE_MODERATOR");
        // No Moderator API exists yet: the call passes authorization and finds nothing (404, not 403)
        mvc.perform(get("/api/moderator/reports").session(session)).andExpect(status().isNotFound());
    }

    @Test
    void aDeletedOrMissingAccountIsSignedOutEverywhere() throws Exception {
        long u = user("USER", "ACTIVE");
        MockHttpSession deleting = signIn(u);
        MockHttpSession otherBrowser = signIn(u);
        mvc.perform(withCsrf(delete("/api/me"), deleting)).andExpect(status().isOk());
        mvc.perform(get("/api/me/profile").session(otherBrowser)).andExpect(status().isUnauthorized());
        assertThat(otherBrowser.isInvalid()).isTrue();

        long gone = user("USER", "ACTIVE");
        MockHttpSession session = signIn(gone);
        jdbc.update("DELETE FROM users WHERE id = ?", gone);
        mvc.perform(get("/api/auth/me").session(session)).andExpect(status().isUnauthorized());
        assertThat(session.isInvalid()).isTrue();
    }

    // ---------------------------------------------------------------- status and role rules

    @Test
    void anAdminCannotChangeTheirOwnStatusOrRole() throws Exception {
        long a = user("ADMIN", "ACTIVE");
        MockHttpSession session = signIn(a);
        for (String next : new String[] {"LOCKED", "ACTIVE", "DELETED"}) {
            changeStatus(session, a, next)
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.message").value("Không thể tự thay đổi trạng thái của chính tài khoản đang đăng nhập."));
        }
        changeRole(session, a, "USER")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Không thể tự thay đổi vai trò của chính tài khoản đang đăng nhập."));
        assertThat(statusOf(a)).isEqualTo("ACTIVE");
        assertThat(roleOf(a)).isEqualTo("ADMIN");
    }

    @Test
    void onlyActiveAndLockedCanBeSetAndDeletedAccountsCannotBeChanged() throws Exception {
        MockHttpSession admin = signIn(user("ADMIN", "ACTIVE"));
        long u = user("USER", "ACTIVE");
        changeStatus(admin, u, "DELETED")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(
                        "Trạng thái không hợp lệ: DELETED. Chỉ có thể khóa (LOCKED) hoặc mở khóa (ACTIVE) tài khoản."));
        assertThat(statusOf(u)).isEqualTo("ACTIVE");
        changeStatus(admin, u, "LOCKED").andExpect(status().isOk());
        assertThat(statusOf(u)).isEqualTo("LOCKED");
        changeStatus(admin, u, "ACTIVE").andExpect(status().isOk());
        assertThat(statusOf(u)).isEqualTo("ACTIVE");

        long d = user("USER", "DELETED");
        for (String next : new String[] {"ACTIVE", "LOCKED"}) {
            changeStatus(admin, d, next)
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.message").value(AdminUserService.DELETED_ACCOUNT));
        }
        changeRole(admin, d, "ACADEMIC_MANAGER")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(AdminUserService.DELETED_ACCOUNT));
        assertThat(statusOf(d)).isEqualTo("DELETED");
        assertThat(roleOf(d)).isEqualTo("USER");
    }

    // ---------------------------------------------------------------- A4: never without an active Admin

    @Test
    @Transactional
    void theLastActiveAdminCannotDeleteTheirAccountButCanWhenAnotherAdminExists() throws Exception {
        long last = user("ADMIN", "ACTIVE");
        lockEveryOtherAdmin(last);
        MockHttpSession session = signIn(last);

        mvc.perform(withCsrf(delete("/api/me"), session))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(ActiveAdminGuard.LAST_ADMIN));
        users.flush();
        assertThat(statusOf(last)).isEqualTo("ACTIVE");
        mvc.perform(get("/api/auth/me").session(session)).andExpect(status().isOk());

        user("ADMIN", "ACTIVE");
        mvc.perform(withCsrf(delete("/api/me"), session)).andExpect(status().isOk());
        users.flush();
        assertThat(statusOf(last)).isEqualTo("DELETED");
    }

    @Test
    @Transactional
    void theLastActiveAdminCannotBeDemotedOrLockedButCanWhenAnotherAdminExists() {
        long last = user("ADMIN", "ACTIVE");
        long actor = user("ADMIN", "ACTIVE");
        // The acting Admin passed the session check, then was locked by a request running at the same time
        // (the FOR UPDATE in ActiveAdminGuard orders such requests): only `last` is still an active Admin
        lockEveryOtherAdmin(last);
        AppUserPrincipal stale = new AppUserPrincipal(actor, email(actor), "Safety Test", "ADMIN", "/admin");

        assertThatThrownBy(() -> adminUsers.updateUserRole(last, "MODERATOR", stale))
                .isInstanceOf(ApiException.class).hasMessage(ActiveAdminGuard.LAST_ADMIN);
        assertThatThrownBy(() -> adminUsers.updateUserStatus(last, "LOCKED", "thử", stale))
                .isInstanceOf(ApiException.class).hasMessage(ActiveAdminGuard.LAST_ADMIN);
        assertThat(roleOf(last)).isEqualTo("ADMIN");
        assertThat(statusOf(last)).isEqualTo("ACTIVE");

        // A second active Admin: both changes go through
        jdbc.update("UPDATE users SET status = 'ACTIVE' WHERE id = ?", actor);
        adminUsers.updateUserStatus(last, "LOCKED", "thử", stale);
        assertThat(statusOf(last)).isEqualTo("LOCKED");
        adminUsers.updateUserStatus(last, "ACTIVE", "thử", stale);
        adminUsers.updateUserRole(last, "USER", stale);
        assertThat(roleOf(last)).isEqualTo("USER");
        assertThat(statusOf(last)).isEqualTo("ACTIVE");
    }

    // ---------------------------------------------------------------- helpers

    private long user(String role, String status) {
        String email = "safety-" + UUID.randomUUID() + "@test.local";
        jdbc.update("""
                INSERT INTO users (email, password_hash, email_verified_at, display_name, role, status, is_adult_confirmed,
                                   created_at, deleted_at)
                VALUES (?, ?, ?, 'Safety Test', ?, ?, TRUE, ?, ?)""",
                email, HASH, Timestamp.from(Instant.now()), role, status,
                Timestamp.from(Instant.now().minus(Duration.ofDays(30))),
                "DELETED".equals(status) ? Timestamp.from(Instant.now()) : null);
        return jdbc.queryForObject("SELECT id FROM users WHERE email = ?", Long.class, email);
    }

    /** Only for the transactional tests: the rollback at the end unlocks them again. */
    private void lockEveryOtherAdmin(long keep) {
        jdbc.update("UPDATE users SET status = 'LOCKED' WHERE role = 'ADMIN' AND status = 'ACTIVE' AND id <> ?", keep);
    }

    private String email(long id) {
        return jdbc.queryForObject("SELECT email FROM users WHERE id = ?", String.class, id);
    }

    private String statusOf(long id) {
        return jdbc.queryForObject("SELECT status FROM users WHERE id = ?", String.class, id);
    }

    private String roleOf(long id) {
        return jdbc.queryForObject("SELECT role FROM users WHERE id = ?", String.class, id);
    }

    private ResultActions login(String email, String password) throws Exception {
        return mvc.perform(post("/api/auth/login").cookie(new Cookie("XSRF-TOKEN", CSRF)).header("X-XSRF-TOKEN", CSRF)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}"));
    }

    /** Signs in through the real login API and returns the session it created. */
    private MockHttpSession signIn(long id) throws Exception {
        return (MockHttpSession) login(email(id), PASSWORD).andExpect(status().isOk())
                .andReturn().getRequest().getSession(false);
    }

    private ResultActions changeStatus(MockHttpSession session, long target, String next) throws Exception {
        return mvc.perform(withCsrf(put("/api/admin/users/" + target + "/status"), session)
                .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"" + next + "\",\"reason\":\"thử\"}"));
    }

    private ResultActions changeRole(MockHttpSession session, long target, String next) throws Exception {
        return mvc.perform(withCsrf(put("/api/admin/users/" + target + "/role"), session)
                .contentType(MediaType.APPLICATION_JSON).content("{\"role\":\"" + next + "\"}"));
    }

    private static MockHttpServletRequestBuilder withCsrf(MockHttpServletRequestBuilder request, MockHttpSession session) {
        return request.session(session).cookie(new Cookie("XSRF-TOKEN", CSRF)).header("X-XSRF-TOKEN", CSRF);
    }

    private static SecurityContext context(MockHttpSession session) {
        return (SecurityContext) session.getAttribute(SPRING_SECURITY_CONTEXT_KEY);
    }

    private static AppUserPrincipal signedIn(MockHttpSession session) {
        return (AppUserPrincipal) context(session).getAuthentication().getPrincipal();
    }

    private static java.util.List<String> authorities(MockHttpSession session) {
        return context(session).getAuthentication().getAuthorities().stream().map(GrantedAuthority::getAuthority).toList();
    }
}
