-- DESTRUCTIVE: wipes every SUTRA table so Flyway can rebuild from scratch.
-- Run as the owner:  psql -U sutra -h localhost -d sutra_db -f database/scripts/03_reset_database.sql
DROP SCHEMA public CASCADE;
CREATE SCHEMA public AUTHORIZATION sutra;
