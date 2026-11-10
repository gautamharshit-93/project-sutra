-- Audit events carry the X-Correlation-Id of the request that caused them, so one user action can be followed
-- through every service's log and the audit trail.
ALTER TABLE audit_outbox ADD COLUMN correlation_id VARCHAR(64);
