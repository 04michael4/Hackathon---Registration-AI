"""Imports the researched term catalog into catalog_research (stdlib only; needs `psql`).

    PGHOST=127.0.0.1 PGPORT=<port> PGUSER=<owner> PGDATABASE=<local database> \
      python3 -I data/catalog-research/import_research_catalog.py --input ~/.registration-demo/spring-2027.json \
        [--decisions data/catalog-research/policy_decisions.json] [--term-code 202710] [--dry-run]

Input: the JSON written by data/catalog-promotion/tools/build_demo_fixture.py (workbook + collected Banner evidence). It holds real CRNs,
titles and meeting times, so it lives OUTSIDE the repository and is never committed.

What it does, in ONE transaction: normalizes the input (strict, no guessing), calls catalog_research.import_term (which replaces that
term's stored catalog and aborts if the result disagrees with the collected Banner evidence or the verified snapshot), then records the
policy decisions in --decisions through catalog_research.record_tier_policy_decision. With --dry-run it rolls back.

It writes only catalog_research. It refuses the shared database (registration_cse007_local / port 55433), approves nothing, promotes
nothing and does not touch catalog_api, catalog_promoted, catalog_live, banner_evidence or cse007_snapshot.
"""
import argparse
import hashlib
import json
import os
import re
import subprocess
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
DAYS = {"MONDAY": 1, "TUESDAY": 2, "WEDNESDAY": 3, "THURSDAY": 4, "FRIDAY": 5, "SATURDAY": 6, "SUNDAY": 7}
# The API term and the Banner term code are mapped explicitly here; no formula derives one from the other.
TERM_MAP = {"2027-SPRING": "202710"}
COMPONENTS = {"LABS_USER_SUPPLIED": 1, "STANDALONE_USER_SUPPLIED": 0, "COMPONENTS_UNVERIFIED": None}
COURSE_ID = re.compile(r"^[A-Z]{2,4}-\d{3}$")
SHARED_DATABASES = {"registration_cse007_local"}
SHARED_PORTS = {"55433"}


class InputError(Exception):
    pass


# ----------------------------------------------------------------------------------------------------------------------
# Prerequisite text -> expression. Strict: anything that is not exactly "course refs joined by AND/OR with parentheses" stays text.
# ----------------------------------------------------------------------------------------------------------------------

def _clean_prereq(text):
    t = text.strip()
    t = re.sub(r"^(official catalog|catalog)\s*:\s*", "", t, flags=re.I)
    t = re.sub(r"\s*\((?:course-level )?catalog(?: and banner)?\)\s*$", "", t, flags=re.I)
    t = re.sub(r"\s+prerequisite$", "", t, flags=re.I)
    return t.strip()


def parse_prerequisite(text):
    """Returns the expression ({"op":"AND"|"OR","args":[...]} or {"course":"CSE-017"}), or None when the text is not an exact expression.
    An unparenthesized mix of AND and OR is ambiguous and is refused. A bare number with no subject (MATH021 OR 031) is refused."""
    if not text:
        return None
    t = _clean_prereq(text)
    tokens = re.findall(r"\(|\)|\bAND\b|\bOR\b|\b[A-Z]{2,4}\s?\d{3}\b", t)
    if not tokens or re.sub(r"\s+", "", " ".join(tokens)) != re.sub(r"\s+", "", t):
        return None
    pos = 0

    def peek():
        return tokens[pos] if pos < len(tokens) else None

    def take():
        nonlocal pos
        pos += 1
        return tokens[pos - 1]

    def group():
        nonlocal pos
        items, ops = [atom()], set()
        while peek() in ("AND", "OR"):
            ops.add(take())
            items.append(atom())
        if len(ops) > 1:
            raise ValueError("ambiguous mix of AND and OR")
        if not ops:
            return items[0]
        args = []
        for item in items:  # flatten a nested group of the same operator
            args.extend(item["args"] if item.get("op") == next(iter(ops)) else [item])
        return {"op": next(iter(ops)), "args": args}

    def atom():
        tok = take() if peek() is not None else None
        if tok is None:
            raise ValueError("unexpected end")
        if tok == "(":
            inner = group()
            if peek() != ")":
                raise ValueError("missing )")
            take()
            return inner
        if tok in (")", "AND", "OR"):
            raise ValueError("unexpected " + tok)
        return {"course": re.sub(r"\s+", "", tok)[:-3] + "-" + tok[-3:]}

    try:
        expression = group()
        if pos != len(tokens):
            return None
    except ValueError:
        return None
    return expression


# ----------------------------------------------------------------------------------------------------------------------
# Normalization
# ----------------------------------------------------------------------------------------------------------------------

