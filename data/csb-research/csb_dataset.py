"""Pure functions that turn the collected CSB research inputs into the payload of catalog_csb.import_csb (stdlib only; no database, no network).

Inputs (all but the program file are LOCAL-ONLY: real CRNs, instructor names and catalog prose never enter the repository):
  grids      csb-grid-<SUBJ>-<NUM>.json   the Browse Classes results grid of one search, instructor cell included (collector_local.js)
  packs      pack-NNN.json                the Class Details panels of every section, deduplicated by collector_local.js pack()
  catalog    the parsed public catalog pages (catalog_courses.py parse)
  program    csb_program_2026_27.json     the degree structure (committed; codes and numbers only)

Fail-closed rules, inherited from data/catalog-research/noncse_details.py (which does the Banner text parsing): text that is not recognized
EXACTLY stays as words (TEXT_ONLY); no rule is invented. Where the catalog and Banner disagree both statements are kept and flagged.
"""
import hashlib
import json
import re
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent / "catalog-research"))
import noncse_details as nd  # noqa: E402  (reused Banner restriction / prerequisite parser)

TERM = "202710"
# Banner subject description -> catalog code. Extends noncse_details.SUBJECTS; an unknown name keeps a prerequisite TEXT_ONLY.
SUBJECTS = dict(nd.SUBJECTS)
SUBJECTS.update({
    "Computer Science and Business": "CSB", "Computer Science & Business": "CSB", "Business": "BUS", "Law": "LAW",
    "Supply Chain Management": "SCM", "Astronomy": "ASTR", "Earth & Environmental Science": "EES", "Business & Health": "BUHE",
    "Entrepreneurship": "ENTP", "Cognitive Science": "COGS", "Environmental Studies": "EVST", "Engineering Management": "EMC",
    "Biostatistics": "BSTA", "Integrated Business & Engineer": "IBE",
})
# a Banner restriction group the shared parser does not know becomes a rule only if listed here (everything else stays TEXT_ONLY)
EXTRA_RESTRICTION_KINDS = {"Degrees": "DEGREE", "Cohorts": "COHORT"}
COMPONENT = {"Lecture": "LECTURE", "Lab": "LAB", "Recitation": "RECITATION", "Independent Study": "INDEPENDENT_STUDY"}
DAYS = {"Monday": 1, "Tuesday": 2, "Wednesday": 3, "Thursday": 4, "Friday": 5, "Saturday": 6, "Sunday": 7}
MEETING = re.compile(
    r"^(?P<days>[A-Za-z,]+) S M T W T F S (?:(?P<start>\d\d:\d\d [AP]M) - (?P<end>\d\d:\d\d [AP]M)|-) "
    r"Type: (?P<type>.+?) Building: (?P<building>.*?) Room: (?P<room>.*?) Start Date: (?P<sd>\d\d/\d\d/\d{4}) End Date: (?P<ed>\d\d/\d\d/\d{4})$")
HOURS = re.compile(r"^(\d+(?:\.\d+)?)(?: TO (\d+(?:\.\d+)?))?$")
CRN = re.compile(r"^[0-9]{5}$")
CATALOG_CODE = re.compile(r"[A-Z]{2,5} \d{3}")
PLACEHOLDER = re.compile(r"^\s*staff\b", re.I)


class DatasetError(Exception):
    pass


def sha256_json(obj):
    return hashlib.sha256(json.dumps(obj, sort_keys=True, separators=(",", ":"), ensure_ascii=False).encode("utf-8")).hexdigest()


def to_time(text):
    """'03:00 PM' -> '15:00'."""
    h, m, ap = re.match(r"(\d\d):(\d\d) ([AP]M)", text).groups()
    h = int(h) % 12 + (12 if ap == "PM" else 0)
    return f"{h:02d}:{m}"


def to_date(text):
    """'01/25/2027' -> '2027-01-25'."""
    mo, d, y = text.split("/")
    return f"{y}-{mo}-{d}"


def location(building, room):
    parts = [p for p in (building, room) if p and p != "None"]
    return " ".join(parts).strip() or "TBA"


