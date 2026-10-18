SET search_path TO cse007_demo;
SELECT c.code, c.title, c.credits, s.section_id, s.component_type,
 s.source_status, m.day_of_week, m.start_time, m.end_time, m.location
FROM course c JOIN section s USING (course_id) JOIN meeting m USING (section_id)
ORDER BY s.section_id, m.day_of_week;
-- Count course credits once per pair, not per section.
SELECT l.primary_section_id AS lecture, l.compatible_section_id AS lab, c.credits
FROM section_link l JOIN course c USING (course_id)
WHERE NOT EXISTS (
 SELECT 1 FROM meeting a JOIN meeting b ON a.day_of_week=b.day_of_week
 AND a.start_time < b.end_time AND b.start_time < a.end_time
 WHERE a.section_id=l.primary_section_id AND b.section_id=l.compatible_section_id
) ORDER BY lecture, lab;
