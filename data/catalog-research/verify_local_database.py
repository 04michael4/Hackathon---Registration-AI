"""Verifies a populated LOCAL database against the research export it was built from (stdlib only; needs psql; SELECTs only).

    PGHOST=127.0.0.1 PGPORT=<port> PGUSER=<owner or reader> PGDATABASE=<local database> \
      python3 -I data/catalog-research/verify_local_database.py --input ~/.registration-demo/spring-2027.json \
        [--save-fingerprint FILE | --compare-fingerprint FILE]

The expectations are read straight from the JSON export by code that does not share the importer's normalization, then compared field by
field with what the database returns. It also checks the serving boundary (nothing here is approved, promoted or served), the
cross-check against the Banner evidence, and the policy decision. --save-fingerprint / --compare-fingerprint prove the stored content is
identical after a restart. The session is forced read-only, and the shared database is refused.
"""
import argparse
import hashlib
import json
import os
import subprocess
import sys
from pathlib import Path

DAYS = {"MONDAY": 1, "TUESDAY": 2, "WEDNESDAY": 3, "THURSDAY": 4, "FRIDAY": 5, "SATURDAY": 6, "SUNDAY": 7}
TERM = "202710"
RESULTS = []


def sql(statement):
    env = {**os.environ, "PGOPTIONS": "-c default_transaction_read_only=on"}
    result = subprocess.run(["psql", "-X", "-q", "-At", "-v", "ON_ERROR_STOP=1", "-c", statement], capture_output=True, text=True, env=env, timeout=120)
    if result.returncode != 0:
        sys.exit("query failed: " + result.stderr.strip()[:400])
    return result.stdout.strip()


def sql_or_none(statement):
    """Like sql(), but returns None when this role may not read the object (a read-only role cannot read the approval tables)."""
    env = {**os.environ, "PGOPTIONS": "-c default_transaction_read_only=on"}
    result = subprocess.run(["psql", "-X", "-q", "-At", "-v", "ON_ERROR_STOP=1", "-c", statement], capture_output=True, text=True, env=env, timeout=120)
    if result.returncode != 0:
        if "permission denied" in result.stderr:
            return None
        sys.exit("query failed: " + result.stderr.strip()[:400])
    return result.stdout.strip()


def rows(statement):
    out = sql(f"SELECT COALESCE(json_agg(t), '[]'::json) FROM ({statement}) t")
    return json.loads(out)


def check(ok, label, detail=""):
    RESULTS.append(bool(ok))
    print(("  ok:   " if ok else "  FAIL: ") + label + (f"  [{detail}]" if detail else ""))


