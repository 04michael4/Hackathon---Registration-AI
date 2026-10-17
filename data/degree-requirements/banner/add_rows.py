"""Append one course's Banner grid rows (stdin) to sections_grid.csv and update progress_manifest.json. Refuses duplicate CRNs.
stdin line: crn section hours days start end type building room   (spaces in building as _, '-' = None)
usage: add_rows.py "ECO 325" "Title" LINKED|NONE_SHOWN "detail note" < rows
       add_rows.py "ECO 325" --none-offered
       extra exam meetings: lines starting with 'X ' : X crn day start end start_date end_date type"""
import csv, json, sys
from pathlib import Path
H = Path(__file__).resolve().parent
code = sys.argv[1]
man = json.load(open(H / "progress_manifest.json"))
if sys.argv[2] == "--none-offered":
    with open(H / "no_sections.csv", "a") as f:
        f.write(f"202710,{code},NOT_OFFERED_IN_SPRING_2027 (0 classes returned),2026-10-17,authenticated_banner\n")
    man["courses"][code] = {"status": "NOT_OFFERED_IN_SPRING_2027", "sections": 0}
else:
    title, link, note = sys.argv[2:5]
    have = {r["crn"] for r in csv.DictReader(open(H / "sections_grid.csv"))}
    rows, extra = [], []
    for ln in sys.stdin.read().splitlines():
        if not ln.strip():
            continue
        p = ln.split()
        if p[0] == "X":
            extra.append(p[1:]); continue
        crn, sec, hrs, days, s, e, typ, b, rm = p
        assert crn.isdigit() and len(crn) == 5 and crn not in have, f"bad/dup crn {crn}"
        have.add(crn)
        rows.append([ "202710", code, sec, crn, title, hrs, days, s, e, typ, "None" if b == "-" else b.replace("_", " "), "None" if rm == "-" else rm,
                      "2027-01-25", "2027-05-07", "NOT_STORED", link if typ in ("Class",) or link == "NONE_SHOWN" else "LINKED", "2026-10-17", "authenticated_banner", "GRID_ONLY_DETAILS_NOT_INSPECTED"])
    with open(H / "sections_grid.csv", "a", newline="") as f:
        csv.writer(f, lineterminator="\n").writerows(rows)
    with open(H / "meetings_extra.csv", "a", newline="") as f:
        for crn, day, s, e, sd, ed, typ in extra:
            sec = next(r[2] for r in rows if r[3] == crn)
            csv.writer(f, lineterminator="\n").writerow(["202710", crn, code, sec, typ.replace("_", " "), day, s, e, sd, ed, "To Be Determined", "None", "2026-10-17"])
    man["courses"][code] = {"status": "GRID_ONLY", "sections": len(rows), "detail": note}
man["next"] = [c for c in man["next"] if c != code]
json.dump(man, open(H / "progress_manifest.json", "w"), indent=1)
print(code, "ok", len(rows) if sys.argv[2] != "--none-offered" else 0)
