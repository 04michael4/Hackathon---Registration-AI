"use client";

import { useState } from "react";
import { useSyllo } from "@/context/SylloContext";
import { catalog } from "@/lib/mock-data";
import { credits } from "@/lib/planner";

export function SavedSchedules() {
  const { state, navigate, update, undo, canUndo, showToast } = useSyllo();
  const [renaming, setRenaming] = useState<string | null>(null);
  const [name, setName] = useState("");
  const open = (id: string) => {
    const saved = state.saved.find((item) => item.id === id);
    if (!saved) return;
    update(`Open ${saved.name}`, (draft) => { draft.planner = [...saved.courses]; draft.preferences = { ...draft.preferences, ...saved.preferences }; });
    navigate("planner");
    showToast("Saved schedule opened", saved.name);
  };
  return (
    <section className="screen saved-screen active react-screen"><div className="saved-shell">
      <div className="saved-heading"><div><span className="features-kicker">SAVED SCHEDULES</span><h1>Your plans, kept together.</h1><p>Save options from the semester planner and reopen them here.</p></div><div className="syllo-inline-actions">{canUndo && <button className="button quiet" onClick={undo}>↶ Undo</button>}<button className="button primary" onClick={() => navigate("planner")}>Plan next semester →</button></div></div>
      <div className="saved-list">
        {state.saved.length ? state.saved.map((item) => (
          <article className="saved-card" key={item.id}><div className="saved-card-kicker"><span>SPRING 2027</span><button onClick={() => update("Delete saved schedule", (draft) => { draft.saved = draft.saved.filter((saved) => saved.id !== item.id); })} aria-label={`Delete ${item.name}`}>×</button></div><h2>{item.name}</h2><p>{item.courses.map((id) => catalog[id]?.code).filter(Boolean).join(" · ") || "Empty schedule"}</p><div className="saved-card-meta"><strong>{credits(item.courses)} credits · {item.courses.length} courses</strong><time>{new Date(item.savedAt).toLocaleString("en-US", { month: "short", day: "numeric", hour: "numeric", minute: "2-digit" })}</time></div><button className="button primary" onClick={() => open(item.id)}>Open schedule →</button><button className="saved-rename" onClick={() => { setRenaming(item.id); setName(item.name); }}>Rename</button></article>
        )) : <div className="saved-empty"><span>↗</span><h2>A good week is worth saving.</h2><p>Build your schedule, choose Save schedule, and find it here.</p><button className="button primary" onClick={() => navigate("planner")}>Build your first plan →</button></div>}
      </div>
      {renaming && <div className="syllo-dialog-backdrop" role="presentation"><form className="syllo-dialog-card" onSubmit={(event) => { event.preventDefault(); const clean = name.trim(); if (!clean) return; update("Rename saved schedule", (draft) => { const item = draft.saved.find((saved) => saved.id === renaming); if (item) item.name = clean.slice(0, 80); }); setRenaming(null); }}><h2>Rename schedule</h2><label>Schedule name<input autoFocus maxLength={80} value={name} onChange={(event) => setName(event.target.value)} required /></label><div className="syllo-modal-actions"><button type="button" className="button quiet" onClick={() => setRenaming(null)}>Cancel</button><button className="button primary" type="submit">Save name</button></div></form></div>}
    </div></section>
  );
}
