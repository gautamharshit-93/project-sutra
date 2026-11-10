-- =====================================================================
-- Tamper-evident audit trail + correlation id.
-- Every entry gets its place in a SHA-256 hash chain (sequence_no, previous_hash, hash). Entries stored before
-- this migration are linked once by the service at start-up (oldest first). A trigger refuses any change to
-- a linked entry and any delete, so the trail can only grow; GET /api/audit-logs/verify recomputes the chain.
-- =====================================================================
ALTER TABLE audit_log ADD COLUMN correlation_id VARCHAR(64);
ALTER TABLE audit_log ADD COLUMN sequence_no    BIGINT;
ALTER TABLE audit_log ADD COLUMN previous_hash  VARCHAR(64);
ALTER TABLE audit_log ADD COLUMN hash           VARCHAR(64);
ALTER TABLE audit_log ADD CONSTRAINT uq_audit_log_sequence UNIQUE (sequence_no);
ALTER TABLE audit_log ADD CONSTRAINT ck_audit_log_chain_shape
    CHECK ((sequence_no IS NULL AND previous_hash IS NULL AND hash IS NULL)
        OR (sequence_no > 0 AND previous_hash IS NOT NULL AND hash IS NOT NULL));
CREATE INDEX idx_audit_correlation ON audit_log (correlation_id);

CREATE TABLE audit_chain_head (
    id            INTEGER  PRIMARY KEY CHECK (id = 1),
    last_sequence BIGINT   NOT NULL CHECK (last_sequence >= 0),
    last_hash     VARCHAR(64) NOT NULL
);
INSERT INTO audit_chain_head (id, last_sequence, last_hash) VALUES (1, 0, repeat('0', 64));

-- Append-only: an entry may be linked into the chain once (hash set from NULL), never changed or deleted.
CREATE FUNCTION audit_log_append_only() RETURNS trigger AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'audit_log is append-only: entries cannot be deleted';
    END IF;
    IF OLD.hash IS NOT NULL THEN
        RAISE EXCEPTION 'audit_log is append-only: entry % cannot be changed', OLD.sequence_no;
    END IF;
    IF NEW.event_id IS DISTINCT FROM OLD.event_id OR NEW.service IS DISTINCT FROM OLD.service
       OR NEW.entity_type IS DISTINCT FROM OLD.entity_type OR NEW.entity_id IS DISTINCT FROM OLD.entity_id
       OR NEW.purchase_request_id IS DISTINCT FROM OLD.purchase_request_id OR NEW.action IS DISTINCT FROM OLD.action
       OR NEW.performed_by IS DISTINCT FROM OLD.performed_by OR NEW.details IS DISTINCT FROM OLD.details
       OR NEW.correlation_id IS DISTINCT FROM OLD.correlation_id THEN
        RAISE EXCEPTION 'audit_log is append-only: only the chain link of an earlier entry can be added';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_audit_log_append_only
    BEFORE UPDATE OR DELETE ON audit_log
    FOR EACH ROW EXECUTE FUNCTION audit_log_append_only();

-- The shared outbox table carries the correlation id too.
ALTER TABLE audit_outbox ADD COLUMN correlation_id VARCHAR(64);
