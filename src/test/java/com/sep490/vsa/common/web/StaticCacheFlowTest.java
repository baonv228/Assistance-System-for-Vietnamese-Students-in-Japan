package com.sep490.vsa.common.web;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Scripts and styles in static/ have no version in their name: the browser keeps them and asks each time whether they
 * changed (304 when not), instead of downloading every file again on every page as Spring Security's "no-store" made it
 * do. API answers, which hold personal data, are still never stored.
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:mysql://localhost:3306/asvsj_test?createDatabaseIfNotExist=true&useUnicode=true&characterEncoding=utf8&connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true",
        "app.media.local-dir=target/test-uploads",
        "app.moderation.retry-initial-delay-ms=3600000",
        "app.places.osm.enabled=false"
})
@AutoConfigureMockMvc
@ActiveProfiles("local")
class StaticCacheFlowTest {
    @Autowired
    MockMvc mvc;

    @Test
    void staticFilesAreCheckedAgainInsteadOfDownloadedAgain() throws Exception {
        var first = mvc.perform(get("/assets/css/site.css"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-cache"))
                .andExpect(header().exists("Last-Modified"))
                .andReturn().getResponse();
        mvc.perform(get("/assets/css/site.css").header("If-Modified-Since", first.getHeader("Last-Modified")))
                .andExpect(status().isNotModified());
        mvc.perform(get("/assets/js/components/site-header.js"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-cache"));
    }

    @Test
    void apiAnswersAreStillNeverStored() throws Exception {
        mvc.perform(get("/api/auth/csrf"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", containsString("no-store")));
    }
}
