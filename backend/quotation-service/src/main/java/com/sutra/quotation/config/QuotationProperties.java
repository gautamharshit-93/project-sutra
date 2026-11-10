package com.sutra.quotation.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.math.BigDecimal;

/**
 * {@code sutra.ai.*} as the quotation service uses it: a line read with confidence below
 * {@code reviewConfidenceThreshold} gets a LOW_CONFIDENCE warning; a match below {@code matchConfidenceThreshold}
 * a LOW_MATCH_CONFIDENCE warning.
 */
@ConfigurationProperties(prefix = "sutra.ai")
public record QuotationProperties(BigDecimal reviewConfidenceThreshold, BigDecimal matchConfidenceThreshold) {

    public QuotationProperties {
        reviewConfidenceThreshold = reviewConfidenceThreshold == null ? new BigDecimal("0.75") : reviewConfidenceThreshold;
        matchConfidenceThreshold = matchConfidenceThreshold == null ? new BigDecimal("0.6") : matchConfidenceThreshold;
    }
}
