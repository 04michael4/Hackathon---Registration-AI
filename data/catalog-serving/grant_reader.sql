-- Gives the backend's read-only role SELECT on the catalog_serving views. Run as the owner:
--   psql -X -v ON_ERROR_STOP=1 -v reader=catalog_reader -f data/catalog-serving/grant_reader.sql
-- catalog_serving views read catalog_research as the VIEW OWNER, so the role needs no access to catalog_research for this.
\if :{?reader}
\else
	\echo 'usage: psql -v reader=<role> -f grant_reader.sql'
	\quit
\endif
SELECT format('GRANT CONNECT ON DATABASE %I TO %I', current_database(), :'reader') \gexec
SELECT format('GRANT USAGE ON SCHEMA catalog_serving TO %I', :'reader') \gexec
SELECT format('GRANT SELECT ON ALL TABLES IN SCHEMA catalog_serving TO %I', :'reader') \gexec
