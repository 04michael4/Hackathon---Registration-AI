"""Validate, sanitize and import Banner captures into the banner_evidence schema (stdlib only; needs `psql`).

The captures come from the two browser extractors in this folder (extract_grid.js, extract_popup.js). They read
whitelisted fields only; this importer does not trust that: it re-validates every field, rejects unknown keys and
anything that looks like personal or session data, and writes only a normalized copy of what it accepted.

    python3 -I banner_collect.py --dry-run [--twice] [--show-sql] [--setup-sql FILE] CAPTURE[=SHA256] ...
    python3 -I banner_collect.py --apply CAPTURE[=SHA256] ...

  CAPTURE        a JSON file produced by an extractor (one search grid and the popups of that course's sections)
  =SHA256        the hash the page reported for that capture; the file must hash to exactly it (proves the
                 transcription is what the page produced)
  --dry-run      validate, then run every INSERT inside a transaction that is ALWAYS rolled back
  --twice        (with --dry-run) run the import a second time in the same transaction and require it to add nothing
  --apply        validate, write the sanitized files under captures/, and commit one transaction

Connection settings come from the usual PG* variables (PGHOST, PGPORT, PGUSER, PGDATABASE, PGPASSFILE).
Nothing here approves a claim, writes a claim_review, or touches cse007_snapshot or catalog_api.
"""
import argparse
import hashlib
import json
import re
import subprocess
import sys
from datetime import datetime
from pathlib import Path

HERE = Path(__file__).resolve().parent
EVIDENCE_ROOT = HERE.parent  # data/banner-evidence
CAPTURE_DIR = EVIDENCE_ROOT / "captures"
TERM_CODE = "202710"
TERM_LABEL = "2027 Spring Semester"
SUBJECTS = {"Computer Science & Engineering": "CSE"}
HOST = "reg-prod.ec.lehigh.edu"
PATH = "/StudentRegistrationSsb/ssb/classSearch/classSearch"
EXTRACTOR = {"SEARCH_GRID": "extract_grid.js v1", "CLASS_DETAILS_POPUP": "extract_popup.js v1"}
DAYS = {"Monday": 1, "Tuesday": 2, "Wednesday": 3, "Thursday": 4, "Friday": 5, "Saturday": 6, "Sunday": 7}

# Same tripwire as banner_evidence.is_safe_text, plus the seat and instructor markers Banner uses.
TRIPWIRES = [
    re.compile(r"<\s*/?\s*(html|head|body|script|style|div|span|form|input|table|iframe|a)\b", re.I),
    re.compile(r"<!doctype", re.I),
    re.compile(r"(set-cookie|cookie\s*[:=]|authorization\s*[:=]|bearer\s+[a-z0-9._-]{8,}|jsessionid|sessionid|session_id|"
               r"uniquesessionid|csrf|xsrf|api[_-]?key|passw(or)?d|access_token|refresh_token|id_token|token\s*[:=])", re.I),
    re.compile(r"[0-9]{8,}"),
    re.compile(r"[a-z0-9._%+-]+@[a-z0-9.-]+\.[a-z]{2,}", re.I),
    re.compile(r"[\x01-\x08\x0b\x0c\x0e-\x1f]"),
    re.compile(r"seats?\s+remain", re.I),
    re.compile(r"\(Primary\)", re.I),
]
CRN = re.compile(r"^[0-9]{5}$")
SECTION = re.compile(r"^[0-9A-Z]{1,4}$")
COURSE_NUMBER = re.compile(r"^[0-9]{3}[A-Z]?$")
HOURS = re.compile(r"^[0-9]{1,2}(\.[0-9]{1,3})?( ?(-|to|TO) ?[0-9]{1,2}(\.[0-9]{1,3})?)?$")
TYPE_TEXT = re.compile(r"^[A-Za-z][A-Za-z /&-]{0,39}$")
LINK_LABEL = re.compile(r"^(Lab|Lecture|Recitation|Discussion|Studio|Seminar|Independent Study) - Linked$")
OPTION = re.compile(r"^Option [0-9]{1,2}$")
TIMESTAMP = re.compile(r"^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}(\.[0-9]{1,3})?Z$")
MEETING = re.compile(
    r"^(?P<days>(?:Monday|Tuesday|Wednesday|Thursday|Friday|Saturday|Sunday)(?:,(?:Monday|Tuesday|Wednesday|Thursday|Friday|Saturday|Sunday))*)"
    r" S M T W T F S (?P<start>[0-9]{2}:[0-9]{2} [AP]M) - (?P<end>[0-9]{2}:[0-9]{2} [AP]M)"
    r" Type: (?P<type>.{1,60}?) Building: (?P<building>.{1,60}?) Room: (?P<room>.{1,60}?)"
    r" Start Date: (?P<sd>[0-9]{2}/[0-9]{2}/[0-9]{4}) End Date: (?P<ed>[0-9]{2}/[0-9]{2}/[0-9]{4})$")
