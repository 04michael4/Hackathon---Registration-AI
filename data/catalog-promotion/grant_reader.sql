-- Gives the backend's read-only role exactly what it needs to read catalog_api and catalog_live. Run as the database owner:
--
--   psql -X -v ON_ERROR_STOP=1 -v reader=registration_reader -f data/catalog-promotion/grant_reader.sql
--
-- Views run with their owner's rights, but functions called from a view run as the CALLER, so the role also needs EXECUTE on the
-- few functions that return only hashes, codes or booleans (the ones that read evidence are SECURITY DEFINER with a pinned
-- search_path). It gets no privilege on any table in catalog_promoted or banner_evidence. On cse007_snapshot it gets only COLUMN-level SELECT on
-- the few columns that JdbcCourseMetadataCatalog (/api/terms and the course detail) reads directly; without them both return a safe 503.
\if :{?reader}
\else
	\echo 'usage: psql -v reader=<role> -f grant_reader.sql'
	\quit
\endif
SELECT format('GRANT CONNECT ON DATABASE %I TO %I', current_database(), :'reader') \gexec
SELECT format('GRANT USAGE ON SCHEMA catalog_api, catalog_live, catalog_promoted TO %I', :'reader') \gexec
SELECT format('GRANT SELECT ON ALL TABLES IN SCHEMA catalog_api TO %I', :'reader') \gexec
SELECT format('GRANT SELECT ON ALL TABLES IN SCHEMA catalog_live TO %I', :'reader') \gexec
SELECT format('GRANT EXECUTE ON FUNCTION catalog_promoted.package_sha(text, text), catalog_promoted.evidence_fingerprint(text, text), '
	|| 'catalog_promoted.snapshot_exclusion_mentions(text), catalog_promoted.is_empty_requirement(text, text), '
	|| 'catalog_promoted.requirement_capability(text) TO %I', :'reader') \gexec
-- cse007_snapshot columns read by JdbcCourseMetadataCatalog: term name and capture date, course description, CRN and instructor. Nothing else there.
SELECT format('GRANT USAGE ON SCHEMA cse007_snapshot TO %I', :'reader') \gexec
SELECT format('GRANT SELECT (term_id, term_code, name, source_id) ON cse007_snapshot.term TO %I', :'reader') \gexec
SELECT format('GRANT SELECT (source_id, captured_at) ON cse007_snapshot.source_asset TO %I', :'reader') \gexec
SELECT format('GRANT SELECT (course_id, code, description) ON cse007_snapshot.course TO %I', :'reader') \gexec
SELECT format('GRANT SELECT (section_id, course_id, term_id, crn, instructor) ON cse007_snapshot.section TO %I', :'reader') \gexec
