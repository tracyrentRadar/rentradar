"""Records to a model matrix, and the spec that makes it reproducible.

    python -m rentradar.features.build --records data/clean --out data/features

The spec is the point. Training builds a matrix from thousands of records;
serving builds one from a single request typed into a phone. If those two
disagree about column order, about which localities exist, or about what to put
in a field the user left blank, the model silently scores nonsense and nothing
in the stack notices. So every decision this module makes is written to
feature_spec.json, and inference reads that file rather than re-deriving
anything.

Decisions taken here, each with its reason:

  Target is log rent. Accra rents run from 350 to 250,000 GHS a month. Fitting
  on the raw figure lets the luxury tail dominate the loss and the model
  becomes a mansion detector. log1p pulls it to something roughly symmetric;
  predictions are exponentiated back before anyone sees them.

  Missing is a value, but only within one source. Whether an agent bothered to
  fill a field is informative when every listing comes from the same site.
  Across two sites it is something worse: if one source fills a field and the
  other never does, the missingness indicator is a label saying which website
  the listing came from. The two sources here sit at opposite ends of the
  market, GPC at a 20,312 median and Jiji at 6,000, so that label predicts
  price beautifully and teaches the model nothing about property. Every
  numeric column kept below was checked for this.

  Furnished is two states, not three. The earlier version had yes, no and
  unknown, on the reasoning that "nobody said" is not the same as "not
  furnished". That is correct within one source and wrong across two: Ghana
  Property Centre never emits False at all, so "no" can only ever mean Jiji.
  Collapsing to stated-furnished against everything else removes the source
  channel and keeps the attribute.

  Localities are capped. There is a long tail of suburbs with one listing
  each; a column for each is noise with a heading. Anything under the
  threshold becomes "other", and the threshold is recorded.

  Amenities are kept only where they vary. A feature present on 2 per cent of
  rows, or on 98 per cent, carries almost nothing and costs a column.

Fields deliberately excluded from the feature set, and why:

  area_sqm        Both sources publish plot area, not the floor area of the
                  dwelling. GPC labels it "Plot size" and Jiji "Property
                  Size". A user entering the floor area of their flat would be
                  scored against a model trained on land, which is a training
                  and serving skew the app cannot detect.

  parking_spaces  Present on 73.4 per cent of GPC rows and 0.0 per cent of
                  Jiji rows. There is no reading of that under which it is a
                  property feature in this corpus.
"""

from __future__ import annotations

import argparse
import json
import math
import sys
from collections import Counter
from datetime import date
from pathlib import Path
from typing import Optional

import numpy as np

SPEC_VERSION = "v2"

# A locality needs at least this many listings to get its own column.
MIN_LOCALITY_COUNT = 15
# An amenity must appear on at least this share of rows, and at most this much,
# to be worth a column.
AMENITY_MIN_SHARE = 0.08
AMENITY_MAX_SHARE = 0.97

# A monthly rent outside this band in cedis is not a rent. The top of the real
# Accra market is a Cantonments house around 12,000 dollars a month, roughly
# 148,000 cedis, so the ceiling leaves generous headroom and still excludes the
# sale prices and typing errors that reach seven figures. One such row at
# 8,001,500 is six standard deviations from the mean in log space and would
# dominate the fit on its own.
RENT_FLOOR_GHS = 300.0
RENT_CEILING_GHS = 300_000.0

# parking_spaces is absent from this list on purpose, see the module docstring.
NUMERIC = ["bedrooms", "bathrooms", "toilets"]
CATEGORICAL = ["property_type", "locality"]

# Two states, not three. See the module docstring.
FURNISHED_STATES = ["yes", "not_stated"]


def load_records(path: Path) -> list[dict]:
    rows: list[dict] = []
    files = sorted(path.glob("*.jsonl")) if path.is_dir() else [path]
    for f in files:
        for line in f.read_text(encoding="utf-8").splitlines():
            line = line.strip()
            if line:
                try:
                    rows.append(json.loads(line))
                except json.JSONDecodeError:
                    continue
    return rows


