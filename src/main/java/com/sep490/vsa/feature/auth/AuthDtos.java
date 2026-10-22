package com.sep490.vsa.feature.auth;

import com.sep490.vsa.feature.user.User;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** Request and response bodies of /api/auth. Messages are shown to the user as-is. */
public final class AuthDtos {

    private AuthDtos() {
    }

    /** Leading and trailing spaces off (Unicode ones too, e.g. the full-width space of a Japanese keyboard). */
    private static String strip(String value) {
        return value == null ? null : value.strip();
    }

    public record SendCodeRequest(
            @NotBlank(message = "Vui lòng nhập email")
            @Email(message = "Email không hợp lệ")
            @Size(max = 255, message = "Email quá dài")
            String email) {
    }

    public record SendCodeResponse(String email, long expiresInMinutes, long resendAfterSeconds) {
    }

    public record VerifyCodeRequest(
            @NotBlank(message = "Vui lòng nhập email") @Email(message = "Email không hợp lệ") String email,
            @NotBlank(message = "Vui lòng nhập mã xác thực")
            @Pattern(regexp = "\\d{6}", message = "Mã xác thực gồm 6 chữ số")
            String code) {
    }

    /**
     * One-time ticket that proves the email was verified; sent back with the password step. {@code passwordMinLength}
     * is the Admin setting password_min_length, shown in the password rules of that step.
     */
    public record VerifyCodeResponse(String ticket, long expiresInMinutes, int passwordMinLength) {
    }

    /**
     * The minimum password length is an Admin setting, so RegistrationService checks it (and BCrypt's 72 bytes); the
     * display name is stripped before it is validated, so " a" is too short.
     */
    public record CompleteRegistrationRequest(
            @NotBlank(message = "Phiên đăng ký đã hết hạn, vui lòng bắt đầu lại") String ticket,
            @NotBlank(message = "Vui lòng nhập tên hiển thị")
            @Size(min = 2, max = 60, message = "Tên hiển thị dài từ 2 đến 60 ký tự")
            String displayName,
            @NotBlank(message = "Vui lòng nhập mật khẩu")
            @Size(max = 72, message = "Mật khẩu dài tối đa 72 ký tự")
            @Pattern(regexp = "^(?=.*\\p{L})(?=.*\\d).+$", message = "Mật khẩu phải có cả chữ và số")
            String password,
            @AssertTrue(message = "Bạn phải từ đủ 18 tuổi để đăng ký") boolean adultConfirmed,
            @AssertTrue(message = "Bạn cần đồng ý Điều khoản, Chính sách bảo mật và Quy tắc cộng đồng") boolean acceptTerms) {

        public CompleteRegistrationRequest {
            displayName = strip(displayName);
        }
    }

    public record LoginRequest(
            @NotBlank(message = "Vui lòng nhập email") @Email(message = "Email không hợp lệ") String email,
            @NotBlank(message = "Vui lòng nhập mật khẩu") String password) {
    }

    /** The display name is stripped before it is validated, as in {@link CompleteRegistrationRequest}. */
    public record CompleteSocialSignupRequest(
            @NotBlank(message = "Vui lòng nhập tên hiển thị")
            @Size(min = 2, max = 60, message = "Tên hiển thị dài từ 2 đến 60 ký tự")
            String displayName,
            @AssertTrue(message = "Bạn phải từ đủ 18 tuổi để đăng ký") boolean adultConfirmed,
            @AssertTrue(message = "Bạn cần đồng ý Điều khoản, Chính sách bảo mật và Quy tắc cộng đồng") boolean acceptTerms) {

        public CompleteSocialSignupRequest {
            displayName = strip(displayName);
        }
    }

    public record PendingSocialResponse(String provider, String email, String displayName) {
    }

    /** Which social sign-in buttons the login page should enable. */
    public record ProvidersResponse(boolean google, boolean facebook) {
    }

    /** {@code homePath} is where the frontend sends this user after login (depends on the role). */
    public record CurrentUserResponse(Long id, String email, String displayName, String role, String roleLabel,
                                      String homePath, String avatarUrl) {
        public static CurrentUserResponse of(User user) {
            return new CurrentUserResponse(user.getId(), user.getEmail(), user.getDisplayName(),
                    user.getRole().name(), user.getRole().label(), user.getRole().homePath(), user.getAvatarUrl());
        }
    }
}
