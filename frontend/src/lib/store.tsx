"use client";

import { createContext, useCallback, useContext, useEffect, useMemo, useReducer, useSyncExternalStore, type ReactNode } from "react";

import { DEMO_ADVISOR, DEMO_STUDENT, NEXT_TERM, TERMS } from "./demo/program";
import { autoFill, type DegreePlan } from "./rules";

// ---------- State shape ----------

export type Role = "student" | "advisor";
export interface Session {
  role: Role;
  name: string;
}

export interface SavedSchedule {
  id: string;
  name: string;
  term: string;
  crns: string[];
  courseIds: string[];
  credits: number;
  updatedAt: string;
}

/** Everything the student edits. Each change to it is one undoable step. */
export interface Doc {
  /** Tab 1: placed sections and the courses in play (placed or being placed). */
  crns: string[];
  courses: string[];
  degree: DegreePlan;
  saved: SavedSchedule[];
  finalId: string | null;
}

export type PlanStatus = "Draft" | "Submitted" | "Changes requested" | "Approved";

export interface Comment {
  id: string;
  author: string;
  /** "plan", "term:<id>" or "course:<id>" */
  target: string;
  text: string;
  at: string;
}

export interface Notification {
  id: string;
  text: string;
  href: string;
  at: string;
  read: boolean;
}

interface State {
  session: Session | null;
  doc: Doc;
  past: { doc: Doc; label: string }[];
  future: { doc: Doc; label: string }[];
  status: PlanStatus;
  statusAt: string;
  comments: Comment[];
  notifications: Notification[];
}

const emptyDegree = (): DegreePlan => Object.fromEntries(TERMS.map((t) => [t.id, []]));
/** The pre-filled four-year template the student starts from. */
const templateDegree = (): DegreePlan => autoFill(DEMO_STUDENT, emptyDegree());

const initialState = (): State => ({
  session: null,
  doc: { crns: [], courses: [], degree: templateDegree(), saved: [], finalId: null },
  past: [],
  future: [],
  status: "Draft",
  statusAt: new Date().toISOString(),
  comments: [],
  notifications: [],
});

// ---------- Reducer ----------

type Action =
  | { type: "login"; session: Session }
  | { type: "logout" }
  | { type: "commit"; label: string; update: (doc: Doc) => Doc }
  | { type: "undo" }
  | { type: "redo" }
  | { type: "status"; status: PlanStatus }
  | { type: "comment"; comment: Comment }
  | { type: "notify"; text: string; href: string }
  | { type: "readAll" };

const HISTORY_LIMIT = 100;
const uid = () => Math.random().toString(36).slice(2, 10);

function reducer(state: State, action: Action): State {
  switch (action.type) {
    case "login":
      return { ...state, session: action.session };
    case "logout":
      return { ...state, session: null };
    case "commit": {
      const next = action.update(state.doc);
      if (next === state.doc) return state;
      return {
        ...state,
        doc: next,
        past: [...state.past, { doc: state.doc, label: action.label }].slice(-HISTORY_LIMIT),
        future: [],
      };
    }
    case "undo": {
      const last = state.past.at(-1);
      if (!last) return state;
      return { ...state, doc: last.doc, past: state.past.slice(0, -1), future: [{ doc: state.doc, label: last.label }, ...state.future] };
    }
    case "redo": {
      const next = state.future[0];
      if (!next) return state;
      return { ...state, doc: next.doc, future: state.future.slice(1), past: [...state.past, { doc: state.doc, label: next.label }] };
    }
    case "status":
      return { ...state, status: action.status, statusAt: new Date().toISOString() };
    case "comment":
      return { ...state, comments: [...state.comments, action.comment] };
    case "notify":
      return {
        ...state,
        notifications: [
          { id: uid(), text: action.text, href: action.href, at: new Date().toISOString(), read: false },
          ...state.notifications,
        ],
      };
    case "readAll":
      return { ...state, notifications: state.notifications.map((n) => ({ ...n, read: true })) };
  }
}

