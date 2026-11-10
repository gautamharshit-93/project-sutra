package com.sutra.api.rfq;

import com.sutra.api.enums.PurchaseRequestStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

public final class PurchaseRequestResponses {

    private PurchaseRequestResponses() {
    }

    public record RequestItemResponse(Long id, Integer lineNumber, String itemCode, String description,
                                      BigDecimal quantity, String unitOfMeasure, String specifications) {
    }

    public record PurchaseRequestSummary(Long id, String requestNumber, String title, PurchaseRequestStatus status,
                                         String entityName, String requestedBy, String currency,
                                         LocalDate requiredBy, int itemCount, Instant createdAt,
                                         LocalDate quotationsDue) {
    }

    public record PurchaseRequestResponse(Long id, String requestNumber, String title, String description,
                                          PurchaseRequestStatus status, Long entityId, String entityName,
                                          Long subEntityId, String subEntityName, Long customerId,
                                          String customerName, Long requestedById, String requestedBy,
                                          String currency, LocalDate requiredBy, boolean editable,
                                          List<RequestItemResponse> items, Instant createdAt, Instant updatedAt,
                                          LocalDate quotationsDue) {
    }
}
