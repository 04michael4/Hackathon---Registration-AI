"use client";
import { useCallback, useEffect, useRef, useState } from "react";
import { createPortal } from "react-dom";
import { loadCourseDetail, searchCatalog } from "@/lib/api/catalogClient";
import { ApiError, isRecord } from "@/lib/api/httpClient";
import { buildGenerateRequest, checkSchedule, defaultForm, explainSchedule, extractPreferences, formFromPreferences, generateSchedules, loadTerms, suggestElectives, validPreferences, type PreferenceForm } from "@/lib/api/scheduleClient";
import type { CheckResponse, CourseDetail, CourseSearchResponse, CourseSummary, ExplainResponse, ExtractResponse, GenerateRequest, GenerateResponse, Schedule, SuggestResponse, TermsResponse } from "@/lib/api/types";
import { CatalogCourseDetails, Prediction } from "./CatalogCourseDetails";
import { ApiPreferences } from "./ApiPreferences";
import { ApiWeekCalendar, ScheduleAlternatives, ScheduleFacts } from "./ApiScheduleResults";
import { buildSemesterPlan } from "@/lib/semesterPlan";

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
  function changeSelection(id: string, kind: "required" | "optional" | "") { invalidate(); setSelected(current => { const next = { ...current }; if (kind) next[id] = kind; else delete next[id]; return next; }); }
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
    try { setDetail(await loadCourseDetail(id, term, controller.signal)); } catch (e) { if (!controller.signal.aborted) setDetailError(asError(e)); } finally { if (!controller.signal.aborted) setDetailLoading(false); }
  }, [term]);
  const active: Schedule | undefined = manual ? { ...manual, rank: 0 } : result?.schedules[alternative];
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
  function addToNextSemester(id: string) {
    const next = { ...selected, [id]: "required" as const };
    if (!active) { changeSelection(id, "required"); setNotice(`${id} added to next semester. Choose sections or generate schedules to place its meetings.`); return; }
    setSelected(next);
    void run("Updating next semester", async signal => {
      const body = buildGenerateRequest(term, Object.keys(next).filter(key => next[key] === "required"), Object.keys(next).filter(key => next[key] === "optional"), form);
      const data = await checkSchedule({ term, preferences: body.preferences, courseIds: [...new Set([...boardCourses.current, ...active.sections.map(section => section.courseId), id])], crns: active.sections.map(section => section.crn), includeClosedSections: body.includeClosedSections }, signal);
      setRequest(body); setManual(data); setResult(null); setExplanation(null); setSuggestions(null); setHistory({ past: [], future: [] });
      setNotice(`${id} added to next semester. Choose its sections or regenerate; your existing meetings are kept.`);
    });
  }
  function manualCheck(crns: string[], extraCourse?: string, direction?: "undo" | "redo") {
    const previous = active?.sections.map(s => s.crn) ?? [];
    void run("Checking sections", async signal => {
      const body = makeRequest();
      const courseIds = [...new Set([...boardCourses.current, ...(active?.sections.map(s => s.courseId) ?? []), ...(extraCourse ? [extraCourse] : [])])];
      if (courseIds.length > 30 || crns.length > 60) throw new Error("Manual plans support at most 30 additional courses and 60 sections.");
      const data = await checkSchedule({ term, preferences: body.preferences, courseIds, crns: [...new Set(crns)], includeClosedSections: body.includeClosedSections }, signal);
      boardCourses.current = courseIds;
      setHistory(current => direction === "undo" ? { past: current.past.slice(0, -1), future: [...current.future, previous] } : direction === "redo" ? { past: [...current.past, previous], future: current.future.slice(0, -1) } : { past: [...current.past, previous].slice(-30), future: [] });
      setRequest(body); setManual(data); setExplanation(null); setSuggestions(null);
    });
  }
  function addCrn(crn: string, courseId?: string) { manualCheck([...(active?.sections.map(s => s.crn) ?? []), crn], courseId); }
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
  const changeTerm = (value: string) => { invalidate(); setTerm(value); setSelected({}); setKnownCourses({}); setQ(""); setSubject(""); setSearchOpen(false); setSearch({ q: "", subject: "", page: 0 }); setDetail(null); dialog.current?.close(); };
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
            {catalog && <>{!catalog.items.length && <p role="status">No courses match these filters. Try another code, subject or topic.</p>}<div className="api-course-results">{catalog.items.map(course => <article key={course.courseId} data-api-course={course.courseId}>
              <div className="api-row"><strong>{course.courseId}</strong><span>{course.credits} credits · {course.sectionCount} sections</span></div><h3>{course.title}</h3><Prediction rating={course.predictedRating} compact />
              <div className="api-row"><button type="button" className="button primary" data-add-next-semester={course.courseId} disabled={Boolean(busy) || selected[course.courseId] === "required" || active?.sections.some(section => section.courseId === course.courseId)} onClick={() => addToNextSemester(course.courseId)}>{selected[course.courseId] === "required" || active?.sections.some(section => section.courseId === course.courseId) ? "Added to next semester" : "Add to next semester"}</button><button type="button" className="button" onClick={() => courseDetails(course.courseId)}>Details &amp; sections</button><label>Use in plan<select aria-label={`${course.courseId} selection`} value={selected[course.courseId] ?? ""} onChange={e => changeSelection(course.courseId, e.target.value as "required" | "optional" | "")}><option value="">Not selected</option><option value="required">Required</option><option value="optional">Optional</option></select></label></div>
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
            </>} totalCredits={active?.totalCredits ?? 0} sections={active?.sections ?? []} onDrop={crn => addCrn(crn, detail?.sections.some(s => s.crn === crn) ? detail.courseId : undefined)} onRemove={crn => manualCheck(active?.sections.filter(s => s.crn !== crn).map(s => s.crn) ?? [])} onOpenCourse={courseDetails} />
            {active && <ScheduleFacts schedule={active} />}
          </div>
          <aside className="api-course-sidebar planner-course-sidebar" aria-label="Course list"><section className="course-list-panel">
            <div className="planner-sidebar-heading"><h3>Course list</h3><strong>{listedIds.length}</strong></div>
            <p className="course-list-key"><span><b className="class-type class-type--planned">P</b>Planned</span><span><b className="class-type class-type--recommended">A</b>Available</span></p>
            <div className="course-list-tools"><div className="course-list-filter" role="group" aria-label="Show class type">{["all", "planned", "available"].map(filter => <button type="button" key={filter} aria-pressed={listFilter === filter} onClick={() => setListFilter(filter)}>{filter[0].toUpperCase() + filter.slice(1)}</button>)}</div><input type="search" aria-label="Filter the course list by code or title" placeholder="Filter this list" value={listQuery} onChange={e => setListQuery(e.target.value)} /></div>
            <div className="api-sidebar-courses">{!listedIds.length && <p className="sidebar-empty">Search for courses to build your plan. Course information loads from the catalog.</p>}{listedIds.map(id => <article className="api-sidebar-card" key={id}>
              <div className="api-row"><span className={`class-type ${selected[id] ? "class-type--planned" : "class-type--recommended"}`}>{selected[id] ? "P" : "A"}</span><strong>{id}</strong></div><button type="button" className="api-course-title" onClick={() => courseDetails(id)}>{knownCourses[id]?.title ?? active?.sections.find(s => s.courseId === id)?.title ?? id}</button>
              <Prediction rating={knownCourses[id]?.predictedRating ?? null} compact />
              <div className="api-row">{selected[id] ? <><select aria-label={`${id} selected role`} value={selected[id]} onChange={e => changeSelection(id, e.target.value as "required" | "optional")}><option value="required">Required</option><option value="optional">Optional</option></select><button type="button" className="button" aria-label={`Remove ${id}`} onClick={() => changeSelection(id, "")}>Remove</button></> : <button type="button" className="button" onClick={() => changeSelection(id, "required")}>Add to plan</button>}<button type="button" className="button" onClick={() => courseDetails(id)}>Details</button></div>
            </article>)}</div>
            <div className="api-sidebar-actions"><button type="button" className="button" onClick={() => setAssistantOpen(open => !open)}>Planning preferences</button><button type="button" className="button primary api-generate" disabled={Boolean(busy) || !term} onClick={generate}>Generate schedules</button></div>
          </section></aside>
        </div>
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
<dialog className="api-course-dialog" ref={dialog} onClose={() => { detailOperation.current?.abort(); }}><button type="button" className="button" onClick={() => dialog.current?.close()}>Close course details</button>{detailLoading && <p role="status">Loading course details…</p>}<ErrorNotice error={detailError} retry={() => void openDetail(detailId)} />{detail && <><CatalogCourseDetails course={detail} /><section><h3>Add sections to the calendar</h3><p>Select a required course first. The backend checks links, conflicts and credits after each change. You can also drag a section onto the calendar.</p>{detail.sections.map(s => <div className="api-row" key={s.crn} draggable onDragStart={e => { e.dataTransfer.setData("text/plain", s.crn); dialog.current?.close(); }}><span>{s.componentType} {s.sectionCode} · CRN {s.crn} · {s.status}{s.lectureLink ? ` · linked to ${s.lectureLink}` : ""}</span><button type="button" className="button" disabled={Boolean(busy) || active?.sections.some(section => section.crn === s.crn)} onClick={() => { addCrn(s.crn, detail.courseId); dialog.current?.close(); }}>Add section</button></div>)}</section></>}</dialog>
  </div>, host)}{savedHost && createPortal(<div className="api-saved"><p>Stored in this browser only. Reopening a plan checks the latest catalog and meeting data.</p>{!saved.length && <p>No saved API plans yet. Generate or assemble a plan in Plan My Semester.</p>}{saved.map(plan => <article className="saved-card" key={plan.id}><h2>{plan.name}</h2><p>{plan.request.term} · CRNs {plan.crns.join(", ")}</p><button type="button" className="button primary" onClick={() => loadSaved(plan)}>Open plan</button><button type="button" onClick={() => { const name = window.prompt("Rename plan", plan.name); if (name?.trim()) persist(saved.map(p => p.id === plan.id ? { ...p, name: name.trim().slice(0, 120) } : p)); }}>Rename</button><button type="button" onClick={() => persist(saved.filter(p => p.id !== plan.id))}>Delete</button></article>)}</div>, savedHost)}</>;
}
