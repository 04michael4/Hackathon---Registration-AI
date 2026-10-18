import type { AppState, Catalog, Course, ElectiveSlot, Term } from "./types";

function course(
  code: string,
  name: string,
  credits: number,
  requirement: string,
  prereqs: string[],
  terms: Course["terms"],
  extra: Partial<Course> = {},
): Course {
  return {
    code,
    name,
    credits,
    requirement,
    prereqs,
    terms,
    required: true,
    section: "Not provided",
    days: "",
    time: "",
    rating: null,
    difficulty: "Not provided",
    workload: "Not provided",
    description: "Description not provided in this demo record.",
    interest: "Computing",
    ...extra,
  };
}

export const catalog: Catalog = {
  cse007: course("CSE 007", "Introduction to Programming", 4, "Major foundation", [], ["fall", "spring"]),
  cse017: course("CSE 017", "Programming & Data Structures", 4, "Major foundation", ["cse007"], ["fall", "spring"]),
  math022: course("MATH 022", "Calculus II", 4, "Mathematics foundation", [], ["fall", "spring"], { interest: "Mathematics" }),
  cse241: course("CSE 241", "Data Structures & Algorithms", 4, "CS Major Core", ["cse017"], ["fall", "spring"], { section: "010 · Prof. Maya Chen", days: "Mon / Wed", time: "9:10–10:25 AM", rating: 4.6, difficulty: "3.7 / 5", workload: "8–10 hrs/wk", description: "Demo course covering data structures and algorithm analysis." }),
  math231: course("MATH 231", "Probability & Statistics", 4, "Data Science Minor", ["math022"], ["fall", "spring"], { section: "020 · Prof. Daniel Park", days: "Tue / Thu", time: "10:45 AM–12:00 PM", rating: 4.3, difficulty: "3.3 / 5", workload: "6–8 hrs/wk", interest: "Mathematics", description: "Demo course exploring probability models and statistical inference." }),
  ds210: course("DS 210", "Data Systems", 3, "Data Science Minor", ["cse017"], ["fall", "spring"], { section: "010 · Prof. Elena Ruiz", days: "Tue / Thu", time: "1:10–2:25 PM", rating: 4.8, difficulty: "3.9 / 5", workload: "8–11 hrs/wk", description: "Demo course exploring how data is stored, queried, and organized." }),
  phil114: course("PHIL 114", "Ethics of Technology", 3, "General education · Humanities", [], ["fall", "spring"], { section: "011 · Prof. Samira Cole", days: "Mon / Wed", time: "11:15 AM–12:05 PM", rating: 4.5, difficulty: "2.4 / 5", workload: "3–5 hrs/wk", interest: "Technology & society", description: "Demo seminar on ethical questions raised by new technologies." }),
  cse109: course("CSE 109", "Systems Software", 4, "CS Major Core", ["cse017"], ["fall", "spring"], { section: "010 · Prof. Evan Cole", days: "Mon / Wed", time: "8:00–9:00 AM", rating: 4.7, difficulty: "3.8 / 5", workload: "8–10 hrs/wk", description: "Demo systems course. The only sample section begins at 8 AM." }),
  cse252: course("CSE 252", "Computer Networks Lab", 1, "CS Major Core", ["cse109"], ["fall", "spring"], { section: "013 · Prof. Jordan Lee", days: "Thursday", time: "2:35–4:25 PM", rating: 4.2, difficulty: "3.1 / 5", workload: "3–4 hrs/wk", description: "Demo networking laboratory; complete Systems Software first." }),
  cse202: course("CSE 202", "Software Design", 3, "CS Major Core", ["cse241"], ["fall", "spring"], { section: "010 · Prof. Maya Chen", days: "Mon / Wed", time: "1:10–2:25 PM", rating: 4.6, description: "Demo course on software design and testing." }),
  cse303: course("CSE 303", "Computing Theory", 3, "CS Major Core", ["cse241"], ["fall", "spring"], { section: "010 · Prof. Jordan Lee", days: "Tue / Thu", time: "9:20–10:35 AM", rating: 4.2, description: "Demo theory course. Data Structures & Algorithms must be completed first." }),
  cse398: course("CSE 398", "Senior Project I", 3, "Major capstone", ["cse109"], ["fall"], { description: "Demo first capstone course, offered in fall only." }),
  cse399: course("CSE 399", "Senior Project II", 3, "Major capstone", ["cse398"], ["spring"], { description: "Demo second capstone course, offered in spring after Senior Project I." }),
  eco001: course("ECO 001", "Principles of Economics", 4, "Free elective", [], ["fall", "spring"], { required: false, section: "020 · Prof. Riley Shah", days: "Tue / Thu", time: "12:10–1:25 PM", rating: 4.4, difficulty: "2.6 / 5", workload: "4–6 hrs/wk", interest: "Economics", description: "Demo introduction to economic reasoning. Its sample time overlaps DS 210." }),
  art003: course("ART 003", "Visual Thinking", 3, "Free elective", [], ["fall", "spring"], { required: false, section: "010 · Prof. Morgan Lee", days: "Friday", time: "10:00 AM–12:30 PM", rating: 4.7, difficulty: "2.1 / 5", workload: "3–4 hrs/wk", interest: "Arts", description: "Demo studio course exploring visual communication." }),
  phil120: course("PHIL 120", "Ethics & Society", 3, "Free elective", [], ["fall", "spring"], { required: false, section: "020 · Prof. Samira Cole", days: "Mon / Wed", time: "2:35–3:50 PM", rating: 4.5, difficulty: "2.4 / 5", workload: "3–5 hrs/wk", interest: "Technology & society", description: "Demo elective in ethical reasoning and society." }),
  ds310: course("DS 310", "Applied Data Projects", 3, "Free elective", ["ds210"], ["spring"], { required: false, section: "030 · Prof. Elena Ruiz", days: "Tue / Thu", time: "3:00–4:15 PM", rating: 4.8, interest: "Computing", description: "Demo advanced elective. Complete DS 210 in an earlier semester before taking this course." }),
};

