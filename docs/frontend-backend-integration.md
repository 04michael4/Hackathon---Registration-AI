# Running and verifying the integrated app

The default semester planner uses Spring Boot for terms, course search/details, schedule generation, manual section checks, elective suggestions, preference extraction and explanations. The native Java solver is authoritative. Degree/transcript/advisor workspaces remain labeled sample-data prototypes because corresponding backend APIs do not exist. No backend contract, authentication filter, database schema or solver was changed for this integration.

## Start both processes

Requirements: Java 21, Node.js 24 LTS/npm 11, PostgreSQL (17.6 tested), and the checked-in Maven wrapper. Install the catalog views following [database setup](database.md) and the appropriate Data runbook: [catalog_api](../data/catalog-api/README.md) or [catalog_serving](../data/catalog-serving/README.md). Use an authorized database and a read-only application account. The backend needs `DB_URL` and `DB_USERNAME`; password comes from `DB_PASSWORD` or `PGPASSFILE`. Spring does not automatically load a root `.env` file.

Terminal 1, Bash:

```bash
cd backend
export JAVA_HOME=/path/to/jdk-21
export CATALOG_SOURCE=database
export DB_URL=jdbc:postgresql://127.0.0.1:5432/registration
export DB_USERNAME=registration_readonly
# Set DB_PASSWORD or PGPASSFILE locally if required.
export DB_CATALOG_SCHEMA=catalog_api
export AI_PROVIDER=none
./mvnw spring-boot:run
```

PowerShell uses `$env:DB_URL='...'` and `./mvnw.cmd spring-boot:run`. On this Windows machine the `.cmd` wrapper encounters an existing directory-target error; Git Bash plus JDK 21 works:

```powershell
$env:JAVA_HOME='C:\Program Files\Java\jdk-21'
# Set the same database/provider variables in this shell first.
& 'C:\Program Files\Git\bin\bash.exe' ./mvnw spring-boot:run
```

Alternatively, put the backend variables in the ignored repository-root `.env` and run this from the repository root:

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\backend\start-local.ps1
```

The launcher parses `KEY=value` entries as data, loads them into the backend process and never prints credentials. File values take precedence for that process. It uses the Maven wrapper through Git Bash when available. `-Packaged` runs the existing `target/registration-backend-0.0.1-SNAPSHOT.jar` without rebuilding; rebuild after Java changes. The execution-policy override applies to this invocation only. Direct Maven/Java launches still need exported variables. Keep the real credentials out of examples and `frontend/.env.local`.


Terminal 2:

```bash
cd frontend
npm ci
# If .env.local does not already exist, copy .env.example to .env.local.
npm run dev
```

`frontend/.env.local` needs `BACKEND_URL=http://127.0.0.1:8080`. Keep existing developer settings. Open <http://localhost:3000> and choose **Continue to course planner**. The entry button does not authenticate: SSO and API authentication are not implemented. No tokens or cookies are required or forwarded.

On Windows use `npm.cmd`/`npx.cmd` if PowerShell blocks `npm.ps1`. For a production process, run `npm run build`, then `npm run start`. Frontend and backend run independently. `PORT` controls Spring Boot; `npm run dev -- --port 3001` selects another frontend port.

## Configuration and request flow

| Setting | Where | Behavior |
|---|---|---|
| `BACKEND_URL` | Next.js server environment or `frontend/.env.local` | Backend origin, optionally including its deployment context path; omit `/api`. Trailing slash accepted. Missing/invalid configuration shows an error, never demo results. |
| `FRONTEND_MODE` | Next.js server | `api` by default. Set exactly `demo` to use the existing offline prototype. Restart after changes. |
| `CORS_ALLOWED_ORIGINS` | Spring Boot | Existing exact-origin allowlist, default `http://localhost:3000`; credentials disabled. Browser traffic normally uses the same-origin proxy, so cross-origin access is unnecessary. |
| `AI_PROVIDER` | Spring Boot | `none` returns useful 503 responses; manual controls and scheduling remain usable. Real Claude calls require backend-only credentials and explicit authorization for paid testing. |

Browser → `/api/backend/<route>` → server-only `BACKEND_URL/api/<route>` → controllers → existing JDBC catalogs → native solver. `src/lib/api/types.ts` mirrors the [API contract](api-contract.md); `httpClient.ts`, `catalogClient.ts` and `scheduleClient.ts` own communication and runtime validation. Presentation never computes solver scores. `/api/catalog` remains a compatibility route for the explicit demo's optional metadata browser and delegates to the same proxy.

The proxy permits only these existing endpoints; it preserves the backend method, JSON schema, status and validation metadata:

| Method | Backend route | Frontend use |
|---|---|---|
| GET | `/api/terms` | Actual term options (`items`, `term`, `name`, `catalogSnapshotDate`) |
| GET | `/api/courses` | `term`, optional `q`/`subject`, zero-based `page`, `size=20` |
| GET | `/api/courses/{courseId}` | `term`; identifiers including numeric topic suffixes retained |
| POST | `/api/schedules/generate` | Exact `term`, `preferences`, `maxResults`, `includeClosedSections` |
| POST | `/api/schedules/check` | Manual CRNs, additional course IDs, preferences and closed-section policy |
| POST | `/api/schedules/suggest-electives` | Current CRNs/preferences and catalog query/subject, limit 5 |
| POST | `/api/preferences/extract` | `term`, text up to 2,000 characters; review before applying |
| POST | `/api/schedules/explain` | Current CRNs and reviewed preferences |

