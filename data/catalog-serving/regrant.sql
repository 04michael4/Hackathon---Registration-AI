-- Re-applies SELECT on the catalog_serving views to every role that already has USAGE on the schema (except the owner and PUBLIC).
-- install.sql drops and recreates some views so a rerun can change their columns; that erases their grants. Without this step a rerun would
-- silently cut the backend's read-only role off from them (the backend then answers 503). Roles are never created or widened here: only roles
-- already allowed into the schema get the views back. A first install has no such role, so nothing happens.
\set ON_ERROR_STOP on
DO $$
DECLARE
	r record;
BEGIN
	FOR r IN SELECT DISTINCT a.grantee::regrole AS role
			FROM pg_namespace n CROSS JOIN LATERAL aclexplode(n.nspacl) a
			WHERE n.nspname = 'catalog_serving' AND a.privilege_type = 'USAGE' AND a.grantee <> 0 AND a.grantee <> n.nspowner LOOP
		EXECUTE format('GRANT SELECT ON ALL TABLES IN SCHEMA catalog_serving TO %s', r.role);
	END LOOP;
END $$;
