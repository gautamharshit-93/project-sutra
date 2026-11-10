package com.sutra.quotation.entity;

import com.sutra.quotation.entity.base.AuditableEntity;
import com.sutra.api.enums.ExtractionSource;
import com.sutra.api.enums.QuotationStatus;
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
import jakarta.persistence.Version;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import lombok.Getter;
import lombok.Setter;

/**
 * A vendor's quotation against a purchase request. Its original files are kept by the document service and
 * referenced from {@link QuotationFile}; extracted data lives in
 * {@link QuotationItem} rows and review flags in {@link QuotationFlag}.
 */
@Entity
@Table(name = "quotation")
@Getter
@Setter
public class Quotation extends AuditableEntity {

    /** The RFQ (owned by the RFQ service; a cross-schema foreign key keeps it valid). */
    @Column(name = "purchase_request_id", nullable = false, updatable = false)
    private Long purchaseRequestId;

    /** The RFQ's number, kept so lists never need the RFQ service just to show it (it never changes). */
    @Column(name = "request_number", nullable = false, length = 30, updatable = false)
    private String requestNumber;

    @Embedded
    @AttributeOverride(name = "id", column = @Column(name = "vendor_id", nullable = false))
    @AttributeOverride(name = "name", column = @Column(name = "vendor_name", nullable = false))
    private NamedRef vendor;

    @Column(name = "quotation_number", length = 60)
    private String quotationNumber;

    @Column(name = "quotation_date")
    private LocalDate quotationDate;

    @Column(name = "valid_until")
    private LocalDate validUntil;

    @Column(length = 3)
    private String currency;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 25)
    private QuotationStatus status = QuotationStatus.UPLOADED;

    @Column(precision = 18, scale = 2)
    private BigDecimal subtotal;

    @Column(name = "discount_total", precision = 18, scale = 2)
    private BigDecimal discountTotal;

    @Column(name = "tax_total", precision = 18, scale = 2)
    private BigDecimal taxTotal;

    @Column(name = "grand_total", precision = 18, scale = 2)
    private BigDecimal grandTotal;

    @Column(name = "stated_subtotal", precision = 18, scale = 2)
    private BigDecimal statedSubtotal;

    @Column(name = "stated_tax_total", precision = 18, scale = 2)
    private BigDecimal statedTaxTotal;

    @Column(name = "stated_grand_total", precision = 18, scale = 2)
    private BigDecimal statedGrandTotal;

    @Column(name = "delivery_terms", columnDefinition = "text")
    private String deliveryTerms;

    @Column(name = "payment_terms", columnDefinition = "text")
    private String paymentTerms;

    @Column(name = "warranty_terms", columnDefinition = "text")
    private String warrantyTerms;

    @Column(columnDefinition = "text")
    private String notes;

    @Enumerated(EnumType.STRING)
    @Column(name = "extraction_source", length = 20)
    private ExtractionSource extractionSource;

    @Column(name = "extraction_confidence", precision = 4, scale = 3)
    private BigDecimal extractionConfidence;

    @Column(name = "failure_reason", columnDefinition = "text")
    private String failureReason;

    @Column(name = "processing_attempts", nullable = false)
    private int processingAttempts;

    @Column(name = "last_processed_at")
    private Instant lastProcessedAt;

    @Embedded
    @AttributeOverride(name = "id", column = @Column(name = "uploaded_by_id", nullable = false))
    @AttributeOverride(name = "username", column = @Column(name = "uploaded_by_name", nullable = false))
    private UserRef uploadedBy;

    /** True when the vendor submitted it through the vendor portal; false when a buyer uploaded it. */
    @Column(name = "submitted_by_vendor", nullable = false)
    private boolean submittedByVendor;

    /** Optimistic-lock version; also recorded in comparison reports to show which data was compared. */
    @Version
    @Column(nullable = false)
    private Long version;

    @OneToMany(mappedBy = "quotation", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("lineNumber")
    private List<QuotationItem> items = new ArrayList<>();

    @OneToMany(mappedBy = "quotation", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("id")
    private List<QuotationFlag> flags = new ArrayList<>();

    public void addItem(QuotationItem item) {
        item.setQuotation(this);
        items.add(item);
    }

    public void addFlag(QuotationFlag flag) {
        flag.setQuotation(this);
        flags.add(flag);
    }

    public int nextLineNumber() {
        return items.stream().mapToInt(QuotationItem::getLineNumber).max().orElse(0) + 1;
    }

    public long openFlagCount() {
        return flags.stream().filter(f -> !f.isResolved()).count();
    }
}
