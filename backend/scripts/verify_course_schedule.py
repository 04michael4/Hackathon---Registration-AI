"""Real HTTP acceptance checks against FixtureDatabase's isolated persisted test data.

Usage: python scripts/verify_course_schedule.py http://127.0.0.1:8080 ../docs/verification/course-schedule-http.json
The script exits nonzero if the real scheduling pipeline is unavailable; 503 is never accepted as success.
"""
import json
import pathlib
import sys
import urllib.error
import urllib.request
from datetime import datetime, timezone

base = sys.argv[1].rstrip("/")
destination = pathlib.Path(sys.argv[2])
checks = []


def check(name, path, expected=200, data=None, inspect=None):
    request = urllib.request.Request(base + path,
        data=None if data is None else json.dumps(data).encode(),
        headers={"Content-Type": "application/json"} if data is not None else {})
    try:
        response = urllib.request.urlopen(request, timeout=20)
    except urllib.error.HTTPError as error:
        response = error
    body = json.loads(response.read())
    failures = []
    if response.status != expected:
        failures.append(f"expected HTTP {expected}, received {response.status}")
    elif inspect:
        try:
            inspect(body)
        except (AssertionError, KeyError, IndexError, TypeError) as error:
            failures.append(str(error) or "response assertion failed")
    checks.append({"name": name, "method": request.get_method(), "path": path,
        "request": data, "expectedStatus": expected, "actualStatus": response.status,
        "response": body, "passed": not failures, "failures": failures})
    print(f"{'PASS' if not failures else 'FAIL'} {name}: HTTP {response.status}")


def require(condition, detail):
    assert condition, detail


def schedule(required, minimum=4, maximum=4, soft=None, blocked=None):
    return {"term": "TEST-2027", "maxResults": 2,
        "preferences": {"hardConstraints": {"requiredCourseIds": required,
            "minCredits": minimum, "maxCredits": maximum, "blockedTimes": blocked or []},
            "softPreferences": soft or {}}}


check("terms", "/api/terms", inspect=lambda b: require(len(b["items"]) == 2, "expected persisted terms"))
check("course search", "/api/courses?term=TEST-2027&q=cse007&size=2",
      inspect=lambda b: require(b["items"][0]["courseId"] == "CSE-007", "wrong course"))
check("course detail", "/api/courses/CSE-007?term=TEST-2027",
      inspect=lambda b: require(len(b["sections"]) == 4, "section metadata missing"))
check("unknown course", "/api/courses/CSE-999?term=TEST-2027", 404)
check("invalid pagination", "/api/courses?term=TEST-2027&size=101", 400)
check("feasible real schedule", "/api/schedules/generate", data=schedule(["CSE-007"]),
      inspect=lambda b: require(bool(b["schedules"]) and b["schedules"][0]["totalCredits"] == 4
          and {s["crn"] for s in b["schedules"][0]["sections"]} == {"12275", "13132"}, "expected compatible real lecture/lab pair"))
check("infeasible real schedule", "/api/schedules/generate",
      data=schedule(["CSE-007"], blocked=[{"days": ["MONDAY"], "start": "00:00", "end": "23:59"}]),
      inspect=lambda b: require(b["schedules"] == [] and bool(b["diagnostics"]), "expected normal infeasibility diagnostics"))
for credits in (18, 19, 21):
    check(f"overload at {credits}", "/api/schedules/generate",
          data=schedule([f"OVR-0{credits}"], minimum=credits, maximum=credits),
          inspect=lambda b, c=credits: require(b["schedules"][0]["totalCredits"] == c
              and b["schedules"][0]["overload"] == (c > 18), "wrong overload flag"))
optional = [f"OPT-{100 + i}" for i in range(10)]
check("exactly ten optional courses", "/api/schedules/generate",
      data=schedule(["CSE-007"], soft={"optionalCourseIds": {"value": optional, "weight": 0}}),
      inspect=lambda b: require(bool(b["schedules"]), "ten optional courses should be accepted"))
check("eleven optional courses", "/api/schedules/generate", 400,
      data=schedule(["CSE-007"], soft={"optionalCourseIds": {"value": optional + ["OPT-110"], "weight": 0}}))
check("invalid credit bounds", "/api/schedules/generate", 400, data=schedule(["CSE-007"], minimum=22, maximum=22))
check("unknown scheduling course", "/api/schedules/generate", 422, data=schedule(["CSE-999"]))

destination.parent.mkdir(parents=True, exist_ok=True)
report = {"verifiedAt": datetime.now(timezone.utc).isoformat(), "baseUrl": base,
          "fixture": "explicit isolated PostgreSQL synthetic catalog (never production seed data)",
          "passed": sum(c["passed"] for c in checks), "failed": sum(not c["passed"] for c in checks), "checks": checks}
destination.write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
print(f"HTTP checks: {report['passed']} passed, {report['failed']} failed; evidence: {destination}")
sys.exit(1 if report["failed"] else 0)
