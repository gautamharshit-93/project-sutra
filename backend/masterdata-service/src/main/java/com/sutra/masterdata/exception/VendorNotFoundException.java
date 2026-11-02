package com.sutra.masterdata.exception;

import com.sutra.common.error.ResourceNotFoundException;
import com.sutra.masterdata.entity.Vendor;

/** A vendor does not exist. */
public class VendorNotFoundException extends ResourceNotFoundException {

    public VendorNotFoundException(Long id) {
        super("VENDOR_NOT_FOUND", "Vendor with id " + id + " was not found");
    }
}
