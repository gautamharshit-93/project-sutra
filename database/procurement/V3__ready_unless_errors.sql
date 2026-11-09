-- =====================================================================
-- Quotations that were read are ready for comparison straight away; warnings stay open as notes to check.
-- Only an open ERROR flag (a line without quantity or price) or a quotation without lines still needs review.
-- This moves existing quotations to the new rule.
-- =====================================================================
UPDATE quotation q
SET status = 'READY_FOR_COMPARISON'
WHERE q.status = 'NEEDS_REVIEW'
  AND EXISTS (SELECT 1 FROM quotation_item i WHERE i.quotation_id = q.id)
  AND NOT EXISTS (SELECT 1 FROM quotation_flag f WHERE f.quotation_id = q.id AND NOT f.resolved AND f.severity = 'ERROR');
