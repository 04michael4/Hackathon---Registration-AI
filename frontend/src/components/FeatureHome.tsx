"use client";

import { useSyllo } from "@/context/SylloContext";
import type { Screen } from "@/lib/types";

const features: { title: string; copy: string; label: string; screen: Screen }[] = [
  { title: "Semester Planner", copy: "Build your schedule manually, then use intelligent suggestions when you want help.", label: "Open Semester Planner", screen: "planner" },
  { title: "Degree Planner", copy: "Plan your courses year by year, from your first semester to graduation.", label: "Open Degree Planner", screen: "requirements" },
  { title: "Academic Advisor Portal", copy: "Share your plan, receive feedback, and make changes with your advisor.", label: "Open Advisor Portal", screen: "advisor" },
  { title: "Saved schedules", copy: "Keep options and return to the schedule that fits.", label: "See saved schedules", screen: "saved" },
];

export function FeatureHome() {
  const { navigate } = useSyllo();
  return (
    <section className="screen features-screen active react-screen">
      <div className="features-shell">
        <div className="features-header"><h1>What would you like to do?</h1></div>
        <div className="features-grid">
          {features.map((feature) => (
            <article className="feature-card" key={feature.screen}>
              <h2>{feature.title}</h2><p>{feature.copy}</p>
              <button className="feature-link" onClick={() => navigate(feature.screen)}>{feature.label} <span>→</span></button>
            </article>
          ))}
        </div>
      </div>
    </section>
  );
}
