package com.sutra.procurement.response;

import com.sutra.procurement.entity.PurchaseRequest;
import com.sutra.procurement.entity.RequestItem;
import com.sutra.procurement.entity.enums.PurchaseRequestStatus;
import com.sutra.procurement.entity.enums.QuotationStatus;
import com.sutra.procurement.response.QuotationResponses.FileResponse;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * What a vendor sees in the vendor portal. Deliberately narrow: the RFQ and the vendor's own quotations only, never
 * other vendors, internal review flags, comparisons or who decided.
 */
public final class VendorPortalResponses {

    private VendorPortalResponses() {
    }

    /** How the vendor's quotation ended. */
    public enum Outcome {
        /** The RFQ is still being decided. */
        PENDING,
        /** The RFQ was awarded to this quotation. */
        AWARDED,
        /** The RFQ was awarded to another vendor. */
        NOT_AWARDED,
        /** The RFQ was cancelled or rejected without an award. */
        CLOSED
    }

    /** Where the quotation is, in words a vendor understands. */
    public enum Stage {
        RECEIVED, BEING_READ, UNDER_REVIEW, ACCEPTED_FOR_COMPARISON, COULD_NOT_BE_READ;

        public static Stage of(QuotationStatus status) {
            return switch (status) {
                case UPLOADED -> RECEIVED;
                case PROCESSING -> BEING_READ;
                case NEEDS_REVIEW -> UNDER_REVIEW;
                case READY_FOR_COMPARISON -> ACCEPTED_FOR_COMPARISON;
                case FAILED -> COULD_NOT_BE_READ;
            };
        }
    }

    public record RfqItem(Integer lineNumber, String itemCode, String description, BigDecimal quantity,
                          String unitOfMeasure, String specifications) {
        static RfqItem from(RequestItem i) {
            return new RfqItem(i.getLineNumber(), i.getItemCode(), i.getDescription(), i.getQuantity(),
                    i.getUnitOfMeasure(), i.getSpecifications());
        }
    }

    public record VendorQuotation(Long id, Long rfqId, String rfqNumber, String rfqTitle, String quotationNumber,
                                  Stage stage, BigDecimal grandTotal, String currency, LocalDate validUntil,
                                  boolean submittedByVendor, boolean canWithdraw, Outcome outcome,
                                  List<FileResponse> files, Instant submittedAt) {
    }

    public record RfqSummary(Long id, String rfqNumber, String title, String buyer, String currency,
                             LocalDate requiredBy, int itemCount, boolean openForQuotations, Instant publishedAt,
                             Long myQuotationId, Stage myQuotationStage, Outcome outcome, LocalDate quotationsDue) {
    }

    public record RfqDetail(Long id, String rfqNumber, String title, String description, String buyer,
                            String currency, LocalDate requiredBy, boolean openForQuotations, Instant publishedAt,
                            List<RfqItem> items, List<VendorQuotation> myQuotations, LocalDate quotationsDue) {

        public static RfqDetail of(PurchaseRequest pr, List<VendorQuotation> mine, java.time.LocalDate today) {
            return new RfqDetail(pr.getId(), pr.getRequestNumber(), pr.getTitle(), pr.getDescription(),
                    pr.getEntity().getName(), pr.getCurrency(), pr.getRequiredBy(),
                    pr.acceptsVendorQuotations(today), pr.getCreatedAt(),
                    pr.getItems().stream().map(RfqItem::from).toList(), mine, pr.getQuotationsDue());
        }
    }
}
