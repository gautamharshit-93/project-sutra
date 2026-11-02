package com.sutra.masterdata.exception;

import com.sutra.common.error.ResourceNotFoundException;

/** A business entity (parent organisation) does not exist. */
public class EntityNotFoundException extends ResourceNotFoundException {

    public EntityNotFoundException(Long id) {
        super("ENTITY_NOT_FOUND", "Entity with id " + id + " was not found");
    }
}
