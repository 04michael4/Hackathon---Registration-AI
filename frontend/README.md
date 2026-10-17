# frontend

The Syllo course planner: Next.js 16 (App Router) + TypeScript (strict) + Tailwind CSS 4 + shadcn/ui (Radix).
It follows the UI/UX spec "Course Planner — UI UX Spec" (four tabs, mock login, demo data).

> Student hackathon prototype. Course data is made up (backend `CATALOG_SOURCE=sample`), prerequisites,
> requirements and transcripts are demo data in `src/lib/demo/program.ts`, and nothing registers anyone for classes.

## Run it

You need Node 20+ and the backend running.

```bash
# Terminal 1: backend with the opt-in sample catalog (no PostgreSQL needed)
cd backend
CATALOG_SOURCE=sample AI_PROVIDER=dummy ./mvnw spring-boot:run

# Terminal 2: frontend
cd frontend
npm install
npm run dev          # http://localhost:3000
```

The browser only calls same-origin `/api/...`; `next.config.ts` forwards it to `BACKEND_URL`
(default `http://localhost:8080`), so no CORS setup is needed and no backend URL reaches client code.

| Command | What it does |
|---|---|
| `npm run dev` | Dev server on port 3000 |
| `npm run build` | Production build (also type-checks) |
| `npm run lint` | ESLint |

## Where things are

| Path | Contents |
|---|---|
| `src/lib/api/` | `types.ts` mirrors `docs/api-contract.md`; `client.ts` turns Problem Details into readable errors |
| `src/lib/demo/program.ts` | Demo-only data the backend doesn't model: degree requirements, prerequisites, offered terms, transcripts, people, mock ratings |
| `src/lib/rules.ts` | One validation module: prerequisite order, "Recommended", requirement progress, drag checks, auto-fill, course statuses (Done, In progress, Planned, Review, Withdraw). Time conflicts come from the backend engine (`/api/schedules/check`), never from here. |
| `src/lib/interest.ts` | `searchByInterest`: the one function behind "Search by interest" (keyword + related-word match on titles and descriptions, electives first). Swap it out to change the matching. |
| `src/lib/store.tsx` | App state with undo/redo history and autosave to `localStorage` |
| `src/lib/catalog.tsx` | Loads the term's courses and sections from the backend once |
| `src/components/` | Shared course card, week calendar, app shell, prerequisite map (`prereq-map.tsx`), advisor status filter; `plan/` holds Tab 1's search bar, Recommended/Planned sidebar, chips, drawer and AI panel |
| `src/app/page.tsx` | Start page: one "Sign in with Lehigh SSO" button (a mock: nothing is asked for or sent) |
| `src/app/(app)/` | The features hub `home`, then the four tabs: `plan`, `degree`, `advisor`, `saved` |

## Design

David's prototype (`Syllo-frontend-calendar-chat.zip`) is the baseline: its start page, features hub, planner layout (search on top, calendar, right sidebar with Recommended and Planned classes, AI panel) and typefaces.
Lehigh brown and white are the palette, with gold as the one accent. Colors are defined once as CSS variables in `src/app/globals.css` (`:root`) and used through Tailwind tokens (`bg-brand`, `text-ink-muted`, …); components don't hard-code hex values.

| Token | Value | Use |
|---|---|---|
| `--brand` | `#562B0C` | Lehigh brown: primary buttons, selection, major-core courses |
| `--brand-mid` / `--brand-soft` | `#9C6B45` / `#F5EDE6` | Gen-ed courses / selected backgrounds |
| `--white`, `--page` | `#FFFFFF`, `#F8F7F5` | Surfaces, page background |
| `--ink`, `--ink-muted` | `#181818`, `#686868` | Text |
| `--line`, `--line-faint` | `#DDD8D3`, `#EEE9E4` | Borders, calendar lines |
| `--gold` / `--gold-soft` | `#F1C84B` / `#FFF3CB` | The one accent: the section a lecture still needs, "Review" tags, electives |
| `--danger` / `--danger-soft` | `#B3261E` / `#FBEAE8` | Status color for conflicts and errors only |

Type: Manrope for headings, codes and times; DM Sans for body text (David's choices).
Every screen fits 1440×900 without page scroll; long content scrolls inside its own panel, sits in the course drawer, or moves behind a tab (the prerequisite map) or a drawer (advisor comments).

## Known limits

- Professor preferences in the AI flow aren't supported: the engine has no instructor constraint yet.
- Prerequisites, requirements and transcripts are frontend demo data until the backend models them. The prerequisite map is built from that data (`COURSES[].prereqs`); the repo's real prerequisite research is mostly `NOT_COLLECTED`, and CSE 340's prerequisites (CSE 109, CSE 140) are an assumption.
- There's no CSB program in `data/degree-requirements/`, so the map follows the demo CS program, not the CSB flowchart's courses.
- Data is per browser (`localStorage`); student and advisor share it only when both sign in on the same browser, which is how the demo shows notifications.
