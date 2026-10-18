import type { CourseDetail, CourseSearchResponse } from "./types";
import { requestJson } from "./httpClient";

const record = (value: unknown): value is Record<string, unknown> => Boolean(value) && typeof value === "object" && !Array.isArray(value);
const count = (value: unknown) => typeof value === "number" && Number.isInteger(value) && value >= 0;
const score = (value: unknown) => typeof value === "number" && Number.isFinite(value) && value >= 1 && value <= 5;
const confidence = (value: unknown) => value === "LOW" || value === "MEDIUM" || value === "HIGH";
const strings = (value: unknown): value is string[] => Array.isArray(value) && value.every(item => typeof item === "string");
const nullableText = (value: unknown) => value === null || typeof value === "string";

function prediction(value: unknown): boolean {
  return value === null || (record(value) && value.basis === "PREDICTED" && score(value.quality) && score(value.difficulty) &&
    confidence(value.confidence) && count(value.sectionsPredicted) && count(value.sections) &&
    Number(value.sectionsPredicted) <= Number(value.sections));
}

function summary(value: unknown): boolean {
  return record(value) && typeof value.courseId === "string" && /^[A-Z]+-\d+(?:-\d+)?$/.test(value.courseId) &&
    typeof value.subject === "string" && typeof value.number === "string" && typeof value.title === "string" && count(value.credits) && prediction(value.predictedRating);
}

function instructor(value: unknown): boolean {
  return record(value) && typeof value.name === "string" &&
    (value.predictedQuality === null || score(value.predictedQuality)) &&
    (value.predictedDifficulty === null || score(value.predictedDifficulty)) &&
    (value.confidence === null || confidence(value.confidence)) &&
    (value.ratingCount === null || count(value.ratingCount)) && typeof value.courseSpecific === "boolean";
}

function section(value: unknown): boolean {
  return record(value) && typeof value.crn === "string" && typeof value.sectionCode === "string" &&
    typeof value.componentType === "string" && nullableText(value.lectureLink) && count(value.credits) &&
    ["OPEN", "CLOSED", "UNKNOWN"].includes(String(value.status)) && strings(value.instructors) &&
    Array.isArray(value.instructorRatings) && value.instructorRatings.every(instructor) &&
    Array.isArray(value.meetings) && value.meetings.every(meeting => record(meeting) && strings(meeting.days) &&
      typeof meeting.start === "string" && typeof meeting.end === "string" && nullableText(meeting.location));
}

function history(value: unknown): boolean {
  return record(value) && typeof value.name === "string" && Array.isArray(value.years) && value.years.every(year => record(year) &&
    Number.isInteger(year.year) && score(year.quality) && score(year.difficulty) && count(year.ratings) && typeof year.lowSample === "boolean");
}

export function courseIdFromCode(code: string): string {
  const match = /^([A-Za-z]+)[ -]*(\d+)(?:-(\d+))?$/.exec(code.trim());
  if (!match) throw new Error("Invalid course code");
  return `${match[1].toUpperCase()}-${match[2].padStart(3, "0")}${match[3] ? `-${match[3]}` : ""}`;
}

export function termIdFromPlanner(term: string): string {
  const match = /^(spring|summer|fall|winter)-(\d{4})$/.exec(term);
  if (!match) throw new Error("Invalid planner term");
  return `${match[2]}-${match[1].toUpperCase()}`;
}

async function readJson(url: string, signal?: AbortSignal): Promise<unknown> {
  return requestJson<unknown>(url, { signal });
}

export async function searchCatalog(text: string, term: string, page = 0, signal?: AbortSignal, subject = ""): Promise<CourseSearchResponse> {
  const query = new URLSearchParams({ term, q: text, page: String(page), size: "20" });
  if (subject) query.set("subject", subject);
  const data = await readJson(`/api/backend/courses?${query}`, signal);
  if (!record(data) || !Array.isArray(data.items) || !data.items.every(item => summary(item) && record(item) && count(item.sectionCount)) ||
      ![data.page, data.size, data.totalItems, data.totalPages].every(count)) {
    throw new Error("Invalid catalog response");
  }
  return data as unknown as CourseSearchResponse;
}

export async function loadCourseDetail(code: string, term: string, signal?: AbortSignal): Promise<CourseDetail> {
  const courseId = courseIdFromCode(code);
  const query = new URLSearchParams({ term });
  const data = await readJson(`/api/backend/courses/${encodeURIComponent(courseId)}?${query}`, signal);
  if (!record(data) || !summary(data) || data.courseId !== courseId || !nullableText(data.description) ||
      !Array.isArray(data.sections) || !data.sections.every(section) ||
      !Array.isArray(data.conditions) || !data.conditions.every(condition => record(condition) &&
        typeof condition.code === "string" && typeof condition.message === "string" &&
        typeof condition.evidenceLevel === "string" && condition.outcome === "UNVERIFIED") ||
      (data.ratingHistory !== undefined && (!Array.isArray(data.ratingHistory) || !data.ratingHistory.every(history)))) {
    throw new Error("Invalid catalog response");
  }
  return { ...data, ratingHistory: data.ratingHistory ?? [] } as unknown as CourseDetail;
}
