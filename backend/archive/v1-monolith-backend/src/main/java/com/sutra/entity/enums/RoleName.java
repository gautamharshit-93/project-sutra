package com.sutra.entity.enums;

/**
 * ADMIN manages everything; PROCUREMENT (buyer) runs RFQs; APPROVER (manager) awards them; AUDITOR reads
 * everything; VENDOR is a supplier's login that only sees open RFQs and its own quotations.
 */
public enum RoleName {
    ADMIN, PROCUREMENT, APPROVER, AUDITOR, VENDOR
}
