"""Is the model learning the property, or learning which website it came from?

num__toilets is the strongest feature at 0.336 and furn__yes is second at
0.180. Neither is a plausible driver of Accra rent on its own. Both become
plausible if one source fills those fields and the other leaves them blank:
the imputed value then tells the model which site a listing came from, and the
two sites sit at opposite ends of the market.

A feature whose availability depends on the source is a source label wearing a
feature's name. At serving time the user's listing has no source, so whatever
the model learned from it is wasted at best and misleading at worst.

    python -m rentradar.collect.fieldcheck
"""

from __future__ import annotations

import argparse
import json
import statistics
import sys
from collections import defaultdict
from pathlib import Path

FIELDS = [
    "bedrooms",
    "bathrooms",
    "toilets",
    "parking_spaces",
    "furnished",
    "property_type",
    "area_sqm",
    "amenities",
    "locality",
]

TARGET = "rent_monthly_ghs_converted"


def load(src: Path) -> list[dict]:
    rows = []
    for path in sorted(src.glob("*.jsonl")):
        with path.open(encoding="utf-8") as fh:
            for line in fh:
                line = line.strip()
                if line:
                    try:
                        rows.append(json.loads(line))
                    except json.JSONDecodeError:
                        pass
    return rows


def present(v) -> bool:
    if v is None:
        return False
    if isinstance(v, str) and not v.strip():
        return False
    if isinstance(v, (list, dict)) and len(v) == 0:
        return False
    return True


def main(argv=None) -> int:
    p = argparse.ArgumentParser(description="Field availability by source")
    p.add_argument("--records", default="data/clean")
    args = p.parse_args(argv)

    rows = load(Path(args.records))
    if not rows:
        print(f"No records under {args.records}.")
        return 1

    by_source: dict[str, list[dict]] = defaultdict(list)
    for r in rows:
        by_source[str(r.get("source") or "unknown")].append(r)

    sources = sorted(by_source)
    print(f"{len(rows):,} records from {len(sources)} sources\n")

    width = max(len(f) for f in FIELDS) + 2
    header = "field".ljust(width) + "".join(s[:22].rjust(24) for s in sources)
    print(header)
    print("-" * len(header))

    for field in FIELDS:
        line = field.ljust(width)
        for s in sources:
            recs = by_source[s]
            n = sum(1 for r in recs if present(r.get(field)))
            line += f"{n / len(recs) * 100:>22.1f}%" + "  "
        print(line)

    print()
    print("A field at 90 per cent on one source and 10 per cent on the other is")
    print("not a property feature. It is a source label, and the model will use")
    print("it as one.\n")

    print("rent, GHS per month".ljust(width) + "".join(s[:22].rjust(24) for s in sources))
    print("-" * len(header))
    for label, fn in (("median", statistics.median),
                      ("mean", statistics.mean)):
        line = label.ljust(width)
        for s in sources:
            vals = [float(r[TARGET]) for r in by_source[s]
                    if r.get(TARGET) is not None]
            line += f"{fn(vals):>22,.0f}" + "  " if vals else " " * 24
        print(line)

    print()
    print("values of 'furnished', by source")
    print("-" * len(header))
    for s in sources:
        vals = defaultdict(int)
        for r in by_source[s]:
            vals[repr(r.get("furnished"))] += 1
        top = sorted(vals.items(), key=lambda kv: -kv[1])[:4]
        print(f"  {s}: " + ", ".join(f"{k} x{v:,}" for k, v in top))

    print()
    print("values of 'toilets', by source")
    print("-" * len(header))
    for s in sources:
        vals = defaultdict(int)
        for r in by_source[s]:
            vals[repr(r.get("toilets"))] += 1
        top = sorted(vals.items(), key=lambda kv: -kv[1])[:6]
        print(f"  {s}: " + ", ".join(f"{k} x{v:,}" for k, v in top))

    return 0


if __name__ == "__main__":
    sys.exit(main())