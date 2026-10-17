"use client";

import { useMemo, useState, useSyncExternalStore } from "react";

import Link from "next/link";

import { Lock, MessageSquare } from "lucide-react";
import { toast } from "sonner";

import { DegreeGrid } from "@/components/degree-grid";
import { Button } from "@/components/ui/button";
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle } from "@/components/ui/dialog";
import { Label } from "@/components/ui/label";
import { Textarea } from "@/components/ui/textarea";
import { DEMO_ADVISOR, OTHER_ADVISEES, PROGRAM, type StudentAccount, TERMS } from "@/lib/demo/program";
import { autoFill, courseCode, type DegreePlan, isFeasible } from "@/lib/rules";
import { type Comment, type PlanStatus, student, useStore } from "@/lib/store";
import { formatDay, formatWhen } from "@/lib/ui";
import { cn } from "@/lib/utils";

const subscribeHash = (onChange: () => void) => {
  window.addEventListener("hashchange", onChange);
  return () => window.removeEventListener("hashchange", onChange);
};
/** The comment target a notification links to, e.g. "#course:CSE-216". */
function useHashTarget(): string | null {
  return useSyncExternalStore(subscribeHash, () => decodeURIComponent(window.location.hash.slice(1)) || null, () => null);
}

const STATUS_STYLE: Record<PlanStatus, string> = {
  Draft: "bg-muted text-ink",
  Submitted: "bg-accent text-ink",
  "Changes requested": "bg-[#FBE4E1] text-signal",
  Approved: "bg-[#DDF0E7] text-[#1F6B4B]",
};

const emptyPlan = (): DegreePlan => Object.fromEntries(TERMS.map((t) => [t.id, []]));

function targetLabel(target: string): string {
  if (target === "plan") return "the whole plan";
  const [kind, id] = target.split(":");
  return kind === "course" ? courseCode(id) : (TERMS.find((t) => t.id === id)?.label ?? id);
}

function CommentList({ comments, highlight }: { comments: Comment[]; highlight: string | null }) {
  if (comments.length === 0) return <p className="text-sm text-muted-foreground">No comments yet.</p>;
  return (
    <ul className="space-y-2">
      {[...comments].reverse().map((c) => (
        <li key={c.id} className={cn("rounded-sm border border-grid bg-white p-3 text-sm", highlight === c.target && "ring-2 ring-marker")}>
          <p className="text-xs text-muted-foreground">
            {c.author} on <b className="text-ink">{targetLabel(c.target)}</b>, {formatWhen(c.at)}
          </p>
          <p className="mt-1">{c.text}</p>
        </li>
      ))}
    </ul>
  );
}

