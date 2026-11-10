package com.sutra.comparison.support;

import com.sutra.api.enums.PurchaseRequestStatus;
import com.sutra.api.rfq.PurchaseRequestResponses.PurchaseRequestResponse;
import com.sutra.api.rfq.PurchaseRequestResponses.RequestItemResponse;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;

/**
 * A changeable RFQ for tests, as the RFQ service would return it ({@link #toResponse()}). Tests stub
 * {@code RfqClient.get} with {@code toResponse()}, so a status set here is what the quotation service sees.
 */
@Getter
@Setter
public class TestRfq {

    private Long id;
    private String requestNumber = "PR-2026-00001";
    private String title = "Laptops for new joiners";
    private Long entityId = 1L;
    private String entityName = "Head Office";
    private Long requestedById;
    private String requestedBy;
    private PurchaseRequestStatus status = PurchaseRequestStatus.OPEN;
    private String currency = "INR";
    private LocalDate requiredBy;
    private LocalDate quotationsDue;
    private Instant createdAt = Instant.parse("2026-10-30T06:00:00Z");
    private final List<Item> items = new ArrayList<>();

    /** A requested line. */
    @Getter
    @Setter
    public static class Item {
        private Long id;
        private Integer lineNumber;
        private String itemCode;
        private String description;
        private BigDecimal quantity;
        private String unitOfMeasure = "Nos";
        private String specifications;

        RequestItemResponse toResponse() {
            return new RequestItemResponse(id, lineNumber, itemCode, description, quantity, unitOfMeasure, specifications);
        }
    }

    public PurchaseRequestResponse toResponse() {
        boolean editable = EnumSet.of(PurchaseRequestStatus.DRAFT, PurchaseRequestStatus.OPEN,
                PurchaseRequestStatus.UNDER_REVIEW).contains(status);
        return new PurchaseRequestResponse(id, requestNumber, title, null, status, entityId, entityName, null, null,
                null, null, requestedById, requestedBy, currency, requiredBy, editable,
                items.stream().map(Item::toResponse).toList(), createdAt, createdAt, quotationsDue);
    }
}
