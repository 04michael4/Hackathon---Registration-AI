"use client";

import { useState } from "react";

import Link from "next/link";
import { useRouter } from "next/navigation";

import { MoreHorizontal, Pin } from "lucide-react";
import { toast } from "sonner";

import { MiniCalendar } from "@/components/week-calendar";
import { Button } from "@/components/ui/button";
import { Dialog, DialogContent, DialogFooter, DialogHeader, DialogTitle } from "@/components/ui/dialog";
import { DropdownMenu, DropdownMenuContent, DropdownMenuItem, DropdownMenuSeparator, DropdownMenuTrigger } from "@/components/ui/dropdown-menu";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { useCatalog } from "@/lib/catalog";
import { NEXT_TERM, termLabel } from "@/lib/demo/program";
import { coursesOf } from "@/lib/plan";
import { courseCode, kindOf } from "@/lib/rules";
import { type SavedSchedule, student, uid, useStore } from "@/lib/store";
import { KIND_COLOR, formatWhen } from "@/lib/ui";
import { cn } from "@/lib/utils";

export default function SavedPage() {
  const { doc, commit, undo } = useStore();
  const { sectionByCrn } = useCatalog();
  const router = useRouter();
  const [renaming, setRenaming] = useState<SavedSchedule | null>(null);
  const [name, setName] = useState("");

  const ordered = [...doc.saved].sort((a, b) => Number(b.id === doc.finalId) - Number(a.id === doc.finalId));

  const openSchedule = (s: SavedSchedule) => {
    commit(`Open “${s.name}”`, (d) => ({ ...d, crns: s.crns, courses: coursesOf(s.crns, sectionByCrn) }));
    toast(`Opened “${s.name}”`, { description: "Your previous calendar is one Undo away." });
    router.push("/plan");
  };

  const duplicate = (s: SavedSchedule) =>
    commit(`Duplicate “${s.name}”`, (d) => ({
      ...d,
      saved: [{ ...s, id: uid(), name: `${s.name} (copy)`, updatedAt: new Date().toISOString() }, ...d.saved],
    }));

  const remove = (s: SavedSchedule) => {
    commit(`Delete “${s.name}”`, (d) => ({ ...d, saved: d.saved.filter((x) => x.id !== s.id), finalId: d.finalId === s.id ? null : d.finalId }));
    toast(`Deleted “${s.name}”`, { action: { label: "Undo", onClick: () => undo() } });
  };

  const markFinal = (s: SavedSchedule) =>
    commit(doc.finalId === s.id ? `Unmark “${s.name}”` : `Mark “${s.name}” as final`, (d) => ({ ...d, finalId: d.finalId === s.id ? null : s.id }));

  const rename = () => {
    if (!renaming || !name.trim()) return;
    commit(`Rename to “${name.trim()}”`, (d) => ({
      ...d,
      saved: d.saved.map((x) => (x.id === renaming.id ? { ...x, name: name.trim(), updatedAt: new Date().toISOString() } : x)),
    }));
    setRenaming(null);
  };

  return (
    <div className="space-y-4">
      <div>
        <h1 className="text-3xl font-bold [font-stretch:85%]">Saved schedules</h1>
        <p className="text-sm text-muted-foreground">Versions you saved from Plan next semester. Mark one as final to keep it handy on registration day.</p>
      </div>

      {ordered.length === 0 ? (
        <div className="max-w-md rounded-sm border border-grid bg-white p-6">
          <p className="font-bold">No saved schedules yet.</p>
          <p className="mt-1 text-sm text-muted-foreground">Build a week in Plan next semester, then press Save version.</p>
          <Button asChild className="mt-4"><Link href="/plan">Plan next semester</Link></Button>
        </div>
      ) : (
        <ul className="grid gap-4 sm:grid-cols-2 xl:grid-cols-3">
          {ordered.map((s) => {
            const isFinal = doc.finalId === s.id;
            const blocks = s.crns.flatMap((crn) => {
              const hit = sectionByCrn.get(crn);
              return (hit?.section.meetings ?? []).map((m, i) => ({
                id: `${crn}-${i}`, days: m.days, start: m.start, end: m.end,
                color: KIND_COLOR[kindOf(student, doc.degree, hit?.course.courseId ?? "")],
              }));
            });
            return (
              <li key={s.id} className={cn("rounded-sm border bg-white p-4", isFinal ? "border-ink" : "border-grid")}>
                <div className="flex items-start gap-2">
                  <div className="min-w-0 flex-1">
                    <h2 className="flex items-center gap-1.5 truncate text-lg font-bold">
                      {isFinal && <Pin className="size-4 fill-marker" aria-label="Final" />}
                      {s.name}
                    </h2>
                    <p className="tabular text-xs text-muted-foreground">
                      {termLabel(NEXT_TERM)}, {s.credits} credits, edited {formatWhen(s.updatedAt)}
                    </p>
                  </div>
                  <DropdownMenu>
                    <DropdownMenuTrigger asChild>
                      <Button variant="ghost" size="icon-sm" aria-label={`More actions for ${s.name}`}><MoreHorizontal aria-hidden /></Button>
                    </DropdownMenuTrigger>
                    <DropdownMenuContent align="end">
                      <DropdownMenuItem onSelect={() => duplicate(s)}>Duplicate</DropdownMenuItem>
                      <DropdownMenuItem onSelect={() => { setRenaming(s); setName(s.name); }}>Rename</DropdownMenuItem>
                      <DropdownMenuItem onSelect={() => markFinal(s)}>{isFinal ? "Unmark as final" : "Mark as final"}</DropdownMenuItem>
                      <DropdownMenuSeparator />
                      <DropdownMenuItem variant="destructive" onSelect={() => remove(s)}>Delete</DropdownMenuItem>
                    </DropdownMenuContent>
                  </DropdownMenu>
                </div>
                <div className="mt-3"><MiniCalendar blocks={blocks} height={110} /></div>
                <p className="mt-2 truncate text-xs">{s.courseIds.map(courseCode).join(", ") || "Empty"}</p>
                <div className="mt-3 flex gap-2">
                  <Button size="sm" onClick={() => openSchedule(s)}>Open</Button>
                  {isFinal ? (
                    <span className="self-center text-xs font-bold">Final for registration</span>
                  ) : (
                    <Button size="sm" variant="outline" onClick={() => markFinal(s)}>Mark as final</Button>
                  )}
                </div>
              </li>
            );
          })}
        </ul>
      )}

      <Dialog open={renaming !== null} onOpenChange={(o) => !o && setRenaming(null)}>
        <DialogContent>
          <DialogHeader><DialogTitle>Rename schedule</DialogTitle></DialogHeader>
          <form onSubmit={(e) => { e.preventDefault(); rename(); }} className="space-y-1.5">
            <Label htmlFor="rename">Name</Label>
            <Input id="rename" autoFocus value={name} onChange={(e) => setName(e.target.value)} />
          </form>
          <DialogFooter>
            <Button variant="outline" onClick={() => setRenaming(null)}>Cancel</Button>
            <Button onClick={rename} disabled={!name.trim()}>Rename</Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>
    </div>
  );
}
