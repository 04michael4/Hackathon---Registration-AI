import type { Day } from "./api/types";
import type { RequirementKind } from "./demo/program";

export const WEEKDAYS: Day[] = ["MONDAY", "TUESDAY", "WEDNESDAY", "THURSDAY", "FRIDAY"];
export const DAY_SHORT: Record<Day, string> = {
  MONDAY: "Mon", TUESDAY: "Tue", WEDNESDAY: "Wed", THURSDAY: "Thu", FRIDAY: "Fri", SATURDAY: "Sat", SUNDAY: "Sun",
};
export const DAY_LETTER: Record<Day, string> = {
  MONDAY: "M", TUESDAY: "T", WEDNESDAY: "W", THURSDAY: "R", FRIDAY: "F", SATURDAY: "S", SUNDAY: "U",
};

/** Calendar range from the spec: 8am to 10pm. */
export const DAY_START = 8 * 60;
export const DAY_END = 22 * 60;

export function minutes(hhmm: string): number {
  const [h, m] = hhmm.split(":").map(Number);
  return h * 60 + m;
}

/** "13:05" -> "1:05pm" */
export function clock(hhmm: string): string {
  const total = minutes(hhmm);
  const h = Math.floor(total / 60);
  const m = total % 60;
  const suffix = h >= 12 ? "pm" : "am";
  const h12 = h % 12 === 0 ? 12 : h % 12;
  return m === 0 ? `${h12}${suffix}` : `${h12}:${String(m).padStart(2, "0")}${suffix}`;
}

export function dayPattern(days: Day[]): string {
  return WEEKDAYS.filter((d) => days.includes(d)).map((d) => DAY_LETTER[d]).join("");
}

export const KIND_LABEL: Record<RequirementKind, string> = {
  core: "Major core",
  gened: "Gen ed",
  elective: "Elective",
  free: "Free elective",
};

/** CSS color variables per requirement kind (the index-tab color). */
export const KIND_COLOR: Record<RequirementKind, string> = {
  core: "var(--req-core)",
  gened: "var(--req-gened)",
  elective: "var(--req-elective)",
  free: "var(--req-free)",
};
