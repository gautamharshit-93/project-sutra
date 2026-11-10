package com.sutra.extraction.service.internal;

import com.sutra.extraction.client.DocumentProcessingClient;
import com.sutra.api.extraction.ExtractedQuotation;
import com.sutra.api.enums.ExtractionSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * The rule-based engine: no AI, no API key, no rate limit. The Python service finds the item table by its column
 * headers (code, description, quantity, unit price, discount, GST, line total), and the totals, dates and
 * commercial terms by their labels. It works on Excel, Word and text-based PDF quotations with a normal layout;
 * scanned PDFs and free-form letters need an AI engine. It also serves as an independent cross-check of the AI
 * engines' readings.
 */
@Component
public class RulesQuotationExtractor implements QuotationExtractor {

    private static final Logger log = LoggerFactory.getLogger(RulesQuotationExtractor.class);

    private final DocumentProcessingClient documentClient;

    public RulesQuotationExtractor(DocumentProcessingClient documentClient) {
        this.documentClient = documentClient;
    }

    @Override
    public ExtractionSource source() {
        return ExtractionSource.RULES;
    }

    /** Always on: it only needs the Python document service, and a stopped service fails with a clear message. */
    @Override
    public boolean isAvailable() {
        return true;
    }

    @Override
    public String model() {
        return "rules v1";
    }

    @Override
    public ExtractedQuotation extract(ExtractionInput input) {
        ExtractedQuotation result = documentClient.extractWithRules(input.documents());
        log.info("Rule-based engine extracted {} line(s) from {} file(s) [{}]", result.items().size(),
                input.documents().size(), input.fileName());
        return result;
    }
}
