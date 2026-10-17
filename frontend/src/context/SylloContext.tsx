"use client";

import { createContext, useCallback, useContext, useEffect, useMemo, useRef, useState } from "react";
import { catalog, defaultState } from "@/lib/mock-data";
import type { AppState, ChatMessage, Course, Screen } from "@/lib/types";

const STORAGE_KEY = "syllo-planning-v4";
const LEGACY_STORAGE_KEY = "syllo-planning-v3";

interface ToastMessage {
  title: string;
  detail: string;
}

interface SylloContextValue {
  state: AppState;
  hydrated: boolean;
  screen: Screen;
  selectedCourse: string | null;
  toast: ToastMessage | null;
  navigate: (screen: Screen) => void;
  setSelectedCourse: (id: string | null) => void;
  update: (label: string, mutator: (draft: AppState) => void) => void;
  undo: () => void;
  canUndo: boolean;
  showToast: (title: string, detail?: string) => void;
}

const SylloContext = createContext<SylloContextValue | null>(null);

function clone<T>(value: T): T {
  return JSON.parse(JSON.stringify(value)) as T;
}

function normalize(candidate: unknown): AppState {
  if (!candidate || typeof candidate !== "object") return clone(defaultState);
  const stored = candidate as Omit<Partial<AppState>, "version"> & { version?: number; chat?: ChatMessage[] };
  if (stored.version !== 3 && stored.version !== 4) return clone(defaultState);
  const state: AppState = {
    ...clone(defaultState),
    ...stored,
    version: 4,
    semesterChat: Array.isArray(stored.semesterChat) ? stored.semesterChat : Array.isArray(stored.chat) ? stored.chat : [],
    preferences: { ...clone(defaultState.preferences), ...stored.preferences },
    review: { ...clone(defaultState.review), ...stored.review },
  };
  state.planner = Array.isArray(state.planner) ? [...new Set(state.planner.filter((id) => catalog[id]))] : [...defaultState.planner];
  state.degree = Object.fromEntries(Object.keys(defaultState.degree).map((term) => [term, Array.isArray(state.degree?.[term]) ? state.degree[term].filter((id) => catalog[id]) : [...defaultState.degree[term]]]));
  state.electives = {
    "elective-a": state.electives?.["elective-a"] && catalog[state.electives["elective-a"]] ? state.electives["elective-a"] : null,
    "elective-b": state.electives?.["elective-b"] && catalog[state.electives["elective-b"]] ? state.electives["elective-b"] : null,
  };
  state.saved = Array.isArray(state.saved) ? state.saved.filter((item) => item && Array.isArray(item.courses)) : [];
  state.semesterChat = Array.isArray(state.semesterChat) ? state.semesterChat : [];
  state.degreeChat = Array.isArray(state.degreeChat) ? state.degreeChat : [];
  state.review.comments = Array.isArray(state.review.comments) ? state.review.comments : [];
  state.review.notifications = Array.isArray(state.review.notifications) ? state.review.notifications : [];
  return state;
}

export function SylloProvider({ children }: Readonly<{ children: React.ReactNode }>) {
  const [state, setState] = useState<AppState>(() => clone(defaultState));
  const [hydrated, setHydrated] = useState(false);
  const [screen, setScreen] = useState<Screen>("login");
  const [selectedCourse, setSelectedCourse] = useState<string | null>(null);
  const [toast, setToast] = useState<ToastMessage | null>(null);
  const [canUndo, setCanUndo] = useState(false);
  const history = useRef<{ label: string; state: AppState }[]>([]);
  const toastTimer = useRef<ReturnType<typeof setTimeout> | null>(null);

  useEffect(() => {
    const timer = window.setTimeout(() => {
      try {
        const stored = window.localStorage.getItem(STORAGE_KEY) ?? window.localStorage.getItem(LEGACY_STORAGE_KEY);
        if (stored) setState(normalize(JSON.parse(stored)));
      } catch {
        // The in-memory demo remains usable when storage is blocked or corrupt.
      }
      setHydrated(true);
    }, 0);
    return () => window.clearTimeout(timer);
  }, []);

  useEffect(() => {
    if (!hydrated) return;
    try {
      window.localStorage.setItem(STORAGE_KEY, JSON.stringify(state));
    } catch {
      // Keep the current session functional without persistent storage.
    }
  }, [hydrated, state]);

  const showToast = useCallback((title: string, detail = "") => {
    setToast({ title, detail });
    if (toastTimer.current) clearTimeout(toastTimer.current);
    toastTimer.current = setTimeout(() => setToast(null), 4500);
  }, []);

  const update = useCallback((label: string, mutator: (draft: AppState) => void) => {
    setState((current) => {
      const before = clone(current);
      const next = clone(current);
      mutator(next);
      if (JSON.stringify(before) === JSON.stringify(next)) return current;
      history.current.push({ label, state: before });
      if (history.current.length > 50) history.current.shift();
      return next;
    });
    setCanUndo(true);
  }, []);

  const undo = useCallback(() => {
    const previous = history.current.pop();
    if (!previous) return;
    setState(previous.state);
    setCanUndo(history.current.length > 0);
    showToast("Change undone", previous.label);
  }, [showToast]);

  const navigate = useCallback((next: Screen) => {
    setSelectedCourse(null);
    setScreen(next);
    window.scrollTo({ top: 0, behavior: "smooth" });
  }, []);

  const value = useMemo<SylloContextValue>(() => ({
    state,
    hydrated,
    screen,
    selectedCourse,
    toast,
    navigate,
    setSelectedCourse,
    update,
    undo,
    canUndo,
    showToast,
  }), [canUndo, hydrated, navigate, screen, selectedCourse, showToast, state, toast, undo, update]);

  return <SylloContext.Provider value={value}>{children}</SylloContext.Provider>;
}

export function useSyllo(): SylloContextValue {
  const context = useContext(SylloContext);
  if (!context) throw new Error("useSyllo must be used within SylloProvider");
  return context;
}

export function useSelectedCourse(): Course | null {
  const { selectedCourse } = useSyllo();
  return selectedCourse ? catalog[selectedCourse] ?? null : null;
}
