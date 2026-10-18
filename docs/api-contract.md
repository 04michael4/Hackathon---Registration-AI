# API Contract

This file is the **source of truth** for the HTTP interface between the frontend and the backend. Change it in the same PR as the code that changes the contract (see [CLAUDE.md § Shared interfaces](../CLAUDE.md#shared-interfaces)).

> ✅ **Agreed by the team (2026-10-15), integration updated 2026-10-16.** Schedule generation uses the authoritative PostgreSQL catalogs and native solver from pre-main and is verified through real HTTP. Demo catalogs are test-only. Endpoint status labels below distinguish implemented routes from planned work. Changes follow the [shared-interface rules](../CLAUDE.md#shared-interfaces).

**Status legend**

| Label | Meaning |
|---|---|
| ✅ **Implemented, verified** | Code exists, has automated tests, and was checked against a running app |
| 🟡 **Implemented** | Code exists but hasn't been fully verified |
| ⬜ **Planned, not implemented** | Agreed design, not yet built. Changes need the owners' approval (see the shared-interface rules). |

| # | Method | Path | Purpose | Status | Responsible module |
|---|---|---|---|---|---|
| 1 | GET | [`/api/health`](#1-get-apihealth) | Liveness check | ✅ Implemented, verified | Backend API |
| 2 | GET | [`/api/terms`](#2-get-apiterms) | List terms with catalog data | Implemented; authoritative DB/HTTP tests | Backend API (+ Data) |
| 3 | GET | [`/api/courses`](#3-get-apicourses) | **Course search** | ✅ Implemented, verified | Backend API (+ Data) |
| 4 | GET | [`/api/courses/{courseId}`](#4-get-apicoursescourseid) | Course detail with sections | ✅ Implemented, verified | Backend API (+ Data) |
| 5 | POST | [`/api/preferences/extract`](#5-post-apipreferencesextract) | **AI preference extraction** | ✅ Implemented, live-verified (eval 19/20 = 95%); PR #2 into `pre-main` pending review | AI Integration |
| 6 | POST | [`/api/schedules/generate`](#6-post-apischedulesgenerate) | **Schedule generation** | ✅ Authoritative PostgreSQL/native solver/real HTTP verified | Backend API + Scheduling Engine |
| 7 | POST | [`/api/schedules/explain`](#7-post-apischedulesexplain) | AI explanation of a schedule | Implemented; DB/engine/SDK with offline AI fixture | AI Integration |
| 8 | POST | [`/api/schedules/check`](#8-post-apischedulescheck) | **Manual mode**: check placed sections, fit hints | ✅ Implemented, verified | Backend API + Scheduling Engine |
| 9 | POST | [`/api/schedules/suggest-electives`](#9-post-apischedulessuggest-electives) | Electives that fit a schedule | ✅ Implemented, verified | Backend API + Scheduling Engine |

---

## Conventions

These apply to every endpoint unless the endpoint says otherwise.

| Topic | Convention | Status |
|---|---|---|
| Base URL | Local: `http://localhost:8080`. Deployed: _TBD_ | Local ✅ |
| Prefix | All endpoints live under `/api` | ✅ |
| Format | JSON success bodies (`application/json`); errors use `application/problem+json` | ✅ |
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

**Status: ✅ Implemented and verified locally, 2026-10-15.** The `common` package uses [RFC 9457 Problem Details](https://www.rfc-editor.org/rfc/rfc9457), with `Content-Type: application/problem+json`, for controller exceptions, Spring MVC errors, servlet error dispatches, and CORS rejections. This replaces the former `{ timestamp, status, error, path }` body and is a **contract change** for frontend consumers.

Every problem includes `type` (`about:blank`), the HTTP status phrase as `title`, numeric `status`, a safe `detail`, the request path as `instance` (without its query string), and a generated UUID `requestId`. The response header `X-Request-Id` matches that UUID. Incoming request IDs are not trusted or echoed. Validation failures additionally include `errors`, sorted by `field`, then `message`; other errors omit that array.

```json
{
  "type": "about:blank",
  "title": "Bad Request",
  "status": 400,
  "detail": "Request validation failed",
  "instance": "/api/schedules/generate",
  "requestId": "123e4567-e89b-42d3-a456-426614174000",
  "errors": [ { "field": "preferences.hardConstraints.maxCredits", "message": "must be less than or equal to 21" } ]
}
```

Validation fields use property paths such as `preferences.hardConstraints.maxCredits`, or a method parameter name for parameter constraints. Object-level/cross-parameter errors without a specific property path use `$`. Messages must be safe for public display; rejected values are never serialized. Unexpected failures and upstream errors use fixed messages, without exception messages, stack traces, provider responses, or request contents. Malformed JSON or invalid JSON types return 400 without a field-error array.

Status code usage:

| Code | When |
|---|---|
| 400 | Malformed JSON, missing required fields, values out of range; scheduling credit minimum above maximum; more than 10 optional course selections |
| 403 | CORS origin, method, or requested header is not allowed |
| 404 | Unknown resource (e.g. course ID) in a path |
| 405 | Unsupported HTTP method; preserves the `Allow` header |
| 406 | Requested response media type is not supported |
| 415 | Request content type is not supported |
| 422 | Well-formed request that can't be processed: an unknown term (endpoints 5 and 6) or unknown course IDs in a scheduling request body |
| 429 | Rate limit exceeded. AI endpoints: provider rate limit via `ai.AiRateLimitedException` (a `ResponseStatusException`); see endpoint 5 |
| 500 | Unexpected server error. Never includes stack traces. |
| 502 | The AI provider returned an error or output that failed validation |
| 503 | A required service is unavailable, including missing scheduling catalog/engine adapters or an unconfigured AI provider |

Backend consumers can throw `common.NotFoundException` (404) and `common.UnprocessableException` (422) with a **public, sanitized message**, or `common.AiUnavailableException` (503, fixed message). Spring `ResponseStatusException`/`ErrorResponseException` statuses and protocol headers are preserved, with sanitized problem bodies; these support future 429/502 mappings without coupling `common` to the AI SDK. Unknown exceptions, including unexpected `IllegalArgumentException`, and return-value validation failures are 500. This infrastructure does not implement AI calls or rate limiting.

### CORS

**Status: ✅ Implemented and verified locally, 2026-10-15.** Cross-origin access is configured for `/api/**` through `CORS_ALLOWED_ORIGINS` (`app.cors.allowed-origins`), a comma-separated list of exact HTTP(S) origins. The default is `http://localhost:3000`. Whitespace around entries is trimmed; wildcards, paths, credentials, queries, and fragments in configured origins are rejected at startup. An empty list disables cross-origin access; same-origin and requests without an `Origin` header still work.

Allowed methods: `GET`, `HEAD`, `POST`, `OPTIONS`. Allowed request headers: `Accept`, `Content-Type`. Credentials are disabled. `X-Request-Id` is exposed to allowed browser origins. Successful preflights are cached for 3600 seconds. The CORS method list does not add methods to individual endpoints. Rejected requests receive a 403 Problem Details body without an allow-origin header; browsers consequently cannot read that body. A future Next.js same-origin proxy can use an empty origin list.

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

**Status codes:** `200` OK for GET (and implicit HEAD); standard OPTIONS/preflight handling; `405` Problem Details for unsupported methods such as POST. CORS restrictions apply to cross-origin requests.

---

## 2. `GET /api/terms`

**Status: ✅ Implemented and PostgreSQL/HTTP verified.** Module: Backend API (endpoint), Data (content). `CATALOG_SOURCE=database` reads the configured PostgreSQL catalog. Without a production read catalog, this endpoint returns a sanitized 503.

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

Terms use the configured API-to-Banner mapping (for example 2027-SPRING, not raw 202710) and are ordered by API term ID; unmapped database terms are not advertised. `catalogSnapshotDate` is the source's actual `captured_at` date in America/New_York, or **null** when capture time is unknown. A transcription verification date is not substituted for the capture date. An empty catalog returns `{"items":[]}`.

---

## 3. `GET /api/courses`

**Status: ✅ Implemented, verified (2026-10-16).** Code: `course.CourseController` / `CourseService`; tests `CourseControllerTest`, `CourseServiceTest`. Production uses the authoritative database catalog. Demo catalogs exist only in test sources. `sectionCount` counts lectures and standalone sections (linked labs and recitations aren't counted separately). `503` if no catalog is configured. Module: Backend API (endpoint), Data (catalog content).

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
| `sectionCount` | int | Number of lectures/standalone sections this term (not linked components) |
| `predictedRating` | object or `null` | Added 2026-10-17. Quality and difficulty **predicted** for this term from earlier terms' student opinions: `quality` and `difficulty` (1.00 to 5.00; 5 is best / hardest), `confidence` (`LOW`, `MEDIUM`, `HIGH`), `basis` (always `PREDICTED`), `sectionsPredicted` and `sections` (lecture sections with a rated instructor / all lecture sections). `null` when no instructor of the course has an earlier rating. Show it as predicted, never as the course's rating |

**Example**

```bash
curl "http://localhost:8080/api/courses?term=2027-SPRING&q=data%20structures&size=2"
```

```json
{
  "items": [
    { "courseId": "CSE-017", "subject": "CSE", "number": "017", "title": "Programming and Data Structures", "credits": 3, "sectionCount": 4,
      "predictedRating": { "quality": 3.66, "difficulty": 2.95, "confidence": "MEDIUM", "basis": "PREDICTED", "sectionsPredicted": 1, "sections": 1 } }
  ],
  "page": 0, "size": 2, "totalItems": 1, "totalPages": 1
}
```

**Status codes:** `200` (an empty `items` array if nothing matches); `400` if `term` is missing or `size > 100`; `404` if the term is unknown.

`term` must be nonblank, `q` is at most 100 characters, `subject` contains letters only (case-insensitive), `page >= 0`, and `size` is 1–100. Invalid values return 400 before catalog access. Code matching ignores spaces and hyphens and is case-insensitive; all whitespace-separated title words must match. SQL wildcard characters in `q` are literal text. Results are ordered by course ID; there is no public sort parameter or credit filter. Out-of-range pages return empty `items`, preserving the actual total. `sectionCount` counts lectures/standalone sections, preserving pre-main behavior; linked labs and recitations are listed in course detail but are not counted separately. Missing catalog configuration returns 503; database query/connectivity failures return sanitized 503 Problem Details; other unexpected failures return 500.

---

## 4. `GET /api/courses/{courseId}`

**Status: ✅ Implemented, verified (2026-10-16).** Sections are ordered by CRN and **also include `componentType`, `lectureLink` (the lecture's CRN for a lab/recitation, `null` for a lecture) and `credits`**, so the manual-mode calendar can pair lectures with labs. Stored descriptions and instructor names are reloaded through the same read-only datasource; absent descriptions remain null and absent instructors remain an empty list. `503` if no catalog is configured. Module: Backend API (+ Data).

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
      "instructors": ["Testwick, Alder"],
      "instructorRatings": [
        { "name": "Testwick, Alder", "predictedQuality": 3.66, "predictedDifficulty": 2.95, "confidence": "MEDIUM", "ratingCount": 12, "courseSpecific": true }
      ],
      "status": "OPEN",
      "meetings": [
        { "days": ["MONDAY", "WEDNESDAY", "FRIDAY"], "start": "10:45", "end": "11:35", "location": "TBD" }
      ]
    }
  ],
  "predictedRating": { "quality": 3.66, "difficulty": 2.95, "confidence": "MEDIUM", "basis": "PREDICTED", "sectionsPredicted": 1, "sections": 1 },
  "conditions": [
    { "code": "PREREQUISITE", "message": "Prerequisite: CSE 004 or CSE 007", "evidenceLevel": "BANNER", "outcome": "UNVERIFIED" },
    { "code": "LINKED_COMPONENTS", "message": "The lab requirement was reported by the team and is not confirmed by Banner.", "evidenceLevel": "USER_CLARIFICATION", "outcome": "UNVERIFIED" }
  ]
}
```

`status` is `OPEN`, `CLOSED`, or `UNKNOWN`. The value depends on whether the data source has enrollment info.

Course IDs use uppercase subject letters, a hyphen, and digits; malformed IDs return 400. Sections are ordered by CRN. A stored instructor name is preserved as a single list item (commas within a name are not split); absent instructors produce an empty list. Meeting days with the same start/end/location are grouped and sorted Monday–Sunday. TBA/asynchronous sections have empty meetings, and null descriptions/locations remain null.

Only an explicit stored `OPEN`, `CLOSED` or `UNKNOWN` availability label is used. Truncated historical seat-count snippets remain `UNKNOWN`; they are never inferred to be current availability. The authoritative readiness views expose only certified whole-credit courses; unknown/fractional or uncertified courses remain hidden rather than rounded or guessed. Prerequisite/corequisite expressions and assessment states remain in Data tables; this response has no prerequisite fields and the MVP does not evaluate student eligibility. Section componentType, credits and lectureLink remain public as defined by pre-main, alongside the retained description/instructors fields.

**`predictedRating`** and each section's **`instructorRatings`** (added 2026-10-17, additive; only with `DB_CATALOG_SCHEMA=catalog_serving` and ratings installed, otherwise `null` and `[]`): what is predicted for the term from earlier terms' opinions (Rate My Professors, later also this app). A prediction is an estimate, not the course's rating: label it as predicted and show `confidence`. `instructors` is filled from the same data when the catalog stores none; an instructor with no earlier rating appears with `null` predictions (never a made-up number), and `courseSpecific` means students rated that instructor for this very course. Instructor names are real people's names: use them only inside the team's own app (ADR-0006).

**`conditions`** (added 2026-10-17, additive): what the application has **not verified** about taking this course. Each item has `code` (`PREREQUISITE`, `CLASS_STANDING`, `LEVEL`, `COLLEGE`, `MAJOR`, `DEPARTMENT_APPROVAL`, `CREDIT_EXCLUSION`, `ARRANGED_MEETING`, `LINKED_COMPONENTS`, `CROSS_LISTING`, `SECTION_TYPE`), a `message` for the student, `evidenceLevel` (`BANNER`, `CATALOG`, `USER_CLARIFICATION` or `TEAM_POLICY`; user-supplied evidence is never labelled Registrar-verified) and `outcome` (always `UNVERIFIED` today: the student is not checked against it). The list is ordered by `code` kind, then message, and is **empty when the catalog has none** (the verified snapshot `catalog_api` has no conditions). An empty list is not a promise that the student may register, and a condition list never means "eligible". The frontend should show the conditions with the course and must not describe a schedule as ready to register. Schedule endpoints do not return conditions yet.

**Status codes:** `200`; `400` if `term` is missing; `404` if the course or term is unknown.

Missing catalog configuration returns 503, and database query/connectivity failures return sanitized 503 Problem Details; other unexpected failures return 500. Course reads never write catalog data. The current native boundary requires integral known section credits; authoritative readiness views exclude unsupported courses before scheduling.

---

## 5. `POST /api/preferences/extract`

**Status: ✅ Implemented and live-verified.** Developed on `feature/ai-preference-extractor`; combined with `common` error handling and CORS, then with `pre-main`'s Schedule API (PR #2 into `pre-main` pending review). Module: AI Integration. **Priority: high.** Code: [`ai/PreferenceController`](../backend/src/main/java/io/github/michael04/registration/ai/PreferenceController.java).

**Live verification (2026-10-15, model `claude-haiku-5-5`, SDK 2.71.0):**

| Check | Result |
|---|---|
| Single request, "I need CSE 17 and Calc 1. I prefer no classes before 10 AM, and I work Tuesday and Thursday from 3 PM to 6 PM." | 200, **16/16 checks** passed; 3.9 s (first request after startup); 3,542 input / 197 output tokens |
| 20-case evaluation (`ClaudeLiveEval`, cases in `backend/src/test/resources/ai-eval/cases.json`) | **19/20 = 95%**; 0 re-asks, 0 refusals, 0 provider errors; about 1.6 s per case; 70,713 / 3,787 tokens; about $0.009 in total |
| Failed case | `vague-time`: "I'm not a morning person. CSE 140 and ECO 1." Courses and blocked times were correct, but no `earliestStart` was produced. A soft preference was omitted; nothing was invented. |
| Invented required courses / wrong hard constraints / wrong times / prompt-injection successes | **None observed** |

**Known limits of this verification:**
- It was one run, so variation between runs is unmeasured.
- Full responses weren't saved, only failure reasons.
- About 12 of the 20 cases don't check for invented soft preferences.
- For the injection cases, only the final result is visible: whether "CSE 999" was ignored by the model or set aside by Java can't be told apart.
- It ran against the dummy catalog, not real Lehigh data.

The endpoint, validation, course-name resolution and error codes all work, and the error mapping was also tested offline against a local fake of the Anthropic API. The AI step is chosen with `AI_PROVIDER`:

| `AI_PROVIDER` | Behavior |
|---|---|
| `none` (default) | Returns `503`, so the frontend shows the manual form |
| `dummy` | Ignores the text and returns the example below, so you can build the frontend without an API key |
| `claude` | Real extraction with Claude (model from `ANTHROPIC_MODEL`, default `claude-haiku-5-5`). Without `ANTHROPIC_API_KEY`, returns `503`; it never falls back to dummy output. |

**Errors** are RFC 9457 Problem Details from the shared `common.GlobalExceptionHandler` (see [Error responses](#error-responses)). They use `application/problem+json`, `type`/`status`/`detail`/`instance`, and a `requestId` that matches the `X-Request-Id` header. This was verified by full-application integration tests on `integrate/ai-into-pre-main`, using fake providers only.

| Situation | Status | `detail` (exact) | AI exception → shared type |
|---|---|---|---|
| Blank text, text over 2,000 characters, or missing term | `400` | `Request validation failed`, plus `errors[].field` | Bean Validation |
| Unknown term (checked before any AI call) | `422` | `Term is not available` (the term is never echoed) | `UnknownTermException` extends `common.UnprocessableException` |
| Rate limited (after the SDK's one automatic retry) | `429` | `Too many requests` | `AiRateLimitedException` extends `ResponseStatusException` |
| Model refused, or output truncated or malformed twice | `502` | `Upstream service returned an invalid response` | `AiOutputInvalidException` extends `ResponseStatusException` |
| Other provider errors (e.g. bad request, unknown model, HTTP 500) | `502` | same as above | `AiProviderException` extends `ResponseStatusException` |
| AI off (`none`), missing or rejected key, provider overloaded (503/529), connection failure, timeout | `503` | `AI service is unavailable` | `common.AiUnavailableException` (the AI module's duplicate class was removed) |

Error responses never include provider messages, student text, keys, exception names or stack traces. Integration tests plant a marker in every exception and request and assert it never appears.

Course resolution uses the configured authoritative database catalog. Test-only fixtures retain the synthetic `2027-SPRING` catalog for offline tests.

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
    "minCredits": 12,                               // 1–21, default 12
    "maxCredits": 18,                               // 1–21 (21 = hard cap), default 18; >= minCredits
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

### Credit limits

These are based on Lehigh's overload policy (text provided by the team, 2026-10-15), plus a team rule capping every schedule at 21 credits. The Schedule API enforces the fixed public-contract limits through shared `CreditLimits` constants and Bean Validation; changing them requires a contract change. The operational scheduling node cap is separately configurable.

| Rule | Value | Source |
|---|---|---|
| **Absolute maximum** | **21 credits** per semester. No schedule may exceed this. | Team rule |
| Default range (student didn't say) | `minCredits` 12, `maxCredits` 18 | Team default |
| Normal load | Up to **17** credits for first-semester students; up to **18** for everyone else | Lehigh policy |
| Overload | Anything above the normal load. Needs special approval. | Lehigh policy |

**Who approves an overload** (Lehigh policy):

| Student status | Approver for an overload up to… | Above that |
|---|---|---|
| First-semester student | 18 credits: Associate Dean | SOS |
| GPA ≤ 3.5 | 19 credits: Associate Dean | SOS |
| GPA > 3.5 | 20 credits: Associate Dean | SOS |

Exception: the policy doesn't apply where the student's normal departmental program requires more credits.

**Other policy rules.** The app can't check these, so it only shows them as information:
- Overloads aren't approved for repeating a course.
- Overloads aren't approved in a semester where the student is enrolled in a graduate course.
- Overload courses can't be added until after the normal three-week registration period, and space can't be reserved for them.

**How the app applies this**
- `maxCredits` must be **1–21**, and `minCredits ≤ maxCredits`. A request with `maxCredits > 21` returns `400`.
- **Preference extraction:**
  - If the student asks for more than 21 credits, `maxCredits` is set to 21, and a warning is added.
  - If they ask for more than 18 credits, a warning says this is an overload that needs approval.
- **Schedule generation:** each schedule with `totalCredits > 18` has `overload: true`. The frontend then shows an approval notice: the approver table above, plus "first-semester students: more than 17 credits is already an overload".
- The app doesn't know a student's GPA or whether they're in their first semester. So it flags overloads at the general 18-credit threshold, and doesn't try to say which approver applies.

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
- The response **never** contains unvalidated model output. The AI's JSON is schema-constrained, then validated server-side (ranges, `minCredits <= maxCredits`, `maxCredits <= 21`, `start < end`, course IDs exist in the term). Credit warnings follow [Credit limits](#credit-limits).
- Course IDs that don't exist go to `unresolved`. They are never silently dropped and never invented.
- `requiredCourseIds` may be **empty** when no course could be identified. That is still `200`, with a warning ("No required courses were identified; add them before generating schedules."). Endpoint 6 rejects an empty list with `400`, so the student adds a course first.
- The student's `text` is treated as data, not instructions.
- **Times** in the model's output must be real times of day written `H:mm` or `HH:mm` (`00:00`–`23:59`). `24:00`, other out-of-range values and other shapes (for example `009:30`, `10:00:30`, `3pm`) are rejected with a warning, never converted: an invalid `earliestStart` or `latestEnd` is dropped, and a blocked time with an invalid start or end is dropped whole.
- **The model's reply must contain every property** of the structured-output contract (all top-level properties, and `days`, `start` and `end` in every blocked time), with the right JSON types. An explicit `null` is a valid answer for the nullable fields and `[]` for the lists, but an absent property is not: `{}` or a partial reply counts as malformed output. Malformed output is retried once, then returns `502`. Refusals and provider errors are not retried.

---

## 6. `POST /api/schedules/generate`

**Status: Implemented and real PostgreSQL/native-solver/HTTP verified (2026-10-16).** Production defaults to the authoritative database catalog from pre-main. Hardcoded Course/Section providers are test-only. No AI is called by generation.

**Purpose:** run the deterministic scheduling engine and return up to `maxResults` conflict-free schedules, ranked by how well they satisfy the soft preferences. **This endpoint never calls the AI.** The same request against the same catalog snapshot always returns the same response.

**Request schema**

| Field | Type | Required | Constraints |
|---|---|---|---|
| `term` | string | yes | Known term |
| `preferences` | `SchedulePreferences` | yes | See [endpoint 5](#5-post-apipreferencesextract). `requiredCourseIds` must be non-empty. |
| `maxResults` | int | no | 1–10, default 5 |
| `includeClosedSections` | boolean | no | Default `false` |

**Validation and preparation order**

1. Validate the original request before catalog lookups or engine invocation. `term` must be nonblank; `preferences` and `hardConstraints` are required. Required course IDs must form a nonempty list of nonblank strings.
2. Both credit bounds must be integers in **1–21**, with `minCredits <= maxCredits`; violations return **HTTP 400**. Omitted/null bounds use 12 and 18. `maxResults` is an integer in **1–10**, default 5. Numeric strings and fractional numbers are rejected, not rounded or clamped.
3. Every supplied preference must include its value (except `minimizeGaps`) and an integer weight in **0–5**, including 0. Omitted preferences are inactive. Missing/null weights and invalid days/times return 400. Blocked times require nonempty valid days and `start < end`; times use `HH:mm`.
4. **Requests containing more than 10 optional courses receive HTTP 400.** This final integration task explicitly supersedes the earlier eight-entry API setting. This counts entries in `preferences.softPreferences.optionalCourseIds.value` **before** de-duplication or required/optional overlap removal, even when the weight is zero. Oversized requests are never truncated. Optional IDs must be nonblank strings.
5. De-duplicate by stable course ID. A course in both lists remains required and is removed from optional selections. Prepare courses sorted by ID and sections sorted by CRN. Unknown terms or selected course IDs return 422.
6. Exclude sections outside the requested term or selected courses. With `includeClosedSections=false` (also the default when null), exclude **every CLOSED component**, including lectures, labs, and recitations. OPEN and UNKNOWN remain eligible. With `true`, closed status alone does not exclude a section. **A lecture that loses every section of one linked component type** (e.g. all its labs are closed) **is excluded too**, along with its remaining linked sections, because it can't be taken alone. This uses the engine's own rule, `CourseOption.excluding`. Preserve component types and lecture links; never infer links. **`lectureLink` is the CRN of the lecture a lab or recitation must be taken with, and `null` for a lecture or standalone section.** A blank or self-referencing `lectureLink` is malformed catalog data: the request fails with a safe `500` rather than scheduling that lab as a standalone section. A lecture with linked sections is always scheduled with exactly one of them per component type. TBA/online sections without scheduled times have empty meetings lists. A course with no eligible sections is passed to the engine for normal infeasibility diagnostics.

The solver owns blocked-time exclusion, conflict solving, linked-component selection, credit feasibility and scoring. The API supplies validated constraints; it does not duplicate that logic. Omitted/null `blockedTimes` becomes an empty list; omitted/null `softPreferences` means no soft preferences. Explicit null for `maxResults` uses its default.

**Response schema** (`200 OK`)

| Field | Type | Description |
|---|---|---|
| `term` | string | Echo of the request term |
| `schedules` | `Schedule[]` | Best first; empty if no feasible schedule exists |
| `schedules[].rank` | int | 1 = best |
| `schedules[].score` | number | Engine-provided 0–100 score, copied unchanged without rounding or reweighting |
| `schedules[].totalCredits` | number | Never more than 21 |
| `schedules[].overload` | boolean | `true` if `totalCredits > 18`, meaning the schedule needs overload approval (see [Credit limits](#credit-limits)) |
| `schedules[].sections` | object[] | `{ crn, courseId, sectionCode, title, credits, meetings[] }` (`meetings` as in endpoint 4) |
| `schedules[].scoreBreakdown` | object | Engine-provided penalty values copied unchanged, e.g. `{ "earliestStart": 0, "daysOff": 4 }`; no missing entries are invented |
| `diagnostics` | string[] | When results are empty or limited: why, e.g. which required courses conflict |
| `searchLimitReached` | boolean | `true` if the engine stopped at its exploration cap. The results are still valid but might not be the global best. |
| `electiveAdvice` | object[] | One entry per typed elective (`softPreferences.optionalCourseIds`): `{ courseId, status, summary, fixes[], alternativeCrns[] }`. `status` is `IN_TOP_SCHEDULE`, `IN_OTHER_SUGGESTION`, `FITS_WITH_CHANGES` or `CANNOT_FIT`; `fixes` are sentences such as "Take MATH-021 20001 instead of 20002." Empty when no electives were typed. |

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
      "overload": false,
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

**Status codes:** `200`, including when `schedules` is empty because the constraints are infeasible; `diagnostics` then explains why. `400` for the validation rules above, including reversed credit bounds and more than 10 optional courses. `422` for unknown course IDs or term. `503` when required catalog services or the configured database are unavailable; no dummy fallback. `500` for unexpected internal failures, using the safe shared error body.

**Error handling:** infeasibility is a normal result, not an error. Validation runs before the engine, so the engine only ever receives well-formed input.

**Result mapping:** preserve engine schedule and section ordering; assign ranks starting at 1. Copy `totalCredits`, score, breakdown, diagnostics, and `searchLimitReached` from the result. Derive overload solely from each result's actual `totalCredits > 18`: 18 is false, 19 and 21 are true, regardless of the requested maximum. Section display details come from the same prepared catalog snapshot. Unknown returned CRNs or missing score/breakdown are internal integration failures, not invented data.

**Exploration configuration:** `scheduling.node-cap=${SCHEDULING_NODE_CAP:200000}` defaults to 200,000 for this final integration task, superseding the earlier 1M API setting. It is passed to the real solver on every generation request. Values must be positive 32-bit integers; invalid values fail startup. A real database/HTTP test with cap 1 verifies the returned limit flag. The native class default remains unchanged; API configuration overrides it.

---

## 7. `POST /api/schedules/explain`

**Status: ✅ Implemented; actual HTTP/database/engine/official-SDK pipeline verified against a local fake AI server.** Module: AI Integration. Live paid-provider quality was not evaluated in this task.

**Purpose:** a short, plain-English explanation of why a generated schedule fits (or misses) the student's preferences.

**Request:** `{ "term": string, "crns": string[], "preferences": SchedulePreferences }`. The backend re-loads the section data by CRN, so the AI only sees catalog data plus the preferences, never arbitrary client-supplied facts.

**Response** (`200 OK`)

```json
{ "explanation": "This schedule keeps Fridays free and starts no earlier than 10:45…", "highlights": ["Fridays off", "No conflicts with your Tue/Thu work hours"] }
```

**Status codes:** `200`; `400`; `422` for an unknown CRN; `429`; `502`; `503` (same meanings as above).

`term`, a nonempty list of at most 100 nonblank `crns`, and valid `preferences` are required. Preference validation reuses the generation rules (including ten optional entries). The service reloads sections from the requested term, normalizes duplicate CRNs, and uses the native `SelectionChecker` to reject unknown, conflicting or incomplete selections with 422 **before an AI call**. Only reloaded catalog facts and engine-computed totals/scores are sent to the provider.

Explanations require `AI_PROVIDER=claude` and a configured key. In `none` or development `dummy` mode this route returns 503; it never fabricates a production explanation. Malformed structured output is retried once, then 502. Refusals are final, provider errors use endpoint 5's safe status mapping, and no provider text is exposed in errors. The public response remains exactly `{ explanation, highlights }`.

---

## 8. `POST /api/schedules/check`

**Status: ✅ Implemented, verified (2026-10-16).** Module: Backend API (endpoint) + Scheduling Engine (`SelectionChecker`). Tests: `ManualModeEndpointsTest`.

**Purpose:** manual mode. The student places sections on a calendar. This endpoint checks the placement against the hard rules and preferences, and tells the UI what to grey out and what to highlight. **There's no elective limit.** It checks placements instead of searching combinations.

**Request**

| Field | Type | Required | Constraints |
|---|---|---|---|
| `term` | string | yes | Known term (else `422`) |
| `preferences` | `SchedulePreferences` | yes | As for endpoint 6; `minCredits ≤ maxCredits` |
| `courseIds` | string[] | no | Extra courses on the board (e.g. electives found by search), at most 30. Required and optional courses from `preferences` are always included. |
| `crns` | string[] | yes | The placed sections, at most 60. An unknown CRN is reported as an issue, not rejected. |
| `includeClosedSections` | boolean | no | Default `false` |

**Response** (`200 OK`)

| Field | Type | Description |
|---|---|---|
| `sections` | object[] | The placed sections, as in endpoint 6 |
| `totalCredits`, `overload` | number, boolean | As in endpoint 6 |
| `score`, `scoreBreakdown` | number, object | Same scoring as generated schedules |
| `complete` | boolean | `true` when there are no issues: a usable schedule |
| `issues` | object[] | `{ type, severity, crns[], message }`. `severity` `CONFLICT` (show red): `TIME_CONFLICT`, `BLOCKED_TIME`, `DUPLICATE_COURSE`, `DUPLICATE_COMPONENT`, `OVER_MAX_CREDITS`, `UNKNOWN_SECTION`. `INCOMPLETE` (show a prompt): `MISSING_LINKED_SECTION` (e.g. "CSE-007 lecture 12275 needs a LAB."), `LINKED_WITHOUT_LECTURE`, `REQUIRED_COURSE_MISSING`, `UNDER_MIN_CREDITS`. |
| `candidates` | object[] | One per unplaced section of the courses in play: `{ crn, courseId, fits, completesLecture, reasons[] }`. Grey out where `fits` is `false` and show `reasons`. Highlight where `completesLecture` is `true` (the labs a placed lecture still needs). |

**Status codes:** `200`; `400` for validation errors; `422` for an unknown term or course; `503` if no catalog is configured.

---

## 9. `POST /api/schedules/suggest-electives`

**Status: ✅ Implemented, verified (2026-10-16).** Module: Backend API + Scheduling Engine (`ElectiveAdvisor`). Tests: `ManualModeEndpointsTest`.

**Purpose:** "which electives fit my schedule?" It returns catalog courses that can be added to the schedule `crns` **as it is** (no conflicts, no blocked times, within the credit maximum), with the exact sections to add, best first.

**Request:** `{ term, preferences, courseIds?, crns, q?, subject?, limit?, includeClosedSections? }`.
- `crns` is the current schedule. Every CRN must belong to the required/optional courses or `courseIds`, else `422`.
- `q` and `subject` narrow the catalog like endpoint 3.
- `limit` is 1–20, default 5.
- At most 50 catalog courses (by course ID) are considered per request.

**Response** (`200 OK`): `{ term, suggestions: [ { courseId, title, sections[], totalCredits, overload, score, scoreBreakdown } ] }`. `sections` is a lecture plus one linked section per component. `score` and `scoreBreakdown` are the schedule's time-preference score after adding the elective.

**Status codes:** `200` (empty `suggestions` if nothing fits); `400`; `422` for an unknown term, course or schedule CRN; `503` if no catalog is configured.

---

## Demo-only endpoints (not part of the contract)

With `CATALOG_SOURCE=demo` (a local, feature-flagged Spring 2027 demo catalog; see README section 4b) the backend also serves `GET /api/demo/offerings`,
`POST /api/demo/schedules/generate` and the page `GET /demo`. They exist only in that mode, are additions (endpoints 1-9 are unchanged and also run on the
demo catalog), and are not a commitment: they may change or disappear after the demo. The demo response adds per-course warnings
(`UNVERIFIED` / `UNRESOLVED` / `OVERRIDE_REQUIRED`) and never reports a course as eligible.

## Change log

| Date | Change | By |
|---|---|---|
| 2026-10-17 | Course search items and course detail gain an additive nullable `predictedRating`; course sections gain an additive `instructorRatings` list and a filled `instructors`. Populated only with `DB_CATALOG_SCHEMA=catalog_serving` and the ratings installed; `null` / `[]` otherwise. No existing field changed. **Contract change**: the frontend owners should show it as predicted, with its confidence. | Claude Code for Michael, Database |
| 2026-10-17 | Course detail (endpoint 4) gains an additive `conditions` list (`code`, `message`, `evidenceLevel`, `outcome`); empty unless the backend runs with `DB_CATALOG_SCHEMA=catalog_serving`. No existing field changed. **Contract change**: the frontend owner should show the conditions with the course. | Claude Code for Michael, Database |
| 2026-10-16 | Resolve local draft/pre-main conflicts: keep authoritative database/catalog/native engine and manual/elective fields; retain terms and explanation routes plus display metadata on the same datasource. Preserve pre-main API term IDs, lecture-only sectionCount and readiness filtering. Old draft datasource is test-only. Consumer review requested for reconciled representations. | Codex, Backend API |
| 2026-10-16 | Final Schedule integration reuses pre-main PostgreSQL catalogs and native solver. Move hardcoded catalogs to test-only sources; production defaults to database. Explicit task requirements restore ten original optional entries (11 returns 400) and the 200000 API node cap; native scoring/algorithm and public response fields unchanged. Backend/Data/frontend/AI/engine consumers should review these config/contract changes. | Codex, Backend API |
| 2026-10-16 | Implemented endpoints 3 and 4 (sections also carry `componentType`, `lectureLink`, `credits`); added endpoints 8 (`/api/schedules/check`, manual mode) and 9 (`/api/schedules/suggest-electives`); added `electiveAdvice` to endpoint 6. The default `CATALOG_SOURCE=dummy` now includes demo sections, so endpoint 6 works with no database. All additive. **Backend API, frontend and Data owners: please review.** | Claude Code for Sunny (at the engine owner's request; Backend API and Data areas) |
| 2026-10-16 | A blank or self-referencing `lectureLink` is now rejected as malformed catalog data (`500`) instead of being treated as a standalone section. Addresses the Backend API owner's integration review. | Claude Code for Sunny, Scheduling Engine |
| 2026-10-16 | Optional-course limit lowered from 10 to **8** (more than 8 → 400). Default `scheduling.node-cap` raised from 200,000 to **1,000,000**. Engine-owner decisions; **contract change**, so the Backend API and frontend owners must review. | Claude Code for Sunny, Scheduling Engine |
| 2026-10-16 | Native scheduling engine integrated behind `SchedulingEngine`. `lectureLink` defined as the lecture's CRN (was opaque). With `includeClosedSections=false`, a lecture whose labs (or recitations) are all closed is now excluded as well. **Contract change:** the Backend API and Data owners must review. | Claude Code for Sunny, Scheduling Engine |
| 2026-10-16 | Implement endpoints 2–4 over an opt-in, read-only PostgreSQL catalog. Clarify parameter validation, stable ordering, null capture dates, literal search terms, historical UNKNOWN status, and 503 when the production catalog is unavailable. Public success shapes are unchanged. Frontend/Data consumer review required. | Codex, Backend API |
| 2026-10-16 | Endpoint 5 review fixes (Marpu, PR #2): `24:00` and other malformed times are rejected instead of being read as `00:00`; a model reply missing any required property is treated as malformed (retry once, then `502`). No request or response shape changed. **Consuming-module review requested.** | Michael |
| 2026-10-16 | AI integration merged with `pre-main`'s Schedule API. Shared preference records use the Backend API's strict JSON and validation; endpoint 5 behavior is unchanged (a request that names no course still returns `200` with a warning). Clarified the 422 row and that endpoint 5 may return an empty `requiredCourseIds`. **Consuming-module review required.** | Michael |
| 2026-10-15 | Implement Schedule API validation and mapping with test doubles; require HTTP 400 for more than 10 original optional selections and reversed credit bounds; document normalization, closed-component filtering, exact score pass-through, overload, configurable node cap, and 503 while production adapters are absent. Shared preference record shapes retained from AI branch; consuming-owner review required. | Codex, Backend API task |
| 2026-10-15 | Endpoint 5 integrated with `common` Problem Details: AI errors mapped onto shared types (422/429/502/503 with fixed `detail` texts); duplicate `ai.AiUnavailableException` removed | Michael |
| 2026-10-15 | Endpoint 5 live-verified: 1 request 16/16 checks; 20-case eval 19/20 (95%); failed case `vague-time` documented | Michael |
| 2026-10-15 | Endpoint 5: Claude provider added (`AI_PROVIDER=claude`), tested offline only; Claude error codes 429/502/503 specified | Michael |
| 2026-10-15 | Endpoint 5 implemented without Claude (`AI_PROVIDER` = `none` / `dummy`); times are serialized as `"HH:mm"` | Michael |
| 2026-10-15 | Implement the agreed Problem Details error format in `common`; add request IDs, sorted validation errors, safe fallback errors, and configurable CORS. Frontend consumers must migrate from the old default error JSON; consuming-module review required before merge. | Codex, Backend API task |
| 2026-10-15 | Credit limits follow Lehigh's overload policy: hard cap 21 (was 24), normal load 17/18, overload approvals; added `schedules[].overload` | Michael, with the team |
| 2026-10-15 | Team agreed the whole contract as written; provisional status removed | Michael, David, Marpu, Sunny |
| 2026-10-15 | Initial contract: health implemented; endpoints 2–7 proposed | Claude Code session for @04michael4 |
