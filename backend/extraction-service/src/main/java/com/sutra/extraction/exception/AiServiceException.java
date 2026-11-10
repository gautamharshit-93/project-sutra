package com.sutra.extraction.exception;

import com.sutra.common.error.SutraException;

import org.springframework.http.HttpStatus;

/** The AI provider (Groq) could not answer: not configured, rate-limited, unreachable or returned bad data. */
public class AiServiceException extends SutraException {

    public AiServiceException(String message) {
        super(HttpStatus.BAD_GATEWAY, "AI_UNAVAILABLE", message);
    }

    public AiServiceException(String message, Throwable cause) {
        super(HttpStatus.BAD_GATEWAY, "AI_UNAVAILABLE", message, cause);
    }
}
