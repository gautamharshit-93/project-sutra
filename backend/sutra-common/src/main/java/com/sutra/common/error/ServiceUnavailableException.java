package com.sutra.common.error;

import org.springframework.http.HttpStatus;

/** Another SUTRA service could not be reached, so the request cannot be completed safely. */
public class ServiceUnavailableException extends SutraException {

    public ServiceUnavailableException(String service, Throwable cause) {
        super(HttpStatus.SERVICE_UNAVAILABLE, "SERVICE_UNAVAILABLE",
                "The " + service + " is not available right now. Try again in a moment.", cause);
    }
}
