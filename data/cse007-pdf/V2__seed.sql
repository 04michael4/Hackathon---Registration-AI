-- Generated from fixture.json by build_seed.py. Do not edit by hand.

-- Stable UUIDs and upserts preserve references. Run in one transaction.

SET LOCAL search_path TO cse007_pdf_demo;

INSERT INTO course (course_id, code, title, description, credits, source_url)
SELECT course_id, code, title, description, credits, source_url FROM jsonb_populate_recordset(NULL::course,
$fixture$[
  {
    "course_id": "00000007-0000-4000-8000-000000000001",
    "code": "CSE-007",
    "title": "Introduction to Programming",
    "description": "Java programming fundamentals, problem solving, methods, arrays and object-oriented concepts. Paraphrased catalog summary.",
    "credits": 4,
    "source_url": "https://catalog.lehigh.edu/courselisting/cse/"
  }
]$fixture$::jsonb)
ON CONFLICT (course_id) DO UPDATE SET code = EXCLUDED.code, title = EXCLUDED.title, description = EXCLUDED.description, credits = EXCLUDED.credits, source_url = EXCLUDED.source_url;

INSERT INTO term (term_id, name, academic_year, term_code)
SELECT term_id, name, academic_year, term_code FROM jsonb_populate_recordset(NULL::term,
$fixture$[
  {
    "term_id": "00000007-0001-4000-8000-000000000001",
    "name": "Spring 2027 (DEMO ONLY)",
    "academic_year": "2026-2027",
    "term_code": "DEMO-2027-SPRING"
  }
]$fixture$::jsonb)
ON CONFLICT (term_id) DO UPDATE SET name = EXCLUDED.name, academic_year = EXCLUDED.academic_year, term_code = EXCLUDED.term_code;

INSERT INTO section (section_id, course_id, term_id, section_code, instructor, delivery_type, component_type, credits, source_status, source_url, verified_at, meeting_status)
SELECT section_id, course_id, term_id, section_code, instructor, delivery_type, component_type, credits, source_status, source_url, verified_at, meeting_status FROM jsonb_populate_recordset(NULL::section,
$fixture$[
  {
    "section_id": "00000007-0002-4000-8000-000000000001",
    "course_id": "00000007-0000-4000-8000-000000000001",
    "term_id": "00000007-0001-4000-8000-000000000001",
    "section_code": "DEMO-L01",
    "instructor": null,
    "delivery_type": "IN_PERSON",
    "component_type": "LECTURE",
    "credits": 4,
    "source_status": "DEMO_SIMULATED",
    "source_url": null,
    "verified_at": null,
    "meeting_status": "SCHEDULED"
  },
  {
    "section_id": "00000007-0002-4000-8000-000000000002",
    "course_id": "00000007-0000-4000-8000-000000000001",
    "term_id": "00000007-0001-4000-8000-000000000001",
    "section_code": "DEMO-L02",
    "instructor": null,
    "delivery_type": "IN_PERSON",
    "component_type": "LECTURE",
    "credits": 4,
    "source_status": "DEMO_SIMULATED",
    "source_url": null,
    "verified_at": null,
    "meeting_status": "SCHEDULED"
  },
  {
    "section_id": "00000007-0002-4000-8000-000000000003",
    "course_id": "00000007-0000-4000-8000-000000000001",
    "term_id": "00000007-0001-4000-8000-000000000001",
    "section_code": "DEMO-B01",
    "instructor": null,
    "delivery_type": "IN_PERSON",
    "component_type": "LAB",
    "credits": 0,
    "source_status": "DEMO_SIMULATED",
    "source_url": null,
    "verified_at": null,
    "meeting_status": "SCHEDULED"
  },
  {
    "section_id": "00000007-0002-4000-8000-000000000004",
    "course_id": "00000007-0000-4000-8000-000000000001",
    "term_id": "00000007-0001-4000-8000-000000000001",
    "section_code": "DEMO-B02",
    "instructor": null,
    "delivery_type": "IN_PERSON",
    "component_type": "LAB",
    "credits": 0,
    "source_status": "DEMO_SIMULATED",
    "source_url": null,
    "verified_at": null,
    "meeting_status": "SCHEDULED"
  },
  {
    "section_id": "00000007-0002-4000-8000-000000000005",
    "course_id": "00000007-0000-4000-8000-000000000001",
    "term_id": "00000007-0001-4000-8000-000000000001",
    "section_code": "DEMO-B03",
    "instructor": null,
    "delivery_type": "IN_PERSON",
    "component_type": "LAB",
    "credits": 0,
    "source_status": "DEMO_SIMULATED",
    "source_url": null,
    "verified_at": null,
    "meeting_status": "SCHEDULED"
  },
  {
    "section_id": "00000007-0002-4000-8000-000000000006",
    "course_id": "00000007-0000-4000-8000-000000000001",
    "term_id": "00000007-0001-4000-8000-000000000001",
    "section_code": "DEMO-L03-TBA",
    "instructor": null,
    "delivery_type": "UNKNOWN",
    "component_type": "LECTURE",
    "credits": 4,
    "source_status": "DEMO_SIMULATED",
    "source_url": null,
    "verified_at": null,
    "meeting_status": "TBA"
  }
]$fixture$::jsonb)
ON CONFLICT (section_id) DO UPDATE SET course_id = EXCLUDED.course_id, term_id = EXCLUDED.term_id, section_code = EXCLUDED.section_code, instructor = EXCLUDED.instructor, delivery_type = EXCLUDED.delivery_type, component_type = EXCLUDED.component_type, credits = EXCLUDED.credits, source_status = EXCLUDED.source_status, source_url = EXCLUDED.source_url, verified_at = EXCLUDED.verified_at, meeting_status = EXCLUDED.meeting_status;

