# Syllo frontend

The Syllo frontend is a Next.js App Router application written in strict TypeScript and React. Its root route hosts the Syllo reference markup, stylesheet cascade, assets and browser runtime directly, without an iframe. The semester and degree plans, transcript history, AI-style responses, saved schedules and advisor feedback remain browser-local demo data. An optional live catalog browser shows API course predictions, conditions and section instructor ratings separately from those demo plans.

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

### Live course predictions, conditions and instructors

For the team's private app, set this in `frontend/.env.local` and restart Next.js:

```dotenv
BACKEND_URL=http://127.0.0.1:8080
```

Run the backend with its existing database configuration and `DB_CATALOG_SCHEMA=catalog_serving`; install the catalog-serving views and ratings through their owner ([runbook](../data/catalog-serving/README.md)). The Plan My Semester screen then includes **Live course catalog**, with real API search and pagination. **Conditions & instructors** opens details for that exact API course, including all sections. These read-only courses are separate from the demo planner's simulated courses and times.

Course identifiers may include a numeric topic suffix, such as `CSE-398-042`. Search results, detail loading and the proxy preserve that suffix, matching the updated `pre-main` API contract.

`predictedRating` is always labeled **Predicted quality**, alongside confidence; details also show predicted difficulty and lecture-section coverage. Missing predictions remain unavailable. Course conditions retain their unverified status; an empty list never confirms eligibility. Each section shows its own instructors and predictions, including instructors with no earlier ratings, historical rating counts and whether that history is course-specific.

Instructor names are real. Use this connection only within the team's own private app; leave `BACKEND_URL` unset on public deployments until the Banner redistribution question is settled. Do not copy API responses or real names into source, fixtures, screenshots, exports or public materials. The connection uses the same-origin read-only `/api/catalog/courses` proxy with `private, no-store` responses. Names are loaded at runtime, never embedded in static build output or written to the demo store, browser storage or application logs. The proxy is disabled when `BACKEND_URL` is unset. Failures show an unavailable state and Retry.

On Windows, use `npm.cmd` if PowerShell blocks `npm.ps1`; no execution-policy change is needed.

Production build and server:

```bash
npm run build
npm run start
```

Quality checks:

```bash
npm run lint
npm test
npm run build && npm run verify:catalog
```

`verify:catalog` uses the installed Chrome executable, a production Next.js server and a loopback fake backend with synthetic instructors only. It checks live search/pagination, prediction labels/confidence, conditions, every section's ratings, null values, failures/retry, stale requests, private caching, storage separation and the default offline demo. It saves synthetic desktop/mobile screenshots in a temporary directory. Set `CHROME_PATH` if Chrome is elsewhere; no real API or database is used.

To check an already running development server, set `SYLLO_CATALOG_DEV_URL=http://localhost:3000` before running `verify:catalog`. This mode checks HTTP success, login/planner rendering, the catalog stylesheet and browser errors without starting a second development server. Catalog CSS is imported directly by `src/app/layout.tsx` so Next.js tracks it as a stylesheet dependency during development.

`npm run verify:degree` checks the calendar workspace and permanent navigation against a running app at `http://localhost:3000` (override `SYLLO_LAYOUT_URL` for a different dev or production URL). It checks twelve viewport sizes, closed/open plan tools, all semester headings, absence of the placeholder row, navigation visibility, minor search/staging, two-way drag/drop, staging removal, undo, individual-semester restoration, multi-select controls and the toolbar AI launcher. It also checks the unified semester course/interest search at desktop, tablet and phone widths, including aliases, prerequisites, details, add/drag/drop, undo, elective filtering and keyboard controls. It saves screenshots containing only the local demo in a temporary directory.

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

1. Enter through the clearly labeled mock Lehigh SSO screen; you land on Plan My Semester. All four app tabs, including Advisor portal, are always visible in the top ribbon.
2. Search and add eligible courses to the weekly semester calendar. Conflicts are highlighted and require an explicit keep/replace choice.
3. Open the local Syllo assistant, set day/time/credit preferences, preview its deterministic suggestion, and apply it after review.
4. Save, rename, reopen and remove named schedules. Undo restores recent planning changes in the current session.
5. Arrange the four-year degree plan with drag and drop. Term offerings and prerequisite order are checked before a move is accepted.
6. Fill both elective slots, submit a valid demo plan, and switch roles to leave advisor comments, request changes or approve it.

## Navigation

Signing in opens Plan My Semester directly; the old "What would you like to do?" hub was removed, and the Syllo logo also returns to Plan My Semester. The nav tabs read Plan My Semester, Degree Planner, Advisor portal and Saved schedules.

The ribbon stays visible on every signed-in screen without a Menu button, hover trigger or dropdown. Narrow screens wrap the same four tabs into a visible row beneath the logo and role button.

## Plan My Semester

