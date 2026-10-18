"""Fetches the PUBLIC 2026-27 Lehigh catalog department pages and parses their course blocks (stdlib only).

    python3 -I data/csb-research/catalog_courses.py fetch  --cache-dir <dir>                      # download the department pages (1 s apart)
    python3 -I data/csb-research/catalog_courses.py parse  --cache-dir <dir> --out <courses.json>  # parse the cached pages for the CSB course set

The catalog is public, but its course descriptions are Lehigh's text: the parsed file (which includes them) is written OUTSIDE the repository
and is not committed. What the repository keeps are the structured facts (code, title, credits, attributes, prerequisite statements) in
csb_import_*.json builds that the dataset builder derives from it, and the tests use a synthetic page (fixtures/catalog_page_synthetic.html).

A course block looks like
    <p class="courseblocktitle"><strong><span>CHM&#160;030  </span><span>Introduction ...</span> 0,4 <span>Credits</span></strong></p>
    <p class="courseblockdesc"> description ... <b>Prerequisites:</b> ... <b>Can be taken Concurrently:</b> ... <b>Attribute/Distribution:</b> LS, NS, NW, Q</p>
Credits are kept as the catalog prints them ('0,4' = a lecture of 0 or 4 credits as the catalog lists it, '1-3' = variable); nothing is interpreted.
"""
import argparse
import html
import json
import re
import subprocess
import sys
import time
from pathlib import Path

BASE = "https://catalog.lehigh.edu/coursesprogramsandcurricula/"
# subject -> page path under BASE. A page may list several subjects (e.g. earth and environmental sciences).
PAGES = {
    "ACCT": "businessandeconomics/accounting", "BUS": "businessandeconomics/business", "ECO": "businessandeconomics/economics",
    "FIN": "businessandeconomics/finance", "LAW": "businessandeconomics/law", "MGT": "businessandeconomics/management",
    "MKT": "businessandeconomics/marketing", "SCM": "businessandeconomics/supplychainmanagement",
    "BIS": "businessandeconomics/businessinformationsystems", "ENTP": "businessandeconomics/entrepreneurship",
    "MATH": "artsandsciences/mathematics", "PHY": "artsandsciences/physics", "CHM": "artsandsciences/chemistry",
    "BIOS": "artsandsciences/biologicalsciences", "ASTR": "artsandsciences/astronomyandastrophysics",
    "EES": "artsandsciences/earthandenvironmentalsciences", "WRT": "artsandsciences/english",
    "CSE": "engineeringandappliedscience/computerscienceandengineering",
    "CSB": "interdisciplinaryundergraduatestudy/computerscienceandbusinessprogram",
}
USER_AGENT = "Mozilla/5.0 (compatible; registration-helper-research; read-only)"

BLOCK = re.compile(r'<div class="courseblock">(.*?)</div>(?=\s*<div class="courseblock">|\s*</div>|\s*<div|\s*$)', re.S)
TITLE = re.compile(r'<p class="courseblocktitle"><strong>(.*?)</strong></p>', re.S)
SPAN = re.compile(r"<span class='cdspacing'>(.*?)</span>", re.S)
DESC = re.compile(r'<p class="courseblockdesc">(.*?)</p>', re.S)
LABELED = re.compile(r"<b>([^<:]+):</b>\s*(.*?)(?=<br\s*/?>|$)", re.S)
CODE = re.compile(r"^([A-Z]{2,5})\s+(\d{3})(?:\s*\((.*)\))?$")   # 'CSE 252 (EMC 252)': the parenthesis lists catalog aliases (cross-listed numbers)


def text_of(fragment):
    """HTML fragment -> plain text with single spaces (entities decoded, non-breaking spaces normalized)."""
    plain = re.sub(r"<[^>]+>", "", fragment)
    return re.sub(r"\s+", " ", html.unescape(plain).replace("\xa0", " ")).strip()


