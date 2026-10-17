"use client";

import { Check, Clock, Star } from "lucide-react";

import { Button } from "@/components/ui/button";
import { Sheet, SheetContent, SheetDescription, SheetHeader, SheetTitle } from "@/components/ui/sheet";
import { useCatalog } from "@/lib/catalog";
import { COURSES, NEXT_TERM, PROFESSOR_RATINGS, termLabel } from "@/lib/demo/program";
import { componentLabel, whenText } from "@/lib/plan";
import { courseCode, historyTermOf, missingPrereqs, requirementFor, termOf, unlocks } from "@/lib/rules";
import { student, useStore } from "@/lib/store";

interface CourseDrawerProps {
  courseId: string | null;
  onClose: () => void;
  /** Shows this course's sections on the calendar to pick from. */
  onShowSections?: (courseId: string) => void;
}

export function CourseDrawer({ courseId, onClose, onShowSections }: CourseDrawerProps) {
  const { courses } = useCatalog();
  const { doc } = useStore();
  const detail = courseId ? courses[courseId] : undefined;
  const meta = courseId ? COURSES[courseId] : undefined;
  const missing = courseId ? missingPrereqs(student, doc.degree, courseId, NEXT_TERM) : [];
  const requirement = courseId ? requirementFor(student, doc.degree, courseId) : undefined;

  return (
    <Sheet open={courseId !== null} onOpenChange={(open) => !open && onClose()}>
      <SheetContent className="w-full overflow-y-auto sm:max-w-md">
        {courseId && (
          <>
            <SheetHeader>
              <SheetTitle className="font-heading text-2xl">
                {courseCode(courseId)} <span className="font-sans text-lg font-normal">{detail?.title ?? meta?.title}</span>
              </SheetTitle>
              <SheetDescription>
                {detail?.credits ?? meta?.credits} credits. {requirement ? `Counts toward ${requirement.label}.` : "Counts as a free elective."}
              </SheetDescription>
            </SheetHeader>

            <div className="space-y-5 px-4 pb-6 text-sm">
              {detail?.description && <p>{detail.description}</p>}

              <section>
                <h3 className="text-base font-bold">Prerequisites</h3>
                {meta?.prereqs.length ? (
                  <ul className="mt-1 space-y-1">
                    {meta.prereqs.map((p) => {
                      const where = termOf(student, doc.degree, p);
                      const taken = historyTermOf(student, p);
                      return (
                        <li key={p} className="flex items-center gap-2">
                          {missing.includes(p) ? <Clock className="size-4 text-signal" aria-hidden /> : <Check className="size-4 text-brand" aria-hidden />}
                          <span>
                            {courseCode(p)}{" "}
                            <span className="text-muted-foreground">
                              {taken ? `taken ${termLabel(taken)}` : where ? `planned ${termLabel(where)}` : "not taken yet"}
                            </span>
                          </span>
                        </li>
                      );
                    })}
                  </ul>
                ) : (
                  <p className="mt-1 text-muted-foreground">None.</p>
                )}
              </section>

              {unlocks(courseId).length > 0 && (
                <section>
                  <h3 className="text-base font-bold">Unlocks</h3>
                  <p className="mt-1">{unlocks(courseId).map(courseCode).join(", ")}</p>
                </section>
              )}

              <section>
                <h3 className="text-base font-bold">Sections in {termLabel(NEXT_TERM)}</h3>
                {!detail ? (
                  <p className="mt-1 text-muted-foreground">Not offered in {termLabel(NEXT_TERM)}.</p>
                ) : (
                  <ul className="mt-2 divide-y divide-grid rounded-sm border border-grid">
                    {detail.sections.map((s) => (
                      <li key={s.crn} className="px-3 py-2">
                        <div className="flex items-baseline justify-between gap-2">
                          <span className="font-bold">
                            {componentLabel(s)} {s.sectionCode}
                          </span>
                          <span className="tabular text-xs text-muted-foreground">CRN {s.crn}</span>
                        </div>
                        <p className="tabular">{whenText(s)}</p>
                        {s.instructors.map((name) => {
                          const rating = PROFESSOR_RATINGS[name];
                          return (
                            <p key={name} className="flex items-center gap-1.5 text-muted-foreground">
                              {name}
                              {rating && (
                                <span className="inline-flex items-center gap-0.5 text-ink">
                                  <Star className="size-3.5 fill-marker text-ink" aria-hidden />
                                  <span className="tabular">{rating.rating.toFixed(1)}</span>
                                  <span className="text-muted-foreground">, {rating.takeAgain}% would take again</span>
                                </span>
                              )}
                            </p>
                          );
                        })}
                        <p className={s.status === "CLOSED" ? "font-bold text-signal" : "text-muted-foreground"}>
                          {s.status === "CLOSED" ? "Closed" : s.status === "OPEN" ? "Open" : "Seat status unknown"}
                        </p>
                      </li>
                    ))}
                  </ul>
                )}
                <p className="mt-2 text-xs text-muted-foreground">Ratings are mock data. Seat counts aren&apos;t in the demo catalog.</p>
              </section>

              {detail && onShowSections && missing.length === 0 && (
                <Button className="w-full" onClick={() => onShowSections(courseId)}>
                  Show sections on the calendar
                </Button>
              )}
              {missing.length > 0 && (
                <p className="rounded-sm bg-accent px-3 py-2">
                  Take {missing.map(courseCode).join(" and ")} first.
                </p>
              )}
            </div>
          </>
        )}
      </SheetContent>
    </Sheet>
  );
}
