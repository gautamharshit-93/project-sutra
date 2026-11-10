package com.sutra.quotation.service;

import com.sutra.api.quotation.VendorPortalResponses.RfqDetail;
import com.sutra.api.quotation.VendorPortalResponses.RfqSummary;
import com.sutra.api.quotation.VendorPortalResponses.VendorQuotation;
import com.sutra.common.web.PageResponse;
import com.sutra.quotation.dto.response.DownloadedFile;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/** The vendor portal: open RFQs, the vendor's own quotations, submit, withdraw and its own files. */
public interface VendorPortalService {

    PageResponse<RfqSummary> rfqs(int page, int size);

    RfqDetail rfq(Long rfqId);

    List<VendorQuotation> myQuotations();

    /** Submits the vendor's quotation (one or more files) for an open RFQ; it is read in the background. */
    VendorQuotation submit(Long rfqId, List<MultipartFile> files);

    /** Withdraws the vendor's own quotation while the RFQ is open and before it was compared. */
    void withdraw(Long quotationId);

    DownloadedFile download(Long quotationId, Long fileId);
}
