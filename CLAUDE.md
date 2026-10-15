# CLAUDE.md

Persistent instructions for AI coding agents (Claude Code; Codex reads them through [AGENTS.md](AGENTS.md)) and for humans. They apply to every task in this repo.

## Important rules

1. **Before a substantial task, read [README.md](README.md), [HANDOFF.md](HANDOFF.md), and [docs/architecture.md](docs/architecture.md).**
2. **Never assume a feature is implemented without inspecting the code.** Docs describe a lot of *planned* design (marked ⬜).
3. Don't modify unrelated modules. Stay inside the task's module unless the task requires otherwise; if it does, say so.
4. Don't change a shared API contract without documenting it (see [Shared interfaces](#shared-interfaces)).
5. **Don't commit or push without explicit permission** from the developer in the current session.
6. Never commit credentials or secrets.
7. Run the relevant tests after code changes.
8. Document failures and unresolved issues in your report and in HANDOFF.md.
9. **Don't claim tests passed unless you ran them and saw them pass.** Quote the actual result line.

## Project goals and priorities

AI-assisted course registration and schedule optimization for Lehigh University students (an independent student project, not affiliated with Lehigh). Priorities, in order:

1. **Correctness.** A returned schedule must never contain a conflict or violate a hard constraint.
2. **Determinism.** The same input and the same catalog data give the same output.
3. **A working end-to-end demo**: course search → AI preference extraction → schedule generation.
4. Simplicity over cleverness. This is a hackathon; prefer the smallest change that works.

## Approved technology stack

| Area | Approved | Notes |
|---|---|---|
| Backend | Java 21, **Spring Boot 4.1.1**, Maven via `./mvnw` | Pinned in `backend/pom.xml`; upgrade only deliberately |
| Database | PostgreSQL | **Not added yet.** The backend must start without a DB until the persistence phase. |
| Frontend | Next.js + TypeScript + Tailwind CSS | Not scaffolded yet |
| AI | Anthropic Claude via the official Java SDK `com.anthropic:anthropic-java` | Not added yet; backend only |
| Tests | JUnit 5, Spring Boot test slices, MockMvc | |

Adding any other dependency or framework needs a reason tied to code in the same PR, and a mention in the PR description. Don't add dependencies speculatively.

## Repository architecture

```
backend/   Spring Boot API  (base package io.github.michael04.registration)
frontend/  Next.js app (placeholder)
docs/      architecture.md, api-contract.md, development-guide.md, decisions/
data/      catalog snapshots and seed data (public or synthetic only)
HANDOFF.md live progress log
```

Commands (run from `backend/`): `./mvnw test` · `./mvnw spring-boot:run` · `curl http://localhost:8080/api/health`. Always use `./mvnw`, never a global `mvn`.

## Backend module responsibilities

| Package (`io.github.michael04.registration.*`) | Module | Responsibility | Status |
|---|---|---|---|
| `health` | Backend API | `GET /api/health` | ✅ |
| `common` | Backend API | Error handling, web config | ⬜ |
| `course` | Backend API | Catalog entities, search, course endpoints | ⬜ |
| `ingest` | Data | Import `data/` snapshots into the DB | ⬜ |
| `engine` | Scheduling Engine | **Pure Java** solver: no Spring, no I/O, no AI, deterministic | ⬜ |
| `schedule` | Scheduling Engine | Schedule endpoints; maps course data ↔ engine | ⬜ |
| `ai` | AI Integration | Claude client, prompts, output validation | ⬜ |

Dependency rules:
- `engine` depends on nothing in the project.
- `engine` and `schedule` never depend on `ai`.
- The AI proposes and explains; only the engine decides validity.

