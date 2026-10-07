"""
RentRadar, Phase 9 deliverable 7: export the production model artefacts.

Why this exists
---------------
Until now every model has existed only as numbers printed in a terminal. The
FastAPI service in Phase 10 cannot serve a number that was never saved. This
script trains the final models, saves them with the transformations they need,
and writes one registry file naming every artefact and its version.

Which models get exported
-------------------------
The ones that won their comparison, not the three LSTMs.

  price     gradient boosting, R squared 0.706 against the LSTM's 0.409
  forecast  six month moving average, MAPE 11.3% against the LSTM's 16.2%
  fraud     the LSTM autoencoder, the only fraud model that found anything

The LSTMs stay in the dissertation as tested and beaten. Serving a model that
lost its own comparison would be indefensible.

Two things about the price model
--------------------------------
It is trained on the training partition to measure honestly, then refitted on
training plus validation for production, because throwing away 15 per cent of a
small corpus at serving time helps nobody. Both scores are recorded.

The 90 per cent interval comes from the validation residual distribution, not
from the model. Gradient boosting gives a point estimate only. The interval is
the 5th and 95th percentile of how wrong it was on data it had not seen.

Commands
--------
  py -m rentradar.models.export inspect
  py -m rentradar.models.export run
"""

from __future__ import annotations

import argparse
import json
import math
import sys
from datetime import datetime, timezone
from pathlib import Path

import numpy as np

ROOT = Path(__file__).resolve().parents[2]
FEATURES = ROOT / "data" / "features"
SERIES = ROOT / "data" / "series"
ARTEFACTS = ROOT / "artifacts" / "models"

MATRIX = FEATURES / "matrix.npz"
SPEC = FEATURES / "feature_spec.json"
ROW_IDS = FEATURES / "row_ids.json"
SPLITS = FEATURES / "splits.json"

SERIES_FILE = SERIES / "accra_month_bedrooms.jsonl"
FORECAST_RESULTS = ARTEFACTS / "forecast_lstm_results.json"

FRAUD_MODEL = ARTEFACTS / "fraud_lstm.keras"
FRAUD_SCALERS = ARTEFACTS / "fraud_lstm_scalers.joblib"
FRAUD_RESULTS = ARTEFACTS / "fraud_lstm_results.json"

PRICE_MODEL_OUT = ARTEFACTS / "price_gb.joblib"
FORECAST_OUT = ARTEFACTS / "forecast_moving_average.json"
REGISTRY_OUT = ARTEFACTS / "registry.json"

# The champion settings from baseline.py. Changing these invalidates every
# score quoted in the write up, so they are stated here rather than tuned.
PRICE_PARAMS = dict(
    max_iter=400,
    learning_rate=0.06,
    max_depth=None,
    min_samples_leaf=15,
    l2_regularization=1.0,
    random_state=42,
)

# The forecast window the walk forward measured. Six months of the index,
# averaged. Not tuned here, inherited from the scored result.
FORECAST_WINDOW = 6

INTERVAL = 90.0  # per cent


def section(title: str) -> None:
    print()
    print("=" * 72)
    print(title)
    print("=" * 72)


def die(message: str) -> None:
    print(f"ERROR: {message}")
    sys.exit(1)


# --------------------------------------------------------------------------
# loading
# --------------------------------------------------------------------------


def load_matrix() -> tuple[np.ndarray, np.ndarray, list[str]]:
    """
    matrix.npz was written by features/build.py. The key names are not
    guaranteed, so find them rather than assume them, and say what was found.
    """
    if not MATRIX.exists():
        die(f"{MATRIX} not found. Run: py -m rentradar.features.build")
    data = np.load(MATRIX, allow_pickle=False)
    keys = list(data.keys())

    x_key = next((k for k in ("X", "x", "features", "matrix") if k in keys), None)
    y_key = next((k for k in ("y", "Y", "target", "targets") if k in keys), None)

    if x_key is None or y_key is None:
        # fall back on shape: the 2D array is X, the 1D array is y
        two_d = [k for k in keys if data[k].ndim == 2]
        one_d = [k for k in keys if data[k].ndim == 1]
        if len(two_d) == 1 and len(one_d) >= 1:
            x_key, y_key = two_d[0], one_d[0]
        else:
            die(f"Could not identify X and y inside {MATRIX.name}. "
                f"Keys present: {keys}")

    X = np.asarray(data[x_key], dtype="float64")
    y = np.asarray(data[y_key], dtype="float64").ravel()
    if X.shape[0] != y.shape[0]:
        die(f"X has {X.shape[0]} rows but y has {y.shape[0]}")
    return X, y, [x_key, y_key]


