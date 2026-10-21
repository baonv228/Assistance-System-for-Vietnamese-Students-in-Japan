package com.sep490.vsa.feature.auth;

import com.sep490.vsa.common.dto.ApiResponse;
import com.sep490.vsa.common.exception.ApiException;
import com.sep490.vsa.common.security.AppUserPrincipal;
import com.sep490.vsa.feature.auth.AuthDtos.CompleteRegistrationRequest;
import com.sep490.vsa.feature.auth.AuthDtos.CompleteSocialSignupRequest;
import com.sep490.vsa.feature.auth.AuthDtos.CurrentUserResponse;
import com.sep490.vsa.feature.auth.AuthDtos.LoginRequest;
import com.sep490.vsa.feature.auth.AuthDtos.PendingSocialResponse;
import com.sep490.vsa.feature.auth.AuthDtos.ProvidersResponse;
import com.sep490.vsa.feature.auth.AuthDtos.SendCodeRequest;
import com.sep490.vsa.feature.auth.AuthDtos.SendCodeResponse;
import com.sep490.vsa.feature.auth.AuthDtos.VerifyCodeRequest;
import com.sep490.vsa.feature.auth.AuthDtos.VerifyCodeResponse;
import com.sep490.vsa.feature.user.User;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Sign-up, login and session endpoints. Logout is POST /api/auth/logout (handled by Spring Security).
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final RegistrationService registrationService;
    private final AuthService authService;
    private final SocialLoginService socialLoginService;
    private final SessionSignIn sessionSignIn;
    private final ObjectProvider<ClientRegistrationRepository> clientRegistrations;

    public AuthController(RegistrationService registrationService, AuthService authService,
                          SocialLoginService socialLoginService, SessionSignIn sessionSignIn,
                          ObjectProvider<ClientRegistrationRepository> clientRegistrations) {
        this.registrationService = registrationService;
        this.authService = authService;
        this.socialLoginService = socialLoginService;
        this.sessionSignIn = sessionSignIn;
        this.clientRegistrations = clientRegistrations;
    }

    /** The page calls this first; the value goes into the X-XSRF-TOKEN header of every POST. */
    @GetMapping("/csrf")
    public ApiResponse<Map<String, String>> csrf(CsrfToken token) {
        return ApiResponse.ok("OK", Map.of("headerName", token.getHeaderName(), "token", token.getToken()));
    }

    @GetMapping("/providers")
    public ApiResponse<ProvidersResponse> providers() {
        ClientRegistrationRepository repo = clientRegistrations.getIfAvailable();
        boolean google = false;
        boolean facebook = false;
        if (repo instanceof InMemoryClientRegistrationRepository registrations) {
            for (var registration : registrations) {
                google |= "google".equals(registration.getRegistrationId());
                facebook |= "facebook".equals(registration.getRegistrationId());
            }
        }
        return ApiResponse.ok("OK", new ProvidersResponse(google, facebook));
    }

    // ----- Email sign-up: 1. send code → 2. verify code → 3. name + password -----

    /** The client address counts toward the hourly code limit (behind a proxy it must be the visitor's address). */
    @PostMapping("/register/send-code")
    public ApiResponse<SendCodeResponse> sendCode(@Valid @RequestBody SendCodeRequest body, HttpServletRequest request) {
        return ApiResponse.ok("Đã gửi mã xác thực tới email của bạn.",
                registrationService.sendCode(body.email(), request.getRemoteAddr()));
    }

    @PostMapping("/register/verify-code")
    public ApiResponse<VerifyCodeResponse> verifyCode(@Valid @RequestBody VerifyCodeRequest request) {
        return ApiResponse.ok("Xác thực email thành công.",
                registrationService.verifyCode(request.email(), request.code()));
    }

    @PostMapping("/register/complete")
    public ApiResponse<CurrentUserResponse> completeRegistration(@Valid @RequestBody CompleteRegistrationRequest request) {
        User user = registrationService.complete(request);
        return ApiResponse.ok("Đăng ký thành công. Hãy đăng nhập.", CurrentUserResponse.of(user));
    }

    // ----- Login and session -----

    @PostMapping("/login")
    public ApiResponse<CurrentUserResponse> login(@Valid @RequestBody LoginRequest body,
                                                  HttpServletRequest request, HttpServletResponse response) {
        User user = authService.login(body.email(), body.password());
        sessionSignIn.signIn(user, request, response);
        return ApiResponse.ok("Đăng nhập thành công.", CurrentUserResponse.of(user));
    }

    /** Returns 401 when nobody is signed in. */
    @GetMapping("/me")
    public ApiResponse<CurrentUserResponse> me(@AuthenticationPrincipal AppUserPrincipal me) {
        if (me == null) {
            throw ApiException.unauthorized("Bạn chưa đăng nhập.");
        }
        return ApiResponse.ok("OK", CurrentUserResponse.of(authService.get(me.id())));
    }

    // ----- First Google/Facebook sign-in -----

    @GetMapping("/social/pending")
    public ApiResponse<PendingSocialResponse> pendingSocial(HttpSession session) {
        PendingSocialSignup pending = pending(session);
        return ApiResponse.ok("OK", new PendingSocialResponse(pending.provider(), pending.email(), pending.displayName()));
    }

    @PostMapping("/social/complete")
    public ApiResponse<CurrentUserResponse> completeSocial(@Valid @RequestBody CompleteSocialSignupRequest body,
                                                           HttpServletRequest request, HttpServletResponse response) {
        HttpSession session = request.getSession(false);
        PendingSocialSignup pending = pending(session);
        User user = socialLoginService.completeSignup(pending, body.displayName());
        session.removeAttribute(PendingSocialSignup.SESSION_KEY);
        sessionSignIn.signIn(user, request, response);
        return ApiResponse.ok("Đăng ký thành công.", CurrentUserResponse.of(user));
    }

    private static PendingSocialSignup pending(HttpSession session) {
        Object value = session == null ? null : session.getAttribute(PendingSocialSignup.SESSION_KEY);
        if (!(value instanceof PendingSocialSignup pending)) {
            throw new ApiException(HttpStatus.NOT_FOUND, "Phiên đăng ký đã hết hạn. Hãy đăng nhập lại bằng Google hoặc Facebook.");
        }
        return pending;
    }
}
