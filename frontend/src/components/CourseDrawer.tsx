"use client";

import { useSelectedCourse, useSyllo } from "@/context/SylloContext";
import { catalog, completed, nextTermId } from "@/lib/mock-data";
import { eligibility } from "@/lib/planner";

function Row({ label, value }: Readonly<{ label: string; value: React.ReactNode }>) {
  return <div className="course-info-row"><span>{label}</span><strong>{value}</strong></div>;
}

export function CourseDrawer() {
  const course = useSelectedCourse();
  const { selectedCourse, setSelectedCourse } = useSyllo();
  if (!course || !selectedCourse) return null;
  const check = eligibility(selectedCourse, nextTermId);
  const prerequisites = course.prereqs.map((id) => `${catalog[id]?.code ?? id}${completed.has(id) ? " (completed)" : ""}`).join(", ") || "None in demo";
  const professor = course.section.includes("·") ? course.section.split("·").slice(1).join("·").trim() : "Not provided";
  return (
    <>
      <button className="drawer-backdrop" aria-label="Close course details" onClick={() => setSelectedCourse(null)} />
      <aside className="detail-drawer open" aria-label={`${course.code} course details`}>
        <div className="drawer-top"><button className="icon-button" onClick={() => setSelectedCourse(null)} aria-label="Close course details">×</button><span className="course-pill">{course.code}</span></div>
        <div className="drawer-content">
          <h2>{course.name}</h2><p className="subtitle">{course.code} · Demo catalog</p><p className="course-description">{course.description}</p>
          <section className="detail-section"><h3>Course & section</h3><Row label="Credits" value={course.credits} /><Row label="Requirement" value={course.requirement} /><Row label="Prerequisites" value={prerequisites} /><Row label="Terms offered" value={course.terms.join(" / ")} /><Row label="Section" value={course.section} /><Row label="Meetings" value={course.days && course.time ? `${course.days} · ${course.time}` : "Section time unavailable"} /><Row label="Next-semester eligibility" value={check.ok ? "Prerequisites met in demo record" : check.reasons.join(" ")} /></section>
          <section className="detail-section"><h3>Professor & workload</h3><Row label="Professor" value={professor} /><Row label="Professor rating" value={course.rating ? `${course.rating} / 5` : "Not provided"} /><Row label="Course difficulty" value={course.difficulty} /><Row label="Weekly workload" value={course.workload} /><p className="sample-note">Simulated course details, professors and ratings. No live registration or seat availability.</p></section>
        </div>
      </aside>
    </>
  );
}
