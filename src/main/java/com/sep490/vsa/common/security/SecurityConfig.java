package com.sep490.vsa.common.security;

import jakarta.servlet.DispatcherType;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextHolderFilter;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.LoginUrlAuthenticationEntryPoint;
import org.springframework.security.web.util.matcher.AntPathRequestMatcher;
import org.springframework.security.web.util.matcher.AnyRequestMatcher;
import org.springframework.security.web.util.matcher.OrRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * Session-based security for the same-origin HTML/JS frontend.
 * <ul>
 *   <li>Login state lives in the HTTP session (cookie JSESSIONID). {@link SessionUserRefreshFilter} checks it against
 *       users.role / users.status on every request.</li>
 *   <li>CSRF: the page reads the token from GET /api/auth/csrf and sends it in the X-XSRF-TOKEN header.</li>
 *   <li>GET /api/** is public (guests can view); other API calls need a signed-in user.</li>
 *   <li>Google/Facebook login is switched on only when their client ids are configured.</li>
 * </ul>
 */
@Configuration
public class SecurityConfig {

    private static final RequestMatcher API = new AntPathRequestMatcher("/api/**");
    /** Member pages a guest is sent away from: after signing in they come back here (?next=, followed by login.js). */
    private static final RequestMatcher MEMBER_PAGES = new OrRequestMatcher(
            new AntPathRequestMatcher("/profile"), new AntPathRequestMatcher("/saved"),
            new AntPathRequestMatcher("/notifications"), new AntPathRequestMatcher("/messages"),
            new AntPathRequestMatcher("/messages/**"));

    /**
     * Not signed in on a page: to the sign-in page. From a Member page (e.g. a chat notification tapped after the
     * session ended: /messages/12) the sign-in page gets ?next= so the member lands back on it; staff pages stay plain.
     */
    static AuthenticationEntryPoint loginThenBack() {
        LoginUrlAuthenticationEntryPoint plain = new LoginUrlAuthenticationEntryPoint("/login");
        return (request, response, exception) -> {
            if (!"GET".equals(request.getMethod()) || !MEMBER_PAGES.matches(request)) {
                plain.commence(request, response, exception);
                return;
            }
            String path = request.getRequestURI().substring(request.getContextPath().length());
            String query = request.getQueryString();
            String next = query == null ? path : path + "?" + query;
            response.sendRedirect(request.getContextPath() + "/login?next="
                    + URLEncoder.encode(next, StandardCharsets.UTF_8));
        };
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public SecurityContextRepository securityContextRepository() {
        return new HttpSessionSecurityContextRepository();
    }

    /** The XSRF-TOKEN cookie; also cleared by SessionSignIn so a token from before the sign-in is not kept. */
    @Bean
    public CsrfTokenRepository csrfTokenRepository() {
        return CookieCsrfTokenRepository.withHttpOnlyFalse();
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http,
                                                   SecurityContextRepository contextRepository,
                                                   CsrfTokenRepository csrfTokenRepository,
                                                   JdbcTemplate jdbc,
                                                   ObjectProvider<ClientRegistrationRepository> clientRegistrations,
                                                   AuthenticationSuccessHandler socialLoginSuccessHandler) throws Exception {
        http
                .csrf(csrf -> csrf
                        .csrfTokenRepository(csrfTokenRepository)
                        .csrfTokenRequestHandler(new CsrfTokenRequestAttributeHandler()))
                .securityContext(context -> context.securityContextRepository(contextRepository))
                // Role and status are read again on every request: a lock or role change applies at once
                .addFilterAfter(new SessionUserRefreshFilter(jdbc, contextRepository), SecurityContextHolderFilter.class)
                .authorizeHttpRequests(auth -> auth
                        // The live stream (/api/me/live) ends with an async dispatch of the request already allowed;
                        // checking it again would fail once the session ended meanwhile, on a response already sent
                        .dispatcherTypeMatchers(DispatcherType.ASYNC).permitAll()
                        // API
                        .requestMatchers("/api/auth/**", "/api/legal/**").permitAll()
                        .requestMatchers("/api/me/**").authenticated()
                        .requestMatchers("/api/moderator/**").hasRole("MODERATOR")
                        .requestMatchers("/api/academic/**").hasRole("ACADEMIC_MANAGER")
                        .requestMatchers("/api/admin/**").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.GET, "/api/**").permitAll()
                        .requestMatchers("/api/**").authenticated()
                        // Pages of each role: the clean address and the file it forwards to (see CleanUrls)
                        .requestMatchers("/moderator", "/moderator/**", "/pages/moderator/**").hasRole("MODERATOR")
                        .requestMatchers("/academic", "/pages/academic/**").hasRole("ACADEMIC_MANAGER")
                        .requestMatchers("/admin", "/admin/**", "/pages/admin/**").hasRole("ADMIN")
                        .requestMatchers("/profile", "/pages/profile.html", "/saved", "/pages/saved.html",
                                "/notifications", "/pages/notifications.html",
                                "/messages", "/messages/**", "/pages/messages.html").authenticated()
                        .anyRequest().permitAll())
                .exceptionHandling(e -> e
                        // Not signed in: API gets 401, pages go to the login page
                        .defaultAuthenticationEntryPointFor(
                                new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED), API)
                        .defaultAuthenticationEntryPointFor(loginThenBack(), AnyRequestMatcher.INSTANCE)
                        // Signed in with the wrong role: API gets 403, pages go to the user's own home page
                        .accessDeniedHandler((request, response, denied) -> {
                            if (API.matches(request)) {
                                response.sendError(HttpStatus.FORBIDDEN.value());
                            } else if (SecurityContextHolder.getContext().getAuthentication() != null
                                    && SecurityContextHolder.getContext().getAuthentication().getPrincipal()
                                    instanceof AppUserPrincipal me) {
                                response.sendRedirect(me.homePath());
                            } else {
                                response.sendRedirect("/");
                            }
                        }))
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .logout(logout -> logout
                        .logoutUrl("/api/auth/logout")
                        .logoutSuccessHandler((request, response, authentication) ->
                                response.setStatus(HttpStatus.NO_CONTENT.value())));

        if (clientRegistrations.getIfAvailable() != null) {
            http.oauth2Login(oauth -> oauth
                    .loginPage("/login")
                    .successHandler(socialLoginSuccessHandler)
                    .failureHandler((request, response, exception) ->
                            response.sendRedirect("/login?error=social")));
        }
        return http.build();
    }
}
