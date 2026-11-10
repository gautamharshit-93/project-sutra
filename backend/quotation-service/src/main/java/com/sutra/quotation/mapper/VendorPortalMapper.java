package com.sutra.quotation.mapper;

import com.sutra.api.enums.PurchaseRequestStatus;
import com.sutra.api.quotation.VendorPortalResponses.RfqDetail;
import com.sutra.api.quotation.VendorPortalResponses.RfqItem;
import com.sutra.api.quotation.VendorPortalResponses.VendorQuotation;
import com.sutra.api.rfq.PurchaseRequestResponses.PurchaseRequestResponse;
import com.sutra.api.rfq.PurchaseRequestResponses.RequestItemResponse;

import java.time.LocalDate;
import java.util.List;

/** Maps RFQs to what a vendor sees of them ({@code VendorPortalResponses}). */
public final class VendorPortalMapper {

    private VendorPortalMapper() {
    }

    /** Open for quotations: OPEN, and the quotations-due date (if any) has not passed. */
    public static boolean acceptsVendorQuotations(PurchaseRequestResponse rfq, LocalDate today) {
        return rfq.status() == PurchaseRequestStatus.OPEN && (rfq.quotationsDue() == null || !today.isAfter(rfq.quotationsDue()));
    }

    public static RfqDetail rfqDetail(PurchaseRequestResponse rfq, List<VendorQuotation> mine, LocalDate today) {
        return new RfqDetail(rfq.id(), rfq.requestNumber(), rfq.title(), rfq.description(), rfq.entityName(),
                rfq.currency(), rfq.requiredBy(), acceptsVendorQuotations(rfq, today), rfq.createdAt(),
                rfq.items().stream().map(VendorPortalMapper::rfqItem).toList(), mine, rfq.quotationsDue());
    }

    public static RfqItem rfqItem(RequestItemResponse i) {
        return new RfqItem(i.lineNumber(), i.itemCode(), i.description(), i.quantity(), i.unitOfMeasure(),
                i.specifications());
    }
}
