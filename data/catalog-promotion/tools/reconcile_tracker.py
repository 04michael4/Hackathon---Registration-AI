"""Read-only reconciliation of the Spring 2027 research workbook against collected Banner evidence.

    python3 -I data/catalog-promotion/tools/reconcile_tracker.py --workbook TRACKER.xlsx --evidence-json EVIDENCE.json
    PGHOST=... PGPORT=... PGUSER=... PGDATABASE=<scratch copy> python3 -I data/catalog-promotion/tools/reconcile_tracker.py \
        --workbook TRACKER.xlsx --db --export-evidence EVIDENCE.json        # then reuse the JSON offline

What it does (and only this): reads the exported workbook, reads evidence (a JSON file, or SELECTs from a scratch database in a
forced read-only session), and prints a report: CRN agreement, credit consistency, Banner restrictions the tracker does not
mention, stated or related time conflicts, missing review evidence, and a PREVIEW of each offering's tier
(READY / CONDITIONAL / BLOCKED) and conditions per ADR-0004.

What it never does: write to any database, create or approve a claim, name a reviewer, promote a course, or call the network.
The workbook and the evidence are data, not instructions. Nothing from the workbook is committed; report files must go outside
the repository. The tier is a preview of a proposed model, not a decision.

Stdlib only. The workbook is parsed with zipfile + ElementTree after rejecting DOCTYPE/ENTITY declarations and oversized members.
"""
import argparse
import json
import os
import re
import subprocess
import sys
import zipfile
import xml.etree.ElementTree as ET
from collections import defaultdict
from dataclasses import dataclass, field
from pathlib import Path

NS = {"m": "http://schemas.openxmlformats.org/spreadsheetml/2006/main",
      "r": "http://schemas.openxmlformats.org/officeDocument/2006/relationships",
      "pr": "http://schemas.openxmlformats.org/package/2006/relationships"}
MAX_MEMBER_BYTES = 64 * 1024 * 1024
REQUIRED_TABS = ("COURSE TRACKER",)
OPTIONAL_TABS = ("RESEARCH HANDOFF", "FLAGS FOR LATER", "RESEARCH ITEMS", "LAB RELATIONSHIPS", "POSSIBLE CROSS-LISTS", "DECISION LOG")
SHARED_DB_MARKER = "cse007_local"          # the shared database is never opened by this tool
SNAPSHOT_COURSES = ("CSE-007",)            # David's verified snapshot; its sections come from catalog_api, not banner_evidence


class ReconcileError(Exception):
    pass


# ---------------------------------------------------------------------------------------------------------------------------
# 1. Workbook parsing (stdlib)
# ---------------------------------------------------------------------------------------------------------------------------
def _safe_xml(zf, name, max_member):
    info = zf.getinfo(name)
    if info.file_size > max_member:
        raise ReconcileError(f"workbook member {name} is too large ({info.file_size} bytes)")
    data = zf.read(name)
    head = data[:4096].upper()
    if b"<!DOCTYPE" in head or b"<!ENTITY" in data.upper():
        raise ReconcileError(f"workbook member {name} declares a DOCTYPE/ENTITY; refusing to parse")
    return ET.fromstring(data)


def _col_index(ref):
    letters = re.match(r"([A-Z]+)", ref).group(1)
    n = 0
    for ch in letters:
        n = n * 26 + (ord(ch) - 64)
    return n - 1


def _text_of(el):
    """Concatenated <t> text of a shared/inline string, ignoring phonetic runs."""
    out = []

    def walk(node):
        for child in node:
            tag = child.tag.rsplit("}", 1)[-1]
            if tag == "rPh":
                continue
            if tag == "t":
                out.append(child.text or "")
            else:
                walk(child)
    walk(el)
    return "".join(out)


def _number_text(raw):
    try:
        f = float(raw)
    except ValueError:
        return raw
    return str(int(f)) if f == int(f) and abs(f) < 1e15 else repr(f)


def read_xlsx(path, max_member=MAX_MEMBER_BYTES):
    """Returns {sheet name: [[cell text, ...], ...]} with blank cells as '' (rows keep their position)."""
    try:
        zf = zipfile.ZipFile(path)
    except (zipfile.BadZipFile, OSError) as exc:
        raise ReconcileError(f"not a readable .xlsx workbook: {exc}") from exc
    with zf:
        names = set(zf.namelist())
        for need in ("xl/workbook.xml", "xl/_rels/workbook.xml.rels"):
            if need not in names:
                raise ReconcileError(f"not a spreadsheet workbook: missing {need}")
        shared = []
        if "xl/sharedStrings.xml" in names:
            for si in _safe_xml(zf, "xl/sharedStrings.xml", max_member).findall("m:si", NS):
                shared.append(_text_of(si))
        rels = {}
        for rel in _safe_xml(zf, "xl/_rels/workbook.xml.rels", max_member).findall("pr:Relationship", NS):
            target = rel.get("Target")
            rels[rel.get("Id")] = target.lstrip("/") if target.startswith("/") else "xl/" + target
        sheets = {}
        for sheet in _safe_xml(zf, "xl/workbook.xml", max_member).find("m:sheets", NS):
            name = sheet.get("name")
            target = rels.get(sheet.get("{%s}id" % NS["r"]))
            if target is None or target not in names:
                raise ReconcileError(f"sheet {name!r} has no worksheet part")
            rows = []
            for row in _safe_xml(zf, target, max_member).iter("{%s}row" % NS["m"]):
                idx = int(row.get("r", len(rows) + 1)) - 1
                cells = {}
                for c in row.findall("m:c", NS):
                    kind = c.get("t")
                    if kind == "inlineStr":
                        is_el = c.find("m:is", NS)
                        value = _text_of(is_el) if is_el is not None else ""
                    else:
                        v = c.find("m:v", NS)
                        raw = v.text if v is not None and v.text is not None else ""
                        if kind == "s":
                            value = shared[int(raw)] if raw.isdigit() and int(raw) < len(shared) else ""
                        elif kind == "b":
                            value = "TRUE" if raw == "1" else "FALSE"
                        elif kind in ("str", "e"):
                            value = raw
                        else:
                            value = _number_text(raw) if raw else ""
                    cells[_col_index(c.get("r", "A1"))] = value
                while len(rows) < idx:
                    rows.append([])
                width = max(cells) + 1 if cells else 0
                rows.append([cells.get(i, "") for i in range(width)])
            sheets[name] = rows
    return sheets


