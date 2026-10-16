# HANDOFF.md

Live record of project progress. **Read it at the start of every session; update your module's section at the end.** Update rules are in [development-guide.md § 9](docs/development-guide.md#9-updating-handoffmd). In short: pull first, edit only your section, add a session-log line, record only real results, and never delete others' entries.

| | |
|---|---|
| **Last updated** | 2026-10-16 15:25 EDT, by a Claude Code session for Michael (@04michael4), on the local branch `reconcile/ai-pre-main` |
| **Current milestone** | Phase 1 ✅ on `main`. Phase 2 on `pre-main`: Marpu's `common` error handling, CORS and Schedule API (validation and mapping; 503 until a production catalog and engine exist). **Michael's AI module (live-verified, eval 19/20 = 95%) is merged with the latest `pre-main` (`9cd9cdf`) on the local branch `reconcile/ai-pre-main`.** Marpu's two PR #2 review findings (`24:00` accepted as `00:00`; a model reply of `{}` accepted as complete) are fixed there with regression tests: **460/460 tests pass.** Not pushed. |
| **Next milestone** | Michael reviews `reconcile/ai-pre-main`; then PR #2 (`integrate/ai-into-pre-main` → `pre-main`) is updated and Marpu reviews it. Sunny's engine branch and David's data branch follow through their own PRs. `main` stays untouched. |

## Repository state

| | |
|---|---|
| `main` | `098cf09` "docs: finalize API contract and apply Lehigh credit-load policy". Phase 1 was merged via PR #1 (`76a8310`). |
| AI branch | `feature/ai-preference-extractor`, created from `098cf09`. Step 1 is in the commit "feat(ai): implement validated preference extraction with dummy provider" (run `git log` for the hash), pushed to `origin/feature/ai-preference-extractor`. Step 2 is in the commit "feat(ai): integrate Claude API and validate live extraction" on top of it, also pushed. **Not merged into `pre-main` or `main`.** |
| `pre-main` | **Shared integration branch** (team decision, 2026-10-15). At `9cd9cdf` "feat(schedule): add validated schedule API and integration boundary" (Marpu), on top of `533633d` (`common` error handling and CORS). `main` must stay untouched. |
| Integration branch | `integrate/ai-into-pre-main`, pushed, **PR #2** into `pre-main` (open). At `4abcddb`, based on `pre-main` `533633d`: the AI merge, the shared-handler fix, docs, and the minimum-credit and student-text escaping fixes (195/195 tests at `4abcddb`). |
| Reconcile branch | `reconcile/ai-pre-main` (**local only, not pushed**): `integrate/ai-into-pre-main` (`4abcddb`) plus a `--no-ff` merge of `pre-main` `9cd9cdf`, with 11 conflicts resolved. Intended to update PR #2 after review. |
| Other branches | `integrate/engine-into-pre-main` and `feature/engine-core` (Sunny's engine, not yet in `pre-main`); `feature/data-cse007-snapshot` (David's CSE 007 database fixtures, no PR); `dev-marpu`; `feature/backend-foundation` (merged) |
| Toolchain verified | OpenJDK 21.0.12 (Homebrew), Maven 3.10.0 via wrapper, Spring Boot 4.1.1, macOS |

## Latest test results

**Review-fix run, 2026-10-16 15:10 EDT**, on the local branch `reconcile/ai-pre-main` after Marpu's two PR #2 findings were fixed, with no `ANTHROPIC_API_KEY`, `RUN_LIVE_AI`, `AI_PROVIDER` or `CATALOG_SOURCE` set, command `cd backend && ./mvnw -B clean test`: **`Tests run: 460, Failures: 0, Errors: 0, Skipped: 0`, BUILD SUCCESS (7.213 s)**. `ClaudeLiveEval` did not run; no real Anthropic call was made (a localhost fake only).

| Finding | Root cause | Fix | Tests |
|---|---|---|---|
| `24:00` accepted as `00:00` | `PreferenceAssembler` parsed times with `DateTimeFormatter.ofPattern("H:mm")`, whose default SMART resolver reads `24:00` as end-of-day and returns midnight. The single `H` also accepted `009:30`. | `ResolverStyle.STRICT` plus an explicit `H:mm`/`HH:mm` shape check; an invalid endpoint drops the preference, or the whole blocked time, with a warning | `PreferenceAssemblerTest` 22 → 104 |
| `{}` accepted as a complete reply | The SDK's typed parse turns a missing property into `null`, and `ClaudeExtraction.toRawPreferences()` turns that into an empty list | `ClaudeExtractionValidator` checks the raw JSON (`rawTextBlock().text()`) for every property and its type, derived from the record; a failure is `MALFORMED` (existing retry once, then 502) | `ClaudeExtractionValidatorTest` 24 (new), `AnthropicClaudeGatewayTest` 17 → 57, `ClaudeEndpointFakeServerIntegrationTest` 10 → 17 |

Both probes were also sent to the packaged app against a localhost stand-in for the Anthropic API (fake key): `{}` twice → 502 after exactly 2 model calls; `{}` then a valid reply → 200 after 2 calls; a partial reply or a blocked time without `end`, twice → 502 after 2 calls; a complete reply with empty course lists → 200 with the no-courses warning after 1 call; `24:00` as block start, block end, `earliestStart` and `latestEnd` → all dropped with warnings, and the one valid block is kept.

**Earlier: reconcile run, 2026-10-16 14:40 EDT**, on the local branch `reconcile/ai-pre-main` (`integrate/ai-into-pre-main` `4abcddb` merged with `pre-main` `9cd9cdf`), with no `ANTHROPIC_API_KEY`, `RUN_LIVE_AI`, `AI_PROVIDER` or `CATALOG_SOURCE` set, command `cd backend && ./mvnw -B clean test`: **`Tests run: 307, Failures: 0, Errors: 0, Skipped: 0`, BUILD SUCCESS (5.871 s).** `ClaudeLiveEval` did not run.

| Group | Tests |
|---|---|
| AI branch at `4abcddb` (all kept): AI module, `common`, health, app context | 195 |
| `pre-main` Schedule API: `ScheduleControllerTest` 86, `EngineMapperTest` 13, `SchedulingConfigurationTest` 9, `ScheduleUnavailableTest` 2 | 110 |
| **New regression tests** in `ClaudeEndpointFakeServerIntegrationTest`: a request naming no known course still returns `200` with a warning and `unresolved`; blocked-time days are de-duplicated and sorted by the AI layer | 2 |

The 195 + 158 (`pre-main`) tests overlap in 48 shared `common`/health/app tests, so the merge itself ran 305 before the 2 new tests.

**Live app check, 14:39 EDT** (packaged jar, `PORT=8093`, no API key, no database; no external calls):

| Mode | Result |
|---|---|
| Defaults (`AI_PROVIDER` none, catalog dummy) | Starts without a database. `GET /api/health` `200`. Extract `503`. Generate `503` (no `SectionCatalog`/engine registered) |
| `AI_PROVIDER=dummy` | Extract `200`, contract JSON with `"15:00"`-style times, no extra `ordered` field; unknown term `422`; generate still `503` |
| `AI_PROVIDER=claude` without a key | Extract `503` (never falls back to dummy) |
| Strict validation (generate) | `400` for weight `"3"`, `3.5`, `6`, missing or null; times `"9:00"`, `"25:00"`; start after end; empty or null days; `FUNDAY`; empty, blank or null course IDs; number as course ID; `maxCredits` 22; min above max; credits as a string; 11 optional courses. `422` for an unknown course or term |
| Strict validation (extract) | `400` for numeric `text`, blank `text` and malformed JSON |

Every error was `application/problem+json` with an `X-Request-Id` header.

### Earlier: AI integration run (before the `pre-main` Schedule API)


**Integration run, 2026-10-15 23:22 EDT**, on `integrate/ai-into-pre-main`, with no `ANTHROPIC_API_KEY`, `RUN_LIVE_AI` or `AI_PROVIDER` set, command `cd backend && ./mvnw -B clean test`: **`Tests run: 177, Failures: 0, Errors: 0, Skipped: 0`, BUILD SUCCESS (5.335 s).**

| Group | Tests |
|---|---|
| Marpu's `common`, unchanged: `GlobalExceptionHandlerTest` 21, `WebConfigTest` 20, `ConfiguredCorsTest` 3, `DisabledCorsTest` 1 | 45 |
| Shared: `HealthControllerTest` 2, `RegistrationBackendApplicationTests` 1 | 3 |
| AI module (all 109 from the feature branch kept; `PreferenceControllerTest` strengthened) | 109 |
| **New integration tests:** `PreferenceEndpointIntegrationTest` 7 (dummy 200, 400, 422, CORS allowed/blocked/errors), `PreferenceEndpointFailureIntegrationTest` 5 (429/502/502/503 + unique request IDs), `ClaudeEndpointFakeServerIntegrationTest` 8 (real Claude path vs. local fake API) | 20 |

**Before the fix** (the merge commit alone): 157 tests, **5 failures**. All were AI status checks returning 500 instead of 422/429/502/502/503, confirming the predicted handler conflict. `dependency:tree`: 0 conflicts; validation starter, `anthropic-java` 2.71.0, Jackson 3.1.5 and Jackson 2.21.5 each present once. `git diff --check` clean. No paid calls.

