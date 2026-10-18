export type Role = "student" | "advisor";
export type Screen = "login" | "features" | "planner" | "requirements" | "advisor" | "saved";
export type Season = "fall" | "spring";
export type CourseStatus = "Done" | "Review" | "Withdraw" | "Planned";

export interface Course {
  code: string;
  name: string;
  credits: number;
  requirement: string;
  prereqs: string[];
  terms: Season[];
  required: boolean;
  section: string;
  days: string;
  time: string;
  rating: number | null;
  difficulty: string;
  workload: string;
  description: string;
  interest: string;
}

export interface Term {
  id: string;
  label: string;
  season: Season;
  year: number;
  past: boolean;
}

export interface ElectiveSlot {
  id: "elective-a" | "elective-b";
  term: string;
  credits: number;
}

export interface Preferences {
  start: number;
  end: number;
  dayOff: string;
  professor: string;
  minCredits: number;
  maxCredits: number;
  aiOpen: boolean;
  degreeElectiveTerms: Partial<Record<ElectiveSlot["id"], string>>;
  advisorCourseStatuses: Record<string, CourseStatus>;
}

export interface ChatMessage {
  role: "user" | "assistant";
  text: string;
}

export interface SavedSchedule {
  id: string;
  name: string;
  term: string;
  courses: string[];
  preferences: Preferences;
  savedAt: string;
}

export interface ReviewComment {
  id: string;
  text: string;
  courseId: string | null;
  createdAt: string;
  type: "comment" | "decision";
}

export interface ReviewNotification {
  id: string;
  text: string;
  read: boolean;
  createdAt: string;
}

export interface ReviewState {
  status: "draft" | "submitted" | "changes-requested" | "approved";
  comments: ReviewComment[];
  notifications: ReviewNotification[];
  submittedAt?: string;
}

export interface AppState {
  version: 4;
  role: Role;
  planner: string[];
  degree: Record<string, string[]>;
  electives: Record<ElectiveSlot["id"], string | null>;
  saved: SavedSchedule[];
  review: ReviewState;
  preferences: Preferences;
  semesterChat: ChatMessage[];
  degreeChat: ChatMessage[];
}

export interface DegreeAudit {
  valid: boolean;
  issues: string[];
  plannedCredits: number;
  totalCredits: number;
  graduation: string;
  missing: string[];
}

export interface PlanPreview {
  ids: string[];
  reasons: Record<string, string>;
  exceptions: string[];
  conflicts: [string, string][];
  source: string[];
}

export type Catalog = Record<string, Course>;
