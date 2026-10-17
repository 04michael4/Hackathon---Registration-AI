"use client";

import { createContext, useCallback, useContext, useEffect, useMemo, useState, type ReactNode } from "react";

import { api, ApiError } from "./api/client";
import type { CourseDetail, Section } from "./api/types";
import { API_TERM } from "./demo/program";

type CatalogState =
  | { status: "loading" }
  | { status: "error"; message: string }
  | { status: "ready"; courses: Record<string, CourseDetail> };

interface Catalog {
  state: CatalogState;
  courses: Record<string, CourseDetail>;
  /** CRN -> section and its course. */
  sectionByCrn: Map<string, { section: Section; course: CourseDetail }>;
  available: Set<string>;
  reload: () => void;
}

const CatalogContext = createContext<Catalog | null>(null);

async function loadCatalog(): Promise<Record<string, CourseDetail>> {
  const page = await api.searchCourses(API_TERM, 100);
  const details = await Promise.all(page.items.map((c) => api.course(API_TERM, c.courseId)));
  return Object.fromEntries(details.map((d) => [d.courseId, d]));
}

export function CatalogProvider({ children }: { children: ReactNode }) {
  const [state, setState] = useState<CatalogState>({ status: "loading" });
  const [attempt, setAttempt] = useState(0);

  useEffect(() => {
    let cancelled = false;
    loadCatalog()
      .then((courses) => !cancelled && setState({ status: "ready", courses }))
      .catch((error: unknown) => {
        if (cancelled) return;
        const message = error instanceof ApiError ? error.message : "Couldn't load the course catalog.";
        setState({ status: "error", message });
      });
    return () => {
      cancelled = true;
    };
  }, [attempt]);

  const reload = useCallback(() => {
    setState({ status: "loading" });
    setAttempt((n) => n + 1);
  }, []);

  const value = useMemo<Catalog>(() => {
    const courses = state.status === "ready" ? state.courses : {};
    const sectionByCrn = new Map<string, { section: Section; course: CourseDetail }>();
    for (const course of Object.values(courses)) {
      for (const section of course.sections) sectionByCrn.set(section.crn, { section, course });
    }
    return { state, courses, sectionByCrn, available: new Set(Object.keys(courses)), reload };
  }, [state, reload]);

  return <CatalogContext.Provider value={value}>{children}</CatalogContext.Provider>;
}

export function useCatalog(): Catalog {
  const catalog = useContext(CatalogContext);
  if (!catalog) throw new Error("useCatalog must be used inside CatalogProvider");
  return catalog;
}
