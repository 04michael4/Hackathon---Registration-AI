"""Turns sanitized Banner Class Details captures (data/banner-evidence/collector/extract_popup.js) into the payload of
catalog_research.import_noncse_details (stdlib only; no database, no network).

Fail-closed by design: text that is not recognized EXACTLY becomes TEXT_ONLY (the words are kept, no rule is invented). In particular a
prerequisite that mixes "And" and "Or" at one level without parentheses is ambiguous (CSE 303 was) and stays TEXT_ONLY. Banner staying
silent ("No ... information is available") is recorded as NONE_LISTED, the project owner's rule for the CSE courses (2026-10-17: silent
Banner means none, user-supplied evidence, not Registrar certification).
"""
import re

TERM = "202710"
CLASS_CODES = {"FR", "SO", "JR", "SR", "GR"}
LEVEL_CODES = {"UG", "GR"}
# Banner subject descriptions -> the codes the catalog uses. Extend as captures reveal more; an unknown name makes the prerequisite TEXT_ONLY.
SUBJECTS = {
    "Computer Science & Engineering": "CSE", "Mathematics": "MATH", "Physics": "PHY", "Chemistry": "CHM", "Economics": "ECO",
    "Engineering": "ENGR", "Industrial & Systems Engr": "ISE", "Industrial & Systems Engineering": "ISE", "Writing": "WRT", "Marketing": "MKT",
    "Business Information Systems": "BIS", "Business Analytics": "BUAN", "Electrical & Computer Engr": "ECE", "Statistics": "STAT",
    "Industrial&Systems Engineering": "ISE", "Finance": "FIN", "Accounting": "ACCT", "Management": "MGT", "Data Science": "DSCI", "Mechanical Engr & Mechanics": "ME",
    "Materials Science & Engineering": "MAT", "Bioengineering": "BIOE", "Biological Sciences": "BIOS", "Earth & Environmental Sciences": "EES",
}
NONE_RESTRICTIONS = "No course restriction information is available."
NONE_PREREQ = "No prerequisite information available."
NONE_COREQ = "No corequisite course information available."
RESTRICTION_KINDS = {"Classes": "CLASS_STANDING", "Levels": "LEVEL", "Colleges": "COLLEGE", "Majors": "MAJOR"}
SEGMENT = re.compile(
    r"(?:(Must|Cannot) be enrolled in one of the following ([A-Za-z][A-Za-z ]*?):\s*(.*?)(?=\s(?:Must|Cannot) be enrolled in one of the following|\sSpecial Approvals:|$))"
    r"|(?:Special Approvals:\s*(.*?)(?=\s(?:Must|Cannot) be enrolled in one of the following|$))")
CODE_ITEM = re.compile(r"\(([A-Z0-9]{1,6})\)")
COURSE_ROW = re.compile(r"\s*([A-Za-z][A-Za-z&.,\- ]*?)\s+(\d{3})\s+(Undergraduate|Graduate)\s+([A-Z]{1,2})\b")
CLASS_ALIASES = {"First-Year Student": "FR", "Sophomore": "SO", "Junior": "JR", "Senior": "SR", "Graduate": "GR"}


def clean(text):
    return re.sub(r"\s+", " ", text or "").strip()


