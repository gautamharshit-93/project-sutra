-- The outbox moved from the procurement schema may predate the correlation id (a database where the single
-- procurement service never ran its V5); add the column if it is missing.
ALTER TABLE audit_outbox ADD COLUMN IF NOT EXISTS correlation_id VARCHAR(64);
