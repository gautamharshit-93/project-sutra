package com.sutra;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/** SUTRA comparison service: Versioned side-by-side comparisons of an RFQ's ready quotations: like-for-like totals, ranking and the advisory vendor grade */
@SpringBootApplication
@ConfigurationPropertiesScan
public class ComparisonServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(ComparisonServiceApplication.class, args);
    }
}
