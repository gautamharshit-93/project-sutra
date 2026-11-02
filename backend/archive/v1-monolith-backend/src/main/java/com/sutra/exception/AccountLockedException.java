package com.sutra.exception;

import org.springframework.http.HttpStatus;

/** Sign-in is temporarily blocked after repeated failed attempts. */
public class AccountLockedException extends SutraException {

    public AccountLockedException(String message) {
        super(HttpStatus.TOO_MANY_REQUESTS, "ACCOUNT_LOCKED", message);
    }
}
