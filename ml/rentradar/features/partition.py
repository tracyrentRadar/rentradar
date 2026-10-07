"""Train, validation and test splits, one scheme per model.

    python -m rentradar.features.partition --features data/features --records data/interim

This answers supervisor comment 10 directly, and goes a step past it. The
comment said a random split leaks future information into a time series model.
That is true, and it is not the only leak in this corpus.

Three models, three different things that must not cross the split:

  PRICE — stratified random, by locality.
    What matters is that East Legon appears in all three folds in roughly the
    proportion it occurs. A plain random split on 3,199 rows where one suburb
    holds a third of them can leave a test fold with almost no Cantonments,
    and the reported error then describes a market that was barely tested.

  FRAUD — grouped by agent, never random.
    Forty-seven agents account for the whole corpus and the largest holds
    fifty-seven listings. Those listings share wording, pricing habits and
    photographs. If an agent's listings sit in both train and test, the model
    learns to recognise the agent and scores beautifully on a skill that is
    useless the moment a new agent appears. Every listing from one agent goes
    into exactly one fold.

  FORECAST — temporal, never random.
    Ordered by posting date: earliest for training, latest for test. A random
    split lets the model see next month while predicting this one, which
    produces an excellent number and a worthless model.

Each split is written with its seed, its scheme and its counts, so any of them
can be reproduced exactly from the file alone.
"""

from __future__ import annotations

import argparse
import json
import random
import sys
from collections import Counter, defaultdict
from datetime import date, datetime
from pathlib import Path

RATIOS = (0.70, 0.15, 0.15)
SEED = 42


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


def _cut(n: int) -> tuple[int, int]:
    a = int(round(n * RATIOS[0]))
    b = a + int(round(n * RATIOS[1]))
    return a, min(b, n)


def stratified_split(index: list[int], keys: list[str], seed: int) -> dict:
    """Random within each stratum, so every stratum is represented in all
    three folds in roughly its own proportion."""
    rng = random.Random(seed)
    buckets: dict[str, list[int]] = defaultdict(list)
    for i, k in zip(index, keys):
        buckets[k].append(i)

    train, val, test = [], [], []
    for k in sorted(buckets):
        rows = buckets[k][:]
        rng.shuffle(rows)
        a, b = _cut(len(rows))
        train += rows[:a]; val += rows[a:b]; test += rows[b:]
    return {"train": sorted(train), "val": sorted(val), "test": sorted(test)}


def grouped_split(index: list[int], groups: list[str], seed: int) -> dict:
    """Whole groups move together. Sorted largest first then filled greedily,
    because a handful of agents hold most of the corpus and shuffling alone
    produces wildly uneven folds."""
    rng = random.Random(seed)
    by_group: dict[str, list[int]] = defaultdict(list)
    for i, g in zip(index, groups):
        by_group[g].append(i)

    order = sorted(by_group, key=lambda g: (-len(by_group[g]), g))
    rng.shuffle(order[: max(1, len(order) // 10)])   # jitter the tail only

    total = len(index)
    want = {"train": total * RATIOS[0], "val": total * RATIOS[1], "test": total * RATIOS[2]}
    folds = {"train": [], "val": [], "test": []}
    for g in order:
        short = max(want, key=lambda f: want[f] - len(folds[f]))
        folds[short] += by_group[g]
    return {k: sorted(v) for k, v in folds.items()}


def temporal_split(index: list[int], dates: list[str]) -> dict:
    """Earliest to latest. No shuffling, no seed: the ordering is the point."""
    def parse(s):
        try:
            return datetime.fromisoformat(str(s).replace("Z", "+00:00")).date()
        except (ValueError, TypeError):
            return date.min
    ordered = [i for _, i in sorted(zip([parse(d) for d in dates], index))]
    a, b = _cut(len(ordered))
    return {"train": ordered[:a], "val": ordered[a:b], "test": ordered[b:]}


def summarise(name: str, split: dict, rows: list[dict], key) -> dict:
    out = {"scheme": name, "counts": {k: len(v) for k, v in split.items()}}
    if key:
        out["distribution"] = {
            fold: dict(Counter(key(rows[i]) for i in idx).most_common(5))
            for fold, idx in split.items()
        }
    return out


def main(argv=None) -> int:
    p = argparse.ArgumentParser(description="Component-specific train/val/test splits")
    p.add_argument("--features", default="data/features")
    p.add_argument("--records", default="data/interim")
    p.add_argument("--seed", type=int, default=SEED)
    args = p.parse_args(argv)

    feat = Path(args.features)
    ids_path = feat / "row_ids.json"
    if not ids_path.exists():
        print(f"{ids_path} not found. Run the feature build first.")
        return 1
    wanted = json.loads(ids_path.read_text(encoding="utf-8"))

    src = Path(args.records)
    if not src.exists() or not any(src.glob("*.jsonl")):
        src = Path("data/records")
    by_id = {f"{r.get('source')}:{r.get('source_record_id')}": r for r in load_records(src)}
    rows = [by_id[i] for i in wanted if i in by_id]
    if len(rows) != len(wanted):
        print(f"warning: {len(wanted) - len(rows)} feature rows had no matching record")
    index = list(range(len(rows)))

    price = stratified_split(index, [r.get("locality") or "unknown" for r in rows], args.seed)
    fraud = grouped_split(index, [r.get("agent_phone_hash") or f"none-{i}"
                                  for i, r in enumerate(rows)], args.seed)
    forecast = temporal_split(index, [r.get("posted_date") for r in rows])

    # Leak checks, asserted rather than assumed.
    agent_of = {i: (rows[i].get("agent_phone_hash") or f"none-{i}") for i in index}
    leaked = (set(agent_of[i] for i in fraud["train"])
              & set(agent_of[i] for i in fraud["test"]))
    overlaps = {
        name: len(set(s["train"]) & set(s["test"]))
        for name, s in (("price", price), ("fraud", fraud), ("forecast", forecast))
    }

    payload = {
        "seed": args.seed,
        "ratios": {"train": RATIOS[0], "val": RATIOS[1], "test": RATIOS[2]},
        "n_rows": len(rows),
        "price": {**summarise("stratified by locality", price, rows,
                              lambda r: r.get("locality") or "unknown"),
                  "indices": price},
        "fraud": {**summarise("grouped by agent", fraud, rows, None),
                  "indices": fraud,
                  "agents_shared_between_train_and_test": len(leaked)},
        "forecast": {**summarise("temporal, earliest to latest", forecast, rows, None),
                     "indices": forecast},
    }
    out = feat / "splits.json"
    out.write_text(json.dumps(payload, indent=2), encoding="utf-8")

    print(f"{len(rows):,} rows, seed {args.seed}\n")
    for name in ("price", "fraud", "forecast"):
        c = payload[name]["counts"]
        print(f"{name:9s} {payload[name]['scheme']:30s} "
              f"train {c['train']:>5,}  val {c['val']:>5,}  test {c['test']:>5,}")
    print()
    print(f"row overlap train/test: {overlaps}  (must all be 0)")
    print(f"agents in both fraud train and test: {len(leaked)}  (must be 0)")

    def span(idx):
        ds = sorted(str(rows[i].get("posted_date") or "")[:10] for i in idx if rows[i].get("posted_date"))
        return f"{ds[0]} to {ds[-1]}" if ds else "no dates"
    print()
    print("forecast folds are strictly ordered in time:")
    for f in ("train", "val", "test"):
        print(f"  {f:6s} {span(forecast[f])}")
    print(f"\nwrote {out}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
