"""Isolation forest fraud baseline, on mismatch features rather than property ones.

The first version of this ran the forest over the full 124 column model matrix
and found luxury. That is what an outlier detector does: it finds what is rare,
and in a corpus of mid-market flats the rare thing is a five bedroom Cantonments
house. The correlation between anomaly score and price ratio came out at +0.178,
dearer flagged as stranger, which is the opposite of a rental scam.

Fraud is not a property being unusual. It is a price not fitting what the
listing claims to be. So this version describes the mismatch:

  log_price_ratio       how far the rent sits from the going rate for its own
                        locality and bedroom count, in logs so that half price
                        and double price are symmetric
  bath_per_bed          a 1 bedroom with 5 bathrooms is a listing nobody read
  toilet_per_bed        same idea, independently stated
  amenity_count         how much the listing claims
  amenities_per_price   luxury claims at a budget price, the classic bait
  agent_locality_spread one contact advertising across unrelated areas
  agent_listing_count   volume from a single contact
  missing_fields        how much of the listing was never filled in

Eight columns, every one a sentence a tenant would understand, which is what
lets the assessment name the factor that drove it.

The partition is grouped by agent, built here if splits.json does not carry one,
so no contact appears in both training and test. Without that the detector can
memorise an agent instead of learning a pattern.

    python -m rentradar.models.fraud_baseline fit
    python -m rentradar.models.fraud_baseline sample --n 200
    python -m rentradar.models.fraud_baseline score --labels data/labels/fraud_labelled.csv
"""

from __future__ import annotations

import argparse
import csv
import json
import math
import random
import statistics
import sys
from collections import Counter, defaultdict
from pathlib import Path
from typing import Optional

import numpy as np

RECORDS = Path("data/clean")
ARTEFACTS = Path("artifacts/models")
LABELS = Path("data/labels")

CHEAP_RATIO = 0.60
MIN_CELL = 8
MIN_CONTAMINATION = 0.01
MAX_CONTAMINATION = 0.20

FEATURE_NAMES = [
    "below_market",           # how far BELOW the cell median, 0 if at or above
    "bath_per_bed",
    "toilet_per_bed",
    "amenity_count",
    "amenity_excess",         # claims more than its neighbours claim
    "agent_locality_spread",
    "agent_volume_log",       # logged, so a busy agency is not an outlier
    "missing_fields",
]

KEY_FIELDS = ["bathrooms", "toilets", "property_type", "furnished", "amenities"]
URL_KEYS = ["listing_url", "url", "source_url", "page_url", "advert_url"]


def section(title: str) -> None:
    print("\n" + "-" * 72)
    print(title)
    print("-" * 72)


def load_records(path: Path) -> list[dict]:
    rows = []
    for f in sorted(path.glob("*.jsonl")):
        with f.open(encoding="utf-8") as fh:
            for line in fh:
                line = line.strip()
                if line:
                    try:
                        rows.append(json.loads(line))
                    except json.JSONDecodeError:
                        pass
    return rows


def rent_of(rec: dict) -> Optional[float]:
    v = rec.get("rent_monthly_ghs_converted")
    if v is None:
        v = rec.get("rent_monthly_ghs")
    try:
        v = float(v)
    except (TypeError, ValueError):
        return None
    return v if v > 0 else None


def url_of(rec: dict) -> str:
    for k in URL_KEYS:
        v = rec.get(k)
        if isinstance(v, str) and v.startswith("http"):
            return v
    return ""


def agent_of(rec: dict) -> str:
    for k in ("agent_phone_hash", "agent_id", "seller_hash", "listing_url_hash"):
        v = rec.get(k)
        if v:
            return str(v)
    return "unknown"


