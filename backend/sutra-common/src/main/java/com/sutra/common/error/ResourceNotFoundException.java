package com.sutra.common.error;

import org.springframework.http.HttpStatus;

/** Generic 404. Specific subclasses exist for the main aggregates so callers can catch them precisely. */
public class ResourceNotFoundException extends SutraException {

    public ResourceNotFoundException(String resource, Object id) {
        this(resource.toUpperCase().replace(' ', '_') + "_NOT_FOUND", resource + " with id " + id + " was not found");
    }

    protected ResourceNotFoundException(String code, String message) {
        super(HttpStatus.NOT_FOUND, code, message);
    }
}
