package com.sutra.entity;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** An immutable snapshot of a side-by-side comparison. A new version is created on every regeneration. */
@Entity
@Table(name = "comparison_report")
@Getter
@Setter
public class ComparisonReport extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "purchase_request_id", nullable = false)
    private PurchaseRequest purchaseRequest;

    @Column(name = "report_version", nullable = false)
    private Integer reportVersion;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "generated_by", nullable = false)
    private AppUser generatedBy;

    @Column(name = "generated_at", nullable = false)
    private Instant generatedAt = Instant.now();

    /** Lowest grand total among quotations that cover every requested item. Informational only - never an award. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "lowest_complete_quotation_id")
    private Quotation lowestCompleteQuotation;

    /** Full comparison matrix serialised from {@code ComparisonResult}. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private String details;

    @OneToMany(mappedBy = "comparisonReport", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<ComparisonQuotation> quotations = new ArrayList<>();
}
