# frontend

The Syllo course planner: Next.js 16 (App Router) + TypeScript (strict) + Tailwind CSS 4 + shadcn/ui (Radix).
It follows the UI/UX spec "Course Planner — UI UX Spec" (four tabs, mock login, demo data).

> Student hackathon prototype. Course data is made up (backend `CATALOG_SOURCE=demo`), prerequisites,
> requirements and transcripts are demo data in `src/lib/demo/program.ts`, and nothing registers anyone for classes.

## Run it

You need Node 20+ and the backend running.

```bash
# Terminal 1: backend with the opt-in demo catalog (no PostgreSQL needed)
cd backend
CATALOG_SOURCE=demo AI_PROVIDER=dummy ./mvnw spring-boot:run

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
| `src/lib/rules.ts` | One validation module: prerequisite order, "Recommended", requirement progress, drag checks, auto-fill. Time conflicts come from the backend engine (`/api/schedules/check`), never from here. |
| `src/lib/store.tsx` | App state with undo/redo history and autosave to `localStorage` |
| `src/lib/catalog.tsx` | Loads the term's courses and sections from the backend once |
| `src/components/` | Shared course card, week calendar, app shell; `plan/` holds Tab 1's panels |
| `src/app/(app)/` | The four tabs: `plan`, `degree`, `advisor`, `saved` |

## Design

Visual direction follows Anthropic's `frontend-design` guidance (avoid templated "AI-looking" defaults).
Concept: engineering graph paper. The weekly calendar is the one bold element; everything else stays quiet.

| Token | Value | Use |
|---|---|---|
| Paper | `#F4F7F9` | Background (cool, deliberately not cream) |
| Ink | `#17324D` | Text and primary buttons |
| Grid | `#C9D6E0` / `#E3EAF0` | Calendar hour / quarter-hour lines |
| Marker | `#F5C842` | Highlighter: the lab a lecture still needs, active tab |
| Signal | `#C23B30` | Conflicts only |
| Requirement tabs | `#2F6DB5` `#B26E14` `#2E8A63` `#8457A8` | Major core, gen ed, elective, free elective |

Type: Archivo (condensed via its width axis) for headings, codes and times; Public Sans for body text.
Avoided on purpose: cream + terracotta, all-caps eyebrow labels, identical rounded cards, arrows on buttons.

## Known limits

- Professor preferences in the AI flow aren't supported: the engine has no instructor constraint yet.
- Prerequisites, requirements and transcripts are frontend demo data until the backend models them.
- Data is per browser (`localStorage`); student and advisor share it only when both sign in on the same browser, which is how the demo shows notifications.
