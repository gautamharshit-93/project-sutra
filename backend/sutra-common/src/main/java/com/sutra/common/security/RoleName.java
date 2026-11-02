package com.sutra.common.security;

/**
 * ADMIN manages everything; PROCUREMENT (buyer) runs RFQs; APPROVER (manager) awards them; AUDITOR reads
 * everything; VENDOR is a supplier's login that only sees open RFQs and its own quotations.
 * SERVICE is never stored: it marks the short-lived tokens SUTRA services use to call each other.
 */
public enum RoleName {
    ADMIN, PROCUREMENT, APPROVER, AUDITOR, VENDOR, SERVICE
}
