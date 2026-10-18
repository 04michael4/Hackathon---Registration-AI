import fs from "node:fs";
import { createRequire } from "node:module";
import path from "node:path";
import vm from "node:vm";

import { describe, expect, it } from "vitest";

type Course = { code: string; name: string; credits: number; required: boolean; requirement: string; terms: string[]; prereqs: string[]; [key: string]: unknown };
type Program = { name: string; kind: string; courses: string[] };
type Category = "major" | "minor" | "gened" | "elective";
type Rect = { left: number; top: number; right: number; bottom: number };
interface DegreeLogic {
  CATEGORIES: Record<Category, { label: string; name: string }>;
  courseCategory(id: string, course: Course, programCourseIds: string[]): Category;
  creditProgress(ids: string[], catalog: Record<string, Course>, where: (id: string) => string): { completedCredits: number; plannedCredits: number; stagedCredits: number; totalCredits: number };
  degreeSearch(catalog: Record<string, Course>, programs: Record<string, Program>, query: string, tags: string[], programCourseIds: string[]): { programs: string[]; courses: string[] };
  rectsIntersect(a: Rect, b: Rect): boolean;
  rectFromPoints(x1: number, y1: number, x2: number, y2: number): Rect;
}
type MoveResult = { ok: boolean; reasons: string[]; warnings?: string[]; moved: string[] };
interface SylloStore {
  catalog: Record<string, Course>;
  programs: Record<string, Program>;
  requirementGroups: { id: string; credits: number; courses: string[] }[];
  completed: Set<string>;
  state: { degree: Record<string, string[]>; bucket: string[]; minors: string[]; electives: Record<string, string | null> };
  maxTermCredits: number;
  termCredits(termId: string): number;
  electiveReasons(id: string, slotId: string): string[];
  moveDegree(id: string, from: string, to: string): { ok: boolean; reasons: string[] };
  locate(id: string): string | null;
  moveCourses(ids: string[], to: string): MoveResult;
  removeCourses(ids: string[]): string[];
  stageCourses(ids: string[]): string[];
  addProgram(id: string): { added: boolean; staged: string[] };
  change(label: string, mutator: (state: SylloStore["state"]) => void): void;
  undo(): boolean;
  redo(): boolean;
  canUndo: boolean;
  canRedo: boolean;
  degreeAudit(): { valid: boolean; issues: string[] };
}

const require = createRequire(import.meta.url);
const logic = require("../../public/reference/planner-logic.js") as DegreeLogic;

function loadDemoStore(): SylloStore {
  const storage = new Map<string, string>();
  const window: { Syllo?: SylloStore } = {};
  const context = vm.createContext({
    window,
    crypto: globalThis.crypto,
    localStorage: { getItem: (k: string) => storage.get(k) ?? null, setItem: (k: string, v: string) => void storage.set(k, v) },
  });
  vm.runInContext(fs.readFileSync(path.join(process.cwd(), "public", "reference", "data.js"), "utf8"), context);
  const S = window.Syllo as SylloStore;
  Object.assign(S, { showToast: () => {} });
  return S;
}
const declaredCourses = (S: SylloStore) => S.state.minors.flatMap((id) => S.programs[id].courses);

describe("course categories (Lehigh colors)", () => {
  it("sorts every demo course into major, minor, GenEd or elective", () => {
    const S = loadDemoStore();
    const cat = (id: string) => logic.courseCategory(id, S.catalog[id], declaredCourses(S));
    expect(["cse241", "cse109", "cse399", "math022"].map(cat)).toEqual(["major", "major", "major", "major"]);
    expect(["math231", "ds210", "eco105"].map(cat)).toEqual(["minor", "minor", "minor"]);
    expect(cat("phil114")).toBe("gened");
    expect(["eco001", "art003", "phil120", "soc001"].map(cat)).toEqual(["elective", "elective", "elective", "elective"]);
  });

  it("colors a course as a minor course once that minor is declared", () => {
    const S = loadDemoStore();
    expect(logic.courseCategory("eco001", S.catalog.eco001, declaredCourses(S))).toBe("elective");
    S.addProgram("econ-minor");
    expect(logic.courseCategory("eco001", S.catalog.eco001, declaredCourses(S))).toBe("minor");
  });

  it("never colors by course level", () => {
    const S = loadDemoStore();
    // CSE 017 (a 0-level code) and CSE 399 (300-level) share the major color.
    expect(logic.courseCategory("cse017", S.catalog.cse017, [])).toBe(logic.courseCategory("cse399", S.catalog.cse399, []));
  });
});

