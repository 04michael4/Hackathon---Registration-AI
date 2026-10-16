-- Generated from fixture.json by build_seed.py. Do not edit by hand.

-- Stable UUID upserts; apply in a transaction together with verify.sql.

SET LOCAL search_path TO cse007_snapshot;

INSERT INTO source_asset (source_id, file_path, sha256, source_kind, source_url, captured_at, verified_at, coverage_note)
SELECT source_id, file_path, sha256, source_kind, source_url, captured_at, verified_at, coverage_note FROM jsonb_populate_recordset(NULL::source_asset,
$fixture$[
  {
    "source_id": "00000007-0010-4000-8000-000000000001",
    "file_path": "Images for courses/KakaoTalk_20261015_214742198.png",
    "sha256": "82ab5c5e510a612494491df74ee6c49bdd73bdccd0cd3c2f6a1028a57c992bf8",
    "source_kind": "USER_SCREENSHOT",
    "source_url": null,
    "captured_at": null,
    "verified_at": "2026-10-16T01:57:28Z",
    "coverage_note": "Search filters: CSE 007, 2027 Spring Semester."
  },
  {
    "source_id": "00000007-0010-4000-8000-000000000002",
    "file_path": "Images for courses/KakaoTalk_20261015_214757090.png",
    "sha256": "cde35620c69afc240187fb225a13273c5e65702d9648adc3b8fc827edeb035b3",
    "source_kind": "USER_SCREENSHOT",
    "source_url": null,
    "captured_at": null,
    "verified_at": "2026-10-16T01:57:28Z",
    "coverage_note": "Three section rows: CRNs, section codes, Hours, highlighted meeting days, times, instructor, attributes, LINKED flags. Status text is truncated."
  },
  {
    "source_id": "00000007-0010-4000-8000-000000000003",
    "file_path": "Images for courses/KakaoTalk_20261015_214823618.png",
    "sha256": "808b265df0473bdbe6de0f1961050a28f23631794612effec4d4d4cb36b3c805",
    "source_kind": "USER_SCREENSHOT",
    "source_url": null,
    "captured_at": null,
    "verified_at": "2026-10-16T01:57:28Z",
    "coverage_note": "Lab 060 details: term code 202710, CRN 13132, title, campus and grade-mode text. Credit Hours 4 conflicts with the results-row Hours 0."
  },
  {
    "source_id": "00000007-0010-4000-8000-000000000004",
    "file_path": "Images for courses/KakaoTalk_20261015_214850130.png",
    "sha256": "e4dddeff2f2034bc784d8ca6023d09e8b673b50bc09f3953c32c9c3a641c8b36",
    "source_kind": "USER_SCREENSHOT",
    "source_url": null,
    "captured_at": null,
    "verified_at": "2026-10-16T01:57:28Z",
    "coverage_note": "Lab 060: Wednesday/Friday 09:20-10:35, 2027-01-25 through 2027-05-07, Packard Lab room 112, Brian Chen."
  },
  {
    "source_id": "00000007-0010-4000-8000-000000000005",
    "file_path": "Images for courses/KakaoTalk_20261015_214926225.png",
    "sha256": "ac566a235249b04908a4cdd1682368b59562e05fa38533d18b4cf8d500ae08fb",
    "source_kind": "USER_SCREENSHOT",
    "source_url": null,
    "captured_at": null,
    "verified_at": "2026-10-16T01:57:28Z",
    "coverage_note": "Cropped detail panel: right-hand content is unreadable. Does not establish prerequisite or other requirement rules."
  },
  {
    "source_id": "00000007-0010-4000-8000-000000000006",
    "file_path": "Images for courses/KakaoTalk_20261015_214949427.png",
    "sha256": "dabb4450850594fd78e2619e4d0e6e2e4dd0a837a4042e04b43f751abba93539",
    "source_kind": "USER_SCREENSHOT",
    "source_url": null,
    "captured_at": null,
    "verified_at": "2026-10-16T01:57:28Z",
    "coverage_note": "Lab 060 enrollment/waitlist snapshot: actual 0, maximum 38, available 38; waitlist values 0. Capture time and current availability unknown."
  },
  {
    "source_id": "00000007-0010-4000-8000-000000000007",
    "file_path": "Images for courses/KakaoTalk_20261015_215023730.png",
    "sha256": "5a8265eb9f0873ec01df2af7127779b7330d5697f141303774c374d0d427581c",
    "source_kind": "USER_SCREENSHOT",
    "source_url": null,
    "captured_at": null,
    "verified_at": "2026-10-16T01:57:28Z",
    "coverage_note": "Lab 060 Linked Sections, Option 1: lecture section 010, CRN 12275. No lab 062 linked-section detail is provided."
  }
]$fixture$::jsonb)
ON CONFLICT (source_id) DO UPDATE SET file_path = EXCLUDED.file_path, sha256 = EXCLUDED.sha256, source_kind = EXCLUDED.source_kind, source_url = EXCLUDED.source_url, captured_at = EXCLUDED.captured_at, verified_at = EXCLUDED.verified_at, coverage_note = EXCLUDED.coverage_note;

