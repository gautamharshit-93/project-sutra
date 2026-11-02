package com.sutra.common.error;

import org.springframework.http.HttpStatus;

/** Base for all application exceptions. Each subclass carries the HTTP status and a stable error code. */
public abstract class SutraException extends RuntimeException {

    private final HttpStatus status;
    private final String code;

    protected SutraException(HttpStatus status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    protected SutraException(HttpStatus status, String code, String message, Throwable cause) {
        super(message, cause);
        this.status = status;
        this.code = code;
    }

    public HttpStatus getStatus() {
        return status;
    }

    public String getCode() {
        return code;
    }
}
