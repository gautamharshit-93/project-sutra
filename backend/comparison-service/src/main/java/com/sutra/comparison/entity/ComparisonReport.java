package com.sutra.comparison.entity;

import jakarta.persistence.CascadeType;
import com.sutra.comparison.entity.base.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Embedded;
import jakarta.persistence.AttributeOverride;
import jakarta.persistence.Entity;
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

    @Column(name = "purchase_request_id", nullable = false, updatable = false)
    private Long purchaseRequestId;

    @Column(name = "report_version", nullable = false)
    private Integer reportVersion;

    @Embedded
    @AttributeOverride(name = "id", column = @Column(name = "generated_by_id", nullable = false))
    @AttributeOverride(name = "username", column = @Column(name = "generated_by_name", nullable = false))
    private UserRef generatedBy;

    @Column(name = "generated_at", nullable = false)
    private Instant generatedAt = Instant.now();

    /** Lowest grand total among quotations that cover every requested item. Informational only - never an award. */
    @Column(name = "lowest_complete_quotation_id")
    private Long lowestCompleteQuotationId;

    /** Full comparison matrix serialised from {@code ComparisonResult}. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private String details;

    @OneToMany(mappedBy = "comparisonReport", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<ComparisonQuotation> quotations = new ArrayList<>();
}
