package com.sep490.vsa.common.security;

import java.io.Serializable;

/**
 * The signed-in user kept in the HTTP session. Read it in a controller with
 * {@code @AuthenticationPrincipal AppUserPrincipal me}.
 *
 * @param role     USER, MODERATOR, ACADEMIC_MANAGER or ADMIN
 * @param homePath page this role lands on after login
 */
public record AppUserPrincipal(Long id, String email, String displayName, String role, String homePath)
        implements Serializable {
}