INSERT INTO course (course_id, code, subject, course_number, title, description, credits, prerequisite_status, source_id)
SELECT course_id, code, subject, course_number, title, description, credits, prerequisite_status, source_id FROM jsonb_populate_recordset(NULL::course,
$fixture$[
  {
    "course_id": "00000007-0000-4000-8000-000000000001",
    "code": "CSE-007",
    "subject": "CSE",
    "course_number": "007",
    "title": "Introduction to Programming",
    "description": null,
    "credits": 4,
    "prerequisite_status": "UNKNOWN",
    "source_id": "00000007-0010-4000-8000-000000000003"
  }
]$fixture$::jsonb)
ON CONFLICT (course_id) DO UPDATE SET code = EXCLUDED.code, subject = EXCLUDED.subject, course_number = EXCLUDED.course_number, title = EXCLUDED.title, description = EXCLUDED.description, credits = EXCLUDED.credits, prerequisite_status = EXCLUDED.prerequisite_status, source_id = EXCLUDED.source_id;

INSERT INTO term (term_id, term_code, name, academic_year, source_id)
SELECT term_id, term_code, name, academic_year, source_id FROM jsonb_populate_recordset(NULL::term,
$fixture$[
  {
    "term_id": "00000007-0001-4000-8000-000000202710",
    "term_code": "202710",
    "name": "2027 Spring Semester",
    "academic_year": null,
    "source_id": "00000007-0010-4000-8000-000000000003"
  }
]$fixture$::jsonb)
ON CONFLICT (term_id) DO UPDATE SET term_code = EXCLUDED.term_code, name = EXCLUDED.name, academic_year = EXCLUDED.academic_year, source_id = EXCLUDED.source_id;

