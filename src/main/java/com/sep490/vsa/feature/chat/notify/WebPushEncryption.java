package com.sep490.vsa.feature.chat.notify;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.SecureRandom;
import java.security.interfaces.ECPrivateKey;
import java.security.interfaces.ECPublicKey;
import java.util.Arrays;

/**
 * Encrypts a push message for one browser (RFC 8291, with the "aes128gcm" content coding of RFC 8188), so the push
 * service that carries it (Google, Mozilla, Microsoft, Apple) cannot read it. ECDH between a fresh key pair of ours and
 * the browser's key, mixed with the browser's auth secret, gives the AES-128-GCM key and nonce of the message.
 * <p>
 * One record only (our payloads are a few hundred bytes), ended by the padding delimiter 0x02, no padding. The body
 * is header (salt 16 ‖ record size 4 ‖ key id length 1 ‖ our public key 65) ‖ ciphertext with its 16-byte tag.
 */
final class WebPushEncryption {

    /** Record size written in the header; the whole message fits in one record of this size. */
    static final int RECORD_SIZE = 4096;
    /** RFC 8291 §4: 4096 bytes of body minus the 86-byte header, the delimiter and the 16-byte tag. */
    static final int MAX_PLAINTEXT = 3993;
    private static final int SALT_LENGTH = 16;
    private static final int AUTH_LENGTH = 16;
    private static final SecureRandom RANDOM = new SecureRandom();

    private WebPushEncryption() {
    }

    /** The values derived for one message (kept apart so the test can compare them with RFC 8291 Appendix A). */
    record Derived(byte[] ecdhSecret, byte[] contentKey, byte[] nonce) {
    }

    /** Encrypts with a fresh ephemeral key pair and a random salt. */
    static byte[] encrypt(byte[] plaintext, byte[] browserPublicKey, byte[] authSecret) {
        byte[] salt = new byte[SALT_LENGTH];
        RANDOM.nextBytes(salt);
        return encrypt(plaintext, browserPublicKey, authSecret, P256.generate(), salt);
    }

    /**
     * Encrypts with the given ephemeral key pair and salt (the test reproduces the RFC's example with them; never
     * reuse either for a second message).
     */
    static byte[] encrypt(byte[] plaintext, byte[] browserPublicKey, byte[] authSecret, KeyPair ephemeral,
                          byte[] salt) {
        if (plaintext.length > MAX_PLAINTEXT) {
            throw new IllegalArgumentException("push payload too large");
        }
        if (salt.length != SALT_LENGTH) {
            throw new IllegalArgumentException("the salt has 16 bytes");
        }
        byte[] serverPublic = P256.raw((ECPublicKey) ephemeral.getPublic());
        Derived derived = derive(browserPublicKey, authSecret, (ECPrivateKey) ephemeral.getPrivate(), serverPublic,
                salt);
        byte[] record = Arrays.copyOf(plaintext, plaintext.length + 1);
        record[plaintext.length] = 0x02;                                  // delimiter of the last (only) record
        byte[] ciphertext;
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(derived.contentKey(), "AES"),
                    new GCMParameterSpec(128, derived.nonce()));
            ciphertext = cipher.doFinal(record);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("AES-GCM is not available", e);
        }
        ByteBuffer body = ByteBuffer.allocate(SALT_LENGTH + 4 + 1 + serverPublic.length + ciphertext.length);
        body.put(salt).putInt(RECORD_SIZE).put((byte) serverPublic.length).put(serverPublic).put(ciphertext);
        return body.array();
    }

    /**
     * RFC 8291 §3.3–3.4: ecdh_secret = ECDH(ours, browser's); IKM = HKDF(salt = auth_secret, ecdh_secret,
     * "WebPush: info" 0x00 ‖ ua_public ‖ as_public, 32); PRK = HKDF-Extract(salt, IKM); CEK = HKDF-Expand(PRK,
     * "Content-Encoding: aes128gcm" 0x00, 16); NONCE = HKDF-Expand(PRK, "Content-Encoding: nonce" 0x00, 12).
     */
    static Derived derive(byte[] browserPublicKey, byte[] authSecret, ECPrivateKey serverPrivate, byte[] serverPublic,
                          byte[] salt) {
        if (authSecret == null || authSecret.length != AUTH_LENGTH) {
            throw new IllegalArgumentException("the auth secret has 16 bytes");
        }
        ECPublicKey browser = P256.publicKey(browserPublicKey);
        byte[] ecdhSecret = P256.sharedSecret(serverPrivate, browser);
        byte[] prkKey = hmac(authSecret, ecdhSecret);
        byte[] keyInfo = concat(ascii("WebPush: info"), new byte[]{0}, browserPublicKey, serverPublic);
        byte[] ikm = hmac(prkKey, keyInfo, new byte[]{1});
        byte[] prk = hmac(salt, ikm);
        byte[] contentKey = Arrays.copyOf(hmac(prk, ascii("Content-Encoding: aes128gcm"), new byte[]{0, 1}), 16);
        byte[] nonce = Arrays.copyOf(hmac(prk, ascii("Content-Encoding: nonce"), new byte[]{0, 1}), 12);
        return new Derived(ecdhSecret, contentKey, nonce);
    }

    /** HMAC-SHA-256 (one HKDF step) over the parts. */
    static byte[] hmac(byte[] key, byte[]... parts) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            for (byte[] part : parts) {
                mac.update(part);
            }
            return mac.doFinal();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HMAC-SHA256 is not available", e);
        }
    }

    private static byte[] ascii(String text) {
        return text.getBytes(StandardCharsets.US_ASCII);
    }

    private static byte[] concat(byte[]... parts) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] part : parts) {
            out.writeBytes(part);
        }
        return out.toByteArray();
    }
}
