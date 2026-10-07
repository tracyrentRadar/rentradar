"""Baselines for the price model, and the number the deep model must beat.

    python -m rentradar.models.baseline --features data/features

Four models, deliberately in order of increasing sophistication:

  Median        predict the training median for everything. Costs nothing and
                is the floor: a model that cannot beat this has learned
                nothing at all.
  Locality      predict the median rent of that locality. Almost free, and on
                property data it is embarrassingly hard to beat, because
                location really is most of the answer.
  Ridge         linear, regularised. Tells you how much of the signal is
                simply additive.
  Gradient      boosted trees. On tabular data of this size and shape this is
  boosting      usually the strongest thing available.

This exists so that the LSTM has something honest to be compared against. If
the deep model cannot beat boosted trees on a 3,199-row tabular problem, that
is a finding worth reporting, not an embarrassment to bury: it is evidence
about the problem, and chapter 5 is more interesting for having it.

Errors are reported in cedis per month, not in log space. A number an examiner
cannot feel is a number they cannot check.
"""

from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

import numpy as np


def mape(actual: np.ndarray, pred: np.ndarray) -> float:
    ok = actual > 0
    return float(np.mean(np.abs(actual[ok] - pred[ok]) / actual[ok]) * 100)


def metrics(y_log_true: np.ndarray, y_log_pred: np.ndarray) -> dict:
    """Score in cedis. The model fits log rent; nobody rents a log."""
    actual = np.expm1(y_log_true)
    pred = np.expm1(y_log_pred)
    err = pred - actual
    ss_res = float(np.sum((actual - pred) ** 2))
    ss_tot = float(np.sum((actual - np.mean(actual)) ** 2))
    return {
        "mae_ghs": float(np.mean(np.abs(err))),
        "median_ae_ghs": float(np.median(np.abs(err))),
        "rmse_ghs": float(np.sqrt(np.mean(err ** 2))),
        "mape_pct": mape(actual, pred),
        "r2": 1.0 - ss_res / ss_tot if ss_tot > 0 else float("nan"),
        "within_10pct": float(np.mean(np.abs(err) / np.maximum(actual, 1) <= 0.10) * 100),
        "within_20pct": float(np.mean(np.abs(err) / np.maximum(actual, 1) <= 0.20) * 100),
    }


