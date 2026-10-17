-- Runs the probes against the installed banner_evidence schema, after the Step 1 seed, inside a transaction that
-- is ALWAYS rolled back. Nothing it does persists.
--   psql -X -v ON_ERROR_STOP=1 -f data/banner-evidence/test_banner_evidence.sql
\set ON_ERROR_STOP on
BEGIN;
SELECT set_config('t.passed', '0', true);
\ir seed_user_reported_links.sql
\ir probes_banner_evidence.sql
ROLLBACK;