INSERT INTO meeting (meeting_id, section_id, day_of_week, start_time, end_time, location, source_status, source_url, verified_at)
SELECT meeting_id, section_id, day_of_week, start_time, end_time, location, source_status, source_url, verified_at FROM jsonb_populate_recordset(NULL::meeting,
$fixture$[
  {
    "meeting_id": "00000007-0003-4000-8000-000000000001",
    "section_id": "00000007-0002-4000-8000-000000000001",
    "day_of_week": 1,
    "start_time": "09:00",
    "end_time": "09:50",
    "location": "DEMO Lecture Room A",
    "source_status": "DEMO_SIMULATED",
    "source_url": null,
    "verified_at": null
  },
  {
    "meeting_id": "00000007-0003-4000-8000-000000000002",
    "section_id": "00000007-0002-4000-8000-000000000001",
    "day_of_week": 3,
    "start_time": "09:00",
    "end_time": "09:50",
    "location": "DEMO Lecture Room A",
    "source_status": "DEMO_SIMULATED",
    "source_url": null,
    "verified_at": null
  },
  {
    "meeting_id": "00000007-0003-4000-8000-000000000003",
    "section_id": "00000007-0002-4000-8000-000000000001",
    "day_of_week": 5,
    "start_time": "09:00",
    "end_time": "09:50",
    "location": "DEMO Lecture Room A",
    "source_status": "DEMO_SIMULATED",
    "source_url": null,
    "verified_at": null
  },
  {
    "meeting_id": "00000007-0003-4000-8000-000000000004",
    "section_id": "00000007-0002-4000-8000-000000000002",
    "day_of_week": 1,
    "start_time": "11:00",
    "end_time": "11:50",
    "location": "DEMO Lecture Room B",
    "source_status": "DEMO_SIMULATED",
    "source_url": null,
    "verified_at": null
  },
  {
    "meeting_id": "00000007-0003-4000-8000-000000000005",
    "section_id": "00000007-0002-4000-8000-000000000002",
    "day_of_week": 3,
    "start_time": "11:00",
    "end_time": "11:50",
    "location": "DEMO Lecture Room B",
    "source_status": "DEMO_SIMULATED",
    "source_url": null,
    "verified_at": null
  },
  {
    "meeting_id": "00000007-0003-4000-8000-000000000006",
    "section_id": "00000007-0002-4000-8000-000000000002",
    "day_of_week": 5,
    "start_time": "11:00",
    "end_time": "11:50",
    "location": "DEMO Lecture Room B",
    "source_status": "DEMO_SIMULATED",
    "source_url": null,
    "verified_at": null
  },
  {
    "meeting_id": "00000007-0003-4000-8000-000000000007",
    "section_id": "00000007-0002-4000-8000-000000000003",
    "day_of_week": 2,
    "start_time": "14:00",
    "end_time": "15:00",
    "location": "DEMO Lab A",
    "source_status": "DEMO_SIMULATED",
    "source_url": null,
    "verified_at": null
  },
  {
    "meeting_id": "00000007-0003-4000-8000-000000000008",
    "section_id": "00000007-0002-4000-8000-000000000004",
    "day_of_week": 4,
    "start_time": "14:00",
    "end_time": "15:00",
    "location": "DEMO Lab B",
    "source_status": "DEMO_SIMULATED",
    "source_url": null,
    "verified_at": null
  },
  {
    "meeting_id": "00000007-0003-4000-8000-000000000009",
    "section_id": "00000007-0002-4000-8000-000000000005",
    "day_of_week": 3,
    "start_time": "11:30",
    "end_time": "12:30",
    "location": "DEMO Lab C",
    "source_status": "DEMO_SIMULATED",
    "source_url": null,
    "verified_at": null
  }
]$fixture$::jsonb)
ON CONFLICT (meeting_id) DO UPDATE SET section_id = EXCLUDED.section_id, day_of_week = EXCLUDED.day_of_week, start_time = EXCLUDED.start_time, end_time = EXCLUDED.end_time, location = EXCLUDED.location, source_status = EXCLUDED.source_status, source_url = EXCLUDED.source_url, verified_at = EXCLUDED.verified_at;

