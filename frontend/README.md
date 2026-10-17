# Syllo frontend

The Syllo frontend is a Next.js App Router application written in strict TypeScript and React, with Tailwind CSS available through PostCSS. It is intentionally independent from the backend during this phase: catalog records, transcript history, planning rules, AI-style responses, saved schedules and advisor feedback are browser-local demo data.

## Requirements

- Node.js 24 LTS
- npm 11 or newer (included with Node.js 24)

The application uses Next.js 16 and requires at least Node.js 20.9. The project standardizes local development on Node.js 24 LTS; `package-lock.json` pins the complete dependency tree.

## Install and run

```bash
cd frontend
npm ci
npm run dev
```

Open <http://localhost:3000>. No backend, database, credentials or environment variables are required for the current mock experience.

Production build and server:

```bash
npm run build
npm run start
```

Quality checks:

```bash
npm run lint
npm test
```

## Project structure

```text
frontend/
├── public/assets/             Static images
├── src/app/                   Next.js layout, page and global CSS entrypoint
├── src/components/            Reusable React screens and UI components
├── src/context/               Persistent app state, navigation and undo
├── src/lib/mock-data.ts       Simulated catalog, transcript and initial state
├── src/lib/planner.ts         Pure scheduling, eligibility and audit rules
├── src/lib/planner.test.ts    Deterministic rule tests
├── src/lib/types.ts           Shared strict TypeScript types
├── src/styles/                Preserved Syllo design system and responsive CSS
├── package.json               Development, build, lint and test scripts
└── package-lock.json          Pinned npm dependency graph
```

## Demo workflow

1. Enter through the clearly labeled mock Lehigh SSO screen, or choose the advisor demo.
2. Search and add eligible courses to the weekly semester calendar. Conflicts are highlighted and require an explicit keep/replace choice.
3. Open the local Syllo assistant, set day/time/credit preferences, preview its deterministic suggestion, and apply it after review.
4. Save, rename, reopen and remove named schedules. Undo restores recent planning changes in the current session.
5. Arrange the four-year degree plan with drag and drop. Term offerings and prerequisite order are checked before a move is accepted.
6. Fill both elective slots, submit a valid demo plan, and switch roles to leave advisor comments, request changes or approve it.

## Data and persistence boundary

The frontend does not call the Spring backend or an AI provider yet. `src/lib/mock-data.ts` owns the replaceable catalog/transcript fixture, while `src/lib/planner.ts` contains deterministic browser-side demo rules. These rules demonstrate interactions only and are not authoritative academic decisions.

Plans, chat messages, saved schedules and advisor feedback are stored under `syllo-planning-v4` in browser `localStorage`. They remain in one browser profile and do not sync. The mock SSO collects no credentials, and the role switch is not an access-control boundary.

When backend integration begins, keep API types in `src/lib/api/` and mirror `docs/api-contract.md`. Only the backend may call the AI provider or hold secrets.