describe("unified degree search", () => {
  const S = loadDemoStore();
  const search = (q: string, tags: string[] = []) => logic.degreeSearch(S.catalog, S.programs, q, tags, declaredCourses(S));

  it("returns nothing for an empty query with no tags", () => {
    expect(search("")).toEqual({ programs: [], courses: [] });
  });

  it("finds minors and concentrations by name, and their courses", () => {
    expect(search("data science minor")).toEqual({ programs: ["ds-minor"], courses: ["ds210", "math231"] });
    expect(search("concentration").programs).toEqual(["ethics-tech"]);
  });

  it("Course code tag matches codes only, forgivingly", () => {
    expect(search("cse17", ["code"]).courses).toEqual(["cse017"]);
    expect(search("CSE 017", ["code"]).courses).toEqual(["cse017"]);
    expect(search("data", ["code"]).courses).toEqual([]);
    expect(search("cse17", ["code"]).programs).toEqual([]);
  });

  it("Interest tag matches interests and related words", () => {
    expect(search("finance", ["interest"]).courses).toEqual(expect.arrayContaining(["eco001", "eco105", "eco146"]));
    expect(search("finance").courses).toEqual([]);
  });

  it("category tags restrict results, several tags mean any of them", () => {
    expect(search("", ["gened"]).courses).toEqual(["phil114"]);
    const minorOrGened = search("", ["minor", "gened"]).courses;
    expect(minorOrGened).toEqual(expect.arrayContaining(["phil114", "math231", "ds210"]));
    expect(minorOrGened).not.toContain("cse241");
    expect(search("", ["minor"]).programs).toEqual(["ds-minor", "econ-minor", "ethics-tech"]);
    const majorSystems = search("systems", ["major"]).courses;
    expect(majorSystems[0]).toBe("cse109");
    expect(majorSystems.every((id) => logic.courseCategory(id, S.catalog[id], declaredCourses(S)) === "major")).toBe(true);
    expect(majorSystems).not.toContain("ds210"); // "Data Systems" is a minor course
  });
});

describe("credit trackers", () => {
  it("adds up completed, planned and staged credits", () => {
    const S = loadDemoStore();
    const where = (id: string) => (S.completed.has(id) ? "completed" : S.locate(id) === "bucket" ? "staged" : S.locate(id) ? "planned" : "none");
    const humanities = S.requirementGroups.find((g) => g.id === "humanities")!;
    expect(humanities.credits).toBe(8);
    expect(logic.creditProgress(humanities.courses, S.catalog, where)).toEqual({ completedCredits: 0, plannedCredits: 3, stagedCredits: 0, totalCredits: 9 });
    S.stageCourses(["phil120"]);
    expect(logic.creditProgress(humanities.courses, S.catalog, where)).toMatchObject({ plannedCredits: 3, stagedCredits: 3 });
  });
});

