-- Installs catalog_api and verifies it in ONE transaction. It commits only if every assertion passes;
-- on any error psql stops and nothing is installed. Safe to rerun. Run from any directory:
--   psql -X -v ON_ERROR_STOP=1 -f data/catalog-api/install.sql
\set ON_ERROR_STOP on
BEGIN;
\ir V1__catalog_api_views.sql
\ir verify_catalog_api.sql
COMMIT;