def parse_meeting(raw):
    """One grid meeting string -> {'kind': 'WEEKLY'|'DATED_ONE_TIME'|'ARRANGED', ...}. Raises DatasetError on an unrecognized shape."""
    m = MEETING.match(raw)
    if not m:
        raise DatasetError(f"meeting text not recognized: {raw!r}")
    mtype = m["type"]
    delivery = None
    if mtype.endswith(" On-Campus Only"):
        mtype, delivery = mtype[: -len(" On-Campus Only")], "On-Campus Only"
    if m["start"] is None:                                   # no clock time: an arranged meeting, never a timetable slot
        return {"kind": "ARRANGED", "type": mtype, "description": raw, "startDate": to_date(m["sd"]), "endDate": to_date(m["ed"])}
    days = [DAYS[d] for d in m["days"].split(",")]
    sd, ed = to_date(m["sd"]), to_date(m["ed"])
    base = {"type": mtype, "delivery": delivery, "start": to_time(m["start"]), "end": to_time(m["end"]),
            "location": location(m["building"], m["room"]), "startDate": sd, "endDate": ed}
    kind = "DATED_ONE_TIME" if (sd == ed or mtype == "Common Hour Exam") else "WEEKLY"
    return dict(base, kind=kind, days=sorted(days))


def parse_hours(text):
    m = HOURS.match(text or "")
    if not m:
        raise DatasetError(f"credit hours not recognized: {text!r}")
    lo = float(m.group(1))
    hi = float(m.group(2)) if m.group(2) else lo
    return lo, hi


def course_code(subject_description, number):
    code = SUBJECTS.get(subject_description)
    if code is None:
        raise DatasetError(f"Banner subject {subject_description!r} has no catalog code in SUBJECTS")
    return code, number, f"{code}-{number}"


# ---------------------------------------------------------------------------------------------------------------------------------------
# catalog and Banner requirement text
def parse_restrictions(text):
    saved = dict(nd.RESTRICTION_KINDS)
    nd.RESTRICTION_KINDS.update(EXTRA_RESTRICTION_KINDS)
    try:
        return nd.parse_restrictions(text)
    finally:
        nd.RESTRICTION_KINDS.clear()
        nd.RESTRICTION_KINDS.update(saved)


def parse_banner_requirement(text, kind):
    parsed = nd.parse_prerequisite(text, SUBJECTS) if kind == "PREREQUISITE" else nd.parse_corequisite(text, SUBJECTS)
    grades = sorted(set(re.findall(r"\b(?:Undergraduate|Graduate)\s+([A-Z]{1,2})\b", text or "")))
    parsed["minGradeShown"] = grades          # the 'Grade' column as Banner prints it (e.g. TR); its meaning is not interpreted here
    return parsed


def parse_catalog_prerequisite(text):
    """The catalog's 'Prerequisites:' line ('ECO 001 and (MATH 021 or MATH 031)') -> {'status', 'expression'}. Strict: anything else is TEXT_ONLY."""
    t = nd.clean(text)
    if not t:
        return {"status": "NONE_LISTED", "expression": None}
    body = re.sub(r",\s*\)", ")", t)                       # the catalog sometimes prints '(a or b, )'
    tokens = re.findall(r"\(|\)|\b(?:and|or)\b|[A-Z]{2,5} \d{3}|\S+", body)
    toks = []
    for tok in tokens:
        if tok in "()":
            toks.append((tok, None))
        elif tok in ("and", "or"):
            toks.append(("op", tok.upper()))
        elif CATALOG_CODE.fullmatch(tok):
            toks.append(("course", tuple(tok.split(" "))))
        else:
            return {"status": "TEXT_ONLY", "expression": None}
    expr = nd._parse([(k, v if k != "course" else (v[0], v[1])) for k, v in toks], {c: c for c in {v[0] for k, v in toks if k == "course"}})
    if expr is None:
        return {"status": "TEXT_ONLY", "expression": None}
    return {"status": "RULE_DEFINED", "expression": expr}


def canonical(expr):
    """Canonical form for comparing two prerequisite expressions: nested same operators flattened, duplicates removed, arguments sorted."""
    if expr is None:
        return None
    if "course" in expr:
        return expr["course"]
    args = []
    for a in expr["args"]:
        c = canonical(a)
        if isinstance(c, dict) and c["op"] == expr["op"]:
            args.extend(c["args"])
        else:
            args.append(c)
    uniq = sorted({json.dumps(a, sort_keys=True): a for a in args}.values(), key=lambda a: json.dumps(a, sort_keys=True))
    return uniq[0] if len(uniq) == 1 else {"op": expr["op"], "args": uniq}


def expression_courses(expr):
    if expr is None:
        return set()
    if "course" in expr:
        return {expr["course"]}
    return set().union(*(expression_courses(a) for a in expr["args"]))


