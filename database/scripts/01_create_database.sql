-- Run once as the PostgreSQL superuser:
--   psql -U postgres -h localhost -f database/scripts/01_create_database.sql
-- Creates the application login and the two databases (app + integration tests).
-- Tables are created by the migrations in database/migrations (applied by Flyway
-- when the backend starts, or manually with 02_apply_migrations.ps1).

CREATE ROLE sutra LOGIN PASSWORD 'sutra_pass';
CREATE DATABASE sutra_db   OWNER sutra ENCODING 'UTF8';
CREATE DATABASE sutra_test OWNER sutra ENCODING 'UTF8';
