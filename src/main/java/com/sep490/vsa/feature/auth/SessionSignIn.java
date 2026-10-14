package com.sep490.vsa.feature.auth;

import com.sep490.vsa.common.security.AppUserPrincipal;
import com.sep490.vsa.feature.user.User;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.stereotype.Component;

import java.util.List;

/** Puts a user into the HTTP session after a password or social login. */
@Component
public class SessionSignIn {

    private final SecurityContextRepository contextRepository;

    public SessionSignIn(SecurityContextRepository contextRepository) {
        this.contextRepository = contextRepository;
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
    }
}