def compare_prerequisites(catalog_text, banner_texts):
    """catalog_text: the catalog line (or None). banner_texts: {variant_text: [crns]} of the Banner prerequisite panel texts of the course's sections.
    Returns one comparison dict per Banner variant. Both statements are always kept; nothing is resolved."""
    cat = parse_catalog_prerequisite(catalog_text) if catalog_text else {"status": "NONE_LISTED", "expression": None}
    out = []
    for text, crns in sorted(banner_texts.items()):
        ban = parse_banner_requirement(text, "PREREQUISITE")
        if cat["status"] == "NONE_LISTED" and ban["status"] == "NONE_LISTED":
            status = "MATCH_BOTH_SILENT"
        elif cat["status"] == "RULE_DEFINED" and ban["status"] == "RULE_DEFINED":
            a, b = canonical(cat["expression"]), canonical(ban["expression"])
            if a == b:
                status = "MATCH"
            elif expression_courses(cat["expression"]) == expression_courses(ban["expression"]):
                status = "SAME_COURSES_DIFFERENT_LOGIC"
            else:
                status = "CONFLICT"
        elif cat["status"] == "NONE_LISTED":
            status = "BANNER_ONLY"
        elif ban["status"] == "NONE_LISTED":
            status = "CATALOG_ONLY"
        else:
            status = "NOT_COMPARABLE_TEXT_ONLY"
        out.append({"status": status, "crns": sorted(crns), "catalogText": catalog_text, "bannerText": text if ban["status"] != "NONE_LISTED" else None,
                    "catalogExpression": cat["expression"], "bannerExpression": ban["expression"]})
    return out


# ---------------------------------------------------------------------------------------------------------------------------------------
# packs
def unpack(pack):
    """The compact pack written by collector_local.js pack() -> {crn: capture dict} with the strings restored."""
    if pack.get("v") != 1:
        raise DatasetError("unknown pack version")
    s = pack["strings"]
    text = lambda i: None if i == -1 else s[i]
    out = {}
    for crn, a in pack["secs"].items():
        term, sched, sec, hours, campus, grade, attrs, restr, pre, co, xl, lk = a
        cross = {"rows": [], "panel_text": text(xl)} if isinstance(xl, int) else {"rows": [dict(zip(("crn", "subject", "course_number", "title", "section"), r)) for r in xl], "panel_text": None}
        linked = {"options": [], "panel_text": text(lk)} if isinstance(lk, int) else {
            "options": [{"label": f"Option {i + 1}", "sections": [dict(zip(("crn", "schedule_type", "section"), m)) for m in opt]} for i, opt in enumerate(lk)], "panel_text": None}
        out[crn] = {"term_code": term, "crn": crn, "details": {"schedule_type": sched, "section": sec, "credit_hours": hours, "campus": text(campus), "grade_mode": text(grade)},
                    "attributes_text": text(attrs), "restrictions_text": text(restr), "prerequisites_text": text(pre), "corequisites_text": text(co),
                    "cross_list": cross, "linked": linked}
    return out


def rebuild_meeting(t):
    """A v2 pack's meeting tuple -> the grid's own meeting string (the inverse of the compact export)."""
    days, start, end, mtype, building, room, sd, ed = t
    clock = f"{start} - {end}" if start else "-"
    return f"{days} S M T W T F S {clock} Type: {mtype} Building: {building} Room: {room} Start Date: {sd} End Date: {ed}"


def unpack_v2(pack):
    """-> (captures by crn, grid rows by crn, search log). Same compact string table as v1, plus the grid rows and the searches that produced them."""
    caps = unpack({"v": 1, "strings": pack["strings"], "secs": pack["secs"]})
    s = pack["strings"]
    text = lambda i: None if i == -1 else s[i]
    rows = {}
    for crn, g in pack["grid"].items():
        subject, number, section, title, hours, mtype, attr, linked, meetings, instructors = g
        rows[crn] = {"crn": crn, "subject": text(subject), "number": number, "section": section, "title": text(title), "hours": hours, "type": mtype,
                     "attribute": text(attr), "linked": text(linked), "meetings": [rebuild_meeting(m) for m in meetings],
                     "instructors": [{"name": n, "role": r} for n, r in instructors]}
    searches = [{"file": "pack-v2", "subject": x["subject"], "courseNumber": x["number"], "count": x["count"], "capturedAt": None} for x in pack.get("searches", [])]
    return caps, rows, searches


