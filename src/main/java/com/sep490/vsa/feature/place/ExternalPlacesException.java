package com.sep490.vsa.feature.place;

/**
 * The map service could not answer. {@link #reason()} says why, so the page can tell the user what to do:
 * LIMITED (the service refuses for now: too many requests, HTTP 429/403), BUSY (other lookups are waiting, see
 * {@link OsmPlaces}), OSM_ERROR (any other error answer), SSL (the server does not trust the certificate, often
 * antivirus HTTPS scanning on Windows), TIMEOUT, DNS or NETWORK.
 */
public class ExternalPlacesException extends RuntimeException {

    private final String reason;

    public ExternalPlacesException(String reason, String message, Throwable cause) {
        super(message, cause);
        this.reason = reason;
    }

    public String reason() {
        return reason;
    }
}
