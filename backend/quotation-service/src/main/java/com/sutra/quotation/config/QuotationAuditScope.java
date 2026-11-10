package com.sutra.quotation.config;

import com.sutra.common.audit.AuditScopeResolver;
import com.sutra.quotation.repository.QuotationRepository;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

/** Groups quotation audit events under their RFQ, so the audit service can show one RFQ's whole history. */
@Component
@Primary
public class QuotationAuditScope implements AuditScopeResolver {

    private final QuotationRepository quotations;

    public QuotationAuditScope(QuotationRepository quotations) {
        this.quotations = quotations;
    }

    @Override
    public Long purchaseRequestIdOf(String entityType, Long entityId) {
        if (entityId == null) {
            return null;
        }
        return switch (entityType) {
            case "PURCHASE_REQUEST" -> entityId;
            case "QUOTATION" -> quotations.findById(entityId).map(q -> q.getPurchaseRequestId()).orElse(null);
            default -> null;
        };
    }
}