def load_packs(directory):
    """-> (captures, grid rows from v2 packs, searches from v2 packs). A later pack replaces an earlier capture of the same CRN."""
    caps, rows, searches = {}, {}, []
    for path in sorted(Path(directory).glob("pack-*.json")):
        pack = json.loads(path.read_text(encoding="utf-8"))
        if pack.get("v") == 1:
            caps.update(unpack(pack))
        elif pack.get("v") == 2:
            c, r, s = unpack_v2(pack)
            caps.update(c)
            rows.update(r)
            searches.extend(s)
        else:
            raise DatasetError(f"{path.name}: unknown pack version")
    return caps, rows, searches


def load_grids(directory):
    """-> (rows by crn, searches). searches: {(subject_label, course_number or None): row count} for the exact searches that were run."""
    rows, searches = {}, []
    for path in sorted(Path(directory).glob("csb-grid-*.json")):
        d = json.loads(path.read_text(encoding="utf-8"))
        label = d.get("search_label") or ""
        m = re.search(r"Subject:\s*(.*?)(?:Course Number:\s*(\S+))?$", label)
        searches.append({"file": path.name, "subject": m.group(1).strip() if m else None, "courseNumber": m.group(2) if m and m.group(2) else None,
                         "count": int(d["banner_count"]), "capturedAt": d.get("captured_at")})
        if int(d["banner_count"]) != len(d["rows"]):
            raise DatasetError(f"{path.name}: Banner says {d['banner_count']} classes but {len(d['rows'])} rows were read (page size?)")
        for r in d["rows"]:
            rows[r["crn"]] = r
    return rows, searches


# ---------------------------------------------------------------------------------------------------------------------------------------
# payload
PANELS_INSPECTED = ["Class Details", "Attributes", "Restrictions", "Prerequisites", "Corequisites", "Cross Listed Courses", "Linked Sections"]
PANELS_NOT_INSPECTED = ["Instructor/Meeting Times (instructors were read from the results grid)", "Enrollment/Waitlist (seat counts are never stored)", "Fees",
                        "Course Description (the public catalog was used)", "Lehigh Bookstore Link"]
SEARCH_SUBJECT_LABEL = re.compile(r"Subject:\s*(.*?)(?:Course Number:\s*(\S+))?$")


def numbers_in(text):
    return [float(x) for x in re.findall(r"\d+(?:\.\d+)?", text or "")]


def catalog_course_row(course_id, found, source_key):
    subject, number = course_id.split("-")
    if found is None:
        return {"courseId": course_id, "subject": subject, "number": number, "title": None, "creditsText": None, "creditsMin": None, "creditsMax": None,
                "attributes": [], "prerequisiteText": None, "prerequisiteStatus": "NOT_FOUND", "prerequisiteExpression": None, "concurrentText": None,
                "corequisiteText": None, "description": None, "aliases": [], "repeatStatus": None, "catalogStatus": "NOT_FOUND_IN_2026_27_CATALOG",
                "catalogYear": "2026-27", "sourceKey": source_key}
    nums = numbers_in(found["credits_text"])
    pre = parse_catalog_prerequisite(found["prerequisites_text"]) if found.get("prerequisites_text") else {"status": "NONE_LISTED", "expression": None}
    return {"courseId": course_id, "subject": subject, "number": number, "title": found["title"], "creditsText": found["credits_text"],
            "creditsMin": min(nums) if nums else None, "creditsMax": max(nums) if nums else None, "attributes": found["attributes"],
            "prerequisiteText": found.get("prerequisites_text"), "prerequisiteStatus": pre["status"], "prerequisiteExpression": pre["expression"],
            "concurrentText": found.get("concurrent_text"), "corequisiteText": found.get("corequisites_text"), "description": found.get("description") or None,
            "aliases": found.get("catalog_aliases", []), "repeatStatus": found.get("repeat_status"), "catalogStatus": "CATALOG_PAGE_READ",
            "catalogYear": "2026-27", "sourceKey": source_key}