// ---------- Persistence (autosave draft) ----------

const STORAGE_KEY = "syllo-demo-v1";

function load(): State {
  const fresh = initialState();
  try {
    const raw = window.localStorage.getItem(STORAGE_KEY);
    if (!raw) return fresh;
    const saved = JSON.parse(raw) as Partial<State>;
    return {
      ...fresh,
      ...saved,
      doc: { ...fresh.doc, ...saved.doc, degree: saved.doc?.degree ? { ...emptyDegree(), ...saved.doc.degree } : fresh.doc.degree },
      past: [],
      future: [],
    };
  } catch {
    return fresh;
  }
}

function persist(state: State) {
  try {
    const { session, doc, status, statusAt, comments, notifications } = state;
    window.localStorage.setItem(STORAGE_KEY, JSON.stringify({ session, doc, status, statusAt, comments, notifications }));
  } catch {
    // Storage unavailable (private mode): the app still works, it just doesn't keep the draft.
  }
}

// ---------- Context ----------

interface Store extends State {
  login: (session: Session) => void;
  logout: () => void;
  commit: (label: string, update: (doc: Doc) => Doc) => void;
  undo: () => void;
  redo: () => void;
  canUndo: boolean;
  canRedo: boolean;
  undoLabel: string | null;
  redoLabel: string | null;
  setStatus: (status: PlanStatus) => void;
  addComment: (target: string, text: string) => void;
  notify: (text: string, href: string) => void;
  readAll: () => void;
  resetDemo: () => void;
}

const StoreContext = createContext<Store | null>(null);

const noopSubscribe = () => () => {};
/** True only in the browser after hydration, so stored data never mismatches server HTML. */
export function useHydrated(): boolean {
  return useSyncExternalStore(noopSubscribe, () => true, () => false);
}

function Provider({ children }: { children: ReactNode }) {
  const [state, dispatch] = useReducer(reducer, undefined, load);

  useEffect(() => {
    persist(state);
  }, [state]);

  const commit = useCallback((label: string, update: (doc: Doc) => Doc) => dispatch({ type: "commit", label, update }), []);
  const notify = useCallback((text: string, href: string) => dispatch({ type: "notify", text, href }), []);

  const store = useMemo<Store>(
    () => ({
      ...state,
      login: (session) => dispatch({ type: "login", session }),
      logout: () => dispatch({ type: "logout" }),
      commit,
      undo: () => dispatch({ type: "undo" }),
      redo: () => dispatch({ type: "redo" }),
      canUndo: state.past.length > 0,
      canRedo: state.future.length > 0,
      undoLabel: state.past.at(-1)?.label ?? null,
      redoLabel: state.future[0]?.label ?? null,
      setStatus: (status) => dispatch({ type: "status", status }),
      addComment: (target, text) =>
        dispatch({
          type: "comment",
          comment: { id: uid(), author: state.session?.name ?? DEMO_ADVISOR.name, target, text, at: new Date().toISOString() },
        }),
      notify,
      readAll: () => dispatch({ type: "readAll" }),
      resetDemo: () => {
        window.localStorage.removeItem(STORAGE_KEY);
        // A full reload starts from empty state; with no session the app returns to sign-in.
        window.location.reload();
      },
    }),
    [state, commit, notify],
  );

  return <StoreContext.Provider value={store}>{children}</StoreContext.Provider>;
}

/** Renders children only in the browser, where the saved draft can be read. */
export function StoreProvider({ children }: { children: ReactNode }) {
  const hydrated = useHydrated();
  if (!hydrated) return null;
  return <Provider>{children}</Provider>;
}

export function useStore(): Store {
  const store = useContext(StoreContext);
  if (!store) throw new Error("useStore must be used inside StoreProvider");
  return store;
}

export const student = DEMO_STUDENT;
export const NEXT = NEXT_TERM;
export { uid };
