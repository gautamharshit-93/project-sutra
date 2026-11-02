package com.sutra.ai;

import com.sutra.entity.enums.ExtractionSource;

/**
 * Reads a quotation document and returns its fields. Implementations report values as printed and must not
 * compute totals; failures are thrown as {@link com.sutra.exception.QuotationProcessingException}.
 */
public interface QuotationExtractor {

    ExtractedQuotation extract(ExtractionInput input);

    ExtractionSource source();

    /** Whether this extractor can be used right now (configured and enabled). */
    boolean isAvailable();

    /** The model in use, shown on the dashboard. */
    String model();
}
