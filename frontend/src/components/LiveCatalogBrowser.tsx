"use client";

import { useEffect, useState } from "react";
import { searchCatalog } from "@/lib/api/catalogClient";
import type { CourseSearchResponse } from "@/lib/api/types";
import { Prediction } from "./CatalogCourseDetails";

function SearchResults({ term, query, page, onPage }: Readonly<{
  term: string; query: string; page: number; onPage: (page: number) => void;
}>) {
  const [result, setResult] = useState<{ data: CourseSearchResponse | null; error: boolean } | null>(null);
  const [attempt, setAttempt] = useState(0);
  useEffect(() => {
    const controller = new AbortController();
    searchCatalog(query, term, page, controller.signal).then(data => {
      if (!controller.signal.aborted) setResult({ data, error: false });
    }).catch(() => {
      if (!controller.signal.aborted) setResult({ data: null, error: true });
    });
    return () => controller.abort();
  }, [term, query, page, attempt]);
  if (!result) return <p role="status">Loading live courses…</p>;
  if (result.error || !result.data) return <div role="status"><p>Live catalog unavailable. Try again when the backend is running.</p>
    <button className="button quiet" type="button" onClick={() => setAttempt(value => value + 1)}>Retry catalog</button></div>;
  const { items, totalItems, totalPages } = result.data;
  return <>
    <p role="status">{totalItems} course{totalItems === 1 ? "" : "s"} · {term}</p>
    {!items.length && <p>No matching courses in this term.</p>}
    <ul className="live-catalog-results">{items.map(course => <li key={course.courseId}>
      <div><strong>{course.courseId} · {course.title}</strong><span>{course.credits} credits</span></div>
      <Prediction rating={course.predictedRating} compact />
      <button className="button quiet" type="button" onClick={() => document.dispatchEvent(new CustomEvent("syllo:catalog-open", {
        detail: { code: course.courseId, term },
      }))}>Conditions & instructors<span className="syllo-sr-only"> for {course.courseId}</span></button>
    </li>)}</ul>
    {totalPages > 1 && <nav className="live-catalog-pages" aria-label="Live catalog pages">
      <button className="button quiet" type="button" disabled={page === 0} onClick={() => onPage(page - 1)}>Previous</button>
      <span>Page {page + 1} of {totalPages}</span>
      <button className="button quiet" type="button" disabled={page + 1 >= totalPages} onClick={() => onPage(page + 1)}>Next</button>
    </nav>}
  </>;
}

export function LiveCatalogBrowser({ term }: Readonly<{ term: string }>) {
  const [text, setText] = useState("");
  const [query, setQuery] = useState("");
  const [page, setPage] = useState(0);
  return <section className="live-catalog-browser" aria-labelledby="live-catalog-heading">
    <h3 id="live-catalog-heading">Live course catalog</h3>
    <p>Explore course conditions and predicted instructor quality. The semester plan below uses demo courses and times.</p>
    <form onSubmit={event => { event.preventDefault(); setQuery(text.trim()); setPage(0); }}>
      <label htmlFor="live-catalog-query">Search by course code or title</label>
      <div className="live-catalog-search"><input id="live-catalog-query" value={text} maxLength={100} onChange={event => setText(event.target.value)} />
        <button className="button primary" type="submit">Search live catalog</button></div>
    </form>
    <SearchResults key={`${term}|${query}|${page}`} term={term} query={query} page={page} onPage={setPage} />
  </section>;
}
