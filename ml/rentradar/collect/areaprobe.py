"""Find out what the area field on a Ghana Property Centre page actually says.

The corpus carries area_value, area_unit and area_sqm. In every record sampled
so far area_unit is "sqm" and area_sqm equals area_value, which would be fine if
the figures were plausible. They are not: a one bedroom flat recorded at 6,667
square metres, a single room at 3. Two different failures, clustered in
different localities, so neither is random noise.

Three possibilities, and they need different fixes:

  the unit is asserted   the page says square feet and the parser writes "sqm"
  the wrong field        the parser reads plot or total area, not covered area
  the wrong number       the parser reads something that is not an area at all

Only the archived page can distinguish them, which is the reason for keeping the
archive in the first place. This walks it, finds the pages behind particular
record ids, and prints every scrap of text near an area-shaped word so the real
markup is visible rather than inferred.

    python -m rentradar.collect.areaprobe
    python -m rentradar.collect.areaprobe --ids 69860 69724 --archive data/raw
"""

from __future__ import annotations

import argparse
import json
import re
import sys
from collections import Counter
from pathlib import Path

# Record ids seen with implausible areas: two tiny, two huge.
DEFAULT_IDS = ["69860", "68698", "69724", "68805"]

AREA_WORDS = re.compile(
    r"(area|size|sqm|sq\.?\s?m|m2|m²|sqft|sq\.?\s?ft|square|plot|acre|covered|total\s+land)",
    re.I)

TEXT_SUFFIXES = {".html", ".htm", ".json", ".jsonl", ".txt", ".xml"}


def scan_corpus(records: Path) -> None:
    """What units the corpus actually claims, and how the figures sit."""
    units, pairs = Counter(), 0
    buckets = Counter()
    for path in sorted(records.glob("*.jsonl")):
        with path.open(encoding="utf-8") as fh:
            for line in fh:
                line = line.strip()
                if not line:
                    continue
                try:
                    r = json.loads(line)
                except json.JSONDecodeError:
                    continue
                u = r.get("area_unit")
                units[u if u is not None else "(absent)"] += 1
                av, asq = r.get("area_value"), r.get("area_sqm")
                if av is not None and asq is not None and abs(av - asq) < 1e-9:
                    pairs += 1
                v = asq
                if v is None:
                    buckets["(none)"] += 1
                elif v < 10:
                    buckets["under 10"] += 1
                elif v < 40:
                    buckets["10 to 40"] += 1
                elif v <= 600:
                    buckets["40 to 600  plausible"] += 1
                elif v <= 2000:
                    buckets["600 to 2,000"] += 1
                else:
                    buckets["over 2,000"] += 1

    total = sum(units.values())
    print(f"\n  area_unit across {total:,} records")
    for u, c in units.most_common():
        print(f"    {c:>6,}  {c / total * 100:>5.1f}%  {u}")
    print(f"\n  area_value equals area_sqm exactly: {pairs:,}")
    real = {u: c for u, c in units.items() if u != "(absent)"}
    if len(real) == 1:
        print("    Only one unit value appears anywhere. A parser that reads the")
        print("    unit from the page would produce at least two on a site that")
        print("    publishes in both, so this is very likely a default, not a read.")

    print("\n  where the figures fall")
    order = ["(none)", "under 10", "10 to 40", "40 to 600  plausible",
             "600 to 2,000", "over 2,000"]
    for k in order:
        if buckets[k]:
            print(f"    {buckets[k]:>6,}  {k}")
    sus = buckets["under 10"] + buckets["over 2,000"]
    known = total - buckets["(none)"]
    if known:
        print(f"\n    {sus:,} of {known:,} populated areas ({sus / known * 100:.1f}%) "
              f"are outside any residential range")


def find_pages(archive: Path, ids: list[str]) -> dict[str, list[Path]]:
    """Archived files mentioning each record id, by filename or by content."""
    found = {i: [] for i in ids}
    if not archive.exists():
        return found
    for p in archive.rglob("*"):
        if not p.is_file() or p.suffix.lower() not in TEXT_SUFFIXES:
            continue
        name_hits = [i for i in ids if i in p.name]
        if name_hits:
            for i in name_hits:
                found[i].append(p)
            continue
        try:
            if p.stat().st_size > 4_000_000:
                continue
            text = p.read_text(encoding="utf-8", errors="ignore")
        except OSError:
            continue
        for i in ids:
            if i in text:
                found[i].append(p)
    return found


def show_area_markup(path: Path, limit: int = 14) -> None:
    try:
        text = path.read_text(encoding="utf-8", errors="ignore")
    except OSError as exc:
        print(f"      could not read: {exc}")
        return

    # Strip tags but keep boundaries, so a table cell does not merge into its
    # neighbour and make an area look like it belongs to another label.
    flat = re.sub(r"<[^>]+>", " | ", text)
    flat = re.sub(r"\s*\|\s*(\|\s*)+", " | ", flat)
    flat = re.sub(r"[ \t]+", " ", flat)

    shown, seen = 0, set()
    for m in AREA_WORDS.finditer(flat):
        a, b = max(0, m.start() - 90), min(len(flat), m.end() + 90)
        snippet = flat[a:b].strip().replace("\n", " ")
        if len(snippet) < 12:
            continue
        # One window often contains several area words. Printing it once per
        # word buries the few distinct ones in repetition.
        fold = re.sub(r"[^a-z0-9]+", "", snippet.lower())
        if fold in seen:
            continue
        seen.add(fold)
        print(f"      ... {snippet} ...")
        shown += 1
        if shown >= limit:
            print(f"      (stopping at {limit} matches)")
            return
    if not shown:
        print("      no area-shaped text found in this file")


def main(argv=None) -> int:
    p = argparse.ArgumentParser(description="Inspect the area field at source")
    p.add_argument("--records", default="data/interim")
    p.add_argument("--archive", default="data",
                   help="root to search for archived pages")
    p.add_argument("--ids", nargs="*", default=DEFAULT_IDS)
    args = p.parse_args(argv)

    print("-" * 70)
    print("1. What the corpus claims")
    print("-" * 70)
    records = Path(args.records)
    if not records.exists():
        records = Path("data/records")
    scan_corpus(records)

    print("\n" + "-" * 70)
    print("2. What the archived pages say")
    print("-" * 70)
    archive = Path(args.archive)
    print(f"  searching {archive.resolve()}")
    found = find_pages(archive, list(args.ids))

    any_found = False
    for rid, paths in found.items():
        print(f"\n  record {rid}")
        if not paths:
            print("    no archived page found")
            continue
        any_found = True
        for path in paths[:2]:
            print(f"    {path}")
            show_area_markup(path)

    if not any_found:
        print("\n  Nothing matched. Point --archive at wherever the raw pages")
        print("  were written, then run this again. Without the page the three")
        print("  explanations cannot be told apart, and guessing which one it is")
        print("  would put an unverified cleaning rule into the methodology.")
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
