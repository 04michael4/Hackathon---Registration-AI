"use client";

import Image from "next/image";
import { useRouter } from "next/navigation";

import { Check } from "lucide-react";

import { Button } from "@/components/ui/button";
import { DEMO_STUDENT } from "@/lib/demo/program";
import { useStore } from "@/lib/store";

/** David's start page: one sign-in entry point. The sign-in is a demo; no credentials are asked for. */
export default function StartPage() {
  const { login } = useStore();
  const router = useRouter();

  const signIn = () => {
    login({ role: "student", name: DEMO_STUDENT.name });
    router.push("/home");
  };

  return (
    <main className="flex h-screen flex-col items-center justify-center bg-paper px-4">
      <div className="mb-6 flex flex-col items-center text-ink">
        <span className="font-heading text-3xl font-extrabold tracking-tight">syllo.</span>
        <span className="text-[0.65rem] font-bold tracking-[0.18em] text-ink-muted">LEHIGH UNIVERSITY</span>
      </div>
      <section className="w-full max-w-[470px] rounded-xl border border-grid bg-white px-10 pt-9 pb-8 text-center">
        <Image src="/lehigh-crest.png" alt="Lehigh University crest" width={64} height={64} className="mx-auto mb-4 size-16" priority />
        <p className="text-[0.62rem] font-bold tracking-[0.14em] text-brand">COURSE PLANNING, CONNECTED</p>
        <h1 className="mt-2 text-3xl font-bold">Welcome to Syllo.</h1>
        <p className="mt-2 text-sm text-ink-muted">
          Sign in with your Lehigh account and your academic information will be ready when you arrive.
        </p>
        <Button size="lg" className="mt-7 h-11 w-full text-base" onClick={signIn}>
          Sign in with Lehigh SSO
        </Button>
        <ul className="my-6 space-y-2.5 border-y border-grid py-4 text-left text-sm" aria-label="Loaded after you sign in">
          {["Student profile and program", "Completed courses and prerequisites", "Degree progress and requirements"].map((item) => (
            <li key={item} className="flex items-center gap-2">
              <Check className="size-4 text-brand" aria-hidden /> {item}
            </li>
          ))}
        </ul>
        <p className="text-[0.7rem] leading-relaxed text-ink-muted">
          Prototype sign-in: no Lehigh credentials are asked for or sent. Student hackathon project, not affiliated with Lehigh University.
        </p>
      </section>
    </main>
  );
}
