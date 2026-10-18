"use client";

import { AdvisorPortal } from "./AdvisorPortal";
import { CourseDrawer } from "./CourseDrawer";
import { DegreePlanner } from "./DegreePlanner";
import { FeatureHome } from "./FeatureHome";
import { LoginScreen } from "./LoginScreen";
import { ProductNav } from "./ProductNav";
import { SavedSchedules } from "./SavedSchedules";
import { SemesterPlanner } from "./SemesterPlanner";
import { SylloProvider, useSyllo } from "@/context/SylloContext";

function AppContent() {
  const { hydrated, screen, toast } = useSyllo();
  if (!hydrated) return <main className="syllo-loading">Loading your demo plan…</main>;

  return (
    <div className="app-shell">
      {screen !== "login" && <ProductNav />}
      <main id="main-content">
        {screen === "login" && <LoginScreen />}
        {screen === "features" && <FeatureHome />}
        {screen === "planner" && <SemesterPlanner />}
        {screen === "requirements" && <DegreePlanner />}
        {screen === "advisor" && <AdvisorPortal />}
        {screen === "saved" && <SavedSchedules />}
      </main>
      <CourseDrawer />
      <div className={`toast ${toast ? "show" : ""}`} role="status" aria-live="polite">
        <span>✓</span>
        <div><strong>{toast?.title ?? "Plan saved"}</strong><small>{toast?.detail ?? ""}</small></div>
      </div>
    </div>
  );
}

export function SylloApp() {
  return <SylloProvider><AppContent /></SylloProvider>;
}