INSERT INTO section (section_id, course_id, term_id, crn, section_code, component_type, instructor, instructor_is_primary, credits, credits_verification_status, delivery_type, meeting_status, link_status, campus_text, grade_mode_text, search_status_text, source_status, source_id, detail_source_id)
SELECT section_id, course_id, term_id, crn, section_code, component_type, instructor, instructor_is_primary, credits, credits_verification_status, delivery_type, meeting_status, link_status, campus_text, grade_mode_text, search_status_text, source_status, source_id, detail_source_id FROM jsonb_populate_recordset(NULL::section,
$fixture$[
  {
    "section_id": "00000007-0002-4000-8000-000000012275",
    "course_id": "00000007-0000-4000-8000-000000000001",
    "term_id": "00000007-0001-4000-8000-000000202710",
    "crn": "12275",
    "section_code": "010",
    "component_type": "LECTURE",
    "instructor": "Chen, Brian",
    "instructor_is_primary": true,
    "credits": 4,
    "credits_verification_status": "TRANSCRIBED",
    "delivery_type": "UNKNOWN",
    "meeting_status": "SCHEDULED",
    "link_status": "PAIR_CONFIRMED",
    "campus_text": null,
    "grade_mode_text": null,
    "search_status_text": "70 of 70 seats...",
    "source_status": "HISTORICAL",
    "source_id": "00000007-0010-4000-8000-000000000002",
    "detail_source_id": null
  },
  {
    "section_id": "00000007-0002-4000-8000-000000013132",
    "course_id": "00000007-0000-4000-8000-000000000001",
    "term_id": "00000007-0001-4000-8000-000000202710",
    "crn": "13132",
    "section_code": "060",
    "component_type": "LAB",
    "instructor": "Chen, Brian",
    "instructor_is_primary": true,
    "credits": 0,
    "credits_verification_status": "NEEDS_REVIEW",
    "delivery_type": "IN_PERSON",
    "meeting_status": "SCHEDULED",
    "link_status": "PAIR_CONFIRMED",
    "campus_text": "Engineering&App Sci On Campus",
    "grade_mode_text": "No Section specified grade mode, please see Catalog link below for more information.",
    "search_status_text": "38 of 38 seats...",
    "source_status": "HISTORICAL",
    "source_id": "00000007-0010-4000-8000-000000000002",
    "detail_source_id": "00000007-0010-4000-8000-000000000003"
  },
  {
    "section_id": "00000007-0002-4000-8000-000000012638",
    "course_id": "00000007-0000-4000-8000-000000000001",
    "term_id": "00000007-0001-4000-8000-000000202710",
    "crn": "12638",
    "section_code": "062",
    "component_type": "LAB",
    "instructor": "Chen, Brian",
    "instructor_is_primary": true,
    "credits": 0,
    "credits_verification_status": "TRANSCRIBED",
    "delivery_type": "IN_PERSON",
    "meeting_status": "SCHEDULED",
    "link_status": "TARGET_UNKNOWN",
    "campus_text": null,
    "grade_mode_text": null,
    "search_status_text": "37 of 37 seats...",
    "source_status": "HISTORICAL",
    "source_id": "00000007-0010-4000-8000-000000000002",
    "detail_source_id": null
  }
]$fixture$::jsonb)
ON CONFLICT (section_id) DO UPDATE SET course_id = EXCLUDED.course_id, term_id = EXCLUDED.term_id, crn = EXCLUDED.crn, section_code = EXCLUDED.section_code, component_type = EXCLUDED.component_type, instructor = EXCLUDED.instructor, instructor_is_primary = EXCLUDED.instructor_is_primary, credits = EXCLUDED.credits, credits_verification_status = EXCLUDED.credits_verification_status, delivery_type = EXCLUDED.delivery_type, meeting_status = EXCLUDED.meeting_status, link_status = EXCLUDED.link_status, campus_text = EXCLUDED.campus_text, grade_mode_text = EXCLUDED.grade_mode_text, search_status_text = EXCLUDED.search_status_text, source_status = EXCLUDED.source_status, source_id = EXCLUDED.source_id, detail_source_id = EXCLUDED.detail_source_id;

INSERT INTO section_attribute (section_id, attribute_text, source_id)
SELECT section_id, attribute_text, source_id FROM jsonb_populate_recordset(NULL::section_attribute,
$fixture$[
  {
    "section_id": "00000007-0002-4000-8000-000000012275",
    "attribute_text": "FLEX - Classroom",
    "source_id": "00000007-0010-4000-8000-000000000002"
  },
  {
    "section_id": "00000007-0002-4000-8000-000000012275",
    "attribute_text": "Quantitative Reasoning",
    "source_id": "00000007-0010-4000-8000-000000000002"
  },
  {
    "section_id": "00000007-0002-4000-8000-000000013132",
    "attribute_text": "On-Campus Required",
    "source_id": "00000007-0010-4000-8000-000000000002"
  },
  {
    "section_id": "00000007-0002-4000-8000-000000013132",
    "attribute_text": "Lab - Linked",
    "source_id": "00000007-0010-4000-8000-000000000002"
  },
  {
    "section_id": "00000007-0002-4000-8000-000000013132",
    "attribute_text": "Quantitative Reasoning",
    "source_id": "00000007-0010-4000-8000-000000000002"
  },
  {
    "section_id": "00000007-0002-4000-8000-000000012638",
    "attribute_text": "On-Campus Required",
    "source_id": "00000007-0010-4000-8000-000000000002"
  },
  {
    "section_id": "00000007-0002-4000-8000-000000012638",
    "attribute_text": "Lab - Linked",
    "source_id": "00000007-0010-4000-8000-000000000002"
  },
  {
    "section_id": "00000007-0002-4000-8000-000000012638",
    "attribute_text": "Quantitative Reasoning",
    "source_id": "00000007-0010-4000-8000-000000000002"
  }
]$fixture$::jsonb)
ON CONFLICT (section_id, attribute_text) DO UPDATE SET source_id = EXCLUDED.source_id;

