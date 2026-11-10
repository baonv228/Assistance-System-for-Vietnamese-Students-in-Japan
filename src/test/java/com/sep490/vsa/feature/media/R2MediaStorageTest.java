package com.sep490.vsa.feature.media;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.ResourceRegion;
import org.springframework.http.HttpRange;
import org.springframework.http.MediaType;
import org.springframework.http.converter.ResourceHttpMessageConverter;
import org.springframework.http.converter.ResourceRegionHttpMessageConverter;
import org.springframework.mock.http.MockHttpOutputMessage;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** {@link R2MediaStorage} against a small fake of R2's S3 API that checks every signature. No internet. */
class R2MediaStorageTest {

    private static final String KEY_ID = "test-key-id";
    private static final String SECRET = "test-secret";
    private static final String BUCKET = "asvsj-media-test";
    private static final String KEY = "blog/2026/11/0f8fad5b-d9cb-469f-a165-70867728950e.jpg";

    private final Map<String, byte[]> objects = new ConcurrentHashMap<>();
    private final List<String> calls = new CopyOnWriteArrayList<>();
    private volatile int forcedStatus;
    private HttpServer server;
    private R2MediaStorage storage;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", this::handle);
        server.start();
        URI endpoint = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
        storage = new R2MediaStorage(endpoint, BUCKET, KEY_ID, SECRET, Clock.systemUTC());
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    @Test
    void savesReadsAndDeletesUnderTheBucket() {
        byte[] photo = bytes(5000);
        storage.put(KEY, photo);
        assertThat(objects).containsKey("/" + BUCKET + "/" + KEY);
        assertThat(storage.get(KEY)).isEqualTo(photo);

        storage.delete(KEY);
        assertThat(objects).isEmpty();
        storage.delete(KEY);                                    // already gone: no error
        assertThat(calls).containsExactly("PUT", "GET", "DELETE", "DELETE");
    }

    @Test
    void resourceAsksOnlyForSizeAndDate() throws IOException {
        assertThat(storage.resource(KEY)).isNull();
        storage.put(KEY, bytes(1234));
        calls.clear();

        Resource file = storage.resource(KEY);

        assertThat(file).isNotNull();
        assertThat(file.contentLength()).isEqualTo(1234);
        assertThat(file.lastModified()).isEqualTo(Instant.parse("2026-11-10T01:02:03Z").toEpochMilli());
        assertThat(calls).containsExactly("HEAD");
    }

    @Test
    void wholeFileIsStreamedWithoutRange() throws IOException {
        byte[] video = bytes(300_000);
        storage.put(KEY, video);
        calls.clear();
        MockHttpOutputMessage out = new MockHttpOutputMessage();

        new ResourceHttpMessageConverter().write(storage.resource(KEY), MediaType.APPLICATION_OCTET_STREAM, out);

        assertThat(out.getBodyAsBytes()).isEqualTo(video);
        assertThat(calls).containsExactly("HEAD", "GET");
    }

    @Test
    void rangeRequestDownloadsFromItsStartOnly() throws IOException {
        byte[] video = bytes(300_000);
        storage.put(KEY, video);
        calls.clear();
        Resource file = storage.resource(KEY);
        ResourceRegion region = HttpRange.createByteRange(200_000, 200_999).toResourceRegion(file);
        MockHttpOutputMessage out = new MockHttpOutputMessage();

        new ResourceRegionHttpMessageConverter().write(region, MediaType.APPLICATION_OCTET_STREAM, out);

        assertThat(out.getBodyAsBytes()).isEqualTo(Arrays.copyOfRange(video, 200_000, 201_000));
        assertThat(calls).containsExactly("HEAD", "GET bytes=200000-");
    }

    @Test
    void errorsOfR2AreReported() {
        forcedStatus = 403;
        assertThatThrownBy(() -> storage.put(KEY, bytes(10)))
                .isInstanceOf(UncheckedIOException.class).hasMessageContaining("HTTP 403");
        assertThat(storage.resource(KEY)).isNull();             // the page answers 404
    }

    @Test
    void refusesKeysNotMadeByMediaService() {
        assertThatThrownBy(() -> storage.put("../secret.txt", bytes(1))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> storage.get("blog/x.jpg")).isInstanceOf(IllegalArgumentException.class);
        assertThat(calls).isEmpty();
    }