# ---------------------------------------------------------------------------------------------------------------------------------------
def parse_restrictions(text):
    """-> {"status": NONE_LISTED|RULE_DEFINED|TEXT_ONLY, "text": str|None, "rules": [...]}. All-or-nothing: one unknown segment = TEXT_ONLY."""
    t = clean(text)
    if not t or t == NONE_RESTRICTIONS:
        return {"status": "NONE_LISTED", "text": None, "rules": []}
    text_only = {"status": "TEXT_ONLY", "text": t, "rules": []}   # keep the words, invent nothing
    body = clean(t.replace("Not all restrictions are applicable.", ""))
    pos, rules = 0, []
    for m in SEGMENT.finditer(body):
        if clean(body[pos:m.start()]):
            return text_only                              # text between segments that no segment explains
        pos = m.end()
        if m.group(4) is not None:                       # Special Approvals
            name = clean(m.group(4))
            if not name:
                return text_only
            rules.append({"kind": "DEPARTMENT_APPROVAL", "polarity": "NEEDS", "values": [name]})
            continue
        kind, chunk = RESTRICTION_KINDS.get(m.group(2).strip()), m.group(3)
        codes = CODE_ITEM.findall(chunk)
        # Everything must be "<Name> (CODE)" items: after taking those out, nothing but names may remain.
        leftover = clean(re.sub(r"[A-Za-z&.,'/\- ]+?\s*\([A-Z0-9]{1,6}\)", "", chunk))
        if kind is None or not codes or leftover:
            return text_only
        if (kind == "CLASS_STANDING" and not set(codes) <= CLASS_CODES) or (kind == "LEVEL" and not set(codes) <= LEVEL_CODES):
            return text_only
        rules.append({"kind": kind, "polarity": "MUST" if m.group(1) == "Must" else "CANNOT", "values": sorted(set(codes))})
    if clean(body[pos:]) or not rules:
        return text_only
    return {"status": "RULE_DEFINED", "text": t, "rules": rules}


# ---------------------------------------------------------------------------------------------------------------------------------------
def _tokens(body):
    """Course rows, connectors and parentheses, in order. None if anything else is left over."""
    pos, out = 0, []
    while pos < len(body):
        rest = body[pos:]
        stripped = rest.lstrip()
        pos += len(rest) - len(stripped)
        if not stripped:
            break
        m = re.match(r"(And|Or)\b", stripped)
        if m:
            out.append(("op", m.group(1).upper())); pos += m.end(); continue
        if stripped[0] in "()":
            out.append((stripped[0], None)); pos += 1; continue
        m = COURSE_ROW.match(stripped)
        if m:
            subject = clean(m.group(1))
            out.append(("course", (subject, m.group(2)))); pos += m.end(); continue
        return None
    return out


def _parse(tokens, subjects):
    """Recursive descent. A level may mix connectors only if it is uniform: ambiguous And/Or mixing returns None."""
    def level(i):
        args, ops, pending = [], set(), False   # pending: a connector was read and still needs its operand
        while True:
            if i >= len(tokens):
                break
            pending = False
            kind, val = tokens[i]
            if kind == "(":
                node, i = level(i + 1)
                if node is None or i >= len(tokens) or tokens[i][0] != ")":
                    return None, i
                i += 1
                args.append(node)
            elif kind == "course":
                code = subjects.get(val[0])
                if code is None:
                    return None, i
                args.append({"course": f"{code}-{val[1]}"}); i += 1
            else:
                return None, i
            if i < len(tokens) and tokens[i][0] == "op":
                ops.add(tokens[i][1]); i += 1; pending = True
                continue
            break
        if not args or len(ops) > 1 or pending:
            return None, i
        if len(args) == 1:
            return args[0], i
        return {"op": ops.pop(), "args": args}, i
    node, end = level(0)
    return node if node is not None and end == len(tokens) else None


def parse_course_requirement(text, none_text, header, subjects=None):
    """Prerequisite or corequisite text -> {"status": NONE_LISTED|RULE_DEFINED|TEXT_ONLY, "text", "expression", "unmapped": [...]}."""
    subjects = SUBJECTS if subjects is None else subjects
    t = clean(text)
    if not t or none_text in t:
        return {"status": "NONE_LISTED", "text": None, "expression": None, "unmapped": []}
    body = t
    for prefix in (header, "And/Or Test Score Subject Course Number Level Grade"):
        body = body.replace(prefix, "", 1).strip()
    tokens = _tokens(body)
    unmapped = []
    if tokens is not None:
        unmapped = sorted({v[0] for k, v in tokens if k == "course" and v[0] not in subjects})
    expression = _parse(tokens, subjects) if tokens else None
    if expression is None:
        return {"status": "TEXT_ONLY", "text": t, "expression": None, "unmapped": unmapped}
    return {"status": "RULE_DEFINED", "text": t, "expression": expression, "unmapped": []}


