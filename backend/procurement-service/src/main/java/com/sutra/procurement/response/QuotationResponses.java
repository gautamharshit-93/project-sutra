package com.sutra.procurement.response;

import com.sutra.procurement.calculation.ExtractionQualityGrader;
import com.sutra.procurement.calculation.GradeReport;
import com.sutra.procurement.entity.Quotation;
import com.sutra.procurement.entity.QuotationFlag;
import com.sutra.procurement.entity.QuotationItem;
import com.sutra.procurement.entity.enums.ExtractionSource;
import com.sutra.procurement.entity.enums.FileFormat;
import com.sutra.procurement.entity.enums.FlagSeverity;
import com.sutra.procurement.entity.enums.QuotationStatus;
import com.sutra.procurement.repository.QuotationFileMetadata;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

public final class QuotationResponses {

    private QuotationResponses() {
    }

    public record FileResponse(Long id, String fileName, String contentType, FileFormat fileFormat,
                               long sizeBytes, String sha256, Instant uploadedAt) {
        public static FileResponse from(QuotationFileMetadata m) {
            return m == null ? null : new FileResponse(m.id(), m.fileName(), m.contentType(), m.fileFormat(),
                    m.sizeBytes(), m.sha256(), m.uploadedAt());
        }
    }

    public record QuotationItemResponse(Long id, Integer lineNumber, Long requestItemId,
                                        Integer requestItemLineNumber, String itemCode, String description,
                                        BigDecimal quantity,
                                        String unitOfMeasure, BigDecimal unitPrice, BigDecimal discountPercent,
                                        BigDecimal discountAmount, BigDecimal taxPercent, BigDecimal taxAmount,
                                        BigDecimal lineTotal, BigDecimal statedLineTotal,
                                        BigDecimal matchConfidence, BigDecimal extractionConfidence,
                                        boolean manuallyEdited, String notes) {
        public static QuotationItemResponse from(QuotationItem i) {
            return new QuotationItemResponse(i.getId(), i.getLineNumber(),
                    i.getRequestItem() == null ? null : i.getRequestItem().getId(),
                    i.getRequestItem() == null ? null : i.getRequestItem().getLineNumber(),
                    i.getItemCode(), i.getDescription(), i.getQuantity(), i.getUnitOfMeasure(), i.getUnitPrice(),
                    i.getDiscountPercent(), i.getDiscountAmount(), i.getTaxPercent(), i.getTaxAmount(),
                    i.getLineTotal(), i.getStatedLineTotal(), i.getMatchConfidence(), i.getExtractionConfidence(),
                    i.isManuallyEdited(), i.getNotes());
        }
    }

    public record QuotationFlagResponse(Long id, Long quotationItemId, String fieldName, FlagSeverity severity,
                                        String code, String message, boolean resolved, String resolvedBy,
                                        Instant resolvedAt, Instant createdAt) {
        public static QuotationFlagResponse from(QuotationFlag f) {
            return new QuotationFlagResponse(f.getId(),
                    f.getQuotationItem() == null ? null : f.getQuotationItem().getId(),
                    f.getFieldName(), f.getSeverity(), f.getCode(), f.getMessage(), f.isResolved(),
                    f.getResolvedBy() == null ? null : f.getResolvedBy().getUsername(),
                    f.getResolvedAt(), f.getCreatedAt());
        }
    }

    public record QuotationSummary(Long id, Long purchaseRequestId, String requestNumber, Long vendorId,
                                   String vendorName, String quotationNumber, QuotationStatus status,
                                   String currency, BigDecimal grandTotal, LocalDate validUntil,
                                   ExtractionSource extractionSource, BigDecimal extractionConfidence,
                                   long openFlags, int itemCount, String failureReason, long fileCount,
                                   String uploadedBy, boolean submittedByVendor, Instant createdAt) {
        public static QuotationSummary from(Quotation q, long fileCount) {
            return new QuotationSummary(q.getId(), q.getPurchaseRequest().getId(),
                    q.getPurchaseRequest().getRequestNumber(), q.getVendor().getId(), q.getVendor().getName(),
                    q.getQuotationNumber(), q.getStatus(), q.getCurrency(), q.getGrandTotal(), q.getValidUntil(),
                    q.getExtractionSource(), q.getExtractionConfidence(), q.openFlagCount(), q.getItems().size(),
                    q.getFailureReason(), fileCount, q.getUploadedBy().getUsername(), q.isSubmittedByVendor(),
                    q.getCreatedAt());
        }
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
                                    List<QuotationFlagResponse> flags, Instant createdAt, Instant updatedAt,
                                    GradeReport extractionQuality) {
        public static QuotationResponse from(Quotation q, List<QuotationFileMetadata> files) {
            return new QuotationResponse(q.getId(), q.getPurchaseRequest().getId(),
                    q.getPurchaseRequest().getRequestNumber(), q.getVendor().getId(), q.getVendor().getName(),
                    q.getQuotationNumber(), q.getQuotationDate(), q.getValidUntil(), q.getCurrency(), q.getStatus(),
                    q.getSubtotal(), q.getDiscountTotal(), q.getTaxTotal(), q.getGrandTotal(), q.getStatedSubtotal(),
                    q.getStatedTaxTotal(), q.getStatedGrandTotal(), q.getDeliveryTerms(), q.getPaymentTerms(),
                    q.getWarrantyTerms(), q.getNotes(), q.getExtractionSource(), q.getExtractionConfidence(),
                    q.getFailureReason(), q.getProcessingAttempts(), q.getLastProcessedAt(),
                    q.getUploadedBy().getUsername(), q.isSubmittedByVendor(), q.getVersion(),
                    q.getStatus() != QuotationStatus.PROCESSING && q.getPurchaseRequest().isEditable(),
                    files.stream().map(FileResponse::from).toList(),
                    q.getItems().stream().map(QuotationItemResponse::from).toList(),
                    q.getFlags().stream().map(QuotationFlagResponse::from).toList(),
                    q.getCreatedAt(), q.getUpdatedAt(), extractionQuality(q));
        }

        /** Graded once data exists (extracted or typed in); null while uploaded, processing or failed empty. */
        private static GradeReport extractionQuality(Quotation q) {
            boolean hasData = !q.getItems().isEmpty() || q.getExtractionSource() != null;
            boolean settled = q.getStatus() != QuotationStatus.UPLOADED && q.getStatus() != QuotationStatus.PROCESSING;
            return hasData && settled ? ExtractionQualityGrader.grade(q) : null;
        }
    }
}
