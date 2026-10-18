-- catalog_csb v3: READ-ONLY views that compare the CSB research with what catalog_research already holds, and that evaluate the suggested
-- natural-science sequences. Needs V1, V2 and catalog_research (V1-V6). Writes nothing anywhere; catalog_csb is never read by catalog_research.
\set ON_ERROR_STOP on

DO $$ BEGIN
	IF to_regclass('catalog_csb.section') IS NULL THEN RAISE EXCEPTION 'catalog_csb V1 and V2 must be installed first'; END IF;
	IF to_regclass('catalog_research.offering') IS NULL OR to_regclass('catalog_research.offering_tier') IS NULL THEN
		RAISE EXCEPTION 'catalog_research must be installed for the coverage views (install V1 and V2 only into a database without it)';
	END IF;
END $$;

-- ---------------------------------------------------------------------------------------------------------
-- Is the prerequisite expression satisfied by a set of completed courses? ({"course": "X"} / {"op": "AND"|"OR", "args": [...]})
-- ---------------------------------------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION catalog_csb.expression_satisfied(p_expr jsonb, p_done text[]) RETURNS boolean
LANGUAGE plpgsql IMMUTABLE AS $$
DECLARE
	a jsonb;
	r boolean;
BEGIN
	IF p_expr IS NULL THEN RETURN true; END IF;
	IF p_expr ? 'course' THEN RETURN (p_expr ->> 'course') = ANY (p_done); END IF;
	IF p_expr ->> 'op' = 'AND' THEN
		FOR a IN SELECT jsonb_array_elements(p_expr -> 'args') LOOP
			IF NOT catalog_csb.expression_satisfied(a, p_done) THEN RETURN false; END IF;
		END LOOP;
		RETURN true;
	ELSIF p_expr ->> 'op' = 'OR' THEN
		FOR a IN SELECT jsonb_array_elements(p_expr -> 'args') LOOP
			IF catalog_csb.expression_satisfied(a, p_done) THEN RETURN true; END IF;
		END LOOP;
		RETURN false;
	END IF;
	RAISE EXCEPTION 'unrecognized expression node %', p_expr;
END $$;

CREATE OR REPLACE FUNCTION catalog_csb.expression_courses(p_expr jsonb) RETURNS text[]
LANGUAGE sql IMMUTABLE AS $$
	SELECT COALESCE(array_agg(DISTINCT c ORDER BY c), '{}') FROM (
		SELECT regexp_matches(p_expr::text, '"course": "([A-Z]+-[0-9]{3})"', 'g') AS m) x, LATERAL unnest(x.m) AS c
$$;

-- ---------------------------------------------------------------------------------------------------------
-- Coverage: for every catalog course the CSB degree names, is it in catalog_research, researched here, searched with no result, or untouched?
-- ---------------------------------------------------------------------------------------------------------
CREATE OR REPLACE VIEW catalog_csb.course_availability AS
SELECT t.term_code, c.course_id,
	(SELECT o.data_source FROM catalog_research.offering o WHERE o.term_code = t.term_code AND o.course_id = c.course_id ORDER BY o.offering_id LIMIT 1) AS research_data_source,
	(SELECT string_agg(DISTINCT tr.tier, ',') FROM catalog_research.offering o JOIN catalog_research.offering_tier tr ON tr.term_code = o.term_code AND tr.offering_id = o.offering_id
		WHERE o.term_code = t.term_code AND o.course_id = c.course_id) AS research_tier,
	EXISTS (SELECT 1 FROM catalog_research.offering o WHERE o.term_code = t.term_code AND o.course_id = c.course_id) AS in_catalog_research,
	EXISTS (SELECT 1 FROM catalog_research.grid_search_result r WHERE r.term_code = t.term_code AND replace(r.course, ' ', '-') = c.course_id) AS searched_not_offered_in_catalog_research,
	EXISTS (SELECT 1 FROM catalog_csb.offering o WHERE o.term_code = t.term_code AND o.course_id = c.course_id) AS in_csb_research,
	EXISTS (SELECT 1 FROM catalog_csb.banner_search b WHERE b.term_code = t.term_code AND b.course_id = c.course_id AND b.result = 'NOT_OFFERED') AS searched_not_offered_in_csb_research