COUNT_WORDS = re.compile(r"\b(must|required|require|select|choose|one of|both|all of|at least|exactly)\b", re.I)


class CollectError(Exception):
    """A capture was rejected. Nothing has been written."""


# ----------------------------------------------------------------------------------------------------------
# Validation
# ----------------------------------------------------------------------------------------------------------

def clean_text(value, where, maximum=200, allow_none=False):
    if value is None:
        if allow_none:
            return None
        raise CollectError(f"{where}: missing")
    if not isinstance(value, str):
        raise CollectError(f"{where}: must be text")
    if not value or value != value.strip() or "\n" in value or len(value) > maximum:
        raise CollectError(f"{where}: empty, padded, multi-line or longer than {maximum}")
    for pattern in TRIPWIRES:
        if pattern.search(value):
            raise CollectError(f"{where}: matches a personal-data or credential tripwire")
    return value


def pattern_text(value, pattern, where, allow_none=False):
    value = clean_text(value, where, allow_none=allow_none)
    if value is not None and not pattern.match(value):
        raise CollectError(f"{where}: unexpected format")
    return value


def exact_keys(obj, keys, where):
    if not isinstance(obj, dict):
        raise CollectError(f"{where}: must be an object")
    extra, missing = set(obj) - set(keys), set(keys) - set(obj)
    if extra or missing:
        raise CollectError(f"{where}: unexpected keys {sorted(extra)} / missing keys {sorted(missing)}")


def parse_meeting(text, where):
    m = MEETING.match(text)
    if not m:
        raise CollectError(f"{where}: meeting text has an unrecognized format")
    days = sorted(DAYS[d] for d in m.group("days").split(","))
    if len(set(days)) != len(days):
        raise CollectError(f"{where}: repeated day")

    def hhmm(value):
        return datetime.strptime(value, "%I:%M %p").strftime("%H:%M")

    def day(value):
        return datetime.strptime(value, "%m/%d/%Y").strftime("%Y-%m-%d")

    meeting = {"days_iso": days, "start": hhmm(m.group("start")), "end": hhmm(m.group("end")), "type": m.group("type"),
               "building": m.group("building"), "room": m.group("room"), "start_date": day(m.group("sd")), "end_date": day(m.group("ed"))}
    if meeting["end"] <= meeting["start"] or meeting["end_date"] < meeting["start_date"]:
        raise CollectError(f"{where}: meeting ends before it starts")
    for key in ("type", "building", "room"):
        clean_text(meeting[key], f"{where}.{key}", 60)
    return meeting


def validate_grid(raw):
    exact_keys(raw, ["view", "captured_at", "header", "rows"], "grid")
    if raw["view"] != "SEARCH_GRID":
        raise CollectError("grid: wrong view")
    pattern_text(raw["captured_at"], TIMESTAMP, "grid.captured_at")
    h = raw["header"]
    exact_keys(h, ["result_count", "term_label", "subject_label", "course_number", "records"], "grid.header")
    if h["term_label"] != TERM_LABEL:
        raise CollectError(f"grid: term is {h['term_label']!r}, expected {TERM_LABEL!r} (select the view-only Spring 2027 term explicitly)")
    if h["subject_label"] not in SUBJECTS:
        raise CollectError("grid: unexpected subject")
    pattern_text(h["course_number"], COURSE_NUMBER, "grid.course_number")
    rows = raw["rows"]
    if not isinstance(rows, list) or not rows:
        raise CollectError("grid: 0 classes (the term was probably not selected explicitly)")
    if not (h["result_count"] == h["records"] == str(len(rows))):
        raise CollectError("grid: result count, records and rows disagree (partial page or pagination)")
    course_id = f"{SUBJECTS[h['subject_label']]}-{h['course_number']}"
    seen = set()
    out = []
    for i, r in enumerate(rows):
        where = f"grid.rows[{i}]"
        exact_keys(r, ["crn", "subject_description", "course_number", "section", "title", "hours", "schedule_type", "link_label", "meetings"], where)
        crn = pattern_text(r["crn"], CRN, where + ".crn")
        if crn in seen:
            raise CollectError(f"{where}: duplicate CRN")
        seen.add(crn)
        if r["subject_description"] != h["subject_label"] or r["course_number"] != h["course_number"]:
            raise CollectError(f"{where}: row belongs to another course")
        row = {"crn": crn, "section": pattern_text(r["section"], SECTION, where + ".section"),
               "title": clean_text(r["title"], where + ".title", 120),
               "hours": pattern_text(r["hours"], HOURS, where + ".hours"),
               "schedule_type": pattern_text(r["schedule_type"], TYPE_TEXT, where + ".schedule_type"),
               "link_label": pattern_text(r["link_label"], LINK_LABEL, where + ".link_label", allow_none=True)}
        if not isinstance(r["meetings"], list):
            raise CollectError(where + ".meetings: must be a list")
        row["meetings"] = [clean_text(t, f"{where}.meetings[{j}]", 300) for j, t in enumerate(r["meetings"])]
        row["parsed_meetings"] = [parse_meeting(t, f"{where}.meetings[{j}]") for j, t in enumerate(row["meetings"])]
        out.append(row)
    return {"course_id": course_id, "subject": SUBJECTS[h["subject_label"]], "course_number": h["course_number"],
            "captured_at": raw["captured_at"], "rows": out}


