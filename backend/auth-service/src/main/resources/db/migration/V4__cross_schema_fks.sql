-- =====================================================================
-- auth: cross-schema foreign keys (a vendor login's vendor, and the users procurement records refer to).
-- =====================================================================
-- ---------------------------------------------------------------------
-- Cross-schema foreign keys. Every service keeps its own schema in the same PostgreSQL database, so a reference
-- into another service's schema (entity, sub-entity, customer, vendor, user) is enforced by the database too.
-- The same block is in masterdata V2, auth V4 and procurement V4: whichever runs when both tables exist adds the
-- key, so the result is the same in any start-up order. ON DELETE RESTRICT: a record in use cannot be deleted
-- (the services already refuse with a clear message; this is the safety net).
-- Disabled with the Flyway placeholder crossSchemaFks=false where a schema is tested alone (integration tests).
-- ---------------------------------------------------------------------
DO $$
BEGIN
    IF '${crossSchemaFks}' = 'true'
       AND to_regclass('procurement.purchase_request') IS NOT NULL AND to_regclass('masterdata.business_entity') IS NOT NULL
       AND NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'fk_pr_entity') THEN
        ALTER TABLE procurement.purchase_request ADD CONSTRAINT fk_pr_entity FOREIGN KEY (entity_id) REFERENCES masterdata.business_entity (id) ON DELETE RESTRICT;
    END IF;
EXCEPTION WHEN duplicate_object THEN NULL; -- another service added it at the same moment
END $$;

DO $$
BEGIN
    IF '${crossSchemaFks}' = 'true'
       AND to_regclass('procurement.purchase_request') IS NOT NULL AND to_regclass('masterdata.sub_entity') IS NOT NULL
       AND NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'fk_pr_sub_entity') THEN
        ALTER TABLE procurement.purchase_request ADD CONSTRAINT fk_pr_sub_entity FOREIGN KEY (sub_entity_id) REFERENCES masterdata.sub_entity (id) ON DELETE RESTRICT;
    END IF;
EXCEPTION WHEN duplicate_object THEN NULL; -- another service added it at the same moment
END $$;

DO $$
BEGIN
    IF '${crossSchemaFks}' = 'true'
       AND to_regclass('procurement.purchase_request') IS NOT NULL AND to_regclass('masterdata.customer') IS NOT NULL
       AND NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'fk_pr_customer') THEN
        ALTER TABLE procurement.purchase_request ADD CONSTRAINT fk_pr_customer FOREIGN KEY (customer_id) REFERENCES masterdata.customer (id) ON DELETE RESTRICT;
    END IF;
EXCEPTION WHEN duplicate_object THEN NULL; -- another service added it at the same moment
END $$;

DO $$
BEGIN
    IF '${crossSchemaFks}' = 'true'
       AND to_regclass('procurement.purchase_request') IS NOT NULL AND to_regclass('auth.app_user') IS NOT NULL
       AND NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'fk_pr_requested_by') THEN
        ALTER TABLE procurement.purchase_request ADD CONSTRAINT fk_pr_requested_by FOREIGN KEY (requested_by_id) REFERENCES auth.app_user (id) ON DELETE RESTRICT;
    END IF;
EXCEPTION WHEN duplicate_object THEN NULL; -- another service added it at the same moment
END $$;

DO $$
BEGIN
    IF '${crossSchemaFks}' = 'true'
       AND to_regclass('procurement.quotation') IS NOT NULL AND to_regclass('masterdata.vendor') IS NOT NULL
       AND NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'fk_quotation_vendor') THEN
        ALTER TABLE procurement.quotation ADD CONSTRAINT fk_quotation_vendor FOREIGN KEY (vendor_id) REFERENCES masterdata.vendor (id) ON DELETE RESTRICT;
    END IF;
EXCEPTION WHEN duplicate_object THEN NULL; -- another service added it at the same moment
END $$;

DO $$
BEGIN
    IF '${crossSchemaFks}' = 'true'
       AND to_regclass('procurement.quotation') IS NOT NULL AND to_regclass('auth.app_user') IS NOT NULL
       AND NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'fk_quotation_uploaded_by') THEN
        ALTER TABLE procurement.quotation ADD CONSTRAINT fk_quotation_uploaded_by FOREIGN KEY (uploaded_by_id) REFERENCES auth.app_user (id) ON DELETE RESTRICT;
    END IF;
EXCEPTION WHEN duplicate_object THEN NULL; -- another service added it at the same moment
END $$;

DO $$
BEGIN
    IF '${crossSchemaFks}' = 'true'
       AND to_regclass('procurement.quotation_file') IS NOT NULL AND to_regclass('auth.app_user') IS NOT NULL
       AND NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'fk_quotation_file_uploaded_by') THEN
        ALTER TABLE procurement.quotation_file ADD CONSTRAINT fk_quotation_file_uploaded_by FOREIGN KEY (uploaded_by_id) REFERENCES auth.app_user (id) ON DELETE RESTRICT;
    END IF;
EXCEPTION WHEN duplicate_object THEN NULL; -- another service added it at the same moment
END $$;

DO $$
BEGIN
    IF '${crossSchemaFks}' = 'true'
       AND to_regclass('procurement.quotation_flag') IS NOT NULL AND to_regclass('auth.app_user') IS NOT NULL
       AND NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'fk_quotation_flag_resolved_by') THEN
        ALTER TABLE procurement.quotation_flag ADD CONSTRAINT fk_quotation_flag_resolved_by FOREIGN KEY (resolved_by_id) REFERENCES auth.app_user (id) ON DELETE RESTRICT;
    END IF;
EXCEPTION WHEN duplicate_object THEN NULL; -- another service added it at the same moment
END $$;

DO $$
BEGIN
    IF '${crossSchemaFks}' = 'true'
       AND to_regclass('procurement.comparison_report') IS NOT NULL AND to_regclass('auth.app_user') IS NOT NULL
       AND NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'fk_comparison_generated_by') THEN
        ALTER TABLE procurement.comparison_report ADD CONSTRAINT fk_comparison_generated_by FOREIGN KEY (generated_by_id) REFERENCES auth.app_user (id) ON DELETE RESTRICT;
    END IF;
EXCEPTION WHEN duplicate_object THEN NULL; -- another service added it at the same moment
END $$;

DO $$
BEGIN
    IF '${crossSchemaFks}' = 'true'
       AND to_regclass('procurement.approval') IS NOT NULL AND to_regclass('auth.app_user') IS NOT NULL
       AND NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'fk_approval_decided_by') THEN
        ALTER TABLE procurement.approval ADD CONSTRAINT fk_approval_decided_by FOREIGN KEY (decided_by_id) REFERENCES auth.app_user (id) ON DELETE RESTRICT;
    END IF;
EXCEPTION WHEN duplicate_object THEN NULL; -- another service added it at the same moment
END $$;

DO $$
BEGIN
    IF '${crossSchemaFks}' = 'true'
       AND to_regclass('auth.app_user') IS NOT NULL AND to_regclass('masterdata.vendor') IS NOT NULL
       AND NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'fk_app_user_vendor') THEN
        ALTER TABLE auth.app_user ADD CONSTRAINT fk_app_user_vendor FOREIGN KEY (vendor_id) REFERENCES masterdata.vendor (id) ON DELETE RESTRICT;
    END IF;
EXCEPTION WHEN duplicate_object THEN NULL; -- another service added it at the same moment
END $$;
