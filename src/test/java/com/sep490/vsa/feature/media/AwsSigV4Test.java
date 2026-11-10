package com.sep490.vsa.feature.media;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** The examples of the AWS S3 documentation ("Signature Calculations for the Authorization Header"). */
class AwsSigV4Test {

    private final AwsSigV4 signer = new AwsSigV4("AKIAIOSFODNN7EXAMPLE",
            "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY", "us-east-1", "s3");

    @Test
    void getObjectExample() {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Host", "examplebucket.s3.amazonaws.com");
        headers.put("Range", "bytes=0-9");
        headers.put("x-amz-content-sha256", AwsSigV4.EMPTY_SHA256);
        headers.put("x-amz-date", "20130524T000000Z");

        String auth = signer.authorization("GET", "/test.txt", "", headers, AwsSigV4.EMPTY_SHA256, "20130524T000000Z");

        assertThat(auth).isEqualTo("AWS4-HMAC-SHA256 Credential=AKIAIOSFODNN7EXAMPLE/20130524/us-east-1/s3/aws4_request,"
                + "SignedHeaders=host;range;x-amz-content-sha256;x-amz-date,"
                + "Signature=f0e8bdb87c964420e857bd35b5d6ed310bd44f0170aba48dd91039c6036bdb41");
    }

    @Test
    void putObjectExample() {
        String payload = AwsSigV4.sha256Hex("Welcome to Amazon S3.".getBytes(StandardCharsets.UTF_8));
        assertThat(payload).isEqualTo("44ce7dd67c959e0d3524ffac1771dfbba87d2b6b4b4e99e42034a8b803f8b072");
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Host", "examplebucket.s3.amazonaws.com");
        headers.put("Date", "Fri, 24 May 2013 00:00:00 GMT");
        headers.put("x-amz-content-sha256", payload);
        headers.put("x-amz-date", "20130524T000000Z");
        headers.put("x-amz-storage-class", "REDUCED_REDUNDANCY");

        String auth = signer.authorization("PUT", AwsSigV4.encodePath("/test$file.text"), "", headers, payload,
                "20130524T000000Z");

        assertThat(auth).endsWith("SignedHeaders=date;host;x-amz-content-sha256;x-amz-date;x-amz-storage-class,"
                + "Signature=98ad721746da40c64f1a55b78f14c238d841ea1380cd77a1b5971af0ece108bd");
    }

    @Test
    void encodesPathLikeS3() {
        assertThat(AwsSigV4.encodePath("/bucket/blog/2026/11/a-b_c.~x.jpg")).isEqualTo("/bucket/blog/2026/11/a-b_c.~x.jpg");
        assertThat(AwsSigV4.encodePath("/a b+é$")).isEqualTo("/a%20b%2B%C3%A9%24");
    }
}