def validate_popup(raw):
    exact_keys(raw, ["view", "captured_at", "term_code", "crn", "details", "restrictions_text", "prerequisites_text",
                     "corequisites_text", "linked"], "popup")
    if raw["view"] != "CLASS_DETAILS_POPUP":
        raise CollectError("popup: wrong view")
    pattern_text(raw["captured_at"], TIMESTAMP, "popup.captured_at")
    if raw["term_code"] != TERM_CODE:
        raise CollectError(f"popup: term code {raw['term_code']!r} is not {TERM_CODE}")
    crn = pattern_text(raw["crn"], CRN, "popup.crn")
    d = raw["details"]
    exact_keys(d, ["associated_term", "campus", "schedule_type", "section", "subject_description", "course_number", "title", "credit_hours"], "popup.details")
    if d["associated_term"] != TERM_LABEL or d["subject_description"] not in SUBJECTS:
        raise CollectError("popup.details: unexpected term or subject")
    details = {"campus": clean_text(d["campus"], "popup.details.campus", 60),
               "schedule_type": pattern_text(d["schedule_type"], TYPE_TEXT, "popup.details.schedule_type"),
               "section": pattern_text(d["section"], SECTION, "popup.details.section"),
               "course_number": pattern_text(d["course_number"], COURSE_NUMBER, "popup.details.course_number"),
               "title": clean_text(d["title"], "popup.details.title", 120),
               "credit_hours": pattern_text(d["credit_hours"], HOURS, "popup.details.credit_hours"),
               "subject_description": d["subject_description"], "associated_term": d["associated_term"]}
    texts = {k: clean_text(raw[k], "popup." + k, 1000) for k in ("restrictions_text", "prerequisites_text", "corequisites_text")}
    linked = raw["linked"]
    exact_keys(linked, ["options", "panel_text"], "popup.linked")
    panel_text = clean_text(linked["panel_text"], "popup.linked.panel_text", 600, allow_none=False) if linked["panel_text"] else ""
    options = []
    if not isinstance(linked["options"], list):
        raise CollectError("popup.linked.options: must be a list")
    for i, o in enumerate(linked["options"]):
        exact_keys(o, ["label", "sections"], f"popup.linked.options[{i}]")
        label = pattern_text(o["label"], OPTION, f"popup.linked.options[{i}].label")
        if not isinstance(o["sections"], list) or not o["sections"]:
            raise CollectError(f"popup.linked.options[{i}]: no sections")
        sections = []
        for j, s in enumerate(o["sections"]):
            where = f"popup.linked.options[{i}].sections[{j}]"
            exact_keys(s, ["subject_description", "schedule_type", "section", "crn"], where)
            if s["subject_description"] not in SUBJECTS:
                raise CollectError(where + ": unexpected subject")
            sections.append({"schedule_type": pattern_text(s["schedule_type"], TYPE_TEXT, where + ".schedule_type"),
                             "section": pattern_text(s["section"], SECTION, where + ".section"),
                             "crn": pattern_text(s["crn"], CRN, where + ".crn")})
        options.append({"label": label, "sections": sections})
    return {"crn": crn, "captured_at": raw["captured_at"], "details": details, **texts,
            "linked": {"options": options, "panel_text": panel_text}}


def load_capture(path, expected_sha):
    try:
        data = Path(path).read_bytes()
    except OSError as exc:
        raise CollectError(f"{path}: cannot read ({exc.strerror})")
    actual = hashlib.sha256(data).hexdigest()
    if expected_sha and actual != expected_sha.lower():
        raise CollectError(f"{path}: SHA-256 {actual[:16]}... does not match the page's {expected_sha[:16]}... (transcription error)")
    try:
        raw = json.loads(data.decode("utf-8"))
    except ValueError as exc:
        raise CollectError(f"{path}: not valid JSON ({exc})")
    if isinstance(raw, dict) and "error" in raw:
        raise CollectError(f"{path}: the extractor reported {raw['error']}")
    return raw, actual


