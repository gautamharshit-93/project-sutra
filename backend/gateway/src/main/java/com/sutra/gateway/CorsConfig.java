package com.sutra.gateway;

import com.sutra.common.web.CorrelationIdFilter;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** CORS for browsers on other origins (the Vite dev server proxies, so it is same-origin). */
@Configuration
public class CorsConfig implements WebMvcConfigurer {

    private final GatewayProperties properties;

    public CorsConfig(GatewayProperties properties) {
        this.properties = properties;
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**")
                .allowedOrigins(properties.corsAllowedOrigins().toArray(String[]::new))
                .allowedMethods("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS")
                .allowedHeaders("Authorization", "Content-Type", CorrelationIdFilter.HEADER)
                .exposedHeaders("Content-Disposition", CorrelationIdFilter.HEADER)
                .maxAge(3600);
    }
}
