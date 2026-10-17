"""Build the isolated SQLite staging database for the five-program, non-CSE course research.
Rerunnable: it deletes and recreates staging.db from the JSON/CSV sources, so a rerun never duplicates rows.
Never touches PostgreSQL. Usage: python3 -I build_staging_db.py [db_path]"""
import csv, json, re, sqlite3, sys
from pathlib import Path

HERE = Path(__file__).resolve().parent

def read_json(p):
    with open(p, encoding="utf-8") as f:
        return json.load(f)

def read_csv(p):
    with open(p, encoding="utf-8", newline="") as f:
        return list(csv.DictReader(f))
TERM = "202710"
SCHEMA = """
PRAGMA foreign_keys = ON;
CREATE TABLE source_evidence (id INTEGER PRIMARY KEY, origin TEXT NOT NULL CHECK (origin IN ('public_catalog','authenticated_banner')),
  term_or_catalog_year TEXT NOT NULL, collected TEXT NOT NULL, locator TEXT NOT NULL, verification TEXT NOT NULL, level TEXT NOT NULL CHECK (level IN ('catalog','crn')),
  UNIQUE (origin, term_or_catalog_year, locator));
CREATE TABLE program (id TEXT PRIMARY KEY, name TEXT NOT NULL, college TEXT, department TEXT, degree_type TEXT NOT NULL CHECK (degree_type IN ('BS','BA','MINOR')),
  catalog_year TEXT NOT NULL, total_credits_text TEXT, restriction TEXT, unknowns_json TEXT, conflicts_json TEXT, evidence_id INTEGER NOT NULL REFERENCES source_evidence(id));
CREATE TABLE requirement_group (id INTEGER PRIMARY KEY, program_id TEXT NOT NULL REFERENCES program(id), parent_id INTEGER REFERENCES requirement_group(id),
  key TEXT NOT NULL, logic TEXT NOT NULL CHECK (logic IN ('ALL','ANY','CHOOSE_N','CREDITS')), n INTEGER, credits_text TEXT, rule_text TEXT, status TEXT NOT NULL, note TEXT);
CREATE TABLE program_requirement (id INTEGER PRIMARY KEY, program_id TEXT NOT NULL REFERENCES program(id), group_id INTEGER NOT NULL REFERENCES requirement_group(id), category TEXT NOT NULL,
  UNIQUE (program_id, group_id));
CREATE TABLE course (code TEXT PRIMARY KEY, subject TEXT NOT NULL, number TEXT NOT NULL, title TEXT, catalog_status TEXT NOT NULL, prerequisite_status TEXT NOT NULL,
  spring2027_status TEXT NOT NULL CHECK (spring2027_status IN ('COLLECTED_GRID','NOT_OFFERED_IN_SPRING_2027','NOT_COLLECTED')));
CREATE TABLE requirement_course_option (id INTEGER PRIMARY KEY, group_id INTEGER NOT NULL REFERENCES requirement_group(id), option_set INTEGER NOT NULL,
  course_code TEXT REFERENCES course(code), rule_text TEXT, credits INTEGER, status TEXT NOT NULL);
CREATE TABLE course_prerequisite (id INTEGER PRIMARY KEY, course_code TEXT NOT NULL REFERENCES course(code), expression_json TEXT, status TEXT NOT NULL, evidence_id INTEGER REFERENCES source_evidence(id));
CREATE TABLE course_equivalence (id INTEGER PRIMARY KEY, course_code TEXT NOT NULL REFERENCES course(code), equivalent_code TEXT NOT NULL, status TEXT NOT NULL);
CREATE TABLE course_credit_exclusion (id INTEGER PRIMARY KEY, course_code TEXT NOT NULL REFERENCES course(code), excluded_text TEXT NOT NULL, status TEXT NOT NULL, evidence_id INTEGER REFERENCES source_evidence(id),
  UNIQUE (course_code, excluded_text));
CREATE TABLE term (code TEXT PRIMARY KEY, name TEXT NOT NULL);
CREATE TABLE section (crn TEXT NOT NULL, term_code TEXT NOT NULL REFERENCES term(code), course_code TEXT NOT NULL REFERENCES course(code), section_no TEXT NOT NULL, title TEXT,
  schedule_type TEXT NOT NULL, grid_hours INTEGER NOT NULL, link_flag TEXT NOT NULL CHECK (link_flag IN ('LINKED','NONE_SHOWN')), detail_status TEXT NOT NULL, evidence_id INTEGER NOT NULL REFERENCES source_evidence(id),
  PRIMARY KEY (term_code, crn), UNIQUE (term_code, course_code, section_no));
CREATE TABLE meeting (id INTEGER PRIMARY KEY, term_code TEXT NOT NULL, crn TEXT NOT NULL, meeting_type TEXT NOT NULL, days TEXT NOT NULL, start_time TEXT NOT NULL, end_time TEXT NOT NULL,
  start_date TEXT NOT NULL, end_date TEXT NOT NULL, building TEXT, room TEXT, FOREIGN KEY (term_code, crn) REFERENCES section(term_code, crn),
  UNIQUE (term_code, crn, meeting_type, days, start_time, start_date));
CREATE TABLE instructor (id INTEGER PRIMARY KEY, display_name TEXT NOT NULL UNIQUE, is_placeholder INTEGER NOT NULL DEFAULT 0 CHECK (is_placeholder IN (0,1)));
CREATE TABLE section_instructor (term_code TEXT NOT NULL, crn TEXT NOT NULL, instructor_id INTEGER NOT NULL REFERENCES instructor(id), role TEXT, PRIMARY KEY (term_code, crn, instructor_id),
  FOREIGN KEY (term_code, crn) REFERENCES section(term_code, crn));
CREATE TABLE linked_section (term_code TEXT NOT NULL, lecture_crn TEXT NOT NULL, linked_crn TEXT NOT NULL, relation TEXT NOT NULL, status TEXT NOT NULL, evidence_id INTEGER REFERENCES source_evidence(id),
  PRIMARY KEY (term_code, lecture_crn, linked_crn), FOREIGN KEY (term_code, lecture_crn) REFERENCES section(term_code, crn), FOREIGN KEY (term_code, linked_crn) REFERENCES section(term_code, crn));
CREATE TABLE section_restriction (id INTEGER PRIMARY KEY, term_code TEXT NOT NULL, crn TEXT NOT NULL, restriction_text TEXT, status TEXT NOT NULL CHECK (status IN ('NOT_COLLECTED','NONE_DISPLAYED_NOT_PROOF','TEXT_RECORDED')),
  FOREIGN KEY (term_code, crn) REFERENCES section(term_code, crn));
CREATE TABLE unresolved (id INTEGER PRIMARY KEY, subject TEXT NOT NULL, detail TEXT NOT NULL);
"""