def table(rows, required_headers, scan=6):
    """Finds the header row (the first of the first `scan` rows holding every required header) and returns a list of
    {normalized header: text} dicts. Headers are matched case-insensitively on their prefix."""
    norm = lambda s: re.sub(r"\s+", " ", s.strip().lower())
    for hi, row in enumerate(rows[:scan]):
        cells = [norm(c) for c in row]
        if all(any(c.startswith(norm(h)) for c in cells) for h in required_headers):
            out = []
            for data in rows[hi + 1:]:
                if not any(x.strip() for x in data):
                    continue
                out.append({cells[i]: (data[i] if i < len(data) else "") for i in range(len(cells)) if cells[i]})
            return out
    raise ReconcileError("table with headers %s not found" % list(required_headers))


def get(row, header):
    """Value of the column whose header starts with `header` ('' when absent)."""
    h = re.sub(r"\s+", " ", header.strip().lower())
    for key, value in row.items():
        if key.startswith(h):
            return value
    return ""


# ---------------------------------------------------------------------------------------------------------------------------
# 2. Evidence
# ---------------------------------------------------------------------------------------------------------------------------
@dataclass
class Evidence:
    sections: list = field(default_factory=list)          # {course_id, crn, title, schedule_type, grid_hours, details_credit_hours, linked_outcome}
    meetings: list = field(default_factory=list)          # {crn, days:[1-7], start:'HH:MM', end:'HH:MM'}
    requirements: list = field(default_factory=list)      # {course_id, crn, kind, text, empty}
    snapshot_sections: list = field(default_factory=list)  # {course_id, crn, component_type}
    snapshot_meetings: list = field(default_factory=list)  # {crn, days, start, end}

    @staticmethod
    def from_json(obj):
        return Evidence(*[obj.get(k, []) for k in ("sections", "meetings", "requirements", "snapshot_sections", "snapshot_meetings")])

    def to_json(self):
        return {"sections": self.sections, "meetings": self.meetings, "requirements": self.requirements,
                "snapshot_sections": self.snapshot_sections, "snapshot_meetings": self.snapshot_meetings}


EVIDENCE_QUERY = """
SELECT json_build_object(
 'sections', COALESCE((SELECT json_agg(json_build_object('course_id', course_id, 'crn', crn, 'title', title, 'schedule_type', schedule_type,
     'grid_hours', grid_hours, 'details_credit_hours', details_credit_hours, 'linked_outcome', linked_outcome) ORDER BY course_id, crn)
     FROM catalog_promoted.evidence_section WHERE term_code = %(term)s), '[]'::json),
 'meetings', COALESCE((SELECT json_agg(json_build_object('crn', crn, 'days', days, 'start', start_time, 'end', end_time) ORDER BY crn)
     FROM (SELECT crn, array_agg(DISTINCT day_of_week ORDER BY day_of_week) AS days, to_char(start_time, 'HH24:MI') AS start_time,
                  to_char(end_time, 'HH24:MI') AS end_time
           FROM catalog_promoted.evidence_meeting WHERE term_code = %(term)s GROUP BY crn, start_time, end_time) q), '[]'::json),
 'requirements', COALESCE((SELECT json_agg(json_build_object('course_id', course_id, 'crn', crn, 'kind', note_kind, 'text', note_text,
     'empty', catalog_promoted.is_empty_requirement(note_kind, note_text)) ORDER BY course_id, crn, note_kind)
     FROM catalog_promoted.evidence_requirement WHERE term_code = %(term)s), '[]'::json),
 'snapshot_sections', COALESCE((SELECT json_agg(json_build_object('course_id', course_id, 'crn', crn, 'component_type', component_type)
     ORDER BY crn) FROM catalog_api.section_offering WHERE term_code = %(term)s), '[]'::json),
 'snapshot_meetings', COALESCE((SELECT json_agg(json_build_object('crn', crn, 'days', days, 'start', start_time, 'end', end_time) ORDER BY crn)
     FROM (SELECT crn, array_agg(day_of_week ORDER BY day_of_week) AS days, start_time, end_time
           FROM catalog_api.section_meeting WHERE term_code = %(term)s GROUP BY crn, start_time, end_time) q), '[]'::json)
)::text
"""


def psql_env(base=None):
    """Environment for a forced read-only session; refuses the shared database."""
    env = dict(os.environ if base is None else base)
    db = env.get("PGDATABASE", "")
    if not db:
        raise ReconcileError("PGDATABASE is not set (use a scratch copy of the evidence, never the shared database)")
    if SHARED_DB_MARKER in db:
        raise ReconcileError(f"refusing to open the shared database ({db}); use a scratch copy")
    env["PGOPTIONS"] = "-c default_transaction_read_only=on"
    return env


def load_evidence_from_db(term="202710", runner=subprocess.run):
    env = psql_env()
    sql = EVIDENCE_QUERY.replace("%(term)s", "'" + re.sub(r"[^0-9A-Za-z]", "", term) + "'")
    proc = runner(["psql", "-X", "-q", "-A", "-t", "-v", "ON_ERROR_STOP=1", "-c", sql], env=env, capture_output=True, text=True, timeout=120)
    if proc.returncode != 0:
        raise ReconcileError("evidence query failed: " + (proc.stderr or "")[-300:])
    return Evidence.from_json(json.loads(proc.stdout))


# ---------------------------------------------------------------------------------------------------------------------------
# 3. Small parsers
# ---------------------------------------------------------------------------------------------------------------------------
def to_minutes(hhmm):
    h, m = hhmm.split(":")[:2]
    return int(h) * 60 + int(m)


def meetings_overlap(a, b):
    return bool(set(a["days"]) & set(b["days"])) and to_minutes(a["start"]) < to_minutes(b["end"]) and to_minutes(b["start"]) < to_minutes(a["end"])


def course_codes(text):
    """CSE course ids mentioned in text, including 'CSE 313/413; 320/420' and 'CSE017-010' forms."""
    found = set()
    for m in re.finditer(r"\bCSE\s*-?\s*0*(\d{1,3})((?:\s*[/;,]\s*(?:CSE\s*)?0*\d{3})*)", text, re.I):
        nums = [m.group(1)] + re.findall(r"\d{3}", m.group(2))
        for n in nums:
            found.add("CSE-" + n.zfill(3))
    return found


CLASS_TOKENS = {"FR": r"first[- ]year|freshm|\bFR\b", "SO": r"sophomore|\bSO\b", "JR": r"junior|\bJR\b", "SR": r"senior|\bSR\b",
                "GR": r"graduate|\bGR\b"}
COLLEGE_TOKENS = {"EN": r"rossin|engrg|engineering college|college of engineering|\(EN\)|\bEN\b|college[^.\n]{0,40}engineering",
                  "AS": r"arts (?:&|and) sciences|\(AS\)"}
