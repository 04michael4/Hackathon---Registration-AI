"""Builds the LOCAL demo fixture (all 42 researched Spring 2027 offerings) for the feature-flagged demo catalog.

    PGDATABASE=<scratch copy> PGHOST=... PGPORT=... PGUSER=... PGPASSFILE=... \
      python3 -I data/catalog-promotion/tools/build_demo_fixture.py --workbook TRACKER.xlsx --db --out ~/.registration-demo/spring-2027.json

    python3 -I .../build_demo_fixture.py --workbook TRACKER.xlsx --evidence-json demo-evidence.json --out FILE   # offline

Reads the exported research workbook and the collected Banner evidence (SELECTs only, in a forced read-only session, from a scratch
copy; the shared database is refused) and writes ONE JSON file. The file holds real CRNs, titles and meeting times, so it is written
OUTSIDE the repository and is never committed. The demo backend reads it with CATALOG_SOURCE=demo and DEMO_FIXTURE_PATH.

It writes no database, approves nothing, promotes nothing and claims no Registrar approval. Every offering is a *proposed scheduling
option*; eligibility facts it knows (Banner restrictions, catalog prerequisites, documented credit exclusions) are carried as structured
data so the demo can show them as warnings. Anything it cannot establish is left out, never guessed.
"""
import argparse
import json
import re
import subprocess
import sys
from collections import defaultdict
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import reconcile_tracker as rt  # noqa: E402

DAY_NAMES = {1: "MONDAY", 2: "TUESDAY", 3: "WEDNESDAY", 4: "THURSDAY", 5: "FRIDAY", 6: "SATURDAY", 7: "SUNDAY"}
TERM = "2027-SPRING"
SOURCE_NOTE = "Banner evidence (scratch copy) and the research workbook, 2026-10-16. Not Registrar-certified."

# Documented credit exclusions, each verified against the workbook text of the offering (see verified_exclusions).
# (offering, partner id or label, kind, short text)
DOCUMENTED_EXCLUSIONS = [
    ("CSE-003", "CSE-007", "CREDIT_AND_ENROLLMENT", "CSE 007 credit excludes CSE 003 (and CSE 004) credit, and CSE 003 cannot be taken after completing CSE 007"),
    ("CSE-007", "CSE-003", "CREDIT_AND_ENROLLMENT", "CSE 007 credit excludes CSE 003 (and CSE 004) credit"),
    ("CSE-140", "MATH 261", "NO_DOUBLE_CREDIT", "no credit for both CSE 140 and MATH 261"),
    ("CSE-241", "CSE 341 / ISE 224", "CREDIT_AND_ENROLLMENT", "CSE 241 cannot be taken with existing credit for CSE 341 or ISE 224"),
    ("CSE-280", "CSE 282", "NO_DOUBLE_CREDIT", "no credit for both CSE 280 and CSE 282"),
    ("CSE-313", "CSE-413", "NO_DOUBLE_CREDIT", "no credit for both CSE 313 and CSE 413"),
    ("CSE-413", "CSE-313", "NO_DOUBLE_CREDIT", "no credit for both CSE 313 and CSE 413"),
    ("CSE-320", "CSE-420", "NO_DOUBLE_CREDIT", "no credit for both CSE 320 and CSE 420"),
    ("CSE-420", "CSE-320", "NO_DOUBLE_CREDIT", "no credit for both CSE 320 and CSE 420"),
    ("CSE-343", "CSE-443", "NO_DOUBLE_CREDIT", "no credit for both CSE 343 and CSE 443"),
    ("CSE-443", "CSE-343", "NO_DOUBLE_CREDIT", "no credit for both CSE 343 and CSE 443"),
    ("CSE-347", "CSE-447", "NO_DOUBLE_CREDIT", "no credit for both CSE 347 and CSE 447"),
    ("CSE-447", "CSE-347", "NO_DOUBLE_CREDIT", "no credit for both CSE 347 and CSE 447"),
    ("CSE-127", "CSE-327", "SOURCES_CONFLICT", "an older catalog excluded dual credit for CSE 127 and CSE 327; the current catalog omits it (demo policy allows both; the credit award is unverified)"),
    ("CSE-327", "CSE-127", "SOURCES_CONFLICT", "an older catalog excluded dual credit for CSE 127 and CSE 327; the current catalog omits it (demo policy allows both; the credit award is unverified)"),
    ("CSE-398-052", "CSE 498 / DSCI 421 / ECE 303 / ECE 403", "NO_DOUBLE_CREDIT", "the term description restricts separate credit with DSCI 421, ECE 303 and ECE 403; whether CSE 398-052 and CSE 498 both earn credit is unconfirmed"),
    ("CSE-498", "CSE 398-052 / DSCI 421 / ECE 303 / ECE 403", "NO_DOUBLE_CREDIT", "the term description restricts separate credit with DSCI 421, ECE 303 and ECE 403; whether CSE 398-052 and CSE 498 both earn credit is unconfirmed"),
]

