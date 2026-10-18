"use client";
import { useCallback, useEffect, useRef, useState, type DragEvent } from "react";
import { createPortal } from "react-dom";
import { loadCourseDetail, searchCatalog } from "@/lib/api/catalogClient";
import { ApiError, isRecord } from "@/lib/api/httpClient";
import { buildGenerateRequest, checkSchedule, defaultForm, explainSchedule, extractPreferences, formFromPreferences, generateSchedules, loadTerms, suggestElectives, validPreferences, type PreferenceForm } from "@/lib/api/scheduleClient";
import type { CheckResponse, CourseDetail, CourseSearchResponse, CourseSummary, ExplainResponse, ExtractResponse, GenerateRequest, GenerateResponse, Schedule, ScheduleSection, SuggestResponse, TermsResponse } from "@/lib/api/types";
import { CatalogCourseDetails, Prediction } from "./CatalogCourseDetails";
import { ApiPreferences } from "./ApiPreferences";
import { ApiWeekCalendar, COURSE_DRAG_TYPE, ScheduleAlternatives, ScheduleFacts, type CalendarGhost } from "./ApiScheduleResults";
import { buildSemesterPlan } from "@/lib/semesterPlan";
import { firstFittingPackage, meetingsOverlap, replaceCourse, sectionMoves, sectionPackages, swapOptions, type DetailSection, type SwapOption } from "@/lib/sectionPackages";
import { sectionDifficulty } from "@/lib/difficulty";
import { SectionMenu } from "./SectionMenu";

interface SavedPlan { id: string; name: string; request: GenerateRequest; crns: string[]; courseIds: string[] }
const savedKey = "smartcourse-api-plans-v1";
function readSaved(): SavedPlan[] {
  try {
    const value: unknown = JSON.parse(localStorage.getItem(savedKey) ?? "[]");
    if (!Array.isArray(value)) return [];
    return value.filter((item: unknown): item is SavedPlan => isRecord(item) && typeof item.id === "string" && typeof item.name === "string" && Array.isArray(item.crns) && item.crns.length <= 60 && item.crns.every((crn: unknown) => typeof crn === "string") && Array.isArray(item.courseIds) && item.courseIds.length <= 30 && item.courseIds.every((id: unknown) => typeof id === "string") && isRecord(item.request) && typeof item.request.term === "string" && typeof item.request.includeClosedSections === "boolean" && Number.isInteger(item.request.maxResults) && Number(item.request.maxResults) >= 1 && Number(item.request.maxResults) <= 10 && validPreferences(item.request.preferences));
  } catch { return []; }
}
export function ErrorNotice({ error, retry }: Readonly<{ error: Error | null; retry?: () => void }>) {
  if (!error) return null;
  return <div className="api-error" role="alert"><strong>{error.message}</strong>{error instanceof ApiError && <>{error.errors.length > 0 && <ul>{error.errors.map((e, i) => <li key={i}><strong>{e.field}:</strong> {e.message}</li>)}</ul>}{error.requestId && <small>Request reference: {error.requestId}</small>}</>}{retry && <button type="button" className="button" onClick={retry}>Retry</button>}</div>;
}
const asError = (value: unknown) => value instanceof Error ? value : new Error("The request failed. Please retry.");

