package com.sutra.common.client;

import com.sutra.common.error.ApiError;
import com.sutra.common.error.ServiceUnavailableException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import java.util.function.Supplier;

/**
 * Runs one call to another SUTRA service and translates its failures:
 * <ul>
 *   <li>4xx with an {@code ApiError} body: {@link RemoteServiceException} with the same status, code and message;</li>
 *   <li>5xx, a timeout or an unreachable service: {@link ServiceUnavailableException} naming the service.</li>
 * </ul>
 * Every client in every service uses this, so a remote error looks the same as a local one.
 */
public final class ServiceCall {

    private static final Logger log = LoggerFactory.getLogger(ServiceCall.class);

    private ServiceCall() {
    }

    public static <T> T call(String service, Supplier<T> call) {
        try {
            return call.get();
        } catch (RestClientResponseException e) {
            throw translate(service, e);
        } catch (RestClientException e) {
            log.warn("{} unreachable: {}", service, e.getMessage());
            throw new ServiceUnavailableException(service, e);
        }
    }

    public static void run(String service, Runnable call) {
        call(service, () -> {
            call.run();
            return null;
        });
    }

    static RuntimeException translate(String service, RestClientResponseException e) {
        HttpStatus status = HttpStatus.resolve(e.getStatusCode().value());
        if (status == null || status.is5xxServerError()) {
            log.warn("{} answered {}: {}", service, e.getStatusCode().value(), e.getMessage());
            return new ServiceUnavailableException(service, e);
        }
        ApiError error = null;
        try {
            error = e.getResponseBodyAs(ApiError.class);
        } catch (RuntimeException ignored) {
            // not an ApiError body: fall back to the status alone
        }
        String code = error != null && error.code() != null ? error.code() : status.name();
        String message = error != null && error.message() != null ? error.message()
                : "The " + service + " refused the request (" + status.value() + ")";
        return new RemoteServiceException(status, code, message);
    }
}
