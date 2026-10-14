package com.sep490.vsa.feature.auth;

import java.time.Duration;
import java.util.Locale;

/**
 * Limits of the sign-up and login flows. They mirror the defaults in table system_settings;
 * read them from there once the Admin settings feature exists.
 */
final class AuthRules {

    static final int CODE_DIGITS = 6;
    static final Duration CODE_VALID_FOR = Duration.ofMinutes(10);
    static final int CODE_MAX_ATTEMPTS = 5;
    static final Duration RESEND_AFTER = Duration.ofSeconds(60);
    static final int CODES_PER_DAY = 10;
    static final Duration TICKET_VALID_FOR = Duration.ofMinutes(30);

    static final int LOGIN_MAX_ATTEMPTS = 5;
    static final Duration LOGIN_LOCK = Duration.ofMinutes(30);

    private AuthRules() {
    }

    static String normalizeEmail(String email) {
        return email == null ? null : email.trim().toLowerCase(Locale.ROOT);
    }
}
