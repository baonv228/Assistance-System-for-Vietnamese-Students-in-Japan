package com.sep490.vsa.common.web;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Scripts, styles and pages in static/ have no version in their name: the browser keeps them and asks each time whether
 * they changed (304 when not), instead of downloading every file again on every page as Spring Security's "no-store"
 * made it do. "Changed" is decided by an ETag of the file's bytes, never by its date: the server's jar dates every file
 * 1980-02-01 on every deploy, so a date check kept old pages in browsers and Cloudflare after a deploy. API answers,
 * which hold personal data, are still never stored.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.datasource.url=jdbc:mysql://localhost:3306/asvsj_test?createDatabaseIfNotExist=true&useUnicode=true&characterEncoding=utf8&connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true",
        "app.media.local-dir=target/test-uploads",
        "app.moderation.retry-initial-delay-ms=3600000",
        "app.places.osm.enabled=false"
})
@AutoConfigureMockMvc
@ActiveProfiles("local")
class StaticCacheFlowTest {
    private static final HttpClient HTTP = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();

    @Autowired
    MockMvc mvc;

    @LocalServerPort
    int port;

    @Test
    void staticFilesAreCheckedAgainByContentInsteadOfDownloadedAgain() throws Exception {
        var first = mvc.perform(get("/assets/css/site.css"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-cache"))
                .andExpect(header().exists("ETag"))
                .andExpect(header().doesNotExist("Last-Modified"))
                .andReturn().getResponse();
        String etag = first.getHeader("ETag");
        assertThat(etag).matches("W/\"[0-9a-f]{32}\"");
        mvc.perform(get("/assets/css/site.css").header("If-None-Match", etag))
                .andExpect(status().isNotModified());
        mvc.perform(get("/assets/css/site.css").header("If-None-Match", etag.substring(2)))
                .andExpect(status().isNotModified());
        mvc.perform(get("/assets/css/site.css").header("If-None-Match", "\"an-older-version\""))
                .andExpect(status().isOk());

        String headerEtag = mvc.perform(get("/assets/js/components/site-header.js"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-cache"))
                .andReturn().getResponse().getHeader("ETag");
        assertThat(headerEtag).isNotBlank().isNotEqualTo(etag);
    }

    @Test
    void aCopyKeptByItsDateIsSentAgain() throws Exception {
        // what a browser or Cloudflare holding the file of an earlier deploy asks: its date is later than the jar's
        String oldCopy = "Mon, 09 Nov 2026 16:41:22 GMT";
        mvc.perform(get("/assets/css/site.css").header("If-Modified-Since", oldCopy))
                .andExpect(status().isOk());
        HttpResponse<byte[]> page = send("/login", "If-Modified-Since", oldCopy);
        assertThat(page.statusCode()).isEqualTo(200);
        assertThat(new String(page.body())).contains("auth-slides");
    }

    // over real HTTP: MockMvc does not carry out the forward of a clean page address to its file
    @Test
    void cleanPageAddressesCarryTheEtagOfTheirFile() throws Exception {
        HttpResponse<byte[]> page = send("/login");
        assertThat(page.statusCode()).isEqualTo(200);
        assertThat(page.headers().firstValue("Last-Modified")).isEmpty();
        String etag = page.headers().firstValue("ETag").orElseThrow();
        assertThat(send("/login", "If-None-Match", etag).statusCode()).isEqualTo(304);
        assertThat(send("/blog/1").headers().firstValue("ETag")).isPresent().isNotEqualTo(Optional.of(etag));
    }

    @Test
    void gzippedFilesStillAnswerNotModifiedOverRealHttp() throws Exception {
        HttpResponse<byte[]> first = send("/assets/css/site.css", "Accept-Encoding", "gzip");
        assertThat(first.statusCode()).isEqualTo(200);
        assertThat(first.headers().firstValue("Content-Encoding")).hasValue("gzip");
        assertThat(first.headers().firstValue("Last-Modified")).isEmpty();
        String etag = first.headers().firstValue("ETag").orElseThrow();

        HttpResponse<byte[]> again = send("/assets/css/site.css", "Accept-Encoding", "gzip", "If-None-Match", etag);
        assertThat(again.statusCode()).isEqualTo(304);
        assertThat(again.body()).isEmpty();
    }

    private HttpResponse<byte[]> send(String path, String... headers) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path));
        for (int i = 0; i < headers.length; i += 2) {
            request.header(headers[i], headers[i + 1]);
        }
        return HTTP.send(request.build(), HttpResponse.BodyHandlers.ofByteArray());
    }

    @Test
    void apiAnswersAreStillNeverStored() throws Exception {
        mvc.perform(get("/api/auth/csrf"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", containsString("no-store")));
    }
}
