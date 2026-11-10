package com.sutra.quotation.service;

import com.sutra.api.extraction.ExtractionChoice;
import com.sutra.api.internal.InternalContracts.RfqQuotationUsage;
import com.sutra.api.quotation.QuotationRequests.QuotationItemRequest;
import com.sutra.api.quotation.QuotationRequests.QuotationUpdateRequest;
import com.sutra.api.quotation.QuotationResponses.QuotationResponse;
import com.sutra.api.quotation.QuotationResponses.QuotationSummary;
import com.sutra.api.rfq.PurchaseRequestResponses.PurchaseRequestResponse;
import com.sutra.quotation.dto.response.DownloadedFile;
import com.sutra.quotation.entity.NamedRef;
import com.sutra.quotation.entity.Quotation;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/**
 * Quotation upload, review and manual correction. A quotation can consist of several files (e.g. the same offer as
 * PDF, Word and Excel, or a quotation plus a price annexure); they stay linked to it and are read together.
 */
public interface QuotationService {

    List<QuotationSummary> listForRequest(Long purchaseRequestId);

    QuotationResponse get(Long id);

    /** One original file (with bytes, from the document service); the access is audited. */
    DownloadedFile download(Long quotationId, Long fileId);

    /** A buyer uploads a vendor's (e-mailed) quotation; it is read in the background. */
    QuotationResponse upload(Long purchaseRequestId, Long vendorId, List<MultipartFile> files, ExtractionChoice choice);

    /** A vendor submits its own quotation (the caller checked the RFQ is open and the vendor has none on it). */
    Quotation submitForVendor(PurchaseRequestResponse rfq, NamedRef vendor, List<MultipartFile> files);

    QuotationResponse addFiles(Long quotationId, List<MultipartFile> files);

    QuotationResponse removeFile(Long quotationId, Long fileId);

    QuotationResponse reprocess(Long id, ExtractionChoice choice);

    QuotationResponse updateHeader(Long id, QuotationUpdateRequest request);

    QuotationResponse addItem(Long id, QuotationItemRequest request);

    QuotationResponse updateItem(Long id, Long itemId, QuotationItemRequest request);

    QuotationResponse deleteItem(Long id, Long itemId);

    QuotationResponse resolveFlag(Long id, Long flagId, String comment);

    void delete(Long id);

    Quotation find(Long id);

    QuotationResponse toResponse(Quotation q);

    /** Re-checks the RFQ's read quotations after the RFQ changed (items, currency). */
    void revalidateForRfq(Long rfqId);

    RfqQuotationUsage usageForRfq(Long rfqId);
}
