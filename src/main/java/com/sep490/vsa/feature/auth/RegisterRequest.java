package com.sep490.vsa.feature.auth;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record RegisterRequest(
        @NotBlank @Size(max = 120) String fullName,
        @NotBlank @Size(min = 3, max = 50) String username,
        @Email @NotBlank String email,
        @NotBlank @Size(min = 6, max = 72) String password
) {
}
