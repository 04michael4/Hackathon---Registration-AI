"use client";

import { useState, type FormEvent } from "react";

import { useRouter } from "next/navigation";

import { MiniCalendar } from "@/components/week-calendar";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { ToggleGroup, ToggleGroupItem } from "@/components/ui/toggle-group";
import { DEMO_ADVISOR, DEMO_STUDENT } from "@/lib/demo/program";
import { type Role, useStore } from "@/lib/store";

const SAMPLE_WEEK = [
  { id: "a", days: ["MONDAY", "WEDNESDAY", "FRIDAY"] as const, start: "10:00", end: "10:50", color: "var(--req-core)" },
  { id: "b", days: ["TUESDAY", "THURSDAY"] as const, start: "10:45", end: "12:00", color: "var(--req-core)" },
  { id: "c", days: ["TUESDAY"] as const, start: "13:00", end: "15:50", color: "var(--req-elective)" },
  { id: "d", days: ["MONDAY", "WEDNESDAY"] as const, start: "15:00", end: "16:15", color: "var(--req-gened)" },
  { id: "e", days: ["THURSDAY"] as const, start: "15:00", end: "17:50", color: "var(--req-free)" },
];

export default function LoginPage() {
  const { login } = useStore();
  const router = useRouter();
  const [role, setRole] = useState<Role>("student");
  const [username, setUsername] = useState("");

  const enter = (r: Role, name: string) => {
    login({ role: r, name });
    router.push(r === "student" ? "/plan" : "/advisor");
  };

  const onSubmit = (event: FormEvent) => {
    event.preventDefault();
    const fallback = role === "student" ? DEMO_STUDENT.name : DEMO_ADVISOR.name;
    enter(role, username.trim() || fallback);
  };

  return (
    <main className="mx-auto grid min-h-screen max-w-5xl items-center gap-12 px-6 py-12 md:grid-cols-[1.1fr_1fr]">
      <section>
        <p className="font-heading text-3xl font-extrabold tracking-tight [font-stretch:80%]">syllo</p>
        <h1 className="mt-8 max-w-md text-4xl leading-[1.05] font-bold [font-stretch:85%] md:text-5xl">
          Plan next semester and the three years after it.
        </h1>
        <p className="mt-4 max-w-md text-base text-muted-foreground">
          Build a week that fits, see which courses unlock what, and send your four-year plan to your advisor for review.
        </p>
        <div className="mt-8 max-w-sm">
          <MiniCalendar blocks={SAMPLE_WEEK.map((b) => ({ ...b, days: [...b.days] }))} height={150} />
        </div>
      </section>

      <section className="rounded-sm border border-grid bg-white p-6">
        <h2 className="text-xl font-bold">Sign in</h2>
        <p className="mt-1 text-sm text-muted-foreground">This is a demo. Any username and password works, and nothing is stored or sent.</p>

        <form onSubmit={onSubmit} className="mt-5 space-y-4">
          <ToggleGroup
            type="single"
            variant="outline"
            value={role}
            onValueChange={(v) => v && setRole(v as Role)}
            aria-label="Sign in as"
            className="w-full"
          >
            <ToggleGroupItem value="student" className="flex-1">Student</ToggleGroupItem>
            <ToggleGroupItem value="advisor" className="flex-1">Advisor</ToggleGroupItem>
          </ToggleGroup>
          <div className="space-y-1.5">
            <Label htmlFor="username">Username</Label>
            <Input id="username" autoComplete="off" value={username} onChange={(e) => setUsername(e.target.value)} placeholder={role === "student" ? "jlee" : "mpatel"} />
          </div>
          <div className="space-y-1.5">
            <Label htmlFor="password">Password</Label>
            <Input id="password" type="password" autoComplete="off" placeholder="Anything" />
          </div>
          <Button type="submit" className="w-full">Sign in as {role}</Button>
        </form>

        <div className="mt-6 border-t border-grid pt-4">
          <p className="text-sm font-bold">Or use a demo account</p>
          <div className="mt-2 grid gap-2">
            <Button variant="outline" className="h-auto justify-start py-2 text-left" onClick={() => enter("student", DEMO_STUDENT.name)}>
              <span>
                <span className="block">Demo student: {DEMO_STUDENT.name}</span>
                <span className="block text-xs font-normal text-muted-foreground">{DEMO_STUDENT.year}, {DEMO_STUDENT.program}</span>
              </span>
            </Button>
            <Button variant="outline" className="h-auto justify-start py-2 text-left" onClick={() => enter("advisor", DEMO_ADVISOR.name)}>
              <span>
                <span className="block">Demo advisor: {DEMO_ADVISOR.name}</span>
                <span className="block text-xs font-normal text-muted-foreground">{DEMO_ADVISOR.title}</span>
              </span>
            </Button>
          </div>
        </div>
      </section>
    </main>
  );
}
