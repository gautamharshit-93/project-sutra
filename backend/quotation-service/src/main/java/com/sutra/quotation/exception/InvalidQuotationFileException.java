package com.sutra.quotation.exception;

import com.sutra.common.error.SutraException;

import org.springframework.http.HttpStatus;

/** The file has an accepted extension but is empty, too large, corrupt or does not match its declared type. */
public class InvalidQuotationFileException extends SutraException {

    public InvalidQuotationFileException(String message) {
        super(HttpStatus.BAD_REQUEST, "INVALID_QUOTATION_FILE", message);
    }
}
