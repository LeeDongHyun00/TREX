package com.trex.server.exception;

import java.util.Map;

public record ErrorResponse(
        String message,
        Map<String, String> fieldErrors
) {

    public static ErrorResponse of(String message) {
        return new ErrorResponse(message, Map.of());
    }

    public static ErrorResponse of(String message, Map<String, String> fieldErrors) {
        return new ErrorResponse(message, fieldErrors);
    }
}
