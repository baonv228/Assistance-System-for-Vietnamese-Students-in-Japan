package com.sep490.vsa.feature.chat.notify;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sep490.vsa.feature.chat.notify.PushSubscriptionRepository.Subscription;
import com.sep490.vsa.feature.chat.notify.WebPushKeyStore.VapidKeys;
import com.sep490.vsa.feature.chat.notify.WebPushSender.Result;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.interfaces.ECPrivateKey;
import java.security.interfaces.ECPublicKey;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The real sender against a local stand-in push service (allowed through {@code app.push.allowed-hosts} as the test
 * value "http://127.0.0.1"; no internet): what it sends, how it reads the answers, and the endpoints it refuses to call.
 * The subscriptions live in memory here; their SQL is covered by NotifyFlowTest.
 */
class WebPushSenderTest {

    private static final String SUBJECT = "mailto:senpai-oi@example.com";
    private static final Instant NOW = Instant.parse("2026-10-28T03:00:00Z");

    private final List<Request> requests = new CopyOnWriteArrayList<>();
    /** Statuses answered in order; when empty, 201. */
    private final Deque<Integer> replies = new ConcurrentLinkedDeque<>();
    private final MemorySubscriptions subscriptions = new MemorySubscriptions();
    private final SecureRandom random = new SecureRandom();
    private HttpServer server;
    private String base;
    private VapidKeys vapid;
    private WebPushSender sender;

    private record Request(String method, String path, String query, Map<String, String> headers, byte[] body) {
    }

    /** push_subscriptions in memory: what the sender did to each row. */
    private static final class MemorySubscriptions extends PushSubscriptionRepository {

        final Map<Long, Integer> failures = new HashMap<>();
        final List<Long> deleted = new ArrayList<>();
        final List<Long> sent = new ArrayList<>();

        MemorySubscriptions() {
            super(null);
        }

        @Override
        public void delete(long id) {
            deleted.add(id);
            failures.remove(id);
        }

        @Override
        public void markSent(long id, Instant now) {
            sent.add(id);
            failures.put(id, 0);
        }

