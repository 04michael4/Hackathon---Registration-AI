"use client";

import { useMemo, useState } from "react";

import Link from "next/link";

import { CircleAlert, CircleCheck, Wand2 } from "lucide-react";
import { toast } from "sonner";

import { DegreeGrid } from "@/components/degree-grid";
import { Button } from "@/components/ui/button";
import { Dialog, DialogContent, DialogDescription, DialogHeader, DialogTitle } from "@/components/ui/dialog";
import { Tooltip, TooltipContent, TooltipTrigger } from "@/components/ui/tooltip";
import { COURSES, PROGRAM, termLabel } from "@/lib/demo/program";
import { autoFill, checkMove, courseCode, isFeasible, isSlot, planProblems, requirementStatus, slotRequirement, termOf } from "@/lib/rules";
import { student, useStore } from "@/lib/store";
import { KIND_COLOR } from "@/lib/ui";

export default function DegreePage() {
  const store = useStore();
  const { doc, commit, undo, status, comments } = store;
  const plan = doc.degree;
  const [slot, setSlot] = useState<{ item: string; term: string } | null>(null);

  const statuses = useMemo(() => requirementStatus(student, plan), [plan]);
  const feasible = isFeasible(student, plan);
  const { errors } = planProblems(student, plan);
  const totalNeeded = statuses.reduce((s, r) => s + r.requirement.count, 0);
  const totalDone = statuses.reduce((s, r) => s + r.done.length, 0);
  const totalCovered = statuses.reduce((s, r) => s + Math.min(r.requirement.count, r.done.length + r.planned.length + r.slots), 0);
  const percentDone = Math.round((totalDone / totalNeeded) * 100);
  const canSubmit = feasible && (status === "Draft" || status === "Changes requested");

  const move = (item: string, from: string, to: string) => {
    const result = checkMove(student, plan, item, to);
    if (!result.ok) {
      toast.error("Can't move it there", { description: result.message });
      return;
    }
    const label = isSlot(item) ? "elective slot" : courseCode(item);
    commit(`Move ${label} to ${termLabel(to)}`, (d) => ({
      ...d,
      degree: { ...d.degree, [from]: d.degree[from].filter((i) => i !== item), [to]: [...d.degree[to], item] },
    }));
    for (const w of result.warnings) toast.warning(w);
  };

  const remove = (item: string, term: string) => {
    commit(`Remove ${courseCode(item)} from ${termLabel(term)}`, (d) => ({
      ...d,
      degree: { ...d.degree, [term]: d.degree[term].filter((i) => i !== item) },
    }));
    toast(`Removed ${courseCode(item)}`, { action: { label: "Undo", onClick: () => undo() } });
  };

  const fill = () => {
    commit("Auto-fill degree plan", (d) => ({ ...d, degree: autoFill(student, d.degree) }));
    toast("Placed every remaining required course", { action: { label: "Undo", onClick: () => undo() } });
  };

  const chooseForSlot = (courseId: string) => {
    if (!slot) return;
    const withoutSlot = { ...plan, [slot.term]: plan[slot.term].filter((i) => i !== slot.item) };
    const result = checkMove(student, withoutSlot, courseId, slot.term);
    if (!result.ok) {
      toast.error("That course can't go in this term", { description: result.message });
      return;
    }
    commit(`Choose ${courseCode(courseId)}`, (d) => ({
      ...d,
      degree: { ...d.degree, [slot.term]: d.degree[slot.term].map((i) => (i === slot.item ? courseId : i)) },
    }));
    setSlot(null);
  };

  const slotRequirementDef = slot ? PROGRAM.requirements.find((r) => r.id === slotRequirement(slot.item)) : undefined;
  const slotOptions = (slotRequirementDef?.options ?? []).filter((id) => !termOf(student, plan, id));
  const latestComment = comments.at(-1);

  return (
    <div className="space-y-4">
      <div className="flex flex-wrap items-end gap-x-8 gap-y-3">
        <div>
          <p className="text-sm text-muted-foreground">{student.name}, {student.year}</p>
          <h1 className="text-3xl font-bold [font-stretch:85%]">{PROGRAM.name}</h1>
        </div>
        <p className="flex items-center gap-2 text-sm">
          {feasible ? (
            <><CircleCheck className="size-4 text-req-elective" aria-hidden /> Graduation on track for {termLabel("S2029")}</>
          ) : (
            <><CircleAlert className="size-4 text-signal" aria-hidden /> Not on track yet: {errors.length ? "fix the prerequisite order" : "some requirements aren't placed"}</>
          )}
        </p>
        <p className="text-sm">
          Plan status: <b>{status}</b>
        </p>
        <div className="ml-auto flex gap-2">
          <Button variant="outline" onClick={fill}>
            <Wand2 aria-hidden /> Auto-fill
          </Button>
          <Tooltip>
            <TooltipTrigger asChild>
              <span tabIndex={canSubmit ? -1 : 0}>
                <Button
                  disabled={!canSubmit}
                  onClick={() => {
                    store.setStatus("Submitted");
                    toast("Sent to your advisor", { description: "You'll get a notification when they respond." });
                  }}
                >
                  Submit for review
                </Button>
              </span>
            </TooltipTrigger>
            {!canSubmit && (
              <TooltipContent>
                {status === "Submitted" ? "Waiting for your advisor." : status === "Approved" ? "Your advisor approved this plan." : "Place every requirement and fix any prerequisite order first."}
              </TooltipContent>
            )}
          </Tooltip>
        </div>
      </div>

      {(status === "Changes requested" || status === "Approved" || latestComment) && (
        <div className="flex flex-wrap items-center gap-3 rounded-sm border border-ink bg-white px-4 py-2.5 text-sm">
          <span className="mr-auto">
            {status === "Approved" ? "Your advisor approved this plan." : status === "Changes requested" ? "Your advisor asked for changes." : "Your advisor left a comment."}
            {latestComment && <span className="text-muted-foreground"> “{latestComment.text}”</span>}
          </span>
          <Link href="/advisor" className="font-bold underline">See all comments</Link>
        </div>
      )}

      <div className="grid gap-4 xl:grid-cols-[1fr_17rem]">
        <DegreeGrid student={student} plan={plan} onMove={move} onRemove={remove} onFillSlot={(item, term) => setSlot({ item, term })} comments={comments} />

        <aside className="h-fit rounded-sm border border-grid bg-white">
          <div className="border-b border-grid p-3">
            <h2 className="text-lg font-bold">Requirements</h2>
            <p className="tabular text-sm">
              <b className="font-heading text-xl">{percentDone}%</b> complete, {Math.round((totalCovered / totalNeeded) * 100)}% planned
            </p>
            <div className="mt-2 h-2 overflow-hidden rounded-full bg-grid-faint" aria-hidden>
              <div className="h-full bg-ink" style={{ width: `${percentDone}%` }} />
              <div className="-mt-2 h-full bg-ink/30" style={{ width: `${(totalCovered / totalNeeded) * 100}%` }} />
            </div>
          </div>
          <ul className="divide-y divide-grid">
            {statuses.map((s) => {
              const covered = Math.min(s.requirement.count, s.done.length + s.planned.length + s.slots);
              return (
                <li key={s.requirement.id} className="flex items-start gap-2 px-3 py-2 text-sm">
                  <span className="mt-1 h-3 w-1.5 shrink-0 rounded-sm" style={{ background: KIND_COLOR[s.requirement.kind] }} aria-hidden />
                  <span className="min-w-0 flex-1">
                    {s.requirement.label}
                    <span className="block text-xs text-muted-foreground">
                      {s.done.length} done, {s.planned.length + s.slots} planned of {s.requirement.count}
                    </span>
                  </span>
                  {covered >= s.requirement.count ? (
                    <CircleCheck className="size-4 text-req-elective" aria-label="Covered" />
                  ) : (
                    <span className="tabular text-xs font-bold text-signal">{s.requirement.count - covered} left</span>
                  )}
                </li>
              );
            })}
          </ul>
          <p className="border-t border-grid p-3 text-xs text-muted-foreground">{PROGRAM.note}.</p>
        </aside>
      </div>

      <Dialog open={slot !== null} onOpenChange={(open) => !open && setSlot(null)}>
        <DialogContent>
          <DialogHeader>
            <DialogTitle>Choose a {slotRequirementDef?.label.toLowerCase() ?? "course"}</DialogTitle>
            <DialogDescription>For {slot ? termLabel(slot.term) : ""}. Only courses that satisfy this requirement are listed.</DialogDescription>
          </DialogHeader>
          {slotOptions.length === 0 ? (
            <p className="text-sm text-muted-foreground">Every course for this requirement is already in your plan.</p>
          ) : (
            <ul className="divide-y divide-grid rounded-sm border border-grid">
              {slotOptions.map((id) => {
                const result = slot ? checkMove(student, { ...plan, [slot.term]: plan[slot.term].filter((i) => i !== slot.item) }, id, slot.term) : null;
                return (
                  <li key={id} className="flex items-center gap-3 px-3 py-2">
                    <span className="min-w-0 flex-1">
                      <span className="font-heading font-semibold [font-stretch:85%]">{courseCode(id)}</span> {COURSES[id]?.title}
                      {result && !result.ok && <span className="block text-xs text-signal">{result.message}</span>}
                      {result && result.ok && result.warnings.map((w) => <span key={w} className="block text-xs text-muted-foreground">{w}</span>)}
                    </span>
                    <Button size="sm" variant="outline" disabled={!result?.ok} onClick={() => chooseForSlot(id)}>Choose</Button>
                  </li>
                );
              })}
            </ul>
          )}
        </DialogContent>
      </Dialog>
    </div>
  );
}
