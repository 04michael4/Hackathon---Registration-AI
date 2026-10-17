// Demo-only academic data: a made-up degree program, transcript and people.
// Nothing here is real Lehigh data. Section times come from the backend; this file only knows
// what the backend doesn't model yet: prerequisites, requirements, offered terms and history.

export type Season = "FALL" | "SPRING";
export type RequirementKind = "core" | "gened" | "elective" | "free";

export interface TermInfo {
  id: string; // e.g. "S2027"
  label: string; // "Spring 2027"
  season: Season;
  apiTerm?: string; // the backend term id, when the catalog has data for it
}

export const TERMS: TermInfo[] = [
  { id: "F2025", label: "Fall 2025", season: "FALL" },
  { id: "S2026", label: "Spring 2026", season: "SPRING" },
  { id: "F2026", label: "Fall 2026", season: "FALL" },
  { id: "S2027", label: "Spring 2027", season: "SPRING", apiTerm: "2027-SPRING" },
  { id: "F2027", label: "Fall 2027", season: "FALL" },
  { id: "S2028", label: "Spring 2028", season: "SPRING" },
  { id: "F2028", label: "Fall 2028", season: "FALL" },
  { id: "S2029", label: "Spring 2029", season: "SPRING" },
];

/** The term in progress now: its courses show as "In progress", earlier ones as "Done". */
export const CURRENT_TERM = "F2026";
/** The term Tab 1 plans. */
export const NEXT_TERM = "S2027";
export const API_TERM = "2027-SPRING";
/** Mock registration opening for the countdown. */
export const REGISTRATION_OPENS = new Date("2026-11-09T07:00:00-05:00");

export interface CourseMeta {
  id: string;
  title: string;
  credits: number;
  prereqs: string[];
  offered: Season[];
}

const c = (id: string, title: string, credits: number, offered: Season[], prereqs: string[] = []): CourseMeta => ({
  id,
  title,
  credits,
  prereqs,
  offered,
});
const BOTH: Season[] = ["FALL", "SPRING"];

export const COURSES: Record<string, CourseMeta> = Object.fromEntries(
  [
    c("CSE-007", "Introduction to Programming", 4, BOTH),
    c("CSE-017", "Programming and Data Structures", 3, BOTH, ["CSE-007"]),
    c("CSE-109", "Systems Software", 3, BOTH, ["CSE-017"]),
    // CSE 140: CSE 017 plus Calculus I, as in the repo's hand-structured expression (data/catalog-research).
    c("CSE-140", "Foundations of Discrete Structures and Algorithms", 3, BOTH, ["CSE-017", "MATH-021"]),
    c("CSE-202", "Computer Organization and Architecture", 3, BOTH, ["CSE-109"]),
    c("CSE-216", "Software Engineering", 3, ["SPRING"], ["CSE-140"]),
    c("CSE-262", "Programming Languages", 3, ["FALL", "SPRING"], ["CSE-109", "CSE-140"]),
    c("CSE-303", "Operating System Design", 3, ["FALL"], ["CSE-202"]),
    // Assumption (no collected prerequisite data for CSE 340): CSE 109 and CSE 140.
    c("CSE-340", "Design and Analysis of Algorithms", 3, ["SPRING"], ["CSE-109", "CSE-140"]),
    c("CSE-241", "Database Systems and Applications", 3, BOTH, ["CSE-017"]),
    c("CSE-318", "Computer Networks", 3, ["FALL"], ["CSE-109"]),
    c("CSE-326", "Machine Learning", 3, ["SPRING"], ["CSE-140", "MATH-231"]),
    c("CSE-343", "Computer Security", 3, BOTH, ["CSE-109"]),
    c("FIN-125", "Introduction to Finance", 3, BOTH),
    c("MATH-021", "Calculus I", 4, BOTH),
    c("MATH-022", "Calculus II", 4, BOTH, ["MATH-021"]),
    c("MATH-205", "Linear Methods", 3, BOTH, ["MATH-022"]),
    c("MATH-231", "Probability and Statistics", 3, BOTH, ["MATH-022"]),
    c("PHY-011", "Introductory Physics I", 4, BOTH, ["MATH-021"]),
    c("CHM-030", "Introduction to Chemical Principles", 4, BOTH),
    c("ENGL-001", "Composition and Literature", 3, BOTH),
    c("ENGL-002", "Research and Argument", 3, BOTH, ["ENGL-001"]),
    c("FYS-001", "First-Year Seminar", 3, ["FALL"]),
    c("PHIL-105", "Ethics", 3, ["SPRING"]),
    c("HIST-010", "Modern World History", 3, BOTH),
    c("ART-011", "Drawing I", 3, BOTH),
    c("ECO-001", "Principles of Economics", 4, BOTH),
    c("PSY-001", "Introduction to Psychology", 3, BOTH),
    c("SOC-001", "Introduction to Sociology", 3, BOTH),
    c("BIO-041", "General Biology", 4, BOTH),
    c("DSCI-001", "Data, Society and You", 3, BOTH),
  ].map((m) => [m.id, m]),
);

export interface Requirement {
  id: string;
  label: string;
  kind: RequirementKind;
  /** Courses that satisfy it. */
  options: string[];
  /** How many of the options are needed. */
  count: number;
}

