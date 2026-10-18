-- Installs professor_ratings and checks its structure in ONE transaction. Commits only if every assertion passes. Safe to rerun.
-- Needs catalog_research installed first (read-only, for the orphan checks).
--   psql -X -v ON_ERROR_STOP=1 -f data/professor-ratings/install.sql
-- Install into an ISOLATED local database first, as a non-superuser owner. The shared database needs a separate approval.
\set ON_ERROR_STOP on
BEGIN;
\ir V1__professor_ratings_schema.sql
\ir V2__professor_ratings_views_and_import.sql
\ir V3__rating_history.sql
\ir V4__student_ratings_and_comments.sql
\ir V5__term_predictions.sql
\ir verify_structure.sql
COMMIT;
