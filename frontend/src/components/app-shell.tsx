"use client";

import { useEffect, type ReactNode } from "react";

import Link from "next/link";
import { usePathname, useRouter } from "next/navigation";

import { Bell, Lock, Redo2, Undo2 } from "lucide-react";

import { Button } from "@/components/ui/button";
import { DropdownMenu, DropdownMenuContent, DropdownMenuItem, DropdownMenuLabel, DropdownMenuSeparator, DropdownMenuTrigger } from "@/components/ui/dropdown-menu";
import { Popover, PopoverContent, PopoverTrigger } from "@/components/ui/popover";
import { Tooltip, TooltipContent, TooltipTrigger } from "@/components/ui/tooltip";
import { isFeasible } from "@/lib/rules";
import { student, useStore } from "@/lib/store";
import { formatWhen } from "@/lib/ui";
import { cn } from "@/lib/utils";

const TABS = [
  { href: "/plan", label: "Plan next semester" },
  { href: "/degree", label: "Plan your degree" },
  { href: "/advisor", label: "Advisor portal" },
  { href: "/saved", label: "Saved schedules" },
];

const ADVISOR_LOCK = "Complete your degree plan to unlock advisor review.";

function isTyping(target: EventTarget | null): boolean {
  return target instanceof HTMLElement && (target.isContentEditable || ["INPUT", "TEXTAREA", "SELECT"].includes(target.tagName));
}

