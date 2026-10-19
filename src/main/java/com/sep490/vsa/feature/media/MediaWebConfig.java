package com.sep490.vsa.feature.media;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.CacheControl;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.concurrent.TimeUnit;

/**
 * Serves files of {@link LocalMediaStorage} at {@code /media/**} while media URLs point at this server
 * (the default {@code app.media.public-base-url=/media/}). With an external store such as R2 the base URL
 * points elsewhere and nothing is registered here.
 */
@Configuration
public class MediaWebConfig implements WebMvcConfigurer {

    private final LocalMediaStorage storage;
    private final String publicBaseUrl;

    public MediaWebConfig(LocalMediaStorage storage, @Value("${app.media.public-base-url:/media/}") String publicBaseUrl) {
        this.storage = storage;
        this.publicBaseUrl = publicBaseUrl;
    }

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        if (!"/media/".equals(publicBaseUrl)) {
            return;
        }
        // Every file has a new random name, so browsers may cache it for a long time
        registry.addResourceHandler("/media/**")
                .addResourceLocations(storage.rootLocation())
                .setCacheControl(CacheControl.maxAge(30, TimeUnit.DAYS).cachePublic());
    }
}