def normalize(fixture, term_code):
    if not isinstance(fixture, dict) or fixture.get("term") not in TERM_MAP:
        raise InputError(f"input term {fixture.get('term') if isinstance(fixture, dict) else None!r} is not one of {sorted(TERM_MAP)}")
    if TERM_MAP[fixture["term"]] != term_code:
        raise InputError(f"input term {fixture['term']} maps to {TERM_MAP[fixture['term']]}, not {term_code}")
    offerings, seen_ids, seen_crns = [], set(), set()
    for o in fixture.get("offerings") or []:
        oid = o.get("offeringId")
        if not oid or oid in seen_ids:
            raise InputError(f"duplicate or missing offeringId {oid!r}")
        seen_ids.add(oid)
        components = o.get("components")
        if components not in COMPONENTS:
            raise InputError(f"{oid}: unknown components status {components!r}")
        sections = []
        for s in o.get("sections") or []:
            if s["crn"] in seen_crns:
                raise InputError(f"{oid}: CRN {s['crn']} appears twice")
            seen_crns.add(s["crn"])
            meetings = []
            for m in s.get("meetings") or []:
                for d in m["days"]:
                    if d not in DAYS:
                        raise InputError(f"{oid}/{s['crn']}: unknown day {d!r}")
                    meetings.append({"day": DAYS[d], "start": m["start"], "end": m["end"], "location": m.get("location") or "TBA"})
            sections.append({"crn": s["crn"], "sectionCode": s["sectionCode"], "component": s["component"], "credits": s["credits"],
                             "lectureCrn": s.get("lectureCrn"),
                             "scheduleType": "Lab" if s["component"] == "LAB" else o["administrativeType"],
                             "meetings": meetings, "arrangedMeeting": None})
        lectures = [s for s in sections if s["component"] == "LECTURE"]
        labs = [s for s in sections if s["component"] == "LAB"]
        if not lectures:
            raise InputError(f"{oid}: no lecture section")
        if (components == "LABS_USER_SUPPLIED") != bool(labs):
            raise InputError(f"{oid}: lab sections and the user-supplied lab status disagree")
        if labs and len(lectures) != 1:
            raise InputError(f"{oid}: labs need exactly one lecture to link to")
        if o.get("arrangedMeeting"):
            if len(lectures) != 1:
                raise InputError(f"{oid}: an arranged meeting needs exactly one lecture section")
            lectures[0]["arrangedMeeting"] = o["arrangedMeeting"]
        exclusions = []
        for x in o.get("creditExclusions") or []:
            partner = x["with"]
            exclusions.append({"withCourseId": partner if COURSE_ID.match(partner) else None,
                               "withText": partner.replace("-", " ") if COURSE_ID.match(partner) else partner, "kind": x["kind"], "text": x["text"]})
        aliases = []
        for a in o.get("aliases") or []:
            m = re.fullmatch(r"([A-Z]{2,4}) (\d{3})", a)
            if not m:
                raise InputError(f"{oid}: alias {a!r} is not 'SUBJ 123'")
            aliases.append({"subject": m.group(1), "number": m.group(2)})
        note = o.get("prerequisiteNote")
        offerings.append({
            "offeringId": oid, "subject": o["subject"], "number": o["number"], "topic": o.get("topic"), "title": o["title"], "credits": o["credits"],
            "administrativeType": o["administrativeType"], "dataSource": o["dataSource"], "componentsStatus": components,
            "requiredLabCount": COMPONENTS[components], "crossListingStatus": o["crossListing"],
            "identityStatus": "SINGLE" if o.get("topic") is None else "TOPIC_IDENTITY_UNDECIDED",
            "sections": sections, "rules": [{"kind": r["kind"], "polarity": r["polarity"], "values": r["values"]} for r in o.get("rules") or []],
            "prerequisiteText": note, "prerequisiteExpression": parse_prerequisite(note) if note else None,
            "creditExclusions": exclusions, "aliases": aliases})
    if not offerings:
        raise InputError("the input has no offerings")
    return {"termCode": term_code, "offerings": offerings}


# ----------------------------------------------------------------------------------------------------------------------
# Database
# ----------------------------------------------------------------------------------------------------------------------

def sql_literal(value):
    """A dollar-quoted literal whose tag does not occur in the text."""
    n = 0
    while f"$q{n}$" in value:
        n += 1
    return f"$q{n}${value}$q{n}$"


def refuse_shared_database():
    if os.environ.get("PGDATABASE", "") in SHARED_DATABASES or os.environ.get("PGPORT", "") in SHARED_PORTS:
        raise InputError("refusing: this looks like the shared database; import into an isolated local database")