Shared errors use RFC 9457 Problem Details with `detail`, optional field `errors`, `requestId` and `X-Request-Id`. Validation is 400, unknown catalog resources 404, unknown generation term/course 422, unavailable database/AI 503. Client handling also covers 401/403/409/500, malformed JSON, cancellation, network failure and timeouts. Upstream internal 5xx details are masked; request references remain available. The server proxy times out after 30 seconds; the browser after 35 seconds. Requests are private/no-store, with fixed route/query allowlists, no forwarded cookies and a 64 KiB JSON body limit.

## Supported behavior and limits

- Catalog keyword/code/title search, subject filter and pagination use real responses. There is no semantic interest-search API: the single course/interest box searches catalog keywords.
- Details retain descriptions, section/component/lecture links, actual CRNs, availability, meetings, instructor names, predictions/confidence and conditions. Structured prerequisite/corequisite eligibility is unavailable; conditions remain unverified. Missing predictions and meeting times are not invented.
- Required/optional courses, credits 1–21, ordered credit bounds, weights 0–5, result limit 1–10, at most 10 optional courses, preferred times/days, blocked times and closed sections map to the existing contract. Backend validation remains authoritative.
- Alternatives show the exact rank, scores, breakdowns, credits, overload flag, sections, elective advice and diagnostics. There is no server schedule ID. Empty results stay empty. TBA/asynchronous sections remain listed without fake calendar events. Seven-day calendars cover returned times, including early/late meetings.
- Manual placement (button or drag), removal, linked-component fit hints, conflict messages and undo/redo call the real checker. Saved plans retain only identifiers, preferences and a user-entered name in this browser; reopening rechecks catalog facts. No server save, enrollment, account, registration or degree/transcript API is implied.
- Every semester search result offers Add to next semester. Required selections appear immediately as planned courses in the degree calendar, and actual calendar sections automatically update its corresponding semester. Lecture/lab sections share one course card; TBA sections remain listed. Adding a course preserves existing meetings and rechecks through the backend. Clicking a live degree card loads its API details. The cross-tab projection contains course identifiers/titles/credits/section identifiers only and stays in memory, outside the persisted sample curriculum. Use Save schedule to restore a plan after reload. Other degree terms, requirements and advisor workflows remain labeled samples; sample courses cannot overwrite the live semester.
- The semester page retains one unified search, calendar-left/course-list-right workspace, Save/Undo/View bigger toolbar, and collapsible header AI/preferences panel. The main calendar shows weekdays and expands for actual weekend meetings; View bigger always includes all seven days. The existing calendar-first degree layout and permanent four-tab ribbon remain. Its sample-data label occupies the existing university line rather than adding a placeholder/banner row.
- Instructor names are real. Keep real catalog access within the team's private app until Banner redistribution is resolved. No real API names were added to fixtures, screenshots, logs or saved plans. Do not publish a configured catalog connection yet.

## Checks and deterministic acceptance setup

```bash
cd frontend
npm test
npm run lint
npm run build
npm run verify:catalog
```

`verify:catalog` uses a loopback fake HTTP server and synthetic instructors to test prediction/condition rendering; it explicitly starts the frontend in demo mode. `verify:degree` checks the existing demo layout/interaction regression suite: start a separate built frontend with `FRONTEND_MODE=demo`, set `SYLLO_LAYOUT_URL` to its URL, then run `npm run verify:degree`. Avoid a second `next dev` in the same directory because of its lock; use `next start` on another port after building.

Backend unit/build checks: `cd backend && ./mvnw verify`. Real database checks require a disposable loopback PostgreSQL test cluster and a database-creation test account:

```bash
export TEST_DATABASE_URL=jdbc:postgresql://127.0.0.1:55437/course_schedule_test
export TEST_DATABASE_USER=postgres
# TEST_DATABASE_PASSWORD if needed; never point this at a shared/production database.
./mvnw -Pdatabase-it verify
```

The existing fixture utility validates loopback/test database naming, creates fresh UUID-named databases, loads the authoritative checked-in PostgreSQL schema/views plus deterministic synthetic rows, and never drops databases. To create an acceptance database:

```bash
./mvnw dependency:build-classpath -Dmdep.outputFile=target/test-classpath.txt -Dmdep.includeScope=test
java -cp "target/test-classes:target/classes:$(cat target/test-classpath.txt)" \
  io.github.michael04.registration.schedule.AuthoritativeCatalogFixture synthetic
```

