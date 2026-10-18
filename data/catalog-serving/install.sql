-- Installs catalog_serving and checks its structure in ONE transaction. Safe to rerun.
-- Needs catalog_research (including the professor_ratings-independent V1-V3) installed first.
--   psql -X -v ON_ERROR_STOP=1 -f data/catalog-serving/install.sql
\set ON_ERROR_STOP on
BEGIN;
\ir V1__catalog_serving.sql
\ir verify_structure.sql
COMMIT;
