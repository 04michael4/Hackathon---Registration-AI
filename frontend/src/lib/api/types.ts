// Mirrors docs/api-contract.md. Keep in sync with the backend; the contract is the source of truth.

export type Day = "MONDAY" | "TUESDAY" | "WEDNESDAY" | "THURSDAY" | "FRIDAY" | "SATURDAY" | "SUNDAY";

export interface TimeBlock {
  days: Day[];
  start: string; // "HH:mm"
  end: string;
}

export interface Weighted<T> {
  value: T;
  weight: number; // 0–5
}

export interface SchedulePreferences {
  hardConstraints: {
    requiredCourseIds: string[];
    minCredits?: number;
    maxCredits?: number;
    blockedTimes?: TimeBlock[];
  };
  softPreferences?: {
    optionalCourseIds?: Weighted<string[]>;
    earliestStart?: Weighted<string>;
    latestEnd?: Weighted<string>;
    daysOff?: Weighted<Day[]>;
    minimizeGaps?: { weight: number };
  };
}

// 2. GET /api/terms
export interface Term {
  term: string;
  name: string;
  catalogSnapshotDate: string;
}

// 3. GET /api/courses
export interface CourseSummary {
  courseId: string;
  subject: string;
  number: string;
  title: string;
  credits: number;
  sectionCount: number;
}

export interface Page<T> {
  items: T[];
  page: number;
  size: number;
  totalItems: number;
  totalPages: number;
}

// 4. GET /api/courses/{courseId}
export interface Meeting {
  days: Day[];
  start: string;
  end: string;
  location: string | null;
}

export interface Section {
  crn: string;
  sectionCode: string;
  componentType: string; // LECTURE, LAB, RECITATION, ...
  lectureLink: string | null; // lecture CRN for a lab/recitation; null for a lecture
  credits: number;
  instructors: string[];
  status: "OPEN" | "CLOSED" | "UNKNOWN";
  meetings: Meeting[];
}

export interface CourseDetail {
  courseId: string;
  subject: string;
  number: string;
  title: string;
  credits: number;
  description: string | null;
  sections: Section[];
}

// 5. POST /api/preferences/extract
export interface ExtractResponse {
  preferences: SchedulePreferences;
  unresolved: string[];
  warnings: string[];
}

// 6. POST /api/schedules/generate
export interface ScheduleSection {
  crn: string;
  courseId: string;
  sectionCode: string;
  title: string;
  credits: number;
  meetings: Meeting[];
}

export interface GeneratedSchedule {
  rank: number;
  score: number;
  totalCredits: number;
  overload: boolean;
  sections: ScheduleSection[];
  scoreBreakdown: Record<string, number>;
}

export interface ElectiveAdvice {
  courseId: string;
  status: "IN_TOP_SCHEDULE" | "IN_OTHER_SUGGESTION" | "FITS_WITH_CHANGES" | "CANNOT_FIT";
  summary: string;
  fixes: string[];
  alternativeCrns: string[];
}

export interface GenerateRequest {
  term: string;
  preferences: SchedulePreferences;
  maxResults?: number;
  includeClosedSections?: boolean;
}

export interface GenerateResponse {
  term: string;
  schedules: GeneratedSchedule[];
  diagnostics: string[];
  searchLimitReached: boolean;
  electiveAdvice: ElectiveAdvice[];
}

// 8. POST /api/schedules/check
export type IssueType =
  | "UNKNOWN_SECTION"
  | "TIME_CONFLICT"
  | "BLOCKED_TIME"
  | "DUPLICATE_COURSE"
  | "DUPLICATE_COMPONENT"
  | "OVER_MAX_CREDITS"
  | "LINKED_WITHOUT_LECTURE"
  | "MISSING_LINKED_SECTION"
  | "REQUIRED_COURSE_MISSING"
  | "UNDER_MIN_CREDITS";

export interface CheckIssue {
  type: IssueType;
  severity: "CONFLICT" | "INCOMPLETE";
  crns: string[];
  message: string;
}

export interface CheckCandidate {
  crn: string;
  courseId: string;
  fits: boolean;
  completesLecture: boolean;
  reasons: string[];
}

export interface CheckRequest {
  term: string;
  preferences: SchedulePreferences;
  courseIds?: string[];
  crns: string[];
  includeClosedSections?: boolean;
}

export interface CheckResponse {
  term: string;
  sections: ScheduleSection[];
  totalCredits: number;
  overload: boolean;
  score: number;
  scoreBreakdown: Record<string, number>;
  complete: boolean;
  issues: CheckIssue[];
  candidates: CheckCandidate[];
}

/** RFC 9457 Problem Details, as returned by every error. */
export interface ProblemDetails {
  title?: string;
  status?: number;
  detail?: string;
  errors?: { field: string; message: string }[];
}
