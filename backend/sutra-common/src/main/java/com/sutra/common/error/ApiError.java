package com.sutra.common.error;

import java.time.Instant;
import java.util.List;

/** The single error shape returned by every endpoint. */
public record ApiError(
        Instant timestamp,
        int status,
        String error,
        String code,
        String message,
        String path,
        List<FieldError> fieldErrors,
        String correlationId) {

    public record FieldError(String field, String message) {
    }
}
