package com.sutra.comparison.config;

import com.sutra.common.audit.AuditScopeResolver;
import com.sutra.comparison.repository.ComparisonReportRepository;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

/** Groups comparison audit events under their RFQ, so the audit service can show one RFQ's whole history. */
@Component
@Primary
public class ComparisonAuditScope implements AuditScopeResolver {

    private final ComparisonReportRepository reports;

    public ComparisonAuditScope(ComparisonReportRepository reports) {
        this.reports = reports;
    }

    @Override
    public Long purchaseRequestIdOf(String entityType, Long entityId) {
        if (entityId == null || !"COMPARISON_REPORT".equals(entityType)) {
            return null;
        }
        return reports.findById(entityId).map(r -> r.getPurchaseRequestId()).orElse(null);
    }
}
