\set ON_ERROR_STOP on
SET search_path TO cse007_pdf_demo;

-- Course and section rows, including the incomplete section with no meetings.
SELECT c.code, c.title, s.section_id, s.section_code, s.component_type,
	coalesce(s.credits, c.credits) AS credits,
	s.meeting_status, s.source_status,
	m.day_of_week, m.start_time, m.end_time, m.location,
	array_remove(ARRAY[
		CASE WHEN s.meeting_status = 'TBA' THEN 'INCOMPLETE_MEETING_METADATA' END,
		CASE WHEN s.delivery_type = 'UNKNOWN' THEN 'UNKNOWN_DELIVERY_TYPE' END
	], NULL) AS metadata_warnings
FROM course c JOIN section s USING (course_id)
LEFT JOIN meeting m USING (section_id)
ORDER BY s.section_code, m.day_of_week;

-- This single-course query reports time feasibility only. Prerequisite eligibility
-- remains UNKNOWN. A complete scheduler must also enforce the user's constraints.
SELECT a.section_code AS lecture, b.section_code AS lab,
	coalesce(a.credits, c.credits) + coalesce(b.credits, c.credits) AS total_credits,
	'DEMO_SIMULATED' AS source_status, 'PREREQUISITES_UNKNOWN' AS warning
FROM section_link l JOIN section a ON a.section_id = l.primary_section_id
JOIN section b ON b.section_id = l.compatible_section_id
JOIN course c ON c.course_id = a.course_id
WHERE a.meeting_status = 'SCHEDULED' AND b.meeting_status = 'SCHEDULED'
	AND a.delivery_type <> 'UNKNOWN' AND b.delivery_type <> 'UNKNOWN'
	AND NOT EXISTS (
		SELECT 1 FROM meeting ma JOIN meeting mb ON ma.day_of_week = mb.day_of_week
		AND ma.start_time < mb.end_time AND mb.start_time < ma.end_time
		WHERE ma.section_id = a.section_id AND mb.section_id = b.section_id
	)
ORDER BY lecture, lab;
