package com.sutra.procurement.response;

import com.sutra.procurement.entity.PurchaseRequest;
import com.sutra.procurement.entity.RequestItem;
import com.sutra.procurement.entity.enums.PurchaseRequestStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

public final class PurchaseRequestResponses {

    private PurchaseRequestResponses() {
    }

    public record RequestItemResponse(Long id, Integer lineNumber, String itemCode, String description,
                                      BigDecimal quantity, String unitOfMeasure, String specifications) {
        public static RequestItemResponse from(RequestItem i) {
            return new RequestItemResponse(i.getId(), i.getLineNumber(), i.getItemCode(), i.getDescription(),
                    i.getQuantity(),
                    i.getUnitOfMeasure(), i.getSpecifications());
        }
    }

    public record PurchaseRequestSummary(Long id, String requestNumber, String title, PurchaseRequestStatus status,
                                         String entityName, String requestedBy, String currency,
                                         LocalDate requiredBy, int itemCount, Instant createdAt,
                                         LocalDate quotationsDue) {
        public static PurchaseRequestSummary from(PurchaseRequest p) {
            return new PurchaseRequestSummary(p.getId(), p.getRequestNumber(), p.getTitle(), p.getStatus(),
                    p.getEntity().getName(), p.getRequestedBy().getUsername(), p.getCurrency(), p.getRequiredBy(),
                    p.getItems().size(), p.getCreatedAt(), p.getQuotationsDue());
        }
    }

    public record PurchaseRequestResponse(Long id, String requestNumber, String title, String description,
                                          PurchaseRequestStatus status, Long entityId, String entityName,
                                          Long subEntityId, String subEntityName, Long customerId,
                                          String customerName, Long requestedById, String requestedBy,
                                          String currency, LocalDate requiredBy, boolean editable,
                                          List<RequestItemResponse> items, Instant createdAt, Instant updatedAt,
                                          LocalDate quotationsDue) {
        public static PurchaseRequestResponse from(PurchaseRequest p) {
            return new PurchaseRequestResponse(p.getId(), p.getRequestNumber(), p.getTitle(), p.getDescription(),
                    p.getStatus(), p.getEntity().getId(), p.getEntity().getName(),
                    p.getSubEntity() == null ? null : p.getSubEntity().getId(),
                    p.getSubEntity() == null ? null : p.getSubEntity().getName(),
                    p.getCustomer() == null ? null : p.getCustomer().getId(),
                    p.getCustomer() == null ? null : p.getCustomer().getName(),
                    p.getRequestedBy().getId(), p.getRequestedBy().getUsername(), p.getCurrency(),
                    p.getRequiredBy(), p.isEditable(),
                    p.getItems().stream().map(RequestItemResponse::from).toList(),
                    p.getCreatedAt(), p.getUpdatedAt(), p.getQuotationsDue());
        }
    }
}