def target_of(rec: dict) -> Optional[float]:
    """Monthly rent in cedis, converted or quoted.

    `rent_monthly_ghs` is only populated for listings actually quoted in cedis.
    `rent_monthly_ghs_converted` is what fx.py writes, carrying its rate and
    date on the record. Preferring the converted column is what keeps the third
    of the corpus priced in dollars inside the training set.
    """
    v = rec.get("rent_monthly_ghs_converted")
    if v is None:
        v = rec.get("rent_monthly_ghs")
    try:
        v = float(v)
    except (TypeError, ValueError):
        return None
    return v if v > 0 else None


def usable(rec: dict, exclude_flagged: bool) -> bool:
    v = target_of(rec)
    if v is None:
        return False
    if not (RENT_FLOOR_GHS <= v <= RENT_CEILING_GHS):
        return False
    if rec.get("bedrooms") is None:
        return False
    if exclude_flagged:
        flags = rec.get("cleaning_flags") or []
        if any(f.endswith("_review") for f in flags):
            return False
    return True


def furnished_state(rec: dict) -> str:
    """Stated furnished, or everything else.

    False and None are deliberately the same bucket. One source says False and
    the other never does, so keeping them apart encodes the source.
    """
    return "yes" if rec.get("furnished") is True else "not_stated"


def build_spec(rows: list[dict]) -> dict:
    """Derive every encoding decision from the training rows, once."""
    n = len(rows)

    loc_counts = Counter(r.get("locality") or "unknown" for r in rows)
    localities = sorted(k for k, c in loc_counts.items() if c >= MIN_LOCALITY_COUNT)

    type_counts = Counter(r.get("property_type") or "unknown" for r in rows)
    types = sorted(type_counts)

    am_counts = Counter(a for r in rows for a in set(r.get("amenities") or []))
    amenities = sorted(
        a for a, c in am_counts.items()
        if AMENITY_MIN_SHARE <= c / n <= AMENITY_MAX_SHARE
    )

    # Medians from the training rows only. Computing them over everything would
    # leak the validation and test distributions into the imputation.
    medians = {}
    for col in NUMERIC:
        vals = [float(r[col]) for r in rows if r.get(col) is not None]
        medians[col] = float(np.median(vals)) if vals else 0.0

    return {
        "spec_version": SPEC_VERSION,
        "built_on": date.today().isoformat(),
        "n_training_rows": n,
        "target": "log1p(rent_monthly_ghs_converted)",
        "numeric": NUMERIC,
        "numeric_medians": medians,
        "excluded": {
            "area_sqm": "plot area, not dwelling floor area, on both sources",
            "parking_spaces": "73.4% present on GPC, 0.0% on Jiji, a source label",
        },
        "localities": localities,
        "locality_other": "other",
        "min_locality_count": MIN_LOCALITY_COUNT,
        "property_types": types,
        "amenities": amenities,
        "amenity_share_bounds": [AMENITY_MIN_SHARE, AMENITY_MAX_SHARE],
        "furnished_states": FURNISHED_STATES,
        "columns": None,   # filled by column_names()
    }


def column_names(spec: dict) -> list[str]:
    cols: list[str] = []
    for c in spec["numeric"]:
        cols.append(f"num__{c}")
        cols.append(f"miss__{c}")
    for t in spec["property_types"]:
        cols.append(f"type__{t}")
    for l in spec["localities"]:
        cols.append(f"loc__{l}")
    cols.append("loc__other")
    for s in spec["furnished_states"]:
        cols.append(f"furn__{s}")
    for a in spec["amenities"]:
        cols.append(f"am__{a}")
    cols.append("num__amenity_count")
    return cols


def encode_one(rec: dict, spec: dict) -> list[float]:
    """One record to one row. This is the function the serving path calls too,
    which is the whole reason it takes a spec rather than a fitted object."""
    out: list[float] = []
    for c in spec["numeric"]:
        v = rec.get(c)
        missing = v is None
        try:
            v = float(v) if not missing else spec["numeric_medians"][c]
        except (TypeError, ValueError):
            v, missing = spec["numeric_medians"][c], True
        out.append(v)
        out.append(1.0 if missing else 0.0)

    t = rec.get("property_type") or "unknown"
    for cand in spec["property_types"]:
        out.append(1.0 if cand == t else 0.0)

    loc = rec.get("locality") or "unknown"
    known = loc in spec["localities"]
    for cand in spec["localities"]:
        out.append(1.0 if cand == loc else 0.0)
    out.append(0.0 if known else 1.0)

    f = furnished_state(rec)
    for cand in spec["furnished_states"]:
        out.append(1.0 if cand == f else 0.0)

    ams = set(rec.get("amenities") or [])
    for cand in spec["amenities"]:
        out.append(1.0 if cand in ams else 0.0)
    out.append(float(len(ams)))
    return out


