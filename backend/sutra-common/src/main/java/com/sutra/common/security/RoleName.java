package com.sutra.common.security;

/**
 * ADMIN can do everything; PROCUREMENT (buyer) does the company's work: entities, customers, RFQs, quotations and
 * comparisons; VENDOR (one login per supplier, self-registered) quotes on open RFQs; APPROVER (manager) awards or
 * rejects. SERVICE is only for service-to-service calls.
 */
public enum RoleName {
    ADMIN, PROCUREMENT, APPROVER, VENDOR, SERVICE
}
