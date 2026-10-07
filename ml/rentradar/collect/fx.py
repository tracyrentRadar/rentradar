"""Currency conversion, as an explicit and reversible step.

Two thirds of the Accra corpus is quoted in US dollars. Binding rule 2 forbids
converting silently, so collection leaves `rent_monthly_ghs` null for those and
this module does the conversion on the record, in the open:

    rent_monthly_ghs_converted   the figure
    fx_rate / fx_rate_date / fx_rate_source   exactly how it was arrived at

The original amount and currency are never touched, so any conversion can be
undone or redone at a different rate. `rent_monthly_ghs` also stays null for
non-GHS listings, which keeps "quoted in cedis" and "expressed in cedis"
distinguishable for the rest of the project. Models train on the converted
column; anything reported as a market fact uses the quoted one.

Which date. These are live asking prices, not historic transactions, so every
record converts at the rate for one reference date, the day of capture, rather
than at the rate on the day each advert was posted. Using posting dates would
put twelve months of exchange rate drift into the target variable, which is
noise dressed as signal.

    python -m rentradar.collect.fx set 2026-10-04 12.31 --source "BoG interbank"
    python -m rentradar.collect.fx rates
    python -m rentradar.collect.fx convert
"""

from __future__ import annotations

import argparse
import csv
import json
import sys
from collections import Counter
from datetime import date, datetime, timedelta
from pathlib import Path
from typing import Optional

RATES_PATH = Path("data/reference/fx_rates.csv")
FIELDS = ["date", "base", "quote", "rate", "source", "recorded_at"]

# A rate more than this many days from the reference date is too stale to use
# without saying so. The cedi has moved several percent in a fortnight before
# now, so carrying one forward silently would be a real error, not a rounding.
MAX_CARRY_FORWARD_DAYS = 7


class RateTable:
    """Dated rates, loaded from a CSV kept under version control.

    The source string matters as much as the number: it is what gets cited in
    chapter three, and a rate whose provenance you cannot state is a rate you
    cannot defend.
    """

    def __init__(self, rows: list[dict]):
        self.rows = sorted(rows, key=lambda r: r["date"])

    @classmethod
    def load(cls, path: Path = RATES_PATH) -> "RateTable":
        if not path.exists():
            return cls([])
        out = []
        with path.open(encoding="utf-8") as fh:
            for r in csv.DictReader(fh):
                try:
                    out.append({
                        "date": date.fromisoformat(r["date"]),
                        "base": r["base"].upper(),
                        "quote": r["quote"].upper(),
                        "rate": float(r["rate"]),
                        "source": r.get("source", ""),
                    })
                except (ValueError, KeyError):
                    continue
        return cls(out)

    def lookup(self, base: str, quote: str, on: date) -> tuple[Optional[dict], Optional[str]]:
        """Rate for `on`, else the most recent one before it.

        Returns (row, flag). The flag is None for an exact match and names the
        staleness otherwise, so carrying a rate forward always leaves a trace
        on the record it touched.
        """
        base, quote = base.upper(), quote.upper()
        candidates = [r for r in self.rows if r["base"] == base and r["quote"] == quote]
        if not candidates:
            return None, f"fx_no_rate_for_{base.lower()}_{quote.lower()}"

        exact = [r for r in candidates if r["date"] == on]
        if exact:
            return exact[-1], None

        prior = [r for r in candidates if r["date"] < on]
        if not prior:
            return None, "fx_no_rate_on_or_before_reference_date"

        row = prior[-1]
        gap = (on - row["date"]).days
        if gap > MAX_CARRY_FORWARD_DAYS:
            return row, f"fx_rate_stale_{gap}_days"
        return row, f"fx_rate_carried_forward_{gap}_days"

    def add(self, on: date, base: str, quote: str, rate: float, source: str,
            path: Path = RATES_PATH) -> None:
        path.parent.mkdir(parents=True, exist_ok=True)
        existing = path.exists()
        with path.open("a", newline="", encoding="utf-8") as fh:
            w = csv.DictWriter(fh, fieldnames=FIELDS)
            if not existing:
                w.writeheader()
            w.writerow({
                "date": on.isoformat(), "base": base.upper(), "quote": quote.upper(),
                "rate": f"{rate:.6f}", "source": source,
                "recorded_at": datetime.now().astimezone().isoformat(timespec="seconds"),
            })


# ----------------------------------------------------------------- converting

