package com.sep490.vsa.feature.chat;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;

/**
 * The few checks the server makes on end-to-end encrypted chat data. The server never decrypts anything: it checks
 * that a public key is a real P-256 point (a wrong one would make the other member's browser fail or, with a crafted
 * point, leak bits of their private key), and verifies the franking tag of a reported message (so a reporter cannot
 * put words in the writer's mouth). Formats in the phase-2 contract: standard base64 with padding, raw uncompressed
 * points, HMAC-SHA256 over {@code "asvsj-frank-v1|<conversationId>|<senderId>|"} followed by the message body P.
 */
final class ChatCrypto {

    /** P-256 (secp256r1): y² = x³ − 3x + b over the prime field p. */
    private static final BigInteger P = new BigInteger(
            "ffffffff00000001000000000000000000000000ffffffffffffffffffffffff", 16);
    private static final BigInteger B = new BigInteger(
            "5ac635d8aa3a93e7b3ebbd55769886bc651d06b0cc53b0f63bce3c3e27d2604b", 16);
    private static final BigInteger THREE = BigInteger.valueOf(3);
    static final int POINT_BYTES = 65;
    static final int FRANKING_KEY_BYTES = 32;
    private static final String FRANKING_PREFIX = "asvsj-frank-v1|";

    private ChatCrypto() {
    }

    /** Standard base64 decoded, or null when the text is missing or not base64 (spaces and URL-safe letters too). */
    static byte[] base64(String text) {
        if (text == null || text.isEmpty()) {
            return null;
        }
        try {
            return Base64.getDecoder().decode(text);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    static String base64(byte[] bytes) {
        return Base64.getEncoder().encodeToString(bytes);
    }

    /**
     * True for a raw uncompressed P-256 point (0x04 ‖ x ‖ y, 32 bytes each) that lies on the curve, with x and y
     * smaller than p. The point at infinity has no such encoding, so it is refused too.
     */
    static boolean isP256Point(byte[] raw) {
        if (raw == null || raw.length != POINT_BYTES || raw[0] != 0x04) {
            return false;
        }
        BigInteger x = new BigInteger(1, Arrays.copyOfRange(raw, 1, 33));
        BigInteger y = new BigInteger(1, Arrays.copyOfRange(raw, 33, 65));
        if (x.compareTo(P) >= 0 || y.compareTo(P) >= 0) {
            return false;
        }
        BigInteger left = y.multiply(y).mod(P);
        BigInteger right = x.pow(3).subtract(THREE.multiply(x)).add(B).mod(P);
        return left.equals(right);
    }

    /** Lower-case hex HMAC-SHA256(key = fk, data = "asvsj-frank-v1|conversationId|senderId|" ‖ plaintext). */
    static String frankingTag(byte[] frankingKey, long conversationId, long senderId, byte[] plaintext) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(frankingKey, "HmacSHA256"));
            mac.update((FRANKING_PREFIX + conversationId + "|" + senderId + "|").getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(mac.doFinal(plaintext));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 is not available", e);
        }
    }

    /** Compares two tags in constant time (the time does not tell how many leading characters matched). */
    static boolean sameTag(String expected, String actual) {
        if (expected == null || actual == null) {
            return false;
        }
        return MessageDigest.isEqual(expected.getBytes(StandardCharsets.US_ASCII),
                actual.getBytes(StandardCharsets.US_ASCII));
    }

    /** The bytes as UTF-8 text, or null when they are not valid UTF-8. */
    static String utf8(byte[] bytes) {
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException e) {
            return null;
        }
    }
}