INSERT INTO meeting (meeting_id, section_id, day_of_week, start_time, end_time, start_date, end_date, campus, building, room, location, meeting_type_text, source_status, source_id)
SELECT meeting_id, section_id, day_of_week, start_time, end_time, start_date, end_date, campus, building, room, location, meeting_type_text, source_status, source_id FROM jsonb_populate_recordset(NULL::meeting,
$fixture$[
  {
    "meeting_id": "00000007-0011-4000-8000-000000000001",
    "section_id": "00000007-0002-4000-8000-000000012275",
    "day_of_week": 1,
    "start_time": "09:20",
    "end_time": "10:35",
    "start_date": null,
    "end_date": null,
    "campus": null,
    "building": null,
    "room": null,
    "location": null,
    "meeting_type_text": null,
    "source_status": "HISTORICAL",
    "source_id": "00000007-0010-4000-8000-000000000002"
  },
  {
    "meeting_id": "00000007-0011-4000-8000-000000000002",
    "section_id": "00000007-0002-4000-8000-000000013132",
    "day_of_week": 3,
    "start_time": "09:20",
    "end_time": "10:35",
    "start_date": "2027-01-25",
    "end_date": "2027-05-07",
    "campus": "Asa Packer Campus",
    "building": "019 Packard Lab",
    "room": "112",
    "location": "Asa Packer Campus | 019 Packard Lab | Room 112",
    "meeting_type_text": "Lab On-Campus Only",
    "source_status": "HISTORICAL",
    "source_id": "00000007-0010-4000-8000-000000000004"
  },
  {
    "meeting_id": "00000007-0011-4000-8000-000000000003",
    "section_id": "00000007-0002-4000-8000-000000013132",
    "day_of_week": 5,
    "start_time": "09:20",
    "end_time": "10:35",
    "start_date": "2027-01-25",
    "end_date": "2027-05-07",
    "campus": "Asa Packer Campus",
    "building": "019 Packard Lab",
    "room": "112",
    "location": "Asa Packer Campus | 019 Packard Lab | Room 112",
    "meeting_type_text": "Lab On-Campus Only",
    "source_status": "HISTORICAL",
    "source_id": "00000007-0010-4000-8000-000000000004"
  },
  {
    "meeting_id": "00000007-0011-4000-8000-000000000004",
    "section_id": "00000007-0002-4000-8000-000000012638",
    "day_of_week": 3,
    "start_time": "10:45",
    "end_time": "12:00",
    "start_date": null,
    "end_date": null,
    "campus": null,
    "building": null,
    "room": null,
    "location": null,
    "meeting_type_text": null,
    "source_status": "HISTORICAL",
    "source_id": "00000007-0010-4000-8000-000000000002"
  },
  {
    "meeting_id": "00000007-0011-4000-8000-000000000005",
    "section_id": "00000007-0002-4000-8000-000000012638",
    "day_of_week": 5,
    "start_time": "10:45",
    "end_time": "12:00",
    "start_date": null,
    "end_date": null,
    "campus": null,
    "building": null,
    "room": null,
    "location": null,
    "meeting_type_text": null,
    "source_status": "HISTORICAL",
    "source_id": "00000007-0010-4000-8000-000000000002"
  }
]$fixture$::jsonb)
ON CONFLICT (meeting_id) DO UPDATE SET section_id = EXCLUDED.section_id, day_of_week = EXCLUDED.day_of_week, start_time = EXCLUDED.start_time, end_time = EXCLUDED.end_time, start_date = EXCLUDED.start_date, end_date = EXCLUDED.end_date, campus = EXCLUDED.campus, building = EXCLUDED.building, room = EXCLUDED.room, location = EXCLUDED.location, meeting_type_text = EXCLUDED.meeting_type_text, source_status = EXCLUDED.source_status, source_id = EXCLUDED.source_id;

