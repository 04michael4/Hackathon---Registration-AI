"use client";

import { useCallback, useEffect, useRef, useState } from "react";
import { createPortal } from "react-dom";
import { courseIdFromCode, loadCourseDetail, termIdFromPlanner } from "@/lib/api/catalogClient";
import type { CourseDetail } from "@/lib/api/types";
import { CatalogCourseDetails } from "./CatalogCourseDetails";
import { LiveCatalogBrowser } from "./LiveCatalogBrowser";

type Target = { element: HTMLElement; code: string; term: string; detail: boolean; key: number };
type Loader = (target: Target, retry?: boolean) => Promise<CourseDetail>;

function CatalogSlot({ target, load }: Readonly<{ target: Target; load: Loader }>) {
  const [result, setResult] = useState<{ course: CourseDetail | null; error: boolean } | null>(null);
  const [attempt, setAttempt] = useState(0);
  useEffect(() => {
    let active = true;
    load(target, attempt > 0).then(course => {
      if (active) setResult({ course, error: false });
    }).catch(() => {
      if (active) setResult({ course: null, error: true });
    });
    return () => { active = false; };
  }, [target, load, attempt]);
  if (!result) return <span role="status">Loading {target.detail ? "catalog details" : "prediction"}…</span>;
  if (result.error) return <div className="catalog-unavailable" role="status">
    {target.detail ? "Live catalog details unavailable." : "Prediction unavailable."}
    <button className="button quiet" type="button" onClick={() => setAttempt(value => value + 1)}>Retry</button>
  </div>;
  return result.course ? <CatalogCourseDetails course={result.course} /> : null;
}

/** Read-only runtime metadata. It never enters the demo store, localStorage, exports, or static HTML. */
export function CatalogMetadata({ enabled }: Readonly<{ enabled: boolean }>) {
  const [targets, setTargets] = useState<Target[]>([]);
  const requests = useRef(new Map<string, Promise<CourseDetail>>());
  const controller = useRef<AbortController | null>(null);
  const load = useCallback<Loader>((target, retry = false) => {
    const key = `${target.term}|${courseIdFromCode(target.code)}|${target.detail}`;
    if (retry) requests.current.delete(key);
    let request = requests.current.get(key);
    if (!request) {
      request = loadCourseDetail(target.code, target.term, controller.current?.signal);
      requests.current.set(key, request);
    }
    return request;
  }, []);

  useEffect(() => {
    if (!enabled) return;
    const abort = new AbortController();
    controller.current = abort;
    const cache = requests.current;
    let sequence = 0;
    const keys = new WeakMap<HTMLElement, number>();
    const sync = () => {
      const next: Target[] = [];
      document.querySelectorAll<HTMLElement>("[data-catalog-browser], [data-catalog-detail]").forEach(element => {
        const detail = element.hasAttribute("data-catalog-detail");
        if (detail && !element.closest(".detail-drawer.open")) return;
        const code = element.dataset.catalogDetail ?? "";
        try {
          if (detail) courseIdFromCode(code);
          const rawTerm = element.dataset.catalogTerm ?? "";
          const term = /^\d{4}-[A-Z]+$/.test(rawTerm) ? rawTerm : termIdFromPlanner(rawTerm);
          if (!keys.has(element)) keys.set(element, ++sequence);
          next.push({ element, code, term, detail, key: keys.get(element)! });
        } catch { /* Ignore non-course placeholders without exposing their contents. */ }
      });
      setTargets(previous => previous.length === next.length && previous.every((item, index) =>
        item.element === next[index].element && item.code === next[index].code && item.term === next[index].term
      ) ? previous : next);
    };
    const observer = new MutationObserver(sync);
    observer.observe(document.body, { childList: true, subtree: true, attributes: true, attributeFilter: ["class", "aria-hidden"] });
    // Initial discovery is asynchronous, just like later runtime changes.
    queueMicrotask(sync);
    return () => { observer.disconnect(); abort.abort(); cache.clear(); };
  }, [enabled]);

  return targets.map(target => createPortal(target.detail ? <CatalogSlot target={target} load={load} />
    : <LiveCatalogBrowser term={target.term} />, target.element, String(target.key)));
}
