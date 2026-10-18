# Frontend/backend integration

Branch: `feature/frontend-backend-integration`, based on approved `pre-main` `64c9ed7` (includes PR #17, Course API, Schedule API and native engine). No merges/cherry-picks are needed. The user authorized committing and pushing this feature branch on 2026-10-18; private environment files remain excluded.

## Audit and gaps at the start

- Next.js 16.4.2 / React 19; active UI is reference HTML plus browser scripts, not the retained typed mock prototype.
- Catalog metadata has an opt-in read-only proxy. Normal semester search, selection, calendar, preferences and generation still use synthetic browser data and a heuristic generator.
- Spring Boot 4.1.1 / Java 21 uses read-only JDBC catalogs and the real `NativeSchedulingEngine`. Generation, manual checks, elective suggestions, preference extraction and explanations already exist. No authentication is implemented; CORS and RFC 9457 errors exist.
- Course prerequisites/corequisites are unverified conditions, not structured eligibility fields. No degree-audit, transcript, account or server-side saved-schedule API exists. Those prototype screens must be identified as demos.
- Keep the navigation and existing visual system. Replace the semester's production data flow with typed React/API components; retain the old semester implementation only in explicit demo mode.

## Implemented

1. Central JSON/Problem Details client, exact DTOs/validators and an allowlisted same-origin proxy using server-only `BACKEND_URL`.
2. Backend-backed term/catalog search, pagination, subject filter, details, course/section selection and manual checking.
3. Exact schedule request validation; real generation, alternative selection, scores/breakdowns, diagnostics, TBA handling and weekly calendar. Existing browser save capability stores identifiers/preferences, not live instructor names.
4. Real preference extraction/explanation where configured, with editable review and useful unavailable states; no heuristic AI in normal mode.
5. Explicit `FRONTEND_MODE=demo`; normal mode never falls back to mock success. Preserve degree/advisor prototypes with clear demo labels.
6. Unit/contract/proxy tests, backend suites, real PostgreSQL/native-engine/browser workflows A–E, CORS checks, builds and docs.

## Verification

Use a new isolated loopback PostgreSQL cluster and deterministic synthetic records through the production JDBC/catalog views. Never modify a shared database, call paid AI, or capture real instructor names. Record actual results and remaining capability gaps in HANDOFF.md and the final report.

Completed 2026-10-18: frontend `Tests 112 passed (112)`, lint exit 0, successful production build. Backend `Tests run: 801, Failures: 0, Errors: 0, Skipped: 40`; isolated PostgreSQL profile `Tests run: 45, Failures: 0, Errors: 0, Skipped: 0`; `BUILD SUCCESS`. Browser: 44 real frontend/Spring Boot/PostgreSQL/native-solver checks, 30 synthetic catalog checks, 228 explicit-demo degree/navigation checks, and 9 private-database browser checks without captures. Real workflows A–E executed. Frontend lint/tests/build and backend unit tests were rerun successfully before publication. See the [runbook and evidence](docs/frontend-backend-integration.md).

Remaining capabilities: no degree/transcript/advisor/account/server-save or structured prerequisite-eligibility API; those are honestly labeled rather than fabricated. The user's private serving database was connected read-only and exercised through actual catalog/detail/generation requests and the browser. Full ratings coverage and live paid Claude were not evaluated. Forty optional source/live/serving/private-fixture tests remain gated. No merge, cherry-pick or PR was performed.
