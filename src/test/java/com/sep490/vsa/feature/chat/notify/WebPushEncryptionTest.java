package com.sep490.vsa.feature.chat.notify;

import com.sep490.vsa.feature.chat.notify.WebPushEncryption.Derived;
import org.junit.jupiter.api.Test;

import javax.crypto.AEADBadTagException;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.SecureRandom;
import java.security.interfaces.ECPrivateKey;
import java.security.interfaces.ECPublicKey;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * RFC 8291 message encryption: the worked example of Appendix A byte for byte (its keys, auth secret and salt; every
 * value below is base64url as printed there), then round trips with fresh keys decrypted as a browser would.
 */
class WebPushEncryptionTest {

    private static final String PLAINTEXT = "When I grow up, I want to be a watermelon";
    private static final String AS_PUBLIC =
            "BP4z9KsN6nGRTbVYI_c7VJSPQTBtkgcy27mlmlMoZIIgDll6e3vCYLocInmYWAmS6TlzAC8wEqKK6PBru3jl7A8";
    private static final String AS_PRIVATE = "yfWPiYE-n46HLnH0KqZOF1fJJU3MYrct3AELtAQ-oRw";
    private static final String UA_PUBLIC =
            "BCVxsr7N_eNgVRqvHtD0zTZsEc6-VV-JvLexhqUzORcxaOzi6-AYWXvTBHm4bjyPjs7Vd8pZGH6SRpkNtoIAiw4";
    private static final String UA_PRIVATE = "q1dXpw3UpT5VOmu_cf_v6ih07Aems3njxI-JWgLcM94";
    private static final String SALT = "DGv6ra1nlYgDCS1FRnbzlw";
    private static final String AUTH_SECRET = "BTBZMqHH6r4Tts7J_aSIgg";
    private static final String ECDH_SECRET = "kyrL1jIIOHEzg3sM2ZWRHDRB62YACZhhSlknJ672kSs";
    private static final String CEK = "oIhVW04MRdy2XN9CiKLxTg";
    private static final String NONCE = "4h_95klXJ5E_qnoN";
    private static final String BODY =
            "DGv6ra1nlYgDCS1FRnbzlwAAEABBBP4z9KsN6nGRTbVYI_c7VJSPQTBtkgcy27ml"
            + "mlMoZIIgDll6e3vCYLocInmYWAmS6TlzAC8wEqKK6PBru3jl7A_yl95bQpu6cVPT"
            + "pK4Mqgkf1CXztLVBSt2Ks3oZwbuwXPXLWyouBWLVWGNWQexSgSxsj_Qulcy4a-fN";

    private final SecureRandom random = new SecureRandom();

    @Test
    void reproducesTheExampleOfRfc8291AppendixA() throws Exception {
        assertThat(new String(P256.decode("V2hlbiBJIGdyb3cgdXAsIEkgd2FudCB0byBiZSBhIHdhdGVybWVsb24"),
                StandardCharsets.UTF_8)).isEqualTo(PLAINTEXT);
        KeyPair server = new KeyPair(P256.publicKey(P256.decode(AS_PUBLIC)), P256.privateKey(P256.decode(AS_PRIVATE)));

        Derived derived = WebPushEncryption.derive(P256.decode(UA_PUBLIC), P256.decode(AUTH_SECRET),
                (ECPrivateKey) server.getPrivate(), P256.decode(AS_PUBLIC), P256.decode(SALT));
        assertThat(P256.encode(derived.ecdhSecret())).isEqualTo(ECDH_SECRET);
        assertThat(P256.encode(derived.contentKey())).isEqualTo(CEK);
        assertThat(P256.encode(derived.nonce())).isEqualTo(NONCE);

        byte[] body = WebPushEncryption.encrypt(PLAINTEXT.getBytes(StandardCharsets.UTF_8), P256.decode(UA_PUBLIC),
                P256.decode(AUTH_SECRET), server, P256.decode(SALT));
        assertThat(P256.encode(body)).isEqualTo(BODY);
        assertThat(body).hasSize(144);

        // The browser of the example reads it back with its private key
        byte[] read = PushTestCrypto.decrypt(P256.decode(BODY), P256.privateKey(P256.decode(UA_PRIVATE)),
                P256.decode(UA_PUBLIC), P256.decode(AUTH_SECRET));
        assertThat(new String(read, StandardCharsets.UTF_8)).isEqualTo(PLAINTEXT);
    }

