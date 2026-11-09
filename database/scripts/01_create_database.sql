-- Run once as the PostgreSQL superuser:
--   psql -U postgres -h localhost -f database/scripts/01_create_database.sql
-- Creates the application login and the two databases (app + integration tests).
-- Tables are created by each service from its own folder (database/auth, masterdata, procurement, audit)
-- through Flyway when the service starts.

CREATE ROLE sutra LOGIN PASSWORD 'sutra_pass';
CREATE DATABASE sutra_db   OWNER sutra ENCODING 'UTF8';
CREATE DATABASE sutra_test OWNER sutra ENCODING 'UTF8';
