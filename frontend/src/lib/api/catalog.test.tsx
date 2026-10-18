import { createElement } from "react";
import { renderToStaticMarkup } from "react-dom/server";
import { afterEach, describe, expect, it, vi } from "vitest";
import { CatalogCourseDetails, Prediction } from "../../components/CatalogCourseDetails";
import { courseIdFromCode, loadCourseDetail, searchCatalog, termIdFromPlanner } from "./catalogClient";
import { proxyCatalog } from "./catalogProxy";
import type { CourseDetail, PredictedRating } from "./types";

const prediction: PredictedRating = { quality: 3.66, difficulty: 2.95, confidence: "LOW", basis: "PREDICTED", sectionsPredicted: 1, sections: 3 };
// Synthetic instructors only; never copy Banner names or API captures into fixtures.
const detail: CourseDetail = {
  courseId: "CSE-099", subject: "CSE", number: "099", title: "Synthetic Fixture Course", credits: 3, description: null,
  predictedRating: prediction,
  conditions: [{ code: "PREREQUISITE", message: "Prerequisite has not been verified", evidenceLevel: "TEAM_POLICY", outcome: "UNVERIFIED" }],
  sections: [{ crn: "99001", sectionCode: "010", componentType: "LECTURE", lectureLink: null, credits: 3, status: "UNKNOWN", meetings: [],
    instructors: ["Fixture, Alpha", "Fixture, Beta"], instructorRatings: [
      { name: "Fixture, Alpha", predictedQuality: 3.66, predictedDifficulty: 2.95, confidence: "LOW", ratingCount: 12, courseSpecific: true },
      { name: "Fixture, Beta", predictedQuality: null, predictedDifficulty: null, confidence: null, ratingCount: null, courseSpecific: false },
    ] },
    { crn: "99002", sectionCode: "060", componentType: "LAB", lectureLink: "99001", credits: 0, status: "CLOSED", meetings: [],
      instructors: ["Fixture, Gamma"], instructorRatings: [{ name: "Fixture, Gamma", predictedQuality: 4.1, predictedDifficulty: 3.1, confidence: "HIGH", ratingCount: 0, courseSpecific: false }] },
  ],
};

afterEach(() => vi.unstubAllGlobals());

describe("prediction and conditions presentation", () => {
  it("labels every prediction, confidence and partial coverage without making it a course rating", () => {
    const html = renderToStaticMarkup(createElement(CatalogCourseDetails, { course: detail }));
    expect(html).toContain("Predicted quality: 3.66 / 5");
    expect(html).toContain("Predicted difficulty: 2.95 / 5");
    expect(html).toContain("Low confidence");
    expect(html).toContain("Coverage: 1 of 3 lecture sections");
    expect(html).toContain("Partial coverage");
    expect(html).not.toContain("Course rating");
  });

  it("renders every section's instructors, missing predictions and historical basis", () => {
    const html = renderToStaticMarkup(createElement(CatalogCourseDetails, { course: detail }));
    for (const name of ["Fixture, Alpha", "Fixture, Beta", "Fixture, Gamma"]) expect(html).toContain(name);
    expect(html).toContain("Section 060");
    expect(html).toContain("Linked lecture CRN: 99001");
    expect(html).toContain("High confidence");
    expect(html).toContain("Based on 12 earlier ratings");
    expect(html).toContain("Based on 0 earlier ratings");
    expect(html).toContain("For this course");
    expect(html).toContain("Instructor history across courses");
    expect(html).toContain("No prediction available");
    expect(html).not.toContain("0.00 / 5");
  });

  it("shows conditions as unverified and never interprets an empty list as eligibility", () => {
    const html = renderToStaticMarkup(createElement(CatalogCourseDetails, { course: detail }));
    expect(html).toContain("Prerequisite has not been verified");
    expect(html).toContain("TEAM POLICY");
    expect(html).toContain("UNVERIFIED");
    const empty = renderToStaticMarkup(createElement(CatalogCourseDetails, { course: { ...detail, conditions: [], predictedRating: null } }));
    expect(empty).toContain("No conditions listed. This does not confirm eligibility.");
    expect(empty).toContain("No prediction available");
  });

  it("escapes instructor names and condition text instead of executing API markup", () => {
    const course = structuredClone(detail);
    course.sections[0].instructorRatings[0].name = '<script>alert("fixture")</script>';
    course.conditions[0].message = '<img src=x onerror="fixture">';
    const html = renderToStaticMarkup(createElement(CatalogCourseDetails, { course }));
    expect(html).toContain("&lt;script&gt;");
    expect(html).toContain("&lt;img");
    expect(html).not.toContain("<script>");
  });

  it("uses predicted wording and confidence in compact cards too", () => {
    const html = renderToStaticMarkup(createElement(Prediction, { rating: prediction, compact: true }));
    expect(html).toContain("Predicted quality");
    expect(html).toContain("Low confidence");
    expect(renderToStaticMarkup(createElement(Prediction, { rating: null }))).toContain("No prediction available");
  });
});

