package com.sutra.extraction.service.internal;

import com.sutra.api.enums.FileFormat;
import java.math.BigDecimal;
import java.util.List;

/**
 * Everything the extractor may use: all files of one quotation (read together), the vendor it was uploaded for,
 * and the purchase-request lines for context. File bytes must never be logged.
 */
public record ExtractionInput(
        List<Document> documents,
        String expectedVendorName,
        String requestCurrency,
        List<RequestedItem> requestedItems,
        /** The Groq model to try first, or null for the configured order. */
        String preferredModel) {

    public ExtractionInput(List<Document> documents, String expectedVendorName, String requestCurrency,
                           List<RequestedItem> requestedItems) {
        this(documents, expectedVendorName, requestCurrency, requestedItems, null);
    }

    public ExtractionInput withPreferredModel(String model) {
        return new ExtractionInput(documents, expectedVendorName, requestCurrency, requestedItems, model);
    }

    public ExtractionInput {
        documents = List.copyOf(documents);
        if (documents.isEmpty()) {
            throw new IllegalArgumentException("A quotation needs at least one file");
        }
    }

    /** One original file of the quotation. */
    public record Document(String fileName, FileFormat format, byte[] content) {
        @Override
        public String toString() {
            return "Document[" + fileName + ", " + format + ", " + content.length + " bytes]";
        }
    }

    /** The purchase-request lines, given as context so the extractor knows what goods are being quoted. */
    public record RequestedItem(int lineNumber, String itemCode, String description, BigDecimal quantity,
                                String unitOfMeasure, String specifications) {
    }

    /** File names joined with ", " - for messages and logs. */
    public String fileName() {
        return String.join(", ", documents.stream().map(Document::fileName).toList());
    }

    @Override
    public String toString() {
        return "ExtractionInput[files=" + documents + "]";
    }
}
