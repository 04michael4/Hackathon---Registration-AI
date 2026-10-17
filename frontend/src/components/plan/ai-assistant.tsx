"use client";

import { useMemo, useState } from "react";

import { Loader2, Sparkles, X } from "lucide-react";

import { MiniCalendar } from "@/components/week-calendar";
import { Button } from "@/components/ui/button";
import { Checkbox } from "@/components/ui/checkbox";
import { Label } from "@/components/ui/label";
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from "@/components/ui/select";
import { Textarea } from "@/components/ui/textarea";
import { api, ApiError } from "@/lib/api/client";
import type { Day, GeneratedSchedule, TimeBlock } from "@/lib/api/types";
import { useCatalog } from "@/lib/catalog";
import { API_TERM, NEXT_TERM } from "@/lib/demo/program";
import { alwaysConflict, blockedCourse, creditsOfCrns, meetingsAsBlocks } from "@/lib/plan";
import { courseCode, isSlot, kindOf, recommended } from "@/lib/rules";
import { student, useStore } from "@/lib/store";
import { KIND_COLOR, WEEKDAYS } from "@/lib/ui";

/** Times to avoid. Each is a hard rule for the engine (blocked time), so a clash is reported, not hidden. */
const AVOID_RULES = {
  before9: { label: "No classes before 9am", allow: "Allow classes before 9am", blocks: [{ days: WEEKDAYS, start: "08:00", end: "09:00" }] },
  before10: { label: "No classes before 10am", allow: "Allow classes before 10am", blocks: [{ days: WEEKDAYS, start: "08:00", end: "10:00" }] },
  friday: { label: "No Friday classes", allow: "Allow Friday classes", blocks: [{ days: ["FRIDAY"] as Day[], start: "08:00", end: "22:00" }] },
  after5: { label: "Nothing after 5pm", allow: "Allow classes after 5pm", blocks: [{ days: WEEKDAYS, start: "17:00", end: "22:00" }] },
} satisfies Record<string, { label: string; allow: string; blocks: TimeBlock[] }>;
type AvoidRule = keyof typeof AVOID_RULES;

type Step = "ask" | "pick" | "results";

interface AiAssistantProps {
  placed: string[];
  onAccept: (crns: string[], label: string) => void;
  onClose: () => void;
}

const CREDIT_OPTIONS = Array.from({ length: 21 }, (_, i) => String(i + 1));