def parse_prerequisite(text, subjects=None):
    return parse_course_requirement(text, NONE_PREREQ, "Catalog Prerequisites", subjects)


def parse_corequisite(text, subjects=None):
    return parse_course_requirement(text, NONE_COREQ, "Corequisites", subjects)


# ---------------------------------------------------------------------------------------------------------------------------------------
def links_from_popup(popup):
    """[(lab_crn, lecture_crn)] stated by this popup's Linked Sections tab (the lecture lists its labs, a lab lists its lecture)."""
    this = popup["crn"]
    kind = (popup.get("details") or {}).get("schedule_type", "")
    is_lecture = kind in ("Class", "Lecture")
    out = []
    for option in (popup.get("linked") or {}).get("options", []):
        for s in option.get("sections", []):
            other, other_kind = s.get("crn"), s.get("schedule_type", "")
            if not other or other == this:
                continue
            if is_lecture and other_kind not in ("Class", "Lecture"):
                out.append((other, this))
            elif not is_lecture and other_kind in ("Class", "Lecture"):
                out.append((this, other))
    return sorted(set(out))


def build_course_payload(course_id, popups, subjects=None):
    """popups: the sanitized Class Details captures of one course (any number of its sections). Restrictions, prerequisites and
    corequisites come from the first LECTURE popup (by CRN); another lecture that disagrees is reported in `disagreements`, never merged."""
    popups = sorted(popups, key=lambda p: p["crn"])
    # The requirements of the COURSE are read from its lecture sections (a lab or recitation page may legitimately say less); a course with
    # no captured lecture falls back to whatever was captured.
    lectures = [p for p in popups if (p.get("details") or {}).get("schedule_type", "") in ("Class", "Lecture")]
    basis = lectures or popups
    first = basis[0]
    parsed = [(p["crn"], parse_restrictions(p.get("restrictions_text")), parse_prerequisite(p.get("prerequisites_text"), subjects),
               parse_corequisite(p.get("corequisites_text"), subjects)) for p in basis]
    disagreements = []
    base = parsed[0]
    for crn, rest, pre, co in parsed[1:]:
        if (rest["status"], rest["rules"], rest["text"]) != (base[1]["status"], base[1]["rules"], base[1]["text"]):
            disagreements.append({"crn": crn, "what": "restrictions"})
        if (pre["status"], pre["expression"], pre["text"]) != (base[2]["status"], base[2]["expression"], base[2]["text"]):
            disagreements.append({"crn": crn, "what": "prerequisites"})
        if (co["status"], co["expression"], co["text"]) != (base[3]["status"], base[3]["expression"], base[3]["text"]):
            disagreements.append({"crn": crn, "what": "corequisites"})
    sides = {}
    for p in popups:
        side = "LECTURE" if (p.get("details") or {}).get("schedule_type", "") in ("Class", "Lecture") else "LAB"
        for link in links_from_popup(p):
            sides.setdefault(link, set()).add(side)
    return {
        "term": first["term_code"], "courseId": course_id, "inspectedCrns": [p["crn"] for p in popups], "requirementsFromCrn": first["crn"],
        "restrictions": base[1], "prerequisite": base[2], "corequisite": base[3], "links": [{"lab": a, "lecture": b, "bothSides": sides[(a, b)] == {"LECTURE", "LAB"}} for a, b in sorted(sides)],
        "classDetails": [{"crn": p["crn"], "creditHours": (p.get("details") or {}).get("credit_hours"),
                          "scheduleType": (p.get("details") or {}).get("schedule_type")} for p in popups],
        "disagreements": disagreements,
    }
