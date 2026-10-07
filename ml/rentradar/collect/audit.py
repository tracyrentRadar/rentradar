"""Corpus quality audit. Reads records, reports what is actually in them.

    python -m rentradar.collect.audit
    python -m rentradar.collect.audit --records data/records --out audit.txt

Read-only, so it is safe to run while a crawl is appending. Everything it
prints belongs in the data quality section of chapter three, because a corpus
claim an examiner cannot interrogate is a corpus claim that will be
interrogated.

The questions it answers, in order of how badly a wrong answer would hurt:
  Did extraction actually work, or is a field silently empty everywhere?
  What currency is the market quoted in, and how much of the corpus does the
    no-silent-conversion rule put out of reach?
  Are the rents plausible, or is something parsed wrong by a factor of ten?
  How much duplication is there between and within sources?
  How far back do posting dates reach, which decides whether a retrospective
    index is possible at all?
"""

from __future__ import annotations

import argparse
import json
import statistics
import sys
from collections import Counter, defaultdict
from datetime import datetime
from pathlib import Path

_out: list[str] = []


def say(line: str = "") -> None:
    print(line)
    _out.append(line)


def rule(title: str) -> None:
    say()
    say("-" * 70)
    say(title)
    say("-" * 70)


def load(records_dir: Path) -> list[dict]:
    rows = []
    for path in sorted(records_dir.glob("*.jsonl")):
        bad = 0
        for line in path.read_text(encoding="utf-8").splitlines():
            line = line.strip()
            if not line:
                continue
            try:
                rows.append(json.loads(line))
            except json.JSONDecodeError:
                bad += 1
        say(f"  {path.name:46s} {sum(1 for _ in path.read_text(encoding='utf-8').splitlines() if _.strip()):>6,} lines"
            + (f"  ({bad} unparseable)" if bad else ""))
    return rows


def pct(n: int, total: int) -> str:
    return f"{100 * n / total:5.1f}%" if total else "    -"


def completeness(rows: list[dict], fields: list[str]) -> None:
    total = len(rows)
    for f in fields:
        present = sum(1 for r in rows if r.get(f) not in (None, "", [], {}))
        bar = "#" * round(20 * present / total) if total else ""
        say(f"  {f:22s} {present:>6,} / {total:,}  {pct(present, total)}  {bar}")


def quantiles(values: list[float]) -> str:
    if not values:
        return "no values"
    v = sorted(values)
    def q(p): return v[min(len(v) - 1, int(p * len(v)))]
    return (f"min {v[0]:>10,.0f} | p10 {q(.10):>9,.0f} | median {statistics.median(v):>9,.0f} | "
            f"p90 {q(.90):>10,.0f} | max {v[-1]:>11,.0f}")


