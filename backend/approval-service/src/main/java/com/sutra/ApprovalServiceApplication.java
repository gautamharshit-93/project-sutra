package com.sutra;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/** SUTRA approval service: Award or reject decisions on compared RFQs: who, when, which quotation and why; never automatic */
@SpringBootApplication
@ConfigurationPropertiesScan
public class ApprovalServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(ApprovalServiceApplication.class, args);
    }
}
