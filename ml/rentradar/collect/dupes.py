"""Duplicate detection and outlier review, before anything is partitioned.

The audit reports a coarse collision count: listings sharing city, locality,
bedrooms, bathrooms and rent. On this corpus that came to 1,313 of 3,083, which
would be alarming if it were real. It probably is not. Fifteen localities carry
most of the listings, bedroom counts cluster on three values, and asking rents
sit on round numbers, so two genuinely different flats colliding on that tuple
is ordinary chance rather than evidence of duplication.

The number matters because it decides the effective sample size, and because a
pair of near-identical rows split across train and test is leakage that inflates
every score reported afterwards. So this module narrows the rule in stages and
reports what survives each one:

    coarse    locality, bedrooms, bathrooms, rent          the audit's rule
    tight     coarse plus property type, toilets, area     same unit, likely
    agent     tight plus the agent's number                same unit, near certain

Deduplication is not applied by default. Look at the clusters first, decide
which rule is defensible, and record the decision in the data dictionary. A
cleaning rule applied silently is exactly what this project claims not to do.

    python -m rentradar.collect.dupes
    python -m rentradar.collect.dupes --write tight
"""

from __future__ import annotations

import argparse
import json
import re
import sys
from collections import Counter, defaultdict
from pathlib import Path
from typing import Optional

# A bedroom count above this is a block of flats, a hostel, or a parse failure.
# None of the three is a dwelling the price model should be trained on.
BEDROOM_CEILING = 12

# Floor areas outside this band in square metres are not residential lettings.
AREA_FLOOR, AREA_CEILING = 10.0, 2000.0


def load(src: Path) -> list[dict]:
    rows = []
    for path in sorted(src.glob("*.jsonl")):
        with path.open(encoding="utf-8") as fh:
            for line in fh:
                line = line.strip()
                if not line:
                    continue
                try:
                    rows.append(json.loads(line))
                except json.JSONDecodeError:
                    continue
    return rows


def norm(s: Optional[str]) -> str:
    return re.sub(r"[^a-z0-9]+", "", (s or "").lower())


def rent_key(r: dict):
    """The quoted figure, not the converted one.

    Two listings quoted at the same dollar amount are the same advert far more
    often than two that happen to land on the same cedi figure after conversion.
    """
    return (norm(r.get("asking_rent_currency")), r.get("asking_rent_amount"))


def key_coarse(r: dict):
    return (norm(r.get("locality")), r.get("bedrooms"), r.get("bathrooms"), rent_key(r))


def key_tight(r: dict):
    return key_coarse(r) + (norm(r.get("property_type")), r.get("toilets"),
                            r.get("area_sqm"))


def key_agent(r: dict):
    return key_tight(r) + (r.get("agent_phone_hash"),)


def cluster(rows: list[dict], keyfn) -> dict:
    groups = defaultdict(list)
    for i, r in enumerate(rows):
        groups[keyfn(r)].append(i)
    return {k: v for k, v in groups.items() if len(v) > 1}


def report_rule(name: str, rows: list[dict], keyfn, show: int = 0) -> None:
    groups = cluster(rows, keyfn)
    n_rows = sum(len(v) for v in groups.values())
    surplus = n_rows - len(groups)
    total = len(rows)
    print(f"\n  {name}")
    print(f"    {len(groups):>5,} clusters covering {n_rows:,} records "
          f"({n_rows / total * 100:.1f}%)")
    print(f"    {surplus:,} records would be removed, leaving "
          f"{total - surplus:,} ({(total - surplus) / total * 100:.1f}% of the corpus)")

    if groups:
        sizes = Counter(len(v) for v in groups.values())
        tail = ", ".join(f"{s}x{c}" for s, c in sorted(sizes.items())[:8])
        print(f"    cluster sizes: {tail}")

    for key, idxs in sorted(groups.items(), key=lambda kv: -len(kv[1]))[:show]:
        r = rows[idxs[0]]
        amt = r.get("asking_rent_amount")
        cur = (r.get("asking_rent_currency") or "?").upper()
        print(f"\n      {len(idxs)} records  {r.get('locality')}  "
              f"{r.get('bedrooms')}bd/{r.get('bathrooms')}ba  "
              f"{cur} {amt:,.0f}" if amt else f"\n      {len(idxs)} records")
        agents = {rows[i].get("agent_phone_hash") for i in idxs}
        dates = sorted({rows[i].get("posted_date") for i in idxs if rows[i].get("posted_date")})
        print(f"        {len(agents)} distinct agent number(s), "
              f"posted {dates[0]} to {dates[-1]}" if dates else
              f"        {len(agents)} distinct agent number(s)")
        for i in idxs[:4]:
            t = (rows[i].get("title") or "")[:62]
            print(f"        id {rows[i].get('listing_id')}  {t}")
        if len(idxs) > 4:
            print(f"        ... and {len(idxs) - 4} more")


def section(title: str) -> None:
    print("\n" + "-" * 70)
    print(title)
    print("-" * 70)


