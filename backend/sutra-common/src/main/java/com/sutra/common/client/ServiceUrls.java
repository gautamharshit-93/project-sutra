package com.sutra.common.client;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code sutra.services.*}: where the other SUTRA services listen. Only the ones a service calls need a value.
 *
 * <pre>
 * auth 8081 · masterdata 8082 · rfq 8083 · audit 8084 · quotation 8085 · document 8086 · extraction 8087
 * comparison 8088 · approval 8089 · reporting 8090 (gateway 8080, Python document reader 8000)
 * </pre>
 */
@ConfigurationProperties(prefix = "sutra.services")
public record ServiceUrls(String auth, String masterdata, String rfq, String quotation, String document,
                          String extraction, String comparison, String approval, String reporting, String audit,
                          int timeoutSeconds) {

    public ServiceUrls {
        timeoutSeconds = timeoutSeconds <= 0 ? 10 : timeoutSeconds;
    }
}