INSERT INTO section_link (link_id, primary_section_id, required_component_group, compatible_section_id)
SELECT link_id, primary_section_id, required_component_group, compatible_section_id FROM jsonb_populate_recordset(NULL::section_link,
$fixture$[
  {
    "link_id": "00000007-0004-4000-8000-000000000001",
    "primary_section_id": "00000007-0002-4000-8000-000000000001",
    "required_component_group": "LAB",
    "compatible_section_id": "00000007-0002-4000-8000-000000000003"
  },
  {
    "link_id": "00000007-0004-4000-8000-000000000002",
    "primary_section_id": "00000007-0002-4000-8000-000000000001",
    "required_component_group": "LAB",
    "compatible_section_id": "00000007-0002-4000-8000-000000000004"
  },
  {
    "link_id": "00000007-0004-4000-8000-000000000003",
    "primary_section_id": "00000007-0002-4000-8000-000000000002",
    "required_component_group": "LAB",
    "compatible_section_id": "00000007-0002-4000-8000-000000000004"
  },
  {
    "link_id": "00000007-0004-4000-8000-000000000004",
    "primary_section_id": "00000007-0002-4000-8000-000000000002",
    "required_component_group": "LAB",
    "compatible_section_id": "00000007-0002-4000-8000-000000000005"
  }
]$fixture$::jsonb)
ON CONFLICT (link_id) DO UPDATE SET primary_section_id = EXCLUDED.primary_section_id, required_component_group = EXCLUDED.required_component_group, compatible_section_id = EXCLUDED.compatible_section_id;

-- prerequisite_rule: no seed rows; see fixture_metadata.

INSERT INTO student_profile (profile_id, major, graduation_target, completed_course_codes, credit_preferences, is_demo)
SELECT profile_id, major, graduation_target, completed_course_codes, credit_preferences, is_demo FROM jsonb_populate_recordset(NULL::student_profile,
$fixture$[
  {
    "profile_id": "00000007-0005-4000-8000-000000000001",
    "major": "Computer Science and Business (synthetic demo)",
    "graduation_target": "DEMO-2030-SPRING",
    "completed_course_codes": [],
    "credit_preferences": {
      "minCredits": 4,
      "maxCredits": 4
    },
    "is_demo": true
  }
]$fixture$::jsonb)
ON CONFLICT (profile_id) DO UPDATE SET major = EXCLUDED.major, graduation_target = EXCLUDED.graduation_target, completed_course_codes = EXCLUDED.completed_course_codes, credit_preferences = EXCLUDED.credit_preferences, is_demo = EXCLUDED.is_demo;

INSERT INTO schedule_preference (preference_id, profile_id, term_id, structured_constraints_json, original_request, schema_version)
SELECT preference_id, profile_id, term_id, structured_constraints_json, original_request, schema_version FROM jsonb_populate_recordset(NULL::schedule_preference,
$fixture$[
  {
    "preference_id": "00000007-0006-4000-8000-000000000001",
    "profile_id": "00000007-0005-4000-8000-000000000001",
    "term_id": "00000007-0001-4000-8000-000000000001",
    "structured_constraints_json": {
      "requiredCourseCodes": [
        "CSE-007"
      ],
      "optionalCourseCodes": [],
      "minCredits": 4,
      "maxCredits": 4,
      "blockedTimes": [
        {
          "dayOfWeek": 1,
          "startTime": "00:00",
          "endTime": "10:00"
        },
        {
          "dayOfWeek": 2,
          "startTime": "00:00",
          "endTime": "10:00"
        },
        {
          "dayOfWeek": 3,
          "startTime": "00:00",
          "endTime": "10:00"
        },
        {
          "dayOfWeek": 4,
          "startTime": "00:00",
          "endTime": "10:00"
        },
        {
          "dayOfWeek": 5,
          "startTime": "00:00",
          "endTime": "10:00"
        }
      ],
      "lockedSectionIds": [],
      "softPreferences": {}
    },
    "original_request": "Demo: schedule CSE 007 for 4 credits. No classes before 10 AM.",
    "schema_version": 1
  }
]$fixture$::jsonb)
ON CONFLICT (preference_id) DO UPDATE SET profile_id = EXCLUDED.profile_id, term_id = EXCLUDED.term_id, structured_constraints_json = EXCLUDED.structured_constraints_json, original_request = EXCLUDED.original_request, schema_version = EXCLUDED.schema_version;

-- generated_schedule: no seed rows; see fixture_metadata.

-- generated_schedule_section: no seed rows; see fixture_metadata.
