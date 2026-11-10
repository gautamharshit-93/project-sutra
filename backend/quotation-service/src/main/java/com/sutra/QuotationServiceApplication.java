package com.sutra;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/** SUTRA quotation service: Vendor quotations: upload and vendor portal, stored lines, backend recalculation, review flags and correction */
@SpringBootApplication
@ConfigurationPropertiesScan
public class QuotationServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(QuotationServiceApplication.class, args);
    }
}
