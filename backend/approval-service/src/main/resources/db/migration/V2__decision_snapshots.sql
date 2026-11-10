-- A decision keeps the comparison version it was made on and the awarded vendor's name, so showing it never
-- needs the comparison or quotation service (both are fixed once decided).
ALTER TABLE approval ADD COLUMN comparison_report_version INTEGER;
ALTER TABLE approval ADD COLUMN selected_vendor_name VARCHAR(200);
DO $$
BEGIN
    IF to_regclass('comparison.comparison_report') IS NOT NULL THEN
        EXECUTE 'UPDATE approval a SET comparison_report_version = r.report_version
                 FROM comparison.comparison_report r WHERE r.id = a.comparison_report_id';
    END IF;
    IF to_regclass('quotation.quotation') IS NOT NULL THEN
        EXECUTE 'UPDATE approval a SET selected_vendor_name = q.vendor_name
                 FROM quotation.quotation q WHERE q.id = a.selected_quotation_id';
    END IF;
END $$;
UPDATE approval SET comparison_report_version = 1 WHERE comparison_report_version IS NULL;
ALTER TABLE approval ALTER COLUMN comparison_report_version SET NOT NULL;
ALTER TABLE approval ADD CONSTRAINT chk_approval_report_version CHECK (comparison_report_version > 0);
CREATE INDEX idx_approval_decided_by ON approval (decided_by_id);
