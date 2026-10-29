package com.sep490.vsa.feature.chat.notify;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.Signature;
import java.security.interfaces.ECPrivateKey;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * VAPID (RFC 8292): every push request carries a JWT signed with the server's P-256 key, so the push service knows
 * the subscription it delivers to was made with our public key (the browser was given it when subscribing) and whom to
 * contact (the {@code sub} claim). Header: {@code Authorization: vapid t=<jwt>, k=<base64url public key>}.
 */
final class Vapid {

    /** "exp" at most 24 h ahead (RFC 8292 §2); 12 h like the common libraries, so a clock a little ahead is fine. */
    static final Duration LIFETIME = Duration.ofHours(12);
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String HEADER = P256.encode("{\"typ\":\"JWT\",\"alg\":\"ES256\"}"
            .getBytes(StandardCharsets.UTF_8));

    private Vapid() {
    }

    /** The JWT: ES256 over header.claims, the signature as raw R ‖ S (64 bytes), as JWS wants it. */
    static String jwt(String audience, Instant expiresAt, String subject, ECPrivateKey key) {
        Map<String, Object> claims = new LinkedHashMap<>();
        claims.put("aud", audience);
        claims.put("exp", expiresAt.getEpochSecond());
        claims.put("sub", subject);
        String signingInput;
        try {
            signingInput = HEADER + "." + P256.encode(JSON.writeValueAsBytes(claims));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
        try {
            Signature signature = Signature.getInstance("SHA256withECDSA");
            signature.initSign(key);
            signature.update(signingInput.getBytes(StandardCharsets.US_ASCII));
            return signingInput + "." + P256.encode(derToRaw(signature.sign()));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("ES256 is not available", e);
        }
    }

    static String authorization(String jwt, byte[] publicKeyRaw) {
        return "vapid t=" + jwt + ", k=" + P256.encode(publicKeyRaw);
    }

    /** The "aud" claim: the origin of the push endpoint (scheme://host, plus the port when it is not the default). */
    static String audience(URI endpoint) {
        String scheme = endpoint.getScheme().toLowerCase(Locale.ROOT);
        int port = endpoint.getPort();
        boolean defaultPort = port == -1 || ("https".equals(scheme) && port == 443)
                || ("http".equals(scheme) && port == 80);
        return scheme + "://" + endpoint.getHost().toLowerCase(Locale.ROOT) + (defaultPort ? "" : ":" + port);
    }

    /**
     * The JDK signs ECDSA as DER (SEQUENCE { INTEGER r, INTEGER s }); JWS ES256 wants r and s as two unsigned 32-byte
     * numbers.
     */
    static byte[] derToRaw(byte[] der) {
        int at = 0;
        if (der.length < 8 || der[at++] != 0x30) {
            throw new IllegalArgumentException("not a DER ECDSA signature");
        }
        int length = der[at++] & 0xff;
        if (length == 0x81) {
            length = der[at++] & 0xff;
        }
        if (length != der.length - at) {
            throw new IllegalArgumentException("bad DER length");
        }
        byte[] out = new byte[64];
        for (int part = 0; part < 2; part++) {
            if (der[at++] != 0x02) {
                throw new IllegalArgumentException("not a DER INTEGER");
            }
            int size = der[at++] & 0xff;
            byte[] value = Arrays.copyOfRange(der, at, at + size);
            at += size;
            int skip = 0;
            while (skip < value.length - 1 && value[skip] == 0) {
                skip++;                                                    // sign byte or leading zeros
            }
            int significant = value.length - skip;
            if (significant > 32) {
                throw new IllegalArgumentException("ECDSA value too long");
            }
            System.arraycopy(value, skip, out, part * 32 + 32 - significant, significant);
        }
        return out;
    }
}