def run_psql(sql):
    result = subprocess.run(["psql", "-X", "-q", "-v", "ON_ERROR_STOP=1", "-At"], input=sql, capture_output=True, text=True, timeout=300)
    return result.returncode, (result.stdout + result.stderr).strip()


def load_prerequisite_expressions(path):
    """Hand-structured prerequisite expressions (see prerequisite_expressions.json): {offeringId: expression}."""
    out = {}
    for entry in json.loads(Path(path).read_text(encoding="utf-8"))["expressions"]:
        if entry["offeringId"] in out:
            raise InputError(f"two hand-structured expressions for {entry['offeringId']}")
        check_expression(entry["expression"], entry["offeringId"])
        out[entry["offeringId"]] = entry["expression"]
    return out


def check_expression(node, where):
    if not isinstance(node, dict):
        raise InputError(f"{where}: an expression node must be an object")
    if "course" in node:
        if not COURSE_ID.match(node["course"]) or set(node) - {"course", "concurrent"} or not isinstance(node.get("concurrent", False), bool):
            raise InputError(f"{where}: bad course node {node!r}")
    elif node.get("op") in ("AND", "OR") and isinstance(node.get("args"), list) and len(node["args"]) >= 2 and set(node) == {"op", "args"}:
        for arg in node["args"]:
            check_expression(arg, where)
    else:
        raise InputError(f"{where}: bad expression node {node!r}")


def apply_prerequisite_expressions(payload, expressions):
    """Attach a hand-structured expression only to a prerequisite the parser left as text; never override a parsed one, never invent one."""
    by_id = {o["offeringId"]: o for o in payload["offerings"]}
    for offering_id, expression in expressions.items():
        o = by_id.get(offering_id)
        if o is None:
            raise InputError(f"hand-structured expression for {offering_id}, which is not in the input")
        if not o["prerequisiteText"]:
            raise InputError(f"{offering_id} has no prerequisite statement to structure")
        if o["prerequisiteExpression"] is not None and o["prerequisiteExpression"] != expression:
            raise InputError(f"{offering_id}: the parser already produced a different expression")
        o["prerequisiteExpression"] = expression


def load_decisions(path, term_code):
    data = json.loads(Path(path).read_text(encoding="utf-8"))
    out = []
    for d in data["decisions"]:
        if d["termCode"] != term_code:
            continue
        out.append(d)
    return out


def build_sql(payload, label, sha, decisions, dry_run):
    parts = ["BEGIN;", f"SELECT 'run=' || catalog_research.import_term({sql_literal(json.dumps(payload, sort_keys=True))}::jsonb, {sql_literal(label)}, '{sha}');"]
    for d in decisions:
        parts.append("SELECT 'decision=' || catalog_research.record_tier_policy_decision({}, {}, {}, {}, {}, {}, {}::date, {});".format(
            *(sql_literal(d[k]) for k in ("termCode", "crn", "decisionType", "decision", "rationale", "limitations", "decidedOn", "sourceRef"))))
    parts.append("ROLLBACK;" if dry_run else "COMMIT;")
    return "\n".join(parts)


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    ap.add_argument("--input", required=True, help="research export JSON (outside the repository)")
    ap.add_argument("--decisions", default=str(HERE / "policy_decisions.json"))
    ap.add_argument("--prerequisite-expressions", default=str(HERE / "prerequisite_expressions.json"))
    ap.add_argument("--term-code", default="202710")
    ap.add_argument("--dry-run", action="store_true")
    args = ap.parse_args(argv)
    try:
        refuse_shared_database()
        raw = Path(args.input).read_bytes()
        payload = normalize(json.loads(raw), args.term_code)
        apply_prerequisite_expressions(payload, load_prerequisite_expressions(args.prerequisite_expressions))
        decisions = load_decisions(args.decisions, args.term_code)
    except (InputError, KeyError, ValueError, OSError) as exc:
        sys.exit("import refused: " + str(exc))
    sha = hashlib.sha256(json.dumps(payload, sort_keys=True).encode()).hexdigest()
    label = f"{Path(args.input).name} (term {args.term_code})"
    code, out = run_psql(build_sql(payload, label, sha, decisions, args.dry_run))
    if code != 0:
        sys.exit("import failed, nothing stored:\n" + out[:1500])
    offerings, sections = len(payload["offerings"]), sum(len(o["sections"]) for o in payload["offerings"])
    print(("DRY RUN (rolled back): " if args.dry_run else "stored: ") + f"{offerings} offerings, {sections} sections, "
          f"{len(decisions)} policy decision(s); input sha256 {sha[:16]}…; {out.replace(chr(10), ' ')}")


if __name__ == "__main__":
    main()
