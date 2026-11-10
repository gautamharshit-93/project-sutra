package com.sutra;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/** SUTRA extraction service: Reads documents: file to text (Python, Tesseract OCR), Groq strict-JSON reading with a rule-based backup, item matching, RFQ drafts; the only service that talks to the AI */
@SpringBootApplication
@ConfigurationPropertiesScan
public class ExtractionServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(ExtractionServiceApplication.class, args);
    }
}
