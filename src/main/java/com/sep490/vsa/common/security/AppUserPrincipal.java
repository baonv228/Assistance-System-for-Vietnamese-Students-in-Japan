package com.sep490.vsa.common.security;

import java.io.Serializable;

/**
 * The signed-in user kept in the HTTP session. Read it in a controller with
 * {@code @AuthenticationPrincipal AppUserPrincipal me}.
 */
public record AppUserPrincipal(Long id, String email, String displayName, String role) implements Serializable {
}
