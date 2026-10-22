package com.sep490.vsa.feature.place;

/**
 * Counts requests to an outside service in {@code api_usage} (one row per service and Japan day), to watch the fair
 * use of the free public map services. {@link OsmPlaces} calls it once for every HTTP request it sends, answered or
 * not; nothing is counted when no request goes out (an answer from memory, the pause after Nominatim could not be
 * reached, made-up DEV places). {@link ApiUsageRecorder} writes the rows.
 */
@FunctionalInterface
public interface ApiUsageCounter {

    /** @param service e.g. {@link OsmPlaces#SEARCH_USAGE} */
    void count(String service);

    /** Counts nothing (tests that do not look at the counts). */
    ApiUsageCounter NONE = service -> {
    };
}
