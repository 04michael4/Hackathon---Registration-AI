-- catalog_research V4: Class Details inspected for grid-staged (non-CSE) courses.
-- Until now a grid-staged offering was BLOCKED (GRID_ONLY_DETAILS_NOT_INSPECTED): only Banner's results grid had been captured, so its
-- prerequisites, corequisites, restrictions and lab/recitation links were unknown. import_noncse_details records what a person's read-only
-- Class Details captures showed, and an inspected offering is then judged by the same rules as the CSE ones (CONDITIONAL, with its conditions).
-- Additive: it changes no merged table definition except widening import_run.kind, and replaces only the offering_tier view (same columns).
-- Run after V1-V3.
\set ON_ERROR_STOP on

-- import_run.kind may now be DETAILS.
DO $$
DECLARE
	cname text;
BEGIN
	SELECT conname INTO cname FROM pg_constraint
		WHERE conrelid = 'catalog_research.import_run'::regclass AND contype = 'c' AND pg_get_constraintdef(oid) ~ 'TERM_CATALOG';
	IF cname IS NOT NULL AND (SELECT pg_get_constraintdef(oid) FROM pg_constraint WHERE conname = cname AND conrelid = 'catalog_research.import_run'::regclass) !~ 'DETAILS' THEN
		EXECUTE format('ALTER TABLE catalog_research.import_run DROP CONSTRAINT %I', cname);
		ALTER TABLE catalog_research.import_run ADD CONSTRAINT import_run_kind_check CHECK (kind IN ('TERM_CATALOG', 'GRID_STAGING', 'PROGRAMS', 'DETAILS'));
	END IF;
END $$;

-- Which sections' Class Details were actually read for an offering (the proof that its details were inspected), with the hash of the capture
-- files and what Class Details said about each section, for cross-checking against the grid.
CREATE TABLE IF NOT EXISTS catalog_research.detail_inspection (
	term_code     text NOT NULL,
	offering_id   text NOT NULL,
	run_id        bigint NOT NULL REFERENCES catalog_research.import_run,
	inspected_crns text[] NOT NULL CHECK (cardinality(inspected_crns) > 0),
	capture_sha256 text NOT NULL CHECK (capture_sha256 ~ '^[0-9a-f]{64}$'),
	PRIMARY KEY (term_code, offering_id),
	FOREIGN KEY (term_code, offering_id) REFERENCES catalog_research.offering
);

CREATE TABLE IF NOT EXISTS catalog_research.section_detail_observation (
	term_code     text NOT NULL,
	crn           text NOT NULL,
	run_id        bigint NOT NULL REFERENCES catalog_research.import_run,
	schedule_type text NOT NULL,
	credit_hours  numeric(4,1) CHECK (credit_hours >= 0),     -- NULL when Class Details states none (e.g. a variable-credit range)
	PRIMARY KEY (term_code, crn),
	FOREIGN KEY (term_code, crn) REFERENCES catalog_research.section
);

-- Class Details disagrees with the grid about a section: the offering stays BLOCKED until a person looks.
CREATE OR REPLACE VIEW catalog_research.detail_mismatch AS
SELECT s.term_code, s.crn, 'SCHEDULE_TYPE_DIFFERS'::text AS problem, s.schedule_type AS stored, d.schedule_type AS detail
FROM catalog_research.section s JOIN catalog_research.section_detail_observation d USING (term_code, crn)
WHERE s.schedule_type IS DISTINCT FROM d.schedule_type
UNION ALL
SELECT s.term_code, s.crn, 'CREDITS_DIFFER', s.credits::text, d.credit_hours::text
FROM catalog_research.section s JOIN catalog_research.section_detail_observation d USING (term_code, crn)
WHERE d.credit_hours IS NOT NULL AND s.component_type = 'LECTURE' AND s.credits::numeric <> d.credit_hours;

