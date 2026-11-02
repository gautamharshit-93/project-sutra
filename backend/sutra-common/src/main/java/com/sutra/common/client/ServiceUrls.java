package com.sutra.common.client;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** {@code sutra.services.*}: where the other SUTRA services listen. Only the ones a service calls need a value. */
@ConfigurationProperties(prefix = "sutra.services")
public record ServiceUrls(String auth, String masterdata, String procurement, String audit, int timeoutSeconds) {

    public ServiceUrls {
        timeoutSeconds = timeoutSeconds <= 0 ? 10 : timeoutSeconds;
    }
}