EVIDENCE_QUERY = """
SELECT json_build_object(
 'sections', COALESCE((SELECT json_agg(json_build_object('course_id', course_id, 'crn', crn, 'section_code', section_code, 'title', title,
     'schedule_type', schedule_type, 'grid_hours', grid_hours, 'details_credit_hours', details_credit_hours, 'linked_outcome', linked_outcome)
     ORDER BY course_id, crn) FROM catalog_promoted.evidence_section WHERE term_code = %(term)s), '[]'::json),
 'meetings', COALESCE((SELECT json_agg(json_build_object('crn', crn, 'days', days, 'start', start_time, 'end', end_time, 'location', location)
     ORDER BY crn, start_time) FROM (SELECT crn, array_agg(DISTINCT day_of_week ORDER BY day_of_week) AS days, to_char(start_time, 'HH24:MI') AS start_time,
       to_char(end_time, 'HH24:MI') AS end_time, location FROM catalog_promoted.evidence_meeting WHERE term_code = %(term)s
       GROUP BY crn, start_time, end_time, location) q), '[]'::json),
 'links', COALESCE((SELECT json_agg(json_build_object('course_id', course_id, 'lecture_crn', lecture_crn, 'linked_crn', linked_crn)
     ORDER BY lecture_crn, linked_crn) FROM catalog_promoted.evidence_link WHERE term_code = %(term)s), '[]'::json),
 'requirements', COALESCE((SELECT json_agg(json_build_object('course_id', course_id, 'crn', crn, 'kind', note_kind, 'text', note_text,
     'empty', catalog_promoted.is_empty_requirement(note_kind, note_text)) ORDER BY course_id, crn, note_kind)
     FROM catalog_promoted.evidence_requirement WHERE term_code = %(term)s), '[]'::json),
 'snapshot_sections', COALESCE((SELECT json_agg(json_build_object('course_id', course_id, 'crn', crn, 'component_type', component_type,
     'credits', credits, 'section_code', section_code, 'lecture_crn', lecture_crn) ORDER BY crn)
     FROM catalog_api.section_offering WHERE term_code = %(term)s), '[]'::json),
 'snapshot_meetings', COALESCE((SELECT json_agg(json_build_object('crn', crn, 'days', days, 'start', start_time, 'end', end_time, 'location', location)
     ORDER BY crn) FROM (SELECT crn, array_agg(day_of_week ORDER BY day_of_week) AS days, start_time, end_time, location
       FROM catalog_api.section_meeting WHERE term_code = %(term)s GROUP BY crn, start_time, end_time, location) q), '[]'::json),
 'snapshot_courses', COALESCE((SELECT json_agg(json_build_object('course_id', course_id, 'title', title, 'credits', credits))
     FROM catalog_api.course_offering WHERE term_code = %(term)s), '[]'::json)
)::text
"""


