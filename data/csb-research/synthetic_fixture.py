"""A small SYNTHETIC CSB dataset for the committed tests: fake CRNs (99xxx), invented instructor names, invented catalog text. Nothing here was read from
Banner or copied from the catalog. It has the same shapes as the real inputs and exercises every rule: linked recitations (both sides), a link only one
side lists, a linked component whose Class Details show the bundled credit total, a lab-only course, an arranged-by-instructor independent study with a
variable credit range, a Common Hour Exam, a placeholder instructor, an unmapped prerequisite subject, and a catalog/Banner prerequisite conflict.
"""
import copy

import csb_dataset as ds

WEEKLY = "{days} S M T W T F S {start} - {end} Type: {type} On-Campus Only Building: {building} Room: {room} Start Date: 01/25/2027 End Date: 05/07/2027"
EXAM = "Wednesday S M T W T F S 04:25 PM - 05:40 PM Type: Common Hour Exam Building: To Be Determined Room: None Start Date: 03/03/2027 End Date: 03/03/2027"
ARRANGED = "None S M T W T F S - Type: Arranged by studnt w/ instruct Building: None Room: None Start Date: 01/25/2027 End Date: 05/07/2027"

PRE_CSB310 = "Catalog Prerequisites And/Or Test Score Subject Course Number Level Grade ( Accounting 108 Undergraduate TR Or Accounting 152 Undergraduate TR ) And ( Computer Science & Engineering 241 Undergraduate TR Or Computer Science & Engineering 341 Undergraduate TR )"
PRE_PHY021 = "Catalog Prerequisites And/Or Test Score Subject Course Number Level Grade ( Physics 010 Undergraduate TR Or Physics 011 Undergraduate TR ) And ( Mathematics 022 Undergraduate TR Or Mathematics 032 Undergraduate TR )"
PRE_NONE = "Catalog Prerequisites No prerequisite information available."
CO_NONE = "Corequisites No corequisite course information available."
RESTR_NONE = "No course restriction information is available."
RESTR_SENIORS = "Not all restrictions are applicable. Must be enrolled in one of the following Classes: Graduate (GR) Junior(JR) Senior(SR) Must be enrolled in one of the following Majors: Computer Science & Business (CSB)"
PRE_UNMAPPED = "Catalog Prerequisites And/Or Test Score Subject Course Number Level Grade Underwater Basketweaving 101 Undergraduate TR"


def row(crn, subject, number, section, title, hours, stype, meetings, instructors, attribute="On-Campus Required", linked=""):
    return {"crn": crn, "subject": subject, "number": number, "section": section, "title": title, "hours": hours, "type": stype, "attribute": attribute,
            "linked": linked, "meetings": meetings, "instructors": [{"name": n, "role": r} for n, r in instructors]}


def cap(crn, stype, section, hours, restrictions, prerequisites, options=None, cross=None, linked_text="No linked course information available.", campus="Synthetic Campus"):
    return {"term_code": "202710", "crn": crn,
            "details": {"schedule_type": stype, "section": section, "credit_hours": hours, "campus": campus, "grade_mode": "Synthetic Standard Letter"},
            "attributes_text": "On-Campus Required CAMP", "restrictions_text": restrictions, "prerequisites_text": prerequisites, "corequisites_text": CO_NONE,
            "cross_list": {"rows": cross or [], "panel_text": None if cross else "No cross list information available."},
            "linked": {"options": [{"label": f"Option {i}", "sections": [{"crn": c, "schedule_type": t, "section": s} for c, t, s in opt]} for i, opt in enumerate(options or [], 1)],
                       "panel_text": None if options else linked_text}}