APPROVAL_TOKEN = r"department (?:permission|approval)|permission required|approval required"


@dataclass
class Rule:
    kind: str          # CLASS_STANDING, LEVEL, COLLEGE, MAJOR, DEPARTMENT_APPROVAL, UNPARSED
    polarity: str      # MUST | CANNOT | NEEDS
    values: tuple
    raw: str = ""


_CLAUSE = re.compile(r"(Must|Cannot) be enrolled in one of the following (Classes|Levels|Colleges|Majors|Fields of Study[^:]*|Campuses|Degrees|Programs):\s*"
                     r"(.*?)(?=(?:Must|Cannot) be enrolled in one of the following|Special Approvals:|\Z)", re.S)
_KIND = {"Classes": "CLASS_STANDING", "Levels": "LEVEL", "Colleges": "COLLEGE", "Majors": "MAJOR"}


def parse_restriction(text):
    """Banner restriction text -> [Rule]. Anything not consumed becomes an UNPARSED rule (the model fails closed on it)."""
    body = re.sub(r"^\s*Not all restrictions are applicable\.?\s*", "", text.strip())
    rules, consumed = [], []
    for m in _CLAUSE.finditer(body):
        label, raw = m.group(2), m.group(3).strip()
        kind = _KIND.get(label, "MAJOR" if label.startswith("Fields of Study") else "UNPARSED")
        values = tuple(dict.fromkeys(re.findall(r"\(([A-Z]{2,5})\)", raw))) or (raw[:60],)
        rules.append(Rule(kind, "MUST" if m.group(1) == "Must" else "CANNOT", values, raw))
        consumed.append(m.span())
        leftover = re.sub(r"[A-Za-z0-9&.,'/\- ]*?\([A-Z]{2,5}\)", "", raw).strip()
        if leftover or not re.search(r"\([A-Z]{2,5}\)", raw):
            rules.append(Rule("UNPARSED", "MUST", (leftover[:60] or raw[:60],), leftover or raw))   # text we cannot account for: fail closed
    sa = re.search(r"Special Approvals:\s*(.*)\Z", body, re.S)
    if sa:
        rules.append(Rule("DEPARTMENT_APPROVAL" if re.search(APPROVAL_TOKEN, sa.group(1), re.I) else "UNPARSED", "NEEDS", (sa.group(1).strip()[:60],), sa.group(1)))
        consumed.append(sa.span())
    rest = body
    for a, b in sorted(consumed, reverse=True):
        rest = rest[:a] + rest[b:]
    if rest.strip():
        rules.append(Rule("UNPARSED", "MUST", (rest.strip()[:60],), rest.strip()))
    return rules


def rule_mentioned(rule, text):
    """(covered, missing values) of whether the tracker `text` names this Banner rule. Heuristic keyword match."""
    t = text or ""
    if rule.kind in ("CLASS_STANDING", "LEVEL"):
        missing = [v for v in rule.values if not re.search(CLASS_TOKENS.get(v, re.escape(v)), t, re.I)]
        if rule.kind == "LEVEL" and re.search(r"graduate[- ]only|\bGR[- ]only|graduate level|graduate standing", t, re.I):
            missing = []
        return (not missing, missing)
    if rule.kind == "COLLEGE":
        missing = [v for v in rule.values if not re.search(COLLEGE_TOKENS.get(v, r"\(" + re.escape(v) + r"\)"), t, re.I)]
        return (not missing, missing)
    if rule.kind == "MAJOR":
        named = re.search(r"computer science|\bmajor|field of study|\bCRSC\b", t, re.I) and re.search(r"\bmajors?\b|field of study", t, re.I)
        return (bool(named), [] if named else list(rule.values))
    if rule.kind == "DEPARTMENT_APPROVAL":
        ok = bool(re.search(APPROVAL_TOKEN, t, re.I))
        return (ok, [] if ok else list(rule.values))
    return (False, list(rule.values))


# ---------------------------------------------------------------------------------------------------------------------------
# 4. Workbook model: offerings and the text that describes each
# ---------------------------------------------------------------------------------------------------------------------------
@dataclass
class Offering:
    offering_id: str
    course_id: str
    topic: str
    crns: list
    row: dict
    text_cols: dict = field(default_factory=dict)


FINDING_COLUMNS = {
    "tracker": ("prereqs & restrictions", "cross-list", "component", "your research notes", "credits to verify"),
    "items": ("currently reported", "exact source quote", "conclusion proposed"),
    "flags": ("flagged issue", "what the official", "what remains", "next action"),
    "decisions": ("decision needed", "evidence / constraint", "decision + supporting"),
    "handoff": ("question / issue", "status after research", "action that remains"),
    "lab": ("required lab count", "lab credit meaning", "evidence already", "your findings"),
    "audit": ("claude-reported banner finding",),
    "cross": ("why not verified", "what to investigate", "source wording", "reported similarity"),
}


def tab_rows(sheets, name, headers):
    if name not in sheets:
        return []
    return table(sheets[name], headers)


def load_workbook(path_or_sheets, max_member=MAX_MEMBER_BYTES):
    sheets = path_or_sheets if isinstance(path_or_sheets, dict) else read_xlsx(path_or_sheets, max_member)
    for need in REQUIRED_TABS:
        if need not in sheets:
            raise ReconcileError(f"workbook has no {need!r} tab (found: {sorted(sheets)})")
    wb = {"tracker": table(sheets["COURSE TRACKER"], ("Course", "CRN(s) reported"))}
    wb["items"] = tab_rows(sheets, "RESEARCH ITEMS", ("ID", "Course / topic", "Research status"))
    wb["flags"] = tab_rows(sheets, "FLAGS FOR LATER", ("Course(s)", "Flagged issue"))
    wb["decisions"] = tab_rows(sheets, "DECISION LOG", ("Applies to", "Decision status"))
    wb["handoff"] = tab_rows(sheets, "RESEARCH HANDOFF", ("Course(s)", "Question / issue"))
    wb["lab"] = tab_rows(sheets, "LAB RELATIONSHIPS", ("Course", "Lecture CRN"))
    wb["cross"] = tab_rows(sheets, "POSSIBLE CROSS-LISTS", ("Possible pairing",))
    wb["audit"] = tab_rows(sheets, "BANNER AUDIT GAPS", ("Gap ID", "Course", "Rule category"))
    wb["tabs"] = {name: len(rows) for name, rows in sheets.items()}
    return wb