def build_features(records: list[dict]):
    """Eight mismatch features, plus the bits the worksheet needs.

    Three deliberate choices here, each one a correction to an earlier version.

    Price is one sided. Only how far BELOW the local norm a listing sits
    counts. A listing at twenty times its neighbours is a data error, and
    being overpriced is what the price estimate is for. Fraud is cheap for
    what it claims, so the feature is clipped at zero above the median.

    Missing is imputed, not zeroed. Eighteen per cent of one source has no
    toilet count. Reading that as "zero toilets" made absent data look like a
    strange property, which is the same fault removed from the price model.
    The absence is still recorded, once, in missing_fields.

    Agent volume is logged. A firm with three hundred listings is a firm. The
    raw count made every busy agency an outlier and it was the single largest
    driver of flags.
    """
    cells = defaultdict(list)
    cell_amenities = defaultdict(list)
    for r in records:
        v = rent_of(r)
        loc, bed = r.get("locality"), r.get("bedrooms")
        if v and loc and bed is not None:
            cells[(str(loc), int(bed))].append(v)
            cell_amenities[(str(loc), int(bed))].append(len(r.get("amenities") or []))
    medians = {k: statistics.median(v) for k, v in cells.items() if len(v) >= MIN_CELL}
    am_medians = {k: statistics.median(v) for k, v in cell_amenities.items()
                  if len(v) >= MIN_CELL}

    def corpus_median(key: str, default: float) -> float:
        vals = []
        for r in records:
            try:
                vals.append(float(r[key]))
            except (KeyError, TypeError, ValueError):
                continue
        return statistics.median(vals) if vals else default

    med_bath = corpus_median("bathrooms", 1.0)
    med_toilet = corpus_median("toilets", 1.0)
    med_amenity = statistics.median(
        [len(r.get("amenities") or []) for r in records]) if records else 0.0

    agent_locs = defaultdict(set)
    agent_n = Counter()
    for r in records:
        a = agent_of(r)
        agent_n[a] += 1
        if r.get("locality"):
            agent_locs[a].add(str(r["locality"]))

    rows, ratios, keep = [], [], []
    for i, r in enumerate(records):
        v = rent_of(r)
        if v is None:
            continue
        try:
            bed = max(1, int(r.get("bedrooms")))
        except (TypeError, ValueError):
            continue

        cell = (str(r.get("locality")), bed)
        med = medians.get(cell)
        ratio = v / med if med else None
        # One sided. Zero means at or above the local norm, negative means below.
        below = min(0.0, math.log(ratio)) if ratio else 0.0

        def num(key, fallback):
            try:
                return float(r.get(key))
            except (TypeError, ValueError):
                return fallback

        ams = len(r.get("amenities") or [])
        excess = ams - am_medians.get(cell, med_amenity)
        missing = sum(1 for k in KEY_FIELDS if not r.get(k))
        a = agent_of(r)

        rows.append([
            below,
            num("bathrooms", med_bath) / bed,
            num("toilets", med_toilet) / bed,
            float(ams),
            float(excess),
            float(len(agent_locs[a])),
            math.log1p(agent_n[a]),
            float(missing),
        ])
        ratios.append(ratio)
        keep.append(i)

    return np.asarray(rows, dtype=np.float64), ratios, keep, medians
    """Eight mismatch features, plus the bits the worksheet needs."""
    cells = defaultdict(list)
    for r in records:
        v = rent_of(r)
        loc, bed = r.get("locality"), r.get("bedrooms")
        if v and loc and bed is not None:
            cells[(str(loc), int(bed))].append(v)
    medians = {k: statistics.median(v) for k, v in cells.items() if len(v) >= MIN_CELL}

    agent_locs = defaultdict(set)
    agent_n = Counter()
    for r in records:
        a = agent_of(r)
        agent_n[a] += 1
        if r.get("locality"):
            agent_locs[a].add(str(r["locality"]))

    rows, ratios, keep = [], [], []
    for i, r in enumerate(records):
        v = rent_of(r)
        if v is None:
            continue
        bed = r.get("bedrooms")
        try:
            bed = max(1, int(bed))
        except (TypeError, ValueError):
            continue

        med = medians.get((str(r.get("locality")), bed))
        ratio = v / med if med else None
        # No cell means no comparison, so it sits at the neutral value rather
        # than being dropped. Dropping it would silently exclude every rare
        # locality, which is where an unusual listing is most likely to be.
        log_ratio = math.log(ratio) if ratio else 0.0

        def num(key, default=0.0):
            try:
                return float(r.get(key))
            except (TypeError, ValueError):
                return default

        ams = r.get("amenities") or []
        missing = sum(1 for k in KEY_FIELDS if not r.get(k))
        a = agent_of(r)

        rows.append([
            log_ratio,
            num("bathrooms") / bed,
            num("toilets") / bed,
            float(len(ams)),
            len(ams) / (v / 1000.0),
            float(len(agent_locs[a])),
            float(agent_n[a]),
            float(missing),
        ])
        ratios.append(ratio)
        keep.append(i)

    return np.asarray(rows, dtype=np.float64), ratios, keep, medians


def agent_groups(records: list[dict], keep: list[int]) -> list[str]:
    return [agent_of(records[i]) for i in keep]