def program_ids(program):
    """Every course id the program file names (requirements, lists, sequences, tracks)."""
    ids = set()
    pat = re.compile(r"^[A-Z]{2,5}-\d{3}$")

    def walk(o):
        if isinstance(o, dict):
            for k, v in o.items():
                if k == "course" and isinstance(v, str) and pat.match(v):
                    ids.add(v)
                walk(v)
        elif isinstance(o, list):
            for v in o:
                if isinstance(v, str) and pat.match(v):
                    ids.add(v)
                else:
                    walk(v)
    for key in ("requirements", "lists", "ns_sequences"):
        walk(program[key])
    ids |= set(program["ns_sequences"]["optional_labs"])      # the optional lab courses are dictionary keys
    return ids


def build_program(program):
    """-> (groups, lists, nsSequences, nsLabs, nsFootnotes). Categories stay categories; nothing is turned into a mandatory course."""
    groups = []
    for cat in program["requirements"]:
        top = {"key": cat["key"], "parentKey": None, "title": cat["title"], "logic": "ALL", "n": None, "credits": cat["credits"], "ruleText": None, "status": "GROUP", "note": None, "options": []}
        groups.append(top)
        order = 0
        for item in cat["items"]:
            if item["type"] == "COURSE":
                order += 1
                top["options"].append({"optionSet": order, "optionOrder": 1, "courseId": item["course"], "listKey": None, "ruleText": None})
            elif item["type"] == "ANY_OF":
                child = {"key": item["key"], "parentKey": cat["key"], "title": item["key"], "logic": "ANY", "n": None, "credits": item["credits"], "ruleText": None,
                         "status": "ALTERNATIVES", "note": item.get("note"), "options": []}
                for s, alt in enumerate(item["alternatives"], 1):
                    for o, course in enumerate(alt, 1):
                        child["options"].append({"optionSet": s, "optionOrder": o, "courseId": course, "listKey": None, "ruleText": None})
                groups.append(child)
            elif item["type"] == "CHOOSE_FROM_LIST":
                big = item["credits"] != 3 or item.get("approval")
                child = {"key": item["key"], "parentKey": cat["key"], "title": item["key"], "logic": "CREDITS" if big else "CHOOSE_N", "n": None if big else item["n"],
                         "credits": item["credits"], "ruleText": ("%s credits chosen from the list (approval by the program co-directors)" % item["credits"]) if big else None,
                         "status": "LIST_SELECTION", "note": item.get("note"), "options": [{"optionSet": 1, "optionOrder": 1, "courseId": None, "listKey": item["list"], "ruleText": None}]}
                groups.append(child)
            elif item["type"] == "CATEGORY":
                natural = item["key"] == "NW_12_CREDITS"
                groups.append({"key": item["key"], "parentKey": cat["key"], "title": item["key"], "logic": "CREDITS", "n": None, "credits": item["credits"], "ruleText": item["rule"],
                               "status": "NATURAL_WORLD_RULE" if natural else "CATEGORY_NOT_COURSE_SPECIFIC", "note": item.get("note"), "options": []})
            else:
                raise DatasetError("unknown requirement item type " + item["type"])
    lists = []
    cs = program["lists"]["CS_ELECTIVE_LIST"]
    lists.append({"key": "CS_ELECTIVE_LIST", "title": cs["title"], "authority": cs["authority"], "status": "CATALOG_LISTED", "statement": cs["also_approved"],
                  "members": [{"courseId": m["course"], "memberGroup": "CATALOG_LIST", "track": "", "listedCreditsText": m["listed_credits"], "memberStatus": "CATALOG_LISTED",
                               "basis": "Listed in the 2026-27 catalog's Computer Science & Business Electives"} for m in cs["members"]]})
    pe = program["lists"]["CSB_PROFESSIONAL_ELECTIVES"]
    members = []
    for grp in pe["candidate_groups"]:
        if grp["group"] == "WEBSITE_TRACK_EXAMPLES":
            for track, courses in grp["tracks"].items():
                for course in courses:
                    members.append({"courseId": course, "memberGroup": grp["group"], "track": track, "listedCreditsText": None, "memberStatus": "APPROVAL_NOT_VERIFIED", "basis": grp["basis"]})
        elif grp["group"] == "CSE_APPROVED_LIST_COURSES":
            for m in cs["members"]:
                members.append({"courseId": m["course"], "memberGroup": grp["group"], "track": "", "listedCreditsText": m["listed_credits"], "memberStatus": "APPROVAL_NOT_VERIFIED", "basis": grp["basis"]})
        else:
            for course in grp["courses"]:
                members.append({"courseId": course, "memberGroup": grp["group"], "track": "", "listedCreditsText": None, "memberStatus": "APPROVAL_NOT_VERIFIED", "basis": grp["basis"]})
    lists.append({"key": "CSB_PROFESSIONAL_ELECTIVES", "title": pe["title"], "authority": pe["authority"], "status": "APPROVAL_NOT_VERIFIED", "statement": pe["statement"], "members": members})
    ns = program["ns_sequences"]
    seqs = [{"key": s["key"], "family": s["family"], "listedCreditsText": s["listed_credits"], "footnoteMarks": s["footnote_marks"], "specialRule": s.get("special_rule"), "courses": s["courses"]}
            for s in ns["sequences"]]
    labs = [{"courseId": k, "creditsText": v["credits_text"], "appliesToFootnote": v["applies_to_footnote"]} for k, v in ns["optional_labs"].items()]
    notes = [{"mark": k, "text": v} for k, v in ns["footnotes"].items()]
    return groups, lists, seqs, labs, notes


