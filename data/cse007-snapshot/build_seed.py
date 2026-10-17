"""Build PostgreSQL seed SQL from the screenshot transcription (stdlib only)."""
import hashlib
import json
from pathlib import Path
from uuid import UUID

ROOT = Path(__file__).resolve().parent
REPO = ROOT.parent.parent
KEYS = {
    "source_asset": ["source_id"],
    "course": ["course_id"],
    "term": ["term_id"],
    "section": ["section_id"],
    "section_attribute": ["section_id", "attribute_text"],
    "meeting": ["meeting_id"],
    "course_requirement": ["requirement_id"],
    "section_component_group": ["primary_section_id", "required_component_group"],
    "section_link": ["link_id"],
    "enrollment_snapshot": ["enrollment_snapshot_id"],
    "data_issue": ["issue_id"],
}


def build():
    fixture = json.loads((ROOT / "fixture.json").read_text(encoding="utf-8"))
    for source in fixture["source_asset"]:
        path = (REPO / source["file_path"]).resolve()
        if not path.is_relative_to(REPO):
            raise ValueError("Source must be inside the repository")
        if hashlib.sha256(path.read_bytes()).hexdigest() != source["sha256"]:
            raise ValueError(f"Source changed: {source['file_path']}")
    parts = [
        "-- Generated from fixture.json by build_seed.py. Do not edit by hand.",
        "-- Stable UUID upserts; apply in a transaction together with verify.sql.",
        "SET LOCAL search_path TO cse007_snapshot;",
    ]
    for table, keys in KEYS.items():
        rows = fixture[table]
        columns = list(rows[0])
        if not all(set(row) == set(columns) for row in rows):
            raise ValueError(f"Inconsistent columns in {table}")
        if not all(column.replace("_", "").isalnum() for column in columns):
            raise ValueError(f"Invalid column in {table}")
        for row in rows:
            for column, value in row.items():
                if column.endswith("_id") and value is not None:
                    UUID(value)
        literal = json.dumps(rows, ensure_ascii=True, indent=2)
        if "$fixture$" in literal:
            raise ValueError("SQL delimiter found in fixture")
        names = ", ".join(columns)
        updates = ", ".join(f"{col} = EXCLUDED.{col}" for col in columns if col not in keys)
        parts.append(
            f"INSERT INTO {table} ({names})\n"
            f"SELECT {names} FROM jsonb_populate_recordset(NULL::{table},\n"
            f"$fixture${literal}$fixture$::jsonb)\n"
            f"ON CONFLICT ({', '.join(keys)}) DO UPDATE SET {updates};"
        )
    seed = "\n\n".join(parts) + "\n"
    (ROOT / "V4__clarified_seed.sql").write_text(seed, encoding="utf-8")
    # A plain-SQL bundle also works in pgAdmin's Query Tool (no psql commands).
    verification = "\n".join(
        line for line in (ROOT / "verify.sql").read_text(encoding="utf-8").splitlines()
        if not line.startswith("\\set ")
    )
    bundle = (
        "-- Generated screenshot + SuY clarification bundle. Run once without cse007_snapshot.\n"
        "BEGIN;\n" + (ROOT / "V1__schema.sql").read_text(encoding="utf-8")
        + "\n" + (ROOT / "V2__seed.sql").read_text(encoding="utf-8")
        + "\n" + (ROOT / "V3__user_clarifications.sql").read_text(encoding="utf-8")
        + "\n" + seed + "\n" + verification + "\nCOMMIT;\n"
    )
    (ROOT / "database.sql").write_text(bundle, encoding="utf-8")
    print(f"PASS: {len(fixture['source_asset'])} source hashes verified; generated V4__clarified_seed.sql and database.sql")


if __name__ == "__main__":
    build()
