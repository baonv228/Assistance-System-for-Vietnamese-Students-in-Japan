package com.sep490.vsa.feature.guide;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.forwardedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The guide and scholarship starter APIs map to tables the schema does not have: until they are rewritten they answer
 * "coming soon" (404) instead of a server error, and their pages show that message as text.
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:mysql://localhost:3306/asvsj_test?createDatabaseIfNotExist=true&useUnicode=true&characterEncoding=utf8&connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true",
        "app.media.local-dir=target/test-uploads",
        "app.moderation.retry-initial-delay-ms=3600000",
        "app.places.osm.enabled=false"
})
@AutoConfigureMockMvc
@ActiveProfiles("local")
class ComingSoonFlowTest {

    @Autowired
    private MockMvc mvc;
    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void theGuideAndScholarshipListsAnswerComingSoonWithoutTouchingTheDatabase() throws Exception {
        // The tables the starter code maps to do not exist: any query would fail with a server error (500)
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM information_schema.tables
                WHERE table_schema = DATABASE() AND table_name IN ('guide', 'scholarship')""", Integer.class)).isZero();
        for (String url : List.of("/api/guides", "/api/guides?category=visa", "/api/scholarships")) {
            mvc.perform(get(url))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.success").value(false))
                    .andExpect(jsonPath("$.message").value("Tính năng này sắp ra mắt."));
        }
        // The pages still open and show the message
        mvc.perform(get("/guides")).andExpect(status().isOk()).andExpect(forwardedUrl("/pages/guides.html"));
        mvc.perform(get("/scholarships")).andExpect(status().isOk()).andExpect(forwardedUrl("/pages/scholarships.html"));
    }

    @Test
    void thePagesPutApiTextInAsTextNeverAsHtml() throws Exception {
        for (String script : List.of("static/assets/js/pages/guides.js", "static/assets/js/pages/scholarships.js")) {
            String code = new ClassPathResource(script).getContentAsString(StandardCharsets.UTF_8);
            assertThat(code).as(script).doesNotContain("innerHTML").doesNotContain("insertAdjacentHTML")
                    .contains("textContent");
        }
    }
}
