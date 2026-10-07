"""The 100-day city price index.

This is the binding constraint on the project timetable. It cannot be collected
retrospectively, so the series finishes 100 days after the first capture. Run
this once a day, every day, starting now.

Index definition, which must also appear in the data dictionary:

    median asking rent per square metre, for two bedroom unfurnished units,
    per city, per day, in GHS

Two bed is the most consistently populated stratum in all three cities. A
median rather than a mean, because the Accra tail runs to GH 18,000 and a mean
would track the tail rather than the market.
"""

from __future__ import annotations

import argparse
import json
import logging
import statistics
import sys
from datetime import date
from pathlib import Path

log = logging.getLogger("rentradar.snapshot")

# Below this many observations a median is not stable enough to publish, so the
# previous value is carried forward and flagged rather than quietly recomputed.
MIN_OBSERVATIONS = 5

STRATUM = {"bedrooms": 2, "furnished": False}


def load_records(records_dir: Path) -> list[dict]:
    rows: list[dict] = []
    for path in sorted(Path(records_dir).glob("*.jsonl")):
        with path.open(encoding="utf-8") as fh:
            for line in fh:
                line = line.strip()
                if line:
                    rows.append(json.loads(line))
    return rows


def in_stratum(rec: dict) -> bool:
    if rec.get("bedrooms") != STRATUM["bedrooms"]:
        return False
    # Unfurnished only. None is not unfurnished, it is unknown, and treating
    # unknown as unfurnished would quietly mix two populations.
    if rec.get("furnished") is not False:
        return False
    if not rec.get("rent_monthly_ghs"):
        return False
    if not rec.get("area_sqm"):
        return False
    if rec.get("asking_rent_currency") != "GHS":
        return False
    return True


def previous_index(out_dir: Path, city: str) -> float | None:
    files = sorted(Path(out_dir).glob("index_*.json"), reverse=True)
    for path in files:
        try:
            data = json.loads(path.read_text(encoding="utf-8"))
        except json.JSONDecodeError:
            continue
        entry = data.get("cities", {}).get(city)
        if entry and entry.get("index_ghs_per_sqm") is not None:
            return float(entry["index_ghs_per_sqm"])
    return None


def build_snapshot(records_dir: Path, out_dir: Path, cities: list[str]) -> dict:
    out_dir = Path(out_dir)
    out_dir.mkdir(parents=True, exist_ok=True)

    rows = load_records(records_dir)
    today = date.today().isoformat()

    result = {
        "date": today,
        "stratum": "2 bedroom, unfurnished, GHS",
        "statistic": "median asking rent per square metre, GHS",
        "min_observations": MIN_OBSERVATIONS,
        "cities": {},
    }

    for city in cities:
        pool = [
            r["rent_monthly_ghs"] / r["area_sqm"]
            for r in rows
            if r.get("city") == city and in_stratum(r) and r["area_sqm"] > 0
        ]

        if len(pool) >= MIN_OBSERVATIONS:
            result["cities"][city] = {
                "index_ghs_per_sqm": round(statistics.median(pool), 2),
                "observations": len(pool),
                "carried_forward": False,
            }
        else:
            carried = previous_index(out_dir, city)
            result["cities"][city] = {
                "index_ghs_per_sqm": carried,
                "observations": len(pool),
                "carried_forward": True,
                "reason": f"only {len(pool)} observations, minimum is {MIN_OBSERVATIONS}",
            }
            log.warning(
                "%s: %s observations, carrying forward %s", city, len(pool), carried
            )

    # One dated file per snapshot, never an overwritten running file. The series
    # has to be reconstructible and auditable, and an append-only directory of
    # dated files is the cheapest way to guarantee that.
    path = out_dir / f"index_{today}.json"
    path.write_text(json.dumps(result, indent=2), encoding="utf-8")
    log.info("wrote %s", path)
    return result


def series_status(out_dir: Path) -> None:
    files = sorted(Path(out_dir).glob("index_*.json"))
    if not files:
        print("No snapshots yet. Day 0 of 100.")
        return

    first = files[0].stem.replace("index_", "")
    last = files[-1].stem.replace("index_", "")
    print(f"Snapshots: {len(files)}")
    print(f"First: {first}")
    print(f"Latest: {last}")
    print(f"Remaining to reach 100: {max(0, 100 - len(files))}")

    gaps = len(files)
    carried = 0
    for path in files:
        data = json.loads(path.read_text(encoding="utf-8"))
        for entry in data.get("cities", {}).values():
            if entry.get("carried_forward"):
                carried += 1
    print(f"Carried-forward city-days: {carried}")
    if carried > gaps:
        print("Warning: more than one carry-forward per day on average.")
        print("The stratum may be too narrow for the thinner cities.")


def main(argv=None) -> int:
    logging.basicConfig(level=logging.INFO, format="%(levelname)s %(message)s")

    parser = argparse.ArgumentParser(description="Daily city rent index snapshot")
    parser.add_argument("--records", default="data/records")
    parser.add_argument("--out", default="data/series")
    parser.add_argument(
        "--cities",
        nargs="+",
        default=["Accra", "Kumasi", "Sekondi-Takoradi"],
    )
    parser.add_argument("--status", action="store_true", help="report series progress")
    args = parser.parse_args(argv)

    if args.status:
        series_status(Path(args.out))
        return 0

    snapshot = build_snapshot(Path(args.records), Path(args.out), args.cities)
    for city, entry in snapshot["cities"].items():
        mark = " (carried forward)" if entry["carried_forward"] else ""
        print(f"{city:20s} {entry['index_ghs_per_sqm']}  n={entry['observations']}{mark}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
