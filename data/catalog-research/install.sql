-- Installs catalog_research and checks its structure in ONE transaction. Commits only if every assertion passes. Safe to rerun.
-- Needs cse007_snapshot, catalog_api, banner_evidence (V1 + V2), catalog_promoted and catalog_live installed first.
--   psql -X -v ON_ERROR_STOP=1 -f data/catalog-research/install.sql
-- Install into an ISOLATED local database, as a non-superuser owner. Never into the shared database.
\set ON_ERROR_STOP on
BEGIN;
\ir V1__catalog_research_schema.sql
\ir V2__catalog_research_views_and_import.sql
\ir verify_structure.sql
COMMIT;