def convert_record(rec: dict, table: RateTable, reference: date) -> dict:
    """Return a copy carrying the converted figure and its full provenance."""
    out = dict(rec)
    out.setdefault("cleaning_flags", [])
    flags = list(out["cleaning_flags"])

    currency = (rec.get("asking_rent_currency") or "").upper()
    amount = rec.get("asking_rent_amount")
    period = rec.get("rent_period") or "month"

    if rec.get("rent_monthly_ghs") is not None:
        # Already quoted in cedis. Mirrored into the converted column so one
        # column can be used for training without a currency branch everywhere.
        out["rent_monthly_ghs_converted"] = rec["rent_monthly_ghs"]
        out["fx_rate"] = 1.0
        out["fx_rate_date"] = None
        out["fx_rate_source"] = "quoted in GHS"
        out["cleaning_flags"] = flags
        return out

    out["rent_monthly_ghs_converted"] = None
    out["fx_rate"] = None
    out["fx_rate_date"] = None
    out["fx_rate_source"] = None

    if amount is None or not currency:
        flags.append("fx_not_converted_no_amount")
        out["cleaning_flags"] = flags
        return out

    row, flag = table.lookup(currency, "GHS", reference)
    if flag:
        flags.append(flag)
    if row is None:
        out["cleaning_flags"] = flags
        return out

    from .schema import MONTHS_IN_PERIOD
    months = MONTHS_IN_PERIOD.get(period)
    if not months:
        flags.append(f"fx_not_converted_unknown_period_{period}")
        out["cleaning_flags"] = flags
        return out

    out["rent_monthly_ghs_converted"] = round((amount / months) * row["rate"], 2)
    out["fx_rate"] = row["rate"]
    out["fx_rate_date"] = row["date"].isoformat()
    out["fx_rate_source"] = row["source"]
    flags.append(f"rent_converted_from_{currency.lower()}")
    out["cleaning_flags"] = flags
    return out


def cmd_convert(args) -> int:
    table = RateTable.load(Path(args.rates))
    if not table.rows:
        print(f"No rates in {args.rates}.")
        print("Read today's USD/GHS interbank rate from bog.gov.gh, then:")
        print(f'  python -m rentradar.collect.fx set {date.today()} 12.3100 '
              f'--source "Bank of Ghana interbank"')
        return 1

    reference = date.fromisoformat(args.reference) if args.reference else date.today()
    src_dir, out_dir = Path(args.records), Path(args.out)
    out_dir.mkdir(parents=True, exist_ok=True)

    total = converted = unconverted = 0
    flags = Counter()

    for path in sorted(src_dir.glob("*.jsonl")):
        dest = out_dir / f"{path.stem}_converted.jsonl"
        n = 0
        with path.open(encoding="utf-8") as fh, dest.open("w", encoding="utf-8") as out:
            for line in fh:
                line = line.strip()
                if not line:
                    continue
                try:
                    rec = json.loads(line)
                except json.JSONDecodeError:
                    continue
                new = convert_record(rec, table, reference)
                out.write(json.dumps(new, ensure_ascii=False) + "\n")
                total += 1
                n += 1
                if new["rent_monthly_ghs_converted"] is not None:
                    converted += 1
                else:
                    unconverted += 1
                for f in new["cleaning_flags"]:
                    if f.startswith("fx_") or f.startswith("rent_converted"):
                        flags[f] += 1
        print(f"  {path.name} -> {dest.name}  ({n:,} records)")

    print(f"\nreference date {reference}")
    print(f"{total:,} records, {converted:,} now carry a cedi figure, {unconverted:,} do not")
    if flags:
        print("\nconversion flags:")
        for k, v in flags.most_common():
            print(f"  {v:>6,}  {k}")
    if unconverted:
        print("\nRecords without a cedi figure need a rate for their currency.")
    return 0


def cmd_set(args) -> int:
    table = RateTable([])
    table.add(date.fromisoformat(args.date), args.base, args.quote,
              float(args.rate), args.source, Path(args.rates))
    print(f"{args.base.upper()}/{args.quote.upper()} = {float(args.rate):.4f} "
          f"on {args.date}  [{args.source}]")
    print(f"appended to {args.rates}")
    return 0


def cmd_rates(args) -> int:
    table = RateTable.load(Path(args.rates))
    if not table.rows:
        print(f"No rates recorded in {args.rates}.")
        return 0
    print(f"{len(table.rows)} rates in {args.rates}\n")
    for r in table.rows:
        print(f"  {r['date']}  {r['base']}/{r['quote']}  {r['rate']:>10.4f}   {r['source']}")
    gaps = [
        (a["date"], b["date"], (b["date"] - a["date"]).days)
        for a, b in zip(table.rows, table.rows[1:])
        if (b["date"] - a["date"]).days > MAX_CARRY_FORWARD_DAYS
    ]
    if gaps:
        print(f"\ngaps longer than {MAX_CARRY_FORWARD_DAYS} days:")
        for a, b, n in gaps:
            print(f"  {a} to {b}  ({n} days)")
    return 0


def main(argv=None) -> int:
    p = argparse.ArgumentParser(description="Dated currency conversion")
    p.add_argument("--rates", default=str(RATES_PATH))
    sub = p.add_subparsers(dest="command", required=True)

    s = sub.add_parser("set", help="record one dated rate")
    s.add_argument("date")
    s.add_argument("rate")
    s.add_argument("--base", default="USD")
    s.add_argument("--quote", default="GHS")
    s.add_argument("--source", required=True,
                   help='e.g. "Bank of Ghana interbank, retrieved 2026-10-04"')
    s.set_defaults(func=cmd_set)

    sub.add_parser("rates", help="list recorded rates").set_defaults(func=cmd_rates)

    c = sub.add_parser("convert", help="write converted copies of the records")
    c.add_argument("--records", default="data/records")
    c.add_argument("--out", default="data/interim")
    c.add_argument("--reference", help="reference date, default today")
    c.set_defaults(func=cmd_convert)

    args = p.parse_args(argv)
    return args.func(args)


if __name__ == "__main__":
    sys.exit(main())
