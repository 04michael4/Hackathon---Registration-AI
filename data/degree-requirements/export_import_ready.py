"""Export the staging DB to import-ready JSON for the team database (does not touch PostgreSQL).
Usage: python3 export_import_ready.py [db_path] [out_path]  -> export/non_cse_staging_202710.json"""
import json, sqlite3, sys
from pathlib import Path
HERE = Path(__file__).resolve().parent

def export(db_path, out_path):
    db = sqlite3.connect(db_path); db.row_factory = sqlite3.Row
    rows = lambda sql, *a: [dict(r) for r in db.execute(sql, a)]
    def group_tree(pid, parent):
        out = []
        for g in rows("SELECT * FROM requirement_group WHERE program_id=? AND parent_id IS ? ORDER BY id", pid, parent):
            g["options"] = rows("SELECT option_set, course_code, rule_text, credits, status FROM requirement_course_option WHERE group_id=? ORDER BY id", g["id"])
            g["children"] = group_tree(pid, g["id"])
            out.append(g)
        return out
    programs = []
    for p in rows("SELECT * FROM program ORDER BY id"):
        p["unknowns"] = json.loads(p.pop("unknowns_json") or "[]"); p["conflicts"] = json.loads(p.pop("conflicts_json") or "[]")
        p["requirements"] = []
        for pr in rows("SELECT group_id, category FROM program_requirement WHERE program_id=? ORDER BY id", p["id"]):
            g = next(x for x in group_tree(p["id"], None) if x["id"] == pr["group_id"])
            g["category"] = pr["category"]; p["requirements"].append(g)
        programs.append(p)
    sections = rows("SELECT * FROM section ORDER BY course_code, section_no")
    for s in sections:
        s["meetings"] = rows("SELECT meeting_type, days, start_time, end_time, start_date, end_date, building, room FROM meeting WHERE term_code=? AND crn=? ORDER BY start_date, start_time", s["term_code"], s["crn"])
        s["linked_crns"] = rows("SELECT linked_crn, relation, status FROM linked_section WHERE term_code=? AND lecture_crn=?", s["term_code"], s["crn"])
        s["restrictions"] = {"status": "NOT_COLLECTED"}
        s["instructors"] = rows("SELECT i.display_name AS name, si.role, i.is_placeholder FROM section_instructor si JOIN instructor i ON i.id=si.instructor_id "
                                "WHERE si.term_code=? AND si.crn=? ORDER BY CASE si.role WHEN 'Primary' THEN 0 ELSE 1 END, i.display_name", s["term_code"], s["crn"])
    has_ins = db.execute("SELECT COUNT(*) FROM section_instructor").fetchone()[0] > 0
    origins = {r[0] for r in db.execute("SELECT DISTINCT origin FROM source_evidence")}
    banner_status = ("SYNTHETIC_FIXTURE_ONLY" if "synthetic_fixture" in origins else
                     "INCLUDED_LOCAL_ONLY_DO_NOT_COMMIT" if "authenticated_banner" in origins else "NOT_INCLUDED_REDISTRIBUTION_NOT_VERIFIED")
    doc = {"format": "non_cse_staging/1", "term": "202710", "banner_data": {"status": banner_status}, "instructor_data": {"status": "INCLUDED_LOCAL_ONLY_DO_NOT_COMMIT" if has_ins else "NOT_INCLUDED_REDISTRIBUTION_NOT_VERIFIED"}, "note": "Staging data only; nothing here is university-approved or schedule-ready. Missing values are NOT_COLLECTED, never guessed.",
           "evidence": rows("SELECT * FROM source_evidence"), "programs": programs, "courses": rows("SELECT * FROM course ORDER BY code"),
           "course_prerequisites": rows("SELECT course_code, status FROM course_prerequisite ORDER BY course_code"),
           "course_credit_exclusions": rows("SELECT course_code, excluded_text, status FROM course_credit_exclusion"),
           "sections": sections, "unresolved": rows("SELECT subject, detail FROM unresolved ORDER BY id")}
    db.close()
    Path(out_path).parent.mkdir(parents=True, exist_ok=True)
    with open(out_path, "w", encoding="utf-8") as f:
        json.dump(doc, f, indent=1, sort_keys=True)
    return doc

if __name__ == "__main__":
    db = sys.argv[1] if len(sys.argv) > 1 else str(HERE / "staging.db")
    _c = sqlite3.connect(db)
    _has = (_c.execute("SELECT COUNT(*) FROM section_instructor").fetchone()[0] > 0
            or _c.execute("SELECT COUNT(*) FROM source_evidence WHERE origin='authenticated_banner'").fetchone()[0] > 0)
    _c.close()
    default = "local_only_banner_202710.json" if _has else "non_cse_staging_202710.json"  # local_only_* is git-ignored
    out = sys.argv[2] if len(sys.argv) > 2 else str(HERE / "export" / default)
    d = export(db, out)
    print("exported", len(d["programs"]), "programs,", len(d["courses"]), "courses,", len(d["sections"]), "sections ->", out)