Details: [architecture.md § 4](docs/architecture.md#4-backend-package-structure).

## Coding conventions

**Java**
- **Package by feature.** A feature's controller, service, DTOs and tests live together.
- Use constructor injection; no field `@Autowired` in production code.
- Request and response DTOs are `record`s. Never expose JPA entities or engine types in the API.
- Validate input with Bean Validation at the controller boundary.
- Indent with **tabs**, matching the existing generated code.
- Don't use `HashSet` or `HashMap` iteration order for anything user-visible or in the engine. Sort explicitly, or use `LinkedHashMap`.

**Spring Boot 4 specifics.** Most online examples are for 3.x and won't compile here:
- The starters are `spring-boot-starter-webmvc` and `spring-boot-starter-webmvc-test`, not `spring-boot-starter-web`.
- `@WebMvcTest` and `@AutoConfigureMockMvc` are in `org.springframework.boot.webmvc.test.autoconfigure`.
- Jackson is 3.x: `tools.jackson.*` for databind. Annotations such as `@JsonProperty` stay in `com.fasterxml.jackson.annotation`.

**TypeScript** (once the frontend exists)
- Turn on `strict` mode, and don't use `any`. Use `unknown` and narrow it.
- Use function components and hooks. Style with Tailwind utility classes; no inline style objects.
- Keep API types in one place (e.g. `frontend/src/lib/api/`), mirroring [docs/api-contract.md](docs/api-contract.md). The frontend never calls Claude directly.

## Naming conventions

| Thing | Convention | Example |
|---|---|---|
| Java packages | lowercase, singular feature name | `...registration.course` |
| Java classes | PascalCase plus role suffix | `CourseController`, `CourseSearchService`, `CourseRepository` |
| DTOs | `*Request` / `*Response` records | `GenerateScheduleRequest` |
| Tests | `<ClassUnderTest>Test`, method names describe behavior | `healthReturnsUpWithServiceName` |
| REST paths | `/api/` + lowercase plural nouns; actions as sub-paths | `/api/courses`, `/api/schedules/generate` |
| JSON fields | camelCase | `requiredCourseIds` |
| Env vars | UPPER_SNAKE_CASE | `ANTHROPIC_API_KEY` |
| DB tables/columns | snake_case | `section.start_time` |
| React components / files | PascalCase `.tsx`; other TS files camelCase | `WeekCalendar.tsx`, `apiClient.ts` |
| Branches | `<type>/<module>-<desc>` | `feature/engine-backtracking` |

## Testing expectations

- Every endpoint has a `@WebMvcTest` slice test covering the success and the main error statuses.
- The engine has plain JUnit tests, with no Spring. Include determinism (same input twice gives identical output) and "no returned schedule contains a conflict".
- AI code is tested with fakes. **`./mvnw test` must never call the real Anthropic API** or need network access, a database, or secrets.
- Prefer slice tests to `@SpringBootTest` unless the full context is required.
- Bug fixes come with a test that fails without the fix.

## Documentation update requirements

Update these **in the same change** as the code:

| If you changed… | Update… |
|---|---|
| An endpoint's request, response, status codes or errors | [docs/api-contract.md](docs/api-contract.md), including the status label and change log |
| Architecture, package boundaries or a major dependency | [docs/architecture.md](docs/architecture.md); write a new ADR in [docs/decisions/](docs/decisions/README.md) if the decision is significant |
| Setup, commands or env vars | [README.md](README.md) and [.env.example](.env.example) |
| Anything meaningful | Your module's section of [HANDOFF.md](HANDOFF.md) (rules in [development-guide.md § 9](docs/development-guide.md#9-updating-handoffmd)) |

Label things honestly: ✅ implemented and verified, 🟡 implemented, ⬜ planned. Use `_TBD_` for unknowns. Never invent contributor names, URLs, data or results.

## Shared interfaces

These are **shared interfaces**: the REST contract (`docs/api-contract.md`), `SchedulePreferences`, the engine's input/output types, `application.properties` keys, `.env.example` variables, and the `common` package.

To change one:
1. Update the documentation in the same PR, with a change-log entry for API changes.
2. Say in the PR description that it's a contract change, and request review from the owners of the consuming modules.
3. Don't rename or remove a field another module uses unless that module is updated in the same PR, or its owner agrees.

## Git safety rules

- **No `git commit`, `git push`, merge, rebase, tag or branch deletion without explicit permission in the current session.** Permission for one commit doesn't carry over to the next.
- Never force-push to shared branches, and never push to `main` directly.
- Never run destructive commands (`git reset --hard`, `git clean -fd`, `git checkout -- .`) on uncommitted work without asking first.
- Check `git status` before and after your work, and report what changed.

## Security and environment variables

- Secrets come only from environment variables. Never hard-code them, log them, or put them in error messages, tests or docs.
- `.env` and `.env.*` are git-ignored, except `.env.example`, which holds **placeholders only**.
- A new config value means: a placeholder in `.env.example`, and a `${ENV_VAR:default}` reference in `application.properties`. Spring does not read `.env` files automatically.
- The Anthropic API key lives in the backend only. Never put it in a `NEXT_PUBLIC_*` variable or frontend code.
- Treat student free text as untrusted data in prompts, and validate all AI output in Java before using it.

## Working with other developers

- Check HANDOFF.md *In progress* before starting, so you don't duplicate or collide with a teammate's work.
- Stay within the module and branch you were given. One agent per branch.
- If a task needs a change in someone else's module, or a shared interface, stop and flag it rather than editing it silently.
- Merge conflicts: never discard a teammate's changes to resolve one. In `HANDOFF.md`, keep both sides.

## Definition of Done

A task is done when **all** of these are true:

- [ ] The code is implemented within the intended module, with no unrelated changes.
- [ ] Tests for the new behavior are added or updated.
- [ ] `./mvnw test` was **run** and passes. The actual result is reported.
- [ ] Changed or added endpoints were verified against the running app (`./mvnw spring-boot:run` plus `curl`).
- [ ] Docs are updated per the table above, including your HANDOFF.md section with real results.
- [ ] No secrets, no new unexplained dependencies, and the Phase boundaries are respected.
- [ ] Known failures and open issues are written down, not hidden.
- [ ] Changes are left **uncommitted** for review, unless the developer explicitly asked for a commit.
