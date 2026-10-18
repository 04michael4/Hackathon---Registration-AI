export type Confidence = "LOW" | "MEDIUM" | "HIGH";

export interface PredictedRating {
  quality: number;
  difficulty: number;
  confidence: Confidence;
  basis: "PREDICTED";
  sectionsPredicted: number;
  sections: number;
}

export interface InstructorRating {
  name: string;
  predictedQuality: number | null;
  predictedDifficulty: number | null;
  confidence: Confidence | null;
  ratingCount: number | null;
  courseSpecific: boolean;
}

export interface CourseCondition {
  code: string;
  message: string;
  evidenceLevel: string;
  outcome: "UNVERIFIED";
}

export interface CourseSummary {
  courseId: string;
  subject: string;
  number: string;
  title: string;
  credits: number;
  sectionCount: number;
  predictedRating: PredictedRating | null;
}

export interface CourseSearchResponse {
  items: CourseSummary[];
  page: number;
  size: number;
  totalItems: number;
  totalPages: number;
}

export interface CourseDetail extends Omit<CourseSummary, "sectionCount"> {
  description: string | null;
  conditions: CourseCondition[];
  sections: {
    crn: string;
    sectionCode: string;
    componentType: string;
    lectureLink: string | null;
    credits: number;
    status: "OPEN" | "CLOSED" | "UNKNOWN";
    instructors: string[];
    instructorRatings: InstructorRating[];
    meetings: { days: string[]; start: string; end: string; location: string | null }[];
  }[];
}

export const DAYS = ["MONDAY", "TUESDAY", "WEDNESDAY", "THURSDAY", "FRIDAY", "SATURDAY", "SUNDAY"] as const;
export type Day = (typeof DAYS)[number];
export interface TermsResponse { items: { term: string; name: string; catalogSnapshotDate: string | null }[] }
export interface FieldError { field: string; message: string }
export interface ProblemDetails { type: string; title: string; status: number; detail: string; instance?: string; requestId?: string; errors?: FieldError[] }
export interface TimeBlock { days: Day[]; start: string; end: string }
export interface Weighted<T> { value: T; weight: number }
export interface SchedulePreferences {
  hardConstraints: { requiredCourseIds: string[]; minCredits: number; maxCredits: number; blockedTimes: TimeBlock[] };
  softPreferences?: {
    optionalCourseIds?: Weighted<string[]>;
    earliestStart?: Weighted<string>;
    latestEnd?: Weighted<string>;
    daysOff?: Weighted<Day[]>;
    minimizeGaps?: { weight: number };
  } | null;
}
export interface GenerateRequest { term: string; preferences: SchedulePreferences; maxResults: number; includeClosedSections: boolean }
export interface ScheduleSection { crn: string; courseId: string; sectionCode: string; title: string; credits: number; meetings: { days: Day[]; start: string; end: string; location: string | null }[] }
export interface Schedule { rank: number; score: number; totalCredits: number; overload: boolean; sections: ScheduleSection[]; scoreBreakdown: Record<string, number> }
export interface GenerateResponse {
  term: string; schedules: Schedule[]; diagnostics: string[]; searchLimitReached: boolean;
  electiveAdvice: { courseId: string; status: "IN_TOP_SCHEDULE" | "IN_OTHER_SUGGESTION" | "FITS_WITH_CHANGES" | "CANNOT_FIT"; summary: string; fixes: string[]; alternativeCrns: string[] }[];
}
export interface CheckRequest { term: string; preferences: SchedulePreferences; courseIds: string[]; crns: string[]; includeClosedSections: boolean }
export interface CheckResponse extends Omit<Schedule, "rank"> {
  term: string; complete: boolean;
  issues: { type: string; severity: "CONFLICT" | "INCOMPLETE"; crns: string[]; message: string }[];
  candidates: { crn: string; courseId: string; fits: boolean; completesLecture: boolean; reasons: string[] }[];
}
export interface SuggestResponse { term: string; suggestions: ({ courseId: string; title: string } & Omit<Schedule, "rank">)[] }
export interface ExtractResponse { preferences: SchedulePreferences; unresolved: string[]; warnings: string[] }
export interface ExplainResponse { explanation: string; highlights: string[] }
