package com.sep490.vsa.common.security;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.security.web.util.matcher.AntPathRequestMatcher;

/**
 * Session-based security for the same-origin HTML/JS frontend.
 * <ul>
 *   <li>Login state lives in the HTTP session (cookie JSESSIONID).</li>
 *   <li>CSRF: the page reads the token from GET /api/auth/csrf and sends it in the X-XSRF-TOKEN header.</li>
 *   <li>GET /api/** is public (guests can view); other API calls need a signed-in user.</li>
 *   <li>Google/Facebook login is switched on only when their client ids are configured.</li>
 * </ul>
 */
@Configuration
public class SecurityConfig {

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public SecurityContextRepository securityContextRepository() {
        return new HttpSessionSecurityContextRepository();
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http,
                                                   SecurityContextRepository contextRepository,
                                                   ObjectProvider<ClientRegistrationRepository> clientRegistrations,
                                                   AuthenticationSuccessHandler socialLoginSuccessHandler) throws Exception {
        http
                .csrf(csrf -> csrf
                        .csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse())
                        .csrfTokenRequestHandler(new CsrfTokenRequestAttributeHandler()))
                .securityContext(context -> context.securityContextRepository(contextRepository))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/api/auth/**", "/api/legal/**").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/**").permitAll()
                        .requestMatchers("/api/**").authenticated()
                        .anyRequest().permitAll())
                .exceptionHandling(e -> e.defaultAuthenticationEntryPointFor(
                        new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED), new AntPathRequestMatcher("/api/**")))
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .logout(logout -> logout
                        .logoutUrl("/api/auth/logout")
                        .logoutSuccessHandler((request, response, authentication) ->
                                response.setStatus(HttpStatus.NO_CONTENT.value())));

        if (clientRegistrations.getIfAvailable() != null) {
            http.oauth2Login(oauth -> oauth
                    .loginPage("/pages/login.html")
                    .successHandler(socialLoginSuccessHandler)
                    .failureHandler((request, response, exception) ->
                            response.sendRedirect("/pages/login.html?error=social")));
        }
        return http.build();
    }
}
