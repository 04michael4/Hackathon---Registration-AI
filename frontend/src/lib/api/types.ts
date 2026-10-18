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
  title: string;
  credits: number;
  predictedRating: PredictedRating | null;
}

export interface CourseSearchResponse {
  items: CourseSummary[];
  page: number;
  size: number;
  totalItems: number;
  totalPages: number;
}

export interface CourseDetail extends CourseSummary {
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
