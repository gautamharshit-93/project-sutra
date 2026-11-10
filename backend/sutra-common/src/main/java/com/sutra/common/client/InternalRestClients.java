package com.sutra.common.client;

import com.sutra.common.security.JwtCodec;
import com.sutra.common.web.CorrelationIdFilter;
import java.net.http.HttpClient;
import java.time.Duration;
import java.time.Instant;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Builds RestClients for calls to another SUTRA service's {@code /internal/**} API. Each call carries a short-lived
 * service token signed by this service and the current request id, so one user action can be followed through
 * every service's log.
 */
@Component
public class InternalRestClients {

    private final JwtCodec jwt;
    private final String serviceName;
    private final ServiceUrls urls;
    private volatile String token;
    private volatile Instant tokenRenewAt = Instant.EPOCH;

    public InternalRestClients(JwtCodec jwt, ServiceUrls urls, @Value("${spring.application.name}") String serviceName) {
        this.jwt = jwt;
        this.urls = urls;
        this.serviceName = serviceName;
    }

    public RestClient forBaseUrl(String baseUrl) {
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build());
        factory.setReadTimeout(Duration.ofSeconds(urls.timeoutSeconds()));
        return RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(factory)
                .requestInterceptor((request, body, execution) -> {
                    request.getHeaders().set(HttpHeaders.AUTHORIZATION, "Bearer " + serviceToken());
                    String correlationId = CorrelationIdFilter.current();
                    if (correlationId != null) {
                        request.getHeaders().set(CorrelationIdFilter.HEADER, correlationId);
                    }
                    return execution.execute(request, body);
                })
                .build();
    }

    /** A cached service token, renewed a minute before it expires. */
    String serviceToken() {
        if (Instant.now().isAfter(tokenRenewAt)) {
            synchronized (this) {
                if (Instant.now().isAfter(tokenRenewAt)) {
                    token = jwt.issueService(serviceName);
                    tokenRenewAt = Instant.now().plus(Duration.ofMinutes(4));
                }
            }
        }
        return token;
    }
}
