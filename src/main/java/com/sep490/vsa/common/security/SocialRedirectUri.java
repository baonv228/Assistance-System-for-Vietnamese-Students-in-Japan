package com.sep490.vsa.common.security;

import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.DefaultOAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.core.endpoint.OAuth2ParameterNames;

import java.net.URI;
import java.util.Locale;
import java.util.Optional;

/**
 * The address Google/Facebook send the person back to ({@code redirect_uri}). Spring builds it from the request, which
 * behind a reverse proxy (nginx, Cloudflare, a tunnel) is only right when the proxy passes the visitor's scheme and host
 * and Tomcat trusts it: otherwise it becomes {@code http://…} or the internal name and the provider answers
 * "redirect_uri_mismatch". When {@code app.public-url} (env {@code APP_PUBLIC_URL}) names the real site, the address is
 * built from it instead, so it is exactly what is registered at Google/Facebook whatever the proxy does. On a local
 * address (the default, development) nothing changes: the request decides.
 */
public final class SocialRedirectUri {

    /** Where the provider sends the person back (Spring Security's default), followed by the registration id. */
    public static final String CALLBACK_PATH = "/login/oauth2/code/";

    private SocialRedirectUri() {
    }

    /** The site's own address without a trailing "/", or empty when it is not set to a real one (localhost, invalid). */
    public static Optional<String> publicBase(String publicUrl) {
        if (publicUrl == null || publicUrl.isBlank()) {
            return Optional.empty();
        }
        URI uri;
        try {
            uri = URI.create(publicUrl.trim());
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
        if (!(scheme.equals("http") || scheme.equals("https")) || host.isEmpty() || isLocal(host)) {
            return Optional.empty();
        }
        String text = publicUrl.trim();
        while (text.endsWith("/")) {
            text = text.substring(0, text.length() - 1);
        }
        return Optional.of(text);
    }

    /** The address to register at the provider for this login ({@code registrationId} = google, facebook). */
    public static String callback(String base, String registrationId) {
        return base + CALLBACK_PATH + registrationId;
    }

    /** The resolver that sends people to the provider; with a real public address the redirect_uri comes from it. */
    public static OAuth2AuthorizationRequestResolver resolver(ClientRegistrationRepository registrations,
                                                              String publicUrl) {
        DefaultOAuth2AuthorizationRequestResolver resolver = new DefaultOAuth2AuthorizationRequestResolver(
                registrations, "/oauth2/authorization");
        publicBase(publicUrl).ifPresent(base -> resolver.setAuthorizationRequestCustomizer(builder -> {
            String[] registrationId = new String[1];
            builder.attributes(attributes -> registrationId[0] = (String) attributes.get(OAuth2ParameterNames.REGISTRATION_ID));
            if (registrationId[0] != null) {
                builder.redirectUri(callback(base, registrationId[0]));
            }
        }));
        return resolver;
    }

    private static boolean isLocal(String host) {
        return host.equals("localhost") || host.endsWith(".localhost") || host.equals("127.0.0.1")
                || host.equals("::1") || host.equals("[::1]") || host.equals("0.0.0.0");
    }
}
