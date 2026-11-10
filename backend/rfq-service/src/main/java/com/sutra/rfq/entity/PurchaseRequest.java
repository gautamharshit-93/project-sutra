package com.sutra.rfq.entity;

import com.sutra.rfq.entity.base.AuditableEntity;
import com.sutra.api.enums.PurchaseRequestStatus;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Embedded;
import jakarta.persistence.AttributeOverride;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name = "purchase_request")
@Getter
@Setter
public class PurchaseRequest extends AuditableEntity {

    /** Statuses in which items, quotations and comparisons may still change. */
    private static final Set<PurchaseRequestStatus> EDITABLE =
            EnumSet.of(PurchaseRequestStatus.DRAFT, PurchaseRequestStatus.OPEN, PurchaseRequestStatus.UNDER_REVIEW);

    @Column(name = "request_number", nullable = false, unique = true, length = 30)
    private String requestNumber;

    @Column(nullable = false, length = 200)
    private String title;

    @Column(columnDefinition = "text")
    private String description;

    @Embedded
    @AttributeOverride(name = "id", column = @Column(name = "entity_id", nullable = false))
    @AttributeOverride(name = "name", column = @Column(name = "entity_name", nullable = false))
    private NamedRef entity;

    @Embedded
    @AttributeOverride(name = "id", column = @Column(name = "sub_entity_id"))
    @AttributeOverride(name = "name", column = @Column(name = "sub_entity_name"))
    private NamedRef subEntity;

    @Embedded
    @AttributeOverride(name = "id", column = @Column(name = "customer_id"))
    @AttributeOverride(name = "name", column = @Column(name = "customer_name"))
    private NamedRef customer;

    @Embedded
    @AttributeOverride(name = "id", column = @Column(name = "requested_by_id", nullable = false))
    @AttributeOverride(name = "username", column = @Column(name = "requested_by_name", nullable = false))
    private UserRef requestedBy;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private PurchaseRequestStatus status = PurchaseRequestStatus.DRAFT;

    @Column(nullable = false, length = 3)
    private String currency = "INR";

    @Column(name = "required_by")
    private LocalDate requiredBy;

    /** Last day vendors may send quotations through the portal (inclusive); null = until the RFQ is compared. */
    @Column(name = "quotations_due")
    private LocalDate quotationsDue;

    @OneToMany(mappedBy = "purchaseRequest", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("lineNumber")
    private List<RequestItem> items = new ArrayList<>();

    public boolean isEditable() {
        return EDITABLE.contains(status);
    }

    public void addItem(RequestItem item) {
        item.setPurchaseRequest(this);
        items.add(item);
    }

    public int nextLineNumber() {
        return items.stream().mapToInt(RequestItem::getLineNumber).max().orElse(0) + 1;
    }

    /** Vendors may submit while the RFQ is open and its due date (if any) has not passed. */
    public boolean acceptsVendorQuotations(LocalDate today) {
        return status == PurchaseRequestStatus.OPEN && (quotationsDue == null || !today.isAfter(quotationsDue));
    }
}
