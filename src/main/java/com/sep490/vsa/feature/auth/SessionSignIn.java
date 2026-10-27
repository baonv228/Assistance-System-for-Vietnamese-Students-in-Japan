package com.sep490.vsa.feature.auth;

import com.sep490.vsa.common.security.AppUserPrincipal;
import com.sep490.vsa.common.security.UserSessions;
import com.sep490.vsa.feature.user.User;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CsrfTokenRepository;
import org.springframework.stereotype.Component;

import java.util.List;

/** Puts a user into the HTTP session after a password or social login. */
@Component
public class SessionSignIn {

    private final SecurityContextRepository contextRepository;
    private final CsrfTokenRepository csrfTokenRepository;
    private final UserSessions userSessions;

    public SessionSignIn(SecurityContextRepository contextRepository, CsrfTokenRepository csrfTokenRepository,
                         UserSessions userSessions) {
        this.contextRepository = contextRepository;
        this.csrfTokenRepository = csrfTokenRepository;
        this.userSessions = userSessions;
    }

    public void signIn(User user, HttpServletRequest request, HttpServletResponse response) {
        AppUserPrincipal principal = new AppUserPrincipal(
                user.getId(), user.getEmail(), user.getDisplayName(), user.getRole().name(), user.getRole().homePath());
        var authentication = new UsernamePasswordAuthenticationToken(
                principal, null, List.of(new SimpleGrantedAuthority("ROLE_" + user.getRole().name())));

        // New session id after login to prevent session fixation
        if (request.getSession(false) != null) {
            request.changeSessionId();
        }
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authentication);
        SecurityContextHolder.setContext(context);
        contextRepository.saveContext(context, request, response);
        // A new CSRF token too, as Spring's own login does: one planted before the sign-in is no use afterwards. The
        // page fetches the new one (client.js asks again after Spring's 403)
        csrfTokenRepository.saveToken(null, request, response);
        // Known by user, so a password change or reset can sign the other devices out (UserSessions)
        userSessions.add(user.getId(), request.getSession());
    }
}