FROM catalog_csb.catalog_course c CROSS JOIN (SELECT DISTINCT term_code FROM catalog_csb.banner_search) t;

CREATE OR REPLACE VIEW catalog_csb.course_coverage AS
SELECT a.term_code, a.course_id,
	CASE WHEN a.in_catalog_research AND a.in_csb_research THEN 'OVERLAP_IN_BOTH_REVIEW'
		WHEN a.in_catalog_research THEN 'IN_CATALOG_RESEARCH_OFFERED'
		WHEN a.in_csb_research THEN 'CSB_RESEARCH_OFFERED'
		WHEN a.searched_not_offered_in_catalog_research THEN 'CATALOG_RESEARCH_SEARCHED_NOT_OFFERED'
		WHEN a.searched_not_offered_in_csb_research THEN 'CSB_RESEARCH_SEARCHED_NOT_OFFERED'
		ELSE 'NOT_SEARCHED_ANYWHERE' END AS coverage,
	a.research_data_source, a.research_tier,
	(SELECT c.catalog_status FROM catalog_csb.catalog_course c WHERE c.course_id = a.course_id) AS catalog_status
FROM catalog_csb.course_availability a;
COMMENT ON VIEW catalog_csb.course_coverage IS 'Where each CSB-named course stands: already in catalog_research (not touched here), researched by the CSB import, searched with no sections, or searched nowhere (report CSE gaps to the catalog_research owner).';

-- ---------------------------------------------------------------------------------------------------------
-- Natural-science sequences, term by term
-- ---------------------------------------------------------------------------------------------------------
CREATE OR REPLACE VIEW catalog_csb.ns_sequence_course_status AS
SELECT t.term_code, sc.sequence_key, sc.ordinal, sc.course_id, a.in_catalog_research, a.in_csb_research,
	CASE WHEN a.in_catalog_research OR a.in_csb_research THEN 'OFFERED'
		WHEN a.searched_not_offered_in_catalog_research OR a.searched_not_offered_in_csb_research THEN 'NOT_OFFERED'
		ELSE 'NOT_SEARCHED' END AS availability,
	c.is_ns, c.catalog_status, c.attributes, c.credits_text AS catalog_credits_text,
	(SELECT string_agg(DISTINCT cr.registration_shape, '; ') FROM catalog_csb.component_requirement cr WHERE cr.term_code = t.term_code AND cr.offering_id = sc.course_id AND cr.component_type = 'LECTURE') AS lab_registration_csb,
	(SELECT string_agg(DISTINCT s.component_type || ' x' || s.n, ', ') FROM (SELECT s.component_type, count(*) AS n FROM catalog_research.section s
		JOIN catalog_research.offering o USING (term_code, offering_id) WHERE o.term_code = t.term_code AND o.course_id = sc.course_id GROUP BY s.component_type) s) AS components_catalog_research
FROM catalog_csb.ns_sequence_course sc
CROSS JOIN (SELECT DISTINCT term_code FROM catalog_csb.banner_search) t
JOIN catalog_csb.catalog_course c ON c.course_id = sc.course_id
JOIN catalog_csb.course_availability a ON a.term_code = t.term_code AND a.course_id = sc.course_id;

