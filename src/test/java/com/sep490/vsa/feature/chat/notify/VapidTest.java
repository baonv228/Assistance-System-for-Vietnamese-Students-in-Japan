package com.sep490.vsa.feature.chat.notify;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.Signature;
import java.security.interfaces.ECPrivateKey;
import java.security.interfaces.ECPublicKey;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * VAPID (RFC 8292): the JWT is ES256 with the raw R ‖ S signature JWS wants, which the JDK verifies once turned back
 * into DER; its claims are aud = origin of the endpoint, exp within 12 hours, sub = the contact.
 */
class VapidTest {

    private static final String SUBJECT = "https://github.com/baonv228/Assistance-System-for-Vietnamese-Students-in-Japan";
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void theJwtVerifiesWithThePublicKeyAndCarriesTheClaims() throws Exception {
        KeyPair keys = P256.generate();
        Instant now = Instant.parse("2026-10-28T03:15:30Z");
        String jwt = Vapid.jwt("https://fcm.googleapis.com", now.plus(Vapid.LIFETIME), SUBJECT,
                (ECPrivateKey) keys.getPrivate());

        String[] parts = jwt.split("\\.");
        assertThat(parts).hasSize(3);
        assertThat(jwt).doesNotContain("=", "+", "/");
        JsonNode header = json.readTree(P256.decode(parts[0]));
        assertThat(header.get("typ").asText()).isEqualTo("JWT");
        assertThat(header.get("alg").asText()).isEqualTo("ES256");
        JsonNode claims = json.readTree(P256.decode(parts[1]));
        assertThat(claims.get("aud").asText()).isEqualTo("https://fcm.googleapis.com");
        assertThat(claims.get("sub").asText()).isEqualTo(SUBJECT);
        long exp = claims.get("exp").asLong();
        assertThat(exp).isEqualTo(now.getEpochSecond() + 12 * 3600);
        assertThat(Duration.ofSeconds(exp - now.getEpochSecond())).isLessThanOrEqualTo(Duration.ofHours(12));

        byte[] signature = P256.decode(parts[2]);
        assertThat(signature).hasSize(64);
        byte[] signed = (parts[0] + "." + parts[1]).getBytes(StandardCharsets.US_ASCII);
        assertThat(verifyDer(keys, signed, signature)).isTrue();
        // The JDK's own raw-format verifier agrees
        Signature p1363 = Signature.getInstance("SHA256withECDSAinP1363Format");
        p1363.initVerify(keys.getPublic());
        p1363.update(signed);
        assertThat(p1363.verify(signature)).isTrue();

        // Another key, or a changed claim, does not verify
        assertThat(verifyDer(P256.generate(), signed, signature)).isFalse();
        String forged = P256.encode(("{\"aud\":\"https://fcm.googleapis.com\",\"exp\":" + (exp + 86400)
                + ",\"sub\":\"" + SUBJECT + "\"}").getBytes(StandardCharsets.UTF_8));
        assertThat(verifyDer(keys, (parts[0] + "." + forged).getBytes(StandardCharsets.US_ASCII), signature))
                .isFalse();
    }

    @Test
    void everySignatureConvertsWhateverTheSizeOfRAndS() throws Exception {
        // About one signature in 128 has an r or s shorter than 32 bytes, half of them a high bit that DER pads
        KeyPair keys = P256.generate();
        for (int i = 0; i < 300; i++) {
            String jwt = Vapid.jwt("https://web.push.apple.com", Instant.ofEpochSecond(1_800_000_000L + i), SUBJECT,
                    (ECPrivateKey) keys.getPrivate());
            int dot = jwt.lastIndexOf('.');
            byte[] signature = P256.decode(jwt.substring(dot + 1));
            assertThat(signature).hasSize(64);
            assertThat(verifyDer(keys, jwt.substring(0, dot).getBytes(StandardCharsets.US_ASCII), signature))
                    .as("signature %d", i).isTrue();
        }
    }

    @Test
    void derIsTurnedIntoTwoFixedSizeNumbers() {
        byte[] r = new byte[32];
        Arrays.fill(r, (byte) 0x80);                                      // high bit: DER adds a 0x00
        byte[] s = new byte[32];
        s[31] = 0x05;                                                      // DER keeps one byte
        byte[] raw = new byte[64];
        System.arraycopy(r, 0, raw, 0, 32);
        System.arraycopy(s, 0, raw, 32, 32);
        byte[] der = PushTestCrypto.rawToDer(raw);
        assertThat(der).startsWith(0x30, 2 + 33 + 2 + 1, 0x02, 33, 0x00);
        assertThat(Vapid.derToRaw(der)).isEqualTo(raw);
        assertThatThrownBy(() -> Vapid.derToRaw(new byte[]{0x31, 6, 2, 1, 1, 2, 1, 1}))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Vapid.derToRaw(new byte[]{0x30, 9, 2, 1, 1, 2, 1, 1}))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void theHeaderAndTheAudience() {
        KeyPair keys = P256.generate();
        byte[] publicRaw = P256.raw((ECPublicKey) keys.getPublic());
        String header = Vapid.authorization("a.b.c", publicRaw);
        assertThat(header).isEqualTo("vapid t=a.b.c, k=" + P256.encode(publicRaw));
        assertThat(P256.decode(header.substring(header.indexOf("k=") + 2))).hasSize(65).startsWith(0x04);

        assertThat(Vapid.audience(URI.create("https://fcm.googleapis.com/fcm/send/abc:def")))
                .isEqualTo("https://fcm.googleapis.com");
        assertThat(Vapid.audience(URI.create("https://Updates.Push.Services.Mozilla.com:443/wpush/v2/x")))
                .isEqualTo("https://updates.push.services.mozilla.com");
        assertThat(Vapid.audience(URI.create("https://wns2-par02p.notify.windows.com/w/?token=x%2By")))
                .isEqualTo("https://wns2-par02p.notify.windows.com");
        assertThat(Vapid.audience(URI.create("http://127.0.0.1:8123/push/1")))
                .isEqualTo("http://127.0.0.1:8123");
    }

    private static boolean verifyDer(KeyPair keys, byte[] signed, byte[] raw) throws Exception {
        Signature verifier = Signature.getInstance("SHA256withECDSA");
        verifier.initVerify(keys.getPublic());
        verifier.update(signed);
        return verifier.verify(PushTestCrypto.rawToDer(raw));
    }
}