def link_status(crn, panels):
    """panels: {crn: [[member crn, ...] per option]}. Same rule as the SQL view catalog_csb.link_status_derived:
    NO_LINKS_LISTED when no panel mentions this section; CONFIRMED_BOTH_SIDES when every listing that involves it is mutual; otherwise
    LISTED_BY_ONE_SIDE_ONLY (it lists a section that does not list it back, or a section lists it and it does not list that section)."""
    own = {m for opt in panels.get(crn, []) for m in opt}
    incoming = {c for c, opts in panels.items() if c != crn and crn in {m for opt in opts for m in opt}}
    if not own and not incoming:
        return "NO_LINKS_LISTED"
    if any(crn not in {x for opt in panels.get(m, []) for x in opt} for m in own) or (incoming - own):
        return "LISTED_BY_ONE_SIDE_ONLY"
    return "CONFIRMED_BOTH_SIDES"


def build_sections(grid_rows, caps, source_key):
    """-> (sections, offerings). Raises DatasetError on anything that cannot be stored exactly (an unknown subject, a missing Class Details capture, a link to a CRN that is not stored)."""
    missing = sorted(set(grid_rows) - set(caps))
    if missing:
        raise DatasetError(f"{len(missing)} grid sections have no Class Details capture: {missing[:8]}")
    extra = sorted(set(caps) - set(grid_rows))
    if extra:
        raise DatasetError(f"{len(extra)} Class Details captures have no grid row: {extra[:8]}")
    panels = {crn: [[m["crn"] for m in opt["sections"]] for opt in caps[crn]["linked"]["options"]] for crn in caps}
    sections, by_offering = [], {}
    for crn in sorted(grid_rows):
        g, cap = grid_rows[crn], caps[crn]
        code, number, offering_id = course_code(g["subject"], g["number"])
        if g["type"] not in COMPONENT:
            raise DatasetError(f"{crn}: schedule type {g['type']!r} has no component mapping")
        lo, hi = parse_hours(g["hours"])
        det = cap["details"]
        discrepancies = []
        for field, grid_v, det_v in (("CREDIT_HOURS", g["hours"], det["credit_hours"]), ("SCHEDULE_TYPE", g["type"], det["schedule_type"]), ("SECTION_NUMBER", g["section"], det["section"])):
            if grid_v != det_v:
                discrepancies.append({"field": field, "gridValue": grid_v, "detailsValue": det_v})
        meetings, arranged = [], []
        for raw in g["meetings"]:
            m = parse_meeting(raw)
            if m["kind"] == "ARRANGED":
                arranged.append({"type": m["type"], "description": m["description"], "startDate": m["startDate"], "endDate": m["endDate"]})
            else:
                for day in m["days"]:
                    meetings.append({"day": day, "start": m["start"], "end": m["end"], "location": m["location"], "type": m["type"], "kind": m["kind"], "startDate": m["startDate"], "endDate": m["endDate"]})
        restr = parse_restrictions(cap["restrictions_text"])
        pre = parse_banner_requirement(cap["prerequisites_text"], "PREREQUISITE")
        co = parse_banner_requirement(cap["corequisites_text"], "COREQUISITE")
        rules = [{"kind": r["kind"], "polarity": r["polarity"], "values": r["values"], "expression": None, "minGradeShown": [], "sourceText": restr["text"]} for r in restr["rules"]]
        if pre["status"] == "RULE_DEFINED":
            rules.append({"kind": "PREREQUISITE", "polarity": "REQUIRES", "values": [], "expression": pre["expression"], "minGradeShown": pre["minGradeShown"], "sourceText": pre["text"]})
        if co["status"] == "RULE_DEFINED":
            rules.append({"kind": "COREQUISITE", "polarity": "REQUIRES", "values": [], "expression": co["expression"], "minGradeShown": co["minGradeShown"], "sourceText": co["text"]})
        links = []
        for n, opt in enumerate(cap["linked"]["options"], 1):
            for m in opt["sections"]:
                if m["crn"] not in grid_rows:
                    raise DatasetError(f"{crn}: Linked Sections lists CRN {m['crn']} which is not a stored section")
                links.append({"optionNo": n, "memberCrn": m["crn"], "memberScheduleType": m["schedule_type"], "memberSection": m["section"]})
        instructors = [{"position": i, "name": x["name"], "role": x["role"], "placeholder": bool(PLACEHOLDER.match(x["name"]))} for i, x in enumerate(g["instructors"], 1)]
        sections.append({
            "crn": crn, "offeringId": offering_id, "sectionCode": g["section"], "componentType": COMPONENT[g["type"]], "scheduleType": g["type"],
            "creditsText": g["hours"], "creditsMin": lo, "creditsMax": hi, "attributeText": g["attribute"], "campus": det["campus"], "gradeMode": det["grade_mode"],
            "detailsCreditHoursText": det["credit_hours"], "creditsDisplayStatus": "GRID_AND_DETAILS_AGREE" if g["hours"] == det["credit_hours"] else "DETAILS_DIFFER_FROM_GRID",
            "linkStatus": link_status(crn, panels),
            "grid": {"courseLabel": f"{g['subject']} {g['number']}", "sectionNo": g["section"], "title": g["title"], "hoursGrid": g["hours"], "scheduleType": g["type"],
                     "attribute": g["attribute"], "linkedCell": g["linked"], "meetings": g["meetings"], "instructors": g["instructors"], "sourceKey": source_key},
            "meetings": meetings, "arranged": arranged, "instructors": instructors,
            "detail": {"panelsInspected": PANELS_INSPECTED, "panelsNotInspected": PANELS_NOT_INSPECTED, "restrictionsText": cap["restrictions_text"], "prerequisitesText": cap["prerequisites_text"],
                       "corequisitesText": cap["corequisites_text"], "crossListText": cap["cross_list"]["panel_text"], "linkedText": cap["linked"]["panel_text"],
                       "restrictionStatus": restr["status"], "prerequisiteStatus": pre["status"], "corequisiteStatus": co["status"], "sourceKey": source_key},
            "rules": rules, "links": links, "crossList": [{"crn": r["crn"], "subject": r["subject"], "courseNumber": r["course_number"], "title": r["title"], "section": r["section"]} for r in cap["cross_list"]["rows"]],
            "discrepancies": discrepancies,
        })
        by_offering.setdefault(offering_id, []).append((sections[-1], g))
    offerings = []
    for offering_id, items in sorted(by_offering.items()):
        lectures = [(s, g) for s, g in items if s["componentType"] == "LECTURE"]
        basis = lectures or items
        first_s, first_g = sorted(basis, key=lambda sg: sg[0]["sectionCode"])[0]
        texts = sorted({s["creditsText"] for s, _ in basis})
        if len(texts) == 1:
            lo, hi = parse_hours(texts[0])
            status, credits_text = ("VARIABLE_RANGE" if lo < hi else "SINGLE_VALUE"), texts[0]
        else:
            status, credits_text, lo, hi = "VARIES_BY_SECTION", None, None, None
        code, number, _ = course_code(first_g["subject"], first_g["number"])
        comps = {s["componentType"] for s, _ in items}
        offerings.append({"offeringId": offering_id, "courseId": offering_id, "subject": code, "number": number, "title": first_g["title"], "creditsText": credits_text,
                          "creditsMin": lo, "creditsMax": hi, "creditsStatus": status, "administrativeType": first_g["type"],
                          "componentsStatus": "COMPONENT_ONLY_COURSE" if not lectures and comps <= {"LAB", "RECITATION"} else ("LINKED_COMPONENTS_LISTED" if len(comps) > 1 else "SINGLE_COMPONENT")})
    return sections, offerings


