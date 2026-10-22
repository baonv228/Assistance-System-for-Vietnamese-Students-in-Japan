package com.sep490.vsa.common.security;

import com.sep490.vsa.feature.user.Role;
import com.sep490.vsa.feature.user.UserStatus;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.context.SecurityContextHolderStrategy;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

/**
 * Checks the signed-in user against {@code users} on every request, because the session keeps the role it was given
 * at login ({@code SessionSignIn}) and an Admin's change must reach sessions that are already open:
 * <ul>
 *   <li>account LOCKED or DELETED (or the row is gone): the session ends and the request goes on as a guest, so the
 *       API answers 401 and pages go to the login page. A locked Admin can no longer unlock themselves.</li>
 *   <li>role changed: the session gets the new role and home page ({@link Role#homePath()}) at once, so a demoted
 *       Admin loses /api/admin/** and a promoted user gets the new pages without signing in again.</li>
 * </ul>
 * One primary-key query per request of a signed-in user. Static files (/assets, /components, /media, the favicon)
 * are skipped: they are public and a page loads many of them. Registered right after the security context is loaded
 * (see SecurityConfig), so authorization always sees the current role.
 */
public class SessionUserRefreshFilter extends OncePerRequestFilter {

    private static final List<String> STATIC_PREFIXES = List.of("/assets/", "/components/", "/media/");

    private final JdbcTemplate jdbc;
    private final SecurityContextRepository contextRepository;
    private final SecurityContextHolderStrategy contextHolder = SecurityContextHolder.getContextHolderStrategy();

    public SessionUserRefreshFilter(JdbcTemplate jdbc, SecurityContextRepository contextRepository) {
        this.jdbc = jdbc;
        this.contextRepository = contextRepository;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        return path.equals("/favicon.ico") || STATIC_PREFIXES.stream().anyMatch(path::startsWith);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Authentication authentication = contextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.getPrincipal() instanceof AppUserPrincipal me) {
            List<String[]> rows = me.id() == null ? List.of() : jdbc.query(
                    "SELECT role, status FROM users WHERE id = ?",
                    (rs, i) -> new String[] {rs.getString(1), rs.getString(2)}, me.id());
            Role role = rows.isEmpty() ? null : role(rows.get(0)[0]);
            if (role == null || !UserStatus.ACTIVE.name().equals(rows.get(0)[1])) {
                signOut(request);
            } else if (!role.name().equals(me.role())) {
                switchRole(me, role, authentication, request, response);
            }
        }
        chain.doFilter(request, response);
    }

    /** Same as logging out: no user for this request, and the session is gone. */
    private void signOut(HttpServletRequest request) {
        contextHolder.clearContext();
        HttpSession session = request.getSession(false);
        if (session != null) {
            session.invalidate();
        }
    }

    /** Builds the signed-in user again with the role from the database, as SessionSignIn does at login. */
    private void switchRole(AppUserPrincipal me, Role role, Authentication old, HttpServletRequest request,
                            HttpServletResponse response) {
        AppUserPrincipal principal = new AppUserPrincipal(me.id(), me.email(), me.displayName(), role.name(),
                role.homePath());
        var authentication = new UsernamePasswordAuthenticationToken(
                principal, null, List.of(new SimpleGrantedAuthority("ROLE_" + role.name())));
        authentication.setDetails(old.getDetails());
        SecurityContext context = contextHolder.createEmptyContext();
        context.setAuthentication(authentication);
        contextHolder.setContext(context);
        contextRepository.saveContext(context, request, response);
    }

    /** Unknown value: null, treated like a missing account (users.role has a CHECK constraint, so never expected). */
    private static Role role(String value) {
        if (value == null) {
            return null;
        }
        try {
            return Role.valueOf(value);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