def main(argv=None) -> int:
    p = argparse.ArgumentParser(description="RentRadar corpus audit")
    p.add_argument("--records", default="data/records")
    p.add_argument("--out", default="audit_report.txt")
    args = p.parse_args(argv)

    records_dir = Path(args.records)
    if not records_dir.exists():
        print(f"{records_dir} not found. Run this from the folder holding 'rentradar' and 'data'.")
        return 1

    rule("1. Files")
    rows = load(records_dir)
    if not rows:
        say("  No records found.")
        return 1
    total = len(rows)
    say(f"\n  {total:,} records loaded")

    rule("2. Coverage")
    for key in ("source", "city", "property_type"):
        say(f"\n  by {key}:")
        for k, n in Counter(r.get(key) or "(none)" for r in rows).most_common(12):
            say(f"    {n:>6,}  {pct(n, total)}  {k}")

    say("\n  top localities:")
    for k, n in Counter(r.get("locality") or "(none)" for r in rows).most_common(15):
        say(f"    {n:>6,}  {pct(n, total)}  {k}")

    rule("3. Field completeness")
    say("  A field empty everywhere means extraction failed, not that the data")
    say("  is missing. That distinction is the whole point of this section.\n")
    completeness(rows, [
        "bedrooms", "bathrooms", "toilets", "parking_spaces", "property_type",
        "locality", "area_sqm", "furnished", "asking_rent_amount",
        "posted_date", "agent_phone_hash", "amenities",
    ])

    rule("4. Currency, and what the no-conversion rule costs")
    cur = Counter(r.get("asking_rent_currency") or "(none)" for r in rows)
    for k, n in cur.most_common():
        say(f"  {k:6s} {n:>6,}  {pct(n, total)}")
    usable = sum(1 for r in rows if r.get("rent_monthly_ghs") is not None)
    say()
    say(f"  rent_monthly_ghs populated: {usable:,} of {total:,}  ({pct(usable, total).strip()})")
    if usable < total:
        say(f"  {total - usable:,} records are priced in another currency and are held back")
        say("  from the GHS figure by binding rule 2. They are not lost: they need a")
        say("  dated Bank of Ghana rate applied as an explicit, flagged step.")

    rule("5. Rent distribution, by currency and period")
    say("  Checking for a parse off by a factor of ten, which is what a mangled")
    say("  thousands separator looks like.\n")
    by_cur = defaultdict(list)
    for r in rows:
        amt = r.get("asking_rent_amount")
        if amt:
            by_cur[r.get("asking_rent_currency")].append(float(amt))
    for k, vals in sorted(by_cur.items(), key=lambda kv: -len(kv[1])):
        say(f"  {k} (n={len(vals):,})")
        say(f"    {quantiles(vals)}")
    say()
    say("  rent_period:")
    for k, n in Counter(r.get("rent_period") for r in rows).most_common():
        say(f"    {n:>6,}  {pct(n, total)}  {k}")

    rule("6. Bedrooms")
    for k, n in sorted(Counter(r.get("bedrooms") for r in rows).items(),
                       key=lambda kv: (kv[0] is None, kv[0])):
        say(f"  {str(k):>6}  {n:>6,}  {pct(n, total)}")

    rule("7. Cleaning flags")
    say("  Every flag is a transformation or an absence that was recorded rather")
    say("  than applied silently. High counts are not errors by themselves.\n")
    flags = Counter(f for r in rows for f in r.get("cleaning_flags", []))
    for k, n in flags.most_common(25):
        say(f"  {n:>6,}  {pct(n, total)}  {k}")
    clean = sum(1 for r in rows if not r.get("cleaning_flags"))
    say(f"\n  records with no flags at all: {clean:,}  ({pct(clean, total).strip()})")

    rule("8. Amenities")
    am = Counter(a for r in rows for a in r.get("amenities", []))
    say(f"  {len(am)} distinct amenities across the corpus")
    say("  Only those on a decent share of listings are usable as features;")
    say("  a feature present on 2% of rows is noise with a column heading.\n")
    for k, n in am.most_common(25):
        say(f"    {n:>6,}  {pct(n, total)}  {k}")

    rule("9. Duplicates")
    by_id = Counter((r.get("source"), r.get("source_record_id")) for r in rows)
    dup_ids = [k for k, n in by_id.items() if n > 1]
    say(f"  same source and listing id more than once: {len(dup_ids)}")
    by_url = Counter(r.get("listing_url_hash") for r in rows if r.get("listing_url_hash"))
    say(f"  same URL more than once:                   {sum(1 for n in by_url.values() if n > 1)}")

    # Near duplicates: the same home relisted, or the same home on two sources.
    coarse = Counter(
        (r.get("city"), r.get("locality"), r.get("bedrooms"), r.get("bathrooms"),
         round(r["asking_rent_amount"] / 100) if r.get("asking_rent_amount") else None)
        for r in rows
    )
    near = sum(n - 1 for n in coarse.values() if n > 1)
    say(f"  same city, locality, beds, baths and rent:  {near}  possible relistings")

    agents = Counter(r.get("agent_phone_hash") for r in rows if r.get("agent_phone_hash"))
    if agents:
        say(f"\n  distinct agent numbers: {len(agents):,}")
        say("  most listings from one number: " + ", ".join(str(n) for _, n in agents.most_common(5)))
        say("  A number carrying a large share of a market is a fraud-model feature,")
        say("  not a problem with the crawl.")

    rule("10. Posting dates")
    dates = []
    for r in rows:
        d = r.get("posted_date")
        if d:
            try:
                dates.append(datetime.fromisoformat(d.replace("Z", "+00:00")).date())
            except ValueError:
                pass
    if not dates:
        say("  No posting dates. A retrospective index is not possible from this corpus.")
    else:
        dates.sort()
        span = (dates[-1] - dates[0]).days
        say(f"  {len(dates):,} records carry a date")
        say(f"  earliest {dates[0]}   latest {dates[-1]}   span {span} days")
        by_month = Counter(d.strftime("%Y-%m") for d in dates)
        say("\n  by month:")
        for k in sorted(by_month):
            say(f"    {k}  {by_month[k]:>5,}  {'#' * round(40 * by_month[k] / max(by_month.values()))}")
        if span >= 100:
            say("\n  The span exceeds 100 days, so a backwards index can be built from")
            say("  this crawl alone. Survivorship bias has to be stated: only listings")
            say("  still live today are visible, so older months are a filtered sample.")

    Path(args.out).write_text("\n".join(_out), encoding="utf-8")
    say()
    say(f"Report written to {args.out}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
