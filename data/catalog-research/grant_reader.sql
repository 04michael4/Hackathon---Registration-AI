-- Gives a read-only role SELECT on catalog_research (data about public course offerings; no student data lives here). Run as the owner:
--   psql -X -v ON_ERROR_STOP=1 -v reader=catalog_reader -f data/catalog-research/grant_reader.sql
-- The role gets no write privilege and no EXECUTE on import_term or record_tier_policy_decision. The backend does not read this schema.
\if :{?reader}
\else
	\echo 'usage: psql -v reader=<role> -f grant_reader.sql'
	\quit
\endif
SELECT format('GRANT CONNECT ON DATABASE %I TO %I', current_database(), :'reader') \gexec
SELECT format('GRANT USAGE ON SCHEMA catalog_research TO %I', :'reader') \gexec
SELECT format('GRANT SELECT ON ALL TABLES IN SCHEMA catalog_research TO %I', :'reader') \gexec