def load_evidence_from_db(term="202710", runner=subprocess.run):
    env = rt.psql_env()
    sql = EVIDENCE_QUERY.replace("%(term)s", "'" + re.sub(r"[^0-9A-Za-z]", "", term) + "'")
    proc = runner(["psql", "-X", "-q", "-A", "-t", "-v", "ON_ERROR_STOP=1", "-c", sql], env=env, capture_output=True, text=True, timeout=120)
    if proc.returncode != 0:
        raise rt.ReconcileError("evidence query failed: " + (proc.stderr or "")[-300:])
    return json.loads(proc.stdout)


def collapse(text, limit):
    text = re.sub(r"\s+", " ", text or "").strip()
    return text if len(text) <= limit else text[:limit - 1].rstrip() + "…"


def meetings_for(crn, ev_meetings):
    out = []
    for m in sorted((m for m in ev_meetings if m["crn"] == crn), key=lambda m: (m["start"], m["end"], m.get("location") or "")):
        out.append({"days": [DAY_NAMES[d] for d in sorted(m["days"])], "start": m["start"], "end": m["end"],
                    "location": (m.get("location") or "TBA")})
    return out


def whole_credits(value, what):
    try:
        f = float(value)
    except (TypeError, ValueError):
        raise rt.ReconcileError(f"{what}: credits {value!r} are not a number") from None
    if f != int(f) or f < 0:
        raise rt.ReconcileError(f"{what}: credits {value!r} are not a whole number")
    return int(f)


def prerequisite_note_for(offering, banner_prereq):
    """A short, human-readable prerequisite remark. Only when Banner lists prerequisites; the wording comes from the workbook's first clause."""
    if not banner_prereq:
        return None
    cell = re.sub(r"\s+", " ", rt.get(offering.row, "prereqs & restrictions to verify"))
    cell = re.split(r"CLAUDE\b|\bUSER_CONFIRMED\b", cell)[0]
    clause = re.split(r";|\.\s", cell)[0].strip(" .")
    if re.search(r"\b[A-Z]{2,5}\s*0?\d{3}\b", clause):
        return collapse(clause, 160)
    return "Banner lists prerequisites for this section; see the catalog."


def verified_exclusions(wb, offering):
    """The documented exclusions for this offering that the workbook itself mentions (partner course numbers must appear in its text)."""
    text = rt.text_for(wb, offering)
    out = []
    for oid, partner, kind, label in DOCUMENTED_EXCLUSIONS:
        if oid != offering.offering_id:
            continue
        digits = re.findall(r"\d{3}", partner)
        if digits and re.search(r"(?<!\d)0*%s(?!\d)" % digits[0], text):
            out.append({"with": partner, "kind": kind, "text": label})
    return out


def aliases_from(wb, course_id):
    out = []
    for r in wb["cross"]:
        parts = re.split(r"\s*(?:↔|<->|<=>)\s*", rt.get(r, "possible pairing"))
        if len(parts) != 2:
            continue
        left = re.match(r"\s*CSE\s*0*(\d{3})", parts[0])
        right = re.match(r"\s*([A-Z]{2,5})\s*0*(\d{3})\s*$", parts[1])
        if left and right and right.group(1) != "CSE" and f"CSE-{left.group(1)}" == course_id:
            out.append(f"{right.group(1)} {right.group(2)}")
    return out