export function AppShell({ children }: { children: ReactNode }) {
  const store = useStore();
  const { session, undo, redo, canUndo, canRedo, undoLabel, redoLabel } = store;
  const pathname = usePathname();
  const router = useRouter();

  useEffect(() => {
    if (!session) router.replace("/");
  }, [session, router]);

  useEffect(() => {
    const onKey = (event: KeyboardEvent) => {
      if (!(event.metaKey || event.ctrlKey) || event.key.toLowerCase() !== "z" || isTyping(event.target)) return;
      event.preventDefault();
      if (event.shiftKey) redo();
      else undo();
    };
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, [undo, redo]);

  if (!session) return null;
  const isStudent = session.role === "student";
  const advisorLocked = isStudent && !isFeasible(student, store.doc.degree);
  const unread = store.notifications.filter((n) => !n.read).length;
  const mod = typeof navigator !== "undefined" && /Mac/.test(navigator.platform) ? "⌘" : "Ctrl+";

  return (
    <div className="flex min-h-screen flex-col">
      <header className="sticky top-0 z-40 border-b border-grid bg-white">
        <div className="mx-auto flex h-14 max-w-[1500px] items-center gap-6 px-4">
          <Link href={isStudent ? "/plan" : "/advisor"} className="font-heading text-2xl font-extrabold tracking-tight [font-stretch:80%]">
            syllo
          </Link>
          <nav aria-label="Main" className="flex h-full items-stretch gap-1">
            {TABS.map((tab) => {
              const active = pathname.startsWith(tab.href);
              const locked = tab.href === "/advisor" && advisorLocked;
              const studentOnly = !isStudent && tab.href !== "/advisor";
              if (studentOnly) return null;
              const className = cn(
                "relative flex items-center gap-1.5 px-3 text-sm",
                active ? "font-bold text-ink" : "text-muted-foreground hover:text-ink",
                locked && "cursor-not-allowed text-muted-foreground/70 hover:text-muted-foreground/70",
              );
              const marker = active && <span className="absolute inset-x-3 bottom-0 h-[3px] bg-marker" aria-hidden />;
              if (locked) {
                return (
                  <Tooltip key={tab.href}>
                    <TooltipTrigger asChild>
                      <span className={className} aria-disabled="true" tabIndex={0}>
                        <Lock className="size-3.5" aria-hidden /> {tab.label}
                        <span className="sr-only">. {ADVISOR_LOCK}</span>
                      </span>
                    </TooltipTrigger>
                    <TooltipContent>{ADVISOR_LOCK}</TooltipContent>
                  </Tooltip>
                );
              }
              return (
                <Link key={tab.href} href={tab.href} className={className} aria-current={active ? "page" : undefined}>
                  {tab.label}
                  {marker}
                </Link>
              );
            })}
          </nav>

          <div className="ml-auto flex items-center gap-1">
            {isStudent && (
              <>
                <Tooltip>
                  <TooltipTrigger asChild>
                    <Button variant="ghost" size="sm" onClick={undo} disabled={!canUndo}>
                      <Undo2 aria-hidden /> Undo
                    </Button>
                  </TooltipTrigger>
                  <TooltipContent>{undoLabel ? `Undo “${undoLabel}”` : "Nothing to undo"} ({mod}Z)</TooltipContent>
                </Tooltip>
                <Tooltip>
                  <TooltipTrigger asChild>
                    <Button variant="ghost" size="sm" onClick={redo} disabled={!canRedo}>
                      <Redo2 aria-hidden /> Redo
                    </Button>
                  </TooltipTrigger>
                  <TooltipContent>{redoLabel ? `Redo “${redoLabel}”` : "Nothing to redo"} ({mod}Shift+Z)</TooltipContent>
                </Tooltip>
              </>
            )}

            <Popover onOpenChange={(open) => !open && unread > 0 && store.readAll()}>
              <PopoverTrigger asChild>
                <Button variant="ghost" size="icon" aria-label={unread ? `Notifications, ${unread} unread` : "Notifications"} className="relative">
                  <Bell aria-hidden />
                  {unread > 0 && (
                    <span className="tabular absolute -top-0.5 -right-0.5 grid min-w-4 place-items-center rounded-full bg-signal px-1 text-[0.65rem] font-bold text-white">
                      {unread}
                    </span>
                  )}
                </Button>
              </PopoverTrigger>
              <PopoverContent align="end" className="w-80 p-0">
                <p className="border-b border-grid px-3 py-2 font-heading text-sm font-semibold">Notifications</p>
                {store.notifications.length === 0 ? (
                  <p className="px-3 py-4 text-sm text-muted-foreground">Comments and status changes from your advisor will show up here.</p>
                ) : (
                  <ul className="max-h-80 overflow-y-auto">
                    {store.notifications.map((n) => (
                      <li key={n.id}>
                        <Link href={n.href} className={cn("block border-b border-grid px-3 py-2 text-sm last:border-b-0 hover:bg-paper", !n.read && "bg-accent/40")}>
                          {n.text}
                          <span className="tabular block text-xs text-muted-foreground">{formatWhen(n.at)}</span>
                        </Link>
                      </li>
                    ))}
                  </ul>
                )}
              </PopoverContent>
            </Popover>

            <DropdownMenu>
              <DropdownMenuTrigger asChild>
                <Button variant="ghost" size="sm" className="gap-2">
                  <span className="grid size-7 place-items-center rounded-full bg-ink text-xs font-bold text-white" aria-hidden>
                    {session.name.split(" ").map((p) => p[0]).slice(0, 2).join("")}
                  </span>
                  <span className="hidden text-sm sm:inline">{session.name}</span>
                </Button>
              </DropdownMenuTrigger>
              <DropdownMenuContent align="end" className="w-56">
                <DropdownMenuLabel>
                  {session.name}
                  <span className="block text-xs font-normal text-muted-foreground">{isStudent ? `${student.year}, ${student.program}` : "Advisor"}</span>
                </DropdownMenuLabel>
                <DropdownMenuSeparator />
                <DropdownMenuItem onSelect={() => store.resetDemo()}>Reset demo data</DropdownMenuItem>
                <DropdownMenuItem onSelect={() => { store.logout(); router.replace("/"); }}>Sign out</DropdownMenuItem>
              </DropdownMenuContent>
            </DropdownMenu>
          </div>
        </div>
      </header>
      <main className="mx-auto w-full max-w-[1500px] flex-1 px-4 py-5">{children}</main>
      <footer className="border-t border-grid px-4 py-3 text-center text-xs text-muted-foreground">
        Student hackathon prototype with made-up demo data. Not affiliated with Lehigh University, and it doesn&apos;t register you for classes.
      </footer>
    </div>
  );
}