def make_inputs():
    """-> (grid rows by crn, captures by crn, searches)."""
    rows = {r["crn"]: r for r in [
        row("99001", "Computer Science and Business", "310", "010", "Synthetic Product Studio", "3", "Lecture",
            [WEEKLY.format(days="Monday,Wednesday", start="09:20 AM", end="10:35 AM", type="Class", building="None", room="None")], [("Doe, Jane", "Primary")]),
        row("99002", "Computer Science and Business", "310", "011", "Synthetic Product Studio", "3", "Lecture",
            [WEEKLY.format(days="Monday,Wednesday", start="03:00 PM", end="04:15 PM", type="Class", building="Synthetic Hall", room="101"), EXAM],
            [("Roe, Richard", "Primary"), ("Poe, Edgar", "Additional")]),
        row("99010", "Physics", "021", "010", "Synthetic Physics Two", "4", "Lecture",
            [WEEKLY.format(days="Tuesday,Thursday", start="10:45 AM", end="12:00 PM", type="Class", building="Lab Hall", room="1")], [("Staff, Teaching", "Primary")],
            linked="LINKED"),
        row("99011", "Physics", "021", "111", "Synthetic Physics Two", "0", "Recitation",
            [WEEKLY.format(days="Friday", start="08:00 AM", end="08:50 AM", type="Recitation", building="Lab Hall", room="2")], [("Moe, Mary", "Primary")], linked="LINKED"),
        row("99012", "Physics", "021", "112", "Synthetic Physics Two", "0", "Recitation",
            [WEEKLY.format(days="Friday", start="09:00 AM", end="09:50 AM", type="Recitation", building="Lab Hall", room="2")], [("Moe, Mary", "Primary")], linked="LINKED"),
        row("99020", "Earth & Environmental Science", "022", "060", "Synthetic Earth Laboratory", "1", "Lab",
            [WEEKLY.format(days="Monday", start="01:35 PM", end="04:15 PM", type="Lab", building="Geo Hall", room="3")], [("Voe, Victor", "Primary")]),
        row("99030", "Computer Science and Business", "392", "016", "Synthetic Independent Study", "1 TO 3", "Independent Study", [ARRANGED], [("Doe, Jane", "Primary")]),
        row("99040", "Accounting", "999", "010", "Synthetic Unmapped Course", "3", "Lecture",
            [WEEKLY.format(days="Tuesday", start="01:35 PM", end="02:50 PM", type="Class", building="None", room="None")], [("Zoe, Zed", "Primary")]),
    ]}
    caps = {c["crn"]: c for c in [
        cap("99001", "Lecture", "010", "3", RESTR_SENIORS, PRE_CSB310),
        cap("99002", "Lecture", "011", "3", RESTR_SENIORS, PRE_CSB310),
        cap("99010", "Lecture", "010", "4", RESTR_NONE, PRE_PHY021, options=[[("99011", "Recitation", "111")], [("99012", "Recitation", "112")]]),
        cap("99011", "Recitation", "111", "4", RESTR_NONE, PRE_PHY021, options=[[("99010", "Lecture", "010")]]),
        cap("99012", "Recitation", "112", "4", RESTR_NONE, PRE_PHY021, options=[[("99010", "Lecture", "010")]],
            cross=[{"crn": "99999", "subject": "Physics", "course_number": "019", "title": "Synthetic Completion", "section": "113"}]),
        cap("99020", "Lab", "060", "1", RESTR_NONE, PRE_NONE),
        cap("99030", "Independent Study", "016", "1 TO 3", RESTR_NONE, PRE_NONE),
        cap("99040", "Lecture", "010", "3", RESTR_NONE, PRE_UNMAPPED),
    ]}
    searches = [
        {"file": "synthetic", "subject": "Computer Science and Business", "courseNumber": None, "count": 3, "capturedAt": None},
        {"file": "synthetic", "subject": "Computer Science and Business", "courseNumber": "010", "count": 0, "capturedAt": None},
        {"file": "synthetic", "subject": "Physics", "courseNumber": "021", "count": 3, "capturedAt": None},
        {"file": "synthetic", "subject": "Earth & Environmental Science", "courseNumber": "022", "count": 1, "capturedAt": None},
        {"file": "synthetic", "subject": "Accounting", "courseNumber": "999", "count": 1, "capturedAt": None},
    ]
    return rows, caps, searches


def make_catalog():
    """Synthetic catalog pages (as catalog_courses.parse_cache would return them). CSB 310's statement matches Banner; PHY 021's conflicts (it adds a course)."""
    def c(cid, title, credits, pre=None, attrs=()):
        subject, number = cid.split("-")
        return {"course_id": cid, "subject": subject, "course_number": number, "title": title, "credits_text": credits, "description": "Synthetic description.",
                "prerequisites_text": pre, "concurrent_text": None, "corequisites_text": None, "attributes": list(attrs), "catalog_aliases": [], "repeat_status": None, "other_labels": {}}
    return {"courses": {
        "CSB-310": c("CSB-310", "Synthetic Product Studio", "3", "(ACCT 108 or ACCT 152) and (CSE 241 or CSE 341)"),
        "PHY-021": c("PHY-021", "Synthetic Physics Two", "0,4", "(PHY 010 or PHY 011) and (MATH 022 or MATH 032) and CHM 030", ("NS", "NW", "Q")),
        "EES-022": c("EES-022", "Synthetic Earth Laboratory", "1", None, ("NS", "NW")),
        "CSB-392": c("CSB-392", "Synthetic Independent Study", "1-3", None),
        "ACCT-999": c("ACCT-999", "Synthetic Unmapped Course", "3", "Underwater Basketweaving 101"),
    }, "not_found": []}


def mutated(caps, crn, **changes):
    out = copy.deepcopy(caps)
    out[crn].update(changes)
    return out


SOURCES = {"catalog": "CATALOG_2026_27", "banner": "BANNER_202710", "rows": [
    {"key": "CATALOG_2026_27", "level": "PUBLIC_CATALOG", "locator": "synthetic catalog", "observedOn": "2026-10-18", "authority": "PRIMARY", "redistribution": "PUBLIC"},
    {"key": "BANNER_202710", "level": "SYNTHETIC_FIXTURE", "locator": "synthetic Banner fixture", "observedOn": "2026-10-18", "authority": "SYNTHETIC", "redistribution": "SYNTHETIC"},
]}


def make_payload(program):
    rows, caps, searches = make_inputs()
    return ds.build_payload(program, make_catalog(), rows, searches, caps, SOURCES)
