package com.sutra.rfq.exception;

import com.sutra.common.error.ResourceNotFoundException;

/** A purchase request does not exist. */
public class PurchaseRequestNotFoundException extends ResourceNotFoundException {

    public PurchaseRequestNotFoundException(Long id) {
        super("PURCHASE_REQUEST_NOT_FOUND", "Purchase request with id " + id + " was not found");
    }
}
