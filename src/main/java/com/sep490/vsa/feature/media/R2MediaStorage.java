package com.sep490.vsa.feature.media;

import com.sep490.vsa.common.http.WindowsTrust;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.AbstractResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.OptionalLong;

/**
 * Keeps media files in a Cloudflare R2 bucket ({@code app.media.storage=r2}) through R2's S3 API, signed with
 * {@link AwsSigV4}. The bucket stays <b>private</b> (no public r2.dev address, no custom domain): files still reach
 * people only through {@link MediaController} at {@code /media/<key>}, one checked file at a time by
 * {@link MediaAccess}, exactly as with {@link LocalMediaStorage}; chat files and tag proofs must never be public.
 * <p>
 * {@link #resource} asks R2 for the size and date only (HEAD); the bytes are fetched when Spring writes the answer, and
 * a Range request (video seeking) fetches only from the asked position ({@code Range: bytes=<start>-}), so a large video
 * is never read whole into memory. Values are read from {@code app.media.r2.*} (env {@code R2_ACCOUNT_ID},
 * {@code R2_BUCKET}, {@code R2_ACCESS_KEY_ID}, {@code R2_SECRET_ACCESS_KEY}); a missing one stops the start.
 */
@Component
@ConditionalOnProperty(name = "app.media.storage", havingValue = "r2")
public class R2MediaStorage implements MediaStorage {

    private static final Logger log = LoggerFactory.getLogger(R2MediaStorage.class);
    private static final DateTimeFormatter AMZ_DATE = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'");
    private static final Duration SMALL_WAIT = Duration.ofSeconds(15);
    private static final Duration TRANSFER_WAIT = Duration.ofMinutes(2);
    /** Tries per request (the first one included) and the pause before the second (twice that before the third). */
    private static final int ATTEMPTS = 3;
    private static final long RETRY_PAUSE_MS = 300;

    private final URI endpoint;
    private final String bucket;
    private final AwsSigV4 signer;
    private final HttpClient client;
    private final Clock clock;

    @Autowired
    public R2MediaStorage(@Value("${app.media.r2.account-id:}") String accountId,
                          @Value("${app.media.r2.endpoint:}") String endpoint,
                          @Value("${app.media.r2.bucket:}") String bucket,
                          @Value("${app.media.r2.access-key-id:}") String accessKeyId,
                          @Value("${app.media.r2.secret-access-key:}") String secretAccessKey) {
        this(endpointOf(accountId, endpoint), bucket, accessKeyId, secretAccessKey, Clock.systemUTC());
    }

    R2MediaStorage(URI endpoint, String bucket, String accessKeyId, String secretAccessKey, Clock clock) {
        StringBuilder missing = new StringBuilder();
        if (isBlank(bucket)) missing.append(" app.media.r2.bucket (R2_BUCKET)");
        if (isBlank(accessKeyId)) missing.append(" app.media.r2.access-key-id (R2_ACCESS_KEY_ID)");
        if (isBlank(secretAccessKey)) missing.append(" app.media.r2.secret-access-key (R2_SECRET_ACCESS_KEY)");
        if (!missing.isEmpty()) {
            throw new IllegalStateException("app.media.storage=r2 needs:" + missing);
        }
        this.endpoint = endpoint;
        this.bucket = bucket.trim();
        this.signer = new AwsSigV4(accessKeyId.trim(), secretAccessKey.trim(), "auto", "s3");
        this.client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .sslContext(WindowsTrust.sslContext())
                .build();
        this.clock = clock;
        log.info("Media files are kept in the Cloudflare R2 bucket '{}' ({})", this.bucket, endpoint.getHost());
    }

    private static URI endpointOf(String accountId, String endpoint) {
        if (!isBlank(endpoint)) {
            return URI.create(endpoint.trim().replaceAll("/+$", ""));
        }
        if (isBlank(accountId)) {
            throw new IllegalStateException("app.media.storage=r2 needs: app.media.r2.account-id (R2_ACCOUNT_ID)");
        }
        return URI.create("https://" + accountId.trim() + ".r2.cloudflarestorage.com");
    }

