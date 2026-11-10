-- After the split of the procurement service (November 2026) its tables live in the schemas rfq, quotation,
-- document, comparison and approval; the old schema "procurement" keeps only its Flyway history. Run this once the
-- new services have started successfully (it refuses if any table other than the history is still there).
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM information_schema.tables
               WHERE table_schema = 'procurement' AND table_name <> 'flyway_schema_history') THEN
        RAISE EXCEPTION 'procurement still has tables: start the new services first, so they move them';
    END IF;
END $$;
DROP SCHEMA IF EXISTS procurement CASCADE;
