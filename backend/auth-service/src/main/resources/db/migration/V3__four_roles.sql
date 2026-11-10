-- =====================================================================
-- Four roles only: Administrator, Buyer (PROCUREMENT), Vendor and Manager (APPROVER). The read-only Auditor role is
-- removed. Logins that had only the Auditor role are deleted (the user chose this on 2026-11-09); the deletion is
-- recorded in the audit trail. Their earlier actions stay in the audit log under their names.
-- =====================================================================
INSERT INTO audit_outbox (event_id, entity_type, entity_id, action, performed_by, details)
SELECT gen_random_uuid(), 'USER', u.id, 'DELETED', 'system',
       jsonb_build_object('username', u.username, 'reason', 'Auditor role removed')
FROM app_user u
WHERE EXISTS (SELECT 1 FROM user_role ur JOIN role r ON r.id = ur.role_id WHERE ur.user_id = u.id AND r.name = 'AUDITOR')
  AND NOT EXISTS (SELECT 1 FROM user_role ur JOIN role r ON r.id = ur.role_id WHERE ur.user_id = u.id AND r.name <> 'AUDITOR');

DELETE FROM app_user u
WHERE EXISTS (SELECT 1 FROM user_role ur JOIN role r ON r.id = ur.role_id WHERE ur.user_id = u.id AND r.name = 'AUDITOR')
  AND NOT EXISTS (SELECT 1 FROM user_role ur JOIN role r ON r.id = ur.role_id WHERE ur.user_id = u.id AND r.name <> 'AUDITOR');

DELETE FROM user_role WHERE role_id IN (SELECT id FROM role WHERE name = 'AUDITOR');
DELETE FROM role WHERE name = 'AUDITOR';

ALTER TABLE role DROP CONSTRAINT chk_role_name;
ALTER TABLE role ADD CONSTRAINT chk_role_name CHECK (name IN ('ADMIN', 'PROCUREMENT', 'APPROVER', 'VENDOR'));

UPDATE role SET description = 'Full access to everything, including users and roles' WHERE name = 'ADMIN';
UPDATE role SET description = 'Company buyer: entities, customers, RFQs, vendor quotations and comparisons' WHERE name = 'PROCUREMENT';
UPDATE role SET description = 'Manager: awards an RFQ to a vendor or rejects it; reads the audit log' WHERE name = 'APPROVER';
UPDATE role SET description = 'Supplier (self-registered): quotes on open RFQs, sees only its own quotations' WHERE name = 'VENDOR';