def code_parts(code):
    s, n = code.split(" ")
    return s, n

def build(db_path):
    db_path = Path(db_path)
    if db_path.exists():
        db_path.unlink()
    db = sqlite3.connect(db_path)
    db.executescript(SCHEMA)
    prog = read_json(HERE / "programs_2026_27.json")
    ev = prog["evidence"]
    cat_ev = db.execute("INSERT INTO source_evidence(origin,term_or_catalog_year,collected,locator,verification,level) VALUES('public_catalog',?,?,?,?, 'catalog')",
                        (prog["catalog_year"], ev["captured"], ev["sources"][0], ev["status"])).lastrowid
    banner_ev = db.execute("INSERT INTO source_evidence(origin,term_or_catalog_year,collected,locator,verification,level) VALUES('authenticated_banner',?,?,?,?, 'crn')",
                           (TERM, "2026-10-17", "Banner Browse Classes results grid (read-only), term 202710", "GRID_ONLY_DETAILS_NOT_INSPECTED")).lastrowid
    db.execute("INSERT INTO term VALUES (?,?)", (TERM, "2027 Spring Semester"))

    # sections first (courses referenced by sections), then requirement courses
    grid = read_csv(HERE / "banner" / "sections_grid.csv")
    no_sec = {r["course"]: r["result"] for r in read_csv(HERE / "banner" / "no_sections.csv")}
    titles = {}
    for r in grid:
        titles.setdefault(r["course"], r["title"])
    def ensure_course(code):
        if db.execute("SELECT 1 FROM course WHERE code=?", (code,)).fetchone():
            return
        s, n = code_parts(code)
        status = "COLLECTED_GRID" if code in titles else ("NOT_OFFERED_IN_SPRING_2027" if code in no_sec else "NOT_COLLECTED")
        db.execute("INSERT INTO course VALUES (?,?,?,?,?,?,?)", (code, s, n, titles.get(code), "PROGRAM_TABLE_ONLY", "NOT_COLLECTED", status))

    # requirement groups
    def add_group(pid, key, logic, items, parent=None, n=None, credits=None, rule=None, status="STATED", note=None):
        gid = db.execute("INSERT INTO requirement_group(program_id,parent_id,key,logic,n,credits_text,rule_text,status,note) VALUES(?,?,?,?,?,?,?,?,?)",
                         (pid, parent, key, logic, n, None if credits is None else str(credits), rule, status, note)).lastrowid
        setno = 0
        for it in items or []:
            if isinstance(it, str):
                ensure_course(it); setno += 1
                db.execute("INSERT INTO requirement_course_option(group_id,option_set,course_code,status) VALUES(?,?,?,'STATED')", (gid, setno, it))
            elif "any" in it:
                add_group(pid, f"{key}.any{setno + 1}", "ANY", it["any"], gid); setno += 1
            elif "all" in it:
                setno += 1
                for c in it["all"]:
                    ensure_course(c)
                    db.execute("INSERT INTO requirement_course_option(group_id,option_set,course_code,status) VALUES(?,?,?,'STATED')", (gid, setno, c))
            elif "credits" in it:
                setno += 1
                db.execute("INSERT INTO requirement_course_option(group_id,option_set,rule_text,credits,status) VALUES(?,?,?,?,?)",
                           (gid, setno, it["rule"], it["credits"], it.get("status", "STATED")))
        return gid
    # nested ANY groups need ANY-set semantics for {"all":[..]} members
    def add_any_children(pid, gid, items):
        pass

    for p in prog["programs"]:
        db.execute("INSERT INTO program VALUES (?,?,?,?,?,?,?,?,?,?,?)",
                   (p["id"], p["name"], p["college"], p["department"], p["degree_type"], prog["catalog_year"], p["total_credits"], p.get("restriction"),
                    json.dumps(p.get("unknown", [])), json.dumps(p.get("conflicts", [])), cat_ev))
        for g in p["groups"]:
            if g["logic"] == "CREDITS":
                gid = add_group(p["id"], g["key"], "CREDITS", [], credits=g.get("credits"), rule=g.get("rule"), status=g.get("status", "STATED"))
            else:
                gid = add_group(p["id"], g["key"], g["logic"], g.get("items"), n=g.get("n"), credits=g.get("credits"), note=g.get("note"))
            db.execute("INSERT INTO program_requirement(program_id,group_id,category) VALUES(?,?,?)", (p["id"], gid, g["category"]))
    for x in prog["course_credit_exclusions"]:
        ensure_course(x["course"])
        for ex in x["excludes"] + [x["also"]]:
            db.execute("INSERT INTO course_credit_exclusion(course_code,excluded_text,status,evidence_id) VALUES(?,?,?,?)", (x["course"], ex, x["status"], cat_ev))

    # sections and meetings
    def hhmm(t):
        return t
    for r in grid:
        ensure_course(r["course"])
        db.execute("INSERT INTO section VALUES (?,?,?,?,?,?,?,?,?,?)",
                   (r["crn"], r["term"], r["course"], r["section"], r["title"], r["type"], int(r["hours_grid"]), r["linked_flag"], r["status"], banner_ev))
        db.execute("INSERT INTO meeting(term_code,crn,meeting_type,days,start_time,end_time,start_date,end_date,building,room) VALUES (?,?,?,?,?,?,?,?,?,?)",
                   (r["term"], r["crn"], r["type"], r["days"], r["start"], r["end"], r["start_date"], r["end_date"], r["building"], r["room"]))
        db.execute("INSERT INTO section_restriction(term_code,crn,status) VALUES (?,?,'NOT_COLLECTED')", (r["term"], r["crn"]))
    for r in read_csv(HERE / "banner" / "meetings_extra.csv"):
        db.execute("INSERT INTO meeting(term_code,crn,meeting_type,days,start_time,end_time,start_date,end_date,building,room) VALUES (?,?,?,?,?,?,?,?,?,?)",
                   (r["term"], r["crn"], r["meeting_type"], r["day"], r["start"], r["end"], r["start_date"], r["end_date"], r["building"], r["room"]))
    # instructors: stored on the user's explicit instruction 2026-10-17; "Staff, Teaching" is Banner's placeholder, not a person
    ins_rows = read_csv(HERE / "banner" / "instructors.csv")
    for name in sorted({r["instructor"] for r in ins_rows}):
        db.execute("INSERT INTO instructor(display_name,is_placeholder) VALUES (?,?)", (name, 1 if name.startswith("Staff,") else 0))
    ids = {n: i for i, n in db.execute("SELECT id, display_name FROM instructor")}
    for r in ins_rows:
        db.execute("INSERT INTO section_instructor VALUES (?,?,?,?)", (r["term"], r["crn"], ids[r["instructor"]], r["role"]))
    # course-level prerequisite status: explicit NOT_COLLECTED for every course
    for (code,) in db.execute("SELECT code FROM course").fetchall():
        db.execute("INSERT INTO course_prerequisite(course_code,expression_json,status) VALUES (?,NULL,'NOT_COLLECTED')", (code,))
    # partial lecture->lab observation (CHM 030 lecture 12305, Linked Sections panel, first four options only)
    for lab in ("12413", "12417", "13233", "12414"):
        db.execute("INSERT INTO linked_section VALUES (?,?,?,?,?,?)", (TERM, "12305", lab, "LAB_OPTION", "OBSERVED_PARTIAL_LIST", banner_ev))
    for course, res in no_sec.items():
        db.execute("INSERT INTO unresolved(subject,detail) VALUES (?,?)", (course, res))
    db.execute("INSERT INTO unresolved(subject,detail) VALUES ('ALL_COURSES','Prerequisites, corequisites, descriptions, equivalences not yet collected from the catalog course pages')")
    db.execute("INSERT INTO unresolved(subject,detail) VALUES ('ALL_SECTIONS','Restrictions, cross-lists, linked sections not inspected except CHM 030 lecture 12305; instructors come from the results grid only')")
    # user-confirmed 2026-10-17: MKT 325 and ECO 325 are cross-listed (same title and meetings, different CRNs)
    for a, b in (("ECO 325", "MKT 325"), ("MKT 325", "ECO 325")):
        db.execute("INSERT INTO course_equivalence(course_code,equivalent_code,status) VALUES (?,?,'CROSS_LISTED_USER_CONFIRMED')", (a, b))
    db.commit()
    return db

if __name__ == "__main__":
    path = sys.argv[1] if len(sys.argv) > 1 else str(HERE / "staging.db")
    d = build(path)
    for t in ("source_evidence", "program", "requirement_group", "program_requirement", "requirement_course_option", "course", "term", "section", "meeting", "linked_section", "instructor", "section_instructor", "section_restriction", "course_prerequisite", "course_credit_exclusion", "unresolved"):
        print(t, d.execute(f"SELECT COUNT(*) FROM {t}").fetchone()[0])
