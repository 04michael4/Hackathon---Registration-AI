\set ON_ERROR_STOP on
BEGIN;
\ir V1__schema.sql
\ir V2__seed.sql
\ir verify.sql
COMMIT;
