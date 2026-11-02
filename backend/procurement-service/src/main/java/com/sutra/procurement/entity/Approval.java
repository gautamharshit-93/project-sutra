package com.sutra.procurement.entity;

import com.sutra.procurement.entity.enums.ApprovalDecision;
import jakarta.persistence.Column;
import jakarta.persistence.Embedded;
import jakarta.persistence.AttributeOverride;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.Getter;
import lombok.Setter;

/** A human approval or rejection decision. Only ever created by an authorised user - never by AI. */
@Entity
@Table(name = "approval")
@Getter
@Setter
public class Approval extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "purchase_request_id", nullable = false)
    private PurchaseRequest purchaseRequest;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "comparison_report_id", nullable = false)
    private ComparisonReport comparisonReport;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "selected_quotation_id")
    private Quotation selectedQuotation;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private ApprovalDecision decision;

    @Column(columnDefinition = "text")
    private String reason;

    @Embedded
    @AttributeOverride(name = "id", column = @Column(name = "decided_by_id", nullable = false))
    @AttributeOverride(name = "username", column = @Column(name = "decided_by_name", nullable = false))
    private UserRef decidedBy;

    @Column(name = "decided_at", nullable = false)
    private Instant decidedAt = Instant.now();
}
