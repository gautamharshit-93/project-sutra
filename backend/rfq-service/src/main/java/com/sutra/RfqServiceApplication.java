package com.sutra;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/** SUTRA rfq service: Purchase requests (RFQs) and their items: create, edit, open, cancel; status set by comparison and approval */
@SpringBootApplication
@ConfigurationPropertiesScan
public class RfqServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(RfqServiceApplication.class, args);
    }
}