- **Navigation** uses the persistent top ribbon described above. Switching pages, moving the pointer away or pressing Escape never hides the tabs.
- **Header**: the title is "Plan My Semester". Syllo AI ("Ask Syllo AI · AI planning assistant") and the registration countdown sit top right; the countdown logic is unchanged.
- **Schedule tools**: credits, Undo (names the change it will undo; disabled when there is nothing to undo), Save schedule and View bigger sit above the calendar. "Use in degree plan" was removed from this page.
- **Course and interest search** share one **Search courses & interests** box and one result list. Search by code, title, department, keyword or interest; codes ignore case, spaces and leading zeros (`cse17` finds CSE 017). Interests retain aliases such as finance → economics, security → systems/networks, and machine learning → data/statistics. Direct course matches come first; related interest results put electives first. All courses / Electives & prerequisites filtering, details, unmet-prerequisite previews, adding to the schedule, drag/drop and undo are retained. Enter focuses an available result action; Escape or the close button dismisses results.
- **Course list** merges Recommended and Planned classes. Each card shows a class-type mark (P = planned, filled; R = recommended, outlined) with the full meaning for screen readers, and filters for All / Planned / Recommended plus a text filter. A course is Planned when it is on the schedule and Recommended when it is recommended and not on it, exactly as the two old lists decided.
- **Professor ratings** (demo Rate My Professor data) color the rating chip on each card and the left edge of calendar blocks: Excellent 4.5+, Good 4.0–4.4, Mixed 3.0–3.9, Low below 3.0, and a dashed neutral "n/a" when there is no rating. The number is always shown, a legend explains the colors, and hovering or focusing a chip shows the professor, quality and difficulty.
- **Preview other sections**: right-click a scheduled course (or Shift+F10, or the dashed-squares button on its card) to show the term's other sections as dashed blocks, every meeting included (lectures, labs, recitations). Previews never change the schedule, credits, saved schedules, undo history or storage. Clear them with the same action, Escape, a click elsewhere, or Clear preview; they also clear when the course leaves the schedule or you leave the planner. Alternative sections are simulated demo data in `data.js`; CSE 109 has only one section, which shows the empty-state message.

## Degree Planner

- **Calendar first**: the calendar fills a dedicated viewport workspace on screens at least 360 pixels tall. All visible semesters stay inside it; crowded course lists scroll within their own semester. The full grid wraps into four columns on smaller laptops/tablets and two columns on phones. Short windows use four columns and compact controls, hiding the search filter tags and individual-semester selector while retaining search and year toggles. Calendar controls stay above both panes, so opening plan tools does not steal calendar height.
- **Header**: Lehigh University, the title "Degree Planner", and a profile menu (Plan My Semester, Advisor portal, Saved schedules, switch demo role). The old tagline and degree text are gone.
- **Unified search**: one search box for courses, minors, concentrations and interests, with filter tags Course code, Major, Minor, GenEd and Interest (several tags combine). Results open in the side panel, keeping the calendar uncovered. Results can be staged or added straight to a semester. Choosing a minor or concentration declares it and stages its courses that aren't planned yet.
- **Requirements & staged**: this toolbar button opens a compact side panel for requirements, credit trackers and unassigned courses. The panel is closed by default and scrolls independently. Drag staged courses into a semester (prerequisite order and offerings are checked) or drag planned courses into the open panel to unassign them. The trackers retain Humanities electives (8 credits), General education electives (8 credits) and each declared minor. The plan isn't ready for review while courses wait in staging. The review strip stays compact below the calendar; multi-select actions take their own row instead of floating over courses. The standalone placeholder row has been removed at the user's request, returning to this earlier layout.
- **Colors** (category, not course level): Major `#653819` brown, Minor/concentration `#E1AA29` gold, General education required slate navy, free and humanities electives cool gray. Each card also shows a text tag (Major, Minor, GenEd, Elective).
- **Selection**: Shift+click (or Shift+Enter) selects several courses across semesters and the staging area; dragging a box over empty space selects what it touches. Drag any selected card to move them all, or use the selection bar (Move, Remove). Delete removes the selection; Esc clears it. Completed courses can't be selected.
- **Free electives and the credit limit**: the two elective slots take a course of any credit amount (1, 3, 4, …). A semester can hold at most 21 credits; this is checked when choosing an elective, adding from search, and moving courses (one at a time or in bulk), and any semester over 21 is flagged in the plan check. The 120-credit graduation total still applies, so smaller electives may mean planning more courses.
- **Undo / redo**: Ctrl/⌘+Z and Ctrl/⌘+Shift+Z (Ctrl+Y also redoes), plus buttons beside the calendar title. Bulk actions are one undo step. Redo also works on Plan My Semester.
- **Syllo AI** opens from the star button in the calendar toolbar. Its floating launcher is hidden in the Degree Planner so it cannot cover the calendar corner or review button. The assistant window retains minimize, close, dock and full-screen controls. Plan My Semester keeps its header button.
- Demo data added for this (simulated, like the rest of `data.js`): an Economics minor (ECO 105, ECO 146), an Ethics & Technology concentration, SOC 001 and PSY 001 as general-education electives, and MUS 001 (a 1-credit free elective).
- The active screen is marked on `<body>` as `data-active-screen` (not `data-screen`, which identifies the screen sections).

## Data and persistence boundary

The optional live catalog browser calls Spring's course search/detail endpoints through a Next.js server proxy. API types and the client live in `src/lib/api/`; React portals mount `LiveCatalogBrowser` and `CatalogCourseDetails` inside the active reference runtime. It does not call an AI provider or connect live courses to the demo schedule solver. `public/reference/data.js` still owns the separate demo catalog/transcript fixture and local store. These planning rules demonstrate interactions only and are not authoritative academic decisions.

Plans, chat messages, saved schedules and advisor feedback are stored under `syllo-planning-v3` in browser `localStorage`, matching the reference. They remain in one browser profile and do not sync. The mock SSO collects no credentials, and the role switch is not an access-control boundary.

When backend integration begins, keep API types in `src/lib/api/` and mirror `docs/api-contract.md`. Only the backend may call the AI provider or hold secrets.