describe("staging bucket and minors", () => {
  it("adding a minor stages its courses that aren't in the plan", () => {
    const S = loadDemoStore();
    expect(S.addProgram("econ-minor")).toEqual({ added: true, staged: ["eco001", "eco105", "eco146"] });
    expect(S.state.bucket).toEqual(["eco001", "eco105", "eco146"]);
    expect(S.state.minors).toContain("econ-minor");
    // The Data Science minor is already declared and fully planned: nothing to stage.
    expect(S.addProgram("ds-minor")).toEqual({ added: false, staged: [] });
  });

  it("staged courses are flagged until they are placed", () => {
    const S = loadDemoStore();
    S.addProgram("econ-minor");
    expect(S.degreeAudit().issues).toEqual(expect.arrayContaining(["ECO 105 (Economics minor) is in the staging area; place it in a semester."]));
  });

  it("does not stage completed or already-planned courses", () => {
    const S = loadDemoStore();
    expect(S.stageCourses(["cse007", "cse241", "soc001", "soc001"])).toEqual(["soc001"]);
  });
});

describe("bulk moves and removal", () => {
  it("moves several courses into a semester in one undo step", () => {
    const S = loadDemoStore();
    S.stageCourses(["soc001", "psy001"]);
    const result = S.moveCourses(["soc001", "psy001"], "fall-2027");
    expect(result).toMatchObject({ ok: true, moved: ["soc001", "psy001"] });
    expect(S.locate("soc001")).toBe("fall-2027");
    S.undo();
    expect([S.locate("soc001"), S.locate("psy001")]).toEqual(["bucket", "bucket"]);
  });

  it("blocks a semester move that breaks prerequisite order or term offerings, and changes nothing", () => {
    const S = loadDemoStore();
    S.addProgram("econ-minor");
    const before = JSON.stringify(S.state);
    expect(S.moveCourses(["eco001", "eco105"], "fall-2027").ok).toBe(false);
    expect(S.moveCourses(["eco146"], "fall-2027").reasons).toContain("ECO 146 is not offered in Fall 2027.");
    expect(JSON.stringify(S.state)).toBe(before);
  });

  it("allows unassigning into the bucket and reports the order problems it creates", () => {
    const S = loadDemoStore();
    const result = S.moveCourses(["cse241"], "bucket");
    expect(result.ok).toBe(true);
    expect(result.warnings).toEqual(expect.arrayContaining(["CSE 202 must be in a later semester than CSE 241."]));
  });

  it("never moves or removes completed coursework", () => {
    const S = loadDemoStore();
    expect(S.moveCourses(["cse007"], "fall-2027").ok).toBe(false);
    expect(S.removeCourses(["cse007", "cse017"])).toEqual([]);
  });

  it("removes several courses at once", () => {
    const S = loadDemoStore();
    expect(S.removeCourses(["cse202", "cse303"])).toEqual(["cse202", "cse303"]);
    expect([S.locate("cse202"), S.locate("cse303")]).toEqual([null, null]);
  });
});

describe("undo and redo", () => {
  it("redo re-applies what undo reverted, and a new change clears redo", () => {
    const S = loadDemoStore();
    S.moveCourses(["cse202"], "spring-2028");
    S.undo();
    expect(S.locate("cse202")).toBe("fall-2027");
    expect(S.canRedo).toBe(true);
    S.redo();
    expect(S.locate("cse202")).toBe("spring-2028");
    S.undo();
    S.removeCourses(["cse303"]);
    expect(S.canRedo).toBe(false);
    expect(S.redo()).toBe(false);
  });
});

describe("marquee selection geometry", () => {
  it("normalizes a box dragged in any direction", () => {
    expect(logic.rectFromPoints(50, 80, 10, 20)).toEqual({ left: 10, top: 20, right: 50, bottom: 80 });
  });
  it("selects cards the box touches and skips the rest", () => {
    const box = logic.rectFromPoints(0, 0, 100, 100);
    expect(logic.rectsIntersect(box, { left: 90, top: 90, right: 150, bottom: 150 })).toBe(true);
    expect(logic.rectsIntersect(box, { left: 100, top: 0, right: 150, bottom: 50 })).toBe(false);
    expect(logic.rectsIntersect(box, { left: 200, top: 200, right: 250, bottom: 250 })).toBe(false);
  });
});

