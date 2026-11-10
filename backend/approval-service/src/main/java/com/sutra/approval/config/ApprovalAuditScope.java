package com.sutra.approval.config;

import com.sutra.approval.repository.ApprovalRepository;
import com.sutra.common.audit.AuditScopeResolver;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

/** Groups approval audit events under their RFQ, so the audit service can show one RFQ's whole history. */
@Component
@Primary
public class ApprovalAuditScope implements AuditScopeResolver {

    private final ApprovalRepository approvals;

    public ApprovalAuditScope(ApprovalRepository approvals) {
        this.approvals = approvals;
    }

    @Override
    public Long purchaseRequestIdOf(String entityType, Long entityId) {
        if (entityId == null || !"APPROVAL".equals(entityType)) {
            return null;
        }
        return approvals.findById(entityId).map(a -> a.getPurchaseRequestId()).orElse(null);
    }
}