def offerings_of(wb):
    out = []
    for row in wb["tracker"]:
        course = get(row, "course").strip()
        m = re.match(r"([A-Z]{2,4})\s*0*(\d{3})", course)
        if not m:
            continue
        course_id = f"{m.group(1)}-{m.group(2)}"
        topic = get(row, "offering / topic")
        crns = re.findall(r"\b\d{5}\b", get(row, "crn(s) reported"))
        out.append(Offering(course_id, course_id, topic, crns, row))
    multi = defaultdict(list)
    for o in out:
        multi[o.course_id].append(o)
    for course_id, group in multi.items():
        if len(group) > 1:
            for o in group:
                m = re.match(r"\s*(\d{3})\b", o.topic)
                o.offering_id = f"{course_id}-{m.group(1)}" if m else f"{course_id}-{o.crns[0] if o.crns else '?'}"
    return out


def selected_text(row, headers):
    return "\n".join(v for k, v in row.items() if any(k.startswith(h) for h in headers) and v)


def text_for(wb, offering):
    """All finding text the workbook holds about this offering (question/prompt columns are excluded)."""
    code = offering.course_id
    blobs = [selected_text(offering.row, FINDING_COLUMNS["tracker"])]
    for kind, rows, col in (("items", wb["items"], "course / topic"), ("flags", wb["flags"], "course(s)"),
                            ("decisions", wb["decisions"], "applies to"), ("handoff", wb["handoff"], "course(s)"),
                            ("lab", wb["lab"], "course"), ("cross", wb["cross"], "possible pairing"),
                            ("audit", wb["audit"], "course")):
        for r in rows:
            if code in course_codes(get(r, col)):
                blobs.append(selected_text(r, FINDING_COLUMNS[kind]))
    return "\n".join(b for b in blobs if b)


def own_text(wb, offering):
    """Text that is about this course alone (rows naming several courses, such as pairings, are left out), used for claims."""
    code = offering.course_id
    blobs = [selected_text(offering.row, FINDING_COLUMNS["tracker"])]
    for kind, rows, col in (("items", wb["items"], "course / topic"), ("flags", wb["flags"], "course(s)"),
                            ("decisions", wb["decisions"], "applies to"), ("handoff", wb["handoff"], "course(s)"),
                            ("lab", wb["lab"], "course")):
        for r in rows:
            if course_codes(get(r, col)) == {code}:
                blobs.append(selected_text(r, FINDING_COLUMNS[kind]))
    return "\n".join(b for b in blobs if b)


# ---------------------------------------------------------------------------------------------------------------------------
# 5. Checks
# ---------------------------------------------------------------------------------------------------------------------------
@dataclass
class Finding:
    check: str         # CRN, CREDITS, RESTRICTION, CONFLICT, REVIEW
    severity: str      # DIFF (tracker and evidence disagree), GAP (evidence the tracker lacks), INFO
    offering: str
    message: str


def evidence_index(ev):
    by_course = defaultdict(list)
    for s in ev.sections:
        by_course[s["course_id"]].append(s)
    meet = defaultdict(list)
    for m in ev.meetings + ev.snapshot_meetings:
        meet[m["crn"]].append(m)
    snap = defaultdict(list)
    for s in ev.snapshot_sections:
        snap[s["course_id"]].append(s)
    reqs = defaultdict(list)
    for r in ev.requirements:
        reqs[r["course_id"]].append(r)
    return by_course, meet, snap, reqs


def check_crns(offerings, ev):
    by_course, _, snap, _ = evidence_index(ev)
    findings = []
    rows_by_course = defaultdict(list)
    for o in offerings:
        rows_by_course[o.course_id].append(o)
    for course_id, group in sorted(rows_by_course.items()):
        ev_crns = {s["crn"] for s in by_course.get(course_id, [])}
        src = "banner_evidence"
        if not ev_crns and course_id in snap:
            ev_crns, src = {s["crn"] for s in snap[course_id]}, "catalog_api snapshot"
        tr_crns = {c for o in group for c in o.crns}
        label = group[0].offering_id if len(group) == 1 else course_id
        if not ev_crns:
            findings.append(Finding("CRN", "DIFF", label, f"tracker lists CRNs {sorted(tr_crns)} but there is no evidence for this course"))
        elif not tr_crns:
            findings.append(Finding("CRN", "GAP", label, f"tracker CRN column is blank; {src} has {sorted(ev_crns)}"))
        elif tr_crns != ev_crns:
            findings.append(Finding("CRN", "DIFF", label, f"CRNs differ: tracker-only {sorted(tr_crns - ev_crns)}, {src}-only {sorted(ev_crns - tr_crns)}"))
        else:
            findings.append(Finding("CRN", "INFO", label, f"CRNs agree with {src} ({len(ev_crns)})"))
    return findings


_CREDIT = re.compile(r"(\d+(?:\.\d+)?)\s*(?:total\s+|course\s+)?credits?\b", re.I)


def tracker_credit_claim(offering):
    col = get(offering.row, "credits to verify")
    m = _CREDIT.search(col)
    if m:
        return float(m.group(1))
    notes = get(offering.row, "your research notes")
    num = offering.course_id[-3:]
    m = re.search(r"CSE\s*0*%s\s*=\s*(\d+(?:\.\d+)?)\s*(?:total\s+|course\s+)?credits?" % num, notes, re.I)
    if m:
        return float(m.group(1))
    for crn in offering.crns:
        m = re.search(r"CRN\s*%s\s*=\s*(\d+(?:\.\d+)?)\s*(?:total\s+|course\s+)?credits?" % crn, col + "\n" + notes, re.I)
        if m:
            return float(m.group(1))
    return None


def _hours(s):
    try:
        return float(s)
    except (TypeError, ValueError):
        return None