def parse_page(page_html):
    """Returns a list of course dicts in page order. Credits are the printed text; a block without a recognizable code is skipped."""
    # keep only the course inventory tab when the page has one (department pages also hold the program text)
    start = page_html.find('id="courseinventorycontainer"')
    body = page_html[start:] if start > 0 else page_html
    courses = []
    for block in BLOCK.findall(body):
        title = TITLE.search(block)
        if not title:
            continue
        spans = SPAN.findall(title.group(1))
        if len(spans) < 2:
            continue
        code = text_of(spans[0])
        m = CODE.match(code)
        if not m:
            continue
        name = text_of(spans[1])
        after = re.sub(r"<[^>]+>", " ", title.group(1).split("</span>", 2)[-1])
        credits = re.sub(r"\s+", " ", html.unescape(after)).replace("Credits", "").replace("Credit", "").strip()
        desc = DESC.search(block)
        desc_html = desc.group(1) if desc else ""
        labeled = {}
        for label, value in LABELED.findall(desc_html):
            labeled[label.strip()] = text_of(value)
        description = text_of(re.split(r"<b>", desc_html, maxsplit=1)[0])
        attrs = [a.strip() for a in labeled.get("Attribute/Distribution", "").split(",") if a.strip()]
        courses.append({
            "course_id": m.group(1) + "-" + m.group(2), "subject": m.group(1), "course_number": m.group(2), "title": name,
            "catalog_aliases": [a.strip().replace(" ", "-") for a in re.split(r"[,/]", m.group(3)) if a.strip()] if m.group(3) else [],
            "credits_text": credits, "description": description,
            "prerequisites_text": labeled.get("Prerequisites"), "concurrent_text": labeled.get("Can be taken Concurrently"),
            "corequisites_text": labeled.get("Corequisites"), "attributes": attrs,
            "repeat_status": labeled.get("Repeat Status"),
            "other_labels": {k: v for k, v in labeled.items() if k not in ("Prerequisites", "Can be taken Concurrently", "Corequisites", "Attribute/Distribution", "Repeat Status")},
        })
    return courses


def fetch(cache_dir, subjects=None):
    cache = Path(cache_dir)
    cache.mkdir(parents=True, exist_ok=True)
    paths = sorted({PAGES[s] for s in (subjects or PAGES)})
    for path in paths:
        target = cache / (path.replace("/", "__") + ".html")
        # curl rather than urllib: the python.org builds ship without the system CA bundle, and certificate checks must stay on
        subprocess.run(["curl", "--silent", "--show-error", "--fail", "--location", "--max-time", "60", "--user-agent", USER_AGENT,
                        "--output", str(target), BASE + path + "/"], check=True)
        print(f"fetched {path} -> {target.name} ({target.stat().st_size} bytes)")
        time.sleep(1)


def parse_cache(cache_dir, wanted):
    """wanted = set of course ids. Returns (courses by id, ids not found on any page)."""
    found = {}
    for subject, path in sorted(PAGES.items()):
        page = Path(cache_dir) / (path.replace("/", "__") + ".html")
        if not page.exists():
            continue
        for course in parse_page(page.read_text(encoding="utf-8", errors="replace")):
            if course["course_id"] in wanted:
                found[course["course_id"]] = course
    return found, sorted(wanted - set(found))


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = parser.add_subparsers(dest="command", required=True)
    f = sub.add_parser("fetch")
    f.add_argument("--cache-dir", required=True)
    p = sub.add_parser("parse")
    p.add_argument("--cache-dir", required=True)
    p.add_argument("--wanted", required=True, help="JSON file: a list of course ids, or an object with a 'courses' list")
    p.add_argument("--out", required=True)
    args = parser.parse_args(argv)
    if args.command == "fetch":
        fetch(args.cache_dir)
        return 0
    data = json.loads(Path(args.wanted).read_text(encoding="utf-8"))
    wanted = set(data["courses"] if isinstance(data, dict) else data)
    found, missing = parse_cache(args.cache_dir, wanted)
    Path(args.out).write_text(json.dumps({"courses": found, "not_found": missing}, indent=1, sort_keys=True), encoding="utf-8")
    print(f"parsed {len(found)} of {len(wanted)} wanted courses; not found on the cached pages: {missing}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
