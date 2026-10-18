"""Builds the import payload of catalog_csb.import_csb from the collected CSB research (stdlib only; no database, no network).

    python3 -I data/csb-research/build_csb_dataset.py --grids ~/.registration-csb/grids --packs ~/.registration-csb/packs \
        --catalog-cache ~/.registration-csb/catalog --out ~/.registration-csb/csb_import_202710.json

Inputs are LOCAL-ONLY (real CRNs, instructor names, catalog prose); the output is too, and the repository never holds it. The program
structure comes from the committed csb_program_2026_27.json. The output file's SHA-256 is what import_csb.py records in import_run.
"""
import argparse
import hashlib
import json
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
import catalog_courses  # noqa: E402
import csb_dataset as ds  # noqa: E402


def collect(grids_dir, packs_dir):
    grid_rows, searches = ds.load_grids(grids_dir)
    caps, pack_rows, pack_searches = ds.load_packs(packs_dir)
    for crn, row in pack_rows.items():
        if crn in grid_rows and grid_rows[crn] != row:
            raise ds.DatasetError(f"CRN {crn}: the grid file and the pack disagree")
        grid_rows[crn] = row
    seen = {(s["subject"], s["courseNumber"]) for s in searches}
    for s in pack_searches:
        if (s["subject"], s["courseNumber"]) not in seen:
            searches.append(s)
            seen.add((s["subject"], s["courseNumber"]))
    return grid_rows, searches, caps


def build(args):
    program = json.loads(Path(args.program).read_text(encoding="utf-8"))
    grid_rows, searches, caps = collect(args.grids, args.packs)
    offered = {ds.course_code(r["subject"], r["number"])[2] for r in grid_rows.values()}
    wanted = ds.program_ids(program) | offered
    found, missing = catalog_courses.parse_cache(args.catalog_cache, wanted)
    sources = {
        "catalog": "CATALOG_2026_27",
        "banner": "BANNER_202710",
        "rows": [
            {"key": "CATALOG_2026_27", "level": "PUBLIC_CATALOG", "locator": program["sources"]["CATALOG"]["locator"], "observedOn": "2026-10-18",
             "authority": "PRIMARY", "redistribution": "PUBLIC"},
            {"key": "CATALOG_COURSE_PAGES", "level": "PUBLIC_CATALOG", "locator": "https://catalog.lehigh.edu/coursesprogramsandcurricula/ department course pages (2026-27)",
             "observedOn": "2026-10-18", "authority": "PRIMARY", "redistribution": "PUBLIC"},
            {"key": "CSB_SITE", "level": "PROGRAM_WEBSITE", "locator": "https://csb.lehigh.edu/ (curriculum, computer-science-business-electives, csb-tracks)", "observedOn": "2026-10-18",
             "authority": "SECONDARY_PROGRAM_PAGE", "redistribution": "PUBLIC"},
            {"key": "CSE_DEPT_ELECTIVE_PAGE", "level": "DEPARTMENT_WEBSITE", "locator": program["sources"]["CSE_DEPT_ELECTIVE_PAGE"]["locator"], "observedOn": "2026-10-18",
             "authority": "SECONDARY_DEPARTMENT_PAGE_POSSIBLY_STALE", "redistribution": "PUBLIC"},
            {"key": "BANNER_202710", "level": "BANNER_AUTHENTICATED",
             "locator": "Lehigh Banner Browse Classes, term 202710 (results grid and Class Details popups), read in the project owner's authenticated session on 2026-10-18",
             "observedOn": "2026-10-18", "authority": "PRIMARY_FOR_SECTIONS", "redistribution": "NOT_VERIFIED_LOCAL_ONLY"},
        ],
    }
    payload = ds.build_payload(program, {"courses": found, "not_found": missing}, grid_rows, searches, caps, {"catalog": "CATALOG_2026_27", "banner": "BANNER_202710", "rows": sources["rows"]})
    for row in payload["catalogCourses"]:
        if row["catalogStatus"] == "CATALOG_PAGE_READ":
            row["sourceKey"] = "CATALOG_COURSE_PAGES"
    return payload, missing


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    ap.add_argument("--grids", required=True)
    ap.add_argument("--packs", required=True)
    ap.add_argument("--catalog-cache", required=True)
    ap.add_argument("--program", default=str(HERE / "csb_program_2026_27.json"))
    ap.add_argument("--out", required=True)
    args = ap.parse_args(argv)
    payload, missing = build(args)
    text = json.dumps(payload, sort_keys=True, indent=1, ensure_ascii=False)
    Path(args.out).write_text(text, encoding="utf-8")
    digest = hashlib.sha256(text.encode("utf-8")).hexdigest()
    print(f"wrote {args.out}  sha256 {digest}")
    print(f"catalog courses {len(payload['catalogCourses'])} (not found on the catalog pages: {missing}); offerings {len(payload['offerings'])}; sections {len(payload['sections'])}; "
          f"searches {len(payload['searches'])}; comparisons {len(payload['comparisons'])}; groups {len(payload['groups'])}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
