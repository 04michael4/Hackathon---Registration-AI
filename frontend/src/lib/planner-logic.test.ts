import fs from "node:fs";
import { createRequire } from "node:module";
import path from "node:path";
import vm from "node:vm";

import { describe, expect, it } from "vitest";

// The browser runtime's own rule file, loaded as CommonJS (it is a classic script in the page).
type Course = { code: string; name: string; section?: string; rating?: number | null; difficulty?: string | null; alternatives?: unknown[]; [key: string]: unknown };
type ClassType = "planned" | "recommended";
type Section = { section: string; instructor: string | null; status: string | null; meetings: { component: string; days: string; time: string }[] };
type Block = { key: string; section: string; component: string; day: string; start: number; end: number };
type Preview = { courseId: string } | null;
interface PlannerLogic {
  codeKey(value: string): string | null;
  searchScore(course: Course, query: string): number;
  semesterSearch(catalog: Record<string, Course>, query: string, options?: { electivesOnly?: boolean; isEligible?: (id: string) => boolean }): string[];
  CLASS_TYPES: Record<ClassType, { letter: string; label: string }>;
  courseList(planner: string[], recommended: string[]): { id: string; type: ClassType; alsoRecommended: boolean }[];
  ratingTier(rating: unknown): { tier: string; label: string };
  professorRating(source: Course | Section): { name: string | null; quality: number | null; difficulty: number | null; tier: string };
  alternativeSections(course: Course): Section[];
  previewBlocks(course: Course, sections: Section[], weekdays: string[]): Block[];
  assignLanes(blocks: { key: string; day: string; start: number; end: number }[]): Record<string, { lane: number; lanes: number }>;
  nextSectionPreview(current: Preview, action: { type: "toggle"; courseId: string } | { type: "clear" } | { type: "sync"; plannerIds: string[]; active: boolean }): Preview;
}
const require = createRequire(import.meta.url);
const logic = require("../../public/reference/planner-logic.js") as PlannerLogic;

interface SylloStore {
  catalog: Record<string, Course>;
  state: { planner: string[]; saved: unknown[] };
  recommendations(): string[];
  change(label: string, mutator: (state: { planner: string[] }) => void): void;
  canUndo: boolean;
}
/** Runs the real demo data store (public/reference/data.js) in an isolated context with fake storage. */
function loadDemoStore(): { S: SylloStore; storage: Map<string, string> } {
  const storage = new Map<string, string>();
  const window: { Syllo?: SylloStore } = {};
  const context = vm.createContext({
    window,
    crypto: globalThis.crypto,
    localStorage: { getItem: (k: string) => storage.get(k) ?? null, setItem: (k: string, v: string) => void storage.set(k, v) },
  });
  vm.runInContext(fs.readFileSync(path.join(process.cwd(), "public", "reference", "data.js"), "utf8"), context);
  return { S: window.Syllo as SylloStore, storage };
}

const WEEKDAYS = ["Mon", "Tue", "Wed", "Thu", "Fri"];

describe("forgiving course-code search", () => {
  const { S } = loadDemoStore();
  const cse017 = S.catalog.cse017;

  it.each(["CSE 017", "cse 017", "CSE017", "cse017", "CSE 17", "cse17", "  Cse   017 "])("finds CSE 017 from %j as an exact code match", (query) => {
    expect(logic.searchScore(cse017, query)).toBe(3);
  });

  it("normalizes case, spaces and leading zeros the same way for every course", () => {
    expect(logic.codeKey("CSE 007")).toBe("cse7");
    expect(logic.codeKey("cse7")).toBe(logic.codeKey("CSE 007"));
    expect(logic.codeKey("MATH 022")).toBe(logic.codeKey("math22"));
    expect(logic.codeKey("PHIL 114")).toBe("phil114");
    expect(logic.codeKey("data structures")).toBeNull();
  });

  it("ranks the exact code above other codes it prefixes", () => {
    expect(logic.searchScore(S.catalog.cse109, "cse1")).toBe(2);
    expect(logic.searchScore(S.catalog.cse017, "cse 17")).toBeGreaterThan(logic.searchScore(S.catalog.cse109, "cse 1"));
  });

  it("doesn't match a different course number", () => {
    expect(logic.searchScore(cse017, "CSE 018")).toBe(0);
    expect(logic.searchScore(cse017, "cse 109")).toBe(0);
    expect(logic.searchScore(S.catalog.math022, "cse 022")).toBe(0);
  });

  it("keeps title, keyword and requirement search", () => {
    const hits = (query: string) => Object.entries(S.catalog).filter(([, c]) => logic.searchScore(c, query) > 0).map(([id]) => id);
    expect(hits("Data Structures")).toEqual(expect.arrayContaining(["cse017", "cse241"]));
    expect(hits("algorithms")).toContain("cse241");
    expect(hits("ethics")).toEqual(expect.arrayContaining(["phil114", "phil120"]));
    expect(hits("minor")).toEqual(expect.arrayContaining(["math231", "ds210"]));
    expect(hits("zzz-not-a-course")).toEqual([]);
    expect(hits("").length).toBe(Object.keys(S.catalog).length);
  });
});