### Earlier: AI feature branch

**Run 2026-10-15 23:09 EDT** on `feature/ai-preference-extractor`, immediately before the step 2 commit, with no `ANTHROPIC_API_KEY`, `RUN_LIVE_AI` or `AI_PROVIDER` set, command `cd backend && ./mvnw -B clean test`:

```
Tests run: 4,  Failures: 0 -- course.CourseCatalogSelectionTest
Tests run: 2,  Failures: 0 -- health.HealthControllerTest
Tests run: 4,  Failures: 0 -- common.json.JsonConfigurationTest
Tests run: 17, Failures: 0 -- ai.AnthropicClaudeGatewayTest      (new: real SDK vs. local fake API)
Tests run: 10, Failures: 0 -- ai.AiConfigurationTest              (new)
Tests run: 9,  Failures: 0 -- ai.PreferenceControllerTest         (+2: 429, 502)
Tests run: 10, Failures: 0 -- ai.ClaudePreferenceExtractorTest    (new)
Tests run: 15, Failures: 0 -- ai.PreferenceAssemblerTest
Tests run: 4,  Failures: 0 -- ai.PreferenceExtractionServiceTest
Tests run: 14, Failures: 0 -- ai.CourseMentionResolverTest
Tests run: 22, Failures: 0 -- ai.AiEvalDatasetTest                (new: 20 eval cases + 2 checks)
Tests run: 1,  Failures: 0 -- RegistrationBackendApplicationTests
Tests run: 112, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS   (Total time: 4.637 s)
```

`ClaudeLiveEval` did not run. Run explicitly without the opt-in variables, it reports `Skipped: 1`, with no API call.

**Live app check, 22:26 EDT** (packaged jar, `PORT=8091`, no API key; no external calls):

| `AI_PROVIDER` | `POST /api/preferences/extract` |
|---|---|
| `claude` | `503`, and the log warns "AI_PROVIDER=claude but ANTHROPIC_API_KEY is not set". The app starts normally. |
| `dummy` | `200`, fixed example |
| `none` | `503` |

### Live Claude API verification (2026-10-15, `claude-haiku-5-5`, SDK 2.71.0, run by Michael with his own key)

**Single request, 22:50 EDT** (`AI_PROVIDER=claude`, app bound to 127.0.0.1:8091):
- Models API: HTTP 200, `claude-haiku-5-5` active, with structured outputs and effort `low` supported.
- Extraction: **HTTP 200, 16/16 checks**.
  - Courses `CSE-017`, `MATH-021`.
  - Hard blocked time Tue/Thu 15:00–18:00.
  - Soft `earliestStart` 10:00, weight 3.
  - Default credits 12–18, with warnings.
- 3.89 s (first request after startup), 1 Claude call, 3,542 input / 197 output tokens, about $0.00045.

**20-case evaluation, 23:01 EDT** (`ClaudeLiveEval`): **19/20 = 95%** (threshold 80%).
- 20 Claude responses, all `end_turn`: 0 re-asks, 0 refusals, 0 provider errors.
- 70,713 input / 3,787 output tokens: **about $0.009 in total**.
- 32.2 s overall, about 1.6 s per case (median 1.33 s, max 3.67 s for the long request).
- **Failed: `vague-time`.** "I'm not a morning person. CSE 140 and ECO 1." Courses and blocked times were correct, but no `earliestStart` was produced. This is model behavior: the prompt lists that phrase as an example, but also says not to invent times. A soft preference was omitted; nothing was invented. The expectation was left as it is.
- **Serious errors: none observed.**
  - 0 invented required courses (all 20 cases check an exact set).
  - All 4 blocked-time cases exact, and 6 cases correctly had none.
  - All times exact.
  - Credit cap and overload rules correct.
  - Both prompt-injection attempts resisted in the final output.

**Known limits of this verification:**
- One run only, so variation between runs is unmeasured.
- `ClaudeLiveEval` saves only failure reasons, not full responses.
- About 12 cases don't check for invented soft preferences (`absentSoft`).
- For the injection cases, "ignored by the model" and "set aside by Java as unresolved" look the same.
- Dummy catalog only.
- Costs use Haiku 5.5's list price ($0.10 / $0.50 per MTok, per the 2026-10-06 reference).

Results and logs were saved outside the repo, and no API key appears in them (checked).

### Earlier: Step 1 commit

**Run 2026-10-15 22:00 EDT** on `feature/ai-preference-extractor`, immediately before the step 1 commit, command `cd backend && ./mvnw -B clean test`:

```
Tests run: 4,  Failures: 0 -- course.CourseCatalogSelectionTest
Tests run: 2,  Failures: 0 -- health.HealthControllerTest
Tests run: 4,  Failures: 0 -- common.json.JsonConfigurationTest
Tests run: 7,  Failures: 0 -- ai.PreferenceControllerTest
Tests run: 15, Failures: 0 -- ai.PreferenceAssemblerTest
Tests run: 4,  Failures: 0 -- ai.PreferenceExtractionServiceTest
Tests run: 14, Failures: 0 -- ai.CourseMentionResolverTest
Tests run: 1,  Failures: 0 -- RegistrationBackendApplicationTests
Tests run: 51, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS   (Total time: 3.131 s)
```

**Live check, 21:57 EDT, same code** (packaged jar on `localhost:8080`):

| Case | Result |
|---|---|
| `AI_PROVIDER=dummy`: `POST /api/preferences/extract` | `200`, contract JSON with times as `"15:00"` |
| `AI_PROVIDER=none`: `POST /api/preferences/extract` | `503` |
| No env vars set (defaults: `none`, catalog `dummy`) | `503`; the app starts without a database |
| `CATALOG_SOURCE=database` (no database catalog exists yet) | Startup fails as intended: "required a bean of type 'CourseCatalog' that could not be found", and the process exits |
| `GET /api/health` | `200` |

Earlier the same evening (21:40, before the catalog change): unknown term → `422`, blank text → `400`.

**Checks:** `git diff --check` clean. No credentials or secret patterns in any of the 39 changed or new files. No `.env` files or build artifacts.

### Earlier: Phase 1 baseline

**Run 2026-10-15 16:45 EDT** (just before the Phase 1 commit), command `cd backend && ./mvnw -B clean test`:

```
Tests run: 2, Failures: 0, Errors: 0, Skipped: 0 -- in io.github.michael04.registration.health.HealthControllerTest
Tests run: 1, Failures: 0, Errors: 0, Skipped: 0 -- in io.github.michael04.registration.RegistrationBackendApplicationTests
Tests run: 3, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS   (Total time: 2.655 s)
```

**Manual verification, same session:** `./mvnw spring-boot:run` started in 0.489 s. Then:
```
$ curl -i http://localhost:8080/api/health
HTTP/1.1 200
Content-Type: application/json
{"status":"UP","service":"registration-backend"}
```
Also observed earlier the same day: an unknown path returns `404` and `POST /api/health` returns `405`, both with Spring Boot's default error JSON.

## Known bugs and blockers