CREATE OR REPLACE VIEW catalog_csb.ns_sequence_status AS
SELECT s.term_code, s.sequence_key, q.family, q.listed_credits_text, count(*) AS courses,
	count(*) FILTER (WHERE s.availability = 'OFFERED') AS offered, count(*) FILTER (WHERE s.availability = 'NOT_OFFERED') AS not_offered,
	count(*) FILTER (WHERE s.availability = 'NOT_SEARCHED') AS not_searched,
	CASE WHEN count(*) FILTER (WHERE s.availability = 'NOT_SEARCHED') > 0 THEN 'UNKNOWN_SOME_COURSES_NOT_SEARCHED'
		WHEN count(*) FILTER (WHERE s.availability = 'OFFERED') = count(*) THEN 'COMPLETE_ALL_COURSES_OFFERED'
		WHEN count(*) FILTER (WHERE s.availability = 'OFFERED') = 0 THEN 'NOT_AVAILABLE_THIS_TERM'
		ELSE 'PARTIAL_SOME_COURSES_NOT_OFFERED' END AS status,
	array_agg(s.course_id || ':' || s.availability ORDER BY s.ordinal) AS course_availability,
	bool_and(s.is_ns OR s.catalog_status <> 'CATALOG_PAGE_READ') AS all_courses_ns_designated
FROM catalog_csb.ns_sequence_course_status s JOIN catalog_csb.ns_sequence q USING (sequence_key)
GROUP BY s.term_code, s.sequence_key, q.family, q.listed_credits_text;
COMMENT ON VIEW catalog_csb.ns_sequence_status IS 'Per suggested sequence and term: COMPLETE (every course has sections), PARTIAL, NOT_AVAILABLE, or UNKNOWN (a course nobody searched). A suggestion, never a requirement; a complete sequence still needs the prerequisites below.';

-- Does each later course of a sequence have the earlier ones as prerequisites (catalog statement)? What else does it still need?
CREATE OR REPLACE VIEW catalog_csb.ns_sequence_dependency AS
SELECT sc.sequence_key, sc.ordinal, sc.course_id AS course_id, c.prerequisite_status, c.prerequisite_text,
	ARRAY(SELECT e.course_id FROM catalog_csb.ns_sequence_course e WHERE e.sequence_key = sc.sequence_key AND e.ordinal < sc.ordinal ORDER BY e.ordinal) AS earlier_courses,
	CASE WHEN c.prerequisite_status = 'NOT_FOUND' THEN 'COURSE_NOT_IN_CATALOG'
		WHEN c.prerequisite_status = 'TEXT_ONLY' THEN 'PREREQUISITE_TEXT_NOT_PARSED'
		WHEN c.prerequisite_status = 'NONE_LISTED' THEN 'CATALOG_LISTS_NO_PREREQUISITE'
		WHEN NOT (catalog_csb.expression_courses(c.prerequisite_expression) && ARRAY(SELECT e.course_id FROM catalog_csb.ns_sequence_course e WHERE e.sequence_key = sc.sequence_key AND e.ordinal < sc.ordinal))
			THEN 'EARLIER_COURSES_NOT_NAMED_AS_PREREQUISITE'
		WHEN catalog_csb.expression_satisfied(c.prerequisite_expression, ARRAY(SELECT e.course_id FROM catalog_csb.ns_sequence_course e WHERE e.sequence_key = sc.sequence_key AND e.ordinal < sc.ordinal))
			THEN 'SATISFIED_BY_EARLIER_COURSES_ALONE'
		ELSE 'EARLIER_COURSES_NAMED_BUT_MORE_REQUIRED' END AS dependency_status,
	ARRAY(SELECT x FROM unnest(catalog_csb.expression_courses(c.prerequisite_expression)) x
		WHERE x <> ALL (ARRAY(SELECT e.course_id FROM catalog_csb.ns_sequence_course e WHERE e.sequence_key = sc.sequence_key AND e.ordinal < sc.ordinal))) AS other_courses_named
FROM catalog_csb.ns_sequence_course sc JOIN catalog_csb.catalog_course c ON c.course_id = sc.course_id
WHERE sc.ordinal > 1;
COMMENT ON VIEW catalog_csb.ns_sequence_dependency IS 'Compares each sequence with the catalog prerequisites of its later courses (OR groups count as satisfied by any listed course). EARLIER_COURSES_NAMED_BUT_MORE_REQUIRED means the sequence alone is not enough (for example a mathematics course); EARLIER_COURSES_NOT_NAMED_AS_PREREQUISITE means the catalog does not make the first course a prerequisite.';
