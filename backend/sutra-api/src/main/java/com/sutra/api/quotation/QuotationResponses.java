package com.sutra.api.quotation;

import com.sutra.api.enums.ExtractionSource;
import com.sutra.api.enums.FileFormat;
import com.sutra.api.enums.FlagSeverity;
import com.sutra.api.enums.QuotationStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

public final class QuotationResponses {

    private QuotationResponses() {
    }

    public record FileResponse(Long id, String fileName, String contentType, FileFormat fileFormat,
                               long sizeBytes, String sha256, Instant uploadedAt) {
    }

    public record QuotationItemResponse(Long id, Integer lineNumber, Long requestItemId,
                                        Integer requestItemLineNumber, String itemCode, String description,
                                        BigDecimal quantity,
                                        String unitOfMeasure, BigDecimal unitPrice, BigDecimal discountPercent,
                                        BigDecimal discountAmount, BigDecimal taxPercent, BigDecimal taxAmount,
                                        BigDecimal lineTotal, BigDecimal statedLineTotal,
                                        BigDecimal matchConfidence, BigDecimal extractionConfidence,
                                        boolean manuallyEdited, String notes) {
    }

    public record QuotationFlagResponse(Long id, Long quotationItemId, String fieldName, FlagSeverity severity,
                                        String code, String message, boolean resolved, String resolvedBy,
                                        Instant resolvedAt, Instant createdAt) {
    }

    public record QuotationSummary(Long id, Long purchaseRequestId, String requestNumber, Long vendorId,
                                   String vendorName, String quotationNumber, QuotationStatus status,
                                   String currency, BigDecimal grandTotal, LocalDate validUntil,
                                   ExtractionSource extractionSource, BigDecimal extractionConfidence,
                                   long openFlags, int itemCount, String failureReason, long fileCount,
                                   String uploadedBy, boolean submittedByVendor, Instant createdAt) {
    }

    public record QuotationResponse(Long id, Long purchaseRequestId, String requestNumber, Long vendorId,
                                    String vendorName, String quotationNumber, LocalDate quotationDate,
                                    LocalDate validUntil, String currency, QuotationStatus status,
                                    BigDecimal subtotal, BigDecimal discountTotal, BigDecimal taxTotal,
                                    BigDecimal grandTotal, BigDecimal statedSubtotal, BigDecimal statedTaxTotal,
                                    BigDecimal statedGrandTotal, String deliveryTerms, String paymentTerms,
                                    String warrantyTerms, String notes, ExtractionSource extractionSource,
                                    BigDecimal extractionConfidence, String failureReason, int processingAttempts,
                                    Instant lastProcessedAt, String uploadedBy, boolean submittedByVendor,
                                    Long version, boolean editable, List<FileResponse> files,
                                    List<QuotationItemResponse> items,
                                    List<QuotationFlagResponse> flags, Instant createdAt, Instant updatedAt) {
    }
}
