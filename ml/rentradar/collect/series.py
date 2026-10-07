"""A price index from the dates the corpus already carries.

The project needs a city-indexed price series. Collecting one honestly takes a
hundred days of watching. This module asks a cheaper question first: how much
history is already implied by the posting dates on records we hold, and is it
good enough to fit a forecast against.

Three things make a naive answer wrong.

  Dates are not equally real. Ghana Property Centre publishes a posting date.
  Jiji does not publish one at all, so Jiji is excluded from any series and the
  limitation is stated rather than papered over.

  The mix moves the median on its own. Within one source the basket still has
  to be held fixed, or the index measures which kinds of property happened to
  be listed that month. Cell medians are combined with weights fixed from the
  whole corpus, never from the period.

  Old listings are survivors. A listing posted in 2022 and still live in 2026
  is one that never let, and the usual reason is that it was overpriced. The
  further back the series reaches, the more it is made of failures. The
  survivorship section below makes that visible instead of leaving it to be
  discovered by a reader.

What this produces is an index of ASKING prices derived from listing dates. It
is not a transaction index. There is no public registry of what anything let
for in Ghana, which is the gap the whole project exists to address, and no
amount of arithmetic here invents one.

    python -m rentradar.collect.series dates
    python -m rentradar.collect.series build --source ghana_property_centre \\
        --date-field posted_date --cells bedrooms
"""

from __future__ import annotations

import argparse
import json
import statistics
import sys
from collections import Counter, defaultdict
from datetime import date
from pathlib import Path
from typing import Optional

DATE_FIELDS = [
    "posted_at", "posted_date", "listed_at", "published_at",
    "first_seen", "collected_at", "scraped_at", "crawled_at", "retrieved_at",
]

TARGET = "rent_monthly_ghs_converted"
FALLBACK_TARGET = "rent_monthly_ghs"

# A cell needs this many listings in a period to contribute its median.
MIN_CELL_OBS = 3
# A period needs this share of the corpus weight present to be trusted.
MIN_WEIGHT_COVERAGE = 0.35
# A cell needs this much support overall to be in the basket at all.
MIN_CELL_TOTAL = 20


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


def rent_of(rec: dict) -> Optional[float]:
    v = rec.get(TARGET)
    if v is None:
        v = rec.get(FALLBACK_TARGET)
    try:
        v = float(v)
    except (TypeError, ValueError):
        return None
    return v if v > 0 else None


def as_date(v) -> Optional[date]:
    if not isinstance(v, str) or len(v) < 10:
        return None
    try:
        return date.fromisoformat(v[:10])
    except ValueError:
        return None


def bucket(d: date, freq: str) -> str:
    if freq == "day":
        return d.isoformat()
    if freq == "week":
        y, w, _ = d.isocalendar()
        return f"{y}-W{w:02d}"
    return f"{d.year}-{d.month:02d}"


def cell_of(loc: str, bed: int, mode: str) -> tuple:
    """How fine the basket is.

    locality-bedrooms controls for the most, and on 120 listings a month it
    leaves cells of three or four, which is noise. bedrooms alone controls for
    less and gives cells of hundreds. Run both and report both: if they tell
    the same story the index is robust, and if they disagree the fine one is
    the one being driven by small numbers.
    """
    if mode == "bedrooms":
        return ("bed", bed)
    if mode == "locality":
        return ("loc", loc)
    return (loc, bed)


def section(title: str) -> None:
    print("\n" + "-" * 72)
    print(title)
    print("-" * 72)


