package com.sep490.vsa.feature.place;

import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;

/**
 * Adds 1 to {@code api_usage} for the service and today's date in Japan (one upsert). The map is asked outside any
 * database transaction (the search, and the lookup before a post is saved), so each count is written at once.
 */
@Component
public class ApiUsageRecorder implements ApiUsageCounter {

    /** Days are counted in Japan time, where the users live. */
    private static final ZoneId JAPAN = ZoneId.of("Asia/Tokyo");

    private final PlaceRepository repository;
    private final Clock clock;

    public ApiUsageRecorder(PlaceRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    @Override
    public void count(String service) {
        repository.addUsage(service, LocalDate.now(clock.withZone(JAPAN)));
    }
}
