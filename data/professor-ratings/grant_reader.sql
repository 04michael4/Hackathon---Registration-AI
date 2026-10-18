-- Gives a read-only role SELECT on the professor_ratings VIEWS only (not the base tables). Run as the owner:
--   psql -X -v ON_ERROR_STOP=1 -v reader=catalog_reader -f data/professor-ratings/grant_reader.sql
-- The role gets no write privilege and no EXECUTE on the writers. The backend does not read this schema yet (see ADR-0006).
\if :{?reader}
\else
	\echo 'usage: psql -v reader=<role> -f grant_reader.sql'
	\quit
\endif
SELECT format('GRANT CONNECT ON DATABASE %I TO %I', current_database(), :'reader') \gexec
SELECT format('GRANT USAGE ON SCHEMA professor_ratings TO %I', :'reader') \gexec
SELECT format('GRANT SELECT ON professor_ratings.%I TO %I', c.relname, :'reader')
	FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
	WHERE n.nspname = 'professor_ratings' AND c.relkind = 'v' AND c.relname IN ('instructor_rating', 'section_instructor_rating', 'instructor_term_rating', 'instructor_year_rating',
		'professor_comment', 'professor_overview', 'professor_prediction', 'section_prediction', 'course_prediction') \gexec
