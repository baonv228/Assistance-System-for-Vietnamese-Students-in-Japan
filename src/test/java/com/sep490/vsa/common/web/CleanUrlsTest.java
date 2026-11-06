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
    void oldFaqAddressesGoToTheFaqTabOfHoiDap() {
        assertThat(CleanUrls.cleanAddress("/faq", null)).isEqualTo("/qa?tab=faq");
        assertThat(CleanUrls.cleanAddress("/faq/", "")).isEqualTo("/qa?tab=faq");
        assertThat(CleanUrls.cleanAddress("/pages/faq.html", "id=12")).isEqualTo("/qa?tab=faq&id=12");
        // The old FAQ page counted pages from 0, Hỏi-Đáp counts from 1
        assertThat(CleanUrls.cleanAddress("/faq", "q=sim&page=1&topic=LIVING")).isEqualTo("/qa?tab=faq&q=sim&page=2&topic=LIVING");
        assertThat(CleanUrls.cleanAddress("/faq", "page=0")).isEqualTo("/qa?tab=faq&page=1");
        assertThat(CleanUrls.cleanAddress("/faq", "subpage=3&page=x")).isEqualTo("/qa?tab=faq&subpage=3&page=x");
        // Other addresses holding "faq" are not the old page
        for (String path : new String[]{"/faqs", "/faq/12", "/api/faqs", "/api/faqs/related", "/moderator/faqs", "/qa"}) {
            assertThat(CleanUrls.cleanAddress(path, "tab=faq")).as(path).isNull();
        }
    }

    @Test
    void theFaqPageIsATabOfHoiDapNotARouteOfItsOwn() throws Exception {
        try (var context = new AnnotationConfigWebApplicationContext()) {
            context.setServletContext(new MockServletContext());
            context.register(TestMvcConfig.class, CleanUrls.Routes.class);
            context.refresh();
            var mvc = MockMvcBuilders.webAppContextSetup(context).build();
            assertThat(CleanUrls.PAGES).doesNotContainKey("/faq").containsEntry("/qa", "/pages/qa.html");
            mvc.perform(get("/qa").queryParam("tab", "faq")).andExpect(status().isOk()).andExpect(forwardedUrl("/pages/qa.html"));
            mvc.perform(get("/faq")).andExpect(status().isNotFound());
        }
    }

    @Test
    void eventRedirectsDoNotChangeApiPaths() {
        assertThat(CleanUrls.cleanAddress("/api/events/", null)).isNull();
        assertThat(CleanUrls.cleanAddress("/api/moderator/events/", null)).isNull();
    }
}
