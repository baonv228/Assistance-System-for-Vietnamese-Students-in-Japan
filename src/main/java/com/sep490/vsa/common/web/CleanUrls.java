package com.sep490.vsa.common.web;

import jakarta.servlet.DispatcherType;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Page addresses without ".html": the browser shows /blog/12 or /qa/ask while the server forwards to the static
 * file in /pages/. Old links (/pages/qa-ask.html, /pages/blog-detail.html?id=12, …) answer 301 to the clean address,
 * so bookmarks and shared links keep working. Links in the pages must use the clean addresses.
 */
public final class CleanUrls {

    /** Clean address → static file. Detail pages take the id from the path (/blog/{id}, /qa/{id}). */
    static final Map<String, String> PAGES = new LinkedHashMap<>();

    static {
        PAGES.put("/login", "/pages/login.html");
        PAGES.put("/register", "/pages/register.html");
        PAGES.put("/complete-signup", "/pages/complete-signup.html");
        PAGES.put("/terms", "/pages/terms.html");
        PAGES.put("/profile", "/pages/profile.html");
        PAGES.put("/saved", "/pages/saved.html");
        PAGES.put("/notifications", "/pages/notifications.html");
        PAGES.put("/map", "/pages/map.html");
        PAGES.put("/coming-soon", "/pages/coming-soon.html");
        PAGES.put("/guides", "/pages/guides.html");
        PAGES.put("/scholarships", "/pages/scholarships.html");
        PAGES.put("/blog", "/pages/blog.html");
        PAGES.put("/blog/new", "/pages/blog-write.html");
        PAGES.put("/qa", "/pages/qa.html");
        PAGES.put("/qa/ask", "/pages/qa-ask.html");
        PAGES.put("/events", "/pages/events.html");
        PAGES.put("/moderator", "/pages/moderator/index.html");
        PAGES.put("/moderator/events", "/pages/moderator/events.html");
        PAGES.put("/academic", "/pages/academic/index.html");
        PAGES.put("/admin", "/pages/admin/index.html");
        PAGES.put("/admin/users", "/pages/admin/users.html");
        PAGES.put("/admin/system-config", "/pages/admin/system-config.html");
        PAGES.put("/admin/activity-log", "/pages/admin/activity-log.html");
    }

    private static final Map<String, String> DETAIL_PAGES = Map.of(
            "/blog", "/pages/blog-detail.html",
            "/qa", "/pages/qa-detail.html",
            "/events", "/pages/event-detail.html");

    /** Edit forms (/blog/{id}/edit, /qa/{id}/edit) reuse the write pages; the script reads the id from the path. */
    private static final Map<String, String> EDIT_PAGES = Map.of(
            "/blog", "/pages/blog-write.html",
            "/qa", "/pages/qa-ask.html");

    /** "Viết review" for an event (/events/{id}/review): writes the viewer's review of this year, or edits it. */
    static final String EVENT_REVIEW_PAGE = "/pages/event-review.html";

    private static final Pattern ID_PARAM = Pattern.compile("(?:^|&)id=(\\d+)(?:&|$)");

    private CleanUrls() {
    }

    @Configuration
    static class Routes implements WebMvcConfigurer {
        @Override
        public void addViewControllers(ViewControllerRegistry registry) {
            PAGES.forEach((path, file) -> registry.addViewController(path).setViewName("forward:" + file));
            DETAIL_PAGES.forEach((path, file) ->
                    registry.addViewController(path + "/{id:\\d+}").setViewName("forward:" + file));
            EDIT_PAGES.forEach((path, file) ->
                    registry.addViewController(path + "/{id:\\d+}/edit").setViewName("forward:" + file));
            registry.addViewController("/events/{id:\\d+}/review").setViewName("forward:" + EVENT_REVIEW_PAGE);
        }
    }

    /** 301 from an old .html address to its clean one. Only for requests from the browser, not our own forwards. */
    @Component
    static class LegacyRedirectFilter extends OncePerRequestFilter {

        private static final Map<String, String> BY_FILE = new LinkedHashMap<>();

        static {
            PAGES.forEach((path, file) -> BY_FILE.put(file, path));
            BY_FILE.put("/pages/moderator/", "/moderator");
            BY_FILE.put("/pages/academic/", "/academic");
            BY_FILE.put("/pages/admin/", "/admin");
            BY_FILE.put("/index.html", "/");
        }

        @Override
        protected boolean shouldNotFilter(HttpServletRequest request) {
            return request.getDispatcherType() != DispatcherType.REQUEST
                    || !"GET".equals(request.getMethod()) && !"HEAD".equals(request.getMethod());
        }

        @Override
        protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
                throws ServletException, IOException {
            String target = cleanAddress(request.getRequestURI(), request.getQueryString());
            if (target == null) {
                chain.doFilter(request, response);
                return;
            }
            response.setStatus(HttpStatus.MOVED_PERMANENTLY.value());
            response.setHeader("Location", target);
        }
    }

    /** The clean address of an old .html link, with its query string, or null when the path is not an old page. */
    static String cleanAddress(String path, String query) {
        String q = query == null ? "" : query;
        for (Map.Entry<String, String> detail : DETAIL_PAGES.entrySet()) {
            if (detail.getValue().equals(path)) {
                Matcher id = ID_PARAM.matcher(q);
                if (!id.find()) {
                    return withQuery(detail.getKey(), q);
                }
                String rest = (q.substring(0, id.start()) + "&" + q.substring(id.end())).replaceAll("^&+|&+$", "")
                        .replaceAll("&{2,}", "&");
                return withQuery(detail.getKey() + "/" + id.group(1), rest);
            }
        }
        String clean = LegacyRedirectFilter.BY_FILE.get(path);
        return clean == null ? null : withQuery(clean, q);
    }

    private static String withQuery(String path, String query) {
        return query.isEmpty() ? path : path + "?" + query;
    }
}