describe("runtime catalog client", () => {
  it("maps planner codes and terms to API identifiers", () => {
    expect(courseIdFromCode("cse 99")).toBe("CSE-099");
    expect(courseIdFromCode("MATH-022")).toBe("MATH-022");
    expect(courseIdFromCode("cse 398-042")).toBe("CSE-398-042");
    expect(courseIdFromCode("CSE-398-052")).toBe("CSE-398-052");
    expect(termIdFromPlanner("spring-2027")).toBe("2027-SPRING");
    expect(() => courseIdFromCode("../secrets")).toThrow();
    expect(() => courseIdFromCode("CSE-398-042-001")).toThrow();
    expect(() => termIdFromPlanner("unknown")).toThrow();
  });

  it("searches the actual API catalog with pagination and retains prediction metadata", async () => {
    const response = { items: [{ ...detail, sectionCount: 2 }], page: 2, size: 20, totalItems: 41, totalPages: 3 };
    const fetcher = vi.fn().mockResolvedValue(Response.json(response));
    vi.stubGlobal("fetch", fetcher);
    expect(await searchCatalog("CSE 099", "2027-SPRING", 2)).toEqual(response);
    expect(fetcher.mock.calls[0][0]).toContain("page=2");
    expect(fetcher.mock.calls[0][1]).toMatchObject({ cache: "no-store", credentials: "omit" });
  });

  it("loads actual conditions and instructor ratings without persisting or logging them", async () => {
    const logger = vi.spyOn(console, "log");
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(Response.json(detail)));
    expect(await loadCourseDetail("CSE 099", "2027-SPRING")).toEqual(detail);
    expect(logger).not.toHaveBeenCalled();
    logger.mockRestore();
  });

  it("accepts topic-suffixed course IDs in search and detail from the latest API contract", async () => {
    const topic = { ...detail, courseId: "CSE-398-042", conditions: [
      { code: "COMMON_EXAMS", message: "Fixture exam condition", evidenceLevel: "BANNER", outcome: "UNVERIFIED" },
      { code: "NOT_CHECKED", message: "Fixture eligibility is unchecked", evidenceLevel: "TEAM_POLICY", outcome: "UNVERIFIED" },
    ] };
    const fetcher = vi.fn().mockResolvedValueOnce(Response.json({ items: [{ ...topic, sectionCount: 2 }], page: 0, size: 20, totalItems: 1, totalPages: 1 }))
      .mockResolvedValueOnce(Response.json(topic));
    vi.stubGlobal("fetch", fetcher);
    expect((await searchCatalog("CSE 398", "2027-SPRING")).items[0].courseId).toBe("CSE-398-042");
    expect(await loadCourseDetail("CSE-398-042", "2027-SPRING")).toEqual(topic);
    expect(fetcher.mock.calls[1][0]).toContain("/courses/CSE-398-042?");
  });

  it("rejects unavailable or malformed detail instead of returning demo ratings", async () => {
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(Response.json({ detail: "unavailable" }, { status: 503 })));
    await expect(loadCourseDetail("CSE 099", "2027-SPRING")).rejects.toThrow("unavailable");
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(Response.json({ ...detail, courseId: "CSE-100" })));
    await expect(loadCourseDetail("CSE 099", "2027-SPRING")).rejects.toThrow("Invalid catalog response");
  });

  it.each([
    { ...detail, predictedRating: { ...prediction, quality: 99 } },
    { ...detail, predictedRating: { ...prediction, confidence: "VERIFIED" } },
    { ...detail, conditions: [{ ...detail.conditions[0], outcome: "ELIGIBLE" }] },
    { ...detail, sections: [{ ...detail.sections[0], instructorRatings: "not a list" }] },
  ])("rejects malformed metadata without displaying invented confidence or eligibility", async course => {
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(Response.json(course)));
    await expect(loadCourseDetail("CSE 099", "2027-SPRING")).rejects.toThrow("Invalid catalog response");
  });
});

