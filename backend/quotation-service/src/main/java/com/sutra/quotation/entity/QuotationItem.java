package com.sutra.quotation.entity;

import com.sutra.quotation.entity.base.BaseEntity;
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
@Table(name = "quotation_item")
@Getter
@Setter
public class QuotationItem extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "quotation_id", nullable = false)
    private Quotation quotation;

    /** The purchase-request line this item was matched to; null when unmatched. */
    @Column(name = "request_item_id")
    private Long requestItemId;

    @Column(name = "line_number", nullable = false)
    private Integer lineNumber;

    @Column(name = "item_code", length = 40)
    private String itemCode;

    @Column(nullable = false, length = 500)
    private String description;

    @Column(precision = 15, scale = 3)
    private BigDecimal quantity;

    @Column(name = "unit_of_measure", length = 30)
    private String unitOfMeasure;

    @Column(name = "unit_price", precision = 18, scale = 4)
    private BigDecimal unitPrice;

    @Column(name = "discount_percent", precision = 7, scale = 4)
    private BigDecimal discountPercent;

    @Column(name = "discount_amount", precision = 18, scale = 2)
    private BigDecimal discountAmount;

    @Column(name = "tax_percent", precision = 7, scale = 4)
    private BigDecimal taxPercent;

    @Column(name = "tax_amount", precision = 18, scale = 2)
    private BigDecimal taxAmount;

    /** Calculated by the backend - authoritative. */
    @Column(name = "line_total", precision = 18, scale = 2)
    private BigDecimal lineTotal;

    /** As printed on the vendor document - compared against {@link #lineTotal}. */
    @Column(name = "stated_line_total", precision = 18, scale = 2)
    private BigDecimal statedLineTotal;

    @Column(name = "match_confidence", precision = 4, scale = 3)
    private BigDecimal matchConfidence;

    @Column(name = "extraction_confidence", precision = 4, scale = 3)
    private BigDecimal extractionConfidence;

    @Column(name = "manually_edited", nullable = false)
    private boolean manuallyEdited;

    @Column(columnDefinition = "text")
    private String notes;
}
