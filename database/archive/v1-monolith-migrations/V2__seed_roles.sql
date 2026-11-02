-- =====================================================================
-- V2: reference data - the four application roles.
-- The first administrator account is created by the backend on startup
-- (com.sutra.config.DataInitializer) so that no password hash lives here.
-- =====================================================================
INSERT INTO role (name, description) VALUES
    ('ADMIN',       'Full access: master data, users and roles, all purchase requests'),
    ('PROCUREMENT', 'Creates purchase requests, uploads and reviews quotations, runs comparisons'),
    ('APPROVER',    'Reviews comparisons and records approval or rejection decisions'),
    ('VIEWER',      'Read-only access to purchase requests, quotations and comparisons');
