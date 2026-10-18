"use client";
import { useRef, useState, type KeyboardEvent, type PointerEvent } from "react";
import type { YearRating } from "@/lib/api/types";
import { qualityTrend } from "@/lib/ratingTrend";

/** One professor's stored yearly ratings, quality and difficulty on the shared 1-5 scale, with this term's prediction as a dashed last step. */
interface Point { year: number; quality: number | null; difficulty: number | null; ratings: number | null; lowSample: boolean; predicted: boolean }

const W = 560, H = 210, LEFT = 34, RIGHT = 96, TOP = 14, BOTTOM = 28;
const SERIES = [{ key: "quality", label: "Quality", className: "is-quality" }, { key: "difficulty", label: "Difficulty", className: "is-difficulty" }] as const;
const fmt = (value: number | null) => value === null ? "—" : value.toFixed(2);
const range = ([from, to]: [number, number]) => from === to ? String(from) : `${from}–${to}`;

export function RatingHistoryChart({ name, years, predicted, predictedYear, predictedLabel }: Readonly<{
  name: string; years: YearRating[]; predicted: { quality: number | null; difficulty: number | null } | null; predictedYear: number; predictedLabel: string;
}>) {
  const [active, setActive] = useState<number | null>(null);
  const tooltip = useRef<HTMLDivElement>(null);
  const history: Point[] = [...years].sort((a, b) => a.year - b.year).map(y => ({ year: y.year, quality: y.quality, difficulty: y.difficulty, ratings: y.ratings, lowSample: y.lowSample, predicted: false }));
  const hasPrediction = Boolean(predicted && (predicted.quality !== null || predicted.difficulty !== null) && history.length && predictedYear > history[history.length - 1].year);
  const points: Point[] = hasPrediction && predicted ? [...history, { year: predictedYear, quality: predicted.quality, difficulty: predicted.difficulty, ratings: null, lowSample: false, predicted: true }] : history;
  if (!points.length) return null;
  const minYear = points[0].year, maxYear = points[points.length - 1].year;
  const span = Math.max(1, maxYear - minYear);
  const x = (year: number) => points.length === 1 ? LEFT + (W - LEFT - RIGHT) / 2 : LEFT + (year - minYear) / span * (W - LEFT - RIGHT);
  const y = (value: number) => TOP + (5 - value) / 4 * (H - TOP - BOTTOM);
  const step = Math.max(1, Math.ceil(span / 7));
  const ticks = Array.from({ length: Math.floor(span / step) + 1 }, (_, i) => minYear + i * step).filter(t => t <= maxYear);
  if (!ticks.includes(maxYear) && points.length > 1) ticks.push(maxYear);
  const path = (key: "quality" | "difficulty") => history.filter(p => p[key] !== null).map((p, i) => `${i ? "L" : "M"}${x(p.year).toFixed(1)},${y(p[key] as number).toFixed(1)}`).join(" ");
  const last = history[history.length - 1];
  const end = points[points.length - 1];
  // Direct labels at the line ends, nudged apart when the two ends nearly meet.
  const labelY = { quality: y(end.quality ?? last.quality ?? 3), difficulty: y(end.difficulty ?? last.difficulty ?? 3) };
  if (Math.abs(labelY.quality - labelY.difficulty) < 14) {
    const mid = (labelY.quality + labelY.difficulty) / 2, up = labelY.quality <= labelY.difficulty ? "quality" : "difficulty";
    labelY[up] = mid - 7; labelY[up === "quality" ? "difficulty" : "quality"] = mid + 7;
  }
  const trend = qualityTrend(years);
  const show = (index: number | null) => {
    setActive(index);
    if (index === null || !tooltip.current) return;
    const left = x(points[index].year) / W * 100;
    tooltip.current.style.setProperty("--tip-left", `${left}%`);
    tooltip.current.dataset.side = left > 60 ? "left" : "right";
  };
  const nearest = (event: PointerEvent<SVGRectElement>) => {
    const box = event.currentTarget.ownerSVGElement?.getBoundingClientRect();
    if (!box) return;
    const px = (event.clientX - box.left) / box.width * W;
    let best = 0;
    points.forEach((p, i) => { if (Math.abs(x(p.year) - px) < Math.abs(x(points[best].year) - px)) best = i; });
    show(best);
  };
  const keys = (event: KeyboardEvent<HTMLDivElement>) => {
    if (event.key === "ArrowRight" || event.key === "ArrowLeft") {
      event.preventDefault();
      const next = active === null ? (event.key === "ArrowRight" ? 0 : points.length - 1) : Math.min(points.length - 1, Math.max(0, active + (event.key === "ArrowRight" ? 1 : -1)));
      show(next);
    } else if (event.key === "Escape") show(null);
  };
  const point = active === null ? null : points[active];
  return <figure className="rating-chart">
    <figcaption><strong>{name}</strong>
      {trend ? <span>Quality {trend.direction === "steady" ? "steady" : trend.direction}: {trend.recentQuality.toFixed(2)} in {range(trend.recentYears)} vs {trend.earlierQuality.toFixed(2)} in {range(trend.earlierYears)} ({trend.change > 0 ? "+" : ""}{trend.change.toFixed(2)}, {trend.ratings} ratings)</span>
        : <span>{history.length} rated {history.length === 1 ? "year" : "years"}: too few to call a trend.</span>}
    </figcaption>
    <ul className="rating-chart-legend" aria-hidden="true">{SERIES.map(s => <li key={s.key} className={s.className}><i></i>{s.label}</li>)}{hasPrediction && <li className="is-predicted"><i></i>{predictedLabel}</li>}<li className="is-low"><i></i>Few ratings</li></ul>
    <div className="rating-chart-plot" tabIndex={0} role="img" onKeyDown={keys} onBlur={() => show(null)}
      aria-label={`${name}: yearly quality and difficulty from ${minYear} to ${last.year}, 1 to 5 scale. ${trend ? `Quality ${trend.direction}.` : ""} Use the left and right arrow keys to read each year; the table below lists every value.`}>
      <svg viewBox={`0 0 ${W} ${H}`} aria-hidden="true" focusable="false">
        {[1, 2, 3, 4, 5].map(v => <g key={v}><line className="rating-chart-grid" x1={LEFT} x2={W - RIGHT} y1={y(v)} y2={y(v)} /><text className="rating-chart-axis" x={LEFT - 8} y={y(v) + 4} textAnchor="end">{v}</text></g>)}
        {ticks.map(t => <text key={t} className="rating-chart-axis" x={x(t)} y={H - 8} textAnchor="middle">{t === predictedYear && hasPrediction ? `${t}*` : t}</text>)}
        {point && <line className="rating-chart-crosshair" x1={x(point.year)} x2={x(point.year)} y1={TOP} y2={H - BOTTOM} />}
        {SERIES.map(s => <g key={s.key} className={`rating-chart-series ${s.className}`}>
          <path d={path(s.key)} />
          {hasPrediction && end[s.key] !== null && last[s.key] !== null && <path className="is-predicted" d={`M${x(last.year)},${y(last[s.key] as number)} L${x(end.year)},${y(end[s.key] as number)}`} />}
          {points.map(p => p[s.key] === null ? null : <circle key={p.year} className={p.predicted ? "is-predicted" : p.lowSample ? "is-low" : undefined} cx={x(p.year)} cy={y(p[s.key] as number)} r={4} />)}
          <text className="rating-chart-label" x={x(end.year) + 9} y={labelY[s.key] + 4}>{s.label} {fmt(end[s.key] ?? last[s.key])}</text>
        </g>)}
        <rect className="rating-chart-hit" x={LEFT - 12} y={0} width={W - LEFT - RIGHT + 24} height={H} onPointerMove={nearest} onPointerLeave={() => show(null)} />
      </svg>
      <div className="rating-chart-tooltip" ref={tooltip} hidden={!point} role="status">
        {point && <><strong>{point.predicted ? predictedLabel : point.year}</strong>
          <span className="is-quality"><i></i><b>{fmt(point.quality)}</b> quality</span>
          <span className="is-difficulty"><i></i><b>{fmt(point.difficulty)}</b> difficulty</span>
          <small>{point.predicted ? "Predicted from earlier ratings" : `${point.ratings} rating${point.ratings === 1 ? "" : "s"}${point.lowSample ? " · few ratings, read with care" : ""}`}</small></>}
      </div>
    </div>
    <details className="rating-chart-table"><summary>Show as a table</summary>
      <table><thead><tr><th scope="col">Year</th><th scope="col">Quality (1–5)</th><th scope="col">Difficulty (1–5)</th><th scope="col">Ratings</th></tr></thead>
        <tbody>{points.map(p => <tr key={p.year}><th scope="row">{p.predicted ? predictedLabel : p.year}</th><td>{fmt(p.quality)}</td><td>{fmt(p.difficulty)}</td><td>{p.predicted ? "prediction" : `${p.ratings}${p.lowSample ? " (few)" : ""}`}</td></tr>)}</tbody></table>
    </details>
  </figure>;
}