INSERT INTO section_link (link_id, primary_section_id, required_component_group, compatible_section_id, course_id, term_id, source_id, verification_status, evidence_note)
SELECT link_id, primary_section_id, required_component_group, compatible_section_id, course_id, term_id, source_id, verification_status, evidence_note FROM jsonb_populate_recordset(NULL::section_link,
$fixture$[
  {
    "link_id": "00000007-0004-4000-8000-001227513132",
    "primary_section_id": "00000007-0002-4000-8000-000000012275",
    "required_component_group": "LAB",
    "compatible_section_id": "00000007-0002-4000-8000-000000013132",
    "course_id": "00000007-0000-4000-8000-000000000001",
    "term_id": "00000007-0001-4000-8000-000000202710",
    "source_id": "00000007-0010-4000-8000-000000000007",
    "verification_status": "PAIR_CONFIRMED",
    "evidence_note": "Lab 060 details list Option 1: Lecture 010, CRN 12275. Stored lecture-first for the data model. This confirms the pair, not the complete lecture-side component group."
  }
]$fixture$::jsonb)
ON CONFLICT (link_id) DO UPDATE SET primary_section_id = EXCLUDED.primary_section_id, required_component_group = EXCLUDED.required_component_group, compatible_section_id = EXCLUDED.compatible_section_id, course_id = EXCLUDED.course_id, term_id = EXCLUDED.term_id, source_id = EXCLUDED.source_id, verification_status = EXCLUDED.verification_status, evidence_note = EXCLUDED.evidence_note;

INSERT INTO enrollment_snapshot (enrollment_snapshot_id, section_id, enrollment_actual, enrollment_maximum, enrollment_seats_available, waitlist_capacity, waitlist_actual, waitlist_seats_available, is_live, source_id)
SELECT enrollment_snapshot_id, section_id, enrollment_actual, enrollment_maximum, enrollment_seats_available, waitlist_capacity, waitlist_actual, waitlist_seats_available, is_live, source_id FROM jsonb_populate_recordset(NULL::enrollment_snapshot,
$fixture$[
  {
    "enrollment_snapshot_id": "00000007-0013-4000-8000-000000013132",
    "section_id": "00000007-0002-4000-8000-000000013132",
    "enrollment_actual": 0,
    "enrollment_maximum": 38,
    "enrollment_seats_available": 38,
    "waitlist_capacity": 0,
    "waitlist_actual": 0,
    "waitlist_seats_available": 0,
    "is_live": false,
    "source_id": "00000007-0010-4000-8000-000000000006"
  }
]$fixture$::jsonb)
ON CONFLICT (enrollment_snapshot_id) DO UPDATE SET section_id = EXCLUDED.section_id, enrollment_actual = EXCLUDED.enrollment_actual, enrollment_maximum = EXCLUDED.enrollment_maximum, enrollment_seats_available = EXCLUDED.enrollment_seats_available, waitlist_capacity = EXCLUDED.waitlist_capacity, waitlist_actual = EXCLUDED.waitlist_actual, waitlist_seats_available = EXCLUDED.waitlist_seats_available, is_live = EXCLUDED.is_live, source_id = EXCLUDED.source_id;