# ----------------------------------------------------------------------------------------------------------
# Plan: what would be recorded
# ----------------------------------------------------------------------------------------------------------

def canonical(obj):
    return json.dumps(obj, sort_keys=True, separators=(",", ":"), ensure_ascii=False)


def grouped(sha):
    """First 16 hex characters in groups of four, so a run of digits can never look like an identifier."""
    return " ".join(sha[i:i + 4] for i in range(0, 16, 4))


def validate_generated(plan):
    """The importer's own text (notes, provenance) must pass the same tripwires as the capture."""
    for src in plan["sources"]:
        for key in ("sanitization_note", "provenance_note"):
            clean_text(src[key], f"generated {src['page_kind']} {key}", 1000)
        for n in src["notes"]:
            clean_text(n["text"], f"generated note {n['kind']}", 1000)


def make_source(page_kind, subject_crn, captured_at, content, course_id, view_note, extractor, raw_sha):
    text = canonical(content)
    sha = hashlib.sha256(text.encode("utf-8")).hexdigest()
    name = f"{page_kind.lower()}-{subject_crn or 'search'}-{sha[:12]}.json"
    return {"page_kind": page_kind, "subject_crn": subject_crn, "captured_at": captured_at, "text": text, "sha": sha,
            "file": f"data/banner-evidence/captures/{TERM_CODE}/{course_id}/{name}",
            "sanitization_note": "Whitelisted fields only, re-validated by the importer. Instructor, status, seat and enrollment data were never read; "
                                 "no page HTML, screenshots, cookies, headers or session data stored. The file holds the normalized fields (no timestamp).",
            "provenance_note": f"Banner Browse Classes, {view_note}; term {TERM_LABEL} (View Only), Banner term {TERM_CODE}; "
                               f"read by Claude through the browser ({extractor}); page capture hash {grouped(raw_sha)}.",
            "sections": [], "meetings": [], "fields": [], "notes": [], "links": []}


def orient(subject_crn, subject_type, option_sections):
    """Return (lecture_crn, other_crn, label-free) pairs; fail closed when neither side is a Lecture."""
    pairs = []
    for s in option_sections:
        if subject_type == "Lecture" and s["schedule_type"] != "Lecture":
            pairs.append((subject_crn, s["crn"]))
        elif subject_type != "Lecture" and s["schedule_type"] == "Lecture":
            pairs.append((s["crn"], subject_crn))
        else:
            raise CollectError(f"cannot orient the link between {subject_crn} ({subject_type}) and {s['crn']} ({s['schedule_type']}): need exactly one Lecture side")
    return pairs


