package com.sep490.vsa.feature.chat.notify;

import javax.crypto.KeyAgreement;
import java.math.BigInteger;
import java.security.AlgorithmParameters;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.ECPrivateKey;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECFieldFp;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.ECParameterSpec;
import java.security.spec.ECPoint;
import java.security.spec.ECPrivateKeySpec;
import java.security.spec.ECPublicKeySpec;
import java.util.Base64;

/**
 * The P-256 curve (secp256r1) as Web Push uses it: browsers give their key as the raw uncompressed point (65 bytes,
 * 0x04 ‖ x ‖ y, base64url) and VAPID publishes ours the same way, while the JDK works with key objects. A browser key is
 * checked to be a point of the curve before it is stored or used, so a made-up key is refused at once.
 */
final class P256 {

    static final ECParameterSpec PARAMS = params();
    static final int RAW_PUBLIC_LENGTH = 65;
    private static final BigInteger P = ((ECFieldFp) PARAMS.getCurve().getField()).getP();

    private P256() {
    }

    /** The key of a raw uncompressed point; IllegalArgumentException when it is not a point of P-256. */
    static ECPublicKey publicKey(byte[] raw) {
        if (raw == null || raw.length != RAW_PUBLIC_LENGTH || raw[0] != 0x04) {
            throw new IllegalArgumentException("not an uncompressed P-256 point");
        }
        BigInteger x = new BigInteger(1, raw, 1, 32);
        BigInteger y = new BigInteger(1, raw, 33, 32);
        // y² = x³ + ax + b (mod p), with a = −3 mod p as the JDK stores it
        BigInteger left = y.multiply(y).mod(P);
        BigInteger right = x.pow(3).add(PARAMS.getCurve().getA().multiply(x)).add(PARAMS.getCurve().getB()).mod(P);
        if (x.compareTo(P) >= 0 || y.compareTo(P) >= 0 || !left.equals(right)) {
            throw new IllegalArgumentException("not a point of P-256");
        }
        try {
            return (ECPublicKey) KeyFactory.getInstance("EC")
                    .generatePublic(new ECPublicKeySpec(new ECPoint(x, y), PARAMS));
        } catch (GeneralSecurityException e) {
            throw new IllegalArgumentException("not a P-256 key", e);
        }
    }

    /** 0x04 ‖ x ‖ y, each coordinate on 32 bytes. */
    static byte[] raw(ECPublicKey key) {
        byte[] out = new byte[RAW_PUBLIC_LENGTH];
        out[0] = 0x04;
        System.arraycopy(fixed(key.getW().getAffineX()), 0, out, 1, 32);
        System.arraycopy(fixed(key.getW().getAffineY()), 0, out, 33, 32);
        return out;
    }

    /** The private key of a 32-byte scalar. */
    static ECPrivateKey privateKey(byte[] scalar) {
        if (scalar == null || scalar.length != 32) {
            throw new IllegalArgumentException("a P-256 private key has 32 bytes");
        }
        BigInteger d = new BigInteger(1, scalar);
        if (d.signum() == 0 || d.compareTo(PARAMS.getOrder()) >= 0) {
            throw new IllegalArgumentException("not a P-256 private key");
        }
        try {
            return (ECPrivateKey) KeyFactory.getInstance("EC").generatePrivate(new ECPrivateKeySpec(d, PARAMS));
        } catch (GeneralSecurityException e) {
            throw new IllegalArgumentException("not a P-256 private key", e);
        }
    }

    /** The 32-byte scalar of a private key. */
    static byte[] scalar(ECPrivateKey key) {
        return fixed(key.getS());
    }

    static KeyPair generate() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
            generator.initialize(new ECGenParameterSpec("secp256r1"));
            return generator.generateKeyPair();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("P-256 is not available", e);
        }
    }

    /** ECDH: the 32-byte x coordinate of the shared point. */
    static byte[] sharedSecret(ECPrivateKey mine, ECPublicKey theirs) {
        try {
            KeyAgreement agreement = KeyAgreement.getInstance("ECDH");
            agreement.init(mine);
            agreement.doPhase(theirs, true);
            return agreement.generateSecret();
        } catch (GeneralSecurityException e) {
            throw new IllegalArgumentException("ECDH failed", e);
        }
    }

    /**
     * Base64url as the Push API gives it (no padding); standard base64 and padding are accepted too, since some
     * browsers sent those. IllegalArgumentException when it is not base64.
     */
    static byte[] decode(String text) {
        if (text == null) {
            throw new IllegalArgumentException("missing");
        }
        String clean = text.strip().replace('+', '-').replace('/', '_');
        int end = clean.length();
        while (end > 0 && clean.charAt(end - 1) == '=') {
            end--;
        }
        return Base64.getUrlDecoder().decode(clean.substring(0, end));
    }

    static String encode(byte[] bytes) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /** An unsigned value on exactly 32 bytes (left-padded with zeros). */
    static byte[] fixed(BigInteger value) {
        byte[] bytes = value.toByteArray();
        if (bytes.length == 32) {
            return bytes;
        }
        byte[] out = new byte[32];
        if (bytes.length > 32) {
            // A leading sign byte (0x00) when the high bit is set
            System.arraycopy(bytes, bytes.length - 32, out, 0, 32);
        } else {
            System.arraycopy(bytes, 0, out, 32 - bytes.length, bytes.length);
        }
        return out;
    }

    private static ECParameterSpec params() {
        try {
            AlgorithmParameters parameters = AlgorithmParameters.getInstance("EC");
            parameters.init(new ECGenParameterSpec("secp256r1"));
            return parameters.getParameterSpec(ECParameterSpec.class);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("P-256 is not available", e);
        }
    }
}
