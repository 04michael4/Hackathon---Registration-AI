-- Installs catalog_csb and checks its structure in ONE transaction. Commits only if every assertion passes. Safe to rerun.
--   psql -X -v ON_ERROR_STOP=1 -f data/csb-research/install.sql
-- Additive: creates only the schema catalog_csb. It changes no table, view, function or row of catalog_research or any other schema, and nothing
-- outside catalog_csb depends on it (DROP SCHEMA catalog_csb CASCADE removes it completely).
-- V3 (the five coverage views) READ catalog_research, so they make catalog_csb depend on it: an ALTER or DROP of catalog_research.offering, .section,
-- .offering_tier or .grid_search_result then fails (or, with CASCADE, silently drops those views). They are therefore NOT installed by default.
-- Install them as a separate, later step: psql -X -v ON_ERROR_STOP=1 -f data/csb-research/install_coverage_views.sql  (or pass -v coverage_views=on here).
-- Run as the schema owner (the role that owns catalog_research), in an ISOLATED local database first. The owner needs CREATE on the database.
\set ON_ERROR_STOP on
BEGIN;
\ir V1__catalog_csb_schema.sql
\ir V2__catalog_csb_views_and_import.sql
\if :{?coverage_views}
\ir V3__catalog_csb_coverage_views.sql
\endif
\ir verify_structure.sql
COMMIT;