export default function AdvisorPage() {
  const store = useStore();
  const { session, doc, status, statusAt, comments } = store;
  const highlight = useHashTarget();
  const [openId, setOpenId] = useState<string>(student.id);
  const [composer, setComposer] = useState<{ target: string; label: string } | null>(null);
  const [text, setText] = useState("");

  const others = useMemo(() => OTHER_ADVISEES.map((s) => ({ account: s, plan: autoFill(s, emptyPlan()) })), []);

  if (!session) return null;
  const isAdvisor = session.role === "advisor";

  if (!isAdvisor) {
    const unlocked = isFeasible(student, doc.degree);
    if (!unlocked) {
      return (
        <div className="mx-auto max-w-lg rounded-sm border border-grid bg-white p-6">
          <Lock className="size-5" aria-hidden />
          <h1 className="mt-2 text-2xl font-bold">Advisor review is locked</h1>
          <p className="mt-2 text-sm">Complete your degree plan to unlock advisor review: place every requirement and keep prerequisites in order.</p>
          <Button asChild className="mt-4"><Link href="/degree">Go to your degree plan</Link></Button>
        </div>
      );
    }
    return (
      <div className="space-y-4">
        <div className="flex flex-wrap items-end gap-4">
          <div>
            <p className="text-sm text-muted-foreground">Reviewed by {DEMO_ADVISOR.name}</p>
            <h1 className="text-3xl font-bold [font-stretch:85%]">Advisor review</h1>
          </div>
          <span className={cn("rounded-sm px-2 py-1 text-sm font-bold", STATUS_STYLE[status])}>{status}</span>
          {status === "Draft" && <p className="text-sm text-muted-foreground">Submit your plan from Plan your degree when you&apos;re ready.</p>}
        </div>
        <div className="grid gap-4 xl:grid-cols-[1fr_20rem]">
          <DegreeGrid student={student} plan={doc.degree} comments={comments} highlight={highlight} />
          <aside className="space-y-2">
            <h2 className="text-lg font-bold">Comments</h2>
            <CommentList comments={comments} highlight={highlight} />
          </aside>
        </div>
      </div>
    );
  }

  const rows = [
    { account: student, plan: doc.degree, status, updated: statusAt, live: true },
    ...others.map(({ account, plan }, i) => ({
      account,
      plan,
      status: (i === 0 ? "Submitted" : "Approved") as PlanStatus,
      updated: i === 0 ? "2026-10-14T15:20:00-04:00" : "2026-10-09T10:05:00-04:00",
      live: false,
    })),
  ];
  const open = rows.find((r) => r.account.id === openId) ?? rows[0];

  const decide = (next: PlanStatus) => {
    store.setStatus(next);
    store.notify(
      next === "Approved" ? `${DEMO_ADVISOR.name} approved your degree plan.` : `${DEMO_ADVISOR.name} asked for changes to your degree plan.`,
      "/degree",
    );
    toast(next === "Approved" ? "Plan approved" : "Changes requested", { description: `${student.name} has been notified.` });
  };

  const addComment = () => {
    if (!composer || !text.trim()) return;
    store.addComment(composer.target, text.trim());
    store.notify(`${DEMO_ADVISOR.name} commented on ${composer.label}: “${text.trim()}”`, `/advisor#${composer.target}`);
    toast("Comment added", { description: `${student.name} has been notified.` });
    setComposer(null);
    setText("");
  };

  return (
    <div className="grid gap-4 lg:grid-cols-[17rem_1fr]">
      <aside className="h-fit rounded-sm border border-grid bg-white">
        <h1 className="border-b border-grid px-3 py-2.5 text-lg font-bold">Advisees</h1>
        <ul className="divide-y divide-grid">
          {rows.map((r) => (
            <li key={r.account.id}>
              <button
                type="button"
                onClick={() => setOpenId(r.account.id)}
                aria-current={r.account.id === open.account.id}
                className={cn("w-full px-3 py-2.5 text-left text-sm hover:bg-paper", r.account.id === open.account.id && "bg-accent/50")}
              >
                <span className="block font-bold">{r.account.name}</span>
                <span className="block text-xs text-muted-foreground">{r.account.year}, last updated {formatDay(r.updated)}</span>
                <span className={cn("mt-1 inline-block rounded-sm px-1.5 py-px text-xs font-bold", STATUS_STYLE[r.status])}>{r.status}</span>
              </button>
            </li>
          ))}
        </ul>
      </aside>

      <section className="min-w-0 space-y-4">
        <div className="flex flex-wrap items-end gap-3">
          <div className="mr-auto">
            <p className="text-sm text-muted-foreground">{PROGRAM.name}, {open.account.year}</p>
            <h2 className="text-3xl font-bold [font-stretch:85%]">{open.account.name}&apos;s plan</h2>
          </div>
          {open.live ? (
            <>
              <Button variant="outline" onClick={() => setComposer({ target: "plan", label: "your plan" })}>
                <MessageSquare aria-hidden /> Comment on plan
              </Button>
              <Button variant="outline" onClick={() => decide("Changes requested")} disabled={status === "Draft"}>Request changes</Button>
              <Button onClick={() => decide("Approved")} disabled={status === "Draft" || status === "Approved"}>Approve</Button>
            </>
          ) : (
            <p className="text-sm text-muted-foreground">Demo advisee: open {student.name}&apos;s plan to leave comments.</p>
          )}
        </div>
        {open.live && status === "Draft" && (
          <p className="rounded-sm bg-accent px-3 py-2 text-sm">{student.name} hasn&apos;t submitted this plan yet. You can still comment on the draft.</p>
        )}
        <DegreeGrid
          student={open.account as StudentAccount}
          plan={open.plan}
          comments={open.live ? comments : []}
          onComment={open.live ? (target, label) => setComposer({ target, label }) : undefined}
          highlight={highlight}
        />
        {open.live && (
          <div className="space-y-2">
            <h3 className="text-lg font-bold">Comments</h3>
            <CommentList comments={comments} highlight={highlight} />
          </div>
        )}
      </section>

      <Dialog open={composer !== null} onOpenChange={(o) => !o && setComposer(null)}>
        <DialogContent>
          <DialogHeader>
            <DialogTitle>Comment on {composer?.label}</DialogTitle>
            <DialogDescription>{student.name} gets a notification that links here.</DialogDescription>
          </DialogHeader>
          <div className="space-y-1.5">
            <Label htmlFor="comment">Comment</Label>
            <Textarea id="comment" rows={4} value={text} onChange={(e) => setText(e.target.value)} placeholder="Take this before junior year for internship applications." />
          </div>
          <DialogFooter>
            <Button variant="outline" onClick={() => setComposer(null)}>Cancel</Button>
            <Button onClick={addComment} disabled={!text.trim()}>Add comment</Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>
    </div>
  );
}
