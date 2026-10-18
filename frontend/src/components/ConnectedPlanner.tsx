"use client";
import { useCallback, useEffect, useRef, useState, type DragEvent } from "react";
import { createPortal } from "react-dom";
import { loadCourseDetail, searchCatalog } from "@/lib/api/catalogClient";
import { ApiError } from "@/lib/api/httpClient";
import { buildGenerateRequest, checkSchedule, defaultForm, explainInfeasible, explainSchedule, extractPreferences, formFromPreferences, generateSchedules, loadTerms, suggestElectives, type PreferenceForm } from "@/lib/api/scheduleClient";
import type { CheckResponse, CourseDetail, CourseSuggestion, CourseSearchResponse, CourseSummary, ExplainResponse, GenerateRequest, GenerateResponse, Schedule, ScheduleSection, SuggestResponse, TermsResponse } from "@/lib/api/types";
import { CatalogCourseDetails, Prediction } from "./CatalogCourseDetails";
import { ApiPreferences } from "./ApiPreferences";
import { ApiWeekCalendar, COURSE_DRAG_TYPE, ScheduleAlternatives, ScheduleFacts, type CalendarGhost } from "./ApiScheduleResults";
import { buildSemesterPlan } from "@/lib/semesterPlan";
import { firstFittingPackage, meetingsOverlap, replaceCourse, sectionMoves, sectionPackages, swapOptions, type DetailSection, type SwapOption } from "@/lib/sectionPackages";
import { sectionDifficulty } from "@/lib/difficulty";
import { SectionMenu } from "./SectionMenu";
import { autoFillSelection, emptyResultReason, extractionIsEmpty, keepPlaced, freshSession, mergeSuggestions, planName, readSavedPlans, savePlan, summarizeExtraction, writeSavedPlans, type ExtractionSummary, type SavedPlan, type Selection, type Suggested } from "@/lib/planSession";

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
  const [prompt, setPrompt] = useState(""), [applied, setApplied] = useState<{ summary: ExtractionSummary; ok: boolean; found: CourseSuggestion[] } | null>(null), [suggested, setSuggested] = useState<Suggested>({}), [emptyWhy, setEmptyWhy] = useState<{ text: string; lowerMinimumTo: number | null } | null>(null), [explaining, setExplaining] = useState(false), [confirmClear, setConfirmClear] = useState(false), [savedNote, setSavedNote] = useState(""), [explanation, setExplanation] = useState<ExplainResponse | null>(null), [suggestions, setSuggestions] = useState<SuggestResponse | null>(null);
  const [saved, setSaved] = useState<SavedPlan[]>(readSavedPlans);
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
  const explainOp = useRef<AbortController | null>(null), noteTimer = useRef<number | undefined>(undefined);
  const operation = useRef<AbortController | null>(null), detailOperation = useRef<AbortController | null>(null);
  const dialog = useRef<HTMLDialogElement>(null);
  useEffect(() => () => { operation.current?.abort(); detailOperation.current?.abort(); explainOp.current?.abort(); window.clearTimeout(noteTimer.current); }, []);
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
  /** Drops the shown explanation and cancels one still being written, so a late reply never describes a different schedule. */
  function clearExplanation() { explainOp.current?.abort(); setExplaining(false); setExplanation(null); }
  function invalidate() { setEmptyWhy(null); operation.current?.abort(); setBusy(""); setResult(null); setManual(null); setRequest(null); setError(null); clearExplanation(); setSuggestions(null); setNotice(""); setHistory({ past: [], future: [] }); boardCourses.current = []; }
  /** Editing a preference never empties the calendar or the course list; the stored request follows the form so Save and Explain match it. */
  function updateForm(value: PreferenceForm) {
    clearExplanation(); setForm(value);
    if (active) { try { setRequest(buildGenerateRequest(term, Object.keys(selected).filter(id => selected[id] === "required"), Object.keys(selected).filter(id => selected[id] === "optional"), value)); } catch { /* an invalid value is reported when schedules are generated */ } setNotice("Preferences changed. Generate schedules again to re-rank."); }
  }
  function makeRequest(sel: Selection = selected) { return buildGenerateRequest(term, Object.keys(sel).filter(id => sel[id] === "required"), Object.keys(sel).filter(id => sel[id] === "optional"), form); }
  async function run(label: string, action: (signal: AbortSignal) => Promise<void>) {
    operation.current?.abort(); const controller = new AbortController(); operation.current = controller; setBusy(label); setError(null); setNotice("");
    try { await action(controller.signal); } catch (e) { if (!controller.signal.aborted) setError(asError(e)); } finally { if (!controller.signal.aborted) setBusy(""); }
  }
  /** Generating never clears the calendar first: the current plan stays visible until real results replace it, and stays if there are none. */
  /** Auto-fill: hand the planned courses plus the AI's findings to the same engine and show the best schedule it can build. */
  function autoFill() {
    const sel = autoFillSelection(selected, suggested);
    if (!Object.keys(sel).length) { setNotice("Ask the AI for courses first, or add a course to your plan, then Auto-fill."); return; }
    generate(sel, true);
  }
  function generate(sel: Selection = selected, fill = false) {
    clearExplanation(); setSuggestions(null);
    void run(fill ? "Auto-filling your schedule" : "Generating schedules", async signal => {
      const body = makeRequest(sel); const data = await generateSchedules(body, signal);
      if (data.term !== term) throw new Error("The backend returned a different catalog term.");
      setRequest(body); setResult(data); setAlternative(0);
      if (data.schedules.length) {
        setEmptyWhy(null); setManual(null); setHistory({ past: [], future: [] }); boardCourses.current = []; explainInBackground(body, data.schedules[0]);
        if (fill) {
          const top = data.schedules[0], placed = new Set(top.sections.map(s => s.courseId));
          setSelected(keepPlaced(sel, placed));
          setNotice(`Auto-filled ${placed.size} course${placed.size === 1 ? "" : "s"} (${top.totalCredits} credits) from the best schedule the engine found. Drag a section to change it, or Save schedule.`);
        }
      }
      else {
        const credits = Object.fromEntries(body.preferences.hardConstraints.requiredCourseIds.map(id => [id, knownCourses[id]?.credits ?? details[id]?.credits]));
        setEmptyWhy(emptyResultReason({ requiredIds: body.preferences.hardConstraints.requiredCourseIds, credits, minCredits: body.preferences.hardConstraints.minCredits, maxCredits: body.preferences.hardConstraints.maxCredits }));
        setNotice("No schedule satisfies every requirement. Your plan is unchanged."); explainInBackground(body, null);
      }
    });
  }
  /** Claude explains the best result (or why there is none) while the student looks at the calendar; failures leave the manual button. */
  function explainInBackground(body: GenerateRequest, schedule: Schedule | null) {
    explainOp.current?.abort(); const controller = new AbortController(); explainOp.current = controller; setExplaining(true);
    (schedule ? explainSchedule(body.term, schedule, body.preferences, controller.signal) : explainInfeasible(body.term, body.preferences, controller.signal))
      .then(data => { if (!controller.signal.aborted) setExplanation(data); })
      .catch(() => undefined)
      .finally(() => { if (!controller.signal.aborted) setExplaining(false); });
  }
  /** Reset the working session only: selections, candidates, temporary preferences. Saved schedules and the catalog are never touched. */
  function clearPlan() {
    const fresh = freshSession(); invalidate(); operation.current?.abort(); setSelected(fresh.selected); setSuggested({}); setForm(fresh.form); setPrompt(""); setApplied(null); setAlternative(0);
    setMoving(null); setMenu(null); setMenuPreview(null); setHoverSection(null); setCardHover(null); setDraggingCourse(null); setGhostFocus(null); setConfirmClear(false);
    setNotice("Plan cleared. Your saved schedules were kept. Add courses to start a new plan.");
  }
  const hasWork = Boolean(Object.keys(selected).length || result || manual);
  /**
   * Ask Claude, validate (the client rejects malformed replies), apply the scheduling rules, and list the courses it found in the
   * Course List as Available. Courses are never added to the plan here: that stays the student's click on "Add to plan".
   */
  async function applyAi(signal: AbortSignal) {
    setApplied(null);
    const data = await extractPreferences(term, prompt, signal);
    const summary = summarizeExtraction(data.preferences, data.unresolved, data.warnings);
    if (extractionIsEmpty(summary, data.suggestions)) { setApplied({ summary, ok: false, found: [] }); return; }
    const nextForm = formFromPreferences(data.preferences, form), fresh = mergeSuggestions(suggested, data.preferences, data.suggestions, selected);
    clearExplanation(); setSuggestions(null); setForm(nextForm); setSuggested(fresh); setListFilter("all"); setListQuery(""); setApplied({ summary, ok: true, found: data.suggestions });
    setKnownCourses(current => ({ ...Object.fromEntries(data.suggestions.map(c => [c.courseId, { courseId: c.courseId, subject: c.courseId.split("-")[0], number: c.courseId.split("-")[1], title: c.title, credits: c.credits, sectionCount: 0, predictedRating: null }])), ...current }));
    if (active) {
      // New hard rules may not suit what is already on the calendar: re-check it and say so. Nothing is removed.
      try {
        const body = buildGenerateRequest(term, Object.keys(selected).filter(id => selected[id] === "required"), Object.keys(selected).filter(id => selected[id] === "optional"), nextForm);
        const data = await checkSchedule({ term, preferences: body.preferences, courseIds: [...new Set([...boardCourses.current, ...active.sections.map(s => s.courseId)])], crns: active.sections.map(s => s.crn), includeClosedSections: body.includeClosedSections }, signal);
        setRequest(body); setManual(data);
        const conflicts = data.issues.filter(i => i.severity === "CONFLICT").length;
        if (conflicts) setNotice(`${conflicts} placement${conflicts === 1 ? "" : "s"} on your calendar ${conflicts === 1 ? "conflicts" : "conflict"} with the new rules. Nothing was removed; see “Review your section selection”.`);
      } catch { if (signal.aborted) return; /* the preferences are still applied; the next action re-checks */ }
    }
    // Titles and credits for courses the student named outright: load them so the list reads "MATH 021 - Title" at once.
    const missing = Object.keys(fresh).filter(id => !knownCourses[id] && !details[id] && !data.suggestions.some(c => c.courseId === id));
    const loaded = (await Promise.allSettled(missing.map(id => loadCourseDetail(id, term, signal)))).flatMap(r => r.status === "fulfilled" ? [r.value] : []);
    if (signal.aborted || !loaded.length) return;
    setDetails(current => ({ ...current, ...Object.fromEntries(loaded.map(d => [d.courseId, d])) }));
    setKnownCourses(current => ({ ...current, ...Object.fromEntries(loaded.map(d => [d.courseId, { courseId: d.courseId, subject: d.subject, number: d.number, title: d.title, credits: d.credits, sectionCount: d.sections.length, predictedRating: d.predictedRating }])) }));
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
    setRequest(body); setManual(data); clearExplanation(); setSuggestions(null);
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
  /** Rename and delete go through the same checked write as saving; the list shown is always the list stored. */
  function persist(next: SavedPlan[]) {
    const written = writeSavedPlans(next);
    if (written.ok) setSaved(next); else setError(new Error(written.message));
  }
  function flashSaved(message: string) { setSavedNote(message); window.clearTimeout(noteTimer.current); noteTimer.current = window.setTimeout(() => setSavedNote(""), 8000); }
  /** Saves the schedule on the calendar (the selected alternative). "Saved" is shown only after the plan is read back from storage. */
  function save() {
    if (!active || !request) return;
    const courseIds = [...new Set(active.sections.map(s => s.courseId))];
    const outcome = savePlan({ name: planName((terms.find(t => t.term === term)?.name ?? term).replace(/ Semester$/, ""), courseIds, readSavedPlans()), request, crns: active.sections.map(s => s.crn), courseIds, credits: active.totalCredits });
    if (!outcome.ok) { setNotice(""); setSavedNote(""); setError(new Error(outcome.message)); return; }
    setError(null); setSaved(outcome.plans); setNotice("Schedule saved successfully! Find it under Saved schedules."); flashSaved("Schedule saved successfully!");
  }
  function loadSaved(plan: SavedPlan) {
    if (hasWork && !window.confirm("Open this saved schedule? Your current plan has not been saved and will be replaced.")) return;
    invalidate(); setTerm(plan.request.term); setForm(formFromPreferences(plan.request.preferences, { ...defaultForm, includeClosedSections: plan.request.includeClosedSections, maxResults: String(plan.request.maxResults) }));
    setSelected(Object.fromEntries([...plan.request.preferences.hardConstraints.requiredCourseIds.map(id => [id, "required"]), ...(plan.request.preferences.softPreferences?.optionalCourseIds?.value ?? []).map(id => [id, "optional"])]));
    document.querySelector<HTMLButtonElement>('[data-route="planner"]')?.click();
    void run("Refreshing saved plan", async signal => { const data = await checkSchedule({ term: plan.request.term, preferences: plan.request.preferences, courseIds: plan.courseIds ?? [], crns: plan.crns, includeClosedSections: plan.request.includeClosedSections }, signal); setManual(data); setRequest(plan.request); });
  }
  const errors = error instanceof ApiError ? error.errors : [];
  const changeTerm = (value: string) => { invalidate(); setTerm(value); setSelected({}); setSuggested({}); setKnownCourses({}); setDetails({}); setMoving(null); setQ(""); setSubject(""); setSearchOpen(false); setSearch({ q: "", subject: "", page: 0 }); setDetail(null); dialog.current?.close(); };
  const listedIds = [...new Set([...Object.keys(suggested), ...Object.keys(selected), ...(catalog?.items.map(course => course.courseId) ?? [])])].filter(id => {
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
              <button type="button" className="button primary sem-save" disabled={!active || !request} onClick={save}>Save schedule</button>{savedNote && <span className="api-save-note" role="status">{savedNote}</span>}
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
              {suggested[id] && !selected[id] && !active?.sections.some(section => section.courseId === id) && <small className="api-suggest-why">AI: {suggested[id].reason}{suggested[id].qualifying ? <><br />{suggested[id].fitsRules ? "Fits your time rules" : "Example sections"}: {suggested[id].qualifying}</> : null}</small>}
              <div className="api-row">{selected[id] || active?.sections.some(section => section.courseId === id) ? <button type="button" className="button" aria-label={`Remove ${id}`} disabled={Boolean(busy)} onClick={() => removeCourse(id)}>Remove</button> : <button type="button" className="button" disabled={Boolean(busy)} onClick={() => dropCourse(id)}>Add to plan</button>}<button type="button" className="button" onClick={() => courseDetails(id)}>Details</button></div>
            </article>)}</div>
            <div className="api-sidebar-actions"><button type="button" className="button" onClick={() => setAssistantOpen(open => !open)}>Planning preferences</button><button type="button" className="button primary api-autofill" disabled={Boolean(busy) || !term || (!Object.keys(suggested).length && !Object.keys(selected).length)} onClick={autoFill}>✦ Auto-fill my schedule</button><button type="button" className="button primary api-generate" disabled={Boolean(busy) || !term} onClick={() => generate()}>Generate schedules</button>
              {confirmClear ? <div className="api-clear-confirm" role="alertdialog" aria-label="Clear current plan"><p>Clear your current plan? Courses, candidates and temporary preferences are removed. Saved schedules are kept.</p><div className="api-row"><button type="button" className="button" onClick={clearPlan}>Yes, clear</button><button type="button" className="button quiet" onClick={() => setConfirmClear(false)}>Keep plan</button></div></div>
                : <button type="button" className="button quiet api-clear" disabled={!hasWork && !prompt} onClick={() => hasWork ? setConfirmClear(true) : clearPlan()}>Clear</button>}</div>
          </section></aside>
        </div>
        {menu && <SectionMenu section={menu.section} x={menu.x} y={menu.y} options={menuOptions} loading={!details[menu.section.courseId]} difficultyOf={crn => difficultyOf(menu.section.courseId, crn)}
          onSwap={option => { setMenuPreview(null); placePackage(menu.section.courseId, option.crns, option.label); }} onPreview={setMenuPreview} onDetails={() => { const id = menu.section.courseId; setMenu(null); courseDetails(id); }} onRemove={() => removeCourse(menu.section.courseId)} onClose={closeMenu} />}
        {result && <ScheduleAlternatives result={result} selected={manual ? -1 : alternative} onSelect={index => { operation.current?.abort(); setBusy(""); setAlternative(index); setManual(null); clearExplanation(); setSuggestions(null); setError(null); }} />}
        {emptyWhy && result && !result.schedules.length && <div className="api-error" role="alert"><strong>No schedule could be generated</strong><p>{emptyWhy.text}</p>{emptyWhy.lowerMinimumTo !== null && <button type="button" className="button" onClick={() => { updateForm({ ...form, minCredits: String(emptyWhy.lowerMinimumTo) }); setEmptyWhy(null); setNotice(`Minimum credits set to ${emptyWhy.lowerMinimumTo}. Generate schedules again.`); }}>Set minimum to {emptyWhy.lowerMinimumTo} credits</button>}</div>}
        {manual && <section aria-label="Manual section validation"><h3>{manual.complete ? "Section selection is complete" : "Review your section selection"}</h3><ul>{manual.issues.map((issue, i) => <li key={i} className={issue.severity === "CONFLICT" ? "api-field-error" : ""}>{issue.severity}: {issue.message}</li>)}</ul><details><summary>Sections that could complete your selection</summary>{manual.candidates.map(c => <p key={c.crn}><button type="button" disabled={Boolean(busy) || !c.fits} onClick={() => addCrn(c.crn, c.courseId)}>{c.courseId} / {c.crn}{c.completesLecture ? " · completes lecture" : ""}</button> {c.reasons.join(" ")}</p>)}</details></section>}
        {active && request && <div className="api-actions"><button type="button" className="button" disabled={Boolean(busy)} onClick={() => void run("Explaining schedule", async signal => setExplanation(await explainSchedule(term, active, request.preferences, signal)))}>Explain selected schedule</button><button type="button" className="button" disabled={Boolean(busy)} onClick={() => void run("Finding electives", async signal => setSuggestions(await suggestElectives({ term, preferences: request.preferences, courseIds: active.sections.map(s => s.courseId), crns: active.sections.map(s => s.crn), includeClosedSections: request.includeClosedSections, q: search.q, ...(search.subject ? { subject: search.subject } : {}), limit: 5 }, signal)))}>Find electives that fit</button></div>}
        {explaining && <p role="status">Claude is reviewing the result…</p>}{explanation && <section className="api-explanation"><h3>Schedule explanation</h3><p>{explanation.explanation}</p><ul>{explanation.highlights.map(h => <li key={h}>{h}</li>)}</ul></section>}{suggestions && <section><h3>Electives that fit</h3>{!suggestions.suggestions.length && <p>No matching electives fit the current selection.</p>}{suggestions.suggestions.map(s => <article key={s.courseId}><h4>{s.courseId} — {s.title}</h4><ScheduleFacts schedule={s} /><button type="button" className="button" onClick={() => manualCheck([...(active?.sections.map(section => section.crn) ?? []), ...s.sections.map(section => section.crn)], s.courseId)}>Add suggested sections</button></article>)}</section>}
      </div>
      <aside className="api-plan-panel" id="api-planning-panel" hidden={!assistantOpen} aria-label="Intelligent Planning"><div className="api-row intelligence-heading"><div><strong>Intelligent Planning</strong><small>Describe your week, or adjust preferences</small></div><button type="button" className="api-close" aria-label="Close Intelligent Planning" onClick={() => setAssistantOpen(false)}>×</button></div>
        <section className="api-ai"><h3>Describe preferences with AI</h3><p>Say which courses you want and when you can take them. What Syllo understands is applied for you; then click Generate schedules.</p><textarea aria-label="AI scheduling preferences" maxLength={2000} value={prompt} onChange={e => setPrompt(e.target.value)} /><button type="button" className="button" disabled={Boolean(busy) || !term || !prompt.trim()} onClick={() => void run("Reading your request", signal => applyAi(signal))}>Extract preferences</button>{applied && <AiResult applied={applied} onAutoFill={Object.keys(suggested).length || Object.keys(selected).length ? autoFill : undefined} busy={Boolean(busy)} />}</section>
        <ApiPreferences value={form} onChange={updateForm} errors={errors} />
      </aside>
    </div>
<dialog className="api-course-dialog" ref={dialog} onClose={() => { detailOperation.current?.abort(); }}><div className="api-dialog-bar"><button type="button" className="api-dialog-close" aria-label="Close course details" onClick={() => dialog.current?.close()}>×</button></div>{detailLoading && <p role="status">Loading course details…</p>}<ErrorNotice error={detailError} retry={() => void openDetail(detailId)} />{detail && <><CatalogCourseDetails course={detail} term={term} /><section className="api-section-packages"><h3>Add to the calendar</h3><p>Each option is a lecture together with the lab or recitation the catalog links to it. Adding one replaces the sections of this course already on the calendar, and the backend checks links, conflicts and credits. You can also drag a course from the course list onto the calendar, then drag a placed section onto a highlighted slot to switch.</p>{!sectionPackages(detail).length && <p>No linked sections are published for this course, so nothing can be placed.</p>}{sectionPackages(detail).map(p => { const placedAlready = p.crns.every(crn => active?.sections.some(section => section.crn === crn)); return <div className="api-row api-section-package" key={p.key}><span><strong>{p.label}</strong>{p.sections.map(s => <small key={s.crn}>{s.componentType} {s.sectionCode} · CRN {s.crn} · {s.status} · {s.meetings.length ? s.meetings.map(m => `${m.days.map(d => d.slice(0, 3)).join("/")} ${m.start}–${m.end}`).join(", ") : "TBA"}</small>)}</span><button type="button" className="button primary" disabled={Boolean(busy) || placedAlready} onClick={() => { placePackage(detail.courseId, p.crns, p.label); dialog.current?.close(); }}>{placedAlready ? "On calendar" : "Add to calendar"}</button></div>; })}</section></>}</dialog>
  </div>, host)}{savedHost && createPortal(<div className="api-saved"><p>Stored in this browser only. Reopening a schedule checks the latest catalog and meeting data.</p>{!saved.length && <p>No saved schedules yet. Generate or build a schedule in Plan My Semester, then choose Save schedule.</p>}{saved.map(plan => <article className="saved-card" key={plan.id}><h2>{plan.name}</h2><p>{plan.courseIds.map(id => id.replace("-", " ")).join(" · ")}</p><p>{plan.request.term}{plan.credits ? ` · ${plan.credits} credits` : ""} · {plan.crns.length} sections{plan.savedAt ? ` · saved ${new Date(plan.savedAt).toLocaleDateString()}` : ""}</p><button type="button" className="button primary" onClick={() => loadSaved(plan)}>Open schedule</button><button type="button" onClick={() => { const name = window.prompt("Rename schedule", plan.name); if (name?.trim()) persist(saved.map(p => p.id === plan.id ? { ...p, name: name.trim().slice(0, 120) } : p)); }}>Rename</button><button type="button" onClick={() => persist(saved.filter(p => p.id !== plan.id))}>Delete</button></article>)}</div>, savedHost)}</>;
}

