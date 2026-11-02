package com.sutra.procurement.config;

import com.sutra.common.audit.AuditScopeResolver;
import com.sutra.procurement.repository.ApprovalRepository;
import com.sutra.procurement.repository.ComparisonReportRepository;
import com.sutra.procurement.repository.QuotationRepository;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

/** Groups audit events by RFQ, so the audit service can show one RFQ's whole history. */
@Component
@Primary
public class ProcurementAuditScope implements AuditScopeResolver {

    private final QuotationRepository quotations;
    private final ComparisonReportRepository reports;
    private final ApprovalRepository approvals;

    public ProcurementAuditScope(QuotationRepository quotations, ComparisonReportRepository reports,
                                 ApprovalRepository approvals) {
        this.quotations = quotations;
        this.reports = reports;
        this.approvals = approvals;
    }

    @Override
    public Long purchaseRequestIdOf(String entityType, Long entityId) {
        if (entityId == null) {
            return null;
        }
        return switch (entityType) {
            case "PURCHASE_REQUEST" -> entityId;
            case "QUOTATION" -> quotations.findById(entityId).map(q -> q.getPurchaseRequest().getId()).orElse(null);
            case "COMPARISON_REPORT" -> reports.findById(entityId).map(r -> r.getPurchaseRequest().getId()).orElse(null);
            case "APPROVAL" -> approvals.findById(entityId).map(a -> a.getPurchaseRequest().getId()).orElse(null);
            default -> null;
        };
    }
}