        @Override
        public int recordFailure(long id) {
            return failures.merge(id, 1, Integer::sum);
        }
    }

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::handle);
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort();

        KeyPair pair = P256.generate();
        vapid = new VapidKeys((ECPublicKey) pair.getPublic(), (ECPrivateKey) pair.getPrivate(),
                P256.raw((ECPublicKey) pair.getPublic()));
        WebPushKeyStore keys = mock(WebPushKeyStore.class);
        when(keys.keys()).thenReturn(vapid);
        sender = new WebPushSender(subscriptions, keys,
                new PushEndpointPolicy(PushEndpointPolicy.DEFAULT_HOSTS + ",http://127.0.0.1"), SUBJECT,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private void handle(HttpExchange exchange) throws IOException {
        Map<String, String> headers = new HashMap<>();
        exchange.getRequestHeaders().forEach((name, values) -> headers.put(name.toLowerCase(), values.get(0)));
        requests.add(new Request(exchange.getRequestMethod(), exchange.getRequestURI().getPath(),
                exchange.getRequestURI().getRawQuery(), headers, exchange.getRequestBody().readAllBytes()));
        Integer status = replies.poll();
        exchange.sendResponseHeaders(status == null ? 201 : status, -1);
        exchange.close();
    }

    @Test
    void sendsTheEncryptedPayloadWithVapid() throws Exception {
        Browser browser = new Browser();
        byte[] payload = "{\"title\":\"Senpai ơi\",\"body\":\"Tin nhắn mới từ Lan\",\"url\":\"/messages/7\",\"tag\":\"chat-7\"}"
                .getBytes(StandardCharsets.UTF_8);

        Result result = sender.send(browser.subscription(1, base + "/push/abc?q=1"), payload);

        assertThat(result).isEqualTo(Result.SENT);
        assertThat(subscriptions.sent).containsExactly(1L);
        assertThat(requests).hasSize(1);
        Request request = requests.get(0);
        assertThat(request.method()).isEqualTo("POST");
        assertThat(request.path()).isEqualTo("/push/abc");
        assertThat(request.query()).isEqualTo("q=1");
        assertThat(request.headers()).containsEntry("ttl", "86400")
                .containsEntry("content-encoding", "aes128gcm")
                .containsEntry("urgency", "high")
                .containsEntry("content-type", "application/octet-stream");

        // Authorization: vapid t=<JWT>, k=<our public key>; the JWT verifies with that key
        String authorization = request.headers().get("authorization");
        assertThat(authorization).matches("vapid t=[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+, k=[A-Za-z0-9_-]+");
        String jwt = authorization.substring("vapid t=".length(), authorization.indexOf(", k="));
        String k = authorization.substring(authorization.indexOf(", k=") + 4);
        assertThat(P256.decode(k)).isEqualTo(vapid.publicRaw());
        String[] parts = jwt.split("\\.");
        Signature verifier = Signature.getInstance("SHA256withECDSA");
        verifier.initVerify(PushTestCrypto.publicKey(P256.decode(k)));
        verifier.update((parts[0] + "." + parts[1]).getBytes(StandardCharsets.US_ASCII));
        assertThat(verifier.verify(PushTestCrypto.rawToDer(P256.decode(parts[2])))).isTrue();
        JsonNode claims = new ObjectMapper().readTree(P256.decode(parts[1]));
        assertThat(claims.get("aud").asText()).isEqualTo(base);
        assertThat(claims.get("exp").asLong()).isEqualTo(NOW.getEpochSecond() + 12 * 3600);
        assertThat(claims.get("sub").asText()).isEqualTo(SUBJECT);

        // Only the browser can read the body
        assertThat(PushTestCrypto.decrypt(request.body(), browser.privateKey(), browser.publicRaw(), browser.auth()))
                .isEqualTo(payload);
        assertThat(new String(request.body(), StandardCharsets.ISO_8859_1)).doesNotContain("Lan", "messages");
    }

    @Test
    void aSubscriptionThatIsGoneIsDeleted() {
        Browser browser = new Browser();
        replies.add(410);
        replies.add(404);

        assertThat(sender.send(browser.subscription(1, base + "/push/1"), new byte[]{1})).isEqualTo(Result.GONE);
        assertThat(sender.send(browser.subscription(2, base + "/push/2"), new byte[]{1})).isEqualTo(Result.GONE);

        assertThat(subscriptions.deleted).containsExactly(1L, 2L);
        assertThat(subscriptions.failures).isEmpty();
        assertThat(requests).hasSize(2);
    }

    @Test
    void failuresAreCountedAndTheSubscriptionIsDroppedAfterFive() {
        Subscription subscription = new Browser().subscription(3, base + "/push/3");

        // Two failures, then a success: the count starts again
        replies.addAll(List.of(500, 429, 201));
        assertThat(sender.send(subscription, new byte[]{1})).isEqualTo(Result.FAILED);
        assertThat(sender.send(subscription, new byte[]{1})).isEqualTo(Result.FAILED);
        assertThat(subscriptions.failures).containsEntry(3L, 2);
        assertThat(sender.send(subscription, new byte[]{1})).isEqualTo(Result.SENT);
        assertThat(subscriptions.failures).containsEntry(3L, 0);

        // Five in a row: 5xx, too many requests, another refusal… then dropped
        replies.addAll(List.of(503, 429, 400, 413, 500));
        for (int i = 1; i <= 4; i++) {
            assertThat(sender.send(subscription, new byte[]{1})).isEqualTo(Result.FAILED);
            assertThat(subscriptions.failures).containsEntry(3L, i);
        }
        assertThat(subscriptions.deleted).isEmpty();
        assertThat(sender.send(subscription, new byte[]{1})).isEqualTo(Result.DROPPED);
        assertThat(subscriptions.deleted).containsExactly(3L);
        assertThat(requests).hasSize(8);
    }

    @Test
    void noAnswerCountsAsAFailure() throws IOException {
        int closedPort;
        try (ServerSocket socket = new ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1"))) {
            closedPort = socket.getLocalPort();
        }
        Subscription subscription = new Browser().subscription(4, "http://127.0.0.1:" + closedPort + "/push/4");

        assertThat(sender.send(subscription, new byte[]{1})).isEqualTo(Result.FAILED);
        assertThat(subscriptions.failures).containsEntry(4L, 1);
        assertThat(subscriptions.deleted).isEmpty();
    }

    @Test
    void endpointsThatAreNotKnownPushServicesAreNeverCalled() {
        int port = server.getAddress().getPort();
        List<String> refused = List.of(
                "https://evil.example.com/push",
                "http://localhost:" + port + "/push",                     // only 127.0.0.1 is allowed over http
                "https://127.0.0.1:" + port + "/push",
                "http://169.254.169.254/latest/meta-data",
                "ftp://127.0.0.1/push",
                "https://fcm.googleapis.com:8443/fcm/send/x",
                "https://user@fcm.googleapis.com/fcm/send/x",
                "https://fcm.googleapis.com.evil.example/fcm/send/x",
                "https://push.services.mozilla.com/wpush/v2/x",           // "*." needs a name under it
                "https://notify.windows.com.example/w",
                "/push/relative",
                "https://fcm.googleapis.com/" + "x".repeat(1000));
        long id = 10;
        for (String endpoint : refused) {
            assertThat(sender.send(new Browser().subscription(id, endpoint), new byte[]{1})).as(endpoint)
                    .isEqualTo(Result.REFUSED);
            assertThat(subscriptions.deleted).as(endpoint).contains(id);
            id++;
        }
        assertThat(requests).isEmpty();

        PushEndpointPolicy production = new PushEndpointPolicy(PushEndpointPolicy.DEFAULT_HOSTS);
        assertThat(production.check(base + "/push")).isEmpty();
        for (String endpoint : List.of(
                "https://fcm.googleapis.com/fcm/send/dGVzdA:APA91b",
                "https://updates.push.services.mozilla.com/wpush/v2/gAAAAAB",
                "https://wns2-par02p.notify.windows.com/w/?token=BQYAAAD%2b",
                "https://web.push.apple.com/QGuQyavXutnMH",
                "https://api.push.apple.com/3/device/x",
                "https://FCM.googleapis.com:443/fcm/send/x")) {
            assertThat(production.check(endpoint)).as(endpoint).isPresent();
        }
    }

    @Test
    void storedKeysThatCannotBeUsedDropTheSubscription() {
        Subscription bad = new Subscription(20, 1, base + "/push/20", P256.encode(new byte[65]), P256.encode(new byte[16]));
        assertThat(sender.send(bad, new byte[]{1})).isEqualTo(Result.REFUSED);
        assertThat(subscriptions.deleted).containsExactly(20L);
        assertThat(requests).isEmpty();
    }

    /** A browser's push keys, as PushManager.subscribe makes them. */
    private final class Browser {

        private final KeyPair keys = P256.generate();
        private final byte[] auth = new byte[16];

        Browser() {
            random.nextBytes(auth);
        }

        Subscription subscription(long id, String endpoint) {
            return new Subscription(id, 1, endpoint, P256.encode(publicRaw()), P256.encode(auth));
        }

        ECPrivateKey privateKey() {
            return (ECPrivateKey) keys.getPrivate();
        }

        byte[] publicRaw() {
            return P256.raw((ECPublicKey) keys.getPublic());
        }

        byte[] auth() {
            return auth;
        }
    }
}
