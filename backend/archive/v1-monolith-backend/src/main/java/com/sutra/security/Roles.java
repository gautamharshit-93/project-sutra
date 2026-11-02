package com.sutra.security;

/** {@code @PreAuthorize} expressions shared by the controllers. */
public final class Roles {

    /** The company's own users. Vendors are excluded: they only use {@code /api/vendor-portal}. */
    public static final String STAFF = "hasAnyRole('ADMIN','PROCUREMENT','APPROVER','AUDITOR')";

    public static final String VENDOR = "hasRole('VENDOR')";

    private Roles() {
    }
}
