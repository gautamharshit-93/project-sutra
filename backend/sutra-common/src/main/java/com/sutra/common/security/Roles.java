package com.sutra.common.security;

/** {@code @PreAuthorize} expressions shared by the services. */
public final class Roles {

    /** The company's own users. Vendors are excluded: they only use {@code /api/vendor-portal}. */
    public static final String STAFF = "hasAnyRole('ADMIN','PROCUREMENT','APPROVER')";

    public static final String ADMIN = "hasRole('ADMIN')";

    /** The company's day-to-day work (entities, customers, vendors' details, RFQs): buyers, and administrators. */
    public static final String BUYER = "hasAnyRole('ADMIN','PROCUREMENT')";

    public static final String VENDOR = "hasRole('VENDOR')";

    /** Who may read the full audit trail. */
    public static final String AUDIT_READERS = "hasAnyRole('ADMIN','APPROVER')";

    private Roles() {
    }
}