def build_plan(captures):
    """captures: list of (raw_json, raw_sha). Exactly one search grid plus popups for sections of that course."""
    grids, popups = [], []
    for raw, sha in captures:
        view = raw.get("view") if isinstance(raw, dict) else None
        if view == "SEARCH_GRID":
            grids.append((validate_grid(raw), sha))
        elif view == "CLASS_DETAILS_POPUP":
            popups.append((validate_popup(raw), sha))
        else:
            raise CollectError("unknown capture view")
    if len(grids) != 1:
        raise CollectError(f"need exactly one search grid per import, got {len(grids)}")
    grid, grid_sha = grids[0]
    course_id = grid["course_id"]
    by_crn = {r["crn"]: r for r in grid["rows"]}
    sources = []

    g = make_source("SEARCH_RESULTS", None, grid["captured_at"],
                    {"view": "SEARCH_GRID", "term_code": TERM_CODE, "subject": grid["subject"], "course_number": grid["course_number"],
                     "rows": [{k: v for k, v in r.items() if k != "parsed_meetings"} for r in grid["rows"]]},
                    course_id, f"Search Results grid for {grid['subject']} {grid['course_number']}", EXTRACTOR["SEARCH_GRID"], grid_sha)
    for r in grid["rows"]:
        g["sections"].append({"crn": r["crn"], "course_id": course_id, "section": r["section"], "title": r["title"],
                              "schedule_type": r["schedule_type"], "campus": None, "outcome": "NOT_CHECKED"})
        for seq, m in enumerate(r["parsed_meetings"], start=1):
            g["meetings"].append({"crn": r["crn"], "seq": seq, **m})
        g["fields"].append({"crn": r["crn"], "view": "SEARCH_GRID", "label": "Hours", "value": r["hours"]})
        if r["link_label"]:
            g["fields"].append({"crn": r["crn"], "view": "SEARCH_GRID", "label": "Attribute link label", "value": r["link_label"]})
    sources.append(g)

    claims = {"links": [], "placeholders": [], "contradictions": []}
    seen_popups = set()
    for p, p_sha in sorted(popups, key=lambda x: x[0]["crn"]):
        crn, d = p["crn"], p["details"]
        if crn in seen_popups:
            raise CollectError(f"two popup captures for CRN {crn}")
        seen_popups.add(crn)
        row = by_crn.get(crn)
        if row is None:
            raise CollectError(f"popup for CRN {crn}, which the search grid does not list")
        if (d["section"], d["title"], d["schedule_type"], d["course_number"]) != (row["section"], row["title"], row["schedule_type"], grid["course_number"]):
            raise CollectError(f"popup for CRN {crn} disagrees with the search grid (section, title, type or course)")
        s = make_source("SECTION_DETAILS", crn, p["captured_at"],
                        {"view": "SECTION_DETAILS", "term_code": TERM_CODE, "crn": crn, "details": d,
                         "restrictions_text": p["restrictions_text"], "prerequisites_text": p["prerequisites_text"],
                         "corequisites_text": p["corequisites_text"]},
                        course_id, f"Class Details popup for CRN {crn} (Class Details, Restrictions, Prerequisites, Corequisites tabs)", EXTRACTOR["CLASS_DETAILS_POPUP"], p_sha)
        s["sections"].append({"crn": crn, "course_id": course_id, "section": d["section"], "title": d["title"], "schedule_type": d["schedule_type"],
                              "campus": d["campus"], "outcome": "NOT_CHECKED"})
        s["fields"].append({"crn": crn, "view": "CLASS_DETAILS", "label": "Credit Hours", "value": d["credit_hours"]})
        for kind, key in (("REGISTRATION_RESTRICTION", "restrictions_text"), ("PREREQUISITE_TEXT", "prerequisites_text"), ("COREQUISITE_TEXT", "corequisites_text")):
            s["notes"].append({"crn": crn, "kind": kind, "text": p[key]})
        sources.append(s)

        options = p["linked"]["options"]
        flat = [sec for o in options for sec in o["sections"]]
        l = make_source("LINKED_SECTIONS", crn, p["captured_at"],
                        {"view": "LINKED_SECTIONS", "term_code": TERM_CODE, "crn": crn, "subject_schedule_type": d["schedule_type"],
                         "linked": p["linked"]},
                        course_id, f"Class Details popup for CRN {crn} (Linked Sections tab)", EXTRACTOR["CLASS_DETAILS_POPUP"], p_sha)
        l["sections"].append({"crn": crn, "course_id": course_id, "section": d["section"], "title": d["title"], "schedule_type": d["schedule_type"],
                              "campus": None, "outcome": "LISTED" if flat else "NONE_SHOWN"})
        listed = {}
        for o in options:
            for sec in o["sections"]:
                if sec["crn"] == crn or sec["crn"] in listed:
                    raise CollectError(f"CRN {crn}: linked sections repeat or list the section itself")
                listed[sec["crn"]] = (o["label"], sec)
        for linked_crn, (label, sec) in sorted(listed.items()):
            grid_row = by_crn.get(linked_crn)
            if grid_row is None:
                raise CollectError(f"CRN {crn} lists linked CRN {linked_crn}, which the search grid for this course does not show")
            if (grid_row["section"], grid_row["schedule_type"]) != (sec["section"], sec["schedule_type"]):
                raise CollectError(f"CRN {crn}: linked section {linked_crn} disagrees with the search grid")
            l["sections"].append({"crn": linked_crn, "course_id": None, "section": sec["section"], "title": None,
                                  "schedule_type": sec["schedule_type"], "campus": None, "outcome": "NOT_CHECKED"})
        for (lecture, other), (label, _) in zip(orient(crn, d["schedule_type"], [s2 for _, (_, s2) in sorted(listed.items())]),
                                                [v for _, v in sorted(listed.items())]):
            l["links"].append({"lecture": lecture, "linked": other, "label": label})
            claims["links"].append({"source": l, "lecture": lecture, "lab": other})
        panel = p["linked"]["panel_text"]
        if COUNT_WORDS.search(panel):
            l["notes"].append({"crn": crn, "kind": "LINK_RULE_TEXT", "text": panel})
        else:
            l["notes"].append({"crn": crn, "kind": "NO_COUNT_STATEMENT",
                               "text": f"The Linked Sections tab lists {len(options)} option(s) and states no number of components to register for."})
        sources.append(l)
        if d["schedule_type"] == "Lecture":
            claims["contradictions"].append({"source": l, "lecture": crn, "listed": sorted(listed)})
            claims["placeholders"].append(("LECTURE_CREDITS", crn))
            claims["placeholders"].append(("STANDALONE_STATUS", crn) if not listed else ("REQUIRED_LAB_COUNT", crn))
            for lab in sorted(listed):
                claims["placeholders"].append(("LAB_CREDITS", lab))
        else:
            claims["placeholders"].append(("LAB_CREDITS", crn))
    plan = {"course_id": course_id, "grid": grid, "sources": sources, "claims": claims,
            "popup_crns": sorted(seen_popups), "section_crns": sorted(by_crn)}
    validate_generated(plan)
    return plan