def load_json(path: Path, what: str) -> dict:
    if not path.exists():
        die(f"{path} not found ({what})")
    return json.loads(path.read_text(encoding="utf-8"))


def split_indices(splits: dict, name: str) -> dict:
    """splits.json stores splits[name]['indices'][part]."""
    if name not in splits:
        die(f"splits.json has no '{name}' split. Present: {sorted(splits.keys())}")
    block = splits[name]
    idx = block.get("indices", block)
    out = {}
    for part in ("train", "val", "test"):
        if part not in idx:
            die(f"splits['{name}'] has no '{part}'. Present: {sorted(idx.keys())}")
        out[part] = np.asarray(idx[part], dtype="int64")
    return out


def read_series(path: Path) -> list[dict]:
    if not path.exists():
        die(f"{path} not found. Run: py -m rentradar.collect.series build "
            f"--cells bedrooms --since 2024-10-01")
    rows = []
    with path.open("r", encoding="utf-8") as fh:
        for line in fh:
            line = line.strip()
            if line:
                rows.append(json.loads(line))
    rows.sort(key=lambda r: str(r.get("period", "")))
    return rows


# --------------------------------------------------------------------------
# metrics
# --------------------------------------------------------------------------


def score(y_log_true: np.ndarray, y_log_pred: np.ndarray) -> dict:
    """
    The target is log1p rent, matching baseline.py, so cedis come back with
    expm1 and not exp.

    R squared is reported in cedis as the headline because that is what
    baseline.py measured and what every figure already written quotes. The log
    space figure sits beside it, labelled, because it is always higher and the
    gap is a definition rather than a result. Switching to it now, having seen
    that it clears the 0.84 threshold, would not survive a viva.
    """
    true_ghs = np.expm1(y_log_true)
    pred_ghs = np.expm1(y_log_pred)
    err = pred_ghs - true_ghs

    ss_res = float(np.sum(err ** 2))
    ss_tot = float(np.sum((true_ghs - true_ghs.mean()) ** 2))
    r2_ghs = 1.0 - ss_res / ss_tot if ss_tot > 0 else float("nan")

    lres = y_log_pred - y_log_true
    ss_res_l = float(np.sum(lres ** 2))
    ss_tot_l = float(np.sum((y_log_true - y_log_true.mean()) ** 2))
    r2_log = 1.0 - ss_res_l / ss_tot_l if ss_tot_l > 0 else float("nan")

    rel = np.abs(err) / np.maximum(true_ghs, 1.0)
    return {
        "n": int(y_log_true.size),
        "r2": float(r2_ghs),
        "r2_log": float(r2_log),
        "mae_ghs": float(np.mean(np.abs(err))),
        "medae_ghs": float(np.median(np.abs(err))),
        "rmse_ghs": float(math.sqrt(np.mean(err ** 2))),
        "mape": float(np.mean(rel) * 100.0),
        "within_10pct": float(np.mean(rel <= 0.10) * 100.0),
        "within_20pct": float(np.mean(rel <= 0.20) * 100.0),
    }
    """
    The targets are log rent, so scores are reported in both spaces. R squared
    in log space is what the baselines quoted, GHS errors are what a reader
    actually understands.
    """
    resid = y_log_pred - y_log_true
    ss_res = float(np.sum(resid ** 2))
    ss_tot = float(np.sum((y_log_true - y_log_true.mean()) ** 2))
    r2 = 1.0 - ss_res / ss_tot if ss_tot > 0 else float("nan")

    true_ghs = np.exp(y_log_true)
    pred_ghs = np.exp(y_log_pred)
    err = pred_ghs - true_ghs
    return {
        "n": int(y_log_true.size),
        "r2_log": float(r2),
        "mae_ghs": float(np.mean(np.abs(err))),
        "medae_ghs": float(np.median(np.abs(err))),
        "rmse_ghs": float(math.sqrt(np.mean(err ** 2))),
        "mape": float(np.mean(np.abs(err) / true_ghs) * 100.0),
    }


