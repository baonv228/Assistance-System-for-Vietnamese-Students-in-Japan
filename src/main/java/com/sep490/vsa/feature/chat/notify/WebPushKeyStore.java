package com.sep490.vsa.feature.chat.notify;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.security.KeyPair;
import java.security.interfaces.ECPrivateKey;
import java.security.interfaces.ECPublicKey;
import java.util.Optional;

/**
 * The server's VAPID key pair ({@code web_push_keys}, one row with id 1). Made the first time it is needed; when two
 * requests make it at the same moment, the row written first wins and both use it (the insert ignores the duplicate
 * and the row is read back). Kept in memory afterwards. Browsers subscribed with this public key, so the row must never
 * change: a new key pair would silently stop every subscription (the push services answer 401/403).
 */
@Component
public class WebPushKeyStore {

    /** @param publicRaw the raw uncompressed point, as the browser and the VAPID header want it */
    public record VapidKeys(ECPublicKey publicKey, ECPrivateKey privateKey, byte[] publicRaw) {

        public String publicKeyBase64Url() {
            return P256.encode(publicRaw);
        }
    }

    private final JdbcTemplate jdbc;
    private volatile VapidKeys cached;

    public WebPushKeyStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public VapidKeys keys() {
        VapidKeys keys = cached;
        if (keys == null) {
            synchronized (this) {
                if (cached == null) {
                    cached = read().orElseGet(this::create);
                }
                keys = cached;
            }
        }
        return keys;
    }

    private VapidKeys create() {
        KeyPair pair = P256.generate();
        jdbc.update("""
                INSERT INTO web_push_keys (id, public_key, private_key) VALUES (1, ?, ?)
                ON DUPLICATE KEY UPDATE id = id""",
                P256.encode(P256.raw((ECPublicKey) pair.getPublic())),
                P256.encode(P256.scalar((ECPrivateKey) pair.getPrivate())));
        return read().orElseThrow(() -> new IllegalStateException("web_push_keys row missing after insert"));
    }

    private Optional<VapidKeys> read() {
        return jdbc.query("SELECT public_key, private_key FROM web_push_keys WHERE id = 1", (rs, i) -> {
            byte[] raw = P256.decode(rs.getString(1));
            return new VapidKeys(P256.publicKey(raw), P256.privateKey(P256.decode(rs.getString(2))), raw);
        }).stream().findFirst();
    }
}
