package com.sutra.rfq.entity;

import com.sutra.rfq.entity.base.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name = "request_item")
@Getter
@Setter
public class RequestItem extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "purchase_request_id", nullable = false)
    private PurchaseRequest purchaseRequest;

    @Column(name = "line_number", nullable = false)
    private Integer lineNumber;

    /** Optional buyer item code (e.g. IT-001); vendors quoting the same code are matched exactly. */
    @Column(name = "item_code", length = 40)
    private String itemCode;

    @Column(nullable = false, length = 500)
    private String description;

    @Column(nullable = false, precision = 15, scale = 3)
    private BigDecimal quantity;

    @Column(name = "unit_of_measure", nullable = false, length = 30)
    private String unitOfMeasure;

    @Column(columnDefinition = "text")
    private String specifications;
}
