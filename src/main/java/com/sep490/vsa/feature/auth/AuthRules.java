package com.sep490.vsa.feature.auth;

import java.time.Duration;
import java.util.Locale;

/**
 * Limits of the sign-up and login flows. Where the Admin has a setting (table system_settings), the value here is
 * only its default: {@link AuthService} and {@link RegistrationService} read the setting on every request and fall
 * back to these when it is missing or not a number.
 */
final class AuthRules {

    static final int CODE_DIGITS = 6;
    /** Defaults of email_code_minutes, email_code_attempts, email_code_resend_seconds and register_ticket_minutes. */
    static final Duration CODE_VALID_FOR = Duration.ofMinutes(10);
    static final int CODE_MAX_ATTEMPTS = 5;
    static final Duration RESEND_AFTER = Duration.ofSeconds(60);
    static final Duration TICKET_VALID_FOR = Duration.ofMinutes(30);
    /** Codes one mailbox may receive in 24 hours ({@link #mailbox}; no Admin setting). */
    static final int CODES_PER_DAY = 10;
    /** Codes sent for one client address (IP) in an hour, counted in memory. Cần chốt. */
    static final int CODES_PER_ADDRESS_HOUR = 5;
    /**
     * Codes the whole site sends in 24 hours. Cần chốt: kept below the daily sending limit of the Gmail account that
     * sends them (about 500 emails a day for a normal Gmail account).
     */
    static final int CODES_PER_DAY_SITE = 300;

    /** Defaults of lock_attempts and lock_minutes: 5 wrong passwords in a row lock the account for 30 minutes. */
    static final int LOGIN_MAX_ATTEMPTS = 5;
    static final Duration LOGIN_LOCK = Duration.ofMinutes(30);

    /** Default of password_min_length. */
    static final int PASSWORD_MIN_LENGTH = 8;
    static final int PASSWORD_MAX_LENGTH = 72;
    /** BCrypt reads only the first 72 bytes of a password (UTF-8: an accented letter takes 2 or 3 bytes). */
    static final int PASSWORD_MAX_BYTES = 72;

    private AuthRules() {
    }

    static String normalizeEmail(String email) {
        return email == null ? null : email.trim().toLowerCase(Locale.ROOT);
    }

    /**
     * The mailbox a (normalized) address delivers to, used only to count codes: the account keeps the email exactly as
     * typed. A "+tag" is dropped for every domain, and for Gmail the dots too: "a.b+x@gmail.com", "ab+y@gmail.com" and
     * "ab@googlemail.com" are all "ab@gmail.com".
     */
    static String mailbox(String email) {
        int at = email.lastIndexOf('@');
        if (at < 0) {
            return email;
        }
        String local = email.substring(0, at);
        String domain = email.substring(at + 1);
        int plus = local.indexOf('+');
        if (plus >= 0) {
            local = local.substring(0, plus);
        }
        if (domain.equals("gmail.com") || domain.equals("googlemail.com")) {
            local = local.replace(".", "");
            domain = "gmail.com";
        }
        return local + "@" + domain;
    }

    /** An Admin setting kept inside the range the code and the columns can take. */
    static int within(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