def build_comparisons(catalog_courses, sections):
    """Catalog prerequisite statement vs the Banner statements of an offering's lecture sections (all sections when it has none)."""
    by = {}
    for s in sections:
        by.setdefault(s["offeringId"], []).append(s)
    out = []
    for offering_id, secs in sorted(by.items()):
        basis = [s for s in secs if s["componentType"] in ("LECTURE", "INDEPENDENT_STUDY")] or secs
        variants = {}
        for s in basis:
            variants.setdefault(s["detail"]["prerequisitesText"], []).append(s["crn"])
        cat = catalog_courses.get(offering_id) or {}
        for cmp in compare_prerequisites(cat.get("prerequisites_text"), variants):
            out.append({"courseId": offering_id, "status": cmp["status"], "crns": cmp["crns"], "catalogText": cmp["catalogText"], "bannerText": cmp["bannerText"],
                        "catalogExpression": cmp["catalogExpression"], "bannerExpression": cmp["bannerExpression"]})
    return out


def build_searches(search_rows, source_key):
    out = []
    for s in search_rows:
        code = None
        if s["courseNumber"]:
            try:
                code = course_code(s["subject"], s["courseNumber"])[2]
            except DatasetError:
                code = None
        out.append({"kind": "EXACT_COURSE" if s["courseNumber"] else "SUBJECT_WIDE", "subjectLabel": s["subject"], "courseNumber": s["courseNumber"], "courseId": code,
                    "classCount": s["count"], "result": "OFFERED" if s["count"] else "NOT_OFFERED", "sourceKey": source_key})
    return out