INSERT INTO data_issue (issue_id, section_id, code, field_name, severity, details, observations)
SELECT issue_id, section_id, code, field_name, severity, details, observations FROM jsonb_populate_recordset(NULL::data_issue,
$fixture$[
  {
    "issue_id": "00000007-0012-4000-8000-000000000001",
    "section_id": "00000007-0002-4000-8000-000000013132",
    "code": "CREDITS_CONFLICT",
    "field_name": "credits",
    "severity": "WARNING",
    "details": "Results Hours is 0; detail Credit Hours is 4. Use 0 provisionally for the lab and retain NEEDS_REVIEW.",
    "observations": [
      {
        "source_id": "00000007-0010-4000-8000-000000000002",
        "label": "Hours",
        "value": 0
      },
      {
        "source_id": "00000007-0010-4000-8000-000000000003",
        "label": "Credit Hours",
        "value": 4
      }
    ]
  },
  {
    "issue_id": "00000007-0012-4000-8000-000000000002",
    "section_id": "00000007-0002-4000-8000-000000012638",
    "code": "LINK_TARGET_UNKNOWN",
    "field_name": "section_link",
    "severity": "WARNING",
    "details": "Search results mark 062 as linked, but its partner is not visible. Do not invent a 010/062 link.",
    "observations": [
      {
        "source_id": "00000007-0010-4000-8000-000000000002",
        "label": "Status",
        "value": "LINKED"
      }
    ]
  },
  {
    "issue_id": "00000007-0012-4000-8000-000000000003",
    "section_id": null,
    "code": "PREREQUISITES_UNKNOWN",
    "field_name": "prerequisite_status",
    "severity": "WARNING",
    "details": "No readable prerequisite, corequisite or restriction detail is supplied. The cropped panel cannot prove no requirements.",
    "observations": [
      {
        "source_id": "00000007-0010-4000-8000-000000000005",
        "value": "Right-hand content cropped"
      }
    ]
  },
  {
    "issue_id": "00000007-0012-4000-8000-000000000004",
    "section_id": "00000007-0002-4000-8000-000000012275",
    "code": "DELIVERY_TYPE_UNKNOWN",
    "field_name": "delivery_type",
    "severity": "WARNING",
    "details": "FLEX - Classroom does not unambiguously establish IN_PERSON, ONLINE or HYBRID in these screenshots.",
    "observations": [
      {
        "source_id": "00000007-0010-4000-8000-000000000002",
        "label": "Attribute",
        "value": "FLEX - Classroom"
      }
    ]
  },
  {
    "issue_id": "00000007-0012-4000-8000-000000000005",
    "section_id": "00000007-0002-4000-8000-000000012275",
    "code": "MEETING_DETAILS_INCOMPLETE",
    "field_name": "meeting.location/date_range",
    "severity": "WARNING",
    "details": "Lecture time/day is visible; room, campus and meeting date range are not. These fields stay NULL.",
    "observations": [
      {
        "source_id": "00000007-0010-4000-8000-000000000002",
        "value": "Monday 09:20-10:35"
      }
    ]
  },
  {
    "issue_id": "00000007-0012-4000-8000-000000000006",
    "section_id": "00000007-0002-4000-8000-000000012638",
    "code": "MEETING_DETAILS_INCOMPLETE",
    "field_name": "meeting.location/date_range",
    "severity": "WARNING",
    "details": "Lab 062 time/days are visible; location and date range are not. Do not copy lab 060 details.",
    "observations": [
      {
        "source_id": "00000007-0010-4000-8000-000000000002",
        "value": "Wednesday/Friday 10:45-12:00"
      }
    ]
  },
  {
    "issue_id": "00000007-0012-4000-8000-000000000007",
    "section_id": null,
    "code": "AVAILABILITY_SNAPSHOT_ONLY",
    "field_name": "enrollment_snapshot",
    "severity": "INFO",
    "details": "Only lab 060 has a fully readable enrollment panel. Counts are static observations, not live availability. Other sections retain truncated raw labels only.",
    "observations": [
      {
        "source_id": "00000007-0010-4000-8000-000000000006",
        "value": "0 enrolled; maximum 38; 38 available in screenshot"
      }
    ]
  },
  {
    "issue_id": "00000007-0012-4000-8000-000000000008",
    "section_id": "00000007-0002-4000-8000-000000012275",
    "code": "COMPONENT_GROUP_INCOMPLETE",
    "field_name": "section_link",
    "severity": "WARNING",
    "details": "The 060 detail confirms the 010/060 pair; lecture-side required-component alternatives and the role of 062 are not fully shown.",
    "observations": [
      {
        "source_id": "00000007-0010-4000-8000-000000000007",
        "value": "Lab 060 -> Option 1 -> Lecture 010 CRN 12275"
      }
    ]
  }
]$fixture$::jsonb)
ON CONFLICT (issue_id) DO UPDATE SET section_id = EXCLUDED.section_id, code = EXCLUDED.code, field_name = EXCLUDED.field_name, severity = EXCLUDED.severity, details = EXCLUDED.details, observations = EXCLUDED.observations;