# --------------------------------------------------------------------------
# price
# --------------------------------------------------------------------------


def export_price(X: np.ndarray, y: np.ndarray, parts: dict, spec: dict) -> dict:
    from sklearn.ensemble import HistGradientBoostingRegressor
    import joblib

    tr, va, te = parts["train"], parts["val"], parts["test"]
    print(f"  rows: {X.shape[0]}, features: {X.shape[1]}")
    print(f"  train {tr.size}, validation {va.size}, test {te.size}")

    # honest fit: training partition only
    honest = HistGradientBoostingRegressor(**PRICE_PARAMS).fit(X[tr], y[tr])
    val_pred = honest.predict(X[va])
    test_pred = honest.predict(X[te])
    val_scores = score(y[va], val_pred)
    test_scores = score(y[te], test_pred)

    print(f"  validation  R2 {val_scores['r2']:.3f} cedis / "
          f"{val_scores['r2_log']:.3f} log   "
          f"MAE {val_scores['mae_ghs']:,.0f} GHS  MAPE {val_scores['mape']:.1f}%")
    print(f"  held out    R2 {test_scores['r2']:.3f} cedis / "
          f"{test_scores['r2_log']:.3f} log   "
          f"MAE {test_scores['mae_ghs']:,.0f} GHS  MAPE {test_scores['mape']:.1f}%")
    print(f"  held out    within 10% on {test_scores['within_10pct']:.1f}% of "
          f"listings, within 20% on {test_scores['within_20pct']:.1f}%")

    # The interval comes from validation residuals, never from test. Using the
    # held out partition to set the interval would make the held out score a
    # fiction.
    resid = y[va] - val_pred
    lo_q = (100.0 - INTERVAL) / 2.0
    hi_q = 100.0 - lo_q
    lo_log = float(np.percentile(resid, lo_q))
    hi_log = float(np.percentile(resid, hi_q))

    # coverage check on the held out partition, which the interval never saw
    test_resid = y[te] - test_pred
    covered = float(np.mean((test_resid >= lo_log) & (test_resid <= hi_log)) * 100.0)

    print(f"  {INTERVAL:.0f}% interval multipliers: "
          f"x{math.exp(lo_log):.2f} to x{math.exp(hi_log):.2f}")
    print(f"  measured coverage on the held out partition: {covered:.1f}%")
    base = math.log1p(12000.0)
    print(f"  typical width at 12,000 GHS: "
          f"{math.expm1(base + lo_log):,.0f} to {math.expm1(base + hi_log):,.0f}")

    # production fit: training plus validation. The corpus is small and the
    # extra 15 per cent is worth more at serving time than it is held back.
    both = np.concatenate([tr, va])
    production = HistGradientBoostingRegressor(**PRICE_PARAMS).fit(X[both], y[both])

    ARTEFACTS.mkdir(parents=True, exist_ok=True)
    joblib.dump(
        {
            "model": production,
            "spec_version": spec.get("version") or spec.get("spec_version"),
            "n_features": int(X.shape[1]),
            "target": "log_rent_ghs_monthly",
            "interval_percent": INTERVAL,
            "interval_log_low": lo_log,
            "interval_log_high": hi_log,
        },
        PRICE_MODEL_OUT,
    )
    print(f"  written: {PRICE_MODEL_OUT.name}")

    return {
        "artefact": PRICE_MODEL_OUT.name,
        "algorithm": "HistGradientBoostingRegressor",
        "hyperparameters": PRICE_PARAMS,
        "target": "log_rent_ghs_monthly",
        "trained_on": "train + validation",
        "scored_on": "held out test partition, fitted on train only",
        "validation": val_scores,
        "test": test_scores,
        "interval": {
            "percent": INTERVAL,
            "source": "validation residual quantiles",
            "log_low": lo_log,
            "log_high": hi_log,
            "multiplier_low": math.exp(lo_log),
            "multiplier_high": math.exp(hi_log),
            "measured_coverage_on_test_percent": covered,
        },
    }


# --------------------------------------------------------------------------
# forecast
# --------------------------------------------------------------------------