def generated_unresolved(sections, searches):
    items = []
    stored = {s["crn"] for s in sections}
    for s in sections:
        for x in s["crossList"]:
            if x["crn"] not in stored:
                items.append({"source": "BANNER_CROSS_LIST_PANEL", "subject": f"{s['offeringId']} CRN {s['crn']}",
                              "detail": f"Cross-listed with CRN {x['crn']} ({x['subject']} {x['courseNumber']}, {x['title']}, section {x['section']}), which this research did not collect; its details are NOT_COLLECTED."})
    n = sum(1 for s in sections if s["creditsDisplayStatus"] == "DETAILS_DIFFER_FROM_GRID")
    if n:
        items.append({"source": "BANNER_CREDITS", "subject": "LINKED_COMPONENT_CREDITS",
                      "detail": f"{n} sections show different credit hours in Class Details than in the results grid (a linked lab or recitation shows the bundled total in Class Details and 0 in the grid). Credits are stored as the grid shows them, counted once per lecture; whether a component adds credits is NOT_VERIFIED."})
    n = sum(1 for s in sections for i in s["instructors"] if i["placeholder"])
    if n:
        items.append({"source": "BANNER_INSTRUCTORS", "subject": "PLACEHOLDER_INSTRUCTORS", "detail": f"{n} instructor entries are placeholders (for example 'Staff, Teaching'); no professor is guessed for them."})
    return items


def build_payload(program, catalog, grid_rows, searches, caps, sources, term=TERM):
    """catalog = {'courses': {id: parsed}, 'not_found': [...]}. Returns the import payload (a dict)."""
    source_catalog, source_banner = sources["catalog"], sources["banner"]
    sections, offerings = build_sections(grid_rows, caps, source_banner)
    groups, lists, seqs, labs, notes = build_program(program)
    wanted = program_ids(program) | {o["courseId"] for o in offerings}
    courses = [catalog_course_row(cid, catalog["courses"].get(cid), source_catalog) for cid in sorted(wanted)]
    prog = program["program"]
    payload = {
        "termCode": term,
        "sources": sources["rows"],
        "catalogCourses": courses,
        "program": {"id": prog["id"], "name": prog["name"], "degreeType": prog["degree_type"], "catalogYear": prog["catalog_year"], "totalCredits": prog["total_credits"],
                    "noPassFail": prog["no_pass_fail"], "sourceKey": source_catalog, "notes": {"transferIn": prog["transfer_in"], "noPassFailNote": prog["no_pass_fail_note"]}},
        "groups": groups, "lists": lists, "nsSequences": seqs, "nsLabs": labs, "nsFootnotes": notes,
        "conflicts": [{"key": c["key"], "catalogStatement": c["catalog"], "otherStatement": c["other"], "status": c["status"]} for c in program["source_conflicts"]],
        "unresolved": [{"source": "PROGRAM_FILE", **u} for u in program["unresolved"]] + generated_unresolved(sections, searches),
        "searches": build_searches(searches, source_banner), "offerings": offerings, "sections": sections,
        "comparisons": build_comparisons(catalog["courses"], sections),
    }
    return payload