describe("unified semester course and interest search", () => {
  const { S } = loadDemoStore();

  it("keeps forgiving course-code and title matches in the same search", () => {
    expect(logic.semesterSearch(S.catalog, "cse17")[0]).toBe("cse017");
    expect(logic.semesterSearch(S.catalog, "data structures")).toEqual(expect.arrayContaining(["cse017", "cse241"]));
  });

  it("retains interest aliases and partial related-interest matches", () => {
    expect(logic.semesterSearch(S.catalog, "finance")).toEqual(["eco001", "eco105", "eco146"]);
    expect(logic.semesterSearch(S.catalog, "machine learning")).toEqual(expect.arrayContaining(["ds210", "math231"]));
    expect(logic.semesterSearch(S.catalog, "security")).toEqual(expect.arrayContaining(["cse109", "cse252"]));
    expect(logic.semesterSearch(S.catalog, "finance astronomy")).toContain("eco001");
  });

  it("ranks direct titles before related interests and related electives before requirements", () => {
    const catalog = {
      required: { code: "ZZ 100", name: "Economic policy", required: true },
      elective: { code: "ZZ 200", name: "Economic models", required: false },
      title: { code: "ZZ 300", name: "Finance", required: true },
    };
    expect(logic.semesterSearch(catalog, "finance")).toEqual(["title", "elective", "required"]);
  });

  it("retains elective filtering without excluding courses with unmet prerequisites", () => {
    expect(logic.semesterSearch(S.catalog, "", { electivesOnly: true, isEligible: () => false })).toContain("ds310");
    expect(logic.semesterSearch(S.catalog, "", { electivesOnly: true }).every(id => !S.catalog[id].required)).toBe(true);
    expect(logic.semesterSearch(S.catalog, "cse17", { electivesOnly: true })).toEqual([]);
  });

  it("keeps eligible courses first when match quality is the same", () => {
    const catalog = { a: { code: "TEST 001", name: "Sample" }, b: { code: "TEST 002", name: "Sample" } };
    expect(logic.semesterSearch(catalog, "Sample", { isEligible: id => id === "b" })).toEqual(["b", "a"]);
  });

  it("handles empty and unknown queries without changing the catalog", () => {
    const before = JSON.stringify(S.catalog);
    expect(logic.semesterSearch(S.catalog, "").length).toBe(Object.keys(S.catalog).length);
    expect(logic.semesterSearch(S.catalog, "zzzznotacourse")).toEqual([]);
    expect(JSON.stringify(S.catalog)).toBe(before);
  });
});

describe("merged course list (R = recommended, P = planned)", () => {
  it("keeps each course's existing class type", () => {
    const { S } = loadDemoStore();
    const list = logic.courseList(S.state.planner, S.recommendations());
    expect(list.map((i) => `${logic.CLASS_TYPES[i.type].letter}:${i.id}`)).toEqual(["P:cse241", "P:math231", "R:ds210", "R:phil114", "R:cse109"]);
    expect(new Set(list.map((i) => i.id)).size).toBe(list.length);
    expect(list.find((i) => i.id === "cse241")?.alsoRecommended).toBe(true);
  });

  it("matches the old separate lists exactly: planned = schedule, recommended = recommendations not on it", () => {
    const { S } = loadDemoStore();
    const list = logic.courseList(S.state.planner, S.recommendations());
    expect(list.filter((i) => i.type === "planned").map((i) => i.id)).toEqual(S.state.planner);
    expect(list.filter((i) => i.type === "recommended").map((i) => i.id)).toEqual(S.recommendations().filter((id) => !S.state.planner.includes(id)));
  });

  it("moves a course from R to P only when it is actually added", () => {
    const { S } = loadDemoStore();
    const typeOf = (id: string) => logic.courseList(S.state.planner, S.recommendations()).find((i) => i.id === id)?.type;
    expect(typeOf("ds210")).toBe("recommended");
    S.change("Add DS 210", (state) => { state.planner = [...state.planner, "ds210"]; });
    expect(typeOf("ds210")).toBe("planned");
  });

  it("labels class types in full for assistive technology", () => {
    expect(logic.CLASS_TYPES.planned).toEqual({ letter: "P", label: "Planned class" });
    expect(logic.CLASS_TYPES.recommended).toEqual({ letter: "R", label: "Recommended class" });
  });
});

