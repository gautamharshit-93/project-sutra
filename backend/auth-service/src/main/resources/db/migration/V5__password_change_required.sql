-- =====================================================================
-- A login that still uses a known start-up password (the bootstrap administrator's) must choose a new one before
-- doing anything else. Set by the service at start-up, cleared when the user changes the password.
-- =====================================================================
ALTER TABLE app_user ADD COLUMN password_change_required BOOLEAN NOT NULL DEFAULT FALSE;
