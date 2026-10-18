-- Installs catalog_promoted and catalog_live and checks their structure in ONE transaction. Commits only if every
-- assertion passes. Safe to rerun. Needs cse007_snapshot, catalog_api and banner_evidence (V1 + V2) installed first.
--   psql -X -v ON_ERROR_STOP=1 -f data/catalog-promotion/install.sql
\set ON_ERROR_STOP on
BEGIN;
\ir V1__catalog_promoted_schema.sql
\ir V2__catalog_live_views.sql
\ir verify_structure.sql
COMMIT;
