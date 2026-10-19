package com.sep490.vsa.common.exception;

import org.springframework.http.HttpStatus;

import java.util.Map;

/**
 * Business error with the HTTP status and the message shown to the user (Vietnamese UI text).
 * {@code fields} optionally carries one message per form field, shown in red under that field.
 */
public class ApiException extends RuntimeException {

    private final HttpStatus status;
    private final Map<String, String> fields;

    public ApiException(HttpStatus status, String message) {
        this(status, message, Map.of());
    }

    public ApiException(HttpStatus status, String message, Map<String, String> fields) {
        super(message);
        this.status = status;
        this.fields = fields == null ? Map.of() : Map.copyOf(fields);
    }

    public HttpStatus getStatus() {
        return status;
    }

    /** Per-field messages (field name → message); empty when the error is not about one field. */
    public Map<String, String> getFields() {
        return fields;
    }

    public static ApiException badRequest(String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, message);
    }

    /** 400 with a message for each invalid field; {@code message} is the summary shown above the form. */
    public static ApiException invalid(String message, Map<String, String> fields) {
        return new ApiException(HttpStatus.BAD_REQUEST, message, fields);
    }

    public static ApiException conflict(String message) {
        return new ApiException(HttpStatus.CONFLICT, message);
    }

    public static ApiException unauthorized(String message) {
        return new ApiException(HttpStatus.UNAUTHORIZED, message);
    }

    public static ApiException forbidden(String message) {
        return new ApiException(HttpStatus.FORBIDDEN, message);
    }

    public static ApiException tooManyRequests(String message) {
        return new ApiException(HttpStatus.TOO_MANY_REQUESTS, message);
    }
}
