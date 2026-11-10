package com.sutra.rfq.mapper;

import com.sutra.api.rfq.PurchaseRequestResponses.PurchaseRequestResponse;
import com.sutra.api.rfq.PurchaseRequestResponses.PurchaseRequestSummary;
import com.sutra.api.rfq.PurchaseRequestResponses.RequestItemResponse;
import com.sutra.rfq.entity.PurchaseRequest;
import com.sutra.rfq.entity.RequestItem;

/** Maps this service's entities to the API records of {@code PurchaseRequestResponses}. */
public final class PurchaseRequestMapper {

    private PurchaseRequestMapper() {
    }

    public static PurchaseRequestResponse purchaseRequestResponse(PurchaseRequest p) {
        return new PurchaseRequestResponse(p.getId(), p.getRequestNumber(), p.getTitle(), p.getDescription(),
                p.getStatus(), p.getEntity().getId(), p.getEntity().getName(),
                p.getSubEntity() == null ? null : p.getSubEntity().getId(),
                p.getSubEntity() == null ? null : p.getSubEntity().getName(),
                p.getCustomer() == null ? null : p.getCustomer().getId(),
                p.getCustomer() == null ? null : p.getCustomer().getName(),
                p.getRequestedBy().getId(), p.getRequestedBy().getUsername(), p.getCurrency(),
                p.getRequiredBy(), p.isEditable(),
                p.getItems().stream().map(PurchaseRequestMapper::requestItemResponse).toList(),
                p.getCreatedAt(), p.getUpdatedAt(), p.getQuotationsDue());
    }

    public static PurchaseRequestSummary purchaseRequestSummary(PurchaseRequest p) {
        return new PurchaseRequestSummary(p.getId(), p.getRequestNumber(), p.getTitle(), p.getStatus(),
                p.getEntity().getName(), p.getRequestedBy().getUsername(), p.getCurrency(), p.getRequiredBy(),
                p.getItems().size(), p.getCreatedAt(), p.getQuotationsDue());
    }

    public static RequestItemResponse requestItemResponse(RequestItem i) {
        return new RequestItemResponse(i.getId(), i.getLineNumber(), i.getItemCode(), i.getDescription(),
                i.getQuantity(),
                i.getUnitOfMeasure(), i.getSpecifications());
    }
}
