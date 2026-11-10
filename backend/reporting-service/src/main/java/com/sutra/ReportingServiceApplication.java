package com.sutra;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;

/**
 * SUTRA reporting service: the dashboard, menu badges, the award summary PDF and the Sahayak assistant. It reads
 * the other services' APIs and stores nothing, so it has no database (the shared audit outbox is left out).
 */
@SpringBootApplication
@ConfigurationPropertiesScan
@ComponentScan(basePackages = "com.sutra",
        excludeFilters = @ComponentScan.Filter(type = FilterType.REGEX, pattern = "com[.]sutra[.]common[.]audit[.].*"))
public class ReportingServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(ReportingServiceApplication.class, args);
    }
}
