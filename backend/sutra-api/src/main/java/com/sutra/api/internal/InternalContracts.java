package com.sutra.api.internal;

import com.sutra.api.enums.ExtractionEngine;
import com.sutra.api.enums.ExtractionSource;
import com.sutra.api.enums.FileFormat;
import com.sutra.api.enums.PurchaseRequestStatus;
import com.sutra.api.extraction.ExtractedQuotation;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * Records exchanged on the services' {@code /internal/**} APIs (service tokens only; never reachable through the
 * gateway). Public request/response records live in the rfq, quotation and decision packages.
 */
public final class InternalContracts {

    private InternalContracts() {
    }

    /**
     * Moves an RFQ from {@code from} to {@code to} on behalf of another service (comparison: OPEN to UNDER_REVIEW;
     * approval: UNDER_REVIEW to APPROVED/REJECTED). Refused when the RFQ is no longer in {@code from}, so two
     * decisions can never race. {@code actor} is the user who caused it, for the audit trail.
     */
    public record StatusChange(PurchaseRequestStatus from, PurchaseRequestStatus to, String actor,
                               Map<String, Object> details) {
    }

    /** How many RFQs and quotations refer to a record (before master data or a user may be deleted). */
    public record Usage(long purchaseRequests, long quotations) {
    }

    /** What the RFQ service needs to know about an RFQ's quotations. */
    public record RfqQuotationUsage(long quotations, List<Long> matchedRequestItemIds) {
    }

    /** Read (or re-read) a quotation with this engine; {@code model} is the Groq model to try first, or null. */
    public record ExtractionTask(Long quotationId, ExtractionEngine engine, String model) {
    }

    /** One stored file of a quotation, by its document id. */
    public record WorkDocument(Long documentId, String fileName, FileFormat format) {
    }

    /** A requested RFQ line, given to the reader as context and used for matching. */
    public record RequestedLine(Long id, int lineNumber, String itemCode, String description, BigDecimal quantity,
                                String unitOfMeasure, String specifications) {
    }

    /** Everything the extraction service needs to read a quotation (returned when it claims the quotation). */
    public record ExtractionWork(Long quotationId, String vendorName, String requestCurrency,
                                 List<WorkDocument> documents, List<RequestedLine> requestedLines) {
    }

    /** Quoted line {@code index} (in the extracted item list) matches requested line {@code requestItemId}. */
    public record LineMatch(int index, Long requestItemId, BigDecimal score) {
    }

    /**
     * The result of reading a quotation: the data as printed and the line matches, or the reason it failed.
     * The quotation service stores it, recalculates every total and raises the review flags.
     */
    public record ExtractionOutcome(ExtractedQuotation data, ExtractionSource source, String model,
                                    List<LineMatch> matches, String failureReason) {

        public static ExtractionOutcome failed(String reason) {
            return new ExtractionOutcome(null, null, null, List.of(), reason);
        }

        public boolean succeeded() {
            return failureReason == null && data != null;
        }
    }

    /** Counts by status, e.g. RFQs or quotations, for the dashboard. */
    public record StatusCounts(Map<String, Long> byStatus) {
    }

    /** A chat completion asked of the AI (Groq) through the extraction service, which owns the AI integration. */
    public record AiCompletionRequest(List<Map<String, Object>> messages, Map<String, Object> responseFormat,
                                      int maxOutputTokens) {
    }

    public record AiCompletion(String content, String model, long inputTokens, long outputTokens) {
    }

    /**
     * What reading uses right now: whether Groq is configured (and its model), the engine AUTO would use first
     * (the rule-based reader when Groq is not configured) and whether the Python document reader answers.
     */
    public record AiStatus(boolean groqConfigured, String groqModel, String extractionEngine, String extractionModel,
                           boolean extractionConfigured, boolean documentReaderAvailable) {
    }

    /** One of a vendor's quotations and the RFQ it is for. */
    public record QuotationRef(Long quotationId, Long rfqId) {
    }
}
