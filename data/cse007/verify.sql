\set ON_ERROR_STOP on
SET search_path TO cse007_demo;
DO $$
DECLARE feasible integer; after_ten integer; blocked integer;
BEGIN
 IF (SELECT count(*) FROM course) <> 1 OR (SELECT count(*) FROM section) <> 5
 OR (SELECT count(*) FROM meeting) <> 9 THEN
  RAISE EXCEPTION 'Unexpected fixture counts';
 END IF;
 IF EXISTS (SELECT 1 FROM section_link l JOIN section a ON a.section_id=l.primary_section_id
 JOIN section b ON b.section_id=l.compatible_section_id
 WHERE a.component_type <> 'LECTURE' OR b.component_type <> 'LAB') THEN
  RAISE EXCEPTION 'Invalid component group';
 END IF;
 IF EXISTS (SELECT 1 FROM section s WHERE NOT EXISTS
 (SELECT 1 FROM meeting m WHERE m.section_id=s.section_id)) THEN
  RAISE EXCEPTION 'Section without meetings';
 END IF;
 SELECT count(*), count(*) FILTER (WHERE NOT EXISTS
 (SELECT 1 FROM meeting m WHERE m.section_id IN (l.primary_section_id,l.compatible_section_id)
 AND m.start_time < '10:00'::time)), count(*) FILTER (WHERE NOT EXISTS
 (SELECT 1 FROM meeting m WHERE m.section_id IN (l.primary_section_id,l.compatible_section_id)
 AND m.day_of_week=5 AND m.start_time < '12:00'::time AND m.end_time > '08:00'::time))
 INTO feasible, after_ten, blocked FROM section_link l WHERE NOT EXISTS
 (SELECT 1 FROM meeting a JOIN meeting b ON a.day_of_week=b.day_of_week
 AND a.start_time < b.end_time AND b.start_time < a.end_time
 WHERE a.section_id=l.primary_section_id AND b.section_id=l.compatible_section_id);
 IF feasible <> 3 OR after_ten <> 1 OR blocked <> 0 THEN
  RAISE EXCEPTION 'Scenario mismatch: feasible %, after ten %, Friday blocked %', feasible, after_ten, blocked;
 END IF;
 IF EXISTS (SELECT 1 FROM section WHERE source_status <> 'DEMO_SIMULATED') THEN
  RAISE EXCEPTION 'Unexpected real section claim';
 END IF;
 RAISE NOTICE 'PASS: 1 course, 5 sections, 9 meetings; 3 feasible pairs; 1 after 10; 0 with Friday morning blocked';
END $$;