/** What the AI understood, in plain words: courses it found (not yet in the plan), hard rules apart from ranking wishes, and anything it could not match. */
function AiResult({ applied, onAutoFill, busy }: Readonly<{ applied: { summary: ExtractionSummary; ok: boolean; found: CourseSuggestion[] }; onAutoFill?: () => void; busy: boolean }>) {
  const { summary, found } = applied;
  const named = [...summary.required, ...summary.optional].filter(c => !found.some(f => f.courseId.replace("-", " ") === c));
  const hasCourses = named.length > 0 || found.length > 0;
  return <div className="api-ai-result" role="status">
    <strong>{applied.ok ? "AI preferences applied successfully." : "I could not find any courses or scheduling details in that."}</strong>
    {!applied.ok && <p>Try something like “I want MATH 021 and no classes before 10 AM” or “I’m interested in data science.”</p>}
    {hasCourses && <><b>Courses found</b><ul>{named.map(c => <li key={c}>{c}</li>)}{found.map(c => <li key={c.courseId}>{c.courseId.replace("-", " ")} — {c.title}<br /><small>{c.reason}{c.qualifying ? ` · ${c.qualifying}` : ""}</small></li>)}</ul><p>They are in your Course List as Available. Choose Add to plan for the ones you want, or let Syllo build a schedule from them.</p>{onAutoFill && <button type="button" className="button primary" disabled={busy} onClick={onAutoFill}>✦ Auto-fill my schedule</button>}</>}
    {applied.ok && summary.requirements.length > 0 && <><b>Always enforced</b><ul>{summary.requirements.map(c => <li key={c}>{c}</li>)}</ul></>}
    {summary.preferences.length > 0 && <><b>Scheduling preferences</b><ul>{summary.preferences.map(c => <li key={c}>{c}</li>)}</ul></>}
    {summary.attention.length > 0 && <><b>Needs your attention</b><ul>{summary.attention.map((c, i) => <li key={i}>{c}</li>)}</ul></>}
    {summary.notes.length > 0 && <><b>How I read your request</b><ul>{summary.notes.map((c, i) => <li key={i}>{c}</li>)}</ul></>}
  </div>;
}
