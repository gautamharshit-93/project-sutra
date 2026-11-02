package com.sutra.common.error;

import org.springframework.http.HttpStatus;

public class DuplicateResourceException extends SutraException {

    public DuplicateResourceException(String message) {
        super(HttpStatus.CONFLICT, "DUPLICATE_RESOURCE", message);
    }
}
