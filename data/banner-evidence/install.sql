-- Installs banner_evidence (V1, then V2) and checks its structure in ONE transaction. It commits only if every assertion
-- passes; on any error psql stops and nothing is installed. Safe to rerun. From the repository root:
--   psql -X -v ON_ERROR_STOP=1 -f data/banner-evidence/install.sql
\set ON_ERROR_STOP on
BEGIN;
\ir V1__banner_evidence_schema.sql
\ir V2__collector_fields.sql
\ir verify_structure.sql
COMMIT;