# ----------------------------------------------------------------------------------------------------------
# SQL
# ----------------------------------------------------------------------------------------------------------

def lit(value):
    if value is None:
        return "NULL"
    if isinstance(value, int):
        return str(value)
    text = str(value)
    if "\x00" in text:
        raise CollectError("NUL in a value")
    return "'" + text.replace("'", "''") + "'"


def source_ref(src):
    return ("(SELECT source_id FROM banner_evidence.evidence_source WHERE source_kind = 'BANNER_LIVE' AND page_kind = %s AND term_code = %s "
            "AND COALESCE(subject_crn, '') = %s AND content_sha256 = %s)" % (lit(src["page_kind"]), lit(TERM_CODE), lit(src["subject_crn"] or ""), lit(src["sha"])))


def claim_values(claim_type, course_id, crn, other, value):
    return f"({lit(TERM_CODE)}, {lit(claim_type)}, {lit(course_id)}, {lit(crn)}, {lit(other)}, {lit(value)})"


def generate_sql(plan):
    course_id = plan["course_id"]
    out = []
    for src in plan["sources"]:
        captured = src["captured_at"]
        out.append(
            "INSERT INTO banner_evidence.evidence_source (source_kind, page_kind, term_code, subject_crn, captured_on, captured_at, captured_by_role, "
            "inspected_by_claude, source_host, source_path, evidence_file, content_sha256, sanitization_note, provenance_note) VALUES "
            f"('BANNER_LIVE', {lit(src['page_kind'])}, {lit(TERM_CODE)}, {lit(src['subject_crn'])}, DATE {lit(captured[:10])}, "
            f"{lit(captured)}::timestamptz, 'CLAUDE_BROWSER', true, {lit(HOST)}, {lit(PATH)}, {lit(src['file'])}, {lit(src['sha'])}, "
            f"{lit(src['sanitization_note'])}, {lit(src['provenance_note'])}) ON CONFLICT DO NOTHING;")
        ref = source_ref(src)
        for s in src["sections"]:
            out.append("INSERT INTO banner_evidence.section_observation (source_id, term_code, crn, course_id, section_code, title, schedule_type, campus_text, linked_sections_outcome) VALUES "
                       f"({ref}, {lit(TERM_CODE)}, {lit(s['crn'])}, {lit(s['course_id'])}, {lit(s['section'])}, {lit(s['title'])}, {lit(s['schedule_type'])}, {lit(s['campus'])}, {lit(s['outcome'])}) ON CONFLICT DO NOTHING;")
        for m in src["meetings"]:
            days = "{" + ",".join(str(d) for d in m["days_iso"]) + "}"
            out.append("INSERT INTO banner_evidence.meeting_observation (source_id, crn, seq, days_iso, start_time, end_time, start_date, end_date, meeting_type_text, building_text, room_text) VALUES "
                       f"({ref}, {lit(m['crn'])}, {m['seq']}, {lit(days)}::smallint[], TIME {lit(m['start'])}, TIME {lit(m['end'])}, DATE {lit(m['start_date'])}, DATE {lit(m['end_date'])}, "
                       f"{lit(m['type'])}, {lit(m['building'])}, {lit(m['room'])}) ON CONFLICT DO NOTHING;")
        for f in src["fields"]:
            out.append("INSERT INTO banner_evidence.section_field_observation (source_id, crn, view_name, field_label, field_value) VALUES "
                       f"({ref}, {lit(f['crn'])}, {lit(f['view'])}, {lit(f['label'])}, {lit(f['value'])}) ON CONFLICT DO NOTHING;")
        for n in src["notes"]:
            out.append("INSERT INTO banner_evidence.note_observation (source_id, crn, note_kind, note_text) VALUES "
                       f"({ref}, {lit(n['crn'])}, {lit(n['kind'])}, {lit(n['text'])}) ON CONFLICT DO NOTHING;")
        for k in src["links"]:
            out.append("INSERT INTO banner_evidence.link_observation (source_id, lecture_crn, linked_crn, link_label) VALUES "
                       f"({ref}, {lit(k['lecture'])}, {lit(k['linked'])}, {lit(k['label'])}) ON CONFLICT DO NOTHING;")
    claims = plan["claims"]
    for c in claims["links"]:
        out.append("INSERT INTO banner_evidence.claim (term_code, claim_type, course_id, crn, other_crn, asserted_value) VALUES "
                   + claim_values("LECTURE_LAB_LINK", course_id, c["lecture"], c["lab"], "LINKED") + " ON CONFLICT DO NOTHING;")
        out.append("INSERT INTO banner_evidence.claim_evidence (claim_id, source_id, stance, note) SELECT c.claim_id, " + source_ref(c["source"]) +
                   ", 'SUPPORTS', 'Banner Linked Sections lists this link.' FROM banner_evidence.claim c WHERE c.term_code = " + lit(TERM_CODE) +
                   f" AND c.claim_type = 'LECTURE_LAB_LINK' AND c.crn = {lit(c['lecture'])} AND c.other_crn = {lit(c['lab'])} AND c.asserted_value = 'LINKED' ON CONFLICT DO NOTHING;")
    for c in claims["contradictions"]:
        listed = "ARRAY[" + ",".join(lit(x) for x in c["listed"]) + "]::text[]"
        out.append("INSERT INTO banner_evidence.claim_evidence (claim_id, source_id, stance, note) SELECT c.claim_id, " + source_ref(c["source"]) +
                   ", 'CONTRADICTS', 'Banner Linked Sections for this lecture does not list this lab.' FROM banner_evidence.claim c WHERE c.term_code = " + lit(TERM_CODE) +
                   f" AND c.claim_type = 'LECTURE_LAB_LINK' AND c.crn = {lit(c['lecture'])} AND c.asserted_value = 'LINKED' AND c.other_crn <> ALL ({listed}) ON CONFLICT DO NOTHING;")
    for claim_type, crn in claims["placeholders"]:
        out.append("INSERT INTO banner_evidence.claim (term_code, claim_type, course_id, crn, other_crn, asserted_value) VALUES "
                   + claim_values(claim_type, course_id, crn, None, None) + " ON CONFLICT DO NOTHING;")
    return out


