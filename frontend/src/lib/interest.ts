// Interest search: "machine learning", "security", "finance" -> related courses.
// All matching lives in searchByInterest so it can be swapped for something smarter (embeddings, an LLM) later.

import type { RequirementKind } from "./demo/program";

export interface InterestCourse {
  courseId: string;
  title: string;
  description: string | null;
  kind: RequirementKind;
}

export interface InterestMatch {
  courseId: string;
  /** The words that matched, to explain the result. */
  matched: string[];
}

/** A few related words per topic, so "finance" also finds "investment" and "economics". */
const RELATED: Record<string, string[]> = {
  "machine learning": ["machine learning", "learning", "neural", "prediction", "statistics", "probability", "data"],
  ai: ["machine learning", "neural", "prediction", "learning"],
  security: ["security", "cryptography", "attack", "defense", "secure", "network"],
  cybersecurity: ["security", "cryptography", "attack", "defense"],
  finance: ["finance", "investment", "stocks", "bonds", "risk", "economics", "markets", "money"],
  business: ["finance", "economics", "markets", "investment"],
  data: ["data", "database", "statistics", "sql", "probability"],
  web: ["software", "database", "applications", "network"],
  art: ["drawing", "studio", "art", "composition"],
  writing: ["writing", "argument", "research", "composition"],
  ethics: ["ethics", "ethical", "society"],
};

// Electives first, as asked: a student searching by interest is usually shopping for an elective.
const KIND_ORDER: Record<RequirementKind, number> = { elective: 0, free: 1, gened: 2, core: 3 };

function termsFor(query: string): string[] {
  const q = query.trim().toLowerCase();
  if (!q) return [];
  const words = q.split(/\s+/).filter((w) => w.length > 2);
  return [...new Set([q, ...(RELATED[q] ?? []), ...words, ...words.flatMap((w) => RELATED[w] ?? [])])];
}

export function searchByInterest(courses: InterestCourse[], query: string): InterestMatch[] {
  const terms = termsFor(query);
  if (!terms.length) return [];
  return courses
    .map((course) => {
      const title = course.title.toLowerCase();
      const description = (course.description ?? "").toLowerCase();
      const matched = terms.filter((t) => title.includes(t) || description.includes(t));
      const score = terms.reduce((sum, t) => sum + (title.includes(t) ? 3 : 0) + (description.includes(t) ? 1 : 0), 0);
      return { course, matched, score };
    })
    .filter((r) => r.score > 0)
    .sort((a, b) => KIND_ORDER[a.course.kind] - KIND_ORDER[b.course.kind] || b.score - a.score || a.course.courseId.localeCompare(b.course.courseId))
    .map((r) => ({ courseId: r.course.courseId, matched: r.matched.slice(0, 3) }));
}
