"""Predicts each instructor's quality and difficulty for a coming term from earlier ratings, and stores the result as one prediction run
(stdlib only; needs psql). Prints parameters, backtest errors and counts, never names.

    PGHOST=127.0.0.1 PGPORT=<port> PGUSER=<owner> PGDATABASE=<isolated database> \
      python3 -I data/professor-ratings/predict_ratings.py [--target-term-code 202710 --target-year 2027 --target-season SPRING] [--since 2019] [--dry-run]

Method (RECENCY_WEIGHTED_SHRUNK_MEAN), for every instructor who teaches in the target term and has at least one earlier rating:
  1. Each earlier rating (RMP and app, from semester_rating_input) gets the weight 0.5 ** (semesters before the target / half_life), so recent
     semesters count more and a professor who got better or worse moves the prediction.
  2. The weighted mean is pulled toward the overall mean of everyone's earlier ratings with the strength of `prior_strength` ratings, so a
     professor with two ratings is not predicted at their extreme.
  3. Per course the instructor teaches in the target term, ratings that name that course pull the instructor's value toward them with the
     strength `course_strength` (None: course labels are ignored).
The three parameters are chosen by a backtest: every past semester since --since is predicted from the semesters before it only, and the
setting with the lowest mean absolute error (quality + difficulty) wins. Simple baselines are reported next to it.
"""
import argparse
import hashlib
import json
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))   # python3 -I does not add the script directory

import import_professor_ratings as ipr

METHOD = "RECENCY_WEIGHTED_SHRUNK_MEAN"
SEASON_OFFSET = {"SPRING": 0.0, "SUMMER": 0.5, "FALL": 1.0}
GRID_HALF_LIFE = (1, 2, 4, 8, None)     # semesters; None = every earlier semester counts the same
GRID_PRIOR = (0, 2, 5, 10)              # pseudo-ratings at the overall mean
GRID_COURSE = (1, 3, 5, 10, None)       # pseudo-ratings at the instructor's value; None = ignore course labels
CONFIDENCE = ((3.0, "LOW"), (10.0, "MEDIUM"))   # effective ratings below 3 -> LOW, below 10 -> MEDIUM, else HIGH


def semester_index(year, season):
    return year * 2 + SEASON_OFFSET[season]


def weight(age, half_life):
    return 1.0 if half_life is None else 0.5 ** (age / half_life)


def mean(values):
    values = list(values)
    return sum(values) / len(values) if values else None


def overall_prior(rows, target_idx):
    earlier = [r for r in rows if r["x"] < target_idx]
    return {"q": mean(r["q"] for r in earlier), "d": mean(r["d"] for r in earlier)} if earlier else None


def predict_level(rows, target_idx, prior, half_life, prior_strength):
    """rows: one instructor's ratings. None when the instructor has no rating before the target."""
    earlier = [r for r in rows if r["x"] < target_idx]
    if not earlier or prior is None:
        return None
    w = [weight(target_idx - r["x"], half_life) for r in earlier]
    n_eff = sum(w)
    q = (sum(wi * r["q"] for wi, r in zip(w, earlier)) + prior_strength * prior["q"]) / (n_eff + prior_strength)
    d = (sum(wi * r["d"] for wi, r in zip(w, earlier)) + prior_strength * prior["d"]) / (n_eff + prior_strength)
    last = max(earlier, key=lambda r: r["x"])
    return {"q": q, "d": d, "n": len(earlier), "n_eff": n_eff, "last": last.get("t", "")}


def predict_course(rows, course, target_idx, level, half_life, course_strength):
    earlier = [r for r in rows if r["x"] < target_idx and r.get("c") == course]
    w = [weight(target_idx - r["x"], half_life) for r in earlier]
    n_eff = sum(w)
    if course_strength is None or not earlier:
        return {"q": level["q"], "d": level["d"], "n": len(earlier), "n_eff": n_eff}
    q = (sum(wi * r["q"] for wi, r in zip(w, earlier)) + course_strength * level["q"]) / (n_eff + course_strength)
    d = (sum(wi * r["d"] for wi, r in zip(w, earlier)) + course_strength * level["d"]) / (n_eff + course_strength)
    return {"q": q, "d": d, "n": len(earlier), "n_eff": n_eff}


def by_instructor(rows):
    grouped = {}
    for r in rows:
        grouped.setdefault(r["i"], []).append(r)
    return grouped


