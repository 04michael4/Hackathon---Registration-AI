"""Scans files for restricted CSB research data before they are committed (stdlib only). Prints file:line and the KIND of hit, never the hit itself.

    python3 -I data/csb-research/check_no_restricted_data.py --payload ~/.registration-csb/csb_import_202710.json [--grids DIR] [--packs DIR] [--catalog-cache DIR] [PATH ...]

With no PATH it scans every file git would commit (tracked-and-modified plus untracked, minus ignored). It loads the LOCAL-ONLY inputs and looks for:
  CRN            a real Banner CRN (any section, linked or cross-listed CRN of the payload) as a whole number
  INSTRUCTOR     a real instructor's last name, or "Last, First", as a whole word
  COPIED_TEXT    a run of 8 consecutive words that also occurs in a Class Details text, Banner restriction text or catalog description of the payload
  GRID_ROW       a verbatim grid row (CRN-bearing JSON or a pipe/tab row containing a real CRN)
  CREDENTIAL     something shaped like a password, token, cookie or key
  LOCAL_PATH     an absolute path into a home directory
Exit status 1 when anything is found. The payload is only read; nothing is written.
"""
import argparse
import html
import json
import re
import subprocess
import sys
from pathlib import Path

WORD = re.compile(r"[A-Za-z0-9']+")
CREDENTIAL = [
    re.compile(r"(?i)\b(password|passwd|secret|api[_-]?key|access[_-]?token|auth[_-]?token|session[_-]?id|jsessionid|cookie)\b\s*[:=]\s*['\"]?[A-Za-z0-9+/_\-]{8,}"),
    re.compile(r"\bsk-[A-Za-z0-9_\-]{16,}"),
    re.compile(r"(?i)\bBearer\s+[A-Za-z0-9._\-]{16,}"),
    re.compile(r"-----BEGIN [A-Z ]*PRIVATE KEY-----"),
]
LOCAL_PATH = re.compile(r"/(Users|home)/[A-Za-z0-9._-]+/")
# a run of 8 words is not reported when it is mostly course numbers (a list of course codes is a fact, not prose) or is a proper name of a college
NUMERIC_RUN = 3
PROPER_NAMES = ("p c rossin college of engineering and applied science",)
SKIP_SUFFIX = {".png", ".jpg", ".jpeg", ".gif", ".pdf", ".ico", ".woff", ".woff2", ".zip", ".dump"}
# short words that are also surnames or ordinary words: matched only as "Last, First", never alone
COMMON_SURNAMES = {"long", "young", "king", "lee", "white", "black", "brown", "green", "hall", "wood", "park", "hill", "price", "bell", "cook", "rose", "wang", "li", "xu", "chen", "kim", "ma"}


OWN_NOTES = {"panelsNotInspected", "panelsInspected"}   # this project's own bookkeeping text stored in every section, not Banner prose


def strings(node, out):
    if isinstance(node, str):
        out.append(node)
    elif isinstance(node, dict):
        for k, v in node.items():
            if k not in OWN_NOTES:
                strings(v, out)
    elif isinstance(node, list):
        for v in node:
            strings(v, out)


def load_restricted(payload_path, extra_dirs, catalog_cache=None):
    payload = json.loads(Path(payload_path).read_text(encoding="utf-8"))
    crns, names, texts = set(), set(), []

    def walk(node, key=""):
        if isinstance(node, dict):
            for k, v in node.items():
                lk = k.lower()
                if isinstance(v, str) and re.fullmatch(r"\d{5}", v) and ("crn" in lk):
                    crns.add(v)
                if lk in ("instructors", "instructor", "instructor_name", "instructorname") or lk.endswith("instructor_name"):
                    vals = []
                    strings(v, vals)
                    names.update(x.strip() for x in vals if x.strip())
                walk(v, k)
        elif isinstance(node, list):
            for v in node:
                walk(v, key)

    walk(payload)
    # every 5-digit string anywhere in the payload (linked, cross-listed and compared CRNs included), and every string long enough to be prose
    allstr = []
    strings(payload, allstr)
    for s in allstr:
        if re.fullmatch(r"\d{5}", s):
            crns.add(s)
    # prose that came from Banner or the catalog: the payload's course pages, sections (Class Details, grid rows, rules) and prerequisite comparisons
    # (the program, requirement and note text in the payload is this repository's own committed program file, so it is not a restricted source)
    for key in ("catalogCourses", "sections", "comparisons"):
        vals = []
        strings(payload.get(key, []), vals)
        texts.extend(v for v in vals if len(WORD.findall(v)) >= 8)
    for page in sorted(Path(catalog_cache).expanduser().glob("*.html")) if catalog_cache else []:
        raw = html.unescape(re.sub(r"<[^>]+>", " ", page.read_text(encoding="utf-8", errors="replace")))
        texts.append(raw)
    for d in extra_dirs:
        for f in sorted(Path(d).expanduser().glob("*.json")):
            try:
                node = json.loads(f.read_text(encoding="utf-8"))
            except Exception:
                continue
            vals = []
            strings(node, vals)
            for s in vals:
                if re.fullmatch(r"\d{5}", s):
                    crns.add(s)
    last_names, full_names = set(), set()
    for n in names:
        if n.lower().startswith("staff") or "," not in n:
            continue
        last, first = [p.strip() for p in n.split(",", 1)]
        if last:
            last_names.add(last)
        full_names.add(n)
    ngrams = set()
    for t in texts:
        words = [w.lower() for w in WORD.findall(t)]
        for i in range(len(words) - 7):
            ngrams.add(tuple(words[i:i + 8]))
    return crns, last_names, full_names, ngrams