# --------------------------------------------------------------- dates
def cmd_dates(args) -> int:
    rows = load(Path(args.records))
    if not rows:
        print(f"No records under {args.records}.")
        return 1

    by_source: dict[str, list[dict]] = defaultdict(list)
    for r in rows:
        by_source[str(r.get("source") or "unknown")].append(r)
    sources = sorted(by_source)

    print(f"{len(rows):,} records from {len(sources)} sources")

    section("1. Which date fields exist, and how often")
    width = max(len(f) for f in DATE_FIELDS) + 2
    print("field".ljust(width) + "".join(s[:20].rjust(22) for s in sources))
    print("-" * (width + 22 * len(sources)))
    present_fields = []
    for field in DATE_FIELDS:
        shares = []
        for s in sources:
            recs = by_source[s]
            n = sum(1 for r in recs if as_date(r.get(field)) is not None)
            shares.append(n / len(recs))
        if any(sh > 0 for sh in shares):
            present_fields.append(field)
            print(field.ljust(width) + "".join(f"{sh * 100:>20.1f}%  " for sh in shares))
    if not present_fields:
        print("  none of the candidate fields hold a parseable date")
        return 1

    section("2. What range each field covers, and how many distinct days")
    for field in present_fields:
        print(f"\n  {field}")
        for s in sources:
            ds = [as_date(r.get(field)) for r in by_source[s]]
            ds = [d for d in ds if d]
            if not ds:
                print(f"    {s:<26} none")
                continue
            distinct = len(set(ds))
            top = Counter(ds).most_common(1)[0]
            print(f"    {s:<26} {min(ds)} to {max(ds)}   {distinct:,} distinct days")
            if distinct <= 3 or top[1] / len(ds) > 0.5:
                print(f"    {'':<26} WARNING: {top[1]:,} of {len(ds):,} share one date, {top[0]}")

    section("3. Flags that say a date was inferred rather than published")
    flags = Counter()
    for r in rows:
        for f in (r.get("cleaning_flags") or []):
            if "date" in f.lower() or "posted" in f.lower() or "age" in f.lower():
                flags[f] += 1
    if flags:
        for f, n in flags.most_common():
            print(f"  {n:>6,}  {f}")
    else:
        print("  none")

    section("What to do with this")
    print("  Run 'build' with --date-field set to whichever field above is")
    print("  both widely present and spread over many distinct days, and")
    print("  --source set to exclude any source with no dates of its own.")
    return 0


# --------------------------------------------------------------- build
def basket(cells: list[tuple]) -> dict[tuple, float]:
    """Fixed weights per cell, from the whole corpus rather than per period."""
    counts = Counter(cells)
    kept = {k: c for k, c in counts.items() if c >= MIN_CELL_TOTAL}
    total = sum(kept.values()) or 1
    return {k: c / total for k, c in kept.items()}


def survivorship(obs: list[tuple], newest: date) -> None:
    """Median asking price against how long a listing has been up.

    If the old listings are dearer than the new ones, the series is not showing
    prices falling. It is showing that dear places do not let and stay on the
    site, while cheap ones go quickly and leave.
    """
    bands = [(0, 90, "under 3 months"), (90, 365, "3 to 12 months"),
             (365, 730, "1 to 2 years"), (730, 1460, "2 to 4 years"),
             (1460, 99999, "over 4 years")]
    section("Survivorship: asking price against how long the listing has been up")
    print(f"  {'age of listing':<20} {'listings':>9} {'median GHS':>12}")
    base = None
    for lo, hi, label in bands:
        vals = [v for d, _, v in obs if lo <= (newest - d).days < hi]
        if not vals:
            continue
        med = statistics.median(vals)
        if base is None:
            base = med
        rel = f"{med / base * 100:>6.0f}%" if base else ""
        print(f"  {label:<20} {len(vals):>9,} {med:>12,.0f}   {rel} of newest")
    print("\n  A rising column here is survivorship, not a falling market.")
    print("  Expensive properties do not let, so they stay listed and the old")
    print("  end of any series fills up with them.")