COUNT_SQL = ("SELECT 'evidence_source|' || count(*) FROM banner_evidence.evidence_source UNION ALL "
             "SELECT 'section_observation|' || count(*) FROM banner_evidence.section_observation UNION ALL "
             "SELECT 'meeting_observation|' || count(*) FROM banner_evidence.meeting_observation UNION ALL "
             "SELECT 'section_field_observation|' || count(*) FROM banner_evidence.section_field_observation UNION ALL "
             "SELECT 'link_observation|' || count(*) FROM banner_evidence.link_observation UNION ALL "
             "SELECT 'note_observation|' || count(*) FROM banner_evidence.note_observation UNION ALL "
             "SELECT 'claim|' || count(*) FROM banner_evidence.claim UNION ALL "
             "SELECT 'claim_evidence|' || count(*) FROM banner_evidence.claim_evidence UNION ALL "
             "SELECT 'claim_review|' || count(*) FROM banner_evidence.claim_review;")


def run_psql(sql):
    result = subprocess.run(["psql", "-X", "-q", "-At", "-v", "ON_ERROR_STOP=1", "-f", "-"], input=sql, capture_output=True, text=True, timeout=300)
    return result.returncode, result.stdout, result.stderr


def parse_counts(block):
    counts = {}
    for line in block.splitlines():
        if "|" in line:
            name, value = line.split("|", 1)
            if value.strip().isdigit():
                counts[name.strip()] = int(value)
    return counts


