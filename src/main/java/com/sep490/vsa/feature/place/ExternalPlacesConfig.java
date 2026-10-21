package com.sep490.vsa.feature.place;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * Picks where "Địa điểm liên quan" finds places that ASVSJ does not have yet: the made-up places when
 * {@code app.places.dev-fake=true}, OpenStreetMap when {@code app.places.osm.enabled=true} (the default; free, no
 * key), otherwise nowhere (the search shows ASVSJ places only).
 */
@Configuration
public class ExternalPlacesConfig {

    private static final Logger log = LoggerFactory.getLogger(ExternalPlacesConfig.class);

    @Bean
    public ExternalPlaces externalPlaces(
            @Value("${app.places.dev-fake:false}") boolean devFake,
            @Value("${app.places.osm.enabled:true}") boolean osm,
            @Value("${app.places.osm.search-url:https://photon.komoot.io/api/}") String searchUrl,
            @Value("${app.places.osm.lookup-url:https://nominatim.openstreetmap.org/lookup}") String lookupUrl,
            @Value("${app.places.osm.user-agent:SenpaiOi/1.0 (ASVSJ, FPT University SEP490 project)}") String userAgent,
            @Value("${app.places.regions:jp,vn}") List<String> regions,
            ApiUsageCounter usage) {
        if (devFake) {
            log.warn("app.places.dev-fake=true: place search shows made-up places (development only).");
            return new DevOsmPlaces();
        }
        if (osm) {
            log.info("Place search includes OpenStreetMap (Photon, Nominatim; countries: {}).",
                    regions.isEmpty() ? "all" : regions);
            return new OsmPlaces(searchUrl, lookupUrl, userAgent, regions, usage);
        }
        log.warn("app.places.osm.enabled=false: place search shows ASVSJ places only.");
        return ExternalPlaces.DISABLED;
    }
}
