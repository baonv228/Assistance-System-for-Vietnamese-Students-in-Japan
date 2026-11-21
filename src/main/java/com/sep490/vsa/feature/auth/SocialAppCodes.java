package com.sep490.vsa.feature.auth;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * One-time codes of the app's Google/Facebook sign-in ({@code common/security/AppSocialLogin}): when the provider has
 * confirmed the person, the browser hands the app a code through {@code senpaioi://auth?code=…}; the code stands for
 * the provider's profile and the app's PKCE challenge until the app trades it in
 * ({@link SocialAppLoginService#exchange}). Nothing is written to the database before that, so the codes live in
 * memory like the sessions (one server; a restart forgets them and the person simply tries again).
 * <p>
 * A code is 32 random bytes (base64url, 43 characters), lives {@link #TTL} and is taken out at its first use, whatever
 * that use proves. At most {@link #MAX_LIVE} codes wait at once, so a flood of sign-ins cannot fill the memory.
 */
@Component
public class SocialAppCodes {

    static final Duration TTL = Duration.ofMinutes(5);
    static final int MAX_LIVE = 5000;

    /** What a code stands for: the app's S256 challenge and the profile the provider confirmed. */
    record Ticket(String challenge, PendingSocialSignup profile, Instant issuedAt) {
    }

    private static final Base64.Encoder BASE64URL = Base64.getUrlEncoder().withoutPadding();

    private final Map<String, Ticket> tickets = new ConcurrentHashMap<>();
    private final SecureRandom random = new SecureRandom();
    private volatile Clock clock;

    @Autowired
    public SocialAppCodes(Clock clock) {
        this.clock = clock;
    }

    /**
     * A new code for this challenge and profile; expired codes are dropped first.
     * @return empty when {@link #MAX_LIVE} codes are still waiting
     */
    synchronized Optional<String> issue(String challenge, PendingSocialSignup profile) {
        Instant now = clock.instant();
        tickets.values().removeIf(ticket -> expired(ticket, now));
        if (tickets.size() >= MAX_LIVE) {
            return Optional.empty();
        }
        Ticket ticket = new Ticket(challenge, profile, now);
        String code;
        do {
            code = newCode();
        } while (tickets.putIfAbsent(code, ticket) != null);
        return Optional.of(code);
    }

    /**
     * Takes the code out (it can never be used again, also when the caller then refuses the verifier).
     * @return the ticket, or empty when the code is unknown, already used or expired
     */
    Optional<Ticket> take(String code) {
        if (code == null) {
            return Optional.empty();
        }
        Ticket ticket = tickets.remove(code);
        if (ticket == null || expired(ticket, clock.instant())) {
            return Optional.empty();
        }
        return Optional.of(ticket);
    }

    /**
     * PKCE S256 (RFC 7636 section 4.6): {@code BASE64URL(SHA-256(ASCII(verifier)))} equals the challenge, compared in
     * constant time.
     */
    static boolean verifierMatches(String verifier, String challenge) {
        if (verifier == null || challenge == null) {
            return false;
        }
        return MessageDigest.isEqual(s256(verifier).getBytes(StandardCharsets.US_ASCII),
                challenge.getBytes(StandardCharsets.US_ASCII));
    }

    /** The S256 challenge of a verifier. */
    static String s256(String verifier) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII));
            return BASE64URL.encodeToString(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is part of every Java runtime", e);
        }
    }

    /** Tests: the clock codes are issued and checked with, so a test can move past {@link #TTL} without waiting. */
    void useClock(Clock clock) {
        this.clock = clock;
    }

    private String newCode() {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        return BASE64URL.encodeToString(bytes);
    }

    private static boolean expired(Ticket ticket, Instant now) {
        return !now.isBefore(ticket.issuedAt().plus(TTL));
    }
}
