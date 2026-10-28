package com.sep490.vsa.feature.auth;

import com.sep490.vsa.common.dto.ApiResponse;
import com.sep490.vsa.common.security.AppUserPrincipal;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * UC-U05 "Quên mật khẩu" (pages /forgot-password and /reset-password, open to guests under /api/auth/**) and UC-U06
 * "Đổi mật khẩu" (the "Tôi" page, /api/me/** needs a signed-in user).
 */
@RestController
public class PasswordController {

    public record ForgotRequest(
            @NotBlank(message = "Vui lòng nhập email") @Email(message = "Email không hợp lệ")
            @Size(max = 255, message = "Email không hợp lệ") String email) {
    }

    /** The token goes in the body, not the address, so it does not end up in server or proxy logs. */
    public record TokenRequest(String token) {
    }

    public record ResetRequest(String token, String password, String confirmPassword) {
    }

    public record ChangeRequest(String currentPassword, String password, String confirmPassword) {
    }

    /** @param email the account of a valid link, masked ("a***@gmail.com") */
    public record LinkCheck(String email) {
    }

    private final PasswordService passwords;

    public PasswordController(PasswordService passwords) {
        this.passwords = passwords;
    }

    /** The message is also the data, for the page to show (client.js hands pages the data). */
    @PostMapping("/api/auth/password/forgot")
    public ApiResponse<String> forgot(@Valid @RequestBody ForgotRequest body) {
        String message = passwords.forgot(body.email());
        return ApiResponse.ok(message, message);
    }

    @PostMapping("/api/auth/password/check")
    public ApiResponse<LinkCheck> check(@RequestBody TokenRequest body) {
        return ApiResponse.ok("OK", new LinkCheck(passwords.check(body.token())));
    }

    @PostMapping("/api/auth/password/reset")
    public ApiResponse<Void> reset(@RequestBody ResetRequest body) {
        passwords.reset(body.token(), body.password(), body.confirmPassword());
        return ApiResponse.ok("Đã đặt lại mật khẩu. Hãy đăng nhập bằng mật khẩu mới.", null);
    }

    @PutMapping("/api/me/password")
    public ApiResponse<String> change(@RequestBody ChangeRequest body, @AuthenticationPrincipal AppUserPrincipal me,
                                      HttpServletRequest request) {
        String message = passwords.change(me.id(), body.currentPassword(), body.password(), body.confirmPassword(),
                request);
        return ApiResponse.ok(message, message);
    }
}
