package com.sep490.vsa.feature.auth;

import java.io.Serializable;

/**
 * Google/Facebook profile of someone signing in for the first time. Kept in the session until they
 * confirm they are 18+ and accept the terms on /complete-signup.
 */
public record PendingSocialSignup(String provider, String providerUserId, String email, String displayName,
                                  String avatarUrl) implements Serializable {

    static final String SESSION_KEY = "PENDING_SOCIAL_SIGNUP";
}
