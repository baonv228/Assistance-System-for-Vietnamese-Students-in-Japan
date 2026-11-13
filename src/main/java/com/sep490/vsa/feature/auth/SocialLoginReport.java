package com.sep490.vsa.feature.auth;

import com.sep490.vsa.common.security.SocialRedirectUri;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.env.Environment;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/**
 * At start the server says in its log which social sign-ins are on and which address must be registered at Google /
 * Facebook ("Authorized redirect URI" / "Valid OAuth Redirect URI"), so a wrong setting on a server shows up at once
 * instead of as "không thành công" for the first real user. Values only, never a secret.
 */
@Component
public class SocialLoginReport {

    private static final Logger log = LoggerFactory.getLogger(SocialLoginReport.class);

    private final ObjectProvider<ClientRegistrationRepository> registrations;
    private final Environment environment;
    private final String publicUrl;

    public SocialLoginReport(ObjectProvider<ClientRegistrationRepository> registrations, Environment environment,
                             @Value("${app.public-url}") String publicUrl) {
        this.registrations = registrations;
        this.environment = environment;
        this.publicUrl = publicUrl;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void report() {
        for (String line : lines()) {
            if (line.startsWith("WARN ")) {
                log.warn(line.substring(5));
            } else {
                log.info(line);
            }
        }
    }

    /** The log lines, the problems marked with "WARN " in front (separate from {@link #report} so a test can read them). */
    List<String> lines() {
        Optional<String> base = SocialRedirectUri.publicBase(publicUrl);
        String[] active = environment.getActiveProfiles();
        boolean production = active.length > 0 && !Arrays.asList(active).contains("local");
        List<String> lines = new ArrayList<>();
        ClientRegistrationRepository repository = registrations.getIfAvailable();
        if (!(repository instanceof InMemoryClientRegistrationRepository all)) {
            lines.add("WARN Google/Facebook sign-in is OFF: no client id is set (spring.security.oauth2.client.registration."
                    + "<google|facebook>.client-id and .client-secret, or the env variables "
                    + "SPRING_SECURITY_OAUTH2_CLIENT_REGISTRATION_GOOGLE_CLIENT_ID / _CLIENT_SECRET and the same for FACEBOOK).");
            return lines;
        }
        for (ClientRegistration registration : all) {
            String id = registration.getRegistrationId();
            String callback = base.map(b -> SocialRedirectUri.callback(b, id))
                    .orElse("<address of the site>" + SocialRedirectUri.CALLBACK_PATH + id);
            lines.add("Sign-in with " + id + " is ON. Register this redirect URI at the provider: " + callback);
        }
        if (base.isEmpty()) {
            lines.add((production ? "WARN " : "") + "app.public-url (env APP_PUBLIC_URL) is not set to the real address of the "
                    + "site (now \"" + publicUrl + "\"): the redirect URI is taken from the request, which is only right when "
                    + "the proxy passes X-Forwarded-Proto and X-Forwarded-Host.");
        } else if (base.get().startsWith("http://")) {
            lines.add("WARN app.public-url is http://, not https://: Facebook (live mode) and Google (real domains) only "
                    + "accept https redirect URIs.");
        }
        return lines;
    }
}