def grouped_split(groups: list[str], seed: int = 42):
    """Train and test with no agent on both sides."""
    by_agent = defaultdict(list)
    for i, g in enumerate(groups):
        by_agent[g].append(i)
    agents = sorted(by_agent)
    random.Random(seed).shuffle(agents)
    cut = int(len(agents) * 0.70)
    train = [i for a in agents[:cut] for i in by_agent[a]]
    test = [i for a in agents[cut:] for i in by_agent[a]]
    return train, test, len(agents)


def save_state(keep, ids, scores, flags, ratios):
    ARTEFACTS.mkdir(parents=True, exist_ok=True)
    np.savez_compressed(ARTEFACTS / "fraud_scores.npz",
                        scores=scores, flags=flags,
                        keep=np.asarray(keep, dtype=np.int64))
    (ARTEFACTS / "fraud_rowmap.json").write_text(
        json.dumps({"ids": ids}), encoding="utf-8")


# ------------------------------------------------------------------ fit
def cmd_fit(args) -> int:
    try:
        from sklearn.ensemble import IsolationForest
    except Exception as e:
        print(f"scikit-learn did not import: {type(e).__name__}: {e}")
        return 1

    records = load_records(RECORDS)
    if not records:
        print(f"No records under {RECORDS}.")
        return 1

    X, ratios, keep, medians = build_features(records)
    n = X.shape[0]
    ids = [f"{records[i].get('source')}:{records[i].get('source_record_id')}"
           for i in keep]
    print(f"records           {len(records):,}")
    print(f"usable            {n:,} rows x {X.shape[1]} mismatch features")
    print(f"cells             {len(medians):,} of (locality, bedrooms) with "
          f"{MIN_CELL}+ listings")

    groups = agent_groups(records, keep)
    train_idx, test_idx, n_agents = grouped_split(groups)
    print(f"agents            {n_agents:,}")
    print(f"partition         train {len(train_idx):,}, test {len(test_idx):,}, "
          f"grouped by agent, no overlap")

    scored = [r for r in ratios if r is not None]
    cheap = sum(1 for r in scored if r < CHEAP_RATIO)
    rate = cheap / len(scored) if scored else 0.0
    contamination = min(MAX_CONTAMINATION, max(MIN_CONTAMINATION, rate))

    section("Contamination, and why this number")
    print(f"  listings with a comparable cell   {len(scored):,} of {n:,}")
    print(f"  asking below {CHEAP_RATIO:.0%} of cell median  {cheap:,}")
    print(f"  contamination used                {contamination:.3f}")
    print("\n  Not the 0.1 default. This is the share priced far below the")
    print("  going rate for their own locality and bedroom count, which is")
    print("  the shape a rental scam takes: the bait is the price.")

    forest = IsolationForest(n_estimators=300, contamination=contamination,
                             random_state=42, n_jobs=-1)
    forest.fit(X[train_idx])
    scores = -forest.score_samples(X)
    flags = forest.predict(X) == -1

    section("Is it finding cheap listings now, or expensive ones?")
    pairs = [(s, r) for s, r in zip(scores, ratios) if r is not None]
    if pairs:
        corr = float(np.corrcoef([p[0] for p in pairs], [p[1] for p in pairs])[0, 1])
        print(f"  correlation of anomaly score with price ratio   {corr:+.3f}")
        print("  Negative is what you want. A scam is cheap for what it claims.")
        print("  The previous version scored +0.178 on the full feature matrix,")
        print("  which is why that version was wrong.")
    flagged_cheap = sum(1 for r, f in zip(ratios, flags)
                        if f and r is not None and r < CHEAP_RATIO)
    total_flagged = int(flags.sum())
    if total_flagged:
        print(f"\n  flagged                      {total_flagged:,} of {n:,} "
              f"({flags.mean() * 100:.1f}%)")
        print(f"  of those, far below norm     {flagged_cheap:,} "
              f"({flagged_cheap / total_flagged * 100:.0f}%)")

    # ------------------------------------------- dominant factor
    mu, sd = X.mean(axis=0), X.std(axis=0)
    sd[sd == 0] = 1.0
    z = (X - mu) / sd

    def dominant(i: int) -> str:
        j = int(np.argmax(np.abs(z[i])))
        return f"{FEATURE_NAMES[j]} {z[i][j]:+.1f} sd"

    section("Most anomalous listings, and what drove each one")
    print(f"  {'score':>7}  {'ratio':>6}  {'rent':>9}  {'bed':>3}  "
          f"{'locality':<22} dominant factor")
    for i in np.argsort(-scores)[:12]:
        r = records[keep[i]]
        ratio = ratios[i]
        print(f"  {scores[i]:>7.4f}  "
              f"{(f'{ratio:.2f}' if ratio else '-'):>6}  "
              f"{(rent_of(r) or 0):>9,.0f}  {str(r.get('bedrooms') or '-'):>3}  "
              f"{str(r.get('locality') or '-')[:22]:<22} {dominant(i)}")

    section("Which features carry the flags")
    for j, name in enumerate(FEATURE_NAMES):
        flagged_mean = float(z[flags, j].mean()) if total_flagged else 0.0
        print(f"  {name:<24} flagged rows average {flagged_mean:+.2f} sd")

    save_state(keep, ids, scores, flags, ratios)
    (ARTEFACTS / "fraud_baseline_results.json").write_text(json.dumps({
        "model": "IsolationForest",
        "features": FEATURE_NAMES,
        "n_estimators": 300,
        "random_state": 42,
        "contamination": contamination,
        "contamination_basis": f"share asking below {CHEAP_RATIO} of cell median",
        "rows": int(n),
        "agents": n_agents,
        "train_rows": len(train_idx),
        "test_rows": len(test_idx),
        "flagged": total_flagged,
        "score_ratio_correlation": corr if pairs else None,
        "note": "F1 is not computable until a labelled sample exists. "
                "Run 'sample', label by hand, then run 'score'.",
    }, indent=2), encoding="utf-8")
    print(f"\n  wrote {ARTEFACTS / 'fraud_baseline_results.json'}")
    print("\n  F1 cannot be reported yet. Run 'sample' next.")
    return 0