def main():
    ap = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    ap.add_argument("--input", required=True)
    ap.add_argument("--save-fingerprint")
    ap.add_argument("--compare-fingerprint")
    args = ap.parse_args()
    if os.environ.get("PGDATABASE") == "registration_cse007_local" or os.environ.get("PGPORT") == "55433":
        sys.exit("refusing the shared database")
    fx = json.loads(Path(args.input).read_text(encoding="utf-8"))
    offs = fx["offerings"]
    who = sql("SELECT current_user || ' on ' || current_database() || ' (' || version() ||')'").split(" (PostgreSQL")[0]
    print(f"== Verifying {who}\n")

    print("1. Offerings and sections")
    db_off = {r["offering_id"]: r for r in rows(f"SELECT * FROM catalog_research.offering WHERE term_code = '{TERM}'")}
    db_sec = {r["crn"]: r for r in rows(f"SELECT * FROM catalog_research.section WHERE term_code = '{TERM}'")}
    exp_sec = {s["crn"]: (o, s) for o in offs for s in o["sections"]}
    check(len(db_off) == len(offs) == 42, f"{len(db_off)} offerings stored (export has {len(offs)})")
    check(len(db_sec) == len(exp_sec) == 52, f"{len(db_sec)} sections stored (export has {len(exp_sec)})")
    check(set(db_off) == {o["offeringId"] for o in offs} and set(db_sec) == set(exp_sec), "the offering ids and the CRNs are exactly the export's")
    bad = []
    for o in offs:
        d = db_off[o["offeringId"]]
        if (d["title"], d["credits"], d["administrative_type"], d["data_source"], d["topic"]) != (o["title"], o["credits"], o["administrativeType"], o["dataSource"], o["topic"]):
            bad.append(o["offeringId"])
    check(not bad, "title, credits, administrative type, data source and topic match for every offering", ",".join(bad))
    bad = []
    for crn, (o, s) in exp_sec.items():
        d = db_sec[crn]
        if (d["offering_id"], d["section_code"], d["component_type"], d["credits"], d["lecture_crn"]) != (o["offeringId"], s["sectionCode"], s["component"], s["credits"], s["lectureCrn"]):
            bad.append(crn)
    check(not bad, "offering, section code, component, credits and lecture link match for every CRN", ",".join(bad))

    print("\n2. Meeting times")
    db_meet = {}
    for r in rows(f"SELECT crn, day_of_week, to_char(start_time,'HH24:MI') AS s, to_char(end_time,'HH24:MI') AS e, location FROM catalog_research.section_meeting WHERE term_code = '{TERM}'"):
        db_meet.setdefault(r["crn"], set()).add((r["day_of_week"], r["s"], r["e"], r["location"]))
    bad = []
    for crn, (o, s) in exp_sec.items():
        want = {(DAYS[d], m["start"], m["end"], m.get("location") or "TBA") for m in s["meetings"] for d in m["days"]}
        if db_meet.get(crn, set()) != want:
            bad.append(crn)
    check(not bad, f"every section's days, start, end and location match ({sum(len(v) for v in db_meet.values())} meeting rows)", ",".join(bad))

    print("\n3. Lecture/lab structure and credits")
    labs = [o for o in offs if any(s["component"] == "LAB" for s in o["sections"])]
    check(len(labs) == 6 and sum(1 for o in offs for s in o["sections"] if s["component"] == "LAB") == 9, "6 lab courses (CSE 007, 017, 109, 202, 216, 241) with 9 lab sections")
    bad = []
    for o in labs:
        d = db_off[o["offeringId"]]
        lec = [s for s in o["sections"] if s["component"] == "LECTURE"]
        ok = (d["components_status"] == "LABS_USER_SUPPLIED" and d["required_lab_count"] == 1 and len(lec) == 1
              and all(db_sec[s["crn"]]["credits"] == 0 and db_sec[s["crn"]]["lecture_crn"] == lec[0]["crn"] for s in o["sections"] if s["component"] == "LAB")
              and db_sec[lec[0]["crn"]]["credits"] == d["credits"])
        if not ok:
            bad.append(o["offeringId"])
    check(not bad, "each lab course: exactly one required lab (user-supplied), labs link to the lecture and add 0 credits, the lecture carries the total", ",".join(bad))
    check(sql("SELECT count(*) FROM catalog_research.offering WHERE components_status = 'STANDALONE_USER_SUPPLIED'") == "1"
          and sql("SELECT count(*) FROM catalog_research.offering WHERE components_status = 'COMPONENTS_UNVERIFIED' AND required_lab_count IS NOT NULL") == "0",
          "components that nobody confirmed carry no lab count (stored as unverified, not guessed)")

    print("\n4. Requirements, restrictions, exclusions, aliases")
    db_rules = {}
    for r in rows(f"SELECT offering_id, rule_kind, polarity, rule_values, parse_status, expression, source_text, evidence_level FROM catalog_research.eligibility_rule WHERE term_code = '{TERM}'"):
        db_rules.setdefault(r["offering_id"], []).append(r)
    bad = []
    for o in offs:
        stored = sorted((r["rule_kind"], r["polarity"], tuple(r["rule_values"])) for r in db_rules.get(o["offeringId"], []) if r["rule_kind"] not in ("PREREQUISITE", "COREQUISITE"))
        want = sorted((r["kind"], r["polarity"], tuple(r["values"])) for r in o["rules"])
        has_pre = any(r["rule_kind"] == "PREREQUISITE" for r in db_rules.get(o["offeringId"], []))
        if stored != want or has_pre != bool(o["prerequisiteNote"]):
            bad.append(o["offeringId"])
    check(not bad, "every restriction rule (class standing, level, college, major, approval) and every prerequisite statement matches the export", ",".join(bad))
    check(all(r["source_text"].strip() for rs in db_rules.values() for r in rs), "every stored rule carries its source text")
    status = {}
    for r in rows(f"SELECT requirement_type, status, count(*)::int AS n FROM catalog_research.requirement_status WHERE term_code = '{TERM}' GROUP BY 1, 2 ORDER BY 1, 2"):
        status.setdefault(r["requirement_type"], {})[r["status"]] = r["n"]
    check(sum(sum(v.values()) for v in status.values()) == 126, "3 requirement statuses for each of the 42 offerings (prerequisite, corequisite, enrollment restriction)")
    for kind, counts in status.items():
        print(f"        {kind:24} " + ", ".join(f"{k}={v}" for k, v in sorted(counts.items())))
    check("TEXT_ONLY" in status.get("PREREQUISITE", {}) and "NONE_LISTED" in status.get("PREREQUISITE", {}) and "NONE_LISTED" in status.get("COREQUISITE", {})
          and sql("SELECT count(*) FROM catalog_research.requirement_status WHERE status = 'NONE_LISTED' AND evidence_level <> 'USER_CLARIFICATION'") == "0",
          "what could not be turned into a rule stays TEXT_ONLY; what Banner lists nothing for is NONE_LISTED (the reviewer's decision, labelled user-supplied, not Registrar-certified)")
    check(sql("SELECT count(*) FROM catalog_research.prerequisite_banner_check") == "0",
          "every structured prerequisite names exactly the courses Banner's own prerequisite text names")
    hand = json.loads((Path(__file__).resolve().parent / "prerequisite_expressions.json").read_text(encoding="utf-8"))["expressions"]
    stored = {r["offering_id"]: r for r in rows("SELECT offering_id, parse_status, expression FROM catalog_research.eligibility_rule WHERE rule_kind = 'PREREQUISITE'")}
    check(all(stored[h["offeringId"]]["parse_status"] == "STRUCTURED" and stored[h["offeringId"]]["expression"] == h["expression"] for h in hand),
          f"the {len(hand)} hand-structured prerequisites are stored exactly as decided")
    want_ex = sorted((o["offeringId"], x["with"], x["kind"]) for o in offs for x in o["creditExclusions"])
    got_ex = sorted((r["offering_id"], r["excluded_course_id"] or r["excluded_text"], r["exclusion_kind"]) for r in rows(f"SELECT offering_id, excluded_course_id, excluded_text, exclusion_kind FROM catalog_research.credit_exclusion WHERE term_code = '{TERM}'"))
    check(want_ex == got_ex, f"{len(got_ex)} credit-exclusion statements match the export")
    want_al = sorted((o["offeringId"], a) for o in offs for a in o["aliases"])
    got_al = sorted((r["offering_id"], r["alias_subject"] + " " + r["alias_number"]) for r in rows(f"SELECT offering_id, alias_subject, alias_number FROM catalog_research.course_alias WHERE term_code = '{TERM}'"))
    check(want_al == got_al, f"{len(got_al)} course aliases match the export (no CRN, status unverified)")
    check(sql("SELECT count(*) FROM catalog_research.course_alias WHERE alias_status <> 'CATALOG_ALIAS_UNVERIFIED'") == "0", "no alias is presented as a verified cross-listing")

    print("\n5. Special cases")
    topics = [o for o in offs if o.get("topic")]
    t_crns = {o["offeringId"]: [x["crn"] for x in o["sections"]] for o in topics}
    t_db = rows(f"SELECT offering_id, course_id, title, identity_status FROM catalog_research.offering WHERE topic IS NOT NULL AND term_code = '{TERM}' ORDER BY offering_id")
    check(len(topics) == 2 and len({t["course_id"] for t in t_db}) == 1 and len({t["title"] for t in t_db}) == 2 and all(t["identity_status"] == "TOPIC_IDENTITY_UNDECIDED" for t in t_db)
          and all(db_sec[c]["offering_id"] == oid for oid, cs in t_crns.items() for c in cs) and len({c for cs in t_crns.values() for c in cs}) == 2,
          "the two CSE 398 topics are separate offerings (one course number, two titles, two CRNs, each CRN attached to its own topic)")
    want_arr = sorted(s["crn"] for o in offs for s in o["sections"] if o.get("arrangedMeeting"))
    arranged = rows(f"SELECT crn, description FROM catalog_research.section_arranged_meeting WHERE term_code = '{TERM}' ORDER BY crn")
    check([a["crn"] for a in arranged] == want_arr and len(arranged) == 2 and {db_sec[a["crn"]]["offering_id"] for a in arranged} == {"CSE-367", "CSE-467"},
          "CSE 367 and CSE 467 each keep an unscheduled mandatory meeting as a warning, not a timetable slot")
    o217 = next(o for o in offs if o["offeringId"] == "CSE-217")
    c217 = o217["sections"][0]["crn"]
    s217 = rows("SELECT o.administrative_type, t.tier FROM catalog_research.offering o JOIN catalog_research.offering_tier t USING (term_code, offering_id) WHERE o.offering_id = 'CSE-217'")
    dec = rows("SELECT d.crn, d.decision, d.evidence_level, d.decided_by_role, t.registrar_certified, t.guarantees_registration, d.observed_value FROM catalog_research.active_tier_policy_decision d JOIN catalog_research.tier_policy_decision t USING (decision_id)")
    check(len(s217) == 1 and s217[0]["administrative_type"] == "Independent Study" == o217["administrativeType"] and s217[0]["tier"] == "CONDITIONAL" and len(dec) == 1
          and dec[0]["crn"] == c217 and dec[0]["evidence_level"] == "TEAM_POLICY" and not dec[0]["registrar_certified"] and not dec[0]["guarantees_registration"],
          "CSE 217 keeps its reported Independent Study type and is CONDITIONAL only through a TEAM_POLICY decision that certifies nothing")
    check(sql("SELECT count(*) FROM catalog_research.offering_status WHERE offering_id = 'CSE-217' AND (serving_status <> 'STORED_NOT_SERVED' OR registration_eligibility_guaranteed)") == "0"
          and sql("SELECT count(*) FROM catalog_research.eligibility_rule WHERE offering_id = 'CSE-217' AND rule_kind = 'CLASS_STANDING' AND polarity = 'CANNOT' AND rule_values = ARRAY['FR']") == "1",
          "the CSE 217 decision grants no eligibility: it is not served, guarantees nothing, and the Banner first-year restriction is still stored")

    print("\n6. Classification, evidence and the serving boundary")
    tiers = {r["tier"]: r["n"] for r in rows("SELECT tier, count(*)::int AS n FROM catalog_research.offering_tier GROUP BY 1")}
    check(tiers == {"READY": 1, "CONDITIONAL": 39, "BLOCKED": 2}, f"research tiers: {tiers}")
    check(sql("SELECT count(*) FROM catalog_research.evidence_mismatch") == "0", "no stored row disagrees with the collected Banner evidence or the verified snapshot")
    served = sql("SELECT string_agg(course_id, ',' ORDER BY course_id) FROM catalog_live.course_offering")
    check(served == "CSE-007", "catalog_live (what the backend reads) serves only the verified snapshot course", served)
    st = {(r["serving_status"], r["promotion_state"]): r["n"] for r in rows("SELECT serving_status, promotion_state, count(*)::int AS n FROM catalog_research.offering_status GROUP BY 1, 2")}
    check(st == {("SERVED", "VERIFIED_CATALOG"): 1, ("STORED_NOT_SERVED", "BLOCKED"): 41}, f"41 offerings are stored but not approved for serving; their promotion state is BLOCKED")
    approvals, promotions = sql_or_none("SELECT count(*) FROM catalog_promoted.approval"), sql_or_none("SELECT count(*) FROM catalog_promoted.promotion")
    if approvals is None or promotions is None:
        print("  skip: no approval and no promotion exists (this role cannot read those tables; the readiness view below shows the effect)")
    else:
        check(approvals == "0" and promotions == "0", "no approval and no promotion exists")
    not_blocked, candidates = sql_or_none("SELECT count(*) FROM catalog_promoted.promotion_readiness WHERE state <> 'BLOCKED'"), sql_or_none("SELECT count(*) FROM catalog_promoted.promotion_readiness")
    if not_blocked is None:
        print("  skip: promotion_readiness (this role cannot read it; offering_status above shows each course's state)")
    else:
        check(not_blocked == "0" and candidates == "40", "all 40 Banner courses are BLOCKED in promotion_readiness (none is ready to promote or promoted)")
    caps = sql_or_none("SELECT string_agg(name || ':' || enabled, ',' ORDER BY name) FROM catalog_promoted.capability")
    if caps is None:
        print("  skip: enforcement capabilities (this role cannot read catalog_promoted.capability)")
    else:
        check(caps == "COREQUISITES_ENFORCED:false,CREDIT_EXCLUSION_ENFORCED:false,PREREQUISITES_ENFORCED:false,RESTRICTIONS_ENFORCED:false", "every enforcement capability is still off")
    check(sql("SELECT count(*) FROM catalog_research.offering_status WHERE registration_eligibility_guaranteed") == "0", "registration eligibility is guaranteed for no offering")
    check(sql("SELECT count(*) FROM catalog_research.candidate_course_offering WHERE schedulable") == "1", "only the served snapshot course is schedulable in the catalog-shaped candidate views")
    snap = sql("SELECT count(*) FROM (SELECT term_code, course_id, crn, section_code, credits, component_type, lecture_crn, status FROM catalog_research.candidate_section_offering WHERE course_id = 'CSE-007' "
               "EXCEPT SELECT term_code, course_id, crn, section_code, credits, component_type, lecture_crn, status FROM catalog_api.section_offering) x")
    check(snap == "0" and sql("SELECT count(*) FROM catalog_api.section_offering") == "3", "the snapshot course's candidate rows are identical to catalog_api's 3 section rows")

    print("\n7. Fingerprint of the stored content")
    drop = {"offering": ["run_id"], "eligibility_rule": ["rule_id"], "credit_exclusion": ["exclusion_id"],
            "tier_policy_decision": ["decision_id", "supersedes_decision_id", "recorded_at", "recorded_by"]}
    parts = []
    for table in ("offering", "section", "section_meeting", "section_arranged_meeting", "eligibility_rule", "requirement_status", "credit_exclusion",
                  "course_alias", "tier_policy_decision"):
        removed = "ARRAY[" + ",".join(f"'{c}'" for c in drop.get(table, [])) + "]::text[]" if table in drop else "ARRAY[]::text[]"
        text = sql(f"SELECT COALESCE(string_agg(j, E'\\n' ORDER BY j), '') FROM (SELECT (to_jsonb(t) - {removed})::text AS j FROM catalog_research.{table} t) q")
        parts.append(table + ":" + hashlib.sha256(text.encode()).hexdigest())
    fingerprint = hashlib.sha256("\n".join(parts).encode()).hexdigest()
    print(f"        content fingerprint {fingerprint[:32]}…")
    if args.save_fingerprint:
        Path(args.save_fingerprint).write_text(fingerprint + "\n", encoding="utf-8")
        print("        saved")
    if args.compare_fingerprint:
        check(Path(args.compare_fingerprint).read_text(encoding="utf-8").strip() == fingerprint, "the stored content is identical to the fingerprint saved before the restart")

    failed = RESULTS.count(False)
    print(f"\n{len(RESULTS) - failed} checks passed, {failed} failed")
    sys.exit(1 if failed else 0)


if __name__ == "__main__":
    main()