export function ConnectedPlanner({ host, savedHost }: Readonly<{ host: Element; savedHost: Element | null }>) {
  const [terms, setTerms] = useState<TermsResponse["items"]>([]), [term, setTerm] = useState("");
  const [termsError, setTermsError] = useState<Error | null>(null), [termsLoading, setTermsLoading] = useState(true), [reload, setReload] = useState(0);
  const [q, setQ] = useState(""), [subject, setSubject] = useState(""), [search, setSearch] = useState({ q: "", subject: "", page: 0 });
  const [catalog, setCatalog] = useState<CourseSearchResponse | null>(null), [catalogLoading, setCatalogLoading] = useState(false), [catalogError, setCatalogError] = useState<Error | null>(null);
  const [selected, setSelected] = useState<Record<string, "required" | "optional">>({});
  const [form, setForm] = useState<PreferenceForm>(defaultForm);
  const [result, setResult] = useState<GenerateResponse | null>(null), [alternative, setAlternative] = useState(0), [manual, setManual] = useState<CheckResponse | null>(null);
  const [request, setRequest] = useState<GenerateRequest | null>(null), [busy, setBusy] = useState(""), [error, setError] = useState<Error | null>(null), [notice, setNotice] = useState("");
  const [detail, setDetail] = useState<CourseDetail | null>(null), [detailLoading, setDetailLoading] = useState(false), [detailError, setDetailError] = useState<Error | null>(null), [detailId, setDetailId] = useState("");
  const [prompt, setPrompt] = useState(""), [extraction, setExtraction] = useState<ExtractResponse | null>(null), [explanation, setExplanation] = useState<ExplainResponse | null>(null), [suggestions, setSuggestions] = useState<SuggestResponse | null>(null);
  const [saved, setSaved] = useState<SavedPlan[]>(readSaved);
  const [assistantOpen, setAssistantOpen] = useState(false), [searchOpen, setSearchOpen] = useState(false);
  const [listFilter, setListFilter] = useState("all"), [listQuery, setListQuery] = useState("");
  const [knownCourses, setKnownCourses] = useState<Record<string, CourseSummary>>({});
  const [termHost] = useState(() => document.getElementById("api-term-header"));
  const [history, setHistory] = useState<{ past: string[][]; future: string[][] }>({ past: [], future: [] });
  const [details, setDetails] = useState<Record<string, CourseDetail>>({}), [moving, setMoving] = useState<ScheduleSection | null>(null);
  const [menu, setMenu] = useState<{ section: ScheduleSection; x: number; y: number } | null>(null);
  // What the dashed preview boxes follow: a hovered placed class, a hovered course card, a dragged course card, a hovered swap option, and the drop box under the pointer.
  const [hoverSection, setHoverSection] = useState<ScheduleSection | null>(null), [cardHover, setCardHover] = useState<string | null>(null);
  const [draggingCourse, setDraggingCourse] = useState<string | null>(null), [menuPreview, setMenuPreview] = useState<SwapOption | null>(null), [ghostFocus, setGhostFocus] = useState<string | null>(null);
  const hoverTimer = useRef<number | undefined>(undefined), prefetching = useRef(new Set<string>());
  const boardCourses = useRef<string[]>([]);
  const operation = useRef<AbortController | null>(null), detailOperation = useRef<AbortController | null>(null);
  const dialog = useRef<HTMLDialogElement>(null);
  useEffect(() => () => { operation.current?.abort(); detailOperation.current?.abort(); }, []);
  useEffect(() => {
    const button = document.getElementById("syllo-ai-toggle");
    const toggle = () => setAssistantOpen(open => !open);
    const dismiss = (event: PointerEvent) => { if (event.target instanceof Element && !event.target.closest(".api-catalog-panel")) setSearchOpen(false); };
    const escape = (event: KeyboardEvent) => { if (event.key === "Escape") { setSearchOpen(false); setAssistantOpen(false); } };
    button?.addEventListener("click", toggle); document.addEventListener("pointerdown", dismiss); document.addEventListener("keydown", escape);
    return () => { button?.removeEventListener("click", toggle); document.removeEventListener("pointerdown", dismiss); document.removeEventListener("keydown", escape); };
  }, []);
  useEffect(() => {
    const button = document.getElementById("syllo-ai-toggle");
    button?.setAttribute("aria-controls", "api-planning-panel"); button?.setAttribute("aria-expanded", String(assistantOpen));
  }, [assistantOpen]);
  useEffect(() => {
    const controller = new AbortController();
    Promise.resolve().then(() => { if (!controller.signal.aborted) { setTermsLoading(true); setTermsError(null); } return loadTerms(controller.signal); }).then(data => { if (controller.signal.aborted) return; setTerms(data.items); setTerm(current => data.items.some(t => t.term === current) ? current : data.items[0]?.term ?? ""); }).catch(e => { if (!controller.signal.aborted) setTermsError(asError(e)); }).finally(() => { if (!controller.signal.aborted) setTermsLoading(false); });
    return () => controller.abort();
  }, [reload]);
  useEffect(() => {
    if (!term) return;
    const controller = new AbortController();
    Promise.resolve().then(() => { if (!controller.signal.aborted) { setCatalogLoading(true); setCatalogError(null); setCatalog(null); } return searchCatalog(search.q, term, search.page, controller.signal, search.subject); }).then(data => { if (!controller.signal.aborted) { setCatalog(data); setKnownCourses(current => ({ ...current, ...Object.fromEntries(data.items.map(course => [course.courseId, course])) })); } }).catch(e => { if (!controller.signal.aborted) setCatalogError(asError(e)); }).finally(() => { if (!controller.signal.aborted) setCatalogLoading(false); });
    return () => controller.abort();
  }, [term, search, reload]);
  function invalidate() { operation.current?.abort(); setBusy(""); setResult(null); setManual(null); setRequest(null); setError(null); setExplanation(null); setSuggestions(null); setNotice(""); setHistory({ past: [], future: [] }); boardCourses.current = []; }
  function updateForm(value: PreferenceForm) { invalidate(); setForm(value); }
  function makeRequest() { return buildGenerateRequest(term, Object.keys(selected).filter(id => selected[id] === "required"), Object.keys(selected).filter(id => selected[id] === "optional"), form); }
  async function run(label: string, action: (signal: AbortSignal) => Promise<void>) {
    operation.current?.abort(); const controller = new AbortController(); operation.current = controller; setBusy(label); setError(null); setNotice("");
    try { await action(controller.signal); } catch (e) { if (!controller.signal.aborted) setError(asError(e)); } finally { if (!controller.signal.aborted) setBusy(""); }
  }
  function generate() {
    setResult(null); setManual(null); setExplanation(null); setSuggestions(null); setHistory({ past: [], future: [] }); boardCourses.current = [];
    void run("Generating schedules", async signal => { const body = makeRequest(); const data = await generateSchedules(body, signal); if (data.term !== term) throw new Error("The backend returned a different catalog term."); setRequest(body); setResult(data); setAlternative(0); });
  }
  const openDetail = useCallback(async (id: string) => {
    detailOperation.current?.abort(); const controller = new AbortController(); detailOperation.current = controller;
    setDetailId(id); setDetail(null); setDetailError(null); setDetailLoading(true); dialog.current?.showModal();
    try { const loaded = await loadCourseDetail(id, term, controller.signal); setDetail(loaded); setDetails(current => ({ ...current, [loaded.courseId]: loaded })); } catch (e) { if (!controller.signal.aborted) setDetailError(asError(e)); } finally { if (!controller.signal.aborted) setDetailLoading(false); }
  }, [term]);
  const active: Schedule | undefined = manual ? { ...manual, rank: 0 } : result?.schedules[alternative];
  const boardKey = [...new Set(active?.sections.map(section => section.courseId) ?? [])].join(" ");
  useEffect(() => {
    // Section details for courses on the calendar, so dragging a placed section can show where it may move.
    const missing = boardKey.split(" ").filter(id => id && !details[id]);
    if (!term || !missing.length) return;
    const controller = new AbortController();
    void Promise.allSettled(missing.map(id => loadCourseDetail(id, term, controller.signal))).then(results => {
      if (controller.signal.aborted) return;
      const loaded = results.flatMap(r => r.status === "fulfilled" ? [r.value] : []);
      if (loaded.length) setDetails(current => ({ ...current, ...Object.fromEntries(loaded.map(d => [d.courseId, d])) }));
    });
    return () => controller.abort();
  }, [boardKey, term, details]);
  useEffect(() => {
    const plan = buildSemesterPlan(term, terms.find(item => item.term === term)?.name ?? term, selected, knownCourses, active?.sections);
    document.dispatchEvent(new CustomEvent("syllo:semester-plan", { detail: plan }));
  }, [term, terms, selected, knownCourses, active?.sections]);
  useEffect(() => {
    const open = (event: Event) => {
      if (!(event instanceof CustomEvent) || typeof event.detail !== "string" || !/^[A-Z]+-\d+(?:-\d+)?$/.test(event.detail)) return;
      void openDetail(event.detail);
    };
    document.addEventListener("syllo:open-semester-course", open);
    return () => document.removeEventListener("syllo:open-semester-course", open);
  }, [openDetail]);
  /** Ask the backend to check a set of CRNs. `nextSelected` applies a selection change in the same request (placing a section requires its course). */
  async function applyCheck(signal: AbortSignal, crns: string[], extraCourse?: string, direction?: "undo" | "redo", nextSelected?: Record<string, "required" | "optional">, done?: string, drop?: string) {
    const previous = active?.sections.map(s => s.crn) ?? [];
    const sel = nextSelected ?? selected;
    const body = buildGenerateRequest(term, Object.keys(sel).filter(id => sel[id] === "required"), Object.keys(sel).filter(id => sel[id] === "optional"), form);
    const courseIds = [...new Set([...boardCourses.current, ...(active?.sections.map(s => s.courseId) ?? []), ...(extraCourse ? [extraCourse] : [])])].filter(id => id !== drop);
    if (courseIds.length > 30 || crns.length > 60) throw new Error("Manual plans support at most 30 additional courses and 60 sections.");
    const data = await checkSchedule({ term, preferences: body.preferences, courseIds, crns: [...new Set(crns)], includeClosedSections: body.includeClosedSections }, signal);
    boardCourses.current = courseIds;
    if (nextSelected) setSelected(nextSelected);
    setHistory(current => direction === "undo" ? { past: current.past.slice(0, -1), future: [...current.future, previous] } : direction === "redo" ? { past: [...current.past, previous], future: current.future.slice(0, -1) } : { past: [...current.past, previous].slice(-30), future: [] });
    setRequest(body); setManual(data); setExplanation(null); setSuggestions(null);
    if (done) setNotice(done);
  }
  function manualCheck(crns: string[], extraCourse?: string, direction?: "undo" | "redo") {
    void run("Checking sections", signal => applyCheck(signal, crns, extraCourse, direction));
  }
  const requireCourse = (courseId: string) => ({ ...selected, [courseId]: "required" as const });
  function addCrn(crn: string, courseId?: string) {
    void run("Checking sections", signal => applyCheck(signal, [...(active?.sections.map(s => s.crn) ?? []), crn], courseId, undefined, courseId ? requireCourse(courseId) : undefined));
  }
  /** Put a whole package (a lecture with its linked lab/recitation) on the calendar, replacing that course's current sections. */
  function placePackage(courseId: string, crns: string[], label: string) {
    setMoving(null); setMenu(null);
    void run("Placing sections", signal => applyCheck(signal, replaceCourse(active?.sections ?? [], courseId, crns), courseId, undefined, requireCourse(courseId), `${courseId}: ${label} placed. The backend checked links, conflicts and credits.`));
  }
  /** A course card dropped on the calendar, or Add to plan / Add to next semester: place the first package that is open and fits the other courses; drag its sections to change it. */
  function dropCourse(courseId: string) {
    if (active?.sections.some(section => section.courseId === courseId)) { setNotice(`${courseId} is already on the calendar. Drag its sections onto a highlighted slot to switch times.`); return; }
    void run("Placing course", async signal => {
      const loaded = details[courseId] ?? await loadCourseDetail(courseId, term, signal);
      setDetails(current => ({ ...current, [loaded.courseId]: loaded }));
      const chosen = firstFittingPackage(sectionPackages(loaded), active?.sections ?? [], form.includeClosedSections);
      if (!chosen) { setSelected(requireCourse(courseId)); setNotice(`${courseId} added to your plan. It has no sections with linked meetings to place on the calendar; open its details to review them.`); return; }
      await applyCheck(signal, replaceCourse(active?.sections ?? [], courseId, chosen.crns), courseId, undefined, requireCourse(courseId), `${courseId}: ${chosen.label} placed. Drag a section onto a highlighted slot to switch.`);
    });
  }
  /** Take a course off the plan and the calendar, keeping every other placed section (never clears the whole schedule). */
  function removeCourse(courseId: string) {
    setMenu(null); setMenuPreview(null);
    const next = { ...selected }; delete next[courseId];
    const remaining = (active?.sections ?? []).filter(section => section.courseId !== courseId);
    for (const section of remaining) next[section.courseId] ??= "required";
    if (!active?.sections.some(section => section.courseId === courseId)) {
      setSelected(next); boardCourses.current = boardCourses.current.filter(id => id !== courseId); setNotice(`${courseId} removed from your plan.`); return;
    }
    if (!remaining.length) { invalidate(); setSelected(next); setNotice(`${courseId} removed. Your calendar is empty.`); return; }
    void run("Removing course", signal => applyCheck(signal, remaining.map(section => section.crn), undefined, undefined, next, `${courseId} removed from the calendar. Undo brings it back.`, courseId));
  }
  const difficultyOf = (courseId: string, crn: string) => sectionDifficulty(details[courseId], crn);
  /** Load a course's sections in the background (hovering or dragging its card) so its previews can appear without a click. */
  const prefetch = useCallback((courseId: string) => {
    if (!term || details[courseId] || prefetching.current.has(courseId)) return;
    prefetching.current.add(courseId);
    void loadCourseDetail(courseId, term).then(loaded => setDetails(current => ({ ...current, [loaded.courseId]: loaded }))).catch(() => undefined).finally(() => prefetching.current.delete(courseId));
  }, [term, details]);
  /** Hover previews wait a moment so sweeping the pointer across the list does not flicker the calendar; leaving clears at once. */
  const hoverSoon = (apply: () => void) => { window.clearTimeout(hoverTimer.current); hoverTimer.current = window.setTimeout(apply, 180); };
  const hoverOff = (apply: () => void) => { window.clearTimeout(hoverTimer.current); apply(); };
  useEffect(() => () => window.clearTimeout(hoverTimer.current), []);
  const board = active?.sections ?? [];
  const ghostOf = (courseId: string, s: DetailSection, crns: string[], droppable: boolean, note?: string): CalendarGhost =>
    ({ key: `${droppable ? "drop" : "preview"}:${s.crn}:${crns.join("+")}`, courseId, crn: s.crn, sectionCode: s.sectionCode, componentType: s.componentType, meetings: s.meetings, crns, instructors: s.instructors, difficulty: difficultyOf(courseId, s.crn), droppable, note,
      overlaps: [...new Set(board.filter(b => b.courseId !== courseId && meetingsOverlap(s.meetings, b.meetings)).map(b => b.courseId))] });
  /** While the pointer is over a drop box, also outline the sections that come with it (the lab of a lecture), unless they are already placed. */
  const companions = (courseId: string, drop: CalendarGhost | undefined) => {
    const d = details[courseId];
    if (!d || !drop) return [];
    return drop.crns.filter(crn => crn !== drop.crn && !board.some(s => s.crn === crn)).flatMap(crn => { const s = d.sections.find(x => x.crn === crn); return s ? [ghostOf(courseId, s, drop.crns, false, `Comes with ${drop.componentType.toLowerCase()} ${drop.sectionCode}`)] : []; });
  };
  let ghosts: CalendarGhost[] = [], fadedCrns: string[] = [];
  if (moving && details[moving.courseId]) {
    const drops = sectionMoves(details[moving.courseId], board, moving.crn, form.includeClosedSections).map(m => ghostOf(moving.courseId, m.section, m.crns, true, "Drop to switch"));
    const focused = drops.find(g => g.key === ghostFocus);
    ghosts = [...drops, ...companions(moving.courseId, focused)];
  } else if (draggingCourse && details[draggingCourse]) {
    const d = details[draggingCourse], others = board.filter(s => s.courseId !== d.courseId), packages = sectionPackages(d);
    const drops = [...new Set(packages.map(p => p.crns[0]))].flatMap(lectureCrn => {
      const chosen = firstFittingPackage(packages.filter(p => p.crns[0] === lectureCrn), others, form.includeClosedSections);
      const lecture = d.sections.find(s => s.crn === lectureCrn);
      return chosen && lecture ? [ghostOf(d.courseId, lecture, chosen.crns, true, "Drop here")] : [];
    });
    const focused = drops.find(g => g.key === ghostFocus);
    ghosts = [...drops, ...companions(d.courseId, focused)];
  } else if (menu && menuPreview) {
    const d = details[menu.section.courseId];
    ghosts = d ? menuPreview.sections.map(s => ghostOf(d.courseId, s, menuPreview.crns, false, "Would move here")) : [];
    fadedCrns = board.filter(s => s.courseId === menu.section.courseId && !menuPreview.crns.includes(s.crn)).map(s => s.crn);
  } else if (!menu && hoverSection && details[hoverSection.courseId]) {
    ghosts = sectionMoves(details[hoverSection.courseId], board, hoverSection.crn, form.includeClosedSections).map(m => ghostOf(hoverSection.courseId, m.section, m.crns, false, "Drag here to switch"));
  } else if (!menu && cardHover && details[cardHover] && !board.some(s => s.courseId === cardHover)) {
    const chosen = firstFittingPackage(sectionPackages(details[cardHover]), board, form.includeClosedSections);
    ghosts = chosen ? chosen.sections.map(s => ghostOf(cardHover, s, chosen.crns, false, "Would go here")) : [];
  }
  const menuOptions: SwapOption[] = menu && details[menu.section.courseId] ? swapOptions(details[menu.section.courseId], active?.sections ?? [], menu.section.crn) : [];
  const closeMenu = useCallback(() => { setMenu(null); setMenuPreview(null); }, []);
  /** Course cards: hovering previews where the course would go; dragging shows a drop box for every lecture option. */
  const cardEvents = (courseId: string) => ({
    draggable: true,
    title: "Drag onto the calendar; point at it to preview where it would go",
    onDragStart: (e: DragEvent) => { e.dataTransfer.setData(COURSE_DRAG_TYPE, courseId); e.dataTransfer.effectAllowed = "copy"; hoverOff(() => setCardHover(null)); setGhostFocus(null); setDraggingCourse(courseId); prefetch(courseId); },
    onDragEnd: () => { setDraggingCourse(null); setGhostFocus(null); },
    onPointerEnter: () => { prefetch(courseId); hoverSoon(() => setCardHover(courseId)); },
    onPointerLeave: () => hoverOff(() => setCardHover(current => current === courseId ? null : current)),
    onFocus: () => { prefetch(courseId); hoverSoon(() => setCardHover(courseId)); },
    onBlur: () => hoverOff(() => setCardHover(current => current === courseId ? null : current)),
  });
  function persist(next: SavedPlan[]) { try { localStorage.setItem(savedKey, JSON.stringify(next)); setSaved(next); } catch { setError(new Error("Browser storage is unavailable. Your schedule is still visible.")); } }
  function save() {
    if (!active || !request) return;
    const name = window.prompt("Name this browser-local plan", `${term} plan`); if (!name?.trim()) return;
    persist([...saved, { id: crypto.randomUUID(), name: name.trim().slice(0, 120), request, crns: active.sections.map(s => s.crn), courseIds: [...new Set(active.sections.map(s => s.courseId))] }]); setNotice("Plan saved in this browser. Course details will be refreshed when reopened.");
  }
  function loadSaved(plan: SavedPlan) {
    invalidate(); setTerm(plan.request.term); setForm(formFromPreferences(plan.request.preferences, { ...defaultForm, includeClosedSections: plan.request.includeClosedSections, maxResults: String(plan.request.maxResults) }));
    setSelected(Object.fromEntries([...plan.request.preferences.hardConstraints.requiredCourseIds.map(id => [id, "required"]), ...(plan.request.preferences.softPreferences?.optionalCourseIds?.value ?? []).map(id => [id, "optional"])]));
    document.querySelector<HTMLButtonElement>('[data-route="planner"]')?.click();
    void run("Refreshing saved plan", async signal => { const data = await checkSchedule({ term: plan.request.term, preferences: plan.request.preferences, courseIds: plan.courseIds ?? [], crns: plan.crns, includeClosedSections: plan.request.includeClosedSections }, signal); setManual(data); setRequest(plan.request); });
  }
  const errors = error instanceof ApiError ? error.errors : [];
  const changeTerm = (value: string) => { invalidate(); setTerm(value); setSelected({}); setKnownCourses({}); setDetails({}); setMoving(null); setQ(""); setSubject(""); setSearchOpen(false); setSearch({ q: "", subject: "", page: 0 }); setDetail(null); dialog.current?.close(); };
  const listedIds = [...new Set([...Object.keys(selected), ...(catalog?.items.map(course => course.courseId) ?? [])])].filter(id => {
    const planned = Boolean(selected[id]);
    return (listFilter === "all" || (listFilter === "planned" ? planned : !planned)) && `${id} ${knownCourses[id]?.title ?? ""}`.toLowerCase().includes(listQuery.toLowerCase());
  });
  const courseDetails = (id: string) => { setSearchOpen(false); void openDetail(id); };
  return <>{termHost && createPortal(<label className="api-term-label">Planning term<select aria-label="Catalog term" value={term} disabled={termsLoading || !terms.length} onChange={e => changeTerm(e.target.value)}><option value="" disabled>{termsLoading ? "Loading terms…" : "Select term"}</option>{terms.map(t => <option key={t.term} value={t.term}>{t.name}</option>)}</select></label>, termHost)}{createPortal(<div className="api-planner" data-api-planner>
    <div className={`api-planner-columns${assistantOpen ? " is-assistant-open" : ""}`}>
      <div className="api-semester-main manual-planner">
        <div className="api-catalog-panel course-search-area">
          <form className="api-search" onSubmit={e => { e.preventDefault(); setSearchOpen(true); setSearch({ q: q.trim(), subject: subject.trim(), page: 0 }); }}>
            <label className="api-search-label">Search courses &amp; interests<div className="api-search-control"><svg viewBox="0 0 24 24" aria-hidden="true"><circle cx="11" cy="11" r="7"/><path d="m16 16 5 5"/></svg><input aria-label="Search courses and interests" maxLength={100} value={q} onFocus={() => setSearchOpen(true)} onChange={e => setQ(e.target.value)} placeholder="Course code, title, or interest (e.g. finance)" aria-controls="api-search-results"/><button className="button primary" disabled={!term} aria-expanded={searchOpen} aria-controls="api-search-results">Search</button></div></label>
            <label className="api-subject-label">Subject<input aria-label="Subject filter" maxLength={10} pattern="[A-Za-z]*" value={subject} onChange={e => setSubject(e.target.value.toUpperCase())} placeholder="All subjects" /></label>
          </form>
          <p className="catalog-note">Live catalog · Course codes and title keywords · Eligibility and enrollment are not confirmed.</p>
          {termsLoading && <p role="status">Loading catalog terms…</p>}
          <ErrorNotice error={termsError} retry={() => setReload(v => v + 1)} />
          {!termsLoading && !termsError && !terms.length && <p role="status">No catalog terms are available. Ask the catalog maintainer to configure a term.</p>}
          <div id="api-search-results" className="api-search-dropdown" hidden={!searchOpen}>
            <div className="api-row"><strong>{catalog ? `${catalog.totalItems} courses found` : "Course search"}</strong><button type="button" className="api-close" aria-label="Close course search results" onClick={() => setSearchOpen(false)}>×</button></div>
            {catalogLoading && <p role="status">Loading courses…</p>}<ErrorNotice error={catalogError} retry={() => setReload(v => v + 1)} />
            {catalog && <>{!catalog.items.length && <p role="status">No courses match these filters. Try another code, subject or topic.</p>}<div className="api-course-results">{catalog.items.map(course => <article key={course.courseId} data-api-course={course.courseId} {...cardEvents(course.courseId)}>
              <div className="api-row"><strong>{course.courseId}</strong><span>{course.credits} credits · {course.sectionCount} sections</span></div><h3>{course.title}</h3><Prediction rating={course.predictedRating} compact />
              <div className="api-row"><button type="button" className="button primary" data-add-next-semester={course.courseId} disabled={Boolean(busy) || selected[course.courseId] === "required" || active?.sections.some(section => section.courseId === course.courseId)} onClick={() => dropCourse(course.courseId)}>{selected[course.courseId] === "required" || active?.sections.some(section => section.courseId === course.courseId) ? "Added to next semester" : "Add to next semester"}</button><button type="button" className="button" onClick={() => courseDetails(course.courseId)}>Details &amp; sections</button></div>
            </article>)}</div><div className="api-row api-search-pages"><button type="button" disabled={catalog.page === 0} onClick={() => setSearch(s => ({ ...s, page: s.page - 1 }))}>Previous</button><span>Page {catalog.totalPages ? catalog.page + 1 : 0} of {catalog.totalPages}</span><button type="button" disabled={catalog.page + 1 >= catalog.totalPages} onClick={() => setSearch(s => ({ ...s, page: s.page + 1 }))}>Next</button></div></>}
          </div>
        </div>
        <ErrorNotice error={error} />{notice && <p role="status">{notice}</p>}{busy && <p role="status">{busy}…</p>}
        <div className="api-schedule-workspace schedule-workspace">
          <div className="api-calendar-column schedule-calendar-column">
            <ApiWeekCalendar toolbar={<>
              <button type="button" className="button quiet sem-undo" disabled={Boolean(busy) || !history.past.length} aria-label="Undo section change" onClick={() => manualCheck(history.past.at(-1) ?? [], undefined, "undo")}>Undo</button>
              <button type="button" className="button quiet" disabled={Boolean(busy) || !history.future.length} aria-label="Redo section change" onClick={() => manualCheck(history.future.at(-1) ?? [], undefined, "redo")}>Redo</button>
              <button type="button" className="button primary sem-save" disabled={!active || !request} onClick={save}>Save schedule</button>
            </>} totalCredits={active?.totalCredits ?? 0} sections={active?.sections ?? []} onDrop={crn => addCrn(crn, detail?.sections.some(s => s.crn === crn) ? detail.courseId : undefined)} onCourseDrop={dropCourse} onRemoveCourse={section => removeCourse(section.courseId)} onSectionMenu={(section, x, y) => setMenu({ section, x, y })} difficultyFor={section => difficultyOf(section.courseId, section.crn)} onSectionDragStart={section => { setMenu(null); hoverOff(() => setHoverSection(null)); setGhostFocus(null); setMoving(section); }} onSectionHover={section => section ? (moving || draggingCourse ? undefined : (prefetch(section.courseId), hoverSoon(() => setHoverSection(section)))) : hoverOff(() => setHoverSection(null))} fadedCrns={fadedCrns} onGhostEnter={ghost => setGhostFocus(ghost.key)} onSectionDragEnd={() => { setMoving(null); setGhostFocus(null); }} ghosts={ghosts} onGhostDrop={ghost => { setDraggingCourse(null); setGhostFocus(null); placePackage(ghost.courseId, ghost.crns, `${ghost.componentType.charAt(0)}${ghost.componentType.slice(1).toLowerCase()} ${ghost.sectionCode}`); }} onRemove={crn => manualCheck(active?.sections.filter(s => s.crn !== crn).map(s => s.crn) ?? [])} onOpenCourse={courseDetails} />
            {active && <ScheduleFacts schedule={active} />}
          </div>
          <aside className="api-course-sidebar planner-course-sidebar" aria-label="Course list"><section className="course-list-panel">
            <div className="planner-sidebar-heading"><h3>Course list</h3><strong>{listedIds.length}</strong></div>
            <p className="course-list-key"><span><b className="class-type class-type--planned">P</b>Planned</span><span><b className="class-type class-type--recommended">A</b>Available</span></p>
            <div className="course-list-tools"><div className="course-list-filter" role="group" aria-label="Show class type">{["all", "planned", "available"].map(filter => <button type="button" key={filter} aria-pressed={listFilter === filter} onClick={() => setListFilter(filter)}>{filter[0].toUpperCase() + filter.slice(1)}</button>)}</div><input type="search" aria-label="Filter the course list by code or title" placeholder="Filter this list" value={listQuery} onChange={e => setListQuery(e.target.value)} /></div>
            <div className="api-sidebar-courses">{!listedIds.length && <p className="sidebar-empty">Search for courses to build your plan. Course information loads from the catalog.</p>}{listedIds.map(id => <article className="api-sidebar-card" key={id} {...cardEvents(id)}>
              <div className="api-row"><span className={`class-type ${selected[id] || active?.sections.some(section => section.courseId === id) ? "class-type--planned" : "class-type--recommended"}`}>{selected[id] || active?.sections.some(section => section.courseId === id) ? "P" : "A"}</span><strong>{id}</strong></div><button type="button" className="api-course-title" onClick={() => courseDetails(id)}>{knownCourses[id]?.title ?? active?.sections.find(s => s.courseId === id)?.title ?? id}</button>
              <Prediction rating={knownCourses[id]?.predictedRating ?? null} compact />
              <div className="api-row">{selected[id] || active?.sections.some(section => section.courseId === id) ? <button type="button" className="button" aria-label={`Remove ${id}`} disabled={Boolean(busy)} onClick={() => removeCourse(id)}>Remove</button> : <button type="button" className="button" disabled={Boolean(busy)} onClick={() => dropCourse(id)}>Add to plan</button>}<button type="button" className="button" onClick={() => courseDetails(id)}>Details</button></div>
            </article>)}</div>
            <div className="api-sidebar-actions"><button type="button" className="button" onClick={() => setAssistantOpen(open => !open)}>Planning preferences</button><button type="button" className="button primary api-generate" disabled={Boolean(busy) || !term} onClick={generate}>Generate schedules</button></div>
          </section></aside>
        </div>
        {menu && <SectionMenu section={menu.section} x={menu.x} y={menu.y} options={menuOptions} loading={!details[menu.section.courseId]} difficultyOf={crn => difficultyOf(menu.section.courseId, crn)}
          onSwap={option => { setMenuPreview(null); placePackage(menu.section.courseId, option.crns, option.label); }} onPreview={setMenuPreview} onDetails={() => { const id = menu.section.courseId; setMenu(null); courseDetails(id); }} onRemove={() => removeCourse(menu.section.courseId)} onClose={closeMenu} />}
        {result && <ScheduleAlternatives result={result} selected={manual ? -1 : alternative} onSelect={index => { operation.current?.abort(); setBusy(""); setAlternative(index); setManual(null); setExplanation(null); setSuggestions(null); setError(null); }} />}
        {manual && <section aria-label="Manual section validation"><h3>{manual.complete ? "Section selection is complete" : "Review your section selection"}</h3><ul>{manual.issues.map((issue, i) => <li key={i} className={issue.severity === "CONFLICT" ? "api-field-error" : ""}>{issue.severity}: {issue.message}</li>)}</ul><details><summary>Sections that could complete your selection</summary>{manual.candidates.map(c => <p key={c.crn}><button type="button" disabled={Boolean(busy) || !c.fits} onClick={() => addCrn(c.crn, c.courseId)}>{c.courseId} / {c.crn}{c.completesLecture ? " · completes lecture" : ""}</button> {c.reasons.join(" ")}</p>)}</details></section>}
        {active && request && <div className="api-actions"><button type="button" className="button" disabled={Boolean(busy)} onClick={() => void run("Explaining schedule", async signal => setExplanation(await explainSchedule(term, active, request.preferences, signal)))}>Explain selected schedule</button><button type="button" className="button" disabled={Boolean(busy)} onClick={() => void run("Finding electives", async signal => setSuggestions(await suggestElectives({ term, preferences: request.preferences, courseIds: active.sections.map(s => s.courseId), crns: active.sections.map(s => s.crn), includeClosedSections: request.includeClosedSections, q: search.q, ...(search.subject ? { subject: search.subject } : {}), limit: 5 }, signal)))}>Find electives that fit</button></div>}
        {explanation && <section><h3>Schedule explanation</h3><p>{explanation.explanation}</p><ul>{explanation.highlights.map(h => <li key={h}>{h}</li>)}</ul></section>}{suggestions && <section><h3>Electives that fit</h3>{!suggestions.suggestions.length && <p>No matching electives fit the current selection.</p>}{suggestions.suggestions.map(s => <article key={s.courseId}><h4>{s.courseId} — {s.title}</h4><ScheduleFacts schedule={s} /><button type="button" className="button" onClick={() => manualCheck([...(active?.sections.map(section => section.crn) ?? []), ...s.sections.map(section => section.crn)], s.courseId)}>Add suggested sections</button></article>)}</section>}
      </div>
      <aside className="api-plan-panel" id="api-planning-panel" hidden={!assistantOpen} aria-label="Intelligent Planning"><div className="api-row intelligence-heading"><div><strong>Intelligent Planning</strong><small>Describe your week, or adjust preferences</small></div><button type="button" className="api-close" aria-label="Close Intelligent Planning" onClick={() => setAssistantOpen(false)}>×</button></div>
        <section className="api-ai"><h3>Describe preferences with AI</h3><p>Extract suggestions, review them, then apply them to the editable form. AI availability depends on backend configuration.</p><textarea aria-label="AI scheduling preferences" maxLength={2000} value={prompt} onChange={e => setPrompt(e.target.value)} /><button type="button" className="button" disabled={Boolean(busy) || !term || !prompt.trim()} onClick={() => void run("Extracting preferences", async signal => { setExtraction(null); setExtraction(await extractPreferences(term, prompt, signal)); })}>Extract preferences</button>{extraction && <div><h3>Review extracted preferences</h3><pre>{JSON.stringify(extraction.preferences, null, 2)}</pre>{[...extraction.unresolved, ...extraction.warnings].map((message, i) => <p key={i}>{message}</p>)}<button type="button" className="button" onClick={() => { invalidate(); setForm(formFromPreferences(extraction.preferences, form)); setSelected(Object.fromEntries([...extraction.preferences.hardConstraints.requiredCourseIds.map(id => [id, "required"]), ...(extraction.preferences.softPreferences?.optionalCourseIds?.value ?? []).map(id => [id, "optional"])])); setExtraction(null); setNotice("Extracted preferences applied. Review and edit before generating."); }}>Apply reviewed preferences</button></div>}</section>
        <ApiPreferences value={form} onChange={updateForm} errors={errors} />
      </aside>
    </div>
<dialog className="api-course-dialog" ref={dialog} onClose={() => { detailOperation.current?.abort(); }}><div className="api-dialog-bar"><button type="button" className="api-dialog-close" aria-label="Close course details" onClick={() => dialog.current?.close()}>×</button></div>{detailLoading && <p role="status">Loading course details…</p>}<ErrorNotice error={detailError} retry={() => void openDetail(detailId)} />{detail && <><CatalogCourseDetails course={detail} term={term} /><section className="api-section-packages"><h3>Add to the calendar</h3><p>Each option is a lecture together with the lab or recitation the catalog links to it. Adding one replaces the sections of this course already on the calendar, and the backend checks links, conflicts and credits. You can also drag a course from the course list onto the calendar, then drag a placed section onto a highlighted slot to switch.</p>{!sectionPackages(detail).length && <p>No linked sections are published for this course, so nothing can be placed.</p>}{sectionPackages(detail).map(p => { const placedAlready = p.crns.every(crn => active?.sections.some(section => section.crn === crn)); return <div className="api-row api-section-package" key={p.key}><span><strong>{p.label}</strong>{p.sections.map(s => <small key={s.crn}>{s.componentType} {s.sectionCode} · CRN {s.crn} · {s.status} · {s.meetings.length ? s.meetings.map(m => `${m.days.map(d => d.slice(0, 3)).join("/")} ${m.start}–${m.end}`).join(", ") : "TBA"}</small>)}</span><button type="button" className="button primary" disabled={Boolean(busy) || placedAlready} onClick={() => { placePackage(detail.courseId, p.crns, p.label); dialog.current?.close(); }}>{placedAlready ? "On calendar" : "Add to calendar"}</button></div>; })}</section></>}</dialog>
  </div>, host)}{savedHost && createPortal(<div className="api-saved"><p>Stored in this browser only. Reopening a plan checks the latest catalog and meeting data.</p>{!saved.length && <p>No saved API plans yet. Generate or assemble a plan in Plan My Semester.</p>}{saved.map(plan => <article className="saved-card" key={plan.id}><h2>{plan.name}</h2><p>{plan.request.term} · CRNs {plan.crns.join(", ")}</p><button type="button" className="button primary" onClick={() => loadSaved(plan)}>Open plan</button><button type="button" onClick={() => { const name = window.prompt("Rename plan", plan.name); if (name?.trim()) persist(saved.map(p => p.id === plan.id ? { ...p, name: name.trim().slice(0, 120) } : p)); }}>Rename</button><button type="button" onClick={() => persist(saved.filter(p => p.id !== plan.id))}>Delete</button></article>)}</div>, savedHost)}</>;
}
