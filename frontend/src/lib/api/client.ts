import type {
  CheckRequest,
  CheckResponse,
  CourseDetail,
  CourseSummary,
  ExtractResponse,
  GenerateRequest,
  GenerateResponse,
  Page,
  ProblemDetails,
  Term,
} from "./types";

/** An API call that failed, with a message safe to show to the student. */
export class ApiError extends Error {
  constructor(
    message: string,
    readonly status: number,
  ) {
    super(message);
  }
}

function isProblem(value: unknown): value is ProblemDetails {
  return typeof value === "object" && value !== null && ("detail" in value || "title" in value);
}

async function request<T>(path: string, init?: RequestInit): Promise<T> {
  let response: Response;
  try {
    response = await fetch(path, {
      ...init,
      headers: { "Content-Type": "application/json", Accept: "application/json", ...init?.headers },
    });
  } catch {
    throw new ApiError("Can't reach the schedule service. Check that the backend is running.", 0);
  }
  if (!response.ok) {
    let body: unknown = null;
    try {
      body = await response.json();
    } catch {
      // Not JSON; fall through to a generic message.
    }
    const fieldErrors = isProblem(body) && body.errors?.length ? `: ${body.errors.map((e) => e.message).join("; ")}` : "";
    const detail = isProblem(body) ? (body.detail ?? body.title) : undefined;
    throw new ApiError(`${detail ?? `Request failed (${response.status})`}${fieldErrors}`, response.status);
  }
  return (await response.json()) as T;
}

export const api = {
  terms: () => request<{ items: Term[] }>("/api/terms").then((r) => r.items),

  searchCourses: (term: string, size = 100) =>
    request<Page<CourseSummary>>(`/api/courses?term=${encodeURIComponent(term)}&size=${size}`),

  course: (term: string, courseId: string) =>
    request<CourseDetail>(`/api/courses/${encodeURIComponent(courseId)}?term=${encodeURIComponent(term)}`),

  extract: (term: string, text: string) =>
    request<ExtractResponse>("/api/preferences/extract", { method: "POST", body: JSON.stringify({ term, text }) }),

  generate: (body: GenerateRequest) =>
    request<GenerateResponse>("/api/schedules/generate", { method: "POST", body: JSON.stringify(body) }),

  check: (body: CheckRequest) =>
    request<CheckResponse>("/api/schedules/check", { method: "POST", body: JSON.stringify(body) }),
};