describe("private read-only catalog proxy", () => {
  it("does not request live instructor data unless configured", async () => {
    const fetcher = vi.fn(); vi.stubGlobal("fetch", fetcher);
    const response = await proxyCatalog(new Request("http://localhost/api/catalog/courses"), ["courses"], undefined);
    expect(response.status).toBe(503);
    expect(fetcher).not.toHaveBeenCalled();
    expect(response.headers.get("cache-control")).toContain("private, no-store");
  });

  it("only forwards the course endpoint and allowed parameters, without cookies or caching", async () => {
    const fetcher = vi.fn().mockResolvedValue(Response.json(detail)); vi.stubGlobal("fetch", fetcher);
    const response = await proxyCatalog(new Request("http://localhost/api/catalog/courses/CSE-099?term=2027-SPRING&secret=fixture", { headers: { Cookie: "session=fixture" } }), ["courses", "CSE-099"], "http://127.0.0.1:8080");
    const [url, options] = fetcher.mock.calls[0];
    expect(String(url)).toBe("http://127.0.0.1:8080/api/courses/CSE-099?term=2027-SPRING");
    expect(options).toMatchObject({ cache: "no-store", redirect: "error", headers: { Accept: "application/json" } });
    expect(response.headers.get("cache-control")).toContain("private, no-store");
    expect(response.headers.get("x-robots-tag")).toContain("noindex");
    expect(await response.json()).toEqual(detail);
  });

  it("rejects paths outside the catalog and sanitizes provider errors", async () => {
    const fetcher = vi.fn(); vi.stubGlobal("fetch", fetcher);
    expect((await proxyCatalog(new Request("http://localhost/api/catalog/secret"), ["secret"], "http://localhost:8080")).status).toBe(404);
    expect(fetcher).not.toHaveBeenCalled();
    fetcher.mockResolvedValue(Response.json({ detail: "Fixture, PrivateName" }, { status: 500 }));
    const failure = await proxyCatalog(new Request("http://localhost/api/catalog/courses"), ["courses"], "http://localhost:8080");
    expect(failure.status).toBe(500);
    expect(await failure.text()).not.toContain("PrivateName");
  });

  it("forwards numeric topic suffixes while rejecting extra path components", async () => {
    const fetcher = vi.fn().mockResolvedValue(Response.json({ ...detail, courseId: "CSE-398-042" }));
    vi.stubGlobal("fetch", fetcher);
    const request = new Request("http://localhost/api/catalog/courses/CSE-398-042?term=2027-SPRING");
    expect((await proxyCatalog(request, ["courses", "CSE-398-042"], "http://localhost:8080")).status).toBe(200);
    expect(String(fetcher.mock.calls[0][0])).toBe("http://localhost:8080/api/courses/CSE-398-042?term=2027-SPRING");
    expect((await proxyCatalog(request, ["courses", "CSE-398-042-001"], "http://localhost:8080")).status).toBe(404);
    expect(fetcher).toHaveBeenCalledTimes(1);
  });
});