    @Test
    void needsEveryValue() {
        URI endpoint = URI.create("http://127.0.0.1:1");
        assertThatThrownBy(() -> new R2MediaStorage(endpoint, "", KEY_ID, "", Clock.systemUTC()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("R2_BUCKET").hasMessageContaining("R2_SECRET_ACCESS_KEY");
        assertThatThrownBy(() -> new R2MediaStorage("", "", BUCKET, KEY_ID, SECRET))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("R2_ACCOUNT_ID");
    }

    private void handle(HttpExchange exchange) throws IOException {
        String method = exchange.getRequestMethod();
        String path = exchange.getRequestURI().getRawPath();
        byte[] body = exchange.getRequestBody().readAllBytes();
        String range = exchange.getRequestHeaders().getFirst("Range");
        calls.add(range == null ? method : method + " " + range);
        if (!signatureValid(exchange, method, path, body)) {
            reply(exchange, 403, "<Error><Code>SignatureDoesNotMatch</Code></Error>".getBytes(), "HEAD".equals(method));
            return;
        }
        if (forcedStatus != 0) {
            reply(exchange, forcedStatus, "<Error><Code>AccessDenied</Code></Error>".getBytes(), "HEAD".equals(method));
            return;
        }
        byte[] stored = objects.get(path);
        switch (method) {
            case "PUT" -> {
                objects.put(path, body);
                reply(exchange, 200, new byte[0], false);
            }
            case "DELETE" -> {
                objects.remove(path);
                reply(exchange, 204, null, false);
            }
            case "HEAD", "GET" -> {
                if (stored == null) {
                    reply(exchange, 404, "<Error><Code>NoSuchKey</Code></Error>".getBytes(), "HEAD".equals(method));
                    return;
                }
                exchange.getResponseHeaders().add("Last-Modified", DateTimeFormatter.RFC_1123_DATE_TIME
                        .format(ZonedDateTime.of(2026, 11, 10, 1, 2, 3, 0, ZoneOffset.UTC)));
                if ("HEAD".equals(method)) {
                    exchange.getResponseHeaders().add("Content-Length", String.valueOf(stored.length));
                    exchange.sendResponseHeaders(200, -1);
                    exchange.close();
                } else if (range != null) {
                    int start = Integer.parseInt(range.substring("bytes=".length(), range.length() - 1));
                    reply(exchange, 206, Arrays.copyOfRange(stored, start, stored.length), false);
                } else {
                    reply(exchange, 200, stored, false);
                }
            }
            default -> reply(exchange, 405, null, false);
        }
    }

    /** Recomputes the signature as R2 would, over host, x-amz-content-sha256 and x-amz-date. */
    private static boolean signatureValid(HttpExchange exchange, String method, String path, byte[] body) {
        var headers = exchange.getRequestHeaders();
        String payloadHash = headers.getFirst("x-amz-content-sha256");
        String amzDate = headers.getFirst("x-amz-date");
        String auth = headers.getFirst("Authorization");
        if (payloadHash == null || amzDate == null || auth == null
                || !payloadHash.equals(AwsSigV4.sha256Hex(body))) {
            return false;
        }
        Map<String, String> signed = new LinkedHashMap<>();
        signed.put("host", headers.getFirst("Host"));
        signed.put("x-amz-content-sha256", payloadHash);
        signed.put("x-amz-date", amzDate);
        String expected = new AwsSigV4(KEY_ID, SECRET, "auto", "s3")
                .authorization(method, path, "", signed, payloadHash, amzDate);
        return auth.equals(expected);
    }

    private static void reply(HttpExchange exchange, int status, byte[] body, boolean head) throws IOException {
        if (body == null || head) {
            exchange.sendResponseHeaders(status, -1);
        } else {
            exchange.sendResponseHeaders(status, body.length == 0 ? -1 : body.length);
            exchange.getResponseBody().write(body);
        }
        exchange.close();
    }

    private static byte[] bytes(int size) {
        byte[] bytes = new byte[size];
        new Random(size).nextBytes(bytes);
        return bytes;
    }
}
