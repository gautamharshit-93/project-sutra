package com.sutra.masterdata.exception;

import com.sutra.common.error.ResourceNotFoundException;
import com.sutra.masterdata.entity.Customer;

/** A customer does not exist. */
public class CustomerNotFoundException extends ResourceNotFoundException {

    public CustomerNotFoundException(Long id) {
        super("CUSTOMER_NOT_FOUND", "Customer with id " + id + " was not found");
    }
}
