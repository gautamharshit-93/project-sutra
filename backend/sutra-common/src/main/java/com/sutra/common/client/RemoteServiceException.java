package com.sutra.common.client;

import com.sutra.common.error.SutraException;
import org.springframework.http.HttpStatus;

/**
 * A business error answered by another SUTRA service (4xx with an {@code ApiError} body), re-thrown here with the
 * same status, code and message, so the user sees exactly what the owning service decided (e.g. 422
 * {@code RFQ_CLOSED} from the RFQ service while uploading a quotation).
 */
public class RemoteServiceException extends SutraException {

    public RemoteServiceException(HttpStatus status, String code, String message) {
        super(status, code, message);
    }
}