def candidate_files(paths):
    if paths:
        return [Path(p) for p in paths]
    out = subprocess.run(["git", "ls-files", "-m", "-o", "--exclude-standard"], capture_output=True, text=True, check=True).stdout.split("\n")
    return [Path(p) for p in out if p]


def scan(files, crns, last_names, full_names, ngrams, show=False):
    hits = []
    last_re = re.compile(r"\b(" + "|".join(re.escape(n) for n in sorted(last_names, key=len, reverse=True)) + r")\b") if last_names else None
    for f in files:
        if f.suffix.lower() in SKIP_SUFFIX or not f.is_file():
            continue
        try:
            text = f.read_text(encoding="utf-8")
        except UnicodeDecodeError:
            hits.append((str(f), 0, "BINARY_OR_NON_UTF8"))
            continue
        for ln, line in enumerate(text.splitlines(), 1):
            for m in re.finditer(r"(?<![\d.])\d{5}(?![\d.])", line):
                if m.group(0) in crns:
                    hits.append((str(f), ln, "CRN"))
            for full in full_names:
                if full in line:
                    hits.append((str(f), ln, "INSTRUCTOR"))
            if last_re:
                for m in last_re.finditer(line):
                    if m.group(1).lower() not in COMMON_SURNAMES:
                        hits.append((str(f), ln, "INSTRUCTOR"))
            for pat in CREDENTIAL:
                if pat.search(line):
                    hits.append((str(f), ln, "CREDENTIAL"))
            if LOCAL_PATH.search(line):
                hits.append((str(f), ln, "LOCAL_PATH"))
            if re.search(r"\"crn\"\s*:\s*\"\d{5}\"", line) and any(c in line for c in crns):
                hits.append((str(f), ln, "GRID_ROW"))
        words = [w.lower() for w in WORD.findall(text)]
        # report the line of the first word of each copied run
        flagged = set()
        for i in range(len(words) - 7):
            run = words[i:i + 8]
            if tuple(run) in ngrams and sum(1 for w in run if w.isdigit()) < NUMERIC_RUN and not any(" ".join(run) in name for name in PROPER_NAMES):
                flagged.add(i)
        if flagged:
            # map word index back to a line number
            pos, ln_of_word = 0, []
            for ln, line in enumerate(text.splitlines(), 1):
                ln_of_word.extend([ln] * len(WORD.findall(line)))
            for i in sorted(flagged):
                hits.append((str(f), ln_of_word[i] if i < len(ln_of_word) else 0, "COPIED_TEXT"))
                if show:
                    print("      copied:", " ".join(words[i:i + 8]))
    return sorted(set(hits))


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    ap.add_argument("--payload", required=True)
    ap.add_argument("--grids")
    ap.add_argument("--packs")
    ap.add_argument("--catalog-cache", help="directory of cached public catalog pages (local-only); their text counts as restricted prose")
    ap.add_argument("--show-copied-words", action="store_true", help="also print the first 8 copied words of each COPIED_TEXT hit (for review; never CRNs or names)")
    ap.add_argument("--ignore-copied-in", action="append", default=[], metavar="PATH",
                    help="do not report COPIED_TEXT in this file (the synthetic fixtures reproduce Banner's panel FORMAT, e.g. the column headings of a prerequisite table; "
                         "CRNs, names and credentials are still checked there)")
    ap.add_argument("paths", nargs="*")
    args = ap.parse_args(argv)
    crns, last_names, full_names, ngrams = load_restricted(Path(args.payload).expanduser(), [d for d in (args.grids, args.packs) if d], args.catalog_cache)
    files = candidate_files(args.paths)
    hits = scan(files, crns, last_names, full_names, ngrams, args.show_copied_words)
    hits = [h for h in hits if not (h[2] == "COPIED_TEXT" and h[0] in set(args.ignore_copied_in))]
    print(f"scanned {len(files)} files against {len(crns)} real CRNs, {len(last_names)} instructor surnames, {len(ngrams)} eight-word runs of restricted text")
    for f, ln, kind in hits:
        print(f"HIT {kind:12s} {f}:{ln}")
    print(f"{len(hits)} hits" if hits else "no restricted data found")
    return 1 if hits else 0


if __name__ == "__main__":
    sys.exit(main())