def encode_all(rows: list[dict], spec: dict) -> tuple[np.ndarray, np.ndarray]:
    X = np.asarray([encode_one(r, spec) for r in rows], dtype=np.float64)
    y = np.asarray([math.log1p(target_of(r)) for r in rows], dtype=np.float64)
    return X, y


def main(argv=None) -> int:
    p = argparse.ArgumentParser(description="Build the model matrix and its spec")
    p.add_argument("--records", default="data/clean",
                   help="deduped records; falls back to data/interim then data/records")
    p.add_argument("--out", default="data/features")
    p.add_argument("--keep-flagged", action="store_true",
                   help="keep records flagged for manual review (default drops them)")
    args = p.parse_args(argv)

    src = Path(args.records)
    for fallback in ("data/interim", "data/records"):
        if src.exists() and any(src.glob("*.jsonl")):
            break
        src = Path(fallback)
    rows_all = load_records(src)
    if not rows_all:
        print(f"No records found under {src}.")
        return 1

    rows = [r for r in rows_all if usable(r, not args.keep_flagged)]
    dropped = len(rows_all) - len(rows)

    # Say why rows left, rather than reporting one opaque total.
    no_target = sum(1 for r in rows_all if target_of(r) is None)
    out_of_band = sum(1 for r in rows_all
                      if target_of(r) is not None
                      and not (RENT_FLOOR_GHS <= target_of(r) <= RENT_CEILING_GHS))
    no_beds = sum(1 for r in rows_all
                  if target_of(r) is not None and r.get("bedrooms") is None)
    if not rows:
        print(f"{len(rows_all):,} records loaded but none usable.")
        print("Most likely the FX conversion has not been run, so every dollar")
        print("listing still has a null cedi figure. Run:")
        print('  py -m rentradar.collect.fx set <date> <rate> --source "Bank of Ghana interbank"')
        print("  py -m rentradar.collect.fx convert")
        return 1

    spec = build_spec(rows)
    spec["columns"] = column_names(spec)
    X, y = encode_all(rows, spec)

    out = Path(args.out)
    out.mkdir(parents=True, exist_ok=True)
    np.savez_compressed(out / "matrix.npz", X=X, y=y)
    (out / "feature_spec.json").write_text(json.dumps(spec, indent=2), encoding="utf-8")
    ids = [f"{r.get('source')}:{r.get('source_record_id')}" for r in rows]
    (out / "row_ids.json").write_text(json.dumps(ids), encoding="utf-8")

    rent = np.expm1(y)
    print(f"source            {src}")
    print(f"records loaded    {len(rows_all):,}")
    print(f"usable            {len(rows):,}   (dropped {dropped:,})")
    if dropped:
        print(f"  no rent figure  {no_target:,}")
        print(f"  rent outside {RENT_FLOOR_GHS:,.0f} to {RENT_CEILING_GHS:,.0f} GHS  {out_of_band:,}")
        print(f"  no bedrooms     {no_beds:,}")
    print(f"matrix            {X.shape[0]:,} x {X.shape[1]}")
    print()
    print(f"excluded          {', '.join(spec['excluded'])}")
    print(f"localities kept   {len(spec['localities'])} of "
          f"{len(set(r.get('locality') or 'unknown' for r in rows))} seen")
    print(f"property types    {len(spec['property_types'])}")
    print(f"amenities kept    {len(spec['amenities'])}")
    print()
    print(f"rent GHS/month    min {rent.min():,.0f}  median {np.median(rent):,.0f}  "
          f"max {rent.max():,.0f}")
    print(f"target (log)      mean {y.mean():.3f}  sd {y.std():.3f}")
    print()
    print(f"wrote {out/'matrix.npz'}")
    print(f"wrote {out/'feature_spec.json'}   <- serving must read this, not re-derive it")
    return 0


if __name__ == "__main__":
    sys.exit(main())