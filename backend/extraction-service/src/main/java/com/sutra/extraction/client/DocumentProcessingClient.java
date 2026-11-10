package com.sutra.extraction.client;

import com.sutra.extraction.service.internal.ExtractionInput;
import com.sutra.api.extraction.ExtractedQuotation;
import java.math.BigDecimal;
import java.util.List;

/**
 * Contract with the Python document-processing service in /ai: file-to-text conversion, item matching, and the
 * rule-based extraction engine (no AI).
 */
public interface DocumentProcessingClient {

    /** Converts a PDF/Excel/Word file into plain text (tables rendered as pipe-separated rows). */
    ParsedDocument parse(String fileName, byte[] content);

    /** Scores each quoted line against the requested lines (fuzzy text similarity). */
    List<MatchScore> match(List<MatchRequestItem> requestItems, List<MatchQuotedItem> quotedItems);

    /**
     * Reads all files of one quotation with fixed rules (table headers, labelled totals and terms) instead of AI.
     * Values are reported as printed; the backend recalculates every total.
     */
    ExtractedQuotation extractWithRules(List<ExtractionInput.Document> documents);

    boolean isAvailable();

    record ParsedDocument(String format, String text, int pages, int charCount, boolean likelyScanned,
                          List<String> warnings) {
    }

    record MatchRequestItem(Long id, String description, String specifications, String unitOfMeasure,
                            BigDecimal quantity) {
    }

    record MatchQuotedItem(int index, String description, String unitOfMeasure, BigDecimal quantity) {
    }

    /** Score of quoted item {@code quotedIndex} against request item {@code requestItemId}, 0..1. */
    record MatchScore(int quotedIndex, Long requestItemId, double score) {
    }
}
