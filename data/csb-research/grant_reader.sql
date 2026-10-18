-- Gives a read-only role SELECT on catalog_csb. Run as the owner:
--   psql -X -v ON_ERROR_STOP=1 -v reader=catalog_reader -f data/csb-research/grant_reader.sql
-- The role gets no write privilege and no EXECUTE on import_csb. catalog_csb holds section data from an authenticated Banner session
-- (including instructor names): it is NOT served by any endpoint and the backend does not read this schema. Grant it to a person or tool
-- only if they may see that data; a role that must not see instructor names must not get this grant.
\if :{?reader}
\else
	\echo 'usage: psql -v reader=<role> -f grant_reader.sql'
	\quit
\endif
SELECT format('GRANT CONNECT ON DATABASE %I TO %I', current_database(), :'reader') \gexec
SELECT format('GRANT USAGE ON SCHEMA catalog_csb TO %I', :'reader') \gexec
SELECT format('GRANT SELECT ON ALL TABLES IN SCHEMA catalog_csb TO %I', :'reader') \gexec
