# Syllo frontend

The Syllo frontend is a Next.js App Router application written in strict TypeScript and React. Its root route hosts the authoritative Syllo reference markup, stylesheet cascade, assets and browser runtime directly—without an iframe—so the rendered interface and local interactions stay byte-for-byte aligned with the supplied reference. Catalog records, transcript history, planning rules, AI-style responses, saved schedules and advisor feedback remain browser-local demo data.

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

Reference parity can be checked when the extracted reference is served at `http://127.0.0.1:4173` and this app is running at `http://127.0.0.1:3000`:

```bash
npm run verify:parity
```

The verifier uses an installed Chrome executable and Node.js built-ins only. Override `CHROME_PATH`, `SYLLO_REFERENCE_URL`, `SYLLO_IMPLEMENTATION_URL`, or `SYLLO_PARITY_OUTPUT` when needed.

**Parity is no longer expected.** The Plan My Semester UX pass (below) deliberately changes the navigation, the planner header and toolbar, the course list and the calendar, so screenshots now differ from `Syllo-updated-frontend.zip` by design. The verifier remains useful for screens that were not changed.

`src/app/page.tsx` reads `src/reference/index.html` once when the module loads. After editing that file, restart `npm run dev` (or delete `.next`) to see the change.

## Project structure

```text
frontend/
├── public/assets/             Static images
├── public/reference/          Browser-local behavior scripts (classic scripts, loaded in order)
│   ├── planner-logic.js       Pure rules shared with Vitest: code search, R/P list, ratings, section previews, degree search, categories, trackers
│   └── ai-widget.js           Floating, draggable Syllo AI for the Degree Planner
├── src/app/                   Next.js layout, page and global CSS entrypoint
├── src/components/            React components, including ordered reference runtime loading
├── src/reference/index.html   Reference document (nav and planner markup updated by the UX pass; no features hub)
├── src/styles/                Reference stylesheets in source order; planner-ux.css then degree-ux.css load last
├── scripts/                   Headless Chrome parity verification
├── src/lib/planner.ts         Pure typed planner rules retained for unit coverage
├── src/lib/planner.test.ts    Deterministic rule tests
├── src/lib/planner-logic.test.ts  Semester planner rules (runs data.js in a sandbox)
├── src/lib/degree-planner.test.ts Degree planner rules: categories, unified search, bucket, bulk moves, undo/redo
├── package.json               Development, build, lint and test scripts
└── package-lock.json          Pinned npm dependency graph
```

## Demo workflow

1. Enter through the clearly labeled mock Lehigh SSO screen; you land on Plan My Semester. The advisor demo is in the nav (Menu) or the Degree Planner profile menu.
2. Search and add eligible courses to the weekly semester calendar. Conflicts are highlighted and require an explicit keep/replace choice.
3. Open the local Syllo assistant, set day/time/credit preferences, preview its deterministic suggestion, and apply it after review.
4. Save, rename, reopen and remove named schedules. Undo restores recent planning changes in the current session.
5. Arrange the four-year degree plan with drag and drop. Term offerings and prerequisite order are checked before a move is accepted.
6. Fill both elective slots, submit a valid demo plan, and switch roles to leave advisor comments, request changes or approve it.

## Navigation

Signing in opens Plan My Semester directly; the old "What would you like to do?" hub was removed, and the Syllo logo also returns to Plan My Semester. The nav tabs read Plan My Semester, Degree Planner, Advisor portal and Saved schedules.

## Plan My Semester

