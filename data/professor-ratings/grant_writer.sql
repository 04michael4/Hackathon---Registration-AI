-- For LATER, when the app lets students rate professors: lets one backend role submit ratings and nothing else. Run as the owner:
--   psql -X -v ON_ERROR_STOP=1 -v writer=<role> -f data/professor-ratings/grant_writer.sql
-- The role gets EXECUTE on submit_student_rating only: no table privilege, no moderation, no imports. Keep it separate from the read-only
-- catalog role, which must stay read-only. Not run anywhere yet.
\if :{?writer}
\else
	\echo 'usage: psql -v writer=<role> -f grant_writer.sql'
	\quit
\endif
SELECT format('GRANT CONNECT ON DATABASE %I TO %I', current_database(), :'writer') \gexec
SELECT format('GRANT USAGE ON SCHEMA professor_ratings TO %I', :'writer') \gexec
SELECT format('GRANT EXECUTE ON FUNCTION professor_ratings.submit_student_rating(text, bigint, integer, text, text, integer, integer, boolean, text) TO %I', :'writer') \gexec
