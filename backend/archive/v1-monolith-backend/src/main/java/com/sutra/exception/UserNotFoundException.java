package com.sutra.exception;

/** A user account does not exist. */
public class UserNotFoundException extends ResourceNotFoundException {

    public UserNotFoundException(Long id) {
        super("USER_NOT_FOUND", "User with id " + id + " was not found");
    }
}
