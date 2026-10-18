"""Name handling for professor_ratings (stdlib only): parses Banner instructor strings and folds names for matching.

Banner lists an instructor as "Last, First Middle", sometimes with a trailing "(P)" marker, and a section can list several separated by ";".
Placeholders such as "Staff" or "TBA" are not people and are never returned.
"""
import re
import unicodedata

PLACEHOLDER = re.compile(r"(^|[\s,])(staff|tba|tbd|instructor)([\s,]|$)", re.IGNORECASE)
TRAILING_MARK = re.compile(r"\s*\([^)]*\)\s*$")


def fold(text):
    """Lower-case ASCII letters and single spaces: accents dropped, apostrophes and periods removed, hyphens become spaces."""
    text = unicodedata.normalize("NFKD", text or "")
    text = "".join(ch for ch in text if not unicodedata.combining(ch))
    text = re.sub(r"[’'`.]", "", text)
    text = re.sub(r"[^A-Za-z]+", " ", text)
    return text.strip().lower()


def is_placeholder(text):
    return not text or bool(PLACEHOLDER.search(text))


def parse_banner_name(text):
    """'Testwick, Alder J. (P)' -> dict(bannerName, lastName, firstName, lastKey, firstKey); None for placeholders and unusable strings."""
    if text is None:
        return None
    cleaned = TRAILING_MARK.sub("", text.strip())
    cleaned = re.sub(r"\s+", " ", cleaned)
    if is_placeholder(cleaned) or "," not in cleaned:
        return None
    last, _, first = cleaned.partition(",")
    last, first = last.strip(), first.strip()
    last_key, first_key = fold(last), fold(first)
    if not last_key or not first_key:
        return None
    return {"bannerName": f"{last}, {first}", "lastName": last, "firstName": first, "lastKey": last_key, "firstKey": first_key}


def split_listed(text):
    """A section's instructor field may list several people separated by ';'."""
    return [part.strip() for part in (text or "").split(";") if part.strip()]


def rmp_name_keys(first_name, last_name):
    """Folds an RMP first/last name pair the same way; returns (first_key, last_key) or None when the last name is unusable."""
    last_key = fold(last_name)
    return (fold(first_name), last_key) if last_key else None
