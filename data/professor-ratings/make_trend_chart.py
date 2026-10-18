"""Writes a local, self-contained HTML preview of professor rating trends (stdlib only; needs psql). A preview for the team, not the app's UI.

    PGHOST=127.0.0.1 PGPORT=<port> PGUSER=<role> PGDATABASE=<database> \
      python3 -I data/professor-ratings/make_trend_chart.py [--out ~/.registration-unified/rmp/trend-chart.html] [--since 2019]

Reads instructor_term_rating and instructor_year_rating (RMP and app ratings combined) and, when a prediction run exists, the predicted
next term from professor_prediction (drawn dashed). The page holds real instructor names, so it is
written OUTSIDE the repository (a path inside it is refused) and must not be published. No external scripts or fonts: it opens offline.
"""
import argparse
import json
import subprocess
import sys
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]
DEFAULT_OUT = Path.home() / ".registration-unified" / "rmp" / "trend-chart.html"

QUERY = """
SELECT coalesce(json_agg(p ORDER BY p.name), '[]') FROM (
	SELECT i.instructor_id AS id, i.banner_name AS name,
		(SELECT json_agg(json_build_object('label', t.term_label, 'order', t.term_order, 'n', t.num_ratings, 'rmp', t.num_rmp_ratings, 'app', t.num_app_ratings,
				'q', t.avg_quality, 'd', t.avg_difficulty, 'wta', t.would_take_again_pct) ORDER BY t.term_order)
			FROM professor_ratings.instructor_term_rating t WHERE t.instructor_id = i.instructor_id AND t.term_year >= {since}) AS terms,
		(SELECT json_agg(json_build_object('label', y.term_year::text, 'order', y.term_year, 'n', y.num_ratings, 'rmp', y.num_rmp_ratings, 'app', y.num_app_ratings,
				'q', y.avg_quality, 'd', y.avg_difficulty, 'wta', y.would_take_again_pct) ORDER BY y.term_year)
			FROM professor_ratings.instructor_year_rating y WHERE y.instructor_id = i.instructor_id AND y.term_year >= {since}) AS years,
		(SELECT json_build_object('label', p.target_term_label, 'q', p.pred_quality, 'd', p.pred_difficulty, 'confidence', p.confidence, 'basis', p.basis_ratings)
			FROM professor_ratings.professor_prediction p WHERE p.instructor_id = i.instructor_id ORDER BY p.target_term_code DESC LIMIT 1) AS prediction
	FROM professor_ratings.instructor i
	WHERE EXISTS (SELECT 1 FROM professor_ratings.instructor_term_rating t WHERE t.instructor_id = i.instructor_id AND t.term_year >= {since})
) p;
"""

