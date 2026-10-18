-- TEST ONLY, executed only after AuthoritativeCatalogFixture creates a fresh, validated loopback test database.
-- Reuse Data's real tables, constraints and readiness views; never replace the DAO, datasource or solver.
CREATE SCHEMA test_fixture;
CREATE TABLE test_fixture.section_status (crn text PRIMARY KEY, status text NOT NULL CHECK (status IN ('OPEN','CLOSED','UNKNOWN')));

-- Production views correctly report UNKNOWN historical availability. This test-only overlay supplies explicit
-- statuses to exercise the same JDBC query/filtering path without changing Data's checked-in view or inventing live seats.
ALTER VIEW catalog_api.section_offering RENAME TO source_section_offering;
CREATE VIEW catalog_api.section_offering AS
SELECT s.term_code, s.course_id, s.crn, s.section_code, s.credits, s.component_type, s.lecture_crn,
       coalesce(f.status, s.status) AS status
FROM catalog_api.source_section_offering s LEFT JOIN test_fixture.section_status f USING (crn);
INSERT INTO test_fixture.section_status VALUES ('12275','OPEN'),('13132','OPEN'),('12638','CLOSED');

-- Entirely synthetic courses in the authoritative schema, with confirmed integral credits/link metadata.
DO $$
DECLARE
    item record;
    course_uuid uuid;
    section_uuid uuid;
    source_uuid uuid;
    term_uuid uuid;
BEGIN
    SELECT source_id INTO source_uuid FROM cse007_snapshot.course WHERE code='CSE-007';
    SELECT term_id INTO term_uuid FROM cse007_snapshot.term WHERE term_code='202710';
    FOR item IN SELECT * FROM (VALUES
        ('MATH-021',4,'40100','SCHEDULED'),('ENGL-001',3,'40200','TBA'),
        ('OPT-100',1,'50100','ASYNCHRONOUS'),('OPT-101',1,'50101','ASYNCHRONOUS'),
        ('OPT-102',1,'50102','ASYNCHRONOUS'),('OPT-103',1,'50103','ASYNCHRONOUS'),
        ('OPT-104',1,'50104','ASYNCHRONOUS'),('OPT-105',1,'50105','ASYNCHRONOUS'),
        ('OPT-106',1,'50106','ASYNCHRONOUS'),('OPT-107',1,'50107','ASYNCHRONOUS'),
        ('OPT-108',1,'50108','ASYNCHRONOUS'),('OPT-109',1,'50109','ASYNCHRONOUS'),
        ('OVR-018',18,'60018','ASYNCHRONOUS'),('OVR-019',19,'60019','ASYNCHRONOUS'),('OVR-021',21,'60021','ASYNCHRONOUS')
    ) AS f(code,credits,crn,meeting_status)
    LOOP
        course_uuid := md5('course-' || item.code)::uuid;
        section_uuid := md5('section-' || item.crn)::uuid;
        INSERT INTO cse007_snapshot.course(course_id,code,subject,course_number,title,credits,prerequisite_status,source_id)
        VALUES(course_uuid,item.code,split_part(item.code,'-',1),split_part(item.code,'-',2),
               'Synthetic integration ' || item.code,item.credits,'UNKNOWN',source_uuid);
        INSERT INTO cse007_snapshot.section(section_id,course_id,term_id,crn,section_code,component_type,credits,
            credits_verification_status,delivery_type,meeting_status,link_status,source_status,source_id)
        VALUES(section_uuid,course_uuid,term_uuid,item.crn,'010','LECTURE',item.credits,'USER_CONFIRMED',
               'UNKNOWN',item.meeting_status,'GROUP_CONFIRMED','HISTORICAL',source_uuid);
    END LOOP;
    -- A conflicting MATH lecture and an alternative that fits the CSE lecture/labs.
    INSERT INTO cse007_snapshot.section(section_id,course_id,term_id,crn,section_code,component_type,credits,
        credits_verification_status,delivery_type,meeting_status,link_status,source_status,source_id)
    SELECT md5('section-40101')::uuid,course_id,term_id,'40101','020',component_type,credits,
        credits_verification_status,delivery_type,meeting_status,link_status,source_status,source_id
    FROM cse007_snapshot.section WHERE crn='40100';
    INSERT INTO cse007_snapshot.meeting(meeting_id,section_id,day_of_week,start_time,end_time,location,source_status,source_id)
    VALUES(md5('meeting-40100')::uuid,md5('section-40100')::uuid,1,'09:30','10:45','Synthetic room','HISTORICAL',source_uuid),
          (md5('meeting-40101')::uuid,md5('section-40101')::uuid,2,'10:30','11:45','Synthetic room','HISTORICAL',source_uuid),
          (md5('meeting-cse-extra')::uuid,(SELECT section_id FROM cse007_snapshot.section WHERE crn='12275'),
           4,'14:00','14:50','Synthetic extra meeting','HISTORICAL',source_uuid);
    INSERT INTO cse007_snapshot.course_requirement(requirement_id,course_id,requirement_type,requirement_expression,assessment_status,source_id)
    VALUES(md5('math-prereq')::uuid,md5('course-MATH-021')::uuid,'PREREQUISITE','{"op":"COURSE","code":"CSE-007"}',
           'RULE_DEFINED',source_uuid);
END $$;
