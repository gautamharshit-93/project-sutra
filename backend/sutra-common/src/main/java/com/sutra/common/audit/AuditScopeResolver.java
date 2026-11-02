package com.sutra.common.audit;

/**
 * Finds the purchase request an audited record belongs to (e.g. a quotation's RFQ), so the audit service can show
 * one RFQ's whole history. Services without RFQ records use the default, which returns null.
 */
@FunctionalInterface
public interface AuditScopeResolver {

    Long purchaseRequestIdOf(String entityType, Long entityId);
}
