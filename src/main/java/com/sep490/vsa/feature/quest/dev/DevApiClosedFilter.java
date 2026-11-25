package com.sep490.vsa.feature.quest.dev;

import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Conditional;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.Environment;
import org.springframework.security.web.util.matcher.AntPathRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * While the test-only API is off ({@link DevApiGuard}: the flag, or a database that is not on this machine), every
 * {@code /api/dev} and {@code /api/dev/**} request answers 404, whatever its method, session or CSRF header (contract
 * 3.9: "Ở mọi trường hợp khác, các đường dẫn /api/dev/** trả 404"). The API's beans are not registered then, so such a
 * request would end in a 404 anyway for a signed-in user (no handler); this filter runs before Spring Security, so a
 * guest's PUT/POST/DELETE gets the same 404 instead of Spring Security's 401 (or 403 without the CSRF header), and
 * nobody can tell the off API from a path that never existed. Same answer as an unknown path: {@code sendError(404)},
 * rendered by Spring Boot's error page.
 * <p>
 * At start it logs one WARN when the flag is on and only the database keeps the API off.
 */
@Component
@Conditional(DevApiGuard.Off.class)
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
public class DevApiClosedFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(DevApiClosedFilter.class);
    /** Also matches "/api/dev" itself. */
    private static final RequestMatcher DEV_API = new AntPathRequestMatcher("/api/dev/**");

    public DevApiClosedFilter(Environment environment) {
        if (DevApiGuard.state(environment) == DevApiGuard.State.OFF_DATABASE) {
            log.warn("The test-only API /api/dev/** and the sample flash card marks stay OFF although {}=true: "
                            + "{} does not point at a database on this machine (localhost, 127.0.0.1 or [::1]). They are "
                            + "never switched on against a shared database (the live site reads it).",
                    DevApiGuard.FLAG, DevApiGuard.URL);
        }
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !DEV_API.matches(request);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws IOException {
        response.sendError(HttpServletResponse.SC_NOT_FOUND);
    }
}