On Windows the classpath separator is `;`. The fixture utility itself only needs `target/test-classes` and the PostgreSQL driver JAR from the Maven cache, so that minimal classpath also works. Set `DB_URL` to the printed `FIXTURE_DATABASE_URL`, `DB_USERNAME` to the test account, `DB_CATALOG_SCHEMA=catalog_api`, `CATALOG_SOURCE=database`, `AI_PROVIDER=none`, then run the built Spring Boot JAR. Keep test passwords local. This tests the production JDBC adapter and native engine, with synthetic MATH/ENGL/OPT rows rather than a fake catalog service.

After building the frontend:

```bash
cd frontend
SMARTCOURSE_TEST_BACKEND_URL=http://127.0.0.1:58080 npm run verify:integration
```

PowerShell: `$env:SMARTCOURSE_TEST_BACKEND_URL='http://127.0.0.1:58080'; npm.cmd run verify:integration`. The script requires the synthetic loopback catalog, launches isolated production frontend/Chrome processes, and checks real browse/search/detail, generation alternatives/scores/credits/CRNs/TBA, saving/rechecking, undo/redo, backend validation, no-feasible results, AI-off, network failure/retry, actual backend connection refusal, navigation and CORS. Screenshots and JSON evidence go to a temporary directory. It stops only its own frontend/browser processes; the test database and backend remain available for inspection. Override `CHROME_PATH` when needed.

Direct smoke requests (use authorized course identifiers in your database):

```bash
curl http://localhost:8080/api/health
curl 'http://localhost:8080/api/courses?term=2027-SPRING&subject=MATH'
curl -H 'Content-Type: application/json' -d '{"term":"2027-SPRING","preferences":{"hardConstraints":{"requiredCourseIds":["MATH-021","ENGL-001"],"minCredits":7,"maxCredits":7,"blockedTimes":[]}},"maxResults":2,"includeClosedSections":false}' http://localhost:8080/api/schedules/generate
```

Those MATH/ENGL identifiers refer specifically to the synthetic acceptance fixture; a different catalog may correctly return 422. Check the printed fixture URL before starting Spring Boot. Missing views/credentials give 503, not mock courses. Inspect backend logs locally for connection problems without copying private data. A missing `BACKEND_URL`, wrong port or stopped Java process gives a retryable frontend error. For direct CORS requests, match the full frontend origin (scheme/host/port); `localhost` and `127.0.0.1` differ. Do not add a second CORS filter or use wildcard origins to hide errors.

## Verification evidence, 2026-10-18

Branch `feature/frontend-backend-integration` starts at approved `pre-main` `64c9ed7`; no teammate branch import was necessary. The user authorized committing and pushing this feature branch on 2026-10-18. Private `.env` files and generated artifacts are excluded.

| Check | Actual result |
|---|---|
| Frontend unit/HTTP/contract/presentation tests | `Tests 112 passed (112)`, `Test Files 5 passed (5)` |
| Frontend lint and production build | Exit 0; `Compiled successfully`, TypeScript and static generation completed |
| Backend unit suite | `Tests run: 801, Failures: 0, Errors: 0, Skipped: 40` |
| Backend isolated PostgreSQL integration profile | `Tests run: 45, Failures: 0, Errors: 0, Skipped: 0`, `BUILD SUCCESS` (JAR packaged) |
| Real frontend + Spring Boot + PostgreSQL + native solver | `PASS: 44 real frontend/Spring Boot/PostgreSQL/native-solver acceptance checks` |
| Separate synthetic catalog browser regression | `PASS: 30 catalog browser checks` |
| Explicit-demo calendar/navigation regression | `PASS: 228 degree layout and navigation checks` |
| Private database browser smoke test, without captures | `PASS: 9 private-database browser checks` |

Real workflows A–E were executed: browsing/filtering/details; actual generation and alternative selection; backend 400 validation shown in the UI; real empty solver output; network failure/retry plus a frontend pointed at an unbound backend port. Credits, scores, CRNs and TBA rendering were compared to actual responses. Saving/reopening and manual remove/undo/redo called the real checker. Configured CORS preflight succeeded and unapproved origin returned 403. Browser exceptions/console errors were absent; expected AI-off explanation requests produced the existing sanitized 503 backend log entries. Invalid-input controller tests verify no solver invocation.

The automated acceptance fixture was loaded into a newly created `course_schedule_test_*` database on a private loopback PostgreSQL 17.6 cluster, using the production catalog views and JDBC adapter. Those tests used no shared database, mock solver, paid AI call or real instructor capture. Temporary synthetic browser evidence: `C:\Users\puram\AppData\Local\Temp\smartcourse-api-check-BjJJuY`; backend build/server logs are ignored local files. The 40 skipped tests require other opt-in source/live/serving/private-fixture configurations; they are not counted as passed.

After the user supplied the private database configuration, the local backend was started through `start-local.ps1` with a read-only account and `catalog_serving`. Actual backend and frontend-proxy requests returned 68 catalog courses; course details and generation succeeded. Nine additional browser checks verified actual search, term selection, section/condition details, two generated alternatives, credits and meetings without logging names, taking screenshots or saving responses. No writes, grants, migrations or database test suites ran against that database. Full ratings coverage and live paid Claude remain unverified. Before publication, frontend lint/tests/build and the 801-test backend unit suite were rerun successfully.
