package com.sep490.vsa.feature.chat.notify;

import javax.crypto.Cipher;
import javax.crypto.KeyAgreement;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.interfaces.ECPrivateKey;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECPoint;
import java.security.spec.ECPublicKeySpec;
import java.util.Arrays;

/**
 * The browser's side of Web Push for the tests, written apart from the production code (so a mistake there does not
 * cancel out here): decrypting an aes128gcm body (RFC 8291 / RFC 8188), and turning a raw ES256 signature back into
 * the DER the JDK verifies.
 */
final class PushTestCrypto {

    private PushTestCrypto() {
    }

    /** Decrypts a push message body with the browser's keys; throws on a wrong key or a changed byte. */
    static byte[] decrypt(byte[] body, ECPrivateKey browserPrivate, byte[] browserPublicRaw, byte[] authSecret)
            throws GeneralSecurityException {
        ByteBuffer in = ByteBuffer.wrap(body);
        byte[] salt = new byte[16];
        in.get(salt);
        int recordSize = in.getInt();
        byte[] serverPublicRaw = new byte[in.get() & 0xff];
        in.get(serverPublicRaw);
        byte[] ciphertext = new byte[in.remaining()];
        in.get(ciphertext);
        if (ciphertext.length > recordSize) {
            throw new GeneralSecurityException("more than one record");
        }

        KeyAgreement agreement = KeyAgreement.getInstance("ECDH");
        agreement.init(browserPrivate);
        agreement.doPhase(publicKey(serverPublicRaw), true);
        byte[] ecdh = agreement.generateSecret();

        byte[] prkKey = hmac(authSecret, ecdh);
        byte[] ikm = hmac(prkKey, concat("WebPush: info".getBytes(StandardCharsets.US_ASCII), new byte[]{0},
                browserPublicRaw, serverPublicRaw, new byte[]{1}));
        byte[] prk = hmac(salt, ikm);
        byte[] cek = Arrays.copyOf(hmac(prk, concat("Content-Encoding: aes128gcm".getBytes(StandardCharsets.US_ASCII),
                new byte[]{0, 1})), 16);
        byte[] nonce = Arrays.copyOf(hmac(prk, concat("Content-Encoding: nonce".getBytes(StandardCharsets.US_ASCII),
                new byte[]{0, 1})), 12);

        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(cek, "AES"), new GCMParameterSpec(128, nonce));
        byte[] record = cipher.doFinal(ciphertext);
        int end = record.length - 1;
        while (end >= 0 && record[end] == 0) {
            end--;                                                         // padding
        }
        if (end < 0 || record[end] != 0x02) {
            throw new GeneralSecurityException("no last-record delimiter");
        }
        return Arrays.copyOf(record, end);
    }

    /** Raw R ‖ S (64 bytes) → DER SEQUENCE { INTEGER r, INTEGER s }. */
    static byte[] rawToDer(byte[] raw) {
        byte[] r = new BigInteger(1, Arrays.copyOfRange(raw, 0, 32)).toByteArray();
        byte[] s = new BigInteger(1, Arrays.copyOfRange(raw, 32, 64)).toByteArray();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(0x30);
        out.write(2 + r.length + 2 + s.length);
        out.write(0x02);
        out.write(r.length);
        out.writeBytes(r);
        out.write(0x02);
        out.write(s.length);
        out.writeBytes(s);
        return out.toByteArray();
    }

    static ECPublicKey publicKey(byte[] raw) throws GeneralSecurityException {
        ECPoint point = new ECPoint(new BigInteger(1, Arrays.copyOfRange(raw, 1, 33)),
                new BigInteger(1, Arrays.copyOfRange(raw, 33, 65)));
        return (ECPublicKey) KeyFactory.getInstance("EC").generatePublic(new ECPublicKeySpec(point, P256.PARAMS));
    }

    private static byte[] hmac(byte[] key, byte[] data) throws GeneralSecurityException {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key, "HmacSHA256"));
        return mac.doFinal(data);
    }

    private static byte[] concat(byte[]... parts) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] part : parts) {
            out.writeBytes(part);
        }
        return out.toByteArray();
    }
}
