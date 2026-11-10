package com.sutra.extraction.exception;

import com.sutra.common.error.SutraException;

import org.springframework.http.HttpStatus;

/**
 * Extraction failed (parser service unavailable, Claude error, unreadable content).
 * The quotation is marked FAILED and the original file is kept so the user can retry or enter data manually.
 */
public class QuotationProcessingException extends SutraException {

    public QuotationProcessingException(String message) {
        super(HttpStatus.BAD_GATEWAY, "QUOTATION_PROCESSING_FAILED", message);
    }

    public QuotationProcessingException(String message, Throwable cause) {
        super(HttpStatus.BAD_GATEWAY, "QUOTATION_PROCESSING_FAILED", message, cause);
    }
}