export const completedCourseIds = ["cse007", "cse017", "math022"] as const;
export const completed = new Set<string>(completedCourseIds);

export const terms: Term[] = [2024, 2025, 2026, 2027].flatMap((year) => [
  { id: `fall-${year}`, label: `Fall ${year}`, season: "fall", year, past: year < 2027 },
  { id: `spring-${year + 1}`, label: `Spring ${year + 1}`, season: "spring", year: year + 1, past: year + 1 < 2027 },
]);

export const electiveSlots: ElectiveSlot[] = [
  { id: "elective-a", term: "spring-2028", credits: 4 },
  { id: "elective-b", term: "spring-2028", credits: 3 },
];

export const nextTermId = "spring-2027";
export const historicalCredits = 82;

export const defaultState: AppState = {
  version: 4,
  role: "student",
  planner: ["cse241", "math231"],
  degree: {
    "fall-2024": ["cse007"],
    "spring-2025": ["cse017", "math022"],
    "fall-2025": [],
    "spring-2026": [],
    "fall-2026": [],
    "spring-2027": ["cse241", "math231", "ds210", "cse109"],
    "fall-2027": ["cse202", "cse303", "cse398", "cse252"],
    "spring-2028": ["cse399", "phil114"],
  },
  electives: { "elective-a": null, "elective-b": null },
  saved: [],
  review: { status: "draft", comments: [], notifications: [] },
  preferences: {
    start: 540,
    end: 1020,
    dayOff: "Fri",
    professor: "",
    minCredits: 12,
    maxCredits: 16,
    aiOpen: false,
    degreeElectiveTerms: {},
    advisorCourseStatuses: {},
  },
  semesterChat: [],
  degreeChat: [],
};
