-- Imports the seven user-reported links in ONE transaction. Safe to rerun (a rerun adds nothing).
--   psql -X -v ON_ERROR_STOP=1 -f data/banner-evidence/import_user_reported_links.sql
\set ON_ERROR_STOP on
BEGIN;
\ir seed_user_reported_links.sql
\ir verify_banner_evidence.sql
COMMIT;
