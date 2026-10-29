package com.sep490.vsa.feature.chat.notify;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * Which push endpoints the server may call. The endpoint is a URL the browser hands over when it subscribes, so any
 * member could send any URL instead: without this check the server would POST to whatever address it is given, inside
 * the server's network too (SSRF). Only https on the default port of the known push services is called:
 * {@code app.push.allowed-hosts} lists them, an entry "*.example.com" meaning any name under example.com.
 * <p>
 * An entry written "http://host" also allows plain http on any port of exactly that host. It exists only for tests
 * with a local stand-in push service; never put one in the server's settings.
 */
@Component
public class PushEndpointPolicy {

    /** Chrome/Edge/Opera (FCM), Firefox (Mozilla autopush), Windows (WNS), Safari (Apple). */
    static final String DEFAULT_HOSTS =
            "fcm.googleapis.com,*.push.services.mozilla.com,*.notify.windows.com,web.push.apple.com,*.push.apple.com";
    /** The column's size ({@code push_subscriptions.endpoint}). */
    static final int MAX_LENGTH = 1000;

    private final Set<String> hosts = new HashSet<>();
    private final Set<String> suffixes = new HashSet<>();
    private final Set<String> plainHttpHosts = new HashSet<>();

    public PushEndpointPolicy(@Value("${app.push.allowed-hosts:" + DEFAULT_HOSTS + "}") String allowedHosts) {
        Arrays.stream(allowedHosts.split(",")).map(s -> s.strip().toLowerCase(Locale.ROOT)).filter(s -> !s.isEmpty())
                .forEach(entry -> {
                    if (entry.startsWith("http://")) {
                        plainHttpHosts.add(entry.substring("http://".length()));
                    } else if (entry.startsWith("*.")) {
                        suffixes.add(entry.substring(1));                       // ".push.services.mozilla.com"
                    } else {
                        hosts.add(entry);
                    }
                });
    }

    /** The endpoint as a URI when the server may call it; empty otherwise. */
    public Optional<URI> check(String endpoint) {
        if (endpoint == null || endpoint.isBlank() || endpoint.length() > MAX_LENGTH) {
            return Optional.empty();
        }
        URI uri;
        try {
            uri = new URI(endpoint);
        } catch (URISyntaxException e) {
            return Optional.empty();
        }
        if (!uri.isAbsolute() || uri.isOpaque() || uri.getHost() == null || uri.getRawUserInfo() != null
                || uri.getRawFragment() != null) {
            return Optional.empty();
        }
        String scheme = uri.getScheme().toLowerCase(Locale.ROOT);
        String host = uri.getHost().toLowerCase(Locale.ROOT);
        boolean allowed = switch (scheme) {
            case "https" -> (uri.getPort() == -1 || uri.getPort() == 443) && knownService(host);
            case "http" -> plainHttpHosts.contains(host);
            default -> false;
        };
        return allowed ? Optional.of(uri) : Optional.empty();
    }

    private boolean knownService(String host) {
        if (hosts.contains(host)) {
            return true;
        }
        for (String suffix : suffixes) {
            if (host.endsWith(suffix) && host.length() > suffix.length()) {
                return true;
            }
        }
        return false;
    }
}