- **Navigation** collapses to a slim bar with the Syllo logo and a Menu button on every signed-in screen. The tabs appear on an intentional hover over the bar (short open and close delays prevent flicker), on keyboard focus, or with Menu; Escape or a click elsewhere closes them. Opening never shifts the page.
- **Header**: the title is "Plan My Semester". Syllo AI ("Ask Syllo AI · AI planning assistant") and the registration countdown sit top right; the countdown logic is unchanged.
- **Schedule tools**: credits, Undo (names the change it will undo; disabled when there is nothing to undo), Save schedule and View bigger sit above the calendar. "Use in degree plan" was removed from this page.
- **Course search** matches codes without case, spaces or leading zeros (`cse17` finds CSE 017); title and keyword search are unchanged. Search by interest is untouched.
- **Course list** merges Recommended and Planned classes. Each card shows a class-type mark (P = planned, filled; R = recommended, outlined) with the full meaning for screen readers, and filters for All / Planned / Recommended plus a text filter. A course is Planned when it is on the schedule and Recommended when it is recommended and not on it, exactly as the two old lists decided.
- **Professor ratings** (demo Rate My Professor data) color the rating chip on each card and the left edge of calendar blocks: Excellent 4.5+, Good 4.0–4.4, Mixed 3.0–3.9, Low below 3.0, and a dashed neutral "n/a" when there is no rating. The number is always shown, a legend explains the colors, and hovering or focusing a chip shows the professor, quality and difficulty.
- **Preview other sections**: right-click a scheduled course (or Shift+F10, or the dashed-squares button on its card) to show the term's other sections as dashed blocks, every meeting included (lectures, labs, recitations). Previews never change the schedule, credits, saved schedules, undo history or storage. Clear them with the same action, Escape, a click elsewhere, or Clear preview; they also clear when the course leaves the schedule or you leave the planner. Alternative sections are simulated demo data in `data.js`; CSE 109 has only one section, which shows the empty-state message.

## Degree Planner

- **One screen**: on laptops and desktops (at least 1100×650) the page fits the window; the calendar and the staging area scroll inside themselves. Smaller screens scroll normally.
- **Header**: Lehigh University, the title "Degree Planner", and a profile menu (Plan My Semester, Advisor portal, Saved schedules, switch demo role). The old tagline and degree text are gone.
- **Unified search**: one search box for courses, minors, concentrations and interests, with filter tags Course code, Major, Minor, GenEd and Interest (several tags combine). Results can be staged or added straight to a semester. Choosing a minor or concentration declares it and stages its courses that aren't planned yet.
- **Requirements & unassigned courses**: a staging area between the calendar and the review panel. Drag staged courses into a semester (prerequisite order and offerings are checked) or drag planned courses back to unassign them. Credit trackers show Humanities electives (8 credits), General education electives (8 credits) and each declared minor: completed, planned, staged and unassigned credits. The plan isn't ready for review while courses wait in the staging area.
- **Colors** (category, not course level): Major `#653819` brown, Minor/concentration `#E1AA29` gold, General education required slate navy, free and humanities electives cool gray. Each card also shows a text tag (Major, Minor, GenEd, Elective).
- **Selection**: Shift+click (or Shift+Enter) selects several courses across semesters and the staging area; dragging a box over empty space selects what it touches. Drag any selected card to move them all, or use the selection bar (Move, Remove). Delete removes the selection; Esc clears it. Completed courses can't be selected.
- **Free electives and the credit limit**: the two elective slots take a course of any credit amount (1, 3, 4, …). A semester can hold at most 21 credits; this is checked when choosing an elective, adding from search, and moving courses (one at a time or in bulk), and any semester over 21 is flagged in the plan check. The 120-credit graduation total still applies, so smaller electives may mean planning more courses.
- **Undo / redo**: Ctrl/⌘+Z and Ctrl/⌘+Shift+Z (Ctrl+Y also redoes), plus buttons beside the calendar title. Bulk actions are one undo step. Redo also works on Plan My Semester.
- **Syllo AI** floats as a launcher pinned to the left or right edge (drag it, or use the arrow keys). It opens the degree assistant as a pop-up you can minimize, close, dock to the side (the page makes room) or expand to full screen (Esc to exit). It appears on the Degree Planner only; Plan My Semester keeps its header button.
- Demo data added for this (simulated, like the rest of `data.js`): an Economics minor (ECO 105, ECO 146), an Ethics & Technology concentration, SOC 001 and PSY 001 as general-education electives, and MUS 001 (a 1-credit free elective).
- The active screen is marked on `<body>` as `data-active-screen` (not `data-screen`, which identifies the screen sections).

## Data and persistence boundary

The frontend does not call the Spring backend or an AI provider yet. `public/reference/data.js` owns the executable catalog/transcript fixture and local store; the remaining reference scripts implement the semester planner, degree planner, advisor review and shared navigation. The retained typed planner modules provide unit coverage but are not the active browser data boundary. These rules demonstrate interactions only and are not authoritative academic decisions.

Plans, chat messages, saved schedules and advisor feedback are stored under `syllo-planning-v3` in browser `localStorage`, matching the reference. They remain in one browser profile and do not sync. The mock SSO collects no credentials, and the role switch is not an access-control boundary.

When backend integration begins, keep API types in `src/lib/api/` and mirror `docs/api-contract.md`. Only the backend may call the AI provider or hold secrets.