    @Override
    public void put(String key, byte[] bytes) {
        HttpResponse<String> answer = send(request("PUT", key, bytes, null, TRANSFER_WAIT),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8), key);
        if (answer.statusCode() != 200) {
            throw failure("save", key, answer.statusCode(), answer.body());
        }
    }

    @Override
    public byte[] get(String key) {
        HttpResponse<byte[]> answer = send(request("GET", key, null, null, TRANSFER_WAIT),
                HttpResponse.BodyHandlers.ofByteArray(), key);
        if (answer.statusCode() != 200) {
            throw failure("read", key, answer.statusCode(), new String(answer.body(), StandardCharsets.UTF_8));
        }
        return answer.body();
    }

    /** The file's size and date (HEAD); null when R2 does not have it or cannot be reached (the page gets a 404). */
    @Override
    public Resource resource(String key) {
        HttpResponse<Void> answer;
        try {
            answer = send(request("HEAD", key, null, null, SMALL_WAIT), HttpResponse.BodyHandlers.discarding(), key);
        } catch (UncheckedIOException e) {
            log.warn("R2 did not answer for {}: {}", key, e.getMessage());
            return null;
        }
        if (answer.statusCode() == 404) {
            return null;
        }
        OptionalLong length = answer.headers().firstValueAsLong("Content-Length");
        if (answer.statusCode() != 200 || length.isEmpty()) {
            log.warn("R2 answered {} to HEAD {}", answer.statusCode(), key);
            return null;
        }
        long modified = answer.headers().firstValue("Last-Modified").map(R2MediaStorage::httpDate).orElse(0L);
        return new R2Resource(key, length.getAsLong(), modified);
    }

    @Override
    public void delete(String key) {
        HttpResponse<String> answer = send(request("DELETE", key, null, null, SMALL_WAIT),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8), key);
        if (answer.statusCode() != 204 && answer.statusCode() != 200 && answer.statusCode() != 404) {
            throw failure("delete", key, answer.statusCode(), answer.body());
        }
    }

    /** A signed request for one object; {@code range} ("bytes=100-") is sent unsigned, as S3 allows. */
    private HttpRequest request(String method, String key, byte[] body, String range, Duration wait) {
        if (!LocalMediaStorage.isSafeKey(key)) {
            throw new IllegalArgumentException("Invalid media key: " + key);
        }
        String path = AwsSigV4.encodePath("/" + bucket + "/" + key);
        URI uri = URI.create(endpoint + path);
        String payloadHash = body == null ? AwsSigV4.EMPTY_SHA256 : AwsSigV4.sha256Hex(body);
        String amzDate = ZonedDateTime.now(clock).withZoneSameInstant(ZoneOffset.UTC).format(AMZ_DATE);
        Map<String, String> signed = new LinkedHashMap<>();
        signed.put("host", hostHeader(uri));
        signed.put("x-amz-content-sha256", payloadHash);
        signed.put("x-amz-date", amzDate);
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri).timeout(wait)
                .header("x-amz-content-sha256", payloadHash)
                .header("x-amz-date", amzDate)
                .header("Authorization", signer.authorization(method, path, "", signed, payloadHash, amzDate));
        if (range != null) {
            builder.header("Range", range);
        }
        if (body != null) {
            builder.header("Content-Type", "application/octet-stream");
        }
        return builder.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofByteArray(body)).build();
    }

    /** The Host header the HTTP client sends: the port only when it is not the scheme's default. */
    private static String hostHeader(URI uri) {
        int port = uri.getPort();
        boolean defaultPort = port == -1 || ("https".equals(uri.getScheme()) && port == 443)
                || ("http".equals(uri.getScheme()) && port == 80);
        return defaultPort ? uri.getHost() : uri.getHost() + ":" + port;
    }

    /**
     * Sends the request, trying up to {@link #ATTEMPTS} times when the connection fails or R2 answers 500/502/503/504:
     * a dropped connection (a stale pooled one, a network blip) used to become a 404 and a broken picture on the page
     * until it was reloaded. Every request here is repeatable (GET, HEAD, PUT of the same bytes, DELETE) and signed with
     * a date valid for 15 minutes, so the same request is sent again.
     */
    private <T> HttpResponse<T> send(HttpRequest request, HttpResponse.BodyHandler<T> handler, String key) {
        IOException last = null;
        for (int attempt = 1; attempt <= ATTEMPTS; attempt++) {
            try {
                HttpResponse<T> answer = client.send(request, handler);
                if (!isServerError(answer.statusCode()) || attempt == ATTEMPTS) {
                    return answer;
                }
                if (answer.body() instanceof InputStream stream) {
                    stream.close();
                }
                last = new IOException("HTTP " + answer.statusCode());
            } catch (IOException e) {
                last = e;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new UncheckedIOException("Interrupted while calling Cloudflare R2 for media file " + key,
                        new IOException(e));
            }
            if (attempt < ATTEMPTS) {
                log.warn("R2 {} {} failed ({}), trying again ({}/{})", request.method(), key, last, attempt + 1, ATTEMPTS);
                try {
                    Thread.sleep(RETRY_PAUSE_MS * attempt);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new UncheckedIOException("Interrupted while calling Cloudflare R2 for media file " + key,
                            new IOException(e));
                }
            }
        }
        throw new UncheckedIOException("Could not reach Cloudflare R2 for media file " + key + ": " + last, last);
    }

    private static boolean isServerError(int status) {
        return status == 500 || status == 502 || status == 503 || status == 504;
    }

    private static UncheckedIOException failure(String action, String key, int status, String body) {
        String detail = body == null ? "" : body.replaceAll("\\s+", " ");
        if (detail.length() > 300) {
            detail = detail.substring(0, 300) + "…";
        }
        return new UncheckedIOException("Could not " + action + " media file " + key + " in R2 (HTTP " + status + ") "
                + detail, new IOException("HTTP " + status));
    }

    private static long httpDate(String value) {
        try {
            return ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli();
        } catch (DateTimeParseException e) {
            return 0L;
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    /**
     * One object of the bucket for {@link MediaController}. Each {@link #getInputStream} is a new download that starts
     * only at the first read, so Spring's {@code skip(start)} for a Range request moves the start instead of
     * downloading and dropping the bytes before it.
     */
    final class R2Resource extends AbstractResource {

        private final String key;
        private final long length;
        private final long modified;

        R2Resource(String key, long length, long modified) {
            this.key = key;
            this.length = length;
            this.modified = modified;
        }

        @Override
        public boolean exists() {
            return true;
        }

        @Override
        public long contentLength() {
            return length;
        }

        @Override
        public long lastModified() {
            return modified;
        }

        @Override
        public String getFilename() {
            return key.substring(key.lastIndexOf('/') + 1);
        }

        @Override
        public String getDescription() {
            return "Cloudflare R2 object [" + key + "]";
        }

        @Override
        public InputStream getInputStream() {
            return new RangedStream(key, length);
        }
    }

    /** Downloads from {@code position} on at the first read; skips before that only move the position. */
    private final class RangedStream extends InputStream {

        private final String key;
        private final long length;
        private long position;
        private InputStream body;

        RangedStream(String key, long length) {
            this.key = key;
            this.length = length;
        }

        @Override
        public long skip(long n) throws IOException {
            if (n <= 0) {
                return 0;
            }
            if (body == null) {
                long moved = Math.min(n, Math.max(0, length - position));
                position += moved;
                return moved;
            }
            return body.skip(n);
        }

        @Override
        public int read() throws IOException {
            return open().read();
        }

        @Override
        public int read(byte[] buffer, int offset, int count) throws IOException {
            return open().read(buffer, offset, count);
        }

        @Override
        public void close() throws IOException {
            if (body != null) {
                body.close();
            }
        }

        private InputStream open() throws IOException {
            if (body != null) {
                return body;
            }
            if (position >= length) {
                body = InputStream.nullInputStream();
                return body;
            }
            String range = position > 0 ? "bytes=" + position + "-" : null;
            HttpResponse<InputStream> answer;
            try {
                answer = send(request("GET", key, null, range, TRANSFER_WAIT),
                        HttpResponse.BodyHandlers.ofInputStream(), key);
            } catch (UncheckedIOException e) {
                throw e.getCause();
            }
            int status = answer.statusCode();
            if (status == 206 || (status == 200 && position == 0)) {
                body = answer.body();
            } else if (status == 200) {                     // the Range was not honoured: drop the bytes before it
                body = answer.body();
                body.skipNBytes(position);
            } else {
                answer.body().close();
                throw new IOException("R2 answered HTTP " + status + " to GET " + key);
            }
            return body;
        }
    }
}
