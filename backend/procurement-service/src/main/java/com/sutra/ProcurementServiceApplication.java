package com.sutra;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * SUTRA procurement service: RFQs, quotations and their files, extraction (Groq with local OCR, or the rule-based reader),
 * review, comparison, award and the vendor portal (schema "procurement").
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class ProcurementServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(ProcurementServiceApplication.class, args);
    }
}
