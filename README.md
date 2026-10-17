# Hackathon---Registration-AI

**AI-assisted course registration and schedule optimization for Lehigh University students.**

Students describe what they want in plain English, for example *"I need CSE 17 and Calc 1, no classes before 10, Fridays off if possible, I work Tue/Thu 3–6"*. The app turns that into structured preferences and generates conflict-free schedules ranked by how well they fit.

> Independent student hackathon project. Not affiliated with or endorsed by Lehigh University. It does **not** register students for classes; it helps them plan.

| | |
|---|---|
| **Current phase** | Phase 1 foundation: backend health API and an independent browser-local frontend prototype are implemented; production feature integration remains planned |
| **What works today** | Spring Boot `GET /api/health`; Next.js mock course/degree planning UI with local persistence; backend and frontend test/build checks |
| **Live status** | [HANDOFF.md](HANDOFF.md) |

## Contents
- [Problem and solution](#problem-and-solution)
- [Features and MVP scope](#features-and-mvp-scope)
- [Technology stack](#technology-stack)
- [Architecture](#architecture)
- [Repository structure](#repository-structure)
- [Getting started](#getting-started)
- [Team and responsibilities](#team-and-responsibilities)
- [Git workflow](#git-workflow)
- [Development status](#development-status)
- [Documentation](#documentation)
- [Known limitations and future work](#known-limitations-and-future-work)

## Problem and solution

**Problem.** Building a semester schedule means cross-referencing the course catalog, checking every combination of sections for time conflicts, staying within credit limits, and working around jobs, practices and personal preferences. It's tedious and easy to get wrong, and the "best" combination is hard to find by hand.

**Solution.** Two components with a clear division of labor:
1. **Claude (AI)** interprets the student's free-text wishes as structured, editable preferences. The student reviews them before anything is generated.
2. **A deterministic Java scheduling engine** searches section combinations. It guarantees every returned schedule satisfies the hard constraints (no conflicts, required courses, credit range, blocked times), and ranks the results by soft preferences (start times, days off, gaps).

The AI never decides whether a schedule is valid, so results are always correct, reproducible and testable. [Architecture § 1](docs/architecture.md#1-system-overview) explains why.

## Features and MVP scope

| Feature | In MVP | Status |
|---|---|---|
| Backend foundation and health check | ✔ | ✅ Implemented, verified |
| Course search by code/title for a term | ✔ | 🟡 Browser-local mock; backend catalog planned |
| AI preference extraction from free text | ✔ | ⬜ Planned |
| Editable preference form (works without AI too) | ✔ | 🟡 Browser-local mock implemented |
| Conflict-free schedule generation, ranked by preferences | ✔ | 🟡 Deterministic browser demo; authoritative Java engine planned |
| Weekly calendar view of generated schedules | ✔ | 🟡 Browser-local mock implemented |
| AI explanation of a schedule | Stretch | ⬜ Planned |
| Prerequisite checking, degree audit, saved schedules, user accounts, actually registering for classes | ✘ Out of scope | — |

## Technology stack

| Layer | Technology | Why |
|---|---|---|
| Backend | **Java 21 + Spring Boot 4.1.1**, Maven Wrapper | Static types for the constraint engine, mature REST, validation and testing; see [ADR-0001](docs/decisions/0001-java-spring-boot-over-python-fastapi.md) |
| Scheduling | Plain Java (no solver library) | Small problem size; deterministic, dependency-free and easy to debug ([Architecture § 7](docs/architecture.md#7-scheduling-engine)) |
| Database | PostgreSQL *(planned)* | Relational catalog data (term → course → section → meeting), with built-in text search |
| AI | Anthropic Claude API via the official Java SDK *(planned)* | Structured, schema-constrained output for reliable extraction |
| Frontend | **Next.js 16 + React 19 + strict TypeScript + Tailwind CSS 4** | Typed UI and reusable components; browser-local mock implemented, backend integration planned |

## Architecture

```mermaid
flowchart LR
    S([Student]) --> FE[Next.js frontend]
    FE -->|REST /api/**| BE[Spring Boot backend]
    BE --> EN[Deterministic scheduling engine]
    BE --> DB[(PostgreSQL)]
    BE -->|structured output| CL[Claude API]
```

Only the backend talks to Claude and holds secrets. The frontend is a thin client. Full details, including what's implemented vs. planned: **[docs/architecture.md](docs/architecture.md)**.

## Repository structure

```
.
├── backend/                 Spring Boot API (Java 21)
│   ├── mvnw, mvnw.cmd       Maven Wrapper (no Maven install needed)
│   ├── pom.xml              Dependencies; Spring Boot version pinned here
│   └── src/
│       ├── main/java/io/github/michael04/registration/
│       │   ├── RegistrationBackendApplication.java
│       │   └── health/      GET /api/health
│       ├── main/resources/application.properties
│       └── test/java/...    Automated tests
├── frontend/                Next.js App Router mock frontend
├── data/                    Catalog snapshots / seed data (placeholder)
├── docs/
│   ├── architecture.md      Technical architecture and design rationale
│   ├── api-contract.md      REST API specification (source of truth)
│   ├── development-guide.md Team workflow, Git, PRs, AI agents
│   └── decisions/           Architecture Decision Records
├── .env.example             Environment variable template (placeholders only)
├── AGENTS.md                Pointer for Codex → CLAUDE.md
├── CLAUDE.md                Rules for AI agents and contributors
└── HANDOFF.md               Live progress and handoff log
```

## Getting started

**CSE 007 database:** A standalone PostgreSQL snapshot using the course screenshots and SuY corrections is available;
see [course data and connection details](data/cse007-snapshot/README.md).
The backend is not wired to it yet. Earlier Phase 1 status tables describe backend integration.

These steps are for macOS. Linux works the same way; on Windows use `mvnw.cmd`.

### 1. Prerequisites

| Tool | Version | Needed for |
|---|---|---|
| JDK | **21** | Backend (required) |
| Git | any recent | Cloning |
| Maven | **not needed** | `./mvnw` downloads the pinned Maven 3.10.0 on first run |
| Node.js | **24 LTS** | Frontend development and production builds |
| PostgreSQL | _TBD_ | Persistence phase (later) |

Install JDK 21 with [Homebrew](https://brew.sh):

```bash
brew install --cask temurin@21    # Eclipse Temurin JDK 21; registers itself with macOS
java -version                     # should report 21
/usr/libexec/java_home -v 21      # should print a JDK 21 path
```

If `java -version` shows another version, add this to `~/.zshrc` and open a new terminal:

```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 21)
```

> Using Homebrew's `openjdk@21` formula instead? It's "keg-only": either run the `sudo ln -sfn …` command shown by `brew info openjdk@21`, or set `JAVA_HOME="$(brew --prefix openjdk@21)/libexec/openjdk.jdk/Contents/Home"`.

### 2. Clone

```bash
git clone https://github.com/04michael4/Hackathon---Registration-AI.git
cd Hackathon---Registration-AI
```

### 3. Environment variables

```bash
cp .env.example .env    # optional right now; nothing is required yet
```

| Variable | Used by | Required now? | Default |
|---|---|---|---|
| `PORT` | Backend | No | `8080` |
| `DB_URL`, `DB_USERNAME`, `DB_PASSWORD` | Backend *(planned)* | No | — |
| `ANTHROPIC_API_KEY`, `ANTHROPIC_MODEL` | Backend *(planned)* | No | — |
| `NEXT_PUBLIC_API_BASE_URL` | Frontend backend integration *(planned; unused today)* | No | `http://localhost:8080` |

`.env` is git-ignored; never commit real credentials. Spring Boot does **not** read `.env` automatically. Export variables in your shell (`export PORT=9090`) or set them in your IDE's run configuration.

### 4. Run the automated tests

```bash
cd backend
./mvnw test
```

The first run downloads Maven and dependencies (a minute or two). Expected output ends with:

```
[INFO] Tests run: 3, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
```

### 5. Run the backend

```bash
cd backend
./mvnw spring-boot:run            # or: PORT=9090 ./mvnw spring-boot:run
```

In a second terminal:

```bash
curl -i http://localhost:8080/api/health
# HTTP/1.1 200
# Content-Type: application/json
# {"status":"UP","service":"registration-backend"}
```

Stop the server with `Ctrl+C`. No database or API key is needed in this phase.

### 6. Run the frontend

```bash
cd frontend
npm ci
npm run dev        # http://localhost:3000; browser-local mock, no backend required
```

Run `npm run lint`, `npm test`, and `npm run build` before review. See [frontend/README.md](frontend/README.md) for the project structure and production commands.

### Troubleshooting

| Symptom | Fix |
|---|---|
| `permission denied: ./mvnw` | `chmod +x mvnw` in `backend/` |
| `Port 8080 was already in use` | `lsof -i :8080` to find the process, or `PORT=9090 ./mvnw spring-boot:run` |
| `release version 21 not supported` | Your shell is on an older JDK; set `JAVA_HOME` (step 1) |
| Mockito "dynamic agent" warnings during tests | Harmless JDK 21 notices; they don't affect results |

## Team and responsibilities

Four modules, one owner each. Owners review changes to their module and to the shared interfaces it uses.

> ⚠️ **Provisional.** Remaining `_TBD_` owners will be filled in once the team agrees. The module split itself is also a proposal.

| Module | Owner | Scope | Code location |
|---|---|---|---|
| Backend API | _TBD_ (Phase 1 foundation: @04michael4) | Spring Boot setup, course search endpoints, error handling, shared config | `backend/…/health`, `course`, `common` |
| Scheduling Engine | _TBD_ | Deterministic solver, schedule endpoints | `backend/…/engine`, `schedule` |
| AI Integration | _TBD_ | Claude client, prompts, output validation | `backend/…/ai` |
| Data and Frontend | David | Catalog data and import; Next.js UI | `data/`, `backend/…/ingest`, `frontend/` |

## Git workflow

- `main` always builds and passes tests. All work happens on branches named `<type>/<module>-<desc>`, e.g. `feature/engine-backtracking`.
- Use [Conventional Commits](https://www.conventionalcommits.org/): `feat(engine): add backtracking search`.
- Open a PR into `main` with ≥ 1 approving review and the `./mvnw test` result in the description, then squash-merge.
- Update your module's section in [HANDOFF.md](HANDOFF.md) with each meaningful change.

Full workflow, including conflicts, AI agents and onboarding: **[docs/development-guide.md](docs/development-guide.md)**.

## Development status

| Component | Status |
|---|---|
| Backend foundation (Spring Boot, Maven Wrapper, health endpoint, tests) | ✅ Implemented and verified locally on 2026-10-15; on `feature/backend-foundation`, **not yet merged to `main`** |
| Course catalog, PostgreSQL | ⬜ Planned |
| Scheduling engine | ⬜ Planned |
| AI integration | ⬜ Planned |
| Frontend mock prototype | ✅ Next.js/React/TypeScript/Tailwind migration implemented and verified locally on 2026-10-17; backend integration remains planned |
| CI, deployment | ⬜ Not started; no deployed URL exists |

Details, test results and next steps: **[HANDOFF.md](HANDOFF.md)**.

## Documentation

| Document | Read it when… |
|---|---|
| [HANDOFF.md](HANDOFF.md) | You start a session: what's done, in progress and next |
| [CLAUDE.md](CLAUDE.md) | Before writing code: rules, conventions, Definition of Done |
| [docs/architecture.md](docs/architecture.md) | You need to understand how and why the system is built this way |
| [docs/api-contract.md](docs/api-contract.md) | You build or call an endpoint |
| [docs/development-guide.md](docs/development-guide.md) | You branch, open a PR, resolve conflicts, or use Claude Code / Codex |
| [docs/decisions/](docs/decisions/README.md) | You want the reasoning behind a major decision, or to record one |

## Known limitations and future work

**Current limitations**
- Only the backend health endpoint exists. The frontend is a browser-local mock and is not connected to backend catalog, scheduling or AI endpoints.
- **The source for Lehigh course data hasn't been chosen.** This blocks course search and scheduling against real data.
- No CI pipeline and no deployment.
- No authentication. This is acceptable because the MVP stores no user data.
- `.env` files aren't loaded automatically by Spring.

**Planned beyond the MVP**
- Prerequisite awareness (needs reliable data)
- Linked lecture/lab sections, if the data supports them
- Travel time between campuses
- Saved schedules (would require accounts and authentication)
- AI explanations
- An OpenAPI spec generated from code, to keep frontend types in sync