def export_forecast(window: int, until: str | None) -> dict:
    rows = read_series(SERIES_FILE)
    if until:
        rows = [r for r in rows if str(r.get("period", ""))[:7] <= until[:7]]

    values, periods, counts = [], [], []
    for r in rows:
        v = r.get("index_ghs")
        if v is None:
            continue
        values.append(float(v))
        periods.append(str(r["period"]))
        counts.append(r.get("listings"))

    if len(values) < window:
        die(f"series has {len(values)} months, need at least {window}")

    recent = values[-window:]
    level = float(np.mean(recent))

    print(f"  series: {len(values)} months, {periods[0]} to {periods[-1]}")
    print(f"  window: last {window} months, {periods[-window]} to {periods[-1]}")
    print(f"  current level: {level:,.0f} GHS")

    # The error band is the measured walk forward error, not a guess. Read it
    # from the scored run rather than restating a number from memory.
    mae = mape = None
    if FORECAST_RESULTS.exists():
        try:
            res = json.loads(FORECAST_RESULTS.read_text(encoding="utf-8"))
            for block in res.get("results", {}).values():
                wm = block.get("metrics", {}).get("window_mean", {}).get("h1")
                if wm:
                    mae, mape = wm.get("mae"), wm.get("mape")
                    break
        except Exception:
            pass

    if mape is None:
        print("  WARNING: no scored window_mean result found, band left unset.")
        print("  Run: py -m rentradar.models.forecast_lstm fit --until 2026-09")
    else:
        print(f"  measured error: MAE {mae:,.0f} GHS, MAPE {mape:.1f}%")
        print(f"  band at the current level: "
              f"{level * (1 - mape / 100):,.0f} to {level * (1 + mape / 100):,.0f} GHS")

    payload = {
        "model": "six_month_moving_average",
        "window_months": window,
        "series_file": SERIES_FILE.name,
        "months_used": len(values),
        "period_range": [periods[0], periods[-1]],
        "level_ghs": level,
        "window_periods": periods[-window:],
        "window_values": recent,
        "mae_ghs": mae,
        "mape_percent": mape,
        "series": [
            {"period": p, "index_ghs": v, "listings": c}
            for p, v, c in zip(periods, values, counts)
        ],
        "note": (
            "The monthly index is dominated by sampling noise and the ADF test "
            "finds it stationary, so there is no trend to extrapolate. The "
            "serving contract is a smoothed current level with a measured band, "
            "not a point forecast for next month."
        ),
    }
    ARTEFACTS.mkdir(parents=True, exist_ok=True)
    FORECAST_OUT.write_text(json.dumps(payload, indent=2), encoding="utf-8")
    print(f"  written: {FORECAST_OUT.name}")

    return {
        "artefact": FORECAST_OUT.name,
        "algorithm": "six month moving average",
        "window_months": window,
        "level_ghs": level,
        "mae_ghs": mae,
        "mape_percent": mape,
        "beat": "sequence to sequence LSTM, MAE 3,385 GHS, MAPE 16.2%",
    }


# --------------------------------------------------------------------------
# fraud
# --------------------------------------------------------------------------


def check_fraud() -> dict:
    """
    The autoencoder already saved itself during training. This verifies the
    artefacts load and records what the service will need.
    """
    present = {
        "model": FRAUD_MODEL.exists(),
        "scalers": FRAUD_SCALERS.exists(),
        "results": FRAUD_RESULTS.exists(),
    }
    for name, ok in present.items():
        print(f"  {name:<10} {'found' if ok else 'MISSING'}")

    if not all(present.values()):
        print("  Run: py -m rentradar.models.fraud_lstm fit --epochs 200")
        return {"status": "incomplete", "present": present}

    results = {}
    try:
        results = json.loads(FRAUD_RESULTS.read_text(encoding="utf-8"))
    except Exception as exc:
        print(f"  could not read results: {exc}")

    try:
        import joblib
        joblib.load(FRAUD_SCALERS)
        print("  scalers load cleanly")
    except Exception as exc:
        print(f"  scalers failed to load: {exc}")
        return {"status": "broken", "error": str(exc)}

    print("  model file left in place, loaded by the service at startup")
    print()
    print("  NOTE: scoring a new listing needs its nearest training comparables,")
    print("  so the service also needs the training reference set. That is not")
    print("  exported yet and is the first job when the fraud endpoint is built.")

    return {
        "artefact": FRAUD_MODEL.name,
        "scalers": FRAUD_SCALERS.name,
        "algorithm": "LSTM autoencoder over 9 comparables x 6 channels",
        "status": "partial",
        "missing": "training reference set for comparable lookup at serving time",
        "scored": {
            k: v for k, v in results.items()
            if isinstance(v, (int, float, str, bool)) or v is None
        },
    }