def run_import(plan, mode, twice=False, setup_sql=None):
    """mode 'dry-run' always rolls back; mode 'apply' commits. Returns (counts_before, counts_after, second_run_counts, report_text)."""
    statements = generate_sql(plan)
    crns = ",".join(lit(c) for c in plan["section_crns"])
    parts = ["\\set ON_ERROR_STOP on", "BEGIN;"] + ([setup_sql] if setup_sql else []) + ["\\echo @@BEFORE", COUNT_SQL] + statements + ["\\echo @@AFTER", COUNT_SQL]
    if twice:
        parts += ["\\echo @@SECOND"] + statements + [COUNT_SQL]
    parts += ["\\echo @@REPORT",
              "\\echo -- links (reported vs Banner)",
              f"SELECT lecture_crn || ' -> ' || lab_crn || ' | ' || outcome FROM banner_evidence.link_reconciliation WHERE course_id = {lit(plan['course_id'])} ORDER BY lecture_crn, lab_crn;",
              "\\echo -- meeting times (reported vs Banner)",
              f"SELECT crn || ' | ' || outcome FROM banner_evidence.meeting_reconciliation WHERE crn IN ({crns}) ORDER BY crn;",
              "\\echo -- credit displays, original labels",
              f"SELECT crn || ' | grid Hours=' || COALESCE(grid_hours, '-') || ' | Class Details Credit Hours=' || COALESCE(class_details_credit_hours, '-') || ' | differ=' || displays_differ FROM banner_evidence.credit_display_comparison WHERE crn IN ({crns}) ORDER BY crn;",
              "\\echo -- claim status for this course",
              f"SELECT claim_type || ' ' || crn || COALESCE('>' || other_crn, '') || ' = ' || COALESCE(asserted_value, 'UNKNOWN') || ' | ' || evidence_status || ' | ' || review_flag || ' | promotable=' || promotion_eligible FROM banner_evidence.claim_status WHERE course_id = {lit(plan['course_id'])} AND NOT superseded ORDER BY claim_type, crn, other_crn;",
              "ROLLBACK;" if mode == "dry-run" else "COMMIT;"]
    code, out, err = run_psql("\n".join(parts) + "\n")
    if code != 0:
        raise CollectError("database rejected the import; the transaction was rolled back:\n" + (err.strip() or out.strip())[-900:])
    sections = re.split(r"^@@(BEFORE|AFTER|SECOND|REPORT)$", out, flags=re.M)
    blocks = {sections[i]: sections[i + 1] for i in range(1, len(sections) - 1, 2)}
    return parse_counts(blocks["BEFORE"]), parse_counts(blocks["AFTER"]), parse_counts(blocks.get("SECOND", "")), blocks["REPORT"].strip()


def write_files(plan):
    for src in plan["sources"]:
        path = EVIDENCE_ROOT.parent.parent / src["file"]
        path.parent.mkdir(parents=True, exist_ok=True)
        if path.exists() and path.read_text(encoding="utf-8") != src["text"]:
            raise CollectError(f"{path} exists with different content")
        path.write_text(src["text"], encoding="utf-8")


def summarize(plan):
    lines = [f"course {plan['course_id']}: {len(plan['section_crns'])} section(s) in the grid, popups for {', '.join(plan['popup_crns']) or 'none'}"]
    for src in plan["sources"]:
        lines.append(f"  source {src['page_kind']:<16} crn={src['subject_crn'] or '-':<6} sha={src['sha'][:12]}  sections={len(src['sections'])} meetings={len(src['meetings'])} "
                     f"fields={len(src['fields'])} notes={len(src['notes'])} links={len(src['links'])}")
    return "\n".join(lines)


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    mode = ap.add_mutually_exclusive_group(required=True)
    mode.add_argument("--dry-run", action="store_true")
    mode.add_argument("--apply", action="store_true")
    ap.add_argument("--twice", action="store_true", help="with --dry-run: import a second time in the same transaction and require no change")
    ap.add_argument("--show-sql", action="store_true", help="print every INSERT that would run")
    ap.add_argument("--setup-sql", metavar="FILE", help="with --dry-run (tests only): run this SQL first, inside the rolled-back transaction")
    ap.add_argument("captures", nargs="+", metavar="CAPTURE[=SHA256]")
    args = ap.parse_args(argv)
    if args.twice and not args.dry_run:
        ap.error("--twice needs --dry-run")
    if args.setup_sql and not args.dry_run:
        ap.error("--setup-sql needs --dry-run (it must never run against the real data)")
    try:
        loaded = []
        for item in args.captures:
            path, _, sha = item.partition("=")
            raw, actual = load_capture(path, sha or None)
            loaded.append((raw, actual))
        plan = build_plan(loaded)
        print("VALIDATION PASSED (every field re-checked; no personal-data tripwire matched)")
        print(summarize(plan))
        if args.show_sql:
            print("\nPROPOSED STATEMENTS:")
            for s in generate_sql(plan):
                print("  " + s)
        if args.apply:
            write_files(plan)
        setup = Path(args.setup_sql).read_text(encoding="utf-8") if args.setup_sql else None
        before, after, second, report = run_import(plan, "dry-run" if args.dry_run else "apply", twice=args.twice, setup_sql=setup)
    except CollectError as exc:
        print("REJECTED: " + str(exc), file=sys.stderr)
        return 2
    added = {k: after[k] - before.get(k, 0) for k in after if after[k] != before.get(k, 0)}
    print(("\nDRY RUN (rolled back)" if args.dry_run else "\nAPPLIED (committed)") + " - rows added: " + (", ".join(f"{k}+{v}" for k, v in added.items()) or "none"))
    if args.twice:
        changed = {k: second[k] - after[k] for k in second if second[k] != after[k]}
        print("second run in the same transaction added: " + (", ".join(f"{k}+{v}" for k, v in changed.items()) or "nothing (idempotent)"))
        if changed:
            return 3
    print("\n" + report)
    return 0


if __name__ == "__main__":
    sys.exit(main())
