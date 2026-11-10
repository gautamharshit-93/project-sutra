-- DESTRUCTIVE: deletes every SUTRA table and all data in one database, so the services rebuild an empty system
-- (only the start-up administrator) through Flyway the next time they start. Stop the services first.
--   psql -U sutra -h localhost -d sutra_db   -f database/scripts/02_reset_database.sql
--   psql -U sutra -h localhost -d sutra_test -f database/scripts/02_reset_database.sql
-- Take a backup first if anything in it matters:
--   pg_dump -U sutra -h localhost -d sutra_db -F c -f sutra_db_backup.dump
DROP SCHEMA IF EXISTS approval CASCADE;
DROP SCHEMA IF EXISTS comparison CASCADE;
DROP SCHEMA IF EXISTS extraction CASCADE;
DROP SCHEMA IF EXISTS quotation CASCADE;
DROP SCHEMA IF EXISTS document CASCADE;
DROP SCHEMA IF EXISTS rfq CASCADE;
DROP SCHEMA IF EXISTS procurement CASCADE;   -- the single service before the split (only its migration history is left)
DROP SCHEMA IF EXISTS auth CASCADE;
DROP SCHEMA IF EXISTS masterdata CASCADE;
DROP SCHEMA IF EXISTS audit CASCADE;
-- Tables of the old single-schema version, if any are left
DROP SCHEMA IF EXISTS public CASCADE;
CREATE SCHEMA public AUTHORIZATION sutra;