# --------------------------------------------------------------------------
# commands
# --------------------------------------------------------------------------


def cmd_inspect(args) -> None:
    section("WHAT IS ON DISK")
    for label, path in [
        ("feature matrix", MATRIX),
        ("feature spec", SPEC),
        ("row ids", ROW_IDS),
        ("splits", SPLITS),
        ("series", SERIES_FILE),
        ("fraud model", FRAUD_MODEL),
        ("fraud scalers", FRAUD_SCALERS),
    ]:
        mark = "found" if path.exists() else "MISSING"
        size = f"{path.stat().st_size / 1024:,.1f} KB" if path.exists() else ""
        print(f"  {label:<16} {mark:<9} {size:>12}  {path.name}")

    if MATRIX.exists():
        X, y, keys = load_matrix()
        print()
        print(f"  matrix keys used : {keys[0]} and {keys[1]}")
        print(f"  shape            : {X.shape[0]} rows, {X.shape[1]} features")
        print(f"  target range     : {y.min():.2f} to {y.max():.2f} "
              f"(log), {math.exp(y.min()):,.0f} to {math.exp(y.max()):,.0f} GHS")

    if SPLITS.exists():
        splits = load_json(SPLITS, "splits")
        print()
        names = sorted(k for k, v in splits.items() if isinstance(v, dict))
        print(f"  splits available : {', '.join(names)}")
        for name in names:
            block = splits[name]
            idx = block.get("indices", block)
            sizes = {k: len(v) for k, v in idx.items() if isinstance(v, list)}
            print(f"    {name:<10} {sizes}")


def cmd_run(args) -> None:
    spec = load_json(SPEC, "feature spec")
    splits = load_json(SPLITS, "splits")
    X, y, keys = load_matrix()
    parts = split_indices(splits, args.price_split)

    section("PRICE, GRADIENT BOOSTING")
    price = export_price(X, y, parts, spec)

    section("FORECAST, MOVING AVERAGE")
    forecast = export_forecast(args.window, args.until)

    section("FRAUD, LSTM AUTOENCODER")
    fraud = check_fraud()

    registry = {
        "generated_at": datetime.now(timezone.utc).isoformat(timespec="seconds"),
        "corpus": {
            "rows": int(X.shape[0]),
            "features": int(X.shape[1]),
            "feature_spec_version": spec.get("version") or spec.get("spec_version"),
            "price_split": args.price_split,
        },
        "models": {
            "price": {"version": args.version, **price},
            "forecast": {"version": args.version, **forecast},
            "fraud": {"version": args.version, **fraud},
        },
        "selection_note": (
            "Each exported model is the one that won its own comparison. The "
            "three LSTM architectures were built, trained and scored against "
            "these on identical partitions with paired significance tests. Two "
            "of the three lost and are reported as such rather than served."
        ),
    }
    REGISTRY_OUT.write_text(json.dumps(registry, indent=2), encoding="utf-8")

    section("DONE")
    print(f"  registry: {REGISTRY_OUT}")
    print(f"  version : {args.version}")
    print()
    print("  Phase 10 can now load these three artefacts and serve them.")


def main() -> None:
    parser = argparse.ArgumentParser(
        description="Export the production model artefacts for the inference service")
    sub = parser.add_subparsers(dest="command", required=True)

    p_in = sub.add_parser("inspect", help="show what is on disk, change nothing")
    p_in.set_defaults(func=cmd_inspect)

    p_run = sub.add_parser("run", help="train and write the artefacts")
    p_run.add_argument("--version", default=datetime.now().strftime("v%Y.%m.%d"),
                       help="version stamped on every artefact in the registry")
    p_run.add_argument("--price-split", default="price",
                       help="which split in splits.json the price model uses")
    p_run.add_argument("--window", type=int, default=FORECAST_WINDOW,
                       help="months averaged for the forecast level")
    p_run.add_argument("--until", default="2026-09",
                       help="drop series months after this one, the current "
                            "month is still being collected")
    p_run.set_defaults(func=cmd_run)

    args = parser.parse_args()
    args.func(args)


if __name__ == "__main__":
    main()