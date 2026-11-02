package com.sutra;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/** SUTRA audit service: stores and serves the audit trail of every service (schema "audit"). */
@SpringBootApplication
@ConfigurationPropertiesScan
public class AuditServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(AuditServiceApplication.class, args);
    }
}