def backtest(rows, since_idx, half_life, prior_strength, course_strength=None, predictor=None):
    """Predicts every (instructor, past semester) from earlier semesters only. Also scores (instructor, course, semester) groups.
    predictor(rows, S, prior) may replace the level model (used for baselines)."""
    grouped = by_instructor(rows)
    semesters = sorted({r["x"] for r in rows if r["x"] >= since_idx})
    eq, ed, cq, cd = [], [], [], []
    for s in semesters:
        prior = overall_prior(rows, s)
        for iid in sorted(grouped):
            mine = grouped[iid]
            now = [r for r in mine if r["x"] == s]
            if not now:
                continue
            level = predictor(mine, s, prior) if predictor else predict_level(mine, s, prior, half_life, prior_strength)
            if level is None:
                continue
            eq.append(abs(level["q"] - mean(r["q"] for r in now)))
            ed.append(abs(level["d"] - mean(r["d"] for r in now)))
            if predictor:
                continue
            for course in sorted({r["c"] for r in now if r.get("c")}):
                actual = [r for r in now if r.get("c") == course]
                pred = predict_course(mine, course, s, level, half_life, course_strength)
                cq.append(abs(pred["q"] - mean(r["q"] for r in actual)))
                cd.append(abs(pred["d"] - mean(r["d"] for r in actual)))
    r4 = lambda v: None if v is None else round(v, 4)
    return {"pairs": len(eq), "maeQuality": r4(mean(eq)), "maeDifficulty": r4(mean(ed)),
            "coursePairs": len(cq), "courseMaeQuality": r4(mean(cq)), "courseMaeDifficulty": r4(mean(cd))}


def last_semester_baseline(rows, s, prior):
    earlier = [r for r in rows if r["x"] < s]
    if not earlier:
        return None
    latest = max(r["x"] for r in earlier)
    last = [r for r in earlier if r["x"] == latest]
    return {"q": mean(r["q"] for r in last), "d": mean(r["d"] for r in last)}


def overall_mean_baseline(rows, s, prior):
    return None if prior is None or not any(r["x"] < s for r in rows) else {"q": prior["q"], "d": prior["d"]}


def choose_parameters(rows, since_idx):
    """Grid search on the backtest. Ties keep the earlier grid entry, so the choice is deterministic."""
    best, grid = None, []
    for hl in GRID_HALF_LIFE:
        for k in GRID_PRIOR:
            res = backtest(rows, since_idx, hl, k)
            grid.append({"halfLife": hl, "priorStrength": k, **{key: res[key] for key in ("pairs", "maeQuality", "maeDifficulty")}})
            score = (res["maeQuality"] or 0) + (res["maeDifficulty"] or 0)
            if best is None or score < best[0] - 1e-12:
                best = (score, hl, k)
    _, hl, k = best
    best_c, course_grid = None, []
    for kc in GRID_COURSE:
        res = backtest(rows, since_idx, hl, k, kc)
        course_grid.append({"courseStrength": kc, **{key: res[key] for key in ("coursePairs", "courseMaeQuality", "courseMaeDifficulty")}})
        score = (res["courseMaeQuality"] or 0) + (res["courseMaeDifficulty"] or 0)
        if best_c is None or score < best_c[0] - 1e-12:
            best_c = (score, kc)
    kc = best_c[1]
    chosen = backtest(rows, since_idx, hl, k, kc)
    baselines = {
        "allTimeMean": backtest(rows, since_idx, None, 0),
        "lastSemesterOnly": backtest(rows, since_idx, None, 0, predictor=last_semester_baseline),
        "overallMean": backtest(rows, since_idx, None, 0, predictor=overall_mean_baseline),
    }
    for b in baselines.values():
        if b["coursePairs"] == 0:   # the baselines are scored per professor and semester only
            for key in ("coursePairs", "courseMaeQuality", "courseMaeDifficulty"):
                del b[key]
    return {"halfLife": hl, "priorStrength": k, "courseStrength": kc}, {"chosen": chosen, "baselines": baselines, "grid": grid, "courseGrid": course_grid}


def confidence(n_eff):
    for limit, label in CONFIDENCE:
        if n_eff < limit:
            return label
    return "HIGH"


def clamp(v):
    return round(min(5.0, max(1.0, v)), 2)


def build_predictions(rows, targets, target_idx, params):
    """targets: {instructor_id: [course_id, ...]} for the target term."""
    grouped = by_instructor(rows)
    prior = overall_prior(rows, target_idx)
    out = []
    for iid in sorted(targets):
        level = predict_level(grouped.get(iid, []), target_idx, prior, params["halfLife"], params["priorStrength"])
        if level is None:
            continue
        courses = []
        for course in sorted(set(targets[iid])):
            c = predict_course(grouped[iid], course, target_idx, level, params["halfLife"], params["courseStrength"])
            courses.append({"courseId": course, "quality": clamp(c["q"]), "difficulty": clamp(c["d"]), "courseRatings": c["n"], "effectiveCourseRatings": round(c["n_eff"], 2)})
        out.append({"instructorId": iid, "quality": clamp(level["q"]), "difficulty": clamp(level["d"]), "basisRatings": level["n"],
                    "effectiveRatings": round(level["n_eff"], 2), "lastRatedTerm": level["last"], "confidence": confidence(level["n_eff"]), "courses": courses})
    return out, prior