# --------------------------------------------------------------- sample
RUBRIC = """\
HOW TO LABEL THIS FILE

Put 1 or 0 in the 'suspicious' column of every row. Leave nothing blank.

  1  you would warn a tenant to be careful about this listing
  0  this looks like an ordinary listing

Judge the LISTING, not the person. You are not deciding whether someone is a
criminal. You are deciding whether a careful tenant should slow down.

Reasons to put 1:
  the rent is far below anything comparable in that area, with no explanation
  the details contradict each other, a 1 bedroom with 5 bathrooms
  a luxury description attached to a budget price
  the same contact appears across many unrelated localities
  almost nothing about the property is filled in, yet the price is confident

Reasons NOT to put 1:
  it is simply cheap, in an area that is cheap
  the listing is thin or badly written, which is most of them
  it is expensive, in an area that is expensive
  you dislike the agent

Label every row before looking at any scores. The sample is half high-score
and half random, shuffled, and you are not told which is which. That is what
makes the resulting F1 worth quoting.

Save as CSV when done, then:
  py -m rentradar.models.fraud_baseline score --labels <this file>
"""


def cmd_sample(args) -> int:
    path = ARTEFACTS / "fraud_scores.npz"
    if not path.exists():
        print(f"No scores at {path}. Run 'fit' first.")
        return 1
    data = np.load(path)
    scores, keep = data["scores"], list(data["keep"])
    ids = json.loads((ARTEFACTS / "fraud_rowmap.json").read_text(encoding="utf-8"))["ids"]

    records = load_records(RECORDS)
    _, ratios, keep2, medians = build_features(records)

    n = len(scores)
    half = args.n // 2
    top = list(np.argsort(-scores)[:half])
    rest = [i for i in range(n) if i not in set(top)]
    rng = random.Random(42)
    rng.shuffle(rest)
    chosen = top + rest[: args.n - half]
    rng.shuffle(chosen)

    LABELS.mkdir(parents=True, exist_ok=True)
    out = Path(args.out) if args.out else LABELS / "fraud_sample.csv"
    with out.open("w", newline="", encoding="utf-8") as fh:
        w = csv.writer(fh)
        w.writerow(["row_id", "suspicious", "source", "locality", "bedrooms",
                    "bathrooms", "toilets", "property_type", "furnished",
                    "rent_ghs", "cell_median_ghs", "ratio_to_cell",
                    "amenities", "title", "url"])
        for i in chosen:
            r = records[keep[i]]
            try:
                med = medians.get((str(r.get("locality")), int(r.get("bedrooms"))))
            except (TypeError, ValueError):
                med = None
            w.writerow([
                ids[i], "", r.get("source", ""), r.get("locality", ""),
                r.get("bedrooms", ""), r.get("bathrooms", ""), r.get("toilets", ""),
                r.get("property_type", ""), r.get("furnished", ""),
                f"{rent_of(r) or 0:.0f}",
                f"{med:.0f}" if med else "",
                f"{ratios[i]:.2f}" if i < len(ratios) and ratios[i] else "",
                "; ".join(r.get("amenities") or [])[:120],
                str(r.get("title") or "")[:120],
                url_of(r),
            ])

    guide = out.with_suffix(".txt")
    guide.write_text(RUBRIC, encoding="utf-8")
    section("Labelling worksheet written")
    print(f"  {out}   {len(chosen)} rows")
    print(f"  {guide}  the rubric")
    print(f"\n  {half} from the highest anomaly scores, "
          f"{len(chosen) - half} at random, then shuffled. No score column,")
    print("  so your labels stay independent of the model they will measure.")
    return 0