def check_credits(offerings, ev):
    by_course, _, _, _ = evidence_index(ev)
    findings = []
    for o in offerings:
        secs = [s for s in by_course.get(o.course_id, []) if not o.crns or s["crn"] in o.crns or len(by_course.get(o.course_id, [])) == len(o.crns)]
        secs = [s for s in secs if not o.crns or s["crn"] in o.crns] or by_course.get(o.course_id, [])
        lectures = [s for s in secs if s["schedule_type"].lower().startswith("lec")]
        for s in secs:
            g, d = _hours(s["grid_hours"]), _hours(s["details_credit_hours"])
            kind = s["schedule_type"].lower()
            if g is None or d is None:
                findings.append(Finding("CREDITS", "GAP", o.offering_id, f"CRN {s['crn']}: a credit display is missing or not numeric"))
            elif kind.startswith("lec") and g != d:
                findings.append(Finding("CREDITS", "DIFF", o.offering_id, f"CRN {s['crn']}: lecture grid hours {s['grid_hours']} differ from Class Details credit hours {s['details_credit_hours']}"))
            elif kind.startswith("lec") and g != int(g):
                findings.append(Finding("CREDITS", "GAP", o.offering_id, f"CRN {s['crn']}: credits {s['grid_hours']} are not a whole number"))
            elif kind.startswith("lab") and g != d:
                findings.append(Finding("CREDITS", "INFO", o.offering_id, f"CRN {s['crn']}: lab grid hours {s['grid_hours']} vs Class Details {s['details_credit_hours']} (semantics need reviewer acceptance)"))
        claim = tracker_credit_claim(o)
        if claim is not None and lectures:
            ev_total = _hours(lectures[0]["grid_hours"])
            if ev_total is not None and claim != ev_total:
                findings.append(Finding("CREDITS", "DIFF", o.offering_id, f"tracker states {claim:g} credits, lecture evidence shows {ev_total:g}"))
            elif ev_total is not None:
                findings.append(Finding("CREDITS", "INFO", o.offering_id, f"tracker credit claim {claim:g} matches lecture evidence"))
    return findings


def grad_only_claim(sentence):
    """True for 'graduate-only' / 'GR only'; not for 'JR / SR / GR only' or 'Junior/Senior/Graduate only' (a list of standings)."""
    for m in re.finditer(r"(?:graduate|\bGR)[- ]only", sentence, re.I):
        if not re.search(r"(?:\bJR|\bSR|junior|senior|undergraduate)\W*(?:(?:or|and)\W*)?$", sentence[:m.start()], re.I):
            return True
    return False


def tracker_claims(restriction_col, other_text, code):
    """Claims the workbook makes about this course: any sentence of its own 'prereqs & restrictions' cell, plus sentences elsewhere
    that name this course and no other (a sentence without a course name is usually about a pair, so it is not counted)."""
    sentences = re.split(r"(?<=[.;])\s+|\n", restriction_col)
    sentences += [x for x in re.split(r"(?<=[.;])\s+|\n", other_text) if course_codes(x) == {code}]
    claims = set()
    for sentence in sentences:
        if course_codes(sentence) - {code}:
            continue
        if re.search(r"(first[- ]year|\bFR\b)[^.\n]{0,60}(exclud|cannot|restrict)|(exclud|cannot)[^.\n]{0,60}(first[- ]year|\bFR\b)", sentence, re.I):
            claims.add("FR_EXCLUDED")
        if grad_only_claim(sentence):
            claims.add("GRAD_ONLY")
    return claims


_NEGATED = re.compile(r"\bno (?:explicit |published |formal |stated )?(?:credit[- ])?exclusion|\bno explicit|not (?:stated|explicit|established|proof)|"
                      r"cannot (?:assume|infer|approve|treat)|do not|does not|not establish|not two|unverified|unresolved|unknown|silence|omit", re.I)
_POSITIVE = re.compile(r"exclu|no[- ]credit|credit will not|cannot be taken|denies credit|not be given|no-double-credit|cannot earn|enrollment restriction", re.I)
_HISTORICAL = re.compile(r"existed in|older|historical|conflict", re.I)


def exclusion_state(text):
    """DOCUMENTED when a sentence of the workbook affirms an exclusion; CONFLICTING_SOURCES when it describes an older or
    contradicting one; otherwise UNRESOLVED (silence is not 'none')."""
    sentences = re.split(r"(?<=[.;])\s+|\n", text)
    if any(_POSITIVE.search(x) and not _NEGATED.search(x) for x in sentences):
        return "DOCUMENTED"
    if any(_POSITIVE.search(x) and _HISTORICAL.search(x) for x in sentences):
        return "CONFLICTING_SOURCES"
    return "UNRESOLVED"


def check_restrictions(wb, offerings, ev):
    _, _, _, reqs = evidence_index(ev)
    findings = []
    for o in offerings:
        text = text_for(wb, o)
        rules = []
        for r in reqs.get(o.course_id, []):
            if r["kind"] == "REGISTRATION_RESTRICTION" and not r["empty"] and (not o.crns or r["crn"] in o.crns or len(set(x["crn"] for x in reqs[o.course_id])) == 1):
                rules += parse_restriction(r["text"])
        seen = set()
        for rule in rules:
            key = (rule.kind, rule.polarity, rule.values)
            if key in seen:
                continue
            seen.add(key)
            if rule.kind == "UNPARSED":
                findings.append(Finding("RESTRICTION", "GAP", o.offering_id, f"Banner restriction text not fully understood ({rule.raw[:60]!r}); the model fails closed on it"))
                continue
            covered, missing = rule_mentioned(rule, text)
            label = f"{rule.polarity.lower()} {rule.kind} {','.join(rule.values)}"
            if covered:
                findings.append(Finding("RESTRICTION", "INFO", o.offering_id, f"Banner {label}: tracker mentions it"))
            else:
                part = f" (tracker lacks {','.join(missing)})" if missing and len(missing) < len(rule.values) else ""
                findings.append(Finding("RESTRICTION", "GAP", o.offering_id, f"Banner {label}: tracker does not mention it{part}"))
        ev_fr = any(r.kind == "CLASS_STANDING" and r.polarity == "CANNOT" and "FR" in r.values for r in rules)
        ev_grad = any((r.kind in ("LEVEL", "CLASS_STANDING") and r.polarity == "MUST" and r.values == ("GR",)) for r in rules)
        claims = tracker_claims(get(o.row, "prereqs & restrictions"), own_text(wb, o), o.course_id)
        if "FR_EXCLUDED" in claims and not ev_fr:
            findings.append(Finding("RESTRICTION", "DIFF", o.offering_id, "tracker says first-year students are excluded, Banner evidence has no such rule"))
        if "GRAD_ONLY" in claims and not ev_grad:
            findings.append(Finding("RESTRICTION", "DIFF", o.offering_id, "tracker says graduate-only, Banner evidence has no graduate-only rule"))
    return findings


def audit_rule(category, finding):
    """What a BANNER AUDIT GAPS row claims: [(kind, polarity or None, values or None)]. Unknown wording yields []."""
    cat, out = category.lower(), []
    if "class" in cat:
        values = tuple(sorted(v for v, pat in CLASS_TOKENS.items() if re.search(pat, finding, re.I)))
        polarity = "CANNOT" if re.search(r"exclud|cannot|not allowed", finding, re.I) else "MUST"
        if values:
            out.append(("CLASS_STANDING", polarity, values))
    if "college" in cat:
        values = tuple(sorted(v for v, pat in COLLEGE_TOKENS.items() if re.search(pat, finding, re.I)))
        if values:
            out.append(("COLLEGE", "MUST", values))
    if "major" in cat:
        out.append(("MAJOR", "MUST", None))
    return out


