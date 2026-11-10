-- =====================================================================
-- Vendors sign up themselves on the vendor portal. The login is created inactive and waits for one-click
-- approval by an administrator; on approval the master data vendor is created and linked, and the sign-up
-- row is removed. Rejecting deletes the login (and, by cascade, its sign-up row).
-- =====================================================================
ALTER TABLE app_user ADD COLUMN pending_approval BOOLEAN NOT NULL DEFAULT FALSE;

CREATE TABLE vendor_signup (
    id             BIGSERIAL PRIMARY KEY,
    user_id        BIGINT       NOT NULL UNIQUE REFERENCES app_user (id) ON DELETE CASCADE,
    company_name   VARCHAR(200) NOT NULL,
    contact_person VARCHAR(150),
    phone          VARCHAR(40),
    address        VARCHAR(1000),
    tax_number     VARCHAR(50),
    website        VARCHAR(255),
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ  NOT NULL DEFAULT now()
);
