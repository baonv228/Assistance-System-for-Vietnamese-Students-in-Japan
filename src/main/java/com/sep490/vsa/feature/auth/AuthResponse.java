package com.sep490.vsa.feature.auth;

public record AuthResponse(
        Long userId,
        String fullName,
        String username,
        String email,
        String role,
        String token
) {
}