def check_audit_tab(wb, offerings, ev):
    """Each row of the BANNER AUDIT GAPS tab must be backed by a Banner restriction in the evidence; status and reviewer are reported
    but never invented or repeated."""
    rows = wb.get("audit", [])
    if not rows:
        return []
    _, _, _, reqs = evidence_index(ev)
    findings = []
    pending = supported = with_reviewer = 0
    for r in rows:
        codes = sorted(course_codes(get(r, "course")))
        gid = get(r, "gap id") or "?"
        label = codes[0] if codes else gid
        status = get(r, "review status").strip().upper()
        pending += status.startswith("PENDING")
        with_reviewer += bool(get(r, "reviewer").strip())
        wanted = audit_rule(get(r, "rule category"), get(r, "claude-reported banner finding") or get(r, "finding"))
        have = []
        for c in codes:
            for q in reqs.get(c, []):
                if q["kind"] == "REGISTRATION_RESTRICTION" and not q["empty"]:
                    have += parse_restriction(q["text"])
        if not wanted:
            findings.append(Finding("AUDIT", "GAP", label, f"{gid}: the row's wording was not understood, so it cannot be matched to Banner evidence"))
            continue
        ok = True
        for kind, polarity, values in wanted:
            hit = any(h.kind == kind and (polarity is None or h.polarity == polarity) and (values is None or set(values) <= set(h.values) and
                      (kind != "CLASS_STANDING" or set(values) == set(h.values))) for h in have)
            if not hit:
                ok = False
                findings.append(Finding("AUDIT", "DIFF", label, f"{gid}: no matching Banner {kind} rule in the evidence ({','.join(values) if values else 'any'})"))
        if ok:
            supported += 1
    findings.append(Finding("AUDIT", "INFO", "BANNER AUDIT GAPS", f"{len(rows)} rows: {supported} backed by Banner evidence, {pending} 'pending human review', "
                                                                    f"{with_reviewer} with a reviewer recorded"))
    return findings


def section_meetings(ev, course_id, crns=None):
    by_course, meet, snap, _ = evidence_index(ev)
    secs = by_course.get(course_id) or snap.get(course_id, [])
    out = []
    for s in secs:
        if crns and s["crn"] not in crns:
            continue
        for m in meet.get(s["crn"], []):
            out.append((s["crn"], m))
    return out


def overlap_between(ev, a, b):
    """First overlapping (crn_a, crn_b, meeting_a, meeting_b) between two (course_id, crns) tuples, else None."""
    for ca, ma in section_meetings(ev, *a):
        for cb, mb in section_meetings(ev, *b):
            if meetings_overlap(ma, mb):
                return ca, cb, ma, mb
    return None


def fmt_meeting(m):
    days = "".join("MTWRFSU"[d - 1] for d in sorted(m["days"]))
    return f"{days} {m['start']}-{m['end']}"


def stated_conflict_pairs(wb):
    """Pairs of CSE courses a workbook sentence calls conflicting. Sentences with > 3 courses or a negation are skipped."""
    pairs = set()
    blobs = []
    for kind, rows in (("items", wb["items"]), ("flags", wb["flags"]), ("decisions", wb["decisions"]), ("handoff", wb["handoff"]),
                       ("cross", wb["cross"]), ("tracker", wb["tracker"])):
        for r in rows:
            blobs.append(selected_text(r, FINDING_COLUMNS[kind]))
    for blob in blobs:
        for sentence in re.split(r"[.;\n]", blob):
            if not re.search(r"conflict|collid|collision|overlap", sentence, re.I):
                continue
            if re.search(r"no (?:time )?conflict|conflict-free|clash-free|without (?:a )?conflict|not conflict", sentence, re.I):
                continue
            codes = sorted(course_codes(sentence))
            if 2 <= len(codes) <= 3:
                for i in range(len(codes)):
                    for j in range(i + 1, len(codes)):
                        pairs.add((codes[i], codes[j]))
    return pairs


def cross_list_pairs(wb, offerings):
    pairs = []
    for r in wb["cross"]:
        raw = get(r, "possible pairing")
        parts = re.split(r"\s*(?:↔|<->|<=>|/)\s*", raw)
        if len(parts) != 2:
            continue
        left, right = parts
        lm = re.match(r"\s*CSE\s*0*(\d{3})(?:-(\d{3}))?", left)
        rm = re.match(r"\s*(?:CSE\s*)?0*(\d{3})(?:-(\d{3}))?\s*$", right)
        if not lm or not rm:
            pairs.append((raw, None, None))       # alias to another subject: no CRN to compare
            continue
        a = f"CSE-{lm.group(1)}" + (f"-{lm.group(2)}" if lm.group(2) else "")
        b = f"CSE-{rm.group(1)}" + (f"-{rm.group(2)}" if rm.group(2) else "")
        pairs.append((raw, a, b))
    return pairs


def check_conflicts(wb, offerings, ev):
    by_id = {o.offering_id: o for o in offerings}
    by_course = defaultdict(list)
    for o in offerings:
        by_course[o.course_id].append(o)

    def target(offering_id):
        if offering_id in by_id:
            o = by_id[offering_id]
            return (o.course_id, set(o.crns) or None)
        base = offering_id[:7]
        return (base, None) if base in by_course else None

    findings = []
    stated = stated_conflict_pairs(wb)
    for a, b in sorted(stated):
        ta, tb = target(a), target(b)
        if not ta or not tb:
            findings.append(Finding("CONFLICT", "INFO", f"{a} / {b}", "workbook states a conflict, but one course has no schedule data here"))
            continue
        hit = overlap_between(ev, ta, tb)
        if hit:
            findings.append(Finding("CONFLICT", "INFO", f"{a} / {b}", f"stated conflict CONFIRMED: CRN {hit[0]} {fmt_meeting(hit[2])} overlaps CRN {hit[1]} {fmt_meeting(hit[3])}"))
        else:
            findings.append(Finding("CONFLICT", "DIFF", f"{a} / {b}", "workbook states a conflict, the meeting data shows none"))
    for raw, a, b in cross_list_pairs(wb, offerings):
        if a is None:
            continue
        ta, tb = target(a), target(b)
        if not ta or not tb:
            continue
        if tuple(sorted((a[:7], b[:7]))) in stated:
            continue
        hit = overlap_between(ev, ta, tb)
        findings.append(Finding("CONFLICT", "INFO", f"{a} / {b}", ("related pair overlaps in time: CRN %s %s vs CRN %s %s" % (hit[0], fmt_meeting(hit[2]), hit[1], fmt_meeting(hit[3]))) if hit else "related pair does not overlap in time"))
    for o in offerings:
        text = selected_text(o.row, FINDING_COLUMNS["tracker"])
        if re.search(r"arranged|second weekly|team meeting|project-team", text, re.I):
            findings.append(Finding("CONFLICT", "INFO", o.offering_id, "tracker describes a mandatory arranged meeting with no time; it is not in Banner's meetings, so the timetable is conditional"))
    return findings