export const PROGRAM = {
  name: "Computer Science, B.S.",
  note: "Demo requirements, simplified for the prototype",
  maxCreditsPerTerm: 18,
  requirements: [
    { id: "cse-core", label: "CSE core", kind: "core", count: 9,
      options: ["CSE-007", "CSE-017", "CSE-109", "CSE-140", "CSE-202", "CSE-216", "CSE-262", "CSE-303", "CSE-340"] },
    { id: "math", label: "Mathematics", kind: "core", count: 4, options: ["MATH-021", "MATH-022", "MATH-205", "MATH-231"] },
    { id: "science", label: "Lab science", kind: "core", count: 2, options: ["PHY-011", "CHM-030"] },
    { id: "writing", label: "Writing", kind: "gened", count: 2, options: ["ENGL-001", "ENGL-002"] },
    { id: "fys", label: "First-year seminar", kind: "gened", count: 1, options: ["FYS-001"] },
    { id: "hu", label: "Humanities", kind: "gened", count: 2, options: ["PHIL-105", "HIST-010", "ART-011"] },
    { id: "ss", label: "Social science", kind: "gened", count: 2, options: ["ECO-001", "PSY-001", "SOC-001"] },
    { id: "tech", label: "Technical elective", kind: "elective", count: 2, options: ["CSE-241", "CSE-318", "CSE-326", "CSE-343"] },
    { id: "free", label: "Free elective", kind: "free", count: 2, options: ["BIO-041", "DSCI-001", "CHM-030", "ECO-001", "ART-011", "FIN-125"] },
  ] satisfies Requirement[],
};

export type Transcript = Record<string, string[]>; // termId -> course ids

export interface StudentAccount {
  id: string;
  name: string;
  program: string;
  year: string;
  creditRange: [number, number];
  transcript: Transcript;
  /** Courses dropped with a W: shown in their term, earn no credit and satisfy nothing. */
  withdrawn?: Transcript;
  /** Terms already taken or in progress; they're locked in the degree grid. */
  lockedTerms: string[];
}

export const DEMO_STUDENT: StudentAccount = {
  id: "jordan",
  name: "Jordan Lee",
  program: PROGRAM.name,
  year: "Sophomore",
  creditRange: [12, 18],
  lockedTerms: ["F2025", "S2026", "F2026"],
  transcript: {
    F2025: ["CSE-007", "MATH-021", "ENGL-001", "FYS-001"],
    S2026: ["HIST-010", "SOC-001", "BIO-041", "DSCI-001"],
    F2026: ["CSE-017", "MATH-022", "ENGL-002", "PSY-001"],
  },
  withdrawn: { S2026: ["CHM-030"] },
};

export const DEMO_ADVISOR = { id: "patel", name: "Dr. Maya Patel", title: "Faculty advisor, Computer Science" };

/** Other advisees shown in the advisor list (static, for the demo). */
export const OTHER_ADVISEES: StudentAccount[] = [
  {
    id: "sam", name: "Sam Rivera", program: PROGRAM.name, year: "Junior", creditRange: [12, 18],
    lockedTerms: ["F2025", "S2026", "F2026"],
    transcript: {
      F2025: ["CSE-007", "MATH-021", "ENGL-001", "FYS-001"],
      S2026: ["CSE-017", "MATH-022", "ENGL-002", "ECO-001"],
      F2026: ["CSE-109", "CSE-140", "MATH-205", "PHY-011"],
    },
    withdrawn: { F2026: ["PHIL-105"] },
  },
  {
    id: "ava", name: "Ava Chen", program: PROGRAM.name, year: "Sophomore", creditRange: [12, 18],
    lockedTerms: ["F2025", "S2026", "F2026"],
    transcript: {
      F2025: ["CSE-007", "MATH-021", "FYS-001", "SOC-001"],
      S2026: ["CSE-017", "ENGL-001", "MATH-022", "ART-011"],
      F2026: ["CSE-140", "ENGL-002", "MATH-231", "CHM-030"],
    },
  },
];

/** Mock professor ratings (made-up names from the demo catalog). */
export const PROFESSOR_RATINGS: Record<string, { rating: number; takeAgain: number }> = {
  "Prof. Ana Moreno": { rating: 4.6, takeAgain: 91 },
  "Dr. Kwame Osei": { rating: 4.1, takeAgain: 80 },
  "Dr. Lena Fischer": { rating: 3.7, takeAgain: 68 },
  "Dr. Priya Raman": { rating: 4.8, takeAgain: 95 },
  "Dr. Marco Bianchi": { rating: 3.9, takeAgain: 72 },
  "Prof. Hannah Cole": { rating: 4.4, takeAgain: 88 },
  "Dr. Samuel Ortiz": { rating: 4.2, takeAgain: 83 },
  "Dr. Yuki Tanaka": { rating: 4.5, takeAgain: 90 },
  "Dr. Elena Petrova": { rating: 4.0, takeAgain: 77 },
  "Dr. James Whitfield": { rating: 3.5, takeAgain: 61 },
  "Dr. Omar Haddad": { rating: 4.3, takeAgain: 85 },
  "Dr. Grace Liu": { rating: 4.7, takeAgain: 93 },
  "Prof. Daniel Brooks": { rating: 4.2, takeAgain: 82 },
  "Dr. Rosa Alvarez": { rating: 4.0, takeAgain: 79 },
  "Prof. Victor Nash": { rating: 3.8, takeAgain: 70 },
  "Dr. Claire Dubois": { rating: 4.5, takeAgain: 89 },
  "Prof. Ian Reyes": { rating: 4.6, takeAgain: 92 },
  "Dr. Nora Lindqvist": { rating: 4.3, takeAgain: 86 },
  "Prof. Mateo Silva": { rating: 4.9, takeAgain: 97 },
};

export function termLabel(termId: string): string {
  return TERMS.find((t) => t.id === termId)?.label ?? termId;
}
