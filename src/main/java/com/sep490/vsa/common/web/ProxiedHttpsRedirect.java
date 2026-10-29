package com.sep490.vsa.common.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Sends a visitor who reached the site over plain http through a proxy (the server behind nginx, a Cloudflare or SSH
 * tunnel: they say so with {@code X-Forwarded-Proto: http}) to the https address of the same page. "Tin nhắn" needs
 * it: browsers give Web Crypto, the microphone and Web Push only to https pages, so an encrypted conversation opened
 * from the http link a tunnel prints first could not be read or answered.
 * <p>
 * Only GET and HEAD are moved (a form sent over http would lose its body); a request that comes straight to the
 * server (no proxy header: {@code localhost}, the LAN address while developing) is left alone. Runs before Spring
 * Security, so a guarded page goes to https first and to the sign-in page after that.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class ProxiedHttpsRedirect extends OncePerRequestFilter {

    /** A host name, an IPv4 address or a bracketed IPv6 address, with an optional port: nothing else is redirected to. */
    private static final Pattern HOST = Pattern.compile("(?:[A-Za-z0-9-]+(?:\\.[A-Za-z0-9-]+)*|\\[[0-9A-Fa-f:.]+])(?::\\d{1,5})?");

    private final boolean enabled;

    public ProxiedHttpsRedirect(@Value("${app.https-redirect.enabled:true}") boolean enabled) {
        this.enabled = enabled;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String method = request.getMethod();
        return !enabled || !("GET".equals(method) || "HEAD".equals(method));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String target = httpsAddress(request);
        if (target == null) {
            chain.doFilter(request, response);
            return;
        }
        response.setStatus(HttpStatus.MOVED_PERMANENTLY.value());
        response.setHeader("Location", target);
    }

    /** The https address of this request when a proxy says it came over http; null otherwise. */
    static String httpsAddress(HttpServletRequest request) {
        if (!"http".equals(firstValue(request.getHeader("X-Forwarded-Proto")))) {
            return null;
        }
        String host = firstValue(request.getHeader("X-Forwarded-Host"));
        if (host == null) {
            host = request.getHeader("Host");
        }
        if (host == null || !HOST.matcher(host.strip()).matches()) {
            return null;
        }
        // The proxy's http port says nothing about its https one: the default https port is assumed
        String name = withoutPort(host.strip());
        String query = request.getQueryString();
        return "https://" + name + request.getRequestURI() + (query == null ? "" : "?" + query);
    }

    /** The first of a comma-separated header ("http, https" after two proxies), lower-cased; null when missing. */
    private static String firstValue(String header) {
        if (header == null || header.isBlank()) {
            return null;
        }
        int comma = header.indexOf(',');
        return (comma < 0 ? header : header.substring(0, comma)).strip().toLowerCase(Locale.ROOT);
    }

    private static String withoutPort(String host) {
        if (host.startsWith("[")) {
            return host.substring(0, host.indexOf(']') + 1);
        }
        int colon = host.lastIndexOf(':');
        return colon < 0 ? host : host.substring(0, colon);
    }
}
