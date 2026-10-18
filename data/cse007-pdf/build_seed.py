"""Regenerate V2__seed.sql from fixture.json using Python's standard library."""
import json
from pathlib import Path
from uuid import UUID

ROOT = Path(__file__).resolve().parent
TABLE_KEYS = {
    "course": ["course_id"],
    "term": ["term_id"],
    "section": ["section_id"],
    "meeting": ["meeting_id"],
    "section_link": ["link_id"],
    "prerequisite_rule": ["rule_id"],
    "student_profile": ["profile_id"],
    "schedule_preference": ["preference_id"],
    "generated_schedule": ["schedule_id"],
    "generated_schedule_section": ["schedule_id", "section_id"],
}


def build():
    fixture = json.loads((ROOT / "fixture.json").read_text(encoding="utf-8"))
    parts = [
        "-- Generated from fixture.json by build_seed.py. Do not edit by hand.",
        "-- Stable UUIDs and upserts preserve references. Run in one transaction.",
        "SET LOCAL search_path TO cse007_pdf_demo;",
    ]
    for table, keys in TABLE_KEYS.items():
        rows = fixture[table]
        if not rows:
            parts.append(f"-- {table}: no seed rows; see fixture_metadata.")
            continue
        columns = list(rows[0])
        if not all(set(row) == set(columns) for row in rows):
            raise ValueError(f"Inconsistent columns in {table}")
        if not all(column.replace("_", "").isalnum() for column in columns):
            raise ValueError(f"Unsafe column name in {table}")
        for row in rows:
            for column, value in row.items():
                if column.endswith("_id"):
                    UUID(value)
        literal = json.dumps(rows, ensure_ascii=True, indent=2)
        if "$fixture$" in literal:
            raise ValueError("Fixture contains the SQL quoting delimiter")
        names = ", ".join(columns)
        updates = ", ".join(f"{column} = EXCLUDED.{column}" for column in columns if column not in keys)
        parts.append(
            f"INSERT INTO {table} ({names})\n"
            f"SELECT {names} FROM jsonb_populate_recordset(NULL::{table},\n"
            f"$fixture${literal}$fixture$::jsonb)\n"
            f"ON CONFLICT ({', '.join(keys)}) DO UPDATE SET {updates};"
        )
    (ROOT / "V2__seed.sql").write_text("\n\n".join(parts) + "\n", encoding="utf-8")
    print("Generated V2__seed.sql from fixture.json")


if __name__ == "__main__":
    build()
