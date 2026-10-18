"use client";
import { DAYS, type FieldError } from "@/lib/api/types";
import type { PreferenceForm } from "@/lib/api/scheduleClient";

export function ApiPreferences({ value, onChange, errors }: Readonly<{ value: PreferenceForm; onChange: (value: PreferenceForm) => void; errors: FieldError[] }>) {
  const set = <K extends keyof PreferenceForm>(key: K, next: PreferenceForm[K]) => onChange({ ...value, [key]: next });
  const field = (key: keyof PreferenceForm, label: string, min: number, max: number) => <label>{label}<input name={key} type="number" min={min} max={max} step="1" value={String(value[key])} aria-invalid={errors.some(e => e.field === key)} onChange={e => set(key, e.target.value as PreferenceForm[typeof key])} />{errors.filter(e => e.field === key).map(e => <small className="api-field-error" key={e.message}>{e.message}</small>)}</label>;
  const days = (selected: typeof DAYS[number][], change: (days: typeof DAYS[number][]) => void) => <div className="api-day-options">{DAYS.map(day => <label key={day}><input type="checkbox" checked={selected.includes(day)} onChange={e => change(e.target.checked ? [...selected, day] : selected.filter(d => d !== day))} />{day.slice(0, 3)}</label>)}</div>;
  return <section className="api-preferences" aria-label="Scheduling preferences"><h2>Scheduling preferences</h2><div className="api-fields">
    {field("minCredits", "Minimum credits", 1, 21)}{field("maxCredits", "Maximum credits", 1, 21)}{field("maxResults", "Maximum alternatives", 1, 10)}
  </div><label className="api-check"><input name="includeClosedSections" type="checkbox" checked={value.includeClosedSections} onChange={e => set("includeClosedSections", e.target.checked)} />Include closed sections (planning only; seats are not guaranteed)</label>
    <details><summary>Time preferences and importance</summary><p>Weights range from 0 (ignore) to 5 (most important). Soft preferences rank feasible schedules.</p><div className="api-fields">
      <label>Earliest preferred start<input name="earliest" type="time" value={value.earliest} onChange={e => set("earliest", e.target.value)} /></label>{field("earliestWeight", "Start importance", 0, 5)}
      <label>Latest preferred end<input name="latest" type="time" value={value.latest} onChange={e => set("latest", e.target.value)} /></label>{field("latestWeight", "End importance", 0, 5)}
      {field("optionalWeight", "Optional courses importance", 0, 5)}{field("gapsWeight", "Minimize gaps importance", 0, 5)}{field("daysWeight", "Days off importance", 0, 5)}
    </div><fieldset><legend>Preferred days off</legend>{days(value.daysOff, next => set("daysOff", next))}</fieldset></details>
    <details><summary>Blocked times (hard constraints)</summary>{value.blockedTimes.map((block, index) => <fieldset key={index}><legend>Blocked time {index + 1}</legend>{days(block.days, next => set("blockedTimes", value.blockedTimes.map((b, i) => i === index ? { ...b, days: next } : b)))}<div className="api-fields">{(["start", "end"] as const).map(key => <label key={key}>{key === "start" ? "From" : "Until"}<input type="time" value={block[key]} onChange={e => set("blockedTimes", value.blockedTimes.map((b, i) => i === index ? { ...b, [key]: e.target.value } : b))} /></label>)}</div><button type="button" onClick={() => set("blockedTimes", value.blockedTimes.filter((_, i) => i !== index))}>Remove blocked time</button></fieldset>)}
      <button type="button" className="button" onClick={() => set("blockedTimes", [...value.blockedTimes, { days: ["MONDAY"], start: "12:00", end: "13:00" }])}>Add blocked time</button></details>
  </section>;
}