_STATUS_WORDS = re.compile(r"source found|awaiting|not cross-listed|section pairing|strong public|academic identity|canonical|needs official|ready for team|open|pending|^n/a$", re.I)


def check_review(wb, offerings):
    """Reports where the workbook shows no named reviewer. It never names or invents one."""
    findings = []
    ready = {"ready for team review", "source found", "evidence gathered", "partially clarified"}
    per = defaultdict(int)
    total = 0
    for r in wb["items"]:
        if get(r, "research status").strip().lower() in ready and not get(r, "reviewer").strip():
            total += 1
            for c in course_codes(get(r, "course / topic")):
                per[c] += 1
    reviewed = sum(1 for r in wb["items"] if get(r, "reviewer").strip())
    findings.append(Finding("REVIEW", "GAP", "RESEARCH ITEMS", f"{total} of {len(wb['items'])} items are at or past 'source found' with no reviewer or date; {reviewed} items carry a reviewer"))
    labs = [r for r in wb["lab"] if not get(r, "human reviewer").strip() and get(r, "research status").strip().lower() in ready]
    if labs:
        findings.append(Finding("REVIEW", "GAP", "LAB RELATIONSHIPS", f"{len(labs)} lab-relationship rows are ready for review with no human reviewer recorded"))
    xl = [r for r in wb["cross"] if _STATUS_WORDS.search(get(r, "decision / reviewer")) or not get(r, "decision / reviewer").strip()]
    if wb["cross"]:
        findings.append(Finding("REVIEW", "GAP", "POSSIBLE CROSS-LISTS", f"{len(xl)} of {len(wb['cross'])} rows have a status or nothing in the reviewer column; none names a reviewer"))
    undated = [r for r in wb["decisions"] if not get(r, "decision date").strip()]
    if wb["decisions"]:
        findings.append(Finding("REVIEW", "GAP", "DECISION LOG", f"{len(undated)} of {len(wb['decisions'])} decisions have no decision date"))
    for o in offerings:
        if get(o.row, "research status").strip().lower() == "ready for team review":
            findings.append(Finding("REVIEW", "GAP", o.offering_id, f"tracker status 'Ready for team review'; {per.get(o.course_id, 0)} research items for this course have no reviewer"))
    if not reviewed and not any(get(r, "human reviewer").strip() for r in wb["lab"]):
        findings.append(Finding("REVIEW", "GAP", "ALL", "the workbook shows no designated reviewer of record; no claim can be treated as approved"))
    return findings


# ---------------------------------------------------------------------------------------------------------------------------
# 6. Tier preview (ADR-0004; a preview, not a decision)
# ---------------------------------------------------------------------------------------------------------------------------
@dataclass
class Preview:
    offering_id: str
    crns: list
    tier: str
    blockers: list
    conditions: list


def preview_tier(wb, offering, offerings_of_course, ev):
    by_course, meet, snap, reqs = evidence_index(ev)
    cid = offering.course_id
    if cid in SNAPSHOT_COURSES and cid in snap:
        return Preview(offering.offering_id, offering.crns, "READY", [], ["PROVENANCE_USER_CONFIRMED_NONE"])
    secs = [s for s in by_course.get(cid, []) if not offering.crns or s["crn"] in offering.crns]
    blockers, conditions = [], []
    if not secs:
        return Preview(offering.offering_id, offering.crns, "BLOCKED", ["NO_EVIDENCE"], [])
    if any(not s["schedule_type"].lower().startswith(("lec", "lab")) for s in secs):
        kinds = sorted({s["schedule_type"] for s in secs if not s["schedule_type"].lower().startswith(("lec", "lab"))})
        blockers.append("SECTION_TYPE_UNSUPPORTED:" + ",".join(kinds) + " (needs a designated reviewer's decision)")
    if len(offerings_of_course) > 1:
        blockers.append("TOPIC_IDENTITY_UNSUPPORTED (pending API decision 4)")
    for s in secs:
        g, d = _hours(s["grid_hours"]), _hours(s["details_credit_hours"])
        if s["schedule_type"].lower().startswith("lec") and (g is None or d is None or g != d):
            blockers.append(f"CREDIT_DISPLAYS_DIFFER:{s['crn']}")
        if not meet.get(s["crn"]):
            blockers.append(f"NO_MEETING_TIMES:{s['crn']}")
    text = text_for(wb, offering)
    for r in reqs.get(cid, []):
        if r["kind"] == "REGISTRATION_RESTRICTION" and not r["empty"]:
            for rule in parse_restriction(r["text"]):
                tag = f"{rule.kind}_{rule.polarity}:{','.join(rule.values)}"
                if tag not in conditions:
                    conditions.append(tag)
        if r["kind"] in ("PREREQUISITE_TEXT", "COREQUISITE_TEXT") and not r["empty"]:
            tag = "PREREQUISITE" if r["kind"] == "PREREQUISITE_TEXT" else "COREQUISITE"
            if tag not in conditions:
                conditions.append(tag)
    labs = [s for s in secs if s["schedule_type"].lower().startswith("lab")]
    confirmed = re.search(r"user[-_ ]confirm|user explicitly confirms", get(offering.row, "component / lab") + get(offering.row, "credits to verify"), re.I)
    if labs:
        conditions.append("LINKED_LABS_USER_SUPPLIED" if confirmed else "LINKED_LABS_UNVERIFIED")
    elif secs and any(s["linked_outcome"] == "NONE_SHOWN" for s in secs):
        stand = re.search(r"(explicitly )?confirms?[^.\n]{0,80}(without an additional|on its own|standalone)", get(offering.row, "component / lab"), re.I)
        conditions.append("LINKED_COMPONENTS_USER_SUPPLIED" if stand else "LINKED_COMPONENTS_UNVERIFIED")
    excl = get(offering.row, "cross-list")
    conditions.append("CREDIT_EXCLUSION_" + exclusion_state(excl))
    if not re.search(r"not cross-listed", excl + text, re.I):
        conditions.append("CROSS_LISTING_UNVERIFIED")
    if re.search(r"arranged|second weekly|team meeting|project-team", get(offering.row, "component / lab") + get(offering.row, "prereqs & restrictions"), re.I):
        conditions.append("ARRANGED_MEETING")
    return Preview(offering.offering_id, offering.crns, "BLOCKED" if blockers else "CONDITIONAL", blockers, conditions)