-- ---------------------------------------------------------------------------------------------------------------------------------------
-- The writer. payload: {"courses":[{term, courseId, inspectedCrns[], restrictions{status,text,rules[{kind,polarity,values[]}]},
--   prerequisite{status,text,expression}, corequisite{status,text,expression}, links[{lab,lecture,bothSides}],
--   classDetails[{crn, creditHours, scheduleType}]}], "captureSha256": "<hex>"}
-- One transaction. Fails (changing nothing) on an unknown offering, a CRN that is not a stored section, a link that is not lab->lecture of the same
-- offering, or a lab linked to two lectures. Replaces the requirement rows and links of the offerings it names; touches no other offering.
CREATE OR REPLACE FUNCTION catalog_research.import_noncse_details(payload jsonb, label text, sha text) RETURNS bigint
LANGUAGE plpgsql AS $$
DECLARE
	rid bigint;
	c jsonb;
	l jsonb;
	off_id text;
	one_crn text;
	n integer;
	kind text;
	req jsonb;
	status text;
	courses integer;
BEGIN
	IF jsonb_typeof(payload -> 'courses') IS DISTINCT FROM 'array' OR jsonb_array_length(payload -> 'courses') = 0 THEN
		RAISE EXCEPTION 'payload needs a non-empty "courses" array';
	END IF;
	courses := jsonb_array_length(payload -> 'courses');
	INSERT INTO catalog_research.import_run (kind, term_code, input_sha256, input_label, offering_count, section_count)
		VALUES ('DETAILS', payload -> 'courses' -> 0 ->> 'term', sha, label, courses,
			(SELECT coalesce(sum(jsonb_array_length(x -> 'inspectedCrns')), 0) FROM jsonb_array_elements(payload -> 'courses') x))
		RETURNING run_id INTO rid;
	FOR c IN SELECT * FROM jsonb_array_elements(payload -> 'courses') LOOP
		off_id := c ->> 'courseId';
		IF NOT EXISTS (SELECT 1 FROM catalog_research.offering o WHERE o.term_code = c ->> 'term' AND o.offering_id = off_id AND o.data_source = 'BANNER_GRID_STAGING') THEN
			RAISE EXCEPTION '% is not a grid-staged offering of term %', off_id, c ->> 'term';
		END IF;
		FOR one_crn IN SELECT jsonb_array_elements_text(c -> 'inspectedCrns') LOOP
			IF NOT EXISTS (SELECT 1 FROM catalog_research.section s WHERE s.term_code = c ->> 'term' AND s.offering_id = off_id AND s.crn = one_crn) THEN
				RAISE EXCEPTION 'CRN % is not a stored section of %', one_crn, off_id;
			END IF;
		END LOOP;
		-- requirement rows (status + evidence follow the table's CHECKs; silence is the owner's "none listed" rule, user-supplied evidence)
		FOREACH kind IN ARRAY ARRAY['PREREQUISITE', 'COREQUISITE', 'ENROLLMENT_RESTRICTION'] LOOP
			req := CASE kind WHEN 'PREREQUISITE' THEN c -> 'prerequisite' WHEN 'COREQUISITE' THEN c -> 'corequisite' ELSE c -> 'restrictions' END;
			status := req ->> 'status';
			IF status NOT IN ('NONE_LISTED', 'TEXT_ONLY', 'RULE_DEFINED') THEN
				RAISE EXCEPTION 'unexpected % status % for %', kind, status, off_id;
			END IF;
			INSERT INTO catalog_research.requirement_status (term_code, offering_id, requirement_type, status, evidence_level, source_text)
				VALUES (c ->> 'term', off_id, kind, status, CASE status WHEN 'NONE_LISTED' THEN 'USER_CLARIFICATION' ELSE 'BANNER' END,
					CASE status WHEN 'NONE_LISTED' THEN NULL ELSE req ->> 'text' END)
				ON CONFLICT (term_code, offering_id, requirement_type) DO UPDATE
				SET status = EXCLUDED.status, evidence_level = EXCLUDED.evidence_level, source_text = EXCLUDED.source_text;
		END LOOP;
		DELETE FROM catalog_research.eligibility_rule WHERE term_code = c ->> 'term' AND offering_id = off_id;
		INSERT INTO catalog_research.eligibility_rule (term_code, offering_id, rule_kind, polarity, rule_values, source_text, evidence_level, parse_status)
			SELECT c ->> 'term', off_id, r ->> 'kind', r ->> 'polarity', ARRAY(SELECT jsonb_array_elements_text(r -> 'values')),
				c -> 'restrictions' ->> 'text', 'BANNER', 'STRUCTURED'
			FROM jsonb_array_elements(c -> 'restrictions' -> 'rules') r;
		FOREACH kind IN ARRAY ARRAY['PREREQUISITE', 'COREQUISITE'] LOOP
			req := CASE kind WHEN 'PREREQUISITE' THEN c -> 'prerequisite' ELSE c -> 'corequisite' END;
			IF req ->> 'status' IN ('TEXT_ONLY', 'RULE_DEFINED') THEN
				INSERT INTO catalog_research.eligibility_rule (term_code, offering_id, rule_kind, polarity, rule_values, expression, source_text, evidence_level, parse_status)
					VALUES (c ->> 'term', off_id, kind, 'REQUIRES', '{}', CASE WHEN req ->> 'status' = 'RULE_DEFINED' THEN req -> 'expression' END, req ->> 'text', 'BANNER',
						CASE WHEN req ->> 'status' = 'RULE_DEFINED' THEN 'STRUCTURED' ELSE 'TEXT_ONLY' END);
			END IF;
		END LOOP;
		-- links: lab/recitation -> lecture of the SAME offering, one lecture per lab
		FOR l IN SELECT * FROM jsonb_array_elements(c -> 'links') LOOP
			IF (SELECT count(DISTINCT x ->> 'lecture') FROM jsonb_array_elements(c -> 'links') x WHERE x ->> 'lab' = l ->> 'lab') > 1 THEN
				RAISE EXCEPTION 'lab % is linked to more than one lecture in %', l ->> 'lab', off_id;
			END IF;
			UPDATE catalog_research.section s
				SET lecture_crn = l ->> 'lecture', link_status = CASE WHEN (l ->> 'bothSides')::boolean THEN 'CONFIRMED' ELSE 'LISTED_BY_LECTURE_ONLY' END
				WHERE s.term_code = c ->> 'term' AND s.offering_id = off_id AND s.crn = l ->> 'lab' AND s.component_type IN ('LAB', 'RECITATION');
			GET DIAGNOSTICS n = ROW_COUNT;
			IF n <> 1 THEN
				RAISE EXCEPTION 'link % -> % does not name a stored lab or recitation of %', l ->> 'lab', l ->> 'lecture', off_id;
			END IF;
		END LOOP;
		DELETE FROM catalog_research.section_detail_observation d USING catalog_research.section s
			WHERE d.term_code = s.term_code AND d.crn = s.crn AND s.term_code = c ->> 'term' AND s.offering_id = off_id;
		INSERT INTO catalog_research.section_detail_observation (term_code, crn, run_id, schedule_type, credit_hours)
			SELECT c ->> 'term', d ->> 'crn', rid, d ->> 'scheduleType', nullif(d ->> 'creditHours', '')::numeric
			FROM jsonb_array_elements(c -> 'classDetails') d;
		INSERT INTO catalog_research.detail_inspection (term_code, offering_id, run_id, inspected_crns, capture_sha256)
			VALUES (c ->> 'term', off_id, rid, ARRAY(SELECT jsonb_array_elements_text(c -> 'inspectedCrns')), payload ->> 'captureSha256')
			ON CONFLICT (term_code, offering_id) DO UPDATE
			SET run_id = EXCLUDED.run_id, inspected_crns = EXCLUDED.inspected_crns, capture_sha256 = EXCLUDED.capture_sha256;
	END LOOP;
	RETURN rid;