def outliers(rows: list[dict]) -> list[int]:
    """Records no price model should see, with the reason stated."""
    bad: list[tuple[int, str]] = []
    for i, r in enumerate(rows):
        bd = r.get("bedrooms")
        if bd is not None and bd > BEDROOM_CEILING:
            bad.append((i, f"bedrooms {bd}"))
            continue
        a = r.get("area_sqm")
        if a is not None and not (AREA_FLOOR <= a <= AREA_CEILING):
            bad.append((i, f"area_sqm {a:,.0f}"))
            continue
        flags = r.get("cleaning_flags") or []
        rev = [f for f in flags if f.endswith("_review")]
        if rev:
            bad.append((i, rev[0]))

    if not bad:
        print("  none")
        return []

    for i, why in bad:
        r = rows[i]
        amt = r.get("asking_rent_amount") or 0
        cur = (r.get("asking_rent_currency") or "?").upper()
        print(f"  {why:<32} {cur} {amt:>12,.0f}/{r.get('rent_period')}  "
              f"{r.get('locality')}  id {r.get('listing_id')}")
        print(f"      {(r.get('title') or '')[:84]}")
        if r.get("url"):
            print(f"      {r['url']}")
    print(f"\n  {len(bad)} records. Open each URL and decide individually.")
    print("  These are few enough to judge by eye, which is better than a rule.")
    return [i for i, _ in bad]


def main(argv=None) -> int:
    p = argparse.ArgumentParser(description="Duplicate and outlier review")
    p.add_argument("--records", default="data/interim")
    p.add_argument("--out", default="data/interim")
    p.add_argument("--show", type=int, default=3,
                   help="largest clusters to print per rule")
    p.add_argument("--write", choices=["coarse", "tight", "agent"],
                   help="apply one rule and write a deduplicated copy")
    args = p.parse_args(argv)

    src = Path(args.records)
    rows = load(src)
    if not rows:
        src = Path("data/records")
        rows = load(src)
    if not rows:
        print(f"No records found in {args.records} or data/records.")
        return 1

    print(f"{len(rows):,} records from {src}")

    section("1. How many duplicates, under three rules")
    print("  Each rule is stricter than the one above it. The gap between")
    print("  'coarse' and 'tight' is how much of the audit's figure was chance.")
    report_rule("coarse   locality + beds + baths + rent", rows, key_coarse, args.show)
    report_rule("tight    + property type + toilets + area", rows, key_tight, args.show)
    report_rule("agent    + agent number", rows, key_agent, args.show)

    section("2. Agent concentration")
    counts = Counter(r.get("agent_phone_hash") for r in rows if r.get("agent_phone_hash"))
    total = sum(counts.values())
    print(f"  {len(counts)} distinct numbers over {total:,} records\n")
    for h, c in counts.most_common(8):
        tight = cluster([r for r in rows if r.get("agent_phone_hash") == h], key_tight)
        dup = sum(len(v) for v in tight.values()) - len(tight)
        print(f"    {c:>5,}  {c / total * 100:>5.1f}%   {str(h)[:16]}   "
              f"{dup:,} of its own listings are tight duplicates")
    top = counts.most_common(1)
    if top and top[0][1] / total > 0.2:
        share = top[0][1] / total * 100
        print(f"\n  One number carries {share:.1f}% of the corpus. A grouped split,")
        print("  keeping an agent's listings entirely on one side of the")
        print("  partition, is mandatory rather than a precaution, and this")
        print("  concentration belongs in the limitations section.")

    section("3. Records to review by hand")
    bad = outliers(rows)

    if args.write:
        keyfn = {"coarse": key_coarse, "tight": key_tight, "agent": key_agent}[args.write]
        drop = set(bad)
        seen = {}
        for i, r in enumerate(rows):
            if i in drop:
                continue
            k = keyfn(r)
            # Keep the earliest posting in each cluster: the first appearance of
            # a unit, with later relistings treated as repeats of it.
            if k not in seen or (r.get("posted_date") or "9999") < (
                    rows[seen[k]].get("posted_date") or "9999"):
                seen[k] = i
        keep = sorted(seen.values())

        out_dir = Path(args.out)
        out_dir.mkdir(parents=True, exist_ok=True)
        dest = out_dir / f"corpus_deduped_{args.write}.jsonl"
        with dest.open("w", encoding="utf-8") as fh:
            for i in keep:
                r = dict(rows[i])
                flags = list(r.get("cleaning_flags") or [])
                flags.append(f"deduped_rule_{args.write}")
                r["cleaning_flags"] = flags
                fh.write(json.dumps(r, ensure_ascii=False) + "\n")

        section("4. Written")
        print(f"  rule           {args.write}")
        print(f"  dropped        {len(drop):,} reviewed outliers")
        print(f"  dropped        {len(rows) - len(drop) - len(keep):,} duplicate records")
        print(f"  kept           {len(keep):,}")
        print(f"  written to     {dest}")
        print("\n  Every kept record carries a deduped_rule flag, so the rule")
        print("  that produced this file is readable off the data itself.")

    return 0


if __name__ == "__main__":
    sys.exit(main())
