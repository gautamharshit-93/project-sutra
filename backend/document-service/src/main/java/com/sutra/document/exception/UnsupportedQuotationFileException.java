package com.sutra.document.exception;

import com.sutra.common.error.SutraException;

import org.springframework.http.HttpStatus;

/** The uploaded file is not one of the accepted quotation formats (PDF, XLSX, XLS, DOCX). */
public class UnsupportedQuotationFileException extends SutraException {

    public UnsupportedQuotationFileException(String message) {
        super(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "UNSUPPORTED_QUOTATION_FILE", message);
    }
}
