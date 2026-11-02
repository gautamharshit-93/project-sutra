package com.sutra.common;

import com.sutra.common.audit.AuditScopeResolver;
import com.sutra.common.client.ServiceUrls;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** Shared wiring for the domain services (each service's application class sits in {@code com.sutra}). */
@Configuration
@EnableScheduling
@EnableConfigurationProperties(ServiceUrls.class)
public class CommonConfig {

    /**
     * Services without purchase-request records do not group their audit events by RFQ. The procurement service
     * replaces this with a {@code @Primary} resolver.
     */
    @Bean
    AuditScopeResolver auditScopeResolver() {
        return (entityType, entityId) -> null;
    }
}