| # | Item | Type | Owner | Status |
|---|---|---|---|---|
| 1 | ~~Module owners not yet assigned~~ | Blocker (coordination) | Team | Resolved 2026-10-15 (Michael AI, David Frontend & Database, Marpu Backend API, Sunny Scheduling Engine) |
| 2 | **Lehigh course data source not chosen.** Blocks real course search and scheduling. | Blocker | Data (_TBD_) | Open |
| 3 | ~~Phase 1 is committed and pushed on `feature/backend-foundation` but not merged to `main`~~ | Process | @04michael4 | Resolved 2026-10-15 (PR #1, `76a8310`) |
| 4 | No CI; tests run only locally | Gap | _TBD_ | Open |
| 5 | Mockito prints JDK "dynamic agent loading" warnings during tests | Cosmetic | — | Accepted (harmless) |

No known functional bugs.

## Important decisions

| Date | Decision | Where |
|---|---|---|
| 2026-10-15 | Java 21 + Spring Boot instead of Python + FastAPI | [ADR-0001](docs/decisions/0001-java-spring-boot-over-python-fastapi.md) |
| 2026-10-15 | Spring Boot 4.1.1 pinned; Maven Wrapper; minimal dependencies; hand-written health endpoint; package by feature | [architecture.md § 15](docs/architecture.md#15-phase-1-decisions-implemented) |
| 2026-10-15 | The AI proposes and explains; the deterministic engine alone decides validity | [architecture.md § 1](docs/architecture.md#1-system-overview) |
| 2026-10-15 | Base package `io.github.michael04.registration` (independent project) | [CLAUDE.md](CLAUDE.md) |
| 2026-10-15 | **Team-agreed** API contract: course search, preference extraction and schedule generation shapes, formats (`"FRIDAY"`, `"15:00"`, `"CSE-017"`), weights 0–5. Agreed, not yet implemented. |
| 2026-10-15 | **Credit limits** follow Lehigh's overload policy: hard cap 21 credits (team rule), default 12–18, normal load 17 (first semester) / 18, schedules over 18 flagged `overload: true` | [api-contract.md § Credit limits](docs/api-contract.md#credit-limits) | [api-contract.md](docs/api-contract.md) |
| 2026-10-15 | **Team-agreed** four-module split and owners: Michael (AI), David (Frontend & DB), Marpu (Backend), Sunny (Engine) | [README § Team](README.md#team-and-responsibilities) |

## Module ownership and dependencies

```mermaid
flowchart LR
    DATA[Data & Frontend<br/>catalog import] --> API[Backend API<br/>course endpoints]
    API --> ENG[Scheduling Engine]
    API --> AI[AI Integration]
    AI -.->|shares SchedulePreferences| ENG
    FE[Data & Frontend<br/>UI] --> API
```

| Module | Owner | Depends on | Depended on by |
|---|---|---|---|
| Backend API | _TBD_ (Phase 1: @04michael4) | — | everyone |
| Scheduling Engine | _TBD_ | Backend API (course data), `SchedulePreferences` contract | Frontend |
| AI Integration | _TBD_ | Backend API (course ID validation), `SchedulePreferences` contract | Frontend |
| Data and Frontend | _TBD_ | Backend API (endpoints), data source decision | — |

The engine **logic** can be built and unit-tested now, before the database or catalog exist, using hand-made test data.

---

## Backend API

**Owner:** Marpu (per the team assignments in README; Phase 1 foundation: @04michael4) · **Status:** ✅ Foundation/common available; 🟡 Schedule API layer implemented and tested on `feature/backend-schedule`, based on testing branch `pre-main`; production engine/catalog integration pending

**Completed**
- 2026-10-15: Spring Boot 4.1.1 / Java 21 project from Spring Initializr in `backend/`, with Maven Wrapper (Maven 3.10.0).
- 2026-10-15: `GET /api/health` → `{"status":"UP","service":"registration-backend"}` (`health` package).
- 2026-10-15: Tests: `HealthControllerTest` (2 × `@WebMvcTest`) and a context-load test. All passing; see [Latest test results](#latest-test-results).
- 2026-10-15: Root `.gitignore` (ignores `.env`/`.env.*`, keeps `.env.example`; verified with `git check-ignore`), `.env.example`, docs.
- 2026-10-15: Codex implemented `common`: centralized RFC 9457 Problem Details, sorted validation errors, safe application exceptions, request IDs, servlet fallback, and exact-origin CORS. Added `spring-boot-starter-validation` for Bean Validation and documented the error-contract change and `CORS_ALLOWED_ORIGINS`. Production changes stay within `common`, `pom.xml`, and `application.properties`; other modules are untouched.
- 2026-10-15: Codex implemented the Schedule API layer: `POST /api/schedules/generate`, nested/cross-field validation, original-list optional limit (10), stable required/optional normalization, term/status section filtering, component/link/time mapping, unchanged score/breakdown and result ordering, actual-credit overload flag, and validated `scheduling.node-cap`. No solver algorithm or production sample results were added. Reuses the existing global advice; no duplicate exception handler.

**Verification — 2026-10-15 22:01 EDT**
- Phase 0: read `docs/development-guide.md`, `AGENTS.md`, `CLAUDE.md`, and this handoff in order; `PLANS.md` is absent. Fetched `origin`, confirmed a clean tree, checked that the task branch did not exist, then created and verified `feature/backend-common` from `origin/main` at `098cf092d49379ec32c634bd6e6c1432229fd7bc`. Local `main` matched `origin/main`, so no merge was needed. Git metadata writes required sandbox approval. No commit, push, or PR was made.
- Baseline: `./mvnw -B test` via Git Bash and JDK 21 → `Tests run: 3, Failures: 0, Errors: 0, Skipped: 0`; `BUILD SUCCESS`.
- Final command from `backend/`, in PowerShell: `$env:JAVA_HOME='C:\Program Files\Java\jdk-21'; & 'C:\Program Files\Git\bin\bash.exe' ./mvnw -B test` → `Tests run: 48, Failures: 0, Errors: 0, Skipped: 0`; `BUILD SUCCESS` (2026-10-15 21:59 EDT). Tests cover nested/body/parameter validation, malformed JSON, missing input, 404/405/406/415/422/429/500/502/503, safe errors, request IDs, default/custom/disabled CORS, and rejected origins/methods/headers. They need no DB, AI service, or secrets.
- Live: `./mvnw -B spring-boot:run '-Dspring-boot.run.arguments=--server.port=0'`, using the same JDK/Git Bash setup, started on port 58982. `curl.exe -i` verified health GET/HEAD 200; unknown route 404; POST health 405 with `Allow: GET`; XML Accept 406; allowed JSON POST preflight 200; untrusted origin 403; allowed-origin headers on a 404; `/error` fallback 500 even with HTML Accept. Error bodies are `application/problem+json`, include `type: about:blank`, and match `requestId` to `X-Request-Id`; query strings are omitted.
- Initial feature test run: `Tests run: 48, Failures: 22, Errors: 0, Skipped: 0` because Spring leaves the problem type unset by default. Resolved by setting `about:blank` explicitly in the shared renderer; all final tests pass.
- **Environment issue:** Windows `mvnw.cmd` fails before Maven starts with `Cannot index into a null array`; the Unix wrapper via Git Bash works. The wrapper files were left unchanged, and README documents the workaround. Existing Mockito dynamic-agent notices remain.
- **Documentation discrepancy:** the repository-state/ownership summaries above and some older design notes are stale. Git shows the foundation merged in `76a8310`; README assigns owners. This entry records the discrepancy without replacing other modules' history. The user explicitly assigned CORS; it is implemented while preserving the frontend's option to use a same-origin proxy.
- **Review note:** error JSON and CORS configuration are shared interfaces. Frontend/AI/backend consumers should review the documented contract when a PR is authorized. The API endpoints for course search, scheduling, and AI remain planned.
- **Pre-push recheck — 2026-10-15 22:26 EDT:** the same `./mvnw -B test` command via Git Bash/JDK 21 returned `Tests run: 48, Failures: 0, Errors: 0, Skipped: 0` and `BUILD SUCCESS`. `git diff --check` is clean. Target: `origin/pre-main`, explicitly requested by the user.

**In progress**
- Common implementation and the authorized push are complete: `origin/pre-main` contains `533633d`.
- 2026-10-15 23:18 EDT: Codex claims Schedule API on `feature/backend-schedule`, created and verified from freshly fetched `origin/pre-main` (`533633d`), following the user's testing-branch direction. Clean tree verified before edits; baseline `./mvnw -B test` via Git Bash/JDK 21: `Tests run: 48, Failures: 0, Errors: 0, Skipped: 0`, `BUILD SUCCESS`.
- Integration gap: no engine implementation or section catalog exists in the fetched repository. Section 7 describes a planned algorithm; README examples lack final scores, total credits, and component links. Preparing the API boundary and tests without implementing or fabricating a solver. Shared preference types will retain the shapes already introduced on `origin/feature/ai-preference-extractor`; consuming owners must review the integration boundary.

**Schedule API verification — 2026-10-15 23:51 EDT**
- Branch: `feature/backend-schedule`, base `origin/pre-main` at `533633dedc6ac885903f1a6853d7ed5db953c6fc`. Fetched origin, read the required guides/contracts, checked existing branches and the clean worktree, then created/switched/verified the task branch before edits. `PLANS.md` is absent. The user's prior testing-branch instruction takes precedence over the guide's default `main` integration workflow. No commit, push, merge or PR for this task.
- Final command from `backend/`: `$env:JAVA_HOME='C:\Program Files\Java\jdk-21'; & 'C:\Program Files\Git\bin\bash.exe' ./mvnw -B verify` → **`Tests run: 158, Failures: 0, Errors: 0, Skipped: 0`**, **`BUILD SUCCESS`**, 2026-10-15 23:49 EDT. Includes all 48 existing tests and 110 new tests: 86 controller/service tests, 13 mapper tests, 9 configuration tests and 2 full-context unavailable-integration tests. Executable JAR packaging succeeded. Tests use synthetic fixtures/mocked solver; no DB or live AI calls.
- Tests cover every required range/boundary, invalid requests never reaching catalog/engine, 10/11 optional entries (including duplicates/overlaps), all five weights, strict JSON types and times, required/optional deduplication, closed lectures/labs/recitations, UNKNOWN status, term filtering, component/link preservation, TBA/online empty meetings, stable preparation, score precision/order, overload at 18/19/21, normal empty results, 422/500/503 errors, cap defaults/custom values and invalid startup config.
- Live: `./mvnw -B spring-boot:run '-Dspring-boot.run.arguments=--server.port=0'` via Git Bash/JDK 21 started on port 54069. `curl.exe -i` verified health 200; empty required list 400 with nested field errors; 11 overlapping optional entries at weight 0 still 400; valid scheduling request 503 because production adapters are absent. Error bodies use the shared Problem Details format and matching request IDs; allowed-origin CORS headers present on 503. Server stopped gracefully after checks. A live successful schedule cannot be verified until the native engine and catalog exist.
- Failures found and resolved: initial 146-test run had one test-only 503 wording mismatch; expanded tests caught Jackson's numeric/boolean-to-string coercion (2 failures in an 85-test targeted run), then lenient `24:00` normalization (1 failure in a 156-test full run). Strict coercion/time settings fixed both parsing defects. Subsequent targeted 86-test run and final 158-test build passed. Mockito's existing dynamic-agent warning and Windows wrapper workaround remain unchanged.
- Files created: `schedule/{ScheduleController,ScheduleService,EngineMapper,GenerateScheduleRequest,GenerateScheduleResponse,ScheduleRequestValidator,ValidScheduleRequest,SchedulingProperties,SchedulingConfiguration}.java`, `schedule/integration/SchedulingEngine.java`; `course/{CourseCatalog,CourseSummary,SectionCatalog,CatalogSection}.java`; `common/json/JsonConfiguration.java`; `common/preferences/{SchedulePreferences,HardConstraints,SoftPreferences,Weighted,GapsPreference,TimeBlock,CreditLimits,Weights}.java`; four `schedule` test classes (`ScheduleControllerTest`, `EngineMapperTest`, `SchedulingConfigurationTest`, `ScheduleUnavailableTest`). Modified: `application.properties`, `.env.example`, README, this handoff, `docs/api-contract.md`, `docs/architecture.md`. No build dependencies, engine algorithm, AI implementation, frontend, or persistence files changed.
- Shared-interface notes: preference/catalog Java shapes were taken from AI branch `c6c7121`, without merging its implementation. Request validation/JSON parsing are stricter. Schedule credit bounds remain the fixed 1–21 public contract, centralized in shared `CreditLimits`; the old contract's request to configure those numbers has been clarified. Reversed credit bounds now return 400 as explicitly required by this task (old contract said 422). Frontend/AI/engine/data owners should review these shared changes when integration is authorized.
- Remaining work: obtain Sunny's actual native solver interface and finalized scoring/component-link rules; implement its `SchedulingEngine` adapter, supply `CourseCatalog`/`SectionCatalog` implementations, then run real-engine/catalog end-to-end tests. The provisional adapter boundary preserves metadata as supplied but cannot prove compatibility with an unavailable engine schema. Full Schedule API Definition of Done is **not yet met**; API-layer checks pass. No user answer identifying another engine location was received during this session.

**Pre-push recheck — 2026-10-16 11:34 EDT**
- User authorized committing all pending changes and pushing to `pre-main`. Fetched `origin/pre-main`; it matches the feature branch base (`533633d`), allowing a normal fast-forward push.
- `./mvnw -B test` via Git Bash/JDK 21: `Tests run: 158, Failures: 0, Errors: 0, Skipped: 0`; `BUILD SUCCESS`. Initial sandbox attempt could not create `C:\.m2\repository`; the approved run outside the sandbox used the existing user cache and passed. `git diff --check` is clean. Production engine/catalog adapters remain pending as documented above.

**Integration review — 2026-10-16**
- Read the fetched engine integration changes at `origin/integrate/engine-into-pre-main` (`9a35770`) without merging them into this branch. That branch already sets a 1,000,000 node-cap default and an eight-entry optional-course limit; `origin/pre-main` remains at `9cd9cdf` with 200,000 and ten.
- Review concern: `NativeSchedulingEngine.parentCrn` converts blank/self-referencing lecture links to null, turning malformed linked sections into standalone sections. Prefer rejecting those values under the documented lecture-CRN-or-null contract; the native `SectionOption` already rejects them. No adapter change made on this branch, where the adapter is absent.
- Updated `ScheduleUnavailableTest`'s comment to describe unavailable integrations without asserting that the solver is absent. Comment/docs only; tests were not rerun. Production catalogs remain pending; earliest-start remains a soft preference in the API contract. No merge, commit or push performed in this review.

**PR #2 review — 2026-10-16 (head `9183a29`)**
- Reviewed the current AI integration against `pre-main` (`9cd9cdf`), including the reconciliation of shared validation/JSON/catalog files. Used an isolated archive under the system temporary directory, preserving the current checkout and its existing edits.
- Existing suite, `./mvnw -B test` via Git Bash/JDK 21: `Tests run: 307, Failures: 0, Errors: 0, Skipped: 0`; `BUILD SUCCESS`. No paid AI calls; gateway tests used local fakes.
- P2: `PreferenceAssembler.parseTime` uses SMART time resolution, so model output `24:00` silently becomes `00:00`. Reproduced a Monday block `24:00`–`10:00` becoming `00:00`–`10:00`, plus an earliest-start preference of midnight, with no warnings. Use strict resolution and add regression coverage.
- P2: `AnthropicClaudeGateway.interpret` accepts a parsed but schema-incomplete `{}` as COMPLETE. Required list fields become null, and `ClaudeExtraction.toRawPreferences` substitutes empty lists, bypassing malformed-output retry/failure handling. Validate required output fields before marking the reply complete; test missing required fields separately from explicit empty lists/null optional values.
- Two temporary regression probes in the isolated copy: `Tests run: 2, Failures: 2, Errors: 0, Skipped: 0`; `BUILD FAILURE`, confirming both findings. Recommendation: request these fixes before merging. No application source change, merge, commit, push or GitHub review submission in this review.

**PR #2 follow-up review — 2026-10-16 (head `4b288b7`)**
- Reviewed the fix commit against previously reviewed `9183a29`, using another isolated temporary archive. Both prior P2 findings are resolved: strict time resolution plus an ASCII time-shape check reject `24:00` with warnings; raw JSON presence/type validation rejects incomplete replies before the SDK's typed conversion. Required-property checks cover nested blocked times and preserve explicit null optional fields and legitimate empty lists. Retry-once/502 behavior is exercised through local fake-server integration tests.
- PR suite, `./mvnw -B clean test` via Git Bash/JDK 21: `Tests run: 460, Failures: 0, Errors: 0, Skipped: 0`; `BUILD SUCCESS` (27.014 s). Re-ran the exact two probes from the initial review, copied only into the isolated temporary tree: `Tests run: 2, Failures: 0, Errors: 0, Skipped: 0`; `BUILD SUCCESS` (9.413 s). No paid AI calls.
- `git diff --check` on the fix commit is clean. Confirmed GitHub's current PR head still matches `4b288b7`. No new blocking findings; recommend approval of this revision. This supersedes the prior request-changes recommendation above. Production catalog integration remains separate work. No GitHub review submission, merge, commit or push; only this local handoff record updated.

**PR #3 update — 2026-10-16 15:29 EDT**
- User explicitly requested pushing these local changes into PR #3. Its head is `pre-main` and its base is `main`. Fetched head `fef4857` (which includes the merged PR #2 fixes), created `feature/backend-pr3-review-notes` from it, and carried over the review notes and test-comment correction while preserving the newer AI handoff entries. Original local edits are retained in the `PR3 local review notes backup` stash. An initial patch check found moved handoff context; transferred the same additions at their current section locations.
- Pre-push command, `./mvnw -B -Dtest=ScheduleUnavailableTest test` via Git Bash/JDK 21: `Tests run: 2, Failures: 0, Errors: 0, Skipped: 0`; `BUILD SUCCESS` (11.142 s). Comment/documentation changes only. Prepared a normal fast-forward push to the existing PR head using `HEAD:refs/heads/pre-main`, under the user's explicit instruction. No promotion or merge into `main` is part of this update.

**Next recommended**
1. ~~Review Phase 1, then open a PR from `feature/backend-foundation` into `main` and merge it.~~ Resolved 2026-10-15: Git records the merge as `76a8310`.
2. Add GitHub Actions CI running `./mvnw test` on PRs.
3. ~~`common`: global error handling with RFC 9457 Problem Details, plus `spring-boot-starter-validation`. This is a contract change, so update [api-contract.md](docs/api-contract.md).~~ Implemented and verified 2026-10-15 on `feature/backend-common`; test on `pre-main` and review the contract before promotion to `main`.
4. Persistence phase: PostgreSQL, a migration tool and the `course` package, once the data source is decided.

**Blockers:** no blocker for the completed foundation/common work. Production Schedule API integration is blocked on the missing engine and catalog adapters; persistence still depends on the data-source decision.

---

## Scheduling Engine

**Owner:** Sunny (per the [README team table](README.md#team-and-responsibilities)) · **Status:** 🟡 Solver implemented and unit-tested, and integrated into `/api/schedules/generate` on `integrate/engine-into-pre-main` (PR into `pre-main` pending review). The live endpoint still returns 503 until a production catalog exists.

**Scope:** `engine/` only. Per the team-agreed README work plan, the `schedule/` package and `POST /api/schedules/generate` belong to the Backend API module (Marpu). See the discrepancy note below.

**Completed**
- 2026-10-15: Engine input/output records and the `ScheduleSolver` interface: `Meeting`, `SectionOption`, `CourseOption`, `Constraints`, `SoftWeights`, `ScheduleProblem`, `ScoredSchedule`, `ScheduleResult`. Compact constructors validate the input and copy collections defensively. Committed as `d37eec8` (not pushed).
- 2026-10-15: **Linked sections** (owner decision): one lecture may allow several labs or recitations, and a lecture with linked sections must be taken with exactly one per component type (`SectionOption.component` + `parentCrn`).
- 2026-10-15: **Infeasible input** (owner decision): return diagnostics plus "make X optional" suggestions. No contract change.
- 2026-10-15: **`BacktrackingScheduleSolver`**. It expands lectures into lecture+lab bundles and pre-filters them, then searches with a conflict table, fewest-options-first ordering, credit pruning and branch-and-bound. It also has a deterministic node cap (default 1,000,000), top-K, diagnostics and suggestions. Supporting pieces:
  - `ScheduleScorer`: the scoring formula, using exact integer arithmetic;
  - `Bundle` (package-private);
  - `Meeting.overlaps`;
  - `CourseOption.excluding`, which removes closed sections safely for the caller.
  - Documented in [architecture.md § 7–8](docs/architecture.md#7-scheduling-engine), with measured search sizes.
- 2026-10-15: Engine tests, plain JUnit 5 with no Spring:
  - `MeetingTest` (9);
  - `ScheduleProblemTest` (14);
  - `CourseOptionTest` (4);
  - `BacktrackingScheduleSolverTest` (25), which includes the README worked example, lecture/lab bundles, every diagnostic, determinism under shuffled input, and 200 seeded random catalogs. Each catalog is checked by an independent validity checker and against a **brute-force solver**: the returned schedules must be exactly the true top K.
  - Also: 7 × 10 sections solving in under 1 s (`assertTimeout`).
- 2026-10-16: **Manual mode support: `SelectionChecker`** (owner decisions: grey out + warn on bad drops; lecture and lab dragged separately, with the lab prompted). It checks a hand-picked set of CRNs and returns:
  - conflicts and missing pieces, each with a message the UI can show;
  - for every unplaced section, whether it fits, why not, and whether it completes a placed lecture;
  - credits and a score computed the same way as for generated schedules.

  `SelectionCheckerTest` (12) includes a cross-check: every schedule the solver generates passes the checker with zero issues and an identical score. Documented in architecture.md § 7 "Manual mode".
- 2026-10-16: **Elective recommendations: `ElectiveAdvisor`** (owner chose "both"):
  - `explainElectives` says, for each typed elective, whether it's in the suggestions, and if not, why and how to fix it (section swap, free a blocked time, raise max credits, take a required course later).
  - `suggestElectives` ranks candidate courses that fit a given schedule.
  - **Solver ranking change:** on an equal score, the schedule with more of the student's electives now ranks first. This fixes electives being dropped at a weight of 0; the new test fails without the fix.
  - Flow recorded: suggested mode with required courses → add electives in manual mode (no limit) or type them up front (≤ 8). `ElectiveAdvisorTest` (11). Documented in architecture.md § 7 "Electives".

**In progress:** Sunny. `feature/engine-core` is done. It has been merged into `integrate/engine-into-pre-main` (from `origin/pre-main` at `9cd9cdf`), following the integration process in development-guide.md. That branch is waiting for a PR review.

**Integration (2026-10-16, `integrate/engine-into-pre-main`)**
- Merged the engine into the latest `pre-main`. Doc conflicts were resolved by keeping both sides.
- **Adapter:** `schedule/integration/NativeSchedulingEngine` (`@Component`) implements Marpu's `SchedulingEngine` with `BacktrackingScheduleSolver(nodeCap)`. It's translation only. Catalog data the engine rejects becomes a safe 500.
- **`lectureLink` defined** as the lecture's CRN (`null` for lectures), matching David's `section_link`. Updated the `CatalogSection` Javadoc and api-contract.md (rule 6 and the change log). Marpu's test fixtures used `"lecture-crn:1"`; they now use real CRNs.
- **Closed-section fix:** `EngineMapper` now removes closed sections with the engine's `CourseOption.excluding`, so a lecture whose labs are all closed is removed too. New `EngineMapperTest` cases fail without the fix. One existing test expected the old behavior and was split and updated.
- **Tests:**
  - `NativeSchedulingEngineTest` (5);
  - `ScheduleEngineIntegrationTest` (4): the full app with a test catalog built from David's CSE-007 snapshot plus one synthetic course. It covers lecture + exactly one lab, ranking by score (98, then 95), a lecture whose only lab is closed, `includeClosedSections`, and a blocked-time diagnostic.
  - `./mvnw -B test` → `Tests run: 221, Failures: 0, Errors: 0, Skipped: 0`, BUILD SUCCESS.
- **Live:** `spring-boot:run`, then `curl`: health 200. `generate` 503, as designed, because no production `CourseCatalog`/`SectionCatalog` exists on `pre-main`.

**Merge notes for `pre-main`** (kept for history)
- ~~No `SchedulingEngine` implementation registered~~ Resolved 2026-10-16: `NativeSchedulingEngine`.
- ~~`lectureLink` meaning unconfirmed~~ Resolved 2026-10-16: the lecture's CRN; needs review by Marpu and David.
- ~~Closed labs could leave a lecture without its lab~~ Resolved 2026-10-16 in `EngineMapper`.
- ~~Marpu's review: a blank or self-referencing `lectureLink` was turned into a standalone section~~ Resolved 2026-10-16: it's now rejected as malformed (`IllegalStateException` → safe 500) in both `EngineMapper` and `NativeSchedulingEngine`. New tests fail without the fix (4 failures).
- **Checked 2026-10-16:** `origin/pre-main` (`7ca189e`) and `main` still keep a lecture whose only lab is closed. A throwaway probe on `pre-main` returned `["12275"]` (the lecture alone) where it should return nothing. The fix is on this branch; merging the PR fixes `pre-main`.
- ~~**Node cap:** `pre-main` defaults to 200,000 vs. the engine's 1,000,000~~ Resolved 2026-10-16: the default is now **1,000,000** (`application.properties`, `.env.example`, README, contract).
- ~~**Optional-course limit:** 10~~ Resolved 2026-10-16: lowered to **8** (`ScheduleRequestValidator.MAX_OPTIONAL_COURSES`, also used by `EngineMapper`; contract updated).

**Owner decisions, 2026-10-16** (recorded in architecture.md § 7 and api-contract.md)
- Two modes: **suggested** (`maxResults: 4`, sent by the frontend) and **manual** (drag and drop, using `SelectionChecker`).
- Manual mode: bad drops are greyed out and warned about, not blocked. Lecture and lab are dragged separately, with the lab prompted.
- **Optional-course limit 8**; **node cap 1,000,000**.
- Earliest start, latest end and days off stay **soft**. The score caps stay at **300/600 min**.
- The major's required courses come from **one demo major entered by hand** (data: David; endpoint: Marpu).

**Backend work done for the team, 2026-10-16** (at the engine owner's request, on `feature/backend-catalog-endpoints`; it touches the Backend API and Data areas, so **Marpu and David should review the PR**):
- **Demo section catalog** `course/DummySectionCatalog`: made-up sections (labs, recitations, a closed section, clashes) under the default `CATALOG_SOURCE=dummy`, so `generate` returns real schedules with no database. In `database` mode, PR #5's PostgreSQL catalogs replace it; no overlap.
- **Endpoints 3–4** (course search and detail; sections also carry `componentType`, `lectureLink`, `credits`).
- **Endpoint 8** `/api/schedules/check` (manual mode).
- **Endpoint 9** `/api/schedules/suggest-electives`.
- **`electiveAdvice`** in `generate`.
- **Engine:** when the minimum credits can't be reached, diagnostics now say how many credits to add or what to lower the minimum to.
- `ScheduleUnavailableTest` now runs with `CATALOG_SOURCE=none` plus a course catalog only, keeping its purpose: safe 503 without section data.
- **Live verification** (`AI_PROVIDER=dummy`): health 200; AI extract 200; `generate` with the dummy AI's preferences returns no schedules, because CSE-017 + MATH-021 total only 7 credits against the default minimum of 12, and now says how to fix it; search, detail, check and suggest all return 200 with correct content.
- ~~**Merging with PR #5**~~ Resolved 2026-10-16 on `integrate/catalog-endpoints-into-pre-main`, which merges PR #5 and this branch into `pre-main` with both adjustments below applied; 683 tests, 0 failures, 10 opt-in skipped. Original note: the code merges cleanly, and the combined suite gives 683 tests, 1 failure, 10 skipped. Whichever PR lands second must make two small changes:
  1. `README.md`: the `DB_URL`/`CATALOG_SOURCE` rows conflict. Keep PR #5's `DB_URL` row and database description, and add "`dummy` now includes demo sections".
  2. `DefaultModeStartsWithoutDatabaseTest` asserts no `SectionCatalog` in default mode. Change it to `hasSize(1).containsOnlyKeys("dummySectionCatalog")`; the "no database objects" intent is unchanged.
- **Still needed:** the frontend (David). It should send `maxResults: 4`, show `diagnostics` and `electiveAdvice`, and use `/check` + `/suggest-electives` for manual mode.

**Needs team agreement** (details in architecture.md § 7)
1. **Marpu:** review the adapter, the `EngineMapper` closed-section change and the `lectureLink` definition (contract change); also the scoring formula and the node-cap default.
2. **David:** the production `SectionCatalog` must set `lectureLink` = the lecture's CRN for each lab or recitation (from `section_link`), and `null` for lectures. Labs carry 0 credits, the lecture carries the course credits. Note: earliest start and days off are **soft** preferences. The data README's "no classes before 10 yields no complete pair" holds only if the team wants those as hard filters; the engine ranks such schedules lower instead.
3. **Michael:** `integrate/ai-into-pre-main` is based on the older `pre-main` (`533633d`). Whichever PR lands second has to merge the other; see the trial-merge notes in the PR.
4. **Marpu:** add `POST /api/schedules/check` (proposed in architecture.md § 7 "Manual mode") and a `check` method on the `SchedulingEngine` adapter. **David:** the frontend's two modes: suggested (`generate` with `maxResults: 4`), and manual (calendar with every section of the student's courses, grey-out and highlight from `/check`). Manual mode also needs `GET /api/courses/{courseId}` (endpoint 4) for section times.
5. **Whole team, scope change (owner chose the demo-sized version on 2026-10-16: one major, requirements entered by hand):** the owner wants manual mode to list **the major's required courses automatically**. The README currently puts degree requirements out of scope. It needs degree-requirement data per major (David), the student's major and completed courses as input (frontend/AI), and an endpoint that returns the courses still required and offered this term (Marpu). A hand-entered requirement list for one demo major would be a feasible hackathon version. Not engine work.
6. **Marpu:** expose elective recommendations by adding `electiveAdvice` to the `generate` response and adding `POST /api/schedules/suggest-electives` (candidates from course search). Proposed in architecture.md § 7 "Electives".

**Next recommended**
1. Review and merge `integrate/engine-into-pre-main` → `pre-main`.
2. A production `CourseCatalog`/`SectionCatalog` (David/Marpu). After that, `generate` returns real schedules with no engine changes.
3. After the team agrees: tune the score caps (300/600 min), choose the node-cap default, and decide the optional-course limit.

**Blockers:** none for the engine. A live demo needs a production catalog.

**Discrepancies found 2026-10-15** (flagged, not edited, because they're outside this module):
- CLAUDE.md (module table) and architecture.md § 4 list `schedule` under the Scheduling Engine module, but the README team plan gives it to Backend API (Marpu). The owners should pick one and fix the other docs.
- The repo header, the *Repository state* section and the README "Current phase" line still say Phase 1 isn't merged and `main` is at `8ea194c`. In fact PR #1 was merged, and `main`/`origin/main` are at `098cf09`. Module owners in the *Module ownership* table here are also still _TBD_.

---

## AI Integration

**Owner:** Michael (@04michael4) · **Status:** ✅ Steps 1 and 2 committed and pushed on `feature/ai-preference-extractor`. **Live-verified: 1 request 16/16 checks; eval 19/20 = 95%.** In PR #2 (`integrate/ai-into-pre-main`); merged with the latest `pre-main` Schedule API on the local branch `reconcile/ai-pre-main` (307/307 tests), not pushed yet.

**Completed** (2026-10-15, branch `feature/ai-preference-extractor`)
- `POST /api/preferences/extract`: request validation (400), unknown term (422, checked before any AI call), AI unavailable (503), invalid AI output (502).
- `PreferenceExtractor` interface with providers chosen by `AI_PROVIDER`: `none` (default, 503) and `dummy` (fixed example, no key).
- `CourseMentionResolver`: "CSE 17", "cse017", "CSE-017" become `CSE-017`; title nicknames like "Calc 1" become `MATH-021`. It only resolves when exactly one course matches; otherwise the mention goes to `unresolved`. Course IDs never come from the AI.
- `PreferenceAssembler`: validates everything from the AI. Defaults 12–18, caps at 21, overload warning above 18, drops invalid times and days with warnings, clamps weights to 0–5, drops weight-0 preferences.
- **Shared stand-ins for Marpu to take over:**
  - `common/preferences/` (`SchedulePreferences` records, `CreditLimits`, `Weights`);
  - `course/CourseCatalog` and `DummyCourseCatalog` (8 fake courses, term `2027-SPRING`);
  - `common/json/JsonConfiguration`, which writes times as `"HH:mm"`.
- Added `spring-boot-starter-validation` to `pom.xml`.
- Tests: 40 new (resolver 14, assembler 15, service 4, controller 7).
- Cleanup, 2026-10-15 21:57:
  - `DummyCourseCatalog` is now active only when `app.catalog.source=dummy` (env `CATALOG_SOURCE`, the default), so it can't conflict with a database catalog. `CourseCatalogSelectionTest` (4) proves exactly one catalog exists for each setting.
  - `JsonConfigurationTest` (4) proves the contract's `SchedulePreferences` JSON reads and writes losslessly, with `"HH:mm"` times.
  - README "Michael: AI Integration" now matches the code, and David's section explains how to replace the dummy catalog.

**Notes for teammates** (shared stand-ins created on this branch; please review them in the PR)
- **Marpu:**
  - Take over `common/preferences/`, `course/CourseCatalog`, `course/CourseSummary` and `common/json/JsonConfiguration`, rather than writing new versions.
  - `spring-boot-starter-validation` is already in `pom.xml`.
  - The records enforce 1–21 credits with `@Max`, but nothing checks `minCredits ≤ maxCredits` or start-before-end on incoming requests. Your generate endpoint needs those checks.
  - `@WebMvcTest` needs `@Import(JsonConfiguration.class)`.
- **David:**
  - Annotate your catalog with `@ConditionalOnProperty(name = CourseCatalog.SOURCE_PROPERTY, havingValue = "database")` and run with `CATALOG_SOURCE=database`. Steps are in README, David's Part A, step 5.
  - The frontend can use `AI_PROVIDER=dummy` now.
  - Times arrive as `"15:00"`; days as `"FRIDAY"`, sorted Monday to Sunday.
- **Sunny:** nothing to integrate yet. Your engine shouldn't use `SchedulePreferences` directly; Marpu's `schedule` package converts it into your engine's own records. Weights are 0–5, and a preference with weight 0 is omitted.

**Step 2: Claude provider** (2026-10-15, uncommitted)
- **Implemented and tested offline:**
  - `com.anthropic:anthropic-java` **2.71.0**, pinned. Jackson 2.21 from the SDK sits next to Boot's Jackson 3.1, with no conflicts in `dependency:tree`.
  - `ClaudePreferenceExtractor`, `ClaudeGateway` / `AnthropicClaudeGateway`, `ClaudeExtraction` (the structured-output schema), `ClaudeProperties`, and the new exceptions `AiRateLimitedException` (429) and `AiProviderException` (502).
  - System prompt: `backend/src/main/resources/ai/preference-extraction-system-prompt.md`.
  - `AI_PROVIDER=claude`. Without a key the app starts and returns 503; it never falls back to dummy.
  - Model `claude-haiku-5-5` by default (`ANTHROPIC_MODEL`), effort `low`, 20 s timeout, 1 SDK retry, one application re-ask for truncated or malformed output, refusal final.
  - Eval set `backend/src/test/resources/ai-eval/cases.json` (20 cases), checked offline by `AiEvalDatasetTest`.
  - The live runner `ClaudeLiveEval` runs only on explicit request.
- **Verified only with fakes:** request shape, response parsing, refusal and truncation handling, and the 429/502/503 mapping.
- **Live-verified 2026-10-15:** see [Latest test results § Live Claude API verification](#live-claude-api-verification-2026-10-15-claude-haiku-5-5-sdk-2710-run-by-michael-with-his-own-key). Eval 19/20; the only failure is `vague-time`.

**In progress:** _none_. Step 2 is complete.

**Integration notes for merging into `pre-main`** (originally checked read-only against `origin/pre-main` at `533633d`). **All four were resolved 2026-10-15 on `integrate/ai-into-pre-main`**; see the resolution after the list.
1. ~~⚠️ **Error-handler conflict, which must be fixed during integration.**~~ **Resolved:** the AI exceptions now use the shared types, with no change to `GlobalExceptionHandler`. Original note: `pre-main`'s `common/GlobalExceptionHandler` has a catch-all `@ExceptionHandler(Exception.class)` that always returns **500** and ignores `@ResponseStatus`. Once merged, the AI exceptions would all become 500 instead of 422 / 429 / 502 / 503:
   - `ai.UnknownTermException` (422)
   - `ai.AiRateLimitedException` (429)
   - `ai.AiProviderException` (502)
   - `ai.AiOutputInvalidException` (502)
   - `ai.AiUnavailableException` (503)

   `pre-main` also has its own `common.AiUnavailableException`, which is a different class from `ai.AiUnavailableException`.

   **Options** (agree with Marpu):
   - (a) The catch-all honors `@ResponseStatus` via `AnnotatedElementUtils.findMergedAnnotation(ex.getClass(), ResponseStatus.class)`.
   - (b) Add explicit handlers for the AI exceptions.
   - (c) Move the AI exceptions onto `common` types, adding 429/502 types there.

   **Safety net:** after a merge, `PreferenceControllerTest`'s 422/429/502/503 tests will fail if this isn't resolved, because `@WebMvcTest` loads controller advice.
2. **Overlapping files**, expect merge conflicts: `backend/pom.xml` (both add `spring-boot-starter-validation`, so keep one), `application.properties` and `.env.example` (keep both sides' keys), and `HANDOFF.md`, `README.md`, `docs/api-contract.md`, `docs/architecture.md` (combine both sides).
3. **Error body format.** `pre-main` turns on Problem Details (`spring.mvc.problemdetails.enabled=true`). The AI exceptions' messages are generic and safe to show as `detail`.
4. ~~**Docs still say PRs go to `main`.**~~ **Resolved:** `CLAUDE.md`, `docs/development-guide.md` and `README.md` now say PRs target `pre-main` and `main` stays untouched.

**Resolution (2026-10-15, `integrate/ai-into-pre-main`, local)**
- **422:** `UnknownTermException` extends `common.UnprocessableException`, with the fixed public message "Term is not available". The term is never echoed.
- **429 / 502:** `AiRateLimitedException`, `AiProviderException` and `AiOutputInvalidException` extend Spring's `ResponseStatusException`. That's the pattern `GlobalExceptionHandlerTest` already uses. They're rendered with generic `detail` texts: "Too many requests" and "Upstream service returned an invalid response".
- **503:** `ai.AiUnavailableException` was **removed**. The AI module throws `common.AiUnavailableException` ("AI service is unavailable"), keeping the SDK error as an internal cause that is never rendered.
- **Unchanged:** every file in `common/` that Marpu wrote, plus Problem Details, `X-Request-Id`/`requestId`, CORS and 5xx logging.
- **Conflicts:** resolved by keeping both sides. Overlapping files: `pom.xml` (one validation starter plus the SDK), `application.properties` (Problem Details, CORS, AI and catalog keys), `README.md`, `HANDOFF.md` (session-log rows merged by time), and `api-contract.md`. `.env.example` and `architecture.md` merged automatically, and were reviewed.

**Next recommended**
1. Michael: review `integrate/ai-into-pre-main`, then push it (a new branch, no force-push) and open a PR **into `pre-main`**. Marpu reviews the exception mapping onto his types, the resolved `pom.xml`/`application.properties`, the contract's error section and 429 row, and the shared stand-ins; David reviews the catalog switch.
2. Optional evaluation improvements, test-only:
   - save full responses in `ClaudeLiveEval`;
   - add `absentSoft` checks to the cases missing them;
   - re-run once (about $0.01) to measure stability.

   Decide as a team whether vague morning wording should produce an `earliestStart`, and only then change the prompt or the expectation.
3. Marpu: take over the shared stand-ins. David: replace `DummyCourseCatalog` with the database catalog.

**Blockers:** none for the AI module. Live runs need a developer's own `ANTHROPIC_API_KEY`, in the shell environment and never committed.

---

## Data and Frontend

**Owner:** _TBD_ · **Status:** ⬜ Not started (placeholders: [data/README.md](data/README.md), [frontend/README.md](frontend/README.md))

**Completed:** _none_.

**In progress:** _none_.

**Next recommended**
1. **Data:** identify a permitted source for Lehigh course/section data, and record its format, terms of use and refresh process in `data/README.md`. Meanwhile, create a small **synthetic** sample catalog so the Engine and Backend API work can proceed.
2. **Frontend:** scaffold Next.js + TypeScript + Tailwind in `frontend/`; add a page that calls `GET /api/health`; document the run commands in `frontend/README.md` and the README.
3. Decide CORS vs. a Next.js proxy with the Backend API owner.

**Blockers:** real data depends on step 1.

---

## Next recommended tasks

In priority order, across the team:
1. ~~Review Phase 1 on `feature/backend-foundation`, then merge it to `main`.~~ Resolved 2026-10-15 (PR #1).
2. ~~Assign module owners, and fill in the README team table and this file.~~ Resolved 2026-10-15 (README team table).
3. Decide the course data source (or agree on a synthetic dataset for the hackathon).
4. Start, in parallel: engine core (no dependencies), frontend scaffold, CI.
5. Integrate the feature branches into **`pre-main`** (the shared integration branch; `main` stays untouched). AI: done locally on `integrate/ai-into-pre-main` (177/177 tests); PR pending.

## Instructions for the next developer or AI agent

1. Read [README.md](README.md), this file, and [docs/architecture.md](docs/architecture.md). Follow [CLAUDE.md](CLAUDE.md).
2. Run `git status` and `git log --oneline -5`. **Confirm the repository state matches this file**, and note any discrepancy in the session log.
3. Run `cd backend && ./mvnw test` to confirm a green baseline before you change anything.
4. Claim your task under *In progress* in your module's section, with your handle and branch.
5. When you finish: run the tests, update your section with real results, add a session-log line, and leave changes uncommitted unless you were told to commit.

---

## Session log

Newest first, one line per session. Append only.

| When | Who | Module | Summary |
|---|---|---|---|
| 2026-10-16 18:41 EDT | Claude Code for Sunny | Integration | `integrate/catalog-endpoints-into-pre-main` from `pre-main` `b715f86`: merged PR #5 (database catalog, Michael) and `feature/backend-catalog-endpoints`. Resolved the README `CATALOG_SOURCE`/`DB_URL` conflict (both kept) and updated one assertion in `DefaultModeStartsWithoutDatabaseTest` (demo section catalog now expected). `cd backend && ./mvnw -B clean test` → `Tests run: 683, Failures: 0, Errors: 0, Skipped: 10` (opt-in live AI / DB tests), BUILD SUCCESS. Live: default mode generate 4 schedules (top 100), search/check 200; database mode with no DB → health 200, generate 503. For review via PR into `pre-main`. |
| 2026-10-16 18:33 EDT | Claude Code for Sunny | Scheduling Engine (+ Backend API / Data, at the owner's request) | `feature/backend-catalog-endpoints` from `pre-main` `b715f86`: demo section catalog, course search/detail, `/check`, `/suggest-electives`, `electiveAdvice`, min-credit suggestion. Docs updated. `cd backend && ./mvnw -B test` → `Tests run: 588, Failures: 0, Errors: 0, Skipped: 0`, BUILD SUCCESS. Live: all endpoints verified with `AI_PROVIDER=dummy`. |
| 2026-10-16 15:58 EDT | Claude Code for Sunny | Scheduling Engine | Addressed Marpu's integration review on `integrate/engine-into-pre-main`: blank or self-referencing lecture links are rejected (mapper and adapter), with new tests (they fail without the fix). Probed `pre-main`: the closed-lab bug is still there (fixed on this branch). `cd backend && ./mvnw -B test` → `Tests run: 550, Failures: 0, Errors: 0, Skipped: 0`, BUILD SUCCESS. |
| 2026-10-16 15:43 EDT | Claude Code for Sunny | Scheduling Engine | Elective recommendations on `feature/engine-core`: `ElectiveAdvisor` (explain and fix typed electives; suggest electives that fit a schedule), `ElectiveAdvice` and `ElectiveSuggestion`, 11 tests. Solver tie-break: more electives first on an equal score; the new test fails without the change. Documented the elective flow. `cd backend && ./mvnw -B test` → `Tests run: 79, Failures: 0, Errors: 0, Skipped: 0`, BUILD SUCCESS. |
| 2026-10-16 15:29 EDT | Codex | Backend API | User authorized updating PR #3 (`pre-main` head). Carried local review notes and unavailable-integration test comment onto fresh `pre-main` at `fef4857`, preserving the merged AI changes and retaining a backup stash. Targeted availability tests: 2 tests, zero failures/errors, BUILD SUCCESS; normal fast-forward push prepared. |
| 2026-10-16 | Codex | Backend API | Re-reviewed PR #2 at `4b288b7`: both prior P2 validation findings fixed; no new blocking findings. Full clean suite: 460 tests, zero failures/errors, BUILD SUCCESS. Both original independent regression probes now pass (2/2). Recommend approval of this revision; no GitHub submission or merge. |
| 2026-10-16 | Codex | Backend API | Reviewed PR #2 at `9183a29` in an isolated copy. Existing suite: 307 tests, zero failures/errors, BUILD SUCCESS. Two added review-only probes both fail: AI `24:00` silently becomes midnight; incomplete `{}` model output is accepted as complete. Both P2 findings recorded above; recommend fixes before merge. No GitHub submission or application source changes. |
| 2026-10-16 | Codex | Backend API | Inspected remote engine integration adapter/mapping/contracts; flagged permissive blank/self lecture-link normalization and confirmed integration branch already uses eight optional courses and 1M nodes. Clarified unavailable-integration test comment locally; no runtime changes or test rerun, merge, commit or push. |
| 2026-10-16 15:25 EDT | Claude Code for Michael | AI Integration | Fixed Marpu's two PR #2 review findings on the local `reconcile/ai-pre-main`: strict `H:mm`/`HH:mm` times (`24:00` is no longer read as `00:00`) and required-property validation of the model's raw JSON (`{}` is malformed: retry once, then 502). 153 new tests; `./mvnw -B clean test`: 460/460 pass; both probes also checked on the packaged app against a localhost fake of the Anthropic API. No real Anthropic call. Not pushed. |
| 2026-10-16 14:45 EDT | Claude Code for Michael | AI Integration | Local branch `reconcile/ai-pre-main` (worktree) from `integrate/ai-into-pre-main` `4abcddb`; `--no-ff` merge of `pre-main` `9cd9cdf`. 11 conflicts resolved: shared records and `JsonConfiguration` take Marpu's strict versions; comment-only and property conflicts combined; docs combined and stale status corrected. Added 2 AI regression tests. `./mvnw -B clean test`: 307/307 pass; live app checks in all three AI modes. Not pushed. |
| 2026-10-16 14:21 EDT | Claude Code for Sunny | Scheduling Engine | Applied the owner's decisions on `integrate/engine-into-pre-main`: optional-course limit 10 → 8 (one constant, validator plus mapper; tests now check the 8/9 boundary) and node-cap default 200,000 → 1,000,000 (properties, `.env.example`, README, contract, tests). Recorded soft time preferences, the 300/600 caps and the demo-major decision. `cd backend && ./mvnw -B test` → `Tests run: 233, Failures: 0, Errors: 0, Skipped: 0`, BUILD SUCCESS. |
| 2026-10-16 14:11 EDT | Claude Code for Sunny | Scheduling Engine | Manual-mode support on `feature/engine-core`: added `SelectionChecker`, `SelectionReport`, `SelectionIssue` and `SectionFit`, plus 12 tests including a cross-check with the solver. Documented the manual mode and a proposed `/api/schedules/check` endpoint, and flagged the "major's required courses" scope change. `cd backend && ./mvnw -B test` → `Tests run: 67, Failures: 0, Errors: 0, Skipped: 0`, BUILD SUCCESS. |
| 2026-10-16 12:55 EDT | Claude Code for Sunny | Scheduling Engine | Created `integrate/engine-into-pre-main` from `origin/pre-main` (`9cd9cdf`) and merged `feature/engine-core`. Added the `NativeSchedulingEngine` adapter, defined `lectureLink` as the lecture's CRN, and fixed the closed-lab case in `EngineMapper` using `CourseOption.excluding`. Added adapter tests and a full-app integration test with CSE-007 data. `cd backend && ./mvnw -B test` → `Tests run: 221, Failures: 0, Errors: 0, Skipped: 0`, BUILD SUCCESS. Live: health 200; generate 503 (no production catalog yet). |
| 2026-10-16 12:44 EDT | Claude Code for Sunny | Scheduling Engine | Owner asked to commit and push `feature/engine-core`. Added merge notes for `origin/pre-main`'s `SchedulingEngine` boundary (adapter mapping, closed-section risk, node-cap mismatch). Test run before commit: 55/55 pass (see 22:44 entry); no code changed since. |
| 2026-10-16 11:34 EDT | Codex | Backend API | User authorized commit/push of all pending Schedule API changes to `pre-main`. Fetched target and confirmed matching base `533633d`. Pre-push `./mvnw -B test` via Git Bash/JDK 21: `Tests run: 158, Failures: 0, Errors: 0, Skipped: 0`, `BUILD SUCCESS`. Prepared normal fast-forward push with explicit `HEAD:refs/heads/pre-main`; production adapters remain pending. |
| 2026-10-15 23:51 EDT | Codex | Backend API | Created and verified `feature/backend-schedule` from testing base `origin/pre-main` (`533633d`) before edits. Implemented Schedule API validation/preparation/response mapping, node-cap config and docs; reused AI-branch shared type shapes. Final `./mvnw -B verify` via Git Bash/JDK 21: `Tests run: 158, Failures: 0, Errors: 0, Skipped: 0`, `BUILD SUCCESS`; JAR packaged, live curl 400/503 checks passed. Native solver/section catalog absent; successful real integration remains blocked and documented. Changes uncommitted; no push/merge/PR. |
| 2026-10-15 23:24 EDT | Claude Code for Michael | AI Integration | Integrated AI into a local `integrate/ai-into-pre-main` from `origin/pre-main` `533633d`: `--no-ff` merge, 5 conflicts resolved keeping both sides; AI errors moved onto shared `common` types and `ResponseStatusException` (no `common/` changes); 20 integration tests added. Before the fix: 157 tests, 5 failures (AI errors returned 500). After: 177/177. Workflow docs retargeted to `pre-main`. Nothing pushed. |
| 2026-10-15 23:10 EDT | Claude Code for Michael | AI Integration | Recorded the live verification (1 request 16/16; eval 19/20 = 95%; failure `vague-time` and evaluation limits documented). Marked endpoint 5 live-verified. Found the `pre-main` error-handler conflict (read-only). Committed and pushed step 2 to `feature/ai-preference-extractor`; not merged. |
| 2026-10-15 22:44 EDT | Claude Code for Sunny | Scheduling Engine | Committed the engine types as `d37eec8` (owner's request; not pushed). Implemented `BacktrackingScheduleSolver`, `ScheduleScorer`, `Bundle`, `Meeting.overlaps` and `CourseOption.excluding`, plus 35 new engine tests (including a brute-force optimality check). Measured search size; default node cap raised to 1,000,000. `cd backend && ./mvnw -B test` → `Tests run: 55, Failures: 0, Errors: 0, Skipped: 0`, BUILD SUCCESS. Solver uncommitted. |
| 2026-10-15 22:30 EDT | Claude Code for Michael | AI Integration | Step 2: Claude provider with the official SDK 2.71.0, structured output, prompt, 429/502/503 mapping, 20-case eval set and opt-in live runner. 112/112 tests pass offline; app modes verified with no key. No live API call made. Uncommitted. |
| 2026-10-15 22:26 EDT | Codex | Backend API | User authorized commit and push of the common package to testing branch `pre-main`. Fetched origin and confirmed `origin/pre-main` matches the feature base (`098cf09`). Pre-push `./mvnw -B test` via Git Bash/JDK 21: `Tests run: 48, Failures: 0, Errors: 0, Skipped: 0`, `BUILD SUCCESS`; diff check clean. Prepared normal fast-forward push with explicit `HEAD:refs/heads/pre-main`; `main` is not a push target. |
| 2026-10-15 22:08 EDT | Claude Code for Sunny | Scheduling Engine | Lecture/lab links added to the engine types (`component`, `parentCrn`; one lecture → many labs). Documented the infeasibility suggestion, the 8 caller rules for the Backend API, and the search-size plan in architecture.md § 7–8. `cd backend && ./mvnw -B test` → `Tests run: 23, Failures: 0, Errors: 0, Skipped: 0`, BUILD SUCCESS. Nothing committed. |
| 2026-10-15 22:01 EDT | Codex | Backend API | Created and verified `feature/backend-common` from freshly fetched `origin/main` (`098cf09`) before edits; implemented common errors, validation, CORS, and docs. Final `./mvnw -B test` via Git Bash/JDK 21: `Tests run: 48, Failures: 0, Errors: 0, Skipped: 0`, `BUILD SUCCESS`; live curl checks passed. Windows wrapper issue/workaround and stale handoff summaries recorded in Backend API. Uncommitted; no push or PR. |
| 2026-10-15 22:01 EDT | Claude Code for Michael | AI Integration | Pre-commit checks passed (51/51 tests, diff-check clean, no secrets or artifacts); committed step 1 and pushed `feature/ai-preference-extractor`. Not merged. Claude provider not built. |
| 2026-10-15 21:58 EDT | Claude Code for Michael | AI Integration | Step 1 cleanup: dummy catalog switchable via `CATALOG_SOURCE`; JSON contract round-trip tests; README AI section corrected; David's replacement steps added. 51/51 tests pass; live-checked dummy 200, none 503, database setting fails clearly. Uncommitted. |
| 2026-10-15 21:45 EDT | Claude Code for Michael | AI Integration | Step 1 on `feature/ai-preference-extractor`: extract endpoint, resolver, validator, dummy/none providers, shared stand-ins (preferences, catalog, JSON time format). 43/43 tests pass; live-checked 200/400/422/503. Uncommitted. |
| 2026-10-15 21:42 EDT | Claude Code for Sunny | Scheduling Engine | Renamed the local branch `Scheduling-Engine` → `feature/engine-core`. Added the engine records and the `ScheduleSolver` interface (types only, no solver), 17 plain-JUnit tests, and architecture.md § 7–8 (engine API, scoring formula). `cd backend && ./mvnw -B test` → `Tests run: 20, Failures: 0, Errors: 0, Skipped: 0`, BUILD SUCCESS. Nothing committed. |
| 2026-10-15 21:30 EDT | Claude Code for @04michael4 | docs | Credit limits updated from Lehigh's overload policy (cap 21, overload flag); updated api-contract.md, architecture.md, README, HANDOFF.md. Docs only. |
| 2026-10-15 21:22 EDT | Claude Code for @04michael4 | docs | Team agreed the API contract as written; removed "provisional" markers from api-contract.md, architecture.md and HANDOFF.md. Docs only, no code changes. |
| 2026-10-15 16:45 EDT | Claude Code for @04michael4 | Backend API / docs | Pre-commit checks; marked API contract, credit limits and team roles as provisional; re-ran tests (3/3 pass) and verified health with curl. Committed Phase 1 and pushed to `origin/feature/backend-foundation`; not merged to `main`. |
| 2026-10-15 16:40 EDT | Claude Code for @04michael4 | Backend API / docs | Wrote README, CLAUDE.md, AGENTS.md, HANDOFF.md, architecture.md, api-contract.md, development-guide.md, ADR-0001. Re-ran tests: 3/3 pass; health verified with curl. Nothing committed. |
| 2026-10-15 16:27 EDT | Claude Code for @04michael4 | Backend API | Phase 1 backend foundation: Spring Boot 4.1.1 skeleton, `/api/health`, tests (3/3 pass), `.gitignore`, `.env.example`. Nothing committed. |
