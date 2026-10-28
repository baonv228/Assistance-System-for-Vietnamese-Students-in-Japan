package com.sep490.vsa.common.web;

import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.mock.web.MockServletContext;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.forwardedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class CleanUrlsTest {
    @Configuration
    @EnableWebMvc
    static class TestMvcConfig {
    }

    @Test
    void eventRoutesForwardToExistingHtmlPages() throws Exception {
        try (var context = new AnnotationConfigWebApplicationContext()) {
            context.setServletContext(new MockServletContext());
            context.register(TestMvcConfig.class, CleanUrls.Routes.class);
            context.refresh();
            var mvc = MockMvcBuilders.webAppContextSetup(context).build();
            String[][] routes = {
                    {"/events", "/pages/events.html"},
                    {"/events/12", "/pages/event-detail.html"},
                    {"/events/12/review", "/pages/event-review.html"},
                    {"/moderator/events", "/pages/moderator/events.html"}
            };
            for (String[] route : routes) {
                assertThat(new ClassPathResource("static" + route[1]).exists()).as(route[1]).isTrue();
                mvc.perform(get(route[0])).andExpect(status().isOk()).andExpect(forwardedUrl(route[1]));
            }
        }
    }

    @Test
    void eventUrlsWithTrailingSlashesKeepTheirQuery() {
        for (String path : new String[]{"/events", "/events/12", "/events/12/review", "/moderator/events"}) {
            assertThat(CleanUrls.cleanAddress(path + "/", "q=Tokyo")).isEqualTo(path + "?q=Tokyo");
            assertThat(CleanUrls.cleanAddress(path, null)).isNull();
        }
    }

    @Test
    void legacyEventPagesRedirectToTheCorrectUserAndModeratorRoutes() {
        assertThat(CleanUrls.cleanAddress("/pages/events.html", "q=Tokyo")).isEqualTo("/events?q=Tokyo");
        assertThat(CleanUrls.cleanAddress("/pages/event-detail.html", "id=12")).isEqualTo("/events/12");
        assertThat(CleanUrls.cleanAddress("/pages/moderator/events.html", null)).isEqualTo("/moderator/events");
        assertThat(CleanUrls.cleanAddress("/pages/event-review.html", "from=list&id=12&edit=true"))
                .isEqualTo("/events/12/review?from=list&edit=true");
        assertThat(CleanUrls.cleanAddress("/pages/event-review.html", null)).isEqualTo("/events");
    }

    @Test
    void eventRedirectsDoNotChangeApiPaths() {
        assertThat(CleanUrls.cleanAddress("/api/events/", null)).isNull();
        assertThat(CleanUrls.cleanAddress("/api/moderator/events/", null)).isNull();
    }
}