PAGE = r"""<!doctype html>
<html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1">
<title>Professor Rating Trends</title>
<style>
:root { --bg:#f7f7f5; --panel:#fff; --ink:#1d1d1f; --muted:#6b6b70; --grid:#e3e3e0; --q:#2563eb; --d:#d97706; }
@media (prefers-color-scheme: dark) { :root { --bg:#141416; --panel:#1e1e21; --ink:#f2f2f3; --muted:#a0a0a8; --grid:#34343a; --q:#60a5fa; --d:#fbbf24; } }
* { box-sizing:border-box; }
body { margin:0; background:var(--bg); color:var(--ink); font:15px/1.45 system-ui, -apple-system, "Segoe UI", sans-serif; }
main { max-width:980px; margin:0 auto; padding:24px 16px 40px; }
h1 { font-size:22px; margin:0 0 4px; } .sub { color:var(--muted); margin:0 0 18px; font-size:13px; }
.controls { display:flex; flex-wrap:wrap; gap:10px; align-items:center; margin-bottom:14px; }
select, button { font:inherit; color:var(--ink); background:var(--panel); border:1px solid var(--grid); border-radius:8px; padding:6px 10px; }
button[aria-pressed="true"] { border-color:var(--q); color:var(--q); }
.panel { background:var(--panel); border:1px solid var(--grid); border-radius:12px; padding:14px; }
svg { width:100%; height:auto; display:block; } .axis { fill:var(--muted); font-size:11px; } .grid { stroke:var(--grid); }
.legend { display:flex; gap:16px; flex-wrap:wrap; color:var(--muted); font-size:13px; margin-top:8px; }
.sw { display:inline-block; width:12px; height:3px; vertical-align:middle; margin-right:6px; }
table { width:100%; border-collapse:collapse; margin-top:14px; font-size:13px; } th, td { text-align:right; padding:5px 8px; border-bottom:1px solid var(--grid); }
th:first-child, td:first-child { text-align:left; } th { color:var(--muted); font-weight:600; }
.up { color:#16a34a; } .down { color:#dc2626; } .note { color:var(--muted); font-size:12px; margin-top:12px; }
.table-wrap { overflow-x:auto; }
</style></head><body><main>
<h1>Professor rating trends</h1>
<p class="sub">Rate My Professors + ratings submitted in the app, per semester. Generated __GENERATED__. Local preview, not for publishing.</p>
<div class="controls">
  <select id="prof" aria-label="Professor"></select>
  <button id="bySem" aria-pressed="true">By semester</button><button id="byYear" aria-pressed="false">By year</button>
</div>
<div class="panel"><svg id="chart" viewBox="0 0 900 340" role="img" aria-label="Rating trend"></svg>
<div class="legend"><span><span class="sw" style="background:var(--q)"></span>Quality (1-5)</span><span><span class="sw" style="background:var(--d)"></span>Difficulty (1-5)</span>
<span>Dot size = number of ratings; hollow dot = fewer than 3; dashed = predicted</span></div>
<div class="table-wrap"><table id="tbl"></table></div></div>
<p class="note">RMP semesters come from the date a review was posted (approximate); app ratings use the semester the student chose. "Change" compares with the previous semester that has ratings. The predicted point is an estimate from earlier ratings (recent semesters weigh more), not a rating.</p>
</main>
<script>
const DATA = __DATA__;
const sel = document.getElementById('prof'), svg = document.getElementById('chart'), tbl = document.getElementById('tbl');
let mode = 'terms';
DATA.forEach((p, i) => { const o = document.createElement('option'); o.value = i; o.textContent = p.name; sel.appendChild(o); });
const NS = 'http://www.w3.org/2000/svg';
function el(tag, attrs, text) { const e = document.createElementNS(NS, tag); for (const k in attrs) e.setAttribute(k, attrs[k]); if (text != null) e.textContent = text; return e; }
function draw() {
  const prof = DATA[sel.value] || {}, past = prof[mode] || [], pred = prof.prediction;
  const predRow = pred ? {label: (mode === 'terms' ? pred.label : pred.label.split(' ')[0]) + ' (predicted)', q: pred.q, d: pred.d, n: 0, predicted: true, confidence: pred.confidence, basis: pred.basis} : null;
  const rows = predRow ? past.concat([predRow]) : past;
  svg.replaceChildren();
  const L = 44, R = 16, T = 14, B = 46, W = 900 - L - R, H = 340 - T - B;
  const x = i => L + (rows.length === 1 ? W / 2 : i * W / (rows.length - 1)), y = v => T + H - (v - 1) / 4 * H;
  for (let v = 1; v <= 5; v++) { svg.appendChild(el('line', {x1: L, x2: L + W, y1: y(v), y2: y(v), class: 'grid'})); svg.appendChild(el('text', {x: L - 8, y: y(v) + 4, 'text-anchor': 'end', class: 'axis'}, v)); }
  const step = Math.max(1, Math.ceil(rows.length / 12));
  rows.forEach((r, i) => { if (i % step === 0 || i === rows.length - 1) svg.appendChild(el('text', {x: x(i), y: T + H + 18, 'text-anchor': 'middle', class: 'axis'}, r.label)); });
  [['q', 'var(--q)'], ['d', 'var(--d)']].forEach(([key, color]) => {
    const pts = rows.map((r, i) => r[key] == null || r.predicted ? null : [x(i), y(Number(r[key]))]).filter(Boolean);
    if (pts.length > 1) svg.appendChild(el('polyline', {points: pts.map(p => p.join(',')).join(' '), fill: 'none', stroke: color, 'stroke-width': 2, 'stroke-opacity': key === 'd' ? 0.7 : 1}));
    if (predRow && pts.length) {
      const last = pts[pts.length - 1], px = x(rows.length - 1), py = y(Number(predRow[key]));
      svg.appendChild(el('line', {x1: last[0], y1: last[1], x2: px, y2: py, stroke: color, 'stroke-width': 2, 'stroke-dasharray': '5 4', 'stroke-opacity': key === 'd' ? 0.7 : 1}));
    }
    rows.forEach((r, i) => {
      if (r[key] == null) return;
      if (r.predicted) {
        const c = el('circle', {cx: x(i), cy: y(Number(r[key])), r: 6, fill: 'var(--panel)', stroke: color, 'stroke-width': 2, 'stroke-dasharray': '3 2'});
        c.appendChild(el('title', {}, `${r.label}: ${key === 'q' ? 'quality' : 'difficulty'} ${r[key]} (confidence ${r.confidence}, from ${r.basis} earlier ratings)`));
        svg.appendChild(c);
        return;
      }
      const rad = Math.min(9, 3 + Math.sqrt(r.n) * 1.4), low = r.n < 3;
      const c = el('circle', {cx: x(i), cy: y(Number(r[key])), r: rad, fill: low ? 'var(--panel)' : color, stroke: color, 'stroke-width': 2});
      c.appendChild(el('title', {}, `${r.label}: ${key === 'q' ? 'quality' : 'difficulty'} ${r[key]} from ${r.n} rating(s) (RMP ${r.rmp}, app ${r.app})`));
      svg.appendChild(c);
    });
  });
  let html = '<tr><th>' + (mode === 'terms' ? 'Semester' : 'Year') + '</th><th>Ratings</th><th>RMP / app</th><th>Quality</th><th>Change</th><th>Difficulty</th><th>Would take again</th></tr>';
  rows.forEach((r, i) => {
    const prev = i ? rows[i - 1].q : null, ch = prev == null || r.q == null ? null : (Number(r.q) - Number(prev));
    const chTxt = ch == null ? '' : `<span class="${ch > 0 ? 'up' : ch < 0 ? 'down' : ''}">${ch > 0 ? '+' : ''}${ch.toFixed(2)}</span>`;
    if (r.predicted) { html += `<tr><td><em>${r.label}</em></td><td colspan="2">confidence ${r.confidence}</td><td><em>${r.q}</em></td><td>${chTxt}</td><td><em>${r.d}</em></td><td></td></tr>`; return; }
    html += `<tr><td>${r.label}</td><td>${r.n}</td><td>${r.rmp} / ${r.app}</td><td>${r.q ?? ''}</td><td>${chTxt}</td><td>${r.d ?? ''}</td><td>${r.wta == null ? '' : r.wta + '%'}</td></tr>`;
  });
  tbl.innerHTML = html;
}
function setMode(m) { mode = m; document.getElementById('bySem').setAttribute('aria-pressed', m === 'terms'); document.getElementById('byYear').setAttribute('aria-pressed', m === 'years'); draw(); }
document.getElementById('bySem').onclick = () => setMode('terms');
document.getElementById('byYear').onclick = () => setMode('years');
sel.onchange = draw;
draw();
</script></body></html>
"""


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    ap.add_argument("--out", default=str(DEFAULT_OUT))
    ap.add_argument("--since", type=int, default=2019)
    args = ap.parse_args(argv)
    out = Path(args.out).expanduser().resolve()
    if REPO in out.parents or out == REPO:
        sys.exit("refused: the chart holds real instructor names; write it outside the repository")
    result = subprocess.run(["psql", "-X", "-q", "-v", "ON_ERROR_STOP=1", "-At"], input=QUERY.format(since=int(args.since)), capture_output=True, text=True, timeout=120)
    if result.returncode != 0:
        sys.exit("psql failed: " + (result.stdout + result.stderr).strip()[:800])
    data = json.loads(result.stdout.strip() or "[]")
    for p in data:
        p["terms"], p["years"] = p["terms"] or [], p["years"] or []
        if p.get("prediction"):
            p["prediction"]["q"], p["prediction"]["d"] = float(p["prediction"]["q"]), float(p["prediction"]["d"])
    from datetime import date
    # json.dumps escapes nothing HTML-specific: neutralise "</" so a name can never close the script element.
    payload = json.dumps(data, ensure_ascii=False).replace("</", "<\\/")
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text(PAGE.replace("__DATA__", payload).replace("__GENERATED__", date.today().isoformat()), encoding="utf-8")
    print(f"wrote {out} ({len(data)} professors with ratings since {args.since})")


if __name__ == "__main__":
    main()
