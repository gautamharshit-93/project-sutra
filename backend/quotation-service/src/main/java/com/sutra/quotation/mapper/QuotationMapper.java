package com.sutra.quotation.mapper;

import com.sutra.api.enums.QuotationStatus;
import com.sutra.api.quotation.QuotationResponses.FileResponse;
import com.sutra.api.quotation.QuotationResponses.QuotationFlagResponse;
import com.sutra.api.quotation.QuotationResponses.QuotationItemResponse;
import com.sutra.api.quotation.QuotationResponses.QuotationResponse;
import com.sutra.api.quotation.QuotationResponses.QuotationSummary;
import com.sutra.api.rfq.PurchaseRequestResponses.PurchaseRequestResponse;
import com.sutra.api.rfq.PurchaseRequestResponses.RequestItemResponse;
import com.sutra.quotation.entity.Quotation;
import com.sutra.quotation.entity.QuotationFile;
import com.sutra.quotation.entity.QuotationFlag;
import com.sutra.quotation.entity.QuotationItem;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/** Maps quotations to the API records of {@code QuotationResponses}. */
public final class QuotationMapper {

    private QuotationMapper() {
    }

    /** {@code rfq} gives the requested lines' numbers and whether the RFQ still accepts changes. */
    public static QuotationResponse quotationResponse(Quotation q, List<QuotationFile> files, PurchaseRequestResponse rfq) {
        Map<Long, Integer> lineOf = rfq.items().stream()
                .collect(Collectors.toMap(RequestItemResponse::id, RequestItemResponse::lineNumber));
        return new QuotationResponse(q.getId(), q.getPurchaseRequestId(), q.getRequestNumber(), q.getVendor().getId(),
                q.getVendor().getName(), q.getQuotationNumber(), q.getQuotationDate(), q.getValidUntil(), q.getCurrency(),
                q.getStatus(), q.getSubtotal(), q.getDiscountTotal(), q.getTaxTotal(), q.getGrandTotal(),
                q.getStatedSubtotal(), q.getStatedTaxTotal(), q.getStatedGrandTotal(), q.getDeliveryTerms(),
                q.getPaymentTerms(), q.getWarrantyTerms(), q.getNotes(), q.getExtractionSource(),
                q.getExtractionConfidence(), q.getFailureReason(), q.getProcessingAttempts(), q.getLastProcessedAt(),
                q.getUploadedBy().getUsername(), q.isSubmittedByVendor(), q.getVersion(),
                q.getStatus() != QuotationStatus.PROCESSING && rfq.editable(),
                files.stream().map(QuotationMapper::fileResponse).toList(),
                q.getItems().stream().map(i -> quotationItemResponse(i, lineOf)).toList(),
                q.getFlags().stream().map(QuotationMapper::quotationFlagResponse).toList(),
                q.getCreatedAt(), q.getUpdatedAt());
    }

    public static QuotationSummary quotationSummary(Quotation q, long fileCount) {
        return new QuotationSummary(q.getId(), q.getPurchaseRequestId(), q.getRequestNumber(), q.getVendor().getId(),
                q.getVendor().getName(), q.getQuotationNumber(), q.getStatus(), q.getCurrency(), q.getGrandTotal(),
                q.getValidUntil(), q.getExtractionSource(), q.getExtractionConfidence(), q.openFlagCount(),
                q.getItems().size(), q.getFailureReason(), fileCount, q.getUploadedBy().getUsername(),
                q.isSubmittedByVendor(), q.getCreatedAt());
    }

    public static QuotationFlagResponse quotationFlagResponse(QuotationFlag f) {
        return new QuotationFlagResponse(f.getId(), f.getQuotationItem() == null ? null : f.getQuotationItem().getId(),
                f.getFieldName(), f.getSeverity(), f.getCode(), f.getMessage(), f.isResolved(),
                f.getResolvedBy() == null ? null : f.getResolvedBy().getUsername(), f.getResolvedAt(), f.getCreatedAt());
    }

    public static QuotationItemResponse quotationItemResponse(QuotationItem i, Map<Long, Integer> requestLineOf) {
        return new QuotationItemResponse(i.getId(), i.getLineNumber(), i.getRequestItemId(),
                i.getRequestItemId() == null ? null : requestLineOf.get(i.getRequestItemId()),
                i.getItemCode(), i.getDescription(), i.getQuantity(), i.getUnitOfMeasure(), i.getUnitPrice(),
                i.getDiscountPercent(), i.getDiscountAmount(), i.getTaxPercent(), i.getTaxAmount(),
                i.getLineTotal(), i.getStatedLineTotal(), i.getMatchConfidence(), i.getExtractionConfidence(),
                i.isManuallyEdited(), i.getNotes());
    }

    public static FileResponse fileResponse(QuotationFile f) {
        return new FileResponse(f.getId(), f.getFileName(), f.getContentType(), f.getFileFormat(), f.getSizeBytes(),
                f.getSha256(), f.getUploadedAt());
    }
}