export function AiAssistant({ placed, onAccept, onClose }: AiAssistantProps) {
  const { sectionByCrn, available } = useCatalog();
  const { doc } = useStore();
  const [step, setStep] = useState<Step>("ask");
  const [minCredits, setMinCredits] = useState(student.creditRange[0]);
  const [maxCredits, setMaxCredits] = useState(student.creditRange[1]);
  const [avoid, setAvoid] = useState<AvoidRule[]>([]);
  const [extraBlocks, setExtraBlocks] = useState<TimeBlock[]>([]);
  const [description, setDescription] = useState("");
  const [aiNote, setAiNote] = useState<string[]>([]);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [picked, setPicked] = useState<string[] | null>(null);
  const [options, setOptions] = useState<GeneratedSchedule[]>([]);
  const [diagnostics, setDiagnostics] = useState<string[]>([]);

  const placedCourses = useMemo(
    () => new Set(placed.map((crn) => sectionByCrn.get(crn)?.course.courseId).filter(Boolean)),
    [placed, sectionByCrn],
  );
  const candidates = useMemo(() => {
    const fromPlan = (doc.degree[NEXT_TERM] ?? []).filter((id) => !isSlot(id) && available.has(id));
    const recs = recommended(student, doc.degree, NEXT_TERM, available).map((r) => r.courseId);
    return [...new Set([...fromPlan, ...recs])].filter((id) => !placedCourses.has(id));
  }, [doc.degree, available, placedCourses]);
  const chosen = picked ?? candidates.filter((id) => (doc.degree[NEXT_TERM] ?? []).includes(id));

  const fillFromDescription = async () => {
    setBusy(true);
    setError(null);
    try {
      const result = await api.extract(API_TERM, description);
      const hard = result.preferences.hardConstraints;
      if (hard.minCredits) setMinCredits(hard.minCredits);
      if (hard.maxCredits) setMaxCredits(hard.maxCredits);
      const soft = result.preferences.softPreferences;
      const next: AvoidRule[] = [];
      const earliest = soft?.earliestStart?.value;
      if (earliest && earliest >= "10:00") next.push("before10");
      else if (earliest && earliest >= "09:00") next.push("before9");
      if (soft?.daysOff?.value.includes("FRIDAY")) next.push("friday");
      setAvoid(next);
      setExtraBlocks(hard.blockedTimes ?? []);
      const mentioned = hard.requiredCourseIds.filter((id) => available.has(id) && !placedCourses.has(id));
      if (mentioned.length) setPicked([...new Set([...chosen, ...mentioned])]);
      setAiNote([...result.warnings, ...result.unresolved.map((u) => `Couldn't find “${u}” in this term.`)]);
    } catch (e) {
      setError(
        e instanceof ApiError && e.status === 503
          ? "The AI isn't set up on this server. Answer the questions below instead."
          : e instanceof Error ? e.message : "Couldn't read that description.",
      );
    } finally {
      setBusy(false);
    }
  };

  const generate = async (courses: string[], rules: AvoidRule[], min = minCredits, max = maxCredits) => {
    setBusy(true);
    setError(null);
    setStep("results");
    const placedCredits = creditsOfCrns(placed, sectionByCrn);
    try {
      const response = await api.generate({
        term: API_TERM,
        maxResults: 3,
        preferences: {
          hardConstraints: {
            requiredCourseIds: courses,
            minCredits: Math.max(1, min - placedCredits),
            maxCredits: Math.max(1, max - placedCredits),
            // Keep what's already on the calendar in place: its meetings are off limits.
            blockedTimes: [...rules.flatMap((r) => AVOID_RULES[r].blocks), ...extraBlocks, ...meetingsAsBlocks(placed, sectionByCrn)],
          },
          softPreferences: { minimizeGaps: { weight: 2 } },
        },
      });
      setOptions(response.schedules);
      setDiagnostics(response.diagnostics);
    } catch (e) {
      setOptions([]);
      setDiagnostics([]);
      setError(e instanceof Error ? e.message : "Couldn't build schedules.");
    } finally {
      setBusy(false);
    }
  };

  const rerun = (courses: string[], rules: AvoidRule[]) => {
    setPicked(courses);
    setAvoid(rules);
    void generate(courses, rules);
  };

  return (
    <aside className="flex h-full flex-col border-l border-grid bg-white" aria-label="AI assistant">
      <div className="flex items-center justify-between border-b border-grid px-4 py-3">
        <h2 className="flex items-center gap-2 text-lg font-bold">
          <Sparkles className="size-4" aria-hidden /> Build with AI
        </h2>
        <Button variant="ghost" size="icon-sm" onClick={onClose} aria-label="Close AI assistant">
          <X aria-hidden />
        </Button>
      </div>

      <div className="min-h-0 flex-1 space-y-5 overflow-y-auto p-4 text-sm">
        {step === "ask" && (
          <>
            <p className="text-muted-foreground">
              Anything already on your calendar stays where it is. Nothing changes until you pick an option.
            </p>
            <div className="space-y-1.5">
              <Label htmlFor="ai-describe">Describe your week (optional)</Label>
              <Textarea
                id="ai-describe"
                rows={3}
                maxLength={2000}
                value={description}
                onChange={(e) => setDescription(e.target.value)}
                placeholder="No classes before 10, Fridays off, I need CSE 140"
              />
              <Button variant="outline" size="sm" disabled={!description.trim() || busy} onClick={fillFromDescription}>
                {busy && <Loader2 className="animate-spin" aria-hidden />} Fill in the answers
              </Button>
              {aiNote.map((n) => (
                <p key={n} className="text-xs text-muted-foreground">{n}</p>
              ))}
            </div>

            <fieldset className="space-y-1.5">
              <legend className="font-bold">Credits this term</legend>
              <div className="flex items-center gap-2">
                <Select value={String(minCredits)} onValueChange={(v) => setMinCredits(Number(v))}>
                  <SelectTrigger className="w-20" aria-label="Minimum credits"><SelectValue /></SelectTrigger>
                  <SelectContent>{CREDIT_OPTIONS.map((c) => <SelectItem key={c} value={c}>{c}</SelectItem>)}</SelectContent>
                </Select>
                <span>to</span>
                <Select value={String(maxCredits)} onValueChange={(v) => setMaxCredits(Number(v))}>
                  <SelectTrigger className="w-20" aria-label="Maximum credits"><SelectValue /></SelectTrigger>
                  <SelectContent>{CREDIT_OPTIONS.map((c) => <SelectItem key={c} value={c}>{c}</SelectItem>)}</SelectContent>
                </Select>
              </div>
              {minCredits > maxCredits && <p className="text-signal">The minimum is above the maximum.</p>}
            </fieldset>

            <fieldset className="space-y-2">
              <legend className="font-bold">Times to avoid</legend>
              {(Object.keys(AVOID_RULES) as AvoidRule[]).map((rule) => (
                <label key={rule} className="flex items-center gap-2">
                  <Checkbox
                    checked={avoid.includes(rule)}
                    onCheckedChange={(on) => setAvoid(on ? [...avoid, rule] : avoid.filter((r) => r !== rule))}
                  />
                  {AVOID_RULES[rule].label}
                </label>
              ))}
              {extraBlocks.length > 0 && (
                <p className="text-xs text-muted-foreground">
                  Also keeping {extraBlocks.length} blocked {extraBlocks.length === 1 ? "time" : "times"} from your description.{" "}
                  <button className="underline" onClick={() => setExtraBlocks([])}>Remove</button>
                </p>
              )}
            </fieldset>

            <Button className="w-full" disabled={minCredits > maxCredits} onClick={() => setStep("pick")}>
              Next: choose courses
            </Button>
          </>
        )}

        {step === "pick" && (
          <>
            <p className="text-muted-foreground">Tick the courses you want this term. These are your recommended courses.</p>
            {candidates.length === 0 ? (
              <p>Every recommended course is already on your calendar.</p>
            ) : (
              <ul className="divide-y divide-grid rounded-sm border border-grid">
                {candidates.map((id) => (
                  <li key={id}>
                    <label className="flex items-center gap-3 px-3 py-2">
                      <Checkbox
                        checked={chosen.includes(id)}
                        onCheckedChange={(on) => setPicked(on ? [...chosen, id] : chosen.filter((c) => c !== id))}
                      />
                      <span className="h-4 w-1.5 rounded-sm" style={{ background: KIND_COLOR[kindOf(student, doc.degree, id)] }} aria-hidden />
                      <span className="font-heading font-semibold [font-stretch:85%]">{courseCode(id)}</span>
                    </label>
                  </li>
                ))}
              </ul>
            )}
            <div className="flex gap-2">
              <Button variant="outline" onClick={() => setStep("ask")}>Back</Button>
              <Button className="flex-1" disabled={chosen.length === 0} onClick={() => generate(chosen, avoid)}>
                Build up to 3 options
              </Button>
            </div>
          </>
        )}

        {step === "results" && (
          <>
            {busy && (
              <p className="flex items-center gap-2 text-muted-foreground">
                <Loader2 className="size-4 animate-spin" aria-hidden /> Checking every section combination…
              </p>
            )}
            {error && <p className="text-signal">{error}</p>}

            {!busy && options.length === 0 && !error && (
              <div className="space-y-3">
                <p className="font-bold">These picks can&apos;t all fit.</p>
                {diagnostics.map((d) => {
                  const blocked = blockedCourse(d);
                  const pair = alwaysConflict(d);
                  return (
                    <div key={d} className="space-y-2 rounded-sm border border-grid p-3">
                      <p>{d}</p>
                      <div className="flex flex-wrap gap-2">
                        {blocked && avoid.map((rule) => (
                          <Button key={rule} size="sm" variant="outline" onClick={() => rerun(chosen, avoid.filter((r) => r !== rule))}>
                            {AVOID_RULES[rule].allow}
                          </Button>
                        ))}
                        {blocked && (
                          <Button size="sm" variant="outline" onClick={() => rerun(chosen.filter((c) => c !== blocked), avoid)}>
                            Skip {courseCode(blocked)}
                          </Button>
                        )}
                        {pair?.map((keep) => (
                          <Button key={keep} size="sm" variant="outline" onClick={() => rerun(chosen.filter((c) => c !== (keep === pair[0] ? pair[1] : pair[0])), avoid)}>
                            Keep {courseCode(keep)}
                          </Button>
                        ))}
                        {(blocked || pair) && (
                          <Button size="sm" variant="ghost" onClick={() => setStep("pick")}>Pick another course</Button>
                        )}
                      </div>
                    </div>
                  );
                })}
                <Button variant="outline" onClick={() => setStep("ask")}>Change answers</Button>
              </div>
            )}

            {!busy && options.length > 0 && (
              <>
                <p className="text-muted-foreground">
                  {options.length === 1 ? "One option fits" : `${options.length} options fit`} every rule. Best first.
                </p>
                <div className="grid gap-3">
                  {options.map((option) => (
                    <article key={option.rank} className="rounded-sm border border-grid p-3">
                      <div className="mb-2 flex items-baseline justify-between">
                        <h3 className="font-bold">Option {option.rank}</h3>
                        <span className="tabular text-xs text-muted-foreground">
                          {option.totalCredits} credits added, fit {option.score}/100
                        </span>
                      </div>
                      <MiniCalendar
                        height={90}
                        blocks={[
                          ...meetingsAsBlocks(placed, sectionByCrn).map((b, i) => ({ id: `p${i}`, ...b, color: "var(--grid)" })),
                          ...option.sections.flatMap((s) =>
                            s.meetings.map((m, i) => ({ id: `${s.crn}-${i}`, days: m.days, start: m.start, end: m.end, color: KIND_COLOR[kindOf(student, doc.degree, s.courseId)] })),
                          ),
                        ]}
                      />
                      <p className="mt-2 text-xs">
                        {option.sections.map((s) => `${courseCode(s.courseId)} ${s.sectionCode}`).join(", ")}
                      </p>
                      <Button
                        size="sm"
                        className="mt-2 w-full"
                        onClick={() => onAccept(option.sections.map((s) => s.crn), `Add AI option ${option.rank}`)}
                      >
                        Use option {option.rank}
                      </Button>
                    </article>
                  ))}
                </div>
                <Button variant="outline" onClick={() => setStep("pick")}>Change courses</Button>
              </>
            )}
          </>
        )}
      </div>
    </aside>
  );
}
