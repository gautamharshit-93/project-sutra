package com.sutra.exception;

import org.springframework.http.HttpStatus;

/** The AI provider (Gemini) could not answer: not configured, rate-limited, unreachable or returned bad data. */
public class AiServiceException extends SutraException {

    public AiServiceException(String message) {
        super(HttpStatus.BAD_GATEWAY, "AI_UNAVAILABLE", message);
    }

    public AiServiceException(String message, Throwable cause) {
        super(HttpStatus.BAD_GATEWAY, "AI_UNAVAILABLE", message, cause);
    }
}
