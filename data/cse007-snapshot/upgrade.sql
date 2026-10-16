\set ON_ERROR_STOP on
BEGIN;
\ir V3__user_clarifications.sql
\ir V4__clarified_seed.sql
\ir verify.sql
COMMIT;
