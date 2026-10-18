"use client";

import Image from "next/image";
import { useSyllo } from "@/context/SylloContext";
import type { Screen } from "@/lib/types";

const routes: { screen: Screen; label: string }[] = [
  { screen: "planner", label: "Plan next semester" },
  { screen: "requirements", label: "Plan your degree" },
  { screen: "advisor", label: "Advisor portal" },
  { screen: "saved", label: "Saved schedules" },
];

export function ProductNav() {
  const { state, screen, navigate, update, showToast } = useSyllo();
  const unread = state.review.notifications.filter((notice) => !notice.read).length;
  const switchRole = () => {
    const next = state.role === "student" ? "advisor" : "student";
    update("Switch demo role", (draft) => { draft.role = next; });
    navigate(next === "advisor" ? "advisor" : "features");
    showToast(`${next === "advisor" ? "Advisor" : "Student"} demo`, "Both roles use this browser’s local demo plan.");
  };

  return (
    <nav className="product-nav" aria-label="Planning sections">
      <div className={`product-nav-tabs ${screen === "features" ? "hidden" : ""}`}>
        {routes.map((route) => (
          <button key={route.screen} className={screen === route.screen ? "active" : ""} aria-current={screen === route.screen ? "page" : undefined} onClick={() => navigate(route.screen)}>
            {route.label}{route.screen === "advisor" && state.role === "student" && unread > 0 ? <span className="advisor-alert">{unread}</span> : null}
          </button>
        ))}
      </div>
      <button className="product-role" onClick={switchRole}>{state.role === "advisor" ? "Advisor demo ⇄" : "Student demo ⇄"}</button>
      <button className="product-brand" onClick={() => navigate("features")} aria-label="Syllo home"><span className="syllo-lockup"><Image className="syllo-lockup-mark" src="/assets/syllo-mark.svg" alt="" width={28} height={28} /><span className="syllo-wordmark">Syllo</span></span></button>
    </nav>
  );
}
