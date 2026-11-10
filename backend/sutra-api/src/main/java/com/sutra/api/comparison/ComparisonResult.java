package com.sutra.api.comparison;

import com.sutra.api.enums.QuotationStatus;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * The side-by-side comparison matrix stored as JSON on {@code comparison_report.details}.
 * Rows are requested items; columns are quotations.
 */
public record ComparisonResult(
        Long purchaseRequestId,
        String requestCurrency,
        List<QuotationColumn> quotations,
        List<ItemRow> rows,
        List<UnmatchedItem> unmatchedItems,
        List<String> warnings,
        Long lowestCompleteQuotationId,
        String disclaimer) {

    public static final String DISCLAIMER = "This comparison is informational. The system never selects or approves "
            + "a vendor; an authorised approver must record the decision.";

    /** One quotation's totals and commercial terms. */
    public record QuotationColumn(
            Long quotationId,
            Long quotationVersion,
            Long vendorId,
            String vendorName,
            String quotationNumber,
            QuotationStatus status,
            String currency,
            BigDecimal subtotal,
            BigDecimal discountTotal,
            BigDecimal taxTotal,
            BigDecimal grandTotal,
            /** Sum of matched lines normalised to the requested quantities - the like-for-like figure. */
            BigDecimal comparableTotal,
            LocalDate validUntil,
            boolean validityExpired,
            String deliveryTerms,
            String paymentTerms,
            String warrantyTerms,
            int itemsQuoted,
            int itemsMissing,
            boolean complete,
            Integer priceRank,
            BigDecimal differenceFromLowest,
            BigDecimal differencePercent,
            long openFlags,
            List<String> missingInformation,
            /** Advisory A-F grade from fixed rules (see VendorGrader); never a selection or approval. */
            GradeReport grade) {
    }

    /** One requested item across all quotations. */
    public record ItemRow(
            Long requestItemId,
            Integer lineNumber,
            String itemCode,
            String description,
            BigDecimal quantity,
            String unitOfMeasure,
            List<ItemCell> cells,
            BigDecimal lowestEffectiveUnitPrice,
            BigDecimal highestEffectiveUnitPrice,
            BigDecimal priceSpreadPercent) {
    }

    /** One quotation's offer for one requested item. {@code status} is MATCHED or MISSING. */
    public record ItemCell(
            Long quotationId,
            String status,
            Long quotationItemId,
            String quotedDescription,
            BigDecimal quotedQuantity,
            BigDecimal unitPrice,
            BigDecimal lineTotal,
            /** lineTotal / quotedQuantity: includes discount and tax, so vendors compare like for like. */
            BigDecimal effectiveUnitPrice,
            BigDecimal comparableLineTotal,
            BigDecimal matchConfidence,
            boolean quantityMismatch,
            boolean lowest,
            BigDecimal differenceFromLowest,
            BigDecimal differencePercent) {
    }

    /** A quoted line that matches no requested item. */
    public record UnmatchedItem(Long quotationId, String vendorName, Long quotationItemId, String description,
                                BigDecimal lineTotal) {
    }
}