describe("professor rating", () => {
  it("uses rating tiers, not course levels", () => {
    expect(logic.ratingTier(4.8).tier).toBe("excellent");
    expect(logic.ratingTier(4.5).tier).toBe("excellent");
    expect(logic.ratingTier(4.3).tier).toBe("good");
    expect(logic.ratingTier(3.8).tier).toBe("mixed");
    expect(logic.ratingTier(2.9).tier).toBe("low");
  });

  it("uses a neutral state when there is no rating", () => {
    for (const missing of [null, undefined, "", "Not provided"]) expect(logic.ratingTier(missing).tier).toBe("none");
  });

  it("reads professor, quality and difficulty from the demo data", () => {
    const { S } = loadDemoStore();
    expect(logic.professorRating(S.catalog.cse241)).toMatchObject({ name: "Prof. Maya Chen", quality: 4.6, difficulty: 3.7, tier: "excellent" });
    expect(logic.professorRating(S.catalog.cse398)).toMatchObject({ quality: null, difficulty: null, tier: "none" });
  });
});

describe("other-section previews", () => {
  it("lists other sections of the term, never the scheduled one, with every linked meeting", () => {
    const { S } = loadDemoStore();
    const sections = logic.alternativeSections(S.catalog.cse241);
    expect(sections.map((s) => s.section)).toEqual(["011", "012"]);
    expect(sections.every((s) => s.section !== "010")).toBe(true);
    expect(sections[0].meetings.map((m) => m.component)).toEqual(["Lecture", "Lab"]);
    const blocks = logic.previewBlocks(S.catalog.cse241, sections, WEEKDAYS);
    expect(blocks.map((b) => `${b.section} ${b.component} ${b.day} ${b.start}-${b.end}`)).toEqual([
      "011 Lecture Tue 790-865", "011 Lecture Thu 790-865", "011 Lab Fri 540-650",
      "012 Lecture Mon 875-950", "012 Lecture Wed 875-950", "012 Lab Fri 790-900",
    ]);
  });

  it("drops the scheduled section even if the data lists it as an alternative", () => {
    const course = { code: "CSE 241", name: "x", section: "010 · Prof. Maya Chen", alternatives: [{ section: "010", meetings: [] }, { section: "011", meetings: [] }] };
    expect(logic.alternativeSections(course).map((s) => s.section)).toEqual(["011"]);
  });

  it("returns nothing for a course with a single section (empty-state message)", () => {
    const { S } = loadDemoStore();
    expect(logic.alternativeSections(S.catalog.cse109)).toEqual([]);
  });

  it("puts overlapping blocks side by side, deterministically", () => {
    const blocks = [
      { key: "b", day: "Mon", start: 600, end: 700 },
      { key: "a", day: "Mon", start: 600, end: 700 },
      { key: "c", day: "Mon", start: 650, end: 760 },
      { key: "d", day: "Mon", start: 800, end: 860 },
      { key: "e", day: "Tue", start: 600, end: 700 },
    ];
    const lanes = logic.assignLanes(blocks);
    expect(lanes).toEqual({ a: { lane: 0, lanes: 3 }, b: { lane: 1, lanes: 3 }, c: { lane: 2, lanes: 3 }, d: { lane: 0, lanes: 1 }, e: { lane: 0, lanes: 1 } });
    expect(logic.assignLanes([...blocks].reverse())).toEqual(lanes);
  });

  it("shows, toggles off, replaces and clears", () => {
    let preview: Preview = null;
    preview = logic.nextSectionPreview(preview, { type: "toggle", courseId: "cse241" });
    expect(preview).toEqual({ courseId: "cse241" });
    preview = logic.nextSectionPreview(preview, { type: "toggle", courseId: "math231" });
    expect(preview).toEqual({ courseId: "math231" });
    preview = logic.nextSectionPreview(preview, { type: "toggle", courseId: "math231" });
    expect(preview).toBeNull();
    preview = logic.nextSectionPreview({ courseId: "cse241" }, { type: "clear" });
    expect(preview).toBeNull();
  });

  it("clears stale previews when the course leaves the schedule or the planner isn't shown", () => {
    const current = { courseId: "cse241" };
    expect(logic.nextSectionPreview(current, { type: "sync", plannerIds: ["cse241", "math231"], active: true })).toBe(current);
    expect(logic.nextSectionPreview(current, { type: "sync", plannerIds: ["math231"], active: true })).toBeNull();
    expect(logic.nextSectionPreview(current, { type: "sync", plannerIds: ["cse241"], active: false })).toBeNull();
  });

  it("never changes the plan, saved schedules, storage or undo history", () => {
    const { S, storage } = loadDemoStore();
    const before = JSON.stringify({ state: S.state, catalog: S.catalog, storage: [...storage], canUndo: S.canUndo });
    let preview: Preview = null;
    for (const id of S.state.planner) {
      preview = logic.nextSectionPreview(preview, { type: "toggle", courseId: id });
      const sections = logic.alternativeSections(S.catalog[id]);
      logic.assignLanes(logic.previewBlocks(S.catalog[id], sections, WEEKDAYS));
      sections.forEach((s) => logic.professorRating(s));
    }
    logic.nextSectionPreview(preview, { type: "clear" });
    expect(JSON.stringify({ state: S.state, catalog: S.catalog, storage: [...storage], canUndo: S.canUndo })).toBe(before);
    expect(S.canUndo).toBe(false);
  });
});