# ---------------------------------------------------------------- score
def cmd_score(args) -> int:
    path = ARTEFACTS / "fraud_scores.npz"
    if not path.exists():
        print(f"No scores at {path}. Run 'fit' first.")
        return 1
    data = np.load(path)
    scores, flags = data["scores"], data["flags"]
    ids = json.loads((ARTEFACTS / "fraud_rowmap.json").read_text(encoding="utf-8"))["ids"]
    index = {rid: i for i, rid in enumerate(ids)}

    labelled = []
    with Path(args.labels).open(encoding="utf-8-sig", newline="") as fh:
        for row in csv.DictReader(fh):
            rid = (row.get("row_id") or "").strip()
            raw = (row.get("suspicious") or "").strip()
            if rid in index and raw in ("0", "1"):
                labelled.append((index[rid], int(raw)))

    if len(labelled) < 30:
        print(f"Only {len(labelled)} usable labelled rows. "
              "Fill 'suspicious' with 1 or 0 on every row.")
        return 1

    idx = [i for i, _ in labelled]
    y = np.array([v for _, v in labelled])
    pred = flags[idx].astype(int)
    sc = scores[idx]

    tp = int(((pred == 1) & (y == 1)).sum())
    fp = int(((pred == 1) & (y == 0)).sum())
    fn = int(((pred == 0) & (y == 1)).sum())
    tn = int(((pred == 0) & (y == 0)).sum())
    prec = tp / (tp + fp) if tp + fp else 0.0
    rec = tp / (tp + fn) if tp + fn else 0.0
    f1 = 2 * prec * rec / (prec + rec) if prec + rec else 0.0

    section("Measured on the hand-labelled sample")
    print(f"  labelled rows        {len(labelled)}")
    print(f"  marked suspicious    {int(y.sum())} ({y.mean() * 100:.1f}%)")
    print(f"\n  {'':>12}{'pred ordinary':>16}{'pred suspicious':>18}")
    print(f"  {'ordinary':>12}{tn:>16,}{fp:>18,}")
    print(f"  {'suspicious':>12}{fn:>16,}{tp:>18,}")
    print(f"\n  precision            {prec:.3f}")
    print(f"  recall               {rec:.3f}")
    print(f"  F1                   {f1:.3f}")

    auc = None
    try:
        from sklearn.metrics import roc_auc_score
        if 0 < y.sum() < len(y):
            auc = float(roc_auc_score(y, sc))
            print(f"  AUC-ROC              {auc:.3f}")
    except Exception:
        pass

    section("Against the threshold")
    print("  acceptance threshold  F1 >= 0.85")
    print(f"  measured              F1 =  {f1:.3f}")
    print("\n  One labeller, one sample. That is a real limitation and belongs")
    print("  in the write-up next to the number. A second labeller on the same")
    print("  rows would let you report agreement, which is what makes it solid.")

    (ARTEFACTS / "fraud_baseline_scored.json").write_text(json.dumps({
        "labelled_rows": len(labelled),
        "positive_rate": float(y.mean()),
        "confusion": {"tp": tp, "fp": fp, "fn": fn, "tn": tn},
        "precision": prec, "recall": rec, "f1": f1, "auc_roc": auc,
        "labeller_count": 1, "threshold_f1": 0.85,
    }, indent=2), encoding="utf-8")
    print(f"\n  wrote {ARTEFACTS / 'fraud_baseline_scored.json'}")
    return 0


def main(argv=None) -> int:
    p = argparse.ArgumentParser(description="Isolation forest fraud baseline")
    sub = p.add_subparsers(dest="command", required=True)
    f = sub.add_parser("fit"); f.set_defaults(func=cmd_fit)
    s = sub.add_parser("sample")
    s.add_argument("--n", type=int, default=200)
    s.add_argument("--out")
    s.set_defaults(func=cmd_sample)
    c = sub.add_parser("score")
    c.add_argument("--labels", required=True)
    c.set_defaults(func=cmd_score)
    args = p.parse_args(argv)
    return args.func(args)


if __name__ == "__main__":
    sys.exit(main())