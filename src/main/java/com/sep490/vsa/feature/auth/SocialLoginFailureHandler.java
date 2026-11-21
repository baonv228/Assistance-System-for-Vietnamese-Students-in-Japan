package com.sep490.vsa.feature.auth;

import com.sep490.vsa.common.security.AppSocialLogin;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.web.authentication.AuthenticationFailureHandler;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * Google/Facebook did not confirm the person (or the exchange with them failed). The person goes back to the sign-in
 * page with a message; the reason is written to the server log (error code and the provider's own description, never a
 * code, token or secret), because on a server "không thành công" alone cannot be diagnosed (wrong redirect URI, app
 * not published, wrong client secret…).
 * <p>
 * A round started by the mobile app ({@link AppSocialLogin}) goes back to the app's address
 * ({@code app.mobile.social-callback}) with the same error names instead. That is known only once the callback has
 * taken out the authorization request the round started with; a callback without it (wrong state, another browser)
 * cannot be told apart and goes to the website's page.
 */
@Component
public class SocialLoginFailureHandler implements AuthenticationFailureHandler {

    private static final Logger log = LoggerFactory.getLogger(SocialLoginFailureHandler.class);

    private final String appCallback;

    public SocialLoginFailureHandler(@Value("${app.mobile.social-callback:senpaioi://auth}") String appCallback) {
        this.appCallback = appCallback;
    }

    @Override
    public void onAuthenticationFailure(HttpServletRequest request, HttpServletResponse response,
                                        AuthenticationException exception) throws IOException {
        String provider = request.getRequestURI().substring(request.getRequestURI().lastIndexOf('/') + 1);
        String code = "unknown";
        String description = exception.getMessage();
        if (exception instanceof OAuth2AuthenticationException oauth) {
            OAuth2Error error = oauth.getError();
            code = error.getErrorCode();
            if (error.getDescription() != null) {
                description = error.getDescription();
            }
        }
        // The provider's own "error" parameter on the way back, e.g. access_denied when the person pressed "Hủy"
        String returned = request.getParameter("error");
        if (returned != null && !returned.isBlank()) {
            code = returned;
        }
        log.warn("Social sign-in with {} failed: {} - {}", clean(provider), clean(code), clean(description));

        boolean cancelled = "access_denied".equals(code);
        String error = cancelled ? "social_cancelled" : "social";
        if (AppSocialLogin.challenge(request).isPresent()) {
            response.sendRedirect(AppSocialLogin.callback(appCallback, "error", error));
            return;
        }
        response.sendRedirect("/login?error=" + error);
    }

    /** One short line: the values come from the provider or the address, so no line breaks and no long text. */
    private static String clean(String text) {
        if (text == null) {
            return "";
        }
        String line = text.replaceAll("[\\r\\n\\t]+", " ").trim();
        return line.length() > 300 ? line.substring(0, 300) + "…" : line;
    }
}
