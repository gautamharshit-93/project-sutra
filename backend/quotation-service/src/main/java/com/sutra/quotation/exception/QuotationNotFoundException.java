package com.sutra.quotation.exception;

import com.sutra.common.error.ResourceNotFoundException;

/** A quotation does not exist. */
public class QuotationNotFoundException extends ResourceNotFoundException {

    public QuotationNotFoundException(Long id) {
        super("QUOTATION_NOT_FOUND", "Quotation with id " + id + " was not found");
    }
}
