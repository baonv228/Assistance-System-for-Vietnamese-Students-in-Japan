package com.sep490.vsa.feature.media;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * AWS Signature Version 4 for the S3 API, which Cloudflare R2 speaks (region "auto", service "s3"). Written with the
 * JDK only, like the Web Push signer, so no AWS SDK is needed. Checked against the examples of the AWS S3
 * documentation in {@code AwsSigV4Test}.
 */
final class AwsSigV4 {

    static final String ALGORITHM = "AWS4-HMAC-SHA256";
    /** SHA-256 of an empty body (GET, HEAD, DELETE). */
    static final String EMPTY_SHA256 = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";

    private static final HexFormat HEX = HexFormat.of();

    private final String accessKeyId;
    private final String secretAccessKey;
    private final String region;
    private final String service;

    AwsSigV4(String accessKeyId, String secretAccessKey, String region, String service) {
        this.accessKeyId = accessKeyId;
        this.secretAccessKey = secretAccessKey;
        this.region = region;
        this.service = service;
    }

    /**
     * The {@code Authorization} header value for one request.
     *
     * @param canonicalUri   the path, already URI-encoded ({@link #encodePath})
     * @param canonicalQuery the query string in canonical form ("" when there is none)
     * @param headers        every header to sign; must hold {@code host}, {@code x-amz-date} and
     *                       {@code x-amz-content-sha256}
     * @param amzDate        the same value as the {@code x-amz-date} header, e.g. 20130524T000000Z
     */
    String authorization(String method, String canonicalUri, String canonicalQuery, Map<String, String> headers,
                         String payloadHash, String amzDate) {
        SortedMap<String, String> signed = new TreeMap<>();
        headers.forEach((name, value) -> signed.put(name.toLowerCase(Locale.ROOT), value.trim().replaceAll(" +", " ")));
        String signedHeaders = String.join(";", signed.keySet());
        String canonicalHeaders = signed.entrySet().stream()
                .map(e -> e.getKey() + ":" + e.getValue() + "\n")
                .collect(Collectors.joining());
        String canonicalRequest = method + "\n" + canonicalUri + "\n" + canonicalQuery + "\n" + canonicalHeaders + "\n"
                + signedHeaders + "\n" + payloadHash;
        String date = amzDate.substring(0, 8);
        String scope = date + "/" + region + "/" + service + "/aws4_request";
        String stringToSign = ALGORITHM + "\n" + amzDate + "\n" + scope + "\n"
                + sha256Hex(canonicalRequest.getBytes(StandardCharsets.UTF_8));
        byte[] key = hmac(("AWS4" + secretAccessKey).getBytes(StandardCharsets.UTF_8), date);
        key = hmac(key, region);
        key = hmac(key, service);
        key = hmac(key, "aws4_request");
        String signature = HEX.formatHex(hmac(key, stringToSign));
        return ALGORITHM + " Credential=" + accessKeyId + "/" + scope + ",SignedHeaders=" + signedHeaders
                + ",Signature=" + signature;
    }

    static String sha256Hex(byte[] bytes) {
        try {
            return HEX.formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    /** S3's URI encoding of a path: every byte but A–Z a–z 0–9 - . _ ~ and "/" as %XY (upper-case hex). */
    static String encodePath(String path) {
        StringBuilder out = new StringBuilder();
        for (byte b : path.getBytes(StandardCharsets.UTF_8)) {
            char c = (char) (b & 0xff);
            if ((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')
                    || c == '-' || c == '.' || c == '_' || c == '~' || c == '/') {
                out.append(c);
            } else {
                out.append('%').append(HEX.withUpperCase().toHexDigits(b));
            }
        }
        return out.toString();
    }

    private static byte[] hmac(byte[] key, String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }
}
