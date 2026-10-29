package com.sep490.vsa.common.web;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;

/** http visitors coming through a proxy or tunnel are sent to the https address; everything else goes on. */
class ProxiedHttpsRedirectTest {

    private final ProxiedHttpsRedirect filter = new ProxiedHttpsRedirect(true);

    @Test
    void aPageAskedOverHttpThroughATunnelMovesToHttps() throws Exception {
        MockHttpServletRequest request = request("GET", "/messages/12", "to=5");
        request.addHeader("X-Forwarded-Proto", "http");
        request.addHeader("Host", "abc-1-2.a.free.pinggy.link");

        MockHttpServletResponse response = run(request);
        assertThat(response.getStatus()).isEqualTo(301);
        assertThat(response.getHeader("Location")).isEqualTo("https://abc-1-2.a.free.pinggy.link/messages/12?to=5");
    }

    @Test
    void theForwardedHostWinsAndItsHttpPortIsDropped() throws Exception {
        MockHttpServletRequest request = request("GET", "/login", null);
        request.addHeader("X-Forwarded-Proto", "HTTP, https");
        request.addHeader("X-Forwarded-Host", "Senpai-Oi.example.com:80");
        request.addHeader("Host", "localhost:8090");

        assertThat(run(request).getHeader("Location")).isEqualTo("https://senpai-oi.example.com/login");
    }

    @Test
    void httpsDirectRequestsFormsAndOddHostsAreLeftAlone() throws Exception {
        MockHttpServletRequest https = request("GET", "/messages", null);
        https.addHeader("X-Forwarded-Proto", "https");
        https.addHeader("Host", "abc.trycloudflare.com");
        assertPassedOn(https);

        // Straight to the server while developing: localhost or the LAN address, no proxy header
        MockHttpServletRequest direct = request("GET", "/messages", null);
        direct.addHeader("Host", "192.168.1.32:8090");
        assertPassedOn(direct);

        // A form sent over http would lose its body
        MockHttpServletRequest form = request("POST", "/api/auth/login", null);
        form.addHeader("X-Forwarded-Proto", "http");
        form.addHeader("Host", "abc.trycloudflare.com");
        assertPassedOn(form);

        // Never a redirect to something that is not a plain host name
        MockHttpServletRequest odd = request("GET", "/", null);
        odd.addHeader("X-Forwarded-Proto", "http");
        odd.addHeader("Host", "evil.example.com/path@other");
        assertPassedOn(odd);

        MockHttpServletRequest off = request("GET", "/", null);
        off.addHeader("X-Forwarded-Proto", "http");
        off.addHeader("Host", "abc.trycloudflare.com");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();
        new ProxiedHttpsRedirect(false).doFilter(off, response, chain);
        assertThat(chain.getRequest()).isNotNull();
    }

    @Test
    void ipv6HostsKeepTheirBrackets() {
        MockHttpServletRequest request = request("GET", "/", null);
        request.addHeader("X-Forwarded-Proto", "http");
        request.addHeader("Host", "[2001:db8::1]:8080");
        assertThat(ProxiedHttpsRedirect.httpsAddress(request)).isEqualTo("https://[2001:db8::1]/");
    }

    private void assertPassedOn(MockHttpServletRequest request) throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();
        filter.doFilter(request, response, chain);
        assertThat(chain.getRequest()).as("passed on to the application").isNotNull();
        assertThat(response.getHeader("Location")).isNull();
    }

    private MockHttpServletResponse run(MockHttpServletRequest request) throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();
        filter.doFilter(request, response, chain);
        assertThat(chain.getRequest()).as("not passed on").isNull();
        return response;
    }

    private static MockHttpServletRequest request(String method, String uri, String query) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, uri);
        request.setQueryString(query);
        return request;
    }
}
