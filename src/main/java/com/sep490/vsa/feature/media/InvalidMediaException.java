package com.sep490.vsa.feature.media;

/** An uploaded file breaks the photo or video rules. The message is shown to the user as is. */
public class InvalidMediaException extends RuntimeException {

    public InvalidMediaException(String message) {
        super(message);
    }
}
