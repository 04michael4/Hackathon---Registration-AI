# API Contract

This file is the **source of truth** for the HTTP interface between the frontend and the backend. Change it in the same PR as the code that changes the contract (see [CLAUDE.md § Shared interfaces](../CLAUDE.md#shared-interfaces)).

> ⚠️ **Provisional.** Only endpoint 1 (`GET /api/health`) is implemented. Endpoints 2–7, the `SchedulePreferences` shape, the credit limits, and the planned error format are **draft proposals that the team hasn't agreed yet**. The owning module confirms each one before building it, and marks the row 🟡/✅ once implemented.

**Status legend**

| Label | Meaning |
|---|---|
| ✅ **Implemented, verified** | Code exists, has automated tests, and was checked against a running app |
| 🟡 **Implemented** | Code exists but hasn't been fully verified |
| ⬜ **Planned, not implemented** | Proposed design only. Field names and shapes may still change before implementation. |

| # | Method | Path | Purpose | Status | Responsible module |
|---|---|---|---|---|---|
| 1 | GET | [`/api/health`](#1-get-apihealth) | Liveness check | ✅ Implemented, verified | Backend API |
| 2 | GET | [`/api/terms`](#2-get-apiterms) | List terms with catalog data | ⬜ Planned | Backend API (+ Data) |
| 3 | GET | [`/api/courses`](#3-get-apicourses) | **Course search** | ⬜ Planned | Backend API (+ Data) |
| 4 | GET | [`/api/courses/{courseId}`](#4-get-apicoursescourseid) | Course detail with sections | ⬜ Planned | Backend API (+ Data) |
| 5 | POST | [`/api/preferences/extract`](#5-post-apipreferencesextract) | **AI preference extraction** | ⬜ Planned | AI Integration |
| 6 | POST | [`/api/schedules/generate`](#6-post-apischedulesgenerate) | **Schedule generation** | ⬜ Planned | Scheduling Engine |
| 7 | POST | [`/api/schedules/explain`](#7-post-apischedulesexplain) | AI explanation of a schedule | ⬜ Planned (stretch) | AI Integration |

---

## Conventions

These apply to every endpoint unless the endpoint says otherwise.

| Topic | Convention | Status |
|---|---|---|
| Base URL | Local: `http://localhost:8080`. Deployed: _TBD_ | Local ✅ |
| Prefix | All endpoints live under `/api` | ✅ |
| Format | JSON request and response bodies, `Content-Type: application/json` | ✅ |
| Field names | `camelCase` | ✅ (health) |
| Days of week | `MONDAY` … `SUNDAY`, matching `java.time.DayOfWeek` names so they map to Java with no translation | ⬜ |
| Times | `"HH:mm"`, 24-hour, Lehigh local time (America/New_York); no time zone in the payload | ⬜ |
| Course IDs | `"<SUBJECT>-<NUMBER>"`, e.g. `"CSE-017"`, which is URL-safe. Display form is `"CSE 017"`. | ⬜ |
| Section IDs | `crn`: the registrar's Course Reference Number, as a string. _Exact format to be confirmed against the data source._ | ⬜ |
| Term IDs | `term`: an opaque string, e.g. `"2027-SPRING"` (illustrative). _Final format TBD with the data source._ | ⬜ |
| Pagination | Query `page` (0-based, default `0`) and `size` (default `20`, max `100`). Response: `{ items, page, size, totalItems, totalPages }` | ⬜ |
| Auth | None in the MVP (no user accounts) | ✅ (by design) |

> All course codes, CRNs, titles, and times in the examples below are **illustrative placeholders**, not real Lehigh catalog data.

### Error responses

**Current behavior (implemented, verified 2026-10-15).** Errors use Spring Boot's default error body:

```json
{ "timestamp": "2026-10-15T20:33:37.726Z", "status": 404, "error": "Not Found", "path": "/api/nope" }
```

Observed: unknown path → `404`; wrong method (e.g. `POST /api/health`) → `405` with an `Allow: GET` header.

**Planned (not implemented).** Switch to [RFC 9457 Problem Details](https://www.rfc-editor.org/rfc/rfc9457) (`Content-Type: application/problem+json`), which Spring supports natively. This switch is itself a contract change: update this section in the same PR and tell the frontend owner.

```json
{
  "type": "about:blank",
  "title": "Bad Request",
  "status": 400,
  "detail": "Request validation failed",
  "instance": "/api/schedules/generate",
  "errors": [ { "field": "hardConstraints.maxCredits", "message": "must be between 1 and 24" } ]
}
```

Planned status code usage:

| Code | When |
|---|---|
| 400 | Malformed JSON, missing required fields, values out of range |
| 404 | Unknown resource (e.g. course ID) in a path |
| 422 | Well-formed request that can't be processed, e.g. unknown course IDs in a body, or `minCredits > maxCredits` |
| 429 | Rate limit exceeded (AI endpoints; mechanism TBD) |
| 500 | Unexpected server error. Never includes stack traces. |
| 502 | The AI provider returned an error or output that failed validation |
| 503 | AI features are not configured (no API key) or the provider is unavailable |

---

## 1. `GET /api/health`

**Status: ✅ Implemented, verified.** Code: [`HealthController`](../backend/src/main/java/io/github/michael04/registration/health/HealthController.java). Test: [`HealthControllerTest`](../backend/src/test/java/io/github/michael04/registration/health/HealthControllerTest.java). Module: Backend API.

**Purpose:** a liveness check. It reports that the process is up and serving HTTP. It checks **no** dependencies (database, AI), so it stays fast and reliable.

**Request parameters:** none. **Request body:** none.

**Response schema** (`200 OK`)

| Field | Type | Description |
|---|---|---|
| `status` | string | Always `"UP"` when the endpoint responds |
| `service` | string | Value of `spring.application.name` (`"registration-backend"`) |

**Example**

```bash
curl -i http://localhost:8080/api/health
```

```http
HTTP/1.1 200
Content-Type: application/json

{"status":"UP","service":"registration-backend"}
```

**Status codes:** `200` OK; `405` for any method other than GET.

---

## 2. `GET /api/terms`

**Status: ⬜ Planned, not implemented.** Module: Backend API (endpoint), Data (content). Priority: low; it supports term selection in the UI.

**Purpose:** list the terms for which catalog data is loaded.

**Response** (`200 OK`)

```json
{
  "items": [
    { "term": "2027-SPRING", "name": "Spring 2027", "catalogSnapshotDate": "2026-10-01" }
  ]
}
```

`catalogSnapshotDate` tells users how fresh the data is, since we use catalog snapshots, not live registrar data.

---

## 3. `GET /api/courses`

**Status: ⬜ Planned, not implemented.** Module: Backend API (endpoint), Data (catalog content). **Priority: high.**

**Purpose:** search the catalog for one term by code or title.

**Query parameters**

| Name | Type | Required | Description |
|---|---|---|---|
| `term` | string | yes | Term ID |
| `q` | string | no | Free text; matches course code (`"CSE 017"`, `"cse017"`) or title words. Max 100 chars. |
| `subject` | string | no | Subject code filter, e.g. `CSE` |
| `page`, `size` | int | no | See [Pagination](#conventions) |

**Response schema** (`200 OK`): page of `CourseSummary`

| Field | Type | Description |
|---|---|---|
| `courseId` | string | e.g. `"CSE-017"` |
| `subject` | string | e.g. `"CSE"` |
| `number` | string | String, to preserve leading zeros (`"017"`) |
| `title` | string | Course title |
| `credits` | number | Credit hours. _Variable-credit representation TBD._ |
| `sectionCount` | int | Number of sections offered this term |

**Example**

```bash
curl "http://localhost:8080/api/courses?term=2027-SPRING&q=data%20structures&size=2"
```

```json
{
  "items": [
    { "courseId": "CSE-017", "subject": "CSE", "number": "017", "title": "Programming and Data Structures", "credits": 3, "sectionCount": 4 }
  ],
  "page": 0, "size": 2, "totalItems": 1, "totalPages": 1
}
```

**Status codes:** `200` (an empty `items` array if nothing matches); `400` if `term` is missing or `size > 100`; `404` if the term is unknown.

---

## 4. `GET /api/courses/{courseId}`

**Status: ⬜ Planned, not implemented.** Module: Backend API (+ Data). Priority: high (needed to display sections and generated schedules).

**Query parameters:** `term` (required).

**Response** (`200 OK`)

```json
{
  "courseId": "CSE-017",
  "subject": "CSE",
  "number": "017",
  "title": "Programming and Data Structures",
  "credits": 3,
  "description": "…",
  "sections": [
    {
      "crn": "40123",
      "sectionCode": "010",
      "instructors": ["Instructor TBA"],
      "status": "OPEN",
      "meetings": [
        { "days": ["MONDAY", "WEDNESDAY", "FRIDAY"], "start": "10:45", "end": "11:35", "location": "TBD" }
      ]
    }
  ]
}
```

`status` is `OPEN`, `CLOSED`, or `UNKNOWN`. The value depends on whether the data source has enrollment info.

**Status codes:** `200`; `400` if `term` is missing; `404` if the course or term is unknown.

---

## 5. `POST /api/preferences/extract`

**Status: ⬜ Planned, not implemented.** Module: AI Integration. **Priority: high.**

**Purpose:** turn a student's free-text description of what they want into a structured `SchedulePreferences` object (the same shape that [`/api/schedules/generate`](#6-post-apischedulesgenerate) accepts). The frontend shows the result to the student for **review and editing before** they generate schedules. The AI proposes; the student confirms.

**Request schema**

| Field | Type | Required | Constraints |
|---|---|---|---|
| `term` | string | yes | Must be a known term. Course IDs are checked against this term. |
| `text` | string | yes | 1–2000 characters |

**Response schema** (`200 OK`)

| Field | Type | Description |
|---|---|---|
| `preferences` | `SchedulePreferences` | Validated, structured preferences (see below) |
| `unresolved` | string[] | Things the student mentioned that couldn't be mapped, e.g. a course not in the term's catalog |
| `warnings` | string[] | Assumptions made, e.g. "Interpreted 'mornings off' as earliest start 12:00" |

**`SchedulePreferences`** (shared with endpoint 6)

```jsonc
{
  "hardConstraints": {
    "requiredCourseIds": ["CSE-017", "MATH-021"],   // must all be scheduled
    "minCredits": 12,                               // PROVISIONAL: 1–24, default 12
    "maxCredits": 18,                               // PROVISIONAL: 1–24, default 18; >= minCredits
    "blockedTimes": [                               // no class may overlap these
      { "days": ["TUESDAY", "THURSDAY"], "start": "15:00", "end": "18:00" }
    ]
  },
  "softPreferences": {                              // each weight: integer 0–5 (0 = ignore)
    "optionalCourseIds": { "value": ["ECO-001"], "weight": 2 },
    "earliestStart":     { "value": "10:00",     "weight": 3 },
    "latestEnd":         { "value": "17:00",     "weight": 1 },
    "daysOff":           { "value": ["FRIDAY"],  "weight": 4 },
    "minimizeGaps":      { "weight": 2 }
  }
}
```

> **Provisional credit limits.** The 1–24 bounds and the 12–18 defaults are placeholders. They have **not** been checked against Lehigh's actual credit-load policy. Confirm them before implementation, and make them configurable rather than hard-coded.

**Example**

```bash
curl -X POST http://localhost:8080/api/preferences/extract \
  -H 'Content-Type: application/json' \
  -d '{"term":"2027-SPRING","text":"I need CSE 17 and Calc 1, no classes before 10, ideally Fridays off. I work Tue/Thu 3-6pm."}'
```

```json
{
  "preferences": {
    "hardConstraints": {
      "requiredCourseIds": ["CSE-017", "MATH-021"],
      "minCredits": 12, "maxCredits": 18,
      "blockedTimes": [ { "days": ["TUESDAY", "THURSDAY"], "start": "15:00", "end": "18:00" } ]
    },
    "softPreferences": {
      "earliestStart": { "value": "10:00", "weight": 4 },
      "daysOff": { "value": ["FRIDAY"], "weight": 3 }
    }
  },
  "unresolved": [],
  "warnings": ["Mapped 'Calc 1' to MATH-021", "Credit range not mentioned; used defaults 12–18"]
}
```

**Status codes:** `200`; `400` if `text` is empty or too long; `422` if the term is unknown; `429` if rate limited; `502` if the AI output failed validation after retry, or the provider errored; `503` if AI is not configured.

**Error handling rules**
- The response **never** contains unvalidated model output. The AI's JSON is schema-constrained, then validated server-side (ranges, `minCredits <= maxCredits`, `start < end`, course IDs exist in the term).
- Course IDs that don't exist go to `unresolved`. They are never silently dropped and never invented.
- The student's `text` is treated as data, not instructions.

---

## 6. `POST /api/schedules/generate`

**Status: ⬜ Planned, not implemented.** Module: Scheduling Engine (logic and endpoint), using the Backend API's course data. **Priority: high.**

**Purpose:** run the deterministic scheduling engine and return up to `maxResults` conflict-free schedules, ranked by how well they satisfy the soft preferences. **This endpoint never calls the AI.** The same request against the same catalog snapshot always returns the same response.

**Request schema**

| Field | Type | Required | Constraints |
|---|---|---|---|
| `term` | string | yes | Known term |
| `preferences` | `SchedulePreferences` | yes | See [endpoint 5](#5-post-apipreferencesextract). `requiredCourseIds` must be non-empty. |
| `maxResults` | int | no | 1–10, default 5 |
| `includeClosedSections` | boolean | no | Default `false` |

**Response schema** (`200 OK`)

| Field | Type | Description |
|---|---|---|
| `term` | string | Echo of the request term |
| `schedules` | `Schedule[]` | Best first; empty if no feasible schedule exists |
| `schedules[].rank` | int | 1 = best |
| `schedules[].score` | number | 0–100. Higher means better soft-preference fit. |
| `schedules[].totalCredits` | number | |
| `schedules[].sections` | object[] | `{ crn, courseId, sectionCode, title, credits, meetings[] }` (`meetings` as in endpoint 4) |
| `schedules[].scoreBreakdown` | object | Penalty per soft preference, e.g. `{ "earliestStart": 0, "daysOff": 4 }` |
| `diagnostics` | string[] | When results are empty or limited: why, e.g. which required courses conflict |
| `searchLimitReached` | boolean | `true` if the engine stopped at its exploration cap. The results are still valid but might not be the global best. |

**Example**

```bash
curl -X POST http://localhost:8080/api/schedules/generate \
  -H 'Content-Type: application/json' \
  -d '{"term":"2027-SPRING","maxResults":1,"preferences":{"hardConstraints":{"requiredCourseIds":["CSE-017","MATH-021"],"minCredits":6,"maxCredits":18,"blockedTimes":[]},"softPreferences":{"earliestStart":{"value":"10:00","weight":3}}}}'
```

```json
{
  "term": "2027-SPRING",
  "schedules": [
    {
      "rank": 1,
      "score": 100,
      "totalCredits": 7,
      "sections": [
        { "crn": "40123", "courseId": "CSE-017", "sectionCode": "010", "title": "Programming and Data Structures", "credits": 3,
          "meetings": [ { "days": ["MONDAY","WEDNESDAY","FRIDAY"], "start": "10:45", "end": "11:35", "location": "TBD" } ] },
        { "crn": "40456", "courseId": "MATH-021", "sectionCode": "020", "title": "Calculus I", "credits": 4,
          "meetings": [ { "days": ["TUESDAY","THURSDAY"], "start": "10:45", "end": "12:00", "location": "TBD" } ] }
      ],
      "scoreBreakdown": { "earliestStart": 0 }
    }
  ],
  "diagnostics": [],
  "searchLimitReached": false
}
```

**Status codes:** `200`, including when `schedules` is empty because the constraints are infeasible; `diagnostics` then explains why. `400` for validation errors. `422` for unknown course IDs or term, or `minCredits > maxCredits`.

**Error handling:** infeasibility is a normal result, not an error. Validation runs before the engine, so the engine only ever receives well-formed input.

---

## 7. `POST /api/schedules/explain`

**Status: ⬜ Planned, not implemented (stretch goal).** Module: AI Integration.

**Purpose:** a short, plain-English explanation of why a generated schedule fits (or misses) the student's preferences.

**Request:** `{ "term": string, "crns": string[], "preferences": SchedulePreferences }`. The backend re-loads the section data by CRN, so the AI only sees catalog data plus the preferences, never arbitrary client-supplied facts.

**Response** (`200 OK`)

```json
{ "explanation": "This schedule keeps Fridays free and starts no earlier than 10:45…", "highlights": ["Fridays off", "No conflicts with your Tue/Thu work hours"] }
```

**Status codes:** `200`; `400`; `422` for an unknown CRN; `429`; `502`; `503` (same meanings as above).

---

## Change log

| Date | Change | By |
|---|---|---|
| 2026-10-15 | Initial contract: health implemented; endpoints 2–7 proposed | Claude Code session for @04michael4 |