    @Test
    void theExampleKeysBelongTogether() {
        // ECDH both ways gives the same secret, so the RFC's public keys are those of its private keys
        byte[] serverSide = P256.sharedSecret(P256.privateKey(P256.decode(AS_PRIVATE)),
                P256.publicKey(P256.decode(UA_PUBLIC)));
        byte[] browserSide = P256.sharedSecret(P256.privateKey(P256.decode(UA_PRIVATE)),
                P256.publicKey(P256.decode(AS_PUBLIC)));
        assertThat(P256.encode(serverSide)).isEqualTo(ECDH_SECRET);
        assertThat(browserSide).isEqualTo(serverSide);
        // And the raw form survives a trip through the JDK's key objects
        assertThat(P256.encode(P256.raw(P256.publicKey(P256.decode(UA_PUBLIC))))).isEqualTo(UA_PUBLIC);
        assertThat(P256.encode(P256.scalar(P256.privateKey(P256.decode(AS_PRIVATE))))).isEqualTo(AS_PRIVATE);
    }

    @Test
    void aBrowserDecryptsWhatTheServerEncrypted() throws Exception {
        KeyPair browser = P256.generate();
        byte[] browserPublic = P256.raw((ECPublicKey) browser.getPublic());
        byte[] auth = randomBytes(16);
        byte[] payload = "{\"title\":\"Senpai ơi\",\"body\":\"Tin nhắn mới từ Lan\",\"url\":\"/messages/7\"}"
                .getBytes(StandardCharsets.UTF_8);

        byte[] first = WebPushEncryption.encrypt(payload, browserPublic, auth);
        byte[] second = WebPushEncryption.encrypt(payload, browserPublic, auth);
        assertThat(PushTestCrypto.decrypt(first, (ECPrivateKey) browser.getPrivate(), browserPublic, auth))
                .isEqualTo(payload);
        assertThat(PushTestCrypto.decrypt(second, (ECPrivateKey) browser.getPrivate(), browserPublic, auth))
                .isEqualTo(payload);
        // A fresh key pair and salt every time: the same payload never gives the same body
        assertThat(Arrays.copyOf(first, 16)).isNotEqualTo(Arrays.copyOf(second, 16));
        assertThat(first).isNotEqualTo(second);
        assertThat(first).hasSize(86 + payload.length + 1 + 16);

        // One changed byte, a wrong auth secret or another browser's key: unreadable
        byte[] changed = first.clone();
        changed[changed.length - 20] ^= 1;
        assertThatThrownBy(() -> PushTestCrypto.decrypt(changed, (ECPrivateKey) browser.getPrivate(), browserPublic,
                auth)).isInstanceOf(AEADBadTagException.class);
        assertThatThrownBy(() -> PushTestCrypto.decrypt(first, (ECPrivateKey) browser.getPrivate(), browserPublic,
                randomBytes(16))).isInstanceOf(AEADBadTagException.class);
        KeyPair other = P256.generate();
        assertThatThrownBy(() -> PushTestCrypto.decrypt(first, (ECPrivateKey) other.getPrivate(), browserPublic,
                auth)).isInstanceOf(AEADBadTagException.class);
    }

    @Test
    void theLargestPayloadFillsOneRecordOf4096Bytes() throws Exception {
        KeyPair browser = P256.generate();
        byte[] browserPublic = P256.raw((ECPublicKey) browser.getPublic());
        byte[] auth = randomBytes(16);
        byte[] largest = randomBytes(WebPushEncryption.MAX_PLAINTEXT);

        byte[] body = WebPushEncryption.encrypt(largest, browserPublic, auth);
        assertThat(body).hasSize(4096);
        assertThat(PushTestCrypto.decrypt(body, (ECPrivateKey) browser.getPrivate(), browserPublic, auth))
                .isEqualTo(largest);
        assertThatThrownBy(() -> WebPushEncryption.encrypt(randomBytes(WebPushEncryption.MAX_PLAINTEXT + 1),
                browserPublic, auth)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void keysThatAreNotPointsOfTheCurveAreRefused() {
        byte[] good = P256.decode(UA_PUBLIC);
        byte[] offCurve = good.clone();
        offCurve[64] ^= 1;                                                 // y changed: not on the curve
        byte[] compressed = Arrays.copyOf(good, 33);
        compressed[0] = 0x02;
        byte[] noPrefix = good.clone();
        noPrefix[0] = 0x05;
        byte[] tooLarge = new byte[65];
        Arrays.fill(tooLarge, (byte) 0xff);
        tooLarge[0] = 0x04;                                                // x, y ≥ p
        for (byte[] bad : new byte[][]{offCurve, compressed, noPrefix, tooLarge, new byte[0]}) {
            assertThatThrownBy(() -> P256.publicKey(bad)).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> WebPushEncryption.encrypt(new byte[]{1}, bad, randomBytes(16)))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> WebPushEncryption.encrypt(new byte[]{1}, good, randomBytes(15)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> P256.decode("not base64!")).isInstanceOf(IllegalArgumentException.class);
        // Standard base64 with padding (some browsers) reads the same as base64url
        assertThat(P256.decode("+/8=")).isEqualTo(P256.decode("-_8"));
    }

    private byte[] randomBytes(int n) {
        byte[] bytes = new byte[n];
        random.nextBytes(bytes);
        return bytes;
    }
}
