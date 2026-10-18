-- Row-count and content fingerprint of every base table OUTSIDE catalog_csb (read-only). Run it before and after a CSB import: the output must be identical.
--   psql -X -At -f data/csb-research/fingerprint_other_schemas.sql
SELECT format('SELECT %L AS table_name, count(*) AS row_count, COALESCE(md5(string_agg(t::text, ''|'' ORDER BY t::text COLLATE "C")), ''empty'') AS content_md5 FROM %I.%I t',
	n.nspname || '.' || c.relname, n.nspname, c.relname)
FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
WHERE c.relkind IN ('r', 'p') AND n.nspname NOT IN ('catalog_csb', 'pg_catalog', 'information_schema', 'pg_toast') AND n.nspname !~ '^pg_'
ORDER BY n.nspname, c.relname
\gexec