END $$;
REVOKE EXECUTE ON FUNCTION catalog_research.import_noncse_details(jsonb, text, text) FROM PUBLIC;

-- ---------------------------------------------------------------------------------------------------------------------------------------
-- Tier. Same rules as before, plus: a grid-staged offering whose Class Details were inspected is judged like any other (so it can be
-- CONDITIONAL); a Class Details mismatch, or a lab/recitation not linked to a lecture, blocks; and the ordinary component types
-- Class (a lecture, in the grid's wording) and Recitation are no longer "unsupported" section types.
CREATE OR REPLACE VIEW catalog_research.offering_tier AS
SELECT o.term_code, o.offering_id,
	CASE
		WHEN EXISTS (SELECT 1 FROM catalog_research.evidence_mismatch m JOIN catalog_research.section s ON s.term_code = m.term_code AND s.crn = m.crn
			WHERE s.term_code = o.term_code AND s.offering_id = o.offering_id) THEN 'BLOCKED'
		WHEN EXISTS (SELECT 1 FROM catalog_research.detail_mismatch m JOIN catalog_research.section s ON s.term_code = m.term_code AND s.crn = m.crn
			WHERE s.term_code = o.term_code AND s.offering_id = o.offering_id) THEN 'BLOCKED'
		WHEN o.data_source = 'BANNER_GRID_STAGING' AND NOT EXISTS (SELECT 1 FROM catalog_research.detail_inspection i WHERE i.term_code = o.term_code AND i.offering_id = o.offering_id) THEN 'BLOCKED'
		WHEN o.identity_status <> 'SINGLE' THEN 'BLOCKED'
		WHEN EXISTS (SELECT 1 FROM catalog_research.section s WHERE s.term_code = o.term_code AND s.offering_id = o.offering_id
			AND s.component_type <> 'LECTURE' AND s.lecture_crn IS NULL) THEN 'BLOCKED'
		WHEN u.crns IS NOT NULL THEN 'BLOCKED'
		WHEN o.data_source = 'SNAPSHOT' AND COALESCE(a.schedulable, false) THEN 'READY'
		ELSE 'CONDITIONAL' END AS tier,
	CASE
		WHEN EXISTS (SELECT 1 FROM catalog_research.evidence_mismatch m JOIN catalog_research.section s ON s.term_code = m.term_code AND s.crn = m.crn
			WHERE s.term_code = o.term_code AND s.offering_id = o.offering_id) THEN 'EVIDENCE_MISMATCH'
		WHEN EXISTS (SELECT 1 FROM catalog_research.detail_mismatch m JOIN catalog_research.section s ON s.term_code = m.term_code AND s.crn = m.crn
			WHERE s.term_code = o.term_code AND s.offering_id = o.offering_id) THEN 'DETAIL_MISMATCH'
		WHEN o.data_source = 'BANNER_GRID_STAGING' AND NOT EXISTS (SELECT 1 FROM catalog_research.detail_inspection i WHERE i.term_code = o.term_code AND i.offering_id = o.offering_id) THEN 'GRID_ONLY_DETAILS_NOT_INSPECTED'
		WHEN o.identity_status <> 'SINGLE' THEN 'TOPIC_IDENTITY_UNDECIDED'
		WHEN EXISTS (SELECT 1 FROM catalog_research.section s WHERE s.term_code = o.term_code AND s.offering_id = o.offering_id
			AND s.component_type <> 'LECTURE' AND s.lecture_crn IS NULL) THEN 'COMPONENT_LINKS_NOT_COLLECTED'
		WHEN u.crns IS NOT NULL THEN 'SECTION_TYPE_UNSUPPORTED:' || u.crns
		WHEN o.data_source = 'SNAPSHOT' AND COALESCE(a.schedulable, false) THEN 'SERVED_BY_VERIFIED_CATALOG'
		WHEN p.crns IS NOT NULL THEN 'POLICY_DECISION_ALLOWS_SECTION_TYPE:' || p.crns
		ELSE 'UNRESOLVED_CONDITIONS_REMAIN' END AS tier_reason
FROM catalog_research.offering o
LEFT JOIN catalog_api.course_offering a ON a.term_code = o.term_code AND a.course_id = o.course_id
LEFT JOIN LATERAL (SELECT string_agg(s.crn, ',' ORDER BY s.crn) AS crns FROM catalog_research.section s
	WHERE s.term_code = o.term_code AND s.offering_id = o.offering_id AND s.schedule_type NOT IN ('Lecture', 'Class', 'Lab', 'Recitation')
		AND NOT EXISTS (SELECT 1 FROM catalog_research.active_tier_policy_decision d WHERE d.term_code = s.term_code AND d.crn = s.crn AND d.is_active)) u ON true
LEFT JOIN LATERAL (SELECT string_agg(s.crn, ',' ORDER BY s.crn) AS crns FROM catalog_research.section s
	WHERE s.term_code = o.term_code AND s.offering_id = o.offering_id AND s.schedule_type NOT IN ('Lecture', 'Class', 'Lab', 'Recitation')
		AND EXISTS (SELECT 1 FROM catalog_research.active_tier_policy_decision d WHERE d.term_code = s.term_code AND d.crn = s.crn AND d.is_active)) p ON true;
COMMENT ON VIEW catalog_research.offering_tier IS 'Derived research tier (READY / CONDITIONAL / BLOCKED) with the reason. A classification, not a promotion.';