def cmd_build(args) -> int:
    rows_all = load(Path(args.records))
    if not rows_all:
        print(f"No records under {args.records}.")
        return 1

    field = args.date_field
    keep_sources = set(args.source) if args.source else None

    rows = []
    skipped = Counter()
    for r in rows_all:
        if keep_sources and str(r.get("source")) not in keep_sources:
            skipped["source excluded"] += 1
            continue
        d = as_date(r.get(field))
        if d is None:
            skipped[f"no parseable {field}"] += 1
            continue
        v = rent_of(r)
        if v is None:
            skipped["no rent figure"] += 1
            continue
        if r.get("locality") is None or r.get("bedrooms") is None:
            skipped["no locality or bedrooms"] += 1
            continue
        rows.append((d, cell_of(str(r["locality"]), int(r["bedrooms"]), args.cells), v))

    print(f"{len(rows_all):,} records loaded, {len(rows):,} usable for an index")
    for reason, n in skipped.most_common():
        print(f"  {n:>6,}  skipped: {reason}")
    if not rows:
        print("\nNothing left. Run 'dates' first and pick a different field.")
        return 1

    if args.since:
        cut = date.fromisoformat(args.since)
        before = len(rows)
        rows = [r for r in rows if r[0] >= cut]
        print(f"  {before - len(rows):>6,}  skipped: posted before {args.since}")

    newest = max(d for d, _, _ in rows)
    survivorship(rows, newest)

    weights = basket([c for _, c, _ in rows])
    section(f"Index by {args.freq}, basket of {len(weights)} cells ({args.cells})")

    per_period: dict[str, list] = defaultdict(list)
    for d, c, v in rows:
        per_period[bucket(d, args.freq)].append((c, v))

    out_rows = []
    for key in sorted(per_period):
        obs = per_period[key]
        cells: dict[tuple, list[float]] = defaultdict(list)
        for c, v in obs:
            cells[c].append(v)

        num = 0.0
        covered = 0.0
        used = 0
        for cell, w in weights.items():
            vals = cells.get(cell) or []
            if len(vals) >= MIN_CELL_OBS:
                num += w * statistics.median(vals)
                covered += w
                used += 1

        out_rows.append({
            "period": key,
            "listings": len(obs),
            "cells_used": used,
            "weight_coverage": round(covered, 4),
            "index_ghs": round(num / covered, 2) if covered > 0 else None,
            "raw_median_ghs": round(statistics.median([v for _, v in obs]), 2),
            "thin": covered < MIN_WEIGHT_COVERAGE,
        })

    print(f"  {'period':<10} {'listings':>9} {'cells':>6} {'cover':>7} "
          f"{'index GHS':>11} {'raw median':>11}")
    for row in out_rows:
        mark = "  thin" if row["thin"] else ""
        idx = f"{row['index_ghs']:,.0f}" if row["index_ghs"] else "-"
        print(f"  {row['period']:<10} {row['listings']:>9,} {row['cells_used']:>6} "
              f"{row['weight_coverage'] * 100:>6.1f}% {idx:>11} "
              f"{row['raw_median_ghs']:>11,.0f}{mark}")

    solid = [r for r in out_rows if not r["thin"] and r["index_ghs"]]
    section("Is this fit to forecast against?")
    print(f"  {len(out_rows)} periods, {len(solid)} above the "
          f"{MIN_WEIGHT_COVERAGE * 100:.0f}% coverage bar")

    # Contiguity matters more than count. ARIMA cannot step over a hole.
    run, best, prev_i = 0, 0, None
    keys = [r["period"] for r in out_rows]
    for i, r in enumerate(out_rows):
        ok = not r["thin"] and r["index_ghs"]
        if ok and (prev_i is None or i == prev_i + 1):
            run += 1
        elif ok:
            run = 1
        else:
            run = 0
        prev_i = i if ok else prev_i
        best = max(best, run)
    print(f"  longest unbroken run of usable periods: {best}")

    if len(solid) >= 2:
        vals = [r["index_ghs"] for r in solid]
        swings = [abs(vals[i] - vals[i - 1]) / vals[i - 1] for i in range(1, len(vals))]
        print(f"  median period-on-period swing: {statistics.median(swings) * 100:.1f}%")
        print("  A real rent index moves a few per cent a month. Much more than")
        print("  that and the movement is sampling noise, not the market.")

    out = Path(args.out)
    out.mkdir(parents=True, exist_ok=True)
    dest = out / f"accra_{args.freq}_{args.cells}.jsonl"
    with dest.open("w", encoding="utf-8") as fh:
        for row in out_rows:
            fh.write(json.dumps(row, ensure_ascii=False) + "\n")
    print(f"\n  wrote {dest}")
    print("  An index of ASKING prices derived from listing dates, from one")
    print("  source, subject to the survivorship above. Not a transaction index.")
    return 0


def main(argv=None) -> int:
    p = argparse.ArgumentParser(description="Price index from listing dates")
    p.add_argument("--records", default="data/clean")
    sub = p.add_subparsers(dest="command", required=True)

    d = sub.add_parser("dates", help="what dates the corpus actually holds")
    d.set_defaults(func=cmd_dates)

    b = sub.add_parser("build", help="build the fixed-weight index")
    b.add_argument("--freq", default="month", choices=["day", "week", "month"])
    b.add_argument("--date-field", default="posted_date")
    b.add_argument("--cells", default="bedrooms",
                   choices=["bedrooms", "locality", "locality-bedrooms"])
    b.add_argument("--since", help="ignore listings posted before this date, YYYY-MM-DD")
    b.add_argument("--source", action="append", help="limit to this source, repeatable")
    b.add_argument("--out", default="data/series")
    b.set_defaults(func=cmd_build)

    args = p.parse_args(argv)
    return args.func(args)


if __name__ == "__main__":
    sys.exit(main())