def main(argv=None) -> int:
    p = argparse.ArgumentParser(description="Price baselines")
    p.add_argument("--features", default="data/features")
    p.add_argument("--out", default="artifacts/models")
    args = p.parse_args(argv)

    feat = Path(args.features)
    for f in ("matrix.npz", "splits.json", "feature_spec.json"):
        if not (feat / f).exists():
            print(f"{feat/f} not found. Run the feature build and partition first.")
            return 1

    d = np.load(feat / "matrix.npz")
    X, y = d["X"], d["y"]
    splits = json.loads((feat / "splits.json").read_text(encoding="utf-8"))
    spec = json.loads((feat / "feature_spec.json").read_text(encoding="utf-8"))
    cols = spec["columns"]
    idx = splits["price"]["indices"]
    tr, va, te = np.array(idx["train"]), np.array(idx["val"]), np.array(idx["test"])

    try:
        from sklearn.linear_model import Ridge
        from sklearn.ensemble import HistGradientBoostingRegressor, RandomForestRegressor
        from sklearn.preprocessing import StandardScaler
    except ImportError as exc:
        # Printing the real exception matters: "scikit-learn is required" is
        # wrong and misleading when the package is installed and something
        # underneath it, usually a numpy or scipy binary mismatch, is what
        # actually failed.
        print(f"could not import scikit-learn: {exc}")
        print("if scikit-learn is installed, the failure is underneath it. try:")
        print("  py -m pip install --upgrade --force-reinstall numpy scipy scikit-learn")
        return 1

    results = {}

    # 1. Median of the training fold.
    med = float(np.median(y[tr]))
    results["median"] = {
        "val": metrics(y[va], np.full(len(va), med)),
        "test": metrics(y[te], np.full(len(te), med)),
    }

    # 2. Median rent of the listing's own locality, falling back to the global
    #    median where a locality is unseen in training.
    loc_cols = [i for i, c in enumerate(cols) if c.startswith("loc__")]
    def loc_key(row):
        hot = [i for i in loc_cols if row[i] == 1.0]
        return cols[hot[0]] if hot else "loc__other"
    train_keys = [loc_key(X[i]) for i in tr]
    by_loc = {}
    for k, v in zip(train_keys, y[tr]):
        by_loc.setdefault(k, []).append(v)
    loc_med = {k: float(np.median(v)) for k, v in by_loc.items()}
    def loc_predict(rows):
        return np.array([loc_med.get(loc_key(X[i]), med) for i in rows])
    results["locality_median"] = {
        "val": metrics(y[va], loc_predict(va)),
        "test": metrics(y[te], loc_predict(te)),
    }

    # 3. Ridge. Scaler fitted on train only; fitting on everything would leak
    #    the test distribution into the preprocessing.
    sc = StandardScaler().fit(X[tr])
    ridge = Ridge(alpha=1.0).fit(sc.transform(X[tr]), y[tr])
    results["ridge"] = {
        "val": metrics(y[va], ridge.predict(sc.transform(X[va]))),
        "test": metrics(y[te], ridge.predict(sc.transform(X[te]))),
    }

    # 4. Boosted trees.
    gb = HistGradientBoostingRegressor(
        max_iter=400, learning_rate=0.06, max_depth=None,
        min_samples_leaf=15, l2_regularization=1.0, random_state=42,
    ).fit(X[tr], y[tr])
    results["gradient_boosting"] = {
        "val": metrics(y[va], gb.predict(X[va])),
        "test": metrics(y[te], gb.predict(X[te])),
    }

    # 5. Random forest, for a permutation-free importance read.
    rf = RandomForestRegressor(
        n_estimators=300, min_samples_leaf=3, random_state=42, n_jobs=-1,
    ).fit(X[tr], y[tr])
    results["random_forest"] = {
        "val": metrics(y[va], rf.predict(X[va])),
        "test": metrics(y[te], rf.predict(X[te])),
    }

    name_w = max(len(k) for k in results) + 2
    print(f"{len(tr):,} train / {len(va):,} val / {len(te):,} test, "
          f"{X.shape[1]} features\n")
    print(f"{'model':<{name_w}}{'MAE GHS':>10}{'MedAE':>9}{'MAPE %':>9}"
          f"{'R2':>8}{'<=10%':>8}{'<=20%':>8}   (test fold)")
    print("-" * (name_w + 52))
    for k, v in results.items():
        t = v["test"]
        print(f"{k:<{name_w}}{t['mae_ghs']:>10,.0f}{t['median_ae_ghs']:>9,.0f}"
              f"{t['mape_pct']:>9.1f}{t['r2']:>8.3f}"
              f"{t['within_10pct']:>8.1f}{t['within_20pct']:>8.1f}")

    best = min(results, key=lambda k: results[k]["test"]["mae_ghs"])
    print(f"\nbest baseline: {best}")

    # Which features the forest actually leaned on. Useful for chapter 4 and
    # for the "Why this estimate" screen.
    order = np.argsort(rf.feature_importances_)[::-1][:12]
    print("\ntop features by random forest importance:")
    for i in order:
        print(f"  {rf.feature_importances_[i]:>6.3f}  {cols[i]}")

    out = Path(args.out)
    out.mkdir(parents=True, exist_ok=True)
    payload = {
        "spec_version": spec["spec_version"],
        "n_train": len(tr), "n_val": len(va), "n_test": len(te),
        "n_features": int(X.shape[1]),
        "split": splits["price"]["scheme"], "seed": splits["seed"],
        "results": results,
        "best": best,
        "top_features": [{"column": cols[int(i)],
                          "importance": float(rf.feature_importances_[i])} for i in order],
    }
    (out / "baseline_results.json").write_text(json.dumps(payload, indent=2), encoding="utf-8")
    print(f"\nwrote {out/'baseline_results.json'}")
    print("\nThis is the bar. Any deep model goes in the same table or it does not ship.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
