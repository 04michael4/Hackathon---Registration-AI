BEGIN;
SET LOCAL search_path TO cse007_demo;
INSERT INTO course VALUES (
 'lehigh-cse-007', 'CSE 007', 'Introduction to Programming',
 'Java programming fundamentals, problem solving, arrays, methods, and object-oriented concepts. Summary of the catalog description; prerequisite eligibility is not evaluated.',
 4, 'https://catalog.lehigh.edu/courselisting/cse/', '2026-10-16T01:02:12Z', '0,4 Credits'
);
INSERT INTO term VALUES ('DEMO-2027-SPRING', 'Spring 2027 (DEMO ONLY)', '2026-2027', true);
-- All identifiers, instructors, rooms, times, and coupling below are invented.
-- No CRNs, seats, enrollment status, or verified prerequisite claims.
INSERT INTO section(section_id, course_id, term_id, section_code, component_type, instructor, delivery_type, source_status) VALUES
 ('demo-cse007-l01','lehigh-cse-007','DEMO-2027-SPRING','DEMO-L01','LECTURE',NULL,'IN_PERSON','DEMO_SIMULATED'),
 ('demo-cse007-l02','lehigh-cse-007','DEMO-2027-SPRING','DEMO-L02','LECTURE',NULL,'IN_PERSON','DEMO_SIMULATED'),
 ('demo-cse007-b01','lehigh-cse-007','DEMO-2027-SPRING','DEMO-B01','LAB',NULL,'IN_PERSON','DEMO_SIMULATED'),
 ('demo-cse007-b02','lehigh-cse-007','DEMO-2027-SPRING','DEMO-B02','LAB',NULL,'IN_PERSON','DEMO_SIMULATED'),
 ('demo-cse007-b03','lehigh-cse-007','DEMO-2027-SPRING','DEMO-B03','LAB',NULL,'IN_PERSON','DEMO_SIMULATED');
INSERT INTO meeting(meeting_id, section_id, day_of_week, start_time, end_time, location) VALUES
 ('demo-m01','demo-cse007-l01',1,'09:00','09:50','DEMO Lecture Room A'),
 ('demo-m02','demo-cse007-l01',3,'09:00','09:50','DEMO Lecture Room A'),
 ('demo-m03','demo-cse007-l01',5,'09:00','09:50','DEMO Lecture Room A'),
 ('demo-m04','demo-cse007-l02',1,'11:00','11:50','DEMO Lecture Room B'),
 ('demo-m05','demo-cse007-l02',3,'11:00','11:50','DEMO Lecture Room B'),
 ('demo-m06','demo-cse007-l02',5,'11:00','11:50','DEMO Lecture Room B'),
 ('demo-m07','demo-cse007-b01',2,'14:00','15:00','DEMO Lab A'),
 ('demo-m08','demo-cse007-b02',4,'14:00','15:00','DEMO Lab B'),
 ('demo-m09','demo-cse007-b03',3,'11:30','12:30','DEMO Lab C');
INSERT INTO section_link VALUES
 ('demo-cse007-l01','LAB','demo-cse007-b01','lehigh-cse-007','DEMO-2027-SPRING'),
 ('demo-cse007-l01','LAB','demo-cse007-b02','lehigh-cse-007','DEMO-2027-SPRING'),
 ('demo-cse007-l02','LAB','demo-cse007-b02','lehigh-cse-007','DEMO-2027-SPRING'),
 ('demo-cse007-l02','LAB','demo-cse007-b03','lehigh-cse-007','DEMO-2027-SPRING');
-- Exactly one linked lab is required per lecture. An allowed pairing may still
-- conflict in time (L02/B03); a scheduler must check both rules independently.
COMMIT;