def build_fixture(wb_source, data):
    """wb_source: a workbook path or the dict returned by rt.load_workbook's input sheets; data: the evidence JSON (see EVIDENCE_QUERY)."""
    wb = rt.load_workbook(wb_source)
    ev = rt.Evidence.from_json(data)
    result = rt.reconcile(wb_source, ev)
    previews = {p.offering_id: p for p in result["previews"]}
    offerings_in = rt.offerings_of(wb)
    by_course = defaultdict(list)
    for s in data["sections"]:
        by_course[s["course_id"]].append(s)
    links = defaultdict(set)
    for l in data.get("links", []):
        links[l["linked_crn"]].add(l["lecture_crn"])
    reqs = defaultdict(list)
    for q in data["requirements"]:
        reqs[q["course_id"]].append(q)
    snap_courses = {c["course_id"]: c for c in data.get("snapshot_courses", [])}
    snap_sections = defaultdict(list)
    for s in data.get("snapshot_sections", []):
        snap_sections[s["course_id"]].append(s)

    offerings = []
    for o in offerings_in:
        cid = o.course_id
        sections = []
        admin_type = "Lecture"
        if cid in snap_courses and cid in snap_sections and not by_course.get(cid):
            title = snap_courses[cid]["title"]
            for s in sorted(snap_sections[cid], key=lambda s: s["crn"]):
                comp = "LAB" if s["component_type"].upper() == "LAB" else "LECTURE"
                sections.append({"crn": s["crn"], "sectionCode": s.get("section_code") or "", "component": comp,
                                 "credits": whole_credits(s["credits"], f"{o.offering_id} {s['crn']}"),
                                 "lectureCrn": s.get("lecture_crn") if comp == "LAB" else None,
                                 "meetings": meetings_for(s["crn"], data.get("snapshot_meetings", []))})
            source = "SNAPSHOT"
        else:
            rows = [s for s in by_course.get(cid, []) if not o.crns or s["crn"] in o.crns]
            if not rows:
                raise rt.ReconcileError(f"{o.offering_id}: no Banner evidence for CRNs {o.crns}")
            title = rows[0]["title"]
            for s in sorted(rows, key=lambda s: s["crn"]):
                kind = s["schedule_type"]
                is_lab = kind.lower().startswith("lab")
                grid = whole_credits(s["grid_hours"], f"{o.offering_id} {s['crn']}")
                lecture_crn = None
                if is_lab:
                    parents = sorted(links.get(s["crn"], ()))
                    if len(parents) != 1:
                        raise rt.ReconcileError(f"{o.offering_id}: lab {s['crn']} has no single confirmed lecture ({parents})")
                    lecture_crn = parents[0]
                    if grid != 0:
                        raise rt.ReconcileError(f"{o.offering_id}: lab {s['crn']} shows {grid} grid credits; refusing to guess")
                else:
                    admin_type = kind
                    if grid != whole_credits(s["details_credit_hours"], f"{o.offering_id} {s['crn']}"):
                        raise rt.ReconcileError(f"{o.offering_id}: lecture {s['crn']} credit displays differ")
                sections.append({"crn": s["crn"], "sectionCode": s["section_code"], "component": "LAB" if is_lab else "LECTURE",
                                 "credits": grid, "lectureCrn": lecture_crn, "meetings": meetings_for(s["crn"], data["meetings"])})
            source = "BANNER_EVIDENCE"
        lectures = [s for s in sections if s["component"] == "LECTURE"]
        if not lectures:
            raise rt.ReconcileError(f"{o.offering_id}: no lecture section")
        crns = {s["crn"] for s in sections}
        rules, seen = [], set()
        for q in reqs.get(cid, []):
            if q["kind"] != "REGISTRATION_RESTRICTION" or q["empty"] or (q["crn"] not in crns):
                continue
            for r in rt.parse_restriction(q["text"]):
                key = (r.kind, r.polarity, r.values)
                if key not in seen:
                    seen.add(key)
                    rules.append({"kind": r.kind, "polarity": r.polarity, "values": list(r.values), "source": "BANNER"})
        banner_prereq = any(q["kind"] in ("PREREQUISITE_TEXT", "COREQUISITE_TEXT") and not q["empty"] and q["crn"] in crns for q in reqs.get(cid, []))
        prerequisite_note = prerequisite_note_for(o, banner_prereq) if cid != "CSE-007" else None
        pv = previews[o.offering_id]
        conds = set(pv.conditions)
        arranged = None
        if "ARRANGED_MEETING" in conds:
            arranged = "A mandatory additional team/instructor meeting has no published time. Time to be arranged."
        components = ("LABS_USER_SUPPLIED" if "LINKED_LABS_USER_SUPPLIED" in conds else "LABS_UNVERIFIED" if "LINKED_LABS_UNVERIFIED" in conds
                      else "STANDALONE_USER_SUPPLIED" if "LINKED_COMPONENTS_USER_SUPPLIED" in conds
                      else "COMPONENTS_UNVERIFIED" if "LINKED_COMPONENTS_UNVERIFIED" in conds else "NOT_APPLICABLE")
        if cid == "CSE-007":
            components = "LABS_USER_SUPPLIED"
        notes = []
        if o.offering_id == "CSE-217":
            notes.append("TEAM_POLICY: included as a conditional option by the demo reviewer's decision of 2026-10-16; Banner reports the section type as "
                         "Independent Study (kept as metadata). Registration eligibility is not guaranteed.")
        if cid == "CSE-398":
            notes.append("Special-topic number: each Spring 2027 topic is its own offering (separate CRN, title and requirements).")
        offerings.append({
            "offeringId": o.offering_id, "courseId": o.offering_id, "subject": cid.split("-")[0], "number": cid.split("-")[1],
            "topic": (re.sub(r"^\s*\d{3}\s*[·.\-:]\s*", "", o.topic).split("(")[0].strip() or None) if len(re.findall(r"\d{3}", o.offering_id)) > 1 else None,
            "title": title, "credits": lectures[0]["credits"], "administrativeType": admin_type, "dataSource": source,
            "tier": "READY" if cid == "CSE-007" else "CONDITIONAL",
            "sections": sections, "rules": rules, "prerequisiteNote": prerequisite_note,
            "components": components, "arrangedMeeting": arranged,
            "creditExclusions": verified_exclusions(wb, o), "aliases": aliases_from(wb, cid),
            "crossListing": "NOT_CROSS_LISTED_USER_CONFIRMED" if "CROSS_LISTING_UNVERIFIED" not in conds and cid != "CSE-007" else "UNVERIFIED",
            "notes": notes,
        })
    ids = [x["offeringId"] for x in offerings]
    if len(ids) != len(set(ids)):
        raise rt.ReconcileError("duplicate offering ids: " + str(sorted(i for i in ids if ids.count(i) > 1)))
    return {"term": TERM, "source": SOURCE_NOTE, "caution": "Proposed scheduling options only. Nothing here is Registrar-certified or guarantees registration.",
            "offerings": sorted(offerings, key=lambda x: x["offeringId"])}


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    ap.add_argument("--workbook", required=True)
    src = ap.add_mutually_exclusive_group(required=True)
    src.add_argument("--evidence-json")
    src.add_argument("--db", action="store_true")
    ap.add_argument("--term", default="202710")
    ap.add_argument("--out", required=True, help="where to write the fixture (outside the repository)")
    args = ap.parse_args(argv)
    try:
        out = Path(args.out).expanduser()
        rt.refuse_repo_output(out.parent)
        data = json.loads(Path(args.evidence_json).read_text(encoding="utf-8")) if args.evidence_json else load_evidence_from_db(args.term)
        fixture = build_fixture(args.workbook, data)
        out.parent.mkdir(parents=True, exist_ok=True)
        out.write_text(json.dumps(fixture, indent=1, ensure_ascii=False), encoding="utf-8")
        n_sec = sum(len(o["sections"]) for o in fixture["offerings"])
        print(f"wrote {len(fixture['offerings'])} offerings / {n_sec} sections to {out}")
    except rt.ReconcileError as exc:
        sys.exit("build_demo_fixture: " + str(exc))


if __name__ == "__main__":
    main()