def load(target_term_code):
    rows = json.loads(ipr.run_psql(
        "SELECT coalesce(json_agg(json_build_object('i', instructor_id, 'x', semester_index, 'q', quality, 'd', difficulty, 'c', course_key, 't', term_label, 's', source) "
        "ORDER BY instructor_id, semester_index, source, quality, difficulty, course_key NULLS FIRST), '[]') FROM professor_ratings.semester_rating_input;") or "[]")
    pairs = json.loads(ipr.run_psql(
        "SELECT coalesce(json_agg(DISTINCT jsonb_build_object('i', s.instructor_id, 'c', o.course_id)), '[]') FROM professor_ratings.instructor_section s "
        "JOIN catalog_research.section cs ON cs.term_code = s.term_code AND cs.crn = s.crn "
        f"JOIN catalog_research.offering o ON o.term_code = cs.term_code AND o.offering_id = cs.offering_id WHERE s.term_code = {ipr.sql_literal(target_term_code)};") or "[]")
    targets = {}
    for p in pairs:
        targets.setdefault(p["i"], []).append(p["c"])
    for r in rows:
        r["x"], r["q"], r["d"] = float(r["x"]), float(r["q"]), float(r["d"])
    return rows, {k: sorted(v) for k, v in targets.items()}


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    ap.add_argument("--target-term-code", default="202710")
    ap.add_argument("--target-year", type=int, default=2027)
    ap.add_argument("--target-season", default="SPRING", choices=sorted(SEASON_OFFSET))
    ap.add_argument("--since", type=int, default=2019, help="first year of the backtest window")
    ap.add_argument("--dry-run", action="store_true")
    ap.add_argument("--allow-shared", action="store_true")
    ap.add_argument("--force", action="store_true", help="store a new run even if the inputs are unchanged")
    args = ap.parse_args(argv)
    try:
        ipr.guard(args)
        rows, targets = load(args.target_term_code)
        if not targets:
            raise ipr.ImportError_(f"nobody is assigned to a section of term {args.target_term_code}; run `instructors` first")
        target_idx = semester_index(args.target_year, args.target_season)
        params, backtest_report = choose_parameters(rows, semester_index(args.since, "SPRING"))
        predictions, prior = build_predictions(rows, targets, target_idx, params)
        params = {**params, "overallMeanQuality": round(prior["q"], 4), "overallMeanDifficulty": round(prior["d"], 4), "backtestSince": args.since,
                  "confidence": "effective ratings < 3 LOW, < 10 MEDIUM, else HIGH"}
        sha = hashlib.sha256(json.dumps([rows, targets, args.target_term_code, args.target_year, args.target_season, args.since], sort_keys=True).encode()).hexdigest()
        previous = ipr.run_psql(f"SELECT input_sha256 FROM professor_ratings.prediction_run WHERE target_term_code = {ipr.sql_literal(args.target_term_code)} ORDER BY run_id DESC LIMIT 1;")
        b = backtest_report
        print(f"chosen: half-life {params['halfLife']} semesters, prior strength {params['priorStrength']}, course strength {params['courseStrength']}")
        print(f"backtest since {args.since} ({b['chosen']['pairs']} professor-semesters): MAE quality {b['chosen']['maeQuality']}, difficulty {b['chosen']['maeDifficulty']}; "
              f"per course ({b['chosen']['coursePairs']}): quality {b['chosen']['courseMaeQuality']}, difficulty {b['chosen']['courseMaeDifficulty']}")
        for name, res in b["baselines"].items():
            print(f"  baseline {name}: MAE quality {res['maeQuality']}, difficulty {res['maeDifficulty']}")
        if previous == sha and not args.force:
            print("inputs unchanged since the latest run; nothing stored (use --force to store anyway)")
            return
        payload = {"targetTermCode": args.target_term_code, "targetYear": args.target_year, "targetSeason": args.target_season, "method": METHOD,
                   "parameters": params, "backtest": backtest_report, "ratingsUsed": sum(1 for r in rows if r["x"] < target_idx), "instructors": predictions}
        body = f"SELECT 'run=' || professor_ratings.record_predictions({ipr.sql_literal(json.dumps(payload, sort_keys=True))}::jsonb, '{sha}');"
        courses = sum(len(p["courses"]) for p in predictions)
        ipr.finish(args, body, f"{len(predictions)} of {len(targets)} instructors predicted, {courses} instructor-course predictions; input sha256 {sha[:16]}…")
    except (ipr.ImportError_, KeyError, ValueError, OSError) as exc:
        sys.exit("refused: " + str(exc))


if __name__ == "__main__":
    main()
