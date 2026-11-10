package com.sutra.approval.entity;

import com.sutra.approval.entity.base.BaseEntity;
import com.sutra.api.enums.ApprovalDecision;
import jakarta.persistence.Column;
import jakarta.persistence.Embedded;
import jakarta.persistence.AttributeOverride;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
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

    /** The RFQ decided on (owned by the RFQ service; a cross-schema foreign key keeps it valid). */
    @Column(name = "purchase_request_id", nullable = false, updatable = false)
    private Long purchaseRequestId;

    /** The comparison the decision was made on, and its version (kept: a decision never changes). */
    @Column(name = "comparison_report_id", nullable = false, updatable = false)
    private Long comparisonReportId;

    @Column(name = "comparison_report_version", nullable = false, updatable = false)
    private Integer comparisonReportVersion;

    /** The awarded quotation and its vendor's name at the time (null when rejected). */
    @Column(name = "selected_quotation_id", updatable = false)
    private Long selectedQuotationId;

    @Column(name = "selected_vendor_name", length = 200, updatable = false)
    private String selectedVendorName;

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
