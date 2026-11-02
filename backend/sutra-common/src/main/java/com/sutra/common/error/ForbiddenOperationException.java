package com.sutra.common.error;

import org.springframework.http.HttpStatus;

/** The caller's role allows the endpoint but not this particular record (e.g. editing another user's request). */
public class ForbiddenOperationException extends SutraException {

    public ForbiddenOperationException(String message) {
        super(HttpStatus.FORBIDDEN, "FORBIDDEN", message);
    }
}
