package com.sep490.vsa.common.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

/**
 * Hashing and random values for one-time codes and tokens.
 * Codes and tokens are stored only as SHA-256 hashes, never in plain text.
 */
public final class TokenHasher {

    private static final SecureRandom RANDOM = new SecureRandom();

    private TokenHasher() {
    }

    /** SHA-256 as 64 lowercase hex characters. */
    public static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }

    /** Compares two hashes in constant time. */
    public static boolean matches(String expectedHash, String actualHash) {
        if (expectedHash == null || actualHash == null) {
            return false;
        }
        return MessageDigest.isEqual(
                expectedHash.getBytes(StandardCharsets.US_ASCII),
                actualHash.getBytes(StandardCharsets.US_ASCII));
    }

    /** Random numeric code, e.g. "048213" for digits = 6. */
    public static String randomDigits(int digits) {
        StringBuilder code = new StringBuilder(digits);
        for (int i = 0; i < digits; i++) {
            code.append(RANDOM.nextInt(10));
        }
        return code.toString();
    }

    /** Random URL-safe token with 256 bits of entropy. */
    public static String randomToken() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
