package com.sutra.gateway;

import com.sutra.common.security.JwtCodec;
import com.sutra.common.security.SecurityProperties;
import com.sutra.common.web.CorrelationIdFilter;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;

/**
 * SUTRA API gateway: the only address the browser talks to. It routes {@code /api/**} to the owning service,
 * handles CORS, gives every request an id, and refuses tokens of users who were deactivated or changed.
 */
@SpringBootApplication
@EnableConfigurationProperties({GatewayProperties.class, SecurityProperties.class})
@Import(CorrelationIdFilter.class)
public class GatewayApplication {

    public static void main(String[] args) {
        SpringApplication.run(GatewayApplication.class, args);
    }

    @Bean
    java.time.Clock clock() {
        return java.time.Clock.systemDefaultZone();
    }

    @Bean
    JwtCodec jwtCodec(SecurityProperties properties) {
        return new JwtCodec(properties);
    }
}