# ---------------------------------------------------------------------------------------------------------------------------
# 7. Report
# ---------------------------------------------------------------------------------------------------------------------------
def reconcile(workbook, evidence):
    wb = load_workbook(workbook)
    ev = evidence if isinstance(evidence, Evidence) else Evidence.from_json(evidence)
    offerings = offerings_of(wb)
    groups = defaultdict(list)
    for o in offerings:
        groups[o.course_id].append(o)
    findings = (check_crns(offerings, ev) + check_credits(offerings, ev) + check_restrictions(wb, offerings, ev)
                + check_audit_tab(wb, offerings, ev) + check_conflicts(wb, offerings, ev) + check_review(wb, offerings))
    previews = [preview_tier(wb, o, groups[o.course_id], ev) for o in offerings]
    return {"wb": wb, "offerings": offerings, "findings": findings, "previews": previews}


def render(result, evidence=None):
    wb, findings, previews = result["wb"], result["findings"], result["previews"]
    tiers = defaultdict(int)
    for p in previews:
        tiers[p.tier] += 1
    lines = ["RECONCILIATION REPORT (read-only; a preview of ADR-0004 tiers, not a decision; no approval or reviewer is implied)",
             f"workbook tabs: " + ", ".join(f"{k} ({v} rows)" for k, v in sorted(wb["tabs"].items())),
             f"offerings in COURSE TRACKER: {len(previews)}",
             f"tier preview: {tiers.get('READY', 0)} READY / {tiers.get('CONDITIONAL', 0)} CONDITIONAL / {tiers.get('BLOCKED', 0)} BLOCKED",
             f"counts: " + ", ".join(f"{sev} {sum(1 for f in findings if f.severity == sev)}" for sev in ("DIFF", "GAP", "INFO")), ""]
    for check, title in (("CRN", "CRN agreement"), ("CREDITS", "Credit consistency"), ("RESTRICTION", "Banner restrictions vs tracker"), ("AUDIT", "BANNER AUDIT GAPS tab vs Banner evidence"),
                         ("CONFLICT", "Time conflicts and arranged meetings"), ("REVIEW", "Missing review evidence")):
        part = [f for f in findings if f.check == check]
        lines.append(f"== {title}: " + ", ".join(f"{sev} {sum(1 for f in part if f.severity == sev)}" for sev in ("DIFF", "GAP", "INFO")))
        for f in sorted(part, key=lambda f: ({"DIFF": 0, "GAP": 1, "INFO": 2}[f.severity], f.offering, f.message)):
            if f.severity == "INFO" and check in ("CRN", "CREDITS"):
                continue
            lines.append(f"  [{f.severity}] {f.offering}: {f.message}")
        lines.append("")
    gaps = defaultdict(list)
    for f in findings:
        if f.check == "RESTRICTION" and f.severity == "GAP" and "tracker does not mention it" in f.message:
            gaps[f.message.split(": tracker")[0]].append(f.offering)
    lines.append("== Banner restrictions the tracker does not mention, grouped")
    for rule, offs in sorted(gaps.items()):
        lines.append(f"  {rule} -> {len(offs)} offerings: " + ", ".join(sorted(offs)))
    lines += ["", "== Not resolvable from the available evidence (offerings affected)"]
    for tag, why in (("LINKED_COMPONENTS_UNVERIFIED", "standalone registration (an empty Linked Sections tab is not proof)"),
                     ("LINKED_LABS_UNVERIFIED", "required lab count / pairing not confirmed by anyone"),
                     ("CROSS_LISTING_UNVERIFIED", "Banner's Cross Listed Courses tab was never collected"),
                     ("CREDIT_EXCLUSION_UNRESOLVED", "no exclusion statement found (silence is not 'none')"),
                     ("CREDIT_EXCLUSION_CONFLICTING_SOURCES", "older and current sources disagree"),
                     ("PREREQUISITE", "needs a completed-courses input to evaluate"),
                     ("ARRANGED_MEETING", "the extra meeting has no published time")):
        hit = sorted(p.offering_id for p in previews if tag in p.conditions)
        if hit:
            lines.append(f"  {tag} ({why}): {len(hit)}")
    lines += ["", "== Per-offering tier preview (offering | CRNs | tier | blockers | conditions)"]
    for p in sorted(previews, key=lambda p: p.offering_id):
        lines.append(f"  {p.offering_id} | {','.join(p.crns) or '-'} | {p.tier} | {'; '.join(p.blockers) or '-'} | {', '.join(p.conditions) or '-'}")
    return "\n".join(lines) + "\n"


def refuse_repo_output(out_dir):
    out = Path(out_dir).resolve()
    here = Path(__file__).resolve()
    for parent in here.parents:
        if (parent / ".git").exists():
            if out == parent or parent in out.parents:
                raise ReconcileError(f"refusing to write report files inside the repository ({parent}); use a directory outside it")
            break
    return out


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    ap.add_argument("--workbook", required=True, help="exported .xlsx (read only)")
    src = ap.add_mutually_exclusive_group(required=True)
    src.add_argument("--evidence-json", help="evidence exported earlier")
    src.add_argument("--db", action="store_true", help="SELECT evidence from the PG* database (scratch copy only; read-only session)")
    ap.add_argument("--term", default="202710")
    ap.add_argument("--export-evidence", help="with --db: also write the evidence JSON here (outside the repository)")
    ap.add_argument("--out-dir", help="write report.txt here (outside the repository)")
    args = ap.parse_args(argv)
    try:
        if args.evidence_json:
            ev = Evidence.from_json(json.loads(Path(args.evidence_json).read_text(encoding="utf-8")))
        else:
            ev = load_evidence_from_db(args.term)
            if args.export_evidence:
                dest = refuse_repo_output(Path(args.export_evidence).parent) / Path(args.export_evidence).name
                dest.write_text(json.dumps(ev.to_json(), indent=1), encoding="utf-8")
        report = render(reconcile(args.workbook, ev), ev)
        sys.stdout.write(report)
        if args.out_dir:
            out = refuse_repo_output(args.out_dir)
            out.mkdir(parents=True, exist_ok=True)
            (out / "report.txt").write_text(report, encoding="utf-8")
    except ReconcileError as exc:
        sys.exit("reconcile_tracker: " + str(exc))


if __name__ == "__main__":
    main()
