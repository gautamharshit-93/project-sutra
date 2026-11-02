package com.sutra.entity;

import com.sutra.entity.enums.PurchaseRequestStatus;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
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

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "entity_id", nullable = false)
    private BusinessEntity entity;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "sub_entity_id")
    private SubEntity subEntity;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "customer_id")
    private Customer customer;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "requested_by", nullable = false)
    private AppUser requestedBy;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private PurchaseRequestStatus status = PurchaseRequestStatus.DRAFT;

    @Column(nullable = false, length = 3)
    private String currency = "INR";

    @Column(name = "required_by")
    private LocalDate requiredBy;

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
}