describe("free electives: any credits, at most 21 credits a semester", () => {
  // Spring 2028 starts with CSE 399 and PHIL 114 (6 credits) plus two empty elective slots.
  const fillSpring2028 = (S: SylloStore, ids: string[]) => S.change("fill", (state) => { state.degree["spring-2028"].push(...ids); });

  it("accepts electives of any credit amount", () => {
    const S = loadDemoStore();
    expect(S.maxTermCredits).toBe(21);
    for (const id of ["mus001", "art003", "eco001"]) expect(S.electiveReasons(id, "elective-a")).toEqual([]);
    expect([1, 3, 4]).toEqual(["mus001", "art003", "eco001"].map((id) => S.catalog[id].credits));
    expect(S.electiveReasons("mus001", "elective-b")).toEqual([]);
  });

  it("allows a semester to reach exactly 21 credits", () => {
    const S = loadDemoStore();
    fillSpring2028(S, ["soc001", "psy001", "phil120"]); // 6 + 11 = 17
    expect(S.termCredits("spring-2028")).toBe(17);
    expect(S.electiveReasons("eco001", "elective-a")).toEqual([]); // 17 + 4 = 21
  });

  it("blocks an elective that would push the semester past 21 credits", () => {
    const S = loadDemoStore();
    fillSpring2028(S, ["soc001", "psy001", "phil120", "mus001"]); // 18
    expect(S.electiveReasons("eco001", "elective-a")).toEqual(["Spring 2028 would have 22 credits; a semester can have at most 21."]);
    expect(S.electiveReasons("art003", "elective-a")).toEqual([]); // 18 + 3 = 21
  });

  it("counts a replaced elective out when checking the limit", () => {
    const S = loadDemoStore();
    fillSpring2028(S, ["soc001", "psy001", "phil120"]); // 17
    S.change("pick", (state) => { state.electives["elective-a"] = "art003"; }); // 20
    expect(S.electiveReasons("eco001", "elective-a")).toEqual([]); // swap 3 for 4: 21
    expect(S.electiveReasons("eco001", "elective-b")).toEqual(["Spring 2028 would have 24 credits; a semester can have at most 21."]);
  });

  it("no longer asks for a 4- or 3-credit course in the graduation check", () => {
    const S = loadDemoStore();
    S.change("pick", (state) => { state.electives["elective-a"] = "mus001"; state.electives["elective-b"] = "art003"; });
    const issues = S.degreeAudit().issues.join(" ");
    expect(issues).not.toMatch(/-credit elective|does not meet this elective slot/);
    // A smaller elective is allowed; the plan just needs more credits overall to reach 120.
    expect(issues).toMatch(/more credits are needed to reach the demo 120-credit requirement/);
  });

  it("applies the same 21-credit limit when moving courses into a semester", () => {
    const S = loadDemoStore();
    fillSpring2028(S, ["soc001", "psy001", "phil120", "mus001"]); // 18
    S.stageCourses(["eco001"]);
    expect(S.moveCourses(["eco001"], "spring-2028")).toMatchObject({ ok: false, reasons: ["Spring 2028 would have 22 credits; a semester can have at most 21."] });
    expect(S.locate("eco001")).toBe("bucket");
    // CSE 202 (3 credits) brings it to exactly 21, which is allowed…
    expect(S.moveDegree("cse202", "fall-2027", "spring-2028").ok).toBe(true);
    expect(S.termCredits("spring-2028")).toBe(21);
    // …but nothing more fits, even a 1-credit course.
    expect(S.moveDegree("cse252", "fall-2027", "spring-2028")).toEqual({ ok: false, reasons: ["Spring 2028 has 22 credits; a semester can have at most 21."] });
    expect(S.locate("cse252")).toBe("fall-2027");
  });

  it("flags any semester over 21 credits in the graduation check", () => {
    const S = loadDemoStore();
    fillSpring2028(S, ["soc001", "psy001", "phil120", "mus001", "eco001"]); // 22, forced past the checks
    expect(S.degreeAudit().issues).toContain("Spring 2028 has 22 credits; a semester can have at most 21.");
  });
});
