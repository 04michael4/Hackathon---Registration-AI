"""Actual HTTP acceptance against AuthoritativeCatalogFixture's synthetic test database.

Usage: python scripts/verify_schedule_integration.py BASE_URL ../docs/verification/schedule-final-http.json
Production uses the unchanged pre-main datasource/DAO/catalogs/solver. No API data is mocked here.
"""
import json
import sys
from pathlib import Path
from datetime import datetime, timezone
from urllib.request import Request, urlopen
from urllib.error import HTTPError

base = sys.argv[1].rstrip("/")
checks = []


def verify(name, body, expected=200, inspect=None, path="/api/schedules/generate"):
    request = Request(base + path, data=None if body is None else json.dumps(body).encode(),
                      headers={"Content-Type": "application/json"})
    try:
        response = urlopen(request, timeout=20)
    except HTTPError as error:
        response = error
    result = json.loads(response.read())
    failures = []
    if response.status != expected:
        failures.append(f"Expected {expected}, received {response.status}")
    elif inspect:
        try:
            inspect(result)
        except (AssertionError, KeyError, IndexError, TypeError) as error:
            failures.append(str(error) or "Response assertion failed")
    checks.append(dict(name=name, method=request.get_method(), path=path, request=body,
                       expectedStatus=expected, actualStatus=response.status, response=result,
                       passed=not failures, failures=failures))
    print(f"{'PASS' if not failures else 'FAIL'} {name}: HTTP {response.status}")


def require(condition, reason):
    assert condition, reason


def schedule(required=None, minimum=4, maximum=4, optional=None, closed=False, blocked=None):
    return {"term": "2027-SPRING", "maxResults": 10, "includeClosedSections": closed,
            "preferences": {"hardConstraints": {"requiredCourseIds": required or ["CSE-007"],
                "minCredits": minimum, "maxCredits": maximum, "blockedTimes": blocked or []},
                "softPreferences": {} if optional is None else {"optionalCourseIds": {"value": optional, "weight": 0}}}}


verify("authoritative course detail", None, path="/api/courses/CSE-007?term=2027-SPRING",
       inspect=lambda b: require(len(b["sections"]) == 3 and b["sections"][1]["lectureLink"] == "12275", "Catalog relationship missing"))
verify("feasible real lecture/lab pair", schedule(), inspect=lambda b: require(
    len(b["schedules"]) == 1 and [s["crn"] for s in b["schedules"][0]["sections"]] == ["12275", "13132"]
    and b["schedules"][0]["score"] == 100 and b["schedules"][0]["totalCredits"] == 4, "Real fixture pair/credits/score mismatch"))
verify("closed lab included only on request", schedule(closed=True), inspect=lambda b: require(
    len(b["schedules"]) == 2 and any(s["crn"] == "12638" for item in b["schedules"] for s in item["sections"]), "Closed lab not included"))
verify("multiple required courses avoid time conflict", schedule(["CSE-007", "MATH-021"], 8, 8), inspect=lambda b: require(
    [s["crn"] for s in b["schedules"][0]["sections"]] == ["12275", "13132", "40101"], "Conflicting alternative selected"))
verify("hard blocked time yields normal infeasibility", schedule(blocked=[{"days": ["MONDAY"], "start": "00:00", "end": "23:59"}]),
       inspect=lambda b: require(b["schedules"] == [] and bool(b["diagnostics"]), "Expected empty result with diagnostics"))
for credits in (18, 19, 21):
    verify(f"actual overload at {credits}", schedule([f"OVR-0{credits}"], credits, credits), inspect=lambda b, c=credits: require(
        b["schedules"][0]["totalCredits"] == c and b["schedules"][0]["overload"] == (c > 18), "Overload mismatch"))
verify("TBA optional course and duplicate normalization", schedule(["CSE-007", "CSE-007"], 7, 7, ["CSE-007", "ENGL-001", "ENGL-001"]),
       inspect=lambda b: require(b["schedules"][0]["sections"][2]["meetings"] == [], "TBA acquired a fake meeting"))
verify("exactly ten optional entries accepted", schedule(optional=[f"OPT-{i}" for i in range(100, 110)]),
       inspect=lambda b: require(bool(b["schedules"]), "Ten optional entries rejected"))
verify("eleven original optional entries rejected", schedule(optional=["CSE-007"] * 11), 400,
       inspect=lambda b: require(b["status"] == 400 and bool(b["errors"]) and bool(b["requestId"]), "Expected structured validation error"))
verify("invalid credit bound", schedule(minimum=22, maximum=22), 400)
verify("unknown course", schedule(["CSE-999"]), 422)
verify("manual endpoint regression", {"term": "2027-SPRING", "crns": ["12275", "13132"],
       "preferences": schedule()["preferences"]}, path="/api/schedules/check",
       inspect=lambda b: require(b["complete"] and b["score"] == 100 and b["totalCredits"] == 4, "Manual mode disagrees with generation"))

report = {"verifiedAt": datetime.now(timezone.utc).isoformat(), "baseUrl": base,
          "pipeline": "authoritative pre-main Hikari/JDBC/catalog_api/native solver; isolated persisted fixtures",
          "passed": sum(c["passed"] for c in checks), "failed": sum(not c["passed"] for c in checks), "checks": checks}
destination = Path(sys.argv[2])
destination.parent.mkdir(parents=True, exist_ok=True)
destination.write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
print(f"HTTP checks: {report['passed']} passed, {report['failed']} failed")
sys.exit(1 if report["failed"] else 0)
