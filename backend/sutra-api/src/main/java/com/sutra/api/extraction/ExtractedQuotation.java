package com.sutra.api.extraction;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

/**
 * Structured extraction output returned by Groq (see {@link ExtractionSchema} for the JSON Schema sent with each
 * request). Values are reported exactly as printed; the backend recalculates every total.
 */
public record ExtractedQuotation(
        Optional<String> vendorName,
        Optional<String> quotationNumber,
        Optional<String> quotationDate,
        Optional<String> validUntil,
        Optional<String> currency,
        List<ExtractedItem> items,
        Optional<BigDecimal> subtotal,
        Optional<BigDecimal> discountTotal,
        Optional<BigDecimal> taxTotal,
        Optional<BigDecimal> grandTotal,
        Optional<String> deliveryTerms,
        Optional<String> paymentTerms,
        Optional<String> warrantyTerms,
        Optional<String> notes,
        double overallConfidence,
        List<String> warnings) {

    public ExtractedQuotation {
        vendorName = orEmpty(vendorName);
        quotationNumber = orEmpty(quotationNumber);
        quotationDate = orEmpty(quotationDate);
        validUntil = orEmpty(validUntil);
        currency = orEmpty(currency);
        items = items == null ? List.of() : items;
        subtotal = orEmpty(subtotal);
        discountTotal = orEmpty(discountTotal);
        taxTotal = orEmpty(taxTotal);
        grandTotal = orEmpty(grandTotal);
        deliveryTerms = orEmpty(deliveryTerms);
        paymentTerms = orEmpty(paymentTerms);
        warrantyTerms = orEmpty(warrantyTerms);
        notes = orEmpty(notes);
        warnings = warnings == null ? List.of() : warnings;
    }

    public record ExtractedItem(
            Optional<String> itemCode,
            String description,
            Optional<BigDecimal> quantity,
            Optional<String> unitOfMeasure,
            Optional<BigDecimal> unitPrice,
            Optional<BigDecimal> discountPercent,
            Optional<BigDecimal> discountAmount,
            Optional<BigDecimal> taxPercent,
            Optional<BigDecimal> taxAmount,
            Optional<BigDecimal> lineTotal,
            double confidence,
            Optional<String> notes) {

        public ExtractedItem {
            itemCode = orEmpty(itemCode);
            quantity = orEmpty(quantity);
            unitOfMeasure = orEmpty(unitOfMeasure);
            unitPrice = orEmpty(unitPrice);
            discountPercent = orEmpty(discountPercent);
            discountAmount = orEmpty(discountAmount);
            taxPercent = orEmpty(taxPercent);
            taxAmount = orEmpty(taxAmount);
            lineTotal = orEmpty(lineTotal);
            notes = orEmpty(notes);
        }
    }

    /** JSON with a missing key yields null rather than Optional.empty(); normalise it. */
    private static <T> Optional<T> orEmpty(Optional<T> value) {
        return value == null ? Optional.empty() : value;
    }
}
