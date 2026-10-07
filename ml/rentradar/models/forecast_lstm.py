"""
RentRadar, Phase 9, model 3 of 3: sequence to sequence LSTM forecaster.

What this does
--------------
Reads the fixed weight monthly rent index built by rentradar.collect.series,
and forecasts the next few months with an encoder decoder LSTM.

The encoder reads a window of monthly log changes. The decoder emits the next
`--horizon` monthly log changes in one shot. That is what makes it sequence to
sequence: a sequence goes in, a sequence comes out, rather than one number.

Why log changes and not levels
------------------------------
If the model sees levels it can score well by copying the last value, which is
exactly what the naive baseline already does. Training on changes forces it to
beat zero, so any win is a real win.

How it is scored
----------------
Walk forward with an expanding window. For each of the last `--test-periods`
months, the model is retrained on every month strictly before that point, then
asked to forecast forward. No future month ever touches a fit that predicts it.

The same origins are scored for five opponents: naive last value, drift,
window mean, same month last year, and a ridge regression on the identical
input window. The ridge is the fair shallow opponent, it sees exactly what the
LSTM sees.

Honest warning that belongs in the write up
-------------------------------------------
The series is short. On 25 monthly points the model trains on roughly 10 to 15
windows. A significance test on 6 paired origins has almost no power, it cannot
reject anything. The bootstrap interval on the error difference is the more
informative number, and it will probably be wide.

Commands
--------
  py -m rentradar.models.forecast_lstm inspect
  py -m rentradar.models.forecast_lstm fit --until 2026-09
  py -m rentradar.models.forecast_lstm fit --until 2026-09 --epochs 600 --seeds 7
"""

from __future__ import annotations

import argparse
import json
import math
import os
import re
import sys
from pathlib import Path

os.environ.setdefault("TF_CPP_MIN_LOG_LEVEL", "3")
os.environ.setdefault("TF_ENABLE_ONEDNN_OPTS", "0")

import numpy as np

ROOT = Path(__file__).resolve().parents[2]
SERIES_PATH = ROOT / "data" / "series" / "accra_month_bedrooms.jsonl"
BASELINE_PATH = ROOT / "artifacts" / "models" / "forecast_baseline_results.json"
OUT_PATH = ROOT / "artifacts" / "models" / "forecast_lstm_results.json"

PERIOD_KEYS = ["period", "month", "period_start", "ym", "bucket", "date"]

# index_ghs is the fixed weight basket written by rentradar.collect.series.
# raw_median_ghs sits in the same file but it drifts with composition, so it is
# last resort only.
VALUE_KEYS = [
    "index_ghs",
    "index",
    "index_value",
    "basket_index",
    "fixed_weight_index",
    "basket",
    "value",
    "level",
    "median_ghs",
    "raw_median_ghs",
    "median",
    "median_rent",
]
COUNT_KEYS = [
    "listings",
    "n",
    "count",
    "n_listings",
    "records",
    "n_records",
    "sample_size",
]

PERIOD_RE = re.compile(r"^\d{4}-\d{2}")


# --------------------------------------------------------------------------
# loading
# --------------------------------------------------------------------------


def read_jsonl(path: Path) -> list[dict]:
    if not path.exists():
        print(f"Series file not found: {path}")
        print("Build it first with:")
        print("  py -m rentradar.collect.series build --cells bedrooms --since 2024-10-01")
        sys.exit(1)
    rows: list[dict] = []
    with path.open("r", encoding="utf-8") as fh:
        for line in fh:
            line = line.strip()
            if not line:
                continue
            rows.append(json.loads(line))
    if not rows:
        print(f"Series file is empty: {path}")
        sys.exit(1)
    return rows


def pick_named_key(rows: list[dict], candidates: list[str]) -> str | None:
    """First candidate key that is present and filled on the first few rows."""
    sample = rows[: min(5, len(rows))]
    for key in candidates:
        if all(key in row and row[key] is not None for row in sample):
            return key
    return None


def pick_period_key(rows: list[dict]) -> str | None:
    named = pick_named_key(rows, PERIOD_KEYS)
    if named:
        return named
    # a month stamp is recognisable on sight, so guessing here is safe
    for key, val in rows[0].items():
        if isinstance(val, str) and PERIOD_RE.match(val):
            return key
    return None


def show_keys_and_exit(rows: list[dict], what: str) -> None:
    print(f"Could not work out which field holds the {what}.")
    print("Guessing the wrong column would silently forecast the wrong thing, so")
    print("this stops here instead. The first record holds:")
    for key, val in rows[0].items():
        print(f"    {key:<20} {val!r}")
    print()
    print("Name it yourself, for example:")
    print("  py -m rentradar.models.forecast_lstm fit --value-field index_ghs")
    sys.exit(1)


def load_series(path: Path, args) -> dict:
    rows = read_jsonl(path)

    pf = args.period_field or pick_period_key(rows)
    if pf is None:
        show_keys_and_exit(rows, "month")

    vf = args.value_field or pick_named_key(rows, VALUE_KEYS)
    if vf is None:
        show_keys_and_exit(rows, "index value")

    cf = args.count_field or pick_named_key(rows, COUNT_KEYS)
    # a field cannot be both the thing being forecast and the sample size
    if cf is not None and cf == vf:
        cf = None

    until = (args.until or "").strip() or None

    recs = []
    dropped_thin = 0
    dropped_coverage = 0
    dropped_until = 0
    for row in rows:
        if row.get(pf) is None or row.get(vf) is None:
            continue
        try:
            val = float(row[vf])
        except (TypeError, ValueError):
            continue
        if not math.isfinite(val) or val <= 0:
            continue

        period = str(row[pf])
        if until is not None and period[:7] > until[:7]:
            dropped_until += 1
            continue
        if args.drop_thin and bool(row.get("thin")):
            dropped_thin += 1
            continue
        cov = row.get("weight_coverage")
        if args.min_coverage > 0 and cov is not None:
            try:
                if float(cov) < args.min_coverage:
                    dropped_coverage += 1
                    continue
            except (TypeError, ValueError):
                pass

        cnt = None
        if cf is not None and row.get(cf) is not None:
            try:
                cnt = float(row[cf])
            except (TypeError, ValueError):
                cnt = None

        recs.append({
            "period": period,
            "value": val,
            "count": cnt,
            "thin": bool(row.get("thin")) if "thin" in row else None,
            "coverage": cov,
        })

    recs.sort(key=lambda r: r["period"])

    # drop duplicate months, keep the last one written
    deduped: dict[str, dict] = {}
    for rec in recs:
        deduped[rec["period"]] = rec
    recs = [deduped[k] for k in sorted(deduped)]

    return {
        "period_field": pf,
        "value_field": vf,
        "count_field": cf,
        "records": recs,
        "dropped": {
            "after_until": dropped_until,
            "thin": dropped_thin,
            "low_coverage": dropped_coverage,
        },
    }


def month_number(period: str) -> int:
    match = re.match(r"^(\d{4})-(\d{2})", period)
    if match:
        return int(match.group(2))
    return 1


def check_contiguous(periods: list[str]) -> list[str]:
    """Return a list of gap descriptions, empty if the months run unbroken."""
    gaps = []
    for prev, cur in zip(periods, periods[1:]):
        pm = re.match(r"^(\d{4})-(\d{2})", prev)
        cm = re.match(r"^(\d{4})-(\d{2})", cur)
        if not pm or not cm:
            continue
        a = int(pm.group(1)) * 12 + int(pm.group(2))
        b = int(cm.group(1)) * 12 + int(cm.group(2))
        if b - a != 1:
            gaps.append(f"{prev} to {cur} ({b - a} months apart)")
    return gaps


def partial_month_warning(recs: list[dict]) -> str | None:
    """
    A month still being collected holds far fewer listings than the months
    before it, and it drags the end of the series down for no real reason.
    """
    if len(recs) < 7:
        return None
    counts = [r["count"] for r in recs]
    if any(c is None or c <= 0 for c in counts):
        return None
    last = counts[-1]
    prior = sorted(counts[-7:-1])
    med = prior[len(prior) // 2]
    if med > 0 and last < 0.5 * med:
        return (f"{recs[-1]['period']} holds only {last:.0f} listings against a median "
                f"of {med:.0f}\n                over the six months before it. That "
                f"month looks like it is still\n                being collected. "
                f"Re-run with --until {recs[-2]['period']} to cut it.")
    return None


# --------------------------------------------------------------------------
# channels and windows
# --------------------------------------------------------------------------


def build_channels(recs: list[dict], use_volume: bool) -> tuple[np.ndarray, np.ndarray, list[str]]:
    """
    Returns (levels, diffs, channel_names).

    levels has one row per month.
    diffs has one row per month transition, so one fewer row, with columns:
        0  log change in the index          <- this is the forecast target
        1  sine of the calendar month
        2  cosine of the calendar month
        3  log change in the listing count  <- only if available and asked for
    """
    levels = np.array([r["value"] for r in recs], dtype="float64")
    periods = [r["period"] for r in recs]
    counts = [r["count"] for r in recs]

    have_counts = use_volume and all(c is not None and c > 0 for c in counts)

    log_levels = np.log(levels)
    rows = []
    for t in range(1, len(levels)):
        month = month_number(periods[t])
        row = [
            log_levels[t] - log_levels[t - 1],
            math.sin(2.0 * math.pi * month / 12.0),
            math.cos(2.0 * math.pi * month / 12.0),
        ]
        if have_counts:
            row.append(math.log(counts[t]) - math.log(counts[t - 1]))
        rows.append(row)

    names = ["log_change", "month_sin", "month_cos"]
    if have_counts:
        names.append("log_count_change")

    return levels, np.array(rows, dtype="float64"), names


def make_windows(diffs: np.ndarray, lookback: int, horizon: int,
                 max_target_end: int) -> tuple[np.ndarray, np.ndarray, list[int]]:
    """
    Every window whose target range stays at or before `max_target_end`
    (an index into `diffs`, exclusive). That is how the walk forward keeps
    the future out of the fit.
    """
    xs, ys, starts = [], [], []
    for i in range(0, len(diffs) - lookback - horizon + 1):
        if i + lookback + horizon > max_target_end:
            break
        xs.append(diffs[i: i + lookback, :])
        ys.append(diffs[i + lookback: i + lookback + horizon, 0])
        starts.append(i)
    if not xs:
        return (np.zeros((0, lookback, diffs.shape[1])),
                np.zeros((0, horizon)), [])
    return np.array(xs), np.array(ys), starts


# --------------------------------------------------------------------------
# metrics
# --------------------------------------------------------------------------


def errors(actual: list[float], pred: list[float]) -> dict:
    a = np.array(actual, dtype="float64")
    p = np.array(pred, dtype="float64")
    if a.size == 0:
        return {"n": 0, "mae": None, "rmse": None, "mape": None}
    err = p - a
    return {
        "n": int(a.size),
        "mae": float(np.mean(np.abs(err))),
        "rmse": float(math.sqrt(np.mean(err ** 2))),
        "mape": float(np.mean(np.abs(err) / np.abs(a)) * 100.0),
    }


def bootstrap_ci(d: np.ndarray, iters: int = 10000, seed: int = 42) -> tuple[float, float]:
    """Interval on the mean of the paired error differences."""
    if d.size == 0:
        return (float("nan"), float("nan"))
    rng = np.random.default_rng(seed)
    draws = rng.choice(d, size=(iters, d.size), replace=True).mean(axis=1)
    return (float(np.percentile(draws, 2.5)), float(np.percentile(draws, 97.5)))


def wilcoxon_p(a: np.ndarray, b: np.ndarray) -> float | None:
    try:
        from scipy.stats import wilcoxon
    except Exception:
        return None
    if a.size < 3:
        return None
    if np.allclose(a, b):
        return 1.0
    try:
        return float(wilcoxon(a, b).pvalue)
    except Exception:
        return None


# --------------------------------------------------------------------------
# the model
# --------------------------------------------------------------------------


def build_model(lookback: int, channels: int, horizon: int,
                units: int, dropout: float, lr: float):
    from tensorflow import keras

    inp = keras.Input(shape=(lookback, channels))
    enc = keras.layers.LSTM(units, name="encoder")(inp)
    enc = keras.layers.Dropout(dropout)(enc)
    rep = keras.layers.RepeatVector(horizon)(enc)
    dec = keras.layers.LSTM(units, return_sequences=True, name="decoder")(rep)
    out = keras.layers.TimeDistributed(keras.layers.Dense(1), name="head")(dec)

    model = keras.Model(inp, out, name="seq2seq_rent_forecaster")
    model.compile(
        optimizer=keras.optimizers.Adam(learning_rate=lr, clipnorm=1.0),
        loss=keras.losses.Huber(delta=1.0),
    )
    return model


def fit_predict_lstm(x_train: np.ndarray, y_train: np.ndarray, x_query: np.ndarray,
                     horizon: int, units: int, dropout: float, lr: float,
                     epochs: int, batch: int, seeds: int) -> tuple[np.ndarray, int]:
    """
    Standardises channels on the training window only, trains one model per
    seed, returns the median predicted log changes plus the parameter count.
    """
    from tensorflow import keras

    mu = x_train.reshape(-1, x_train.shape[2]).mean(axis=0)
    sd = x_train.reshape(-1, x_train.shape[2]).std(axis=0)
    sd[sd < 1e-8] = 1.0

    xt = (x_train - mu) / sd
    xq = (x_query - mu) / sd

    y_mu = float(y_train.mean())
    y_sd = float(y_train.std())
    if y_sd < 1e-8:
        y_sd = 1.0
    yt = ((y_train - y_mu) / y_sd)[:, :, None]

    size = xt.shape[0] if batch <= 0 else min(batch, xt.shape[0])

    preds = []
    params = 0
    for seed in range(seeds):
        keras.utils.set_random_seed(1000 + seed)
        model = build_model(xt.shape[1], xt.shape[2], horizon, units, dropout, lr)
        params = int(model.count_params())
        model.fit(xt, yt, epochs=epochs, batch_size=size, verbose=0, shuffle=True)
        raw = model(xq, training=False).numpy()[:, :, 0]
        preds.append(raw * y_sd + y_mu)
        keras.backend.clear_session()

    return np.median(np.array(preds), axis=0), params


def fit_predict_ridge(x_train: np.ndarray, y_train: np.ndarray,
                      x_query: np.ndarray) -> np.ndarray:
    """The fair shallow opponent: same window in, same horizon out."""
    from sklearn.linear_model import Ridge

    flat_t = x_train.reshape(x_train.shape[0], -1)
    flat_q = x_query.reshape(x_query.shape[0], -1)
    mu = flat_t.mean(axis=0)
    sd = flat_t.std(axis=0)
    sd[sd < 1e-8] = 1.0
    model = Ridge(alpha=1.0).fit((flat_t - mu) / sd, y_train)
    pred = model.predict((flat_q - mu) / sd)
    return np.atleast_2d(pred)


# --------------------------------------------------------------------------
# walk forward
# --------------------------------------------------------------------------


def levels_from_diffs(last_level: float, diffs: np.ndarray) -> np.ndarray:
    return float(last_level) * np.exp(np.cumsum(diffs))


def run_lookback(levels: np.ndarray, diffs: np.ndarray, periods: list[str],
                 lookback: int, horizon: int, test_periods: int,
                 args) -> dict:
    """One full walk forward for a single lookback length."""
    n = len(levels)

    origins = list(range(n - 1 - test_periods, n - 1))
    origins = [o for o in origins if o >= 0]
    if not origins:
        print(f"  lookback {lookback}: series too short for {test_periods} test months")
        return {}

    model_names = ["lstm", "ridge", "naive_last", "drift", "window_mean",
                   "naive_seasonal"]
    collected = {name: {h: {"actual": [], "pred": []} for h in range(1, horizon + 1)}
                 for name in model_names}
    per_origin = []
    clipped = 0
    params = 0
    train_sizes = []

    for origin in origins:
        # everything strictly before the origin is fair game
        x_tr, y_tr, _ = make_windows(diffs, lookback, horizon, max_target_end=origin)
        if x_tr.shape[0] < args.min_train:
            print(f"  lookback {lookback}: origin {periods[origin]} has only "
                  f"{x_tr.shape[0]} training windows, needs {args.min_train}, skipped")
            continue
        train_sizes.append(int(x_tr.shape[0]))

        start = origin - lookback
        if start < 0:
            continue
        x_q = diffs[start:origin, :][None, :, :]

        hist = diffs[:origin, 0]
        lo, hi = float(hist.min()), float(hist.max())

        lstm_diffs, params = fit_predict_lstm(
            x_tr, y_tr, x_q, horizon,
            args.units, args.dropout, args.lr, args.epochs, args.batch, args.seeds,
        )
        lstm_diffs = lstm_diffs[0]
        before = lstm_diffs.copy()
        lstm_diffs = np.clip(lstm_diffs, lo, hi)
        clipped += int(np.sum(before != lstm_diffs))

        ridge_diffs = fit_predict_ridge(x_tr, y_tr, x_q)[0]
        ridge_diffs = np.clip(ridge_diffs, lo, hi)

        drift = float(hist.mean())
        anchor = float(levels[origin])
        win_mean = float(np.mean(levels[max(0, origin - lookback + 1): origin + 1]))

        # same month last year, falling back to the last value when the
        # series does not reach back twelve months
        seasonal = []
        for h in range(1, horizon + 1):
            back = origin + h - 12
            seasonal.append(float(levels[back]) if back >= 0 else anchor)

        paths = {
            "lstm": levels_from_diffs(anchor, lstm_diffs),
            "ridge": levels_from_diffs(anchor, ridge_diffs),
            "naive_last": np.repeat(anchor, horizon),
            "drift": levels_from_diffs(anchor, np.repeat(drift, horizon)),
            "window_mean": np.repeat(win_mean, horizon),
            "naive_seasonal": np.array(seasonal, dtype="float64"),
        }

        row = {
            "origin_period": periods[origin],
            "origin_level": anchor,
            "train_windows": int(x_tr.shape[0]),
            "steps": [],
        }
        for h in range(1, horizon + 1):
            idx = origin + h
            if idx > n - 1:
                break
            actual = float(levels[idx])
            step = {"h": h, "period": periods[idx], "actual": actual}
            for name in model_names:
                pred = float(paths[name][h - 1])
                collected[name][h]["actual"].append(actual)
                collected[name][h]["pred"].append(pred)
                step[name] = pred
            row["steps"].append(step)
        per_origin.append(row)

    if not per_origin:
        return {}

    # headline metrics
    metrics = {}
    for name in model_names:
        by_h = {}
        all_a, all_p = [], []
        for h in range(1, horizon + 1):
            a = collected[name][h]["actual"]
            p = collected[name][h]["pred"]
            if a:
                by_h[f"h{h}"] = errors(a, p)
                all_a += a
                all_p += p
        by_h["all_horizons"] = errors(all_a, all_p)
        metrics[name] = by_h

    # significance at step 1 against the best opponent
    e = {}
    for name in model_names:
        a = np.array(collected[name][1]["actual"], dtype="float64")
        p = np.array(collected[name][1]["pred"], dtype="float64")
        e[name] = np.abs(p - a)

    opponents = [m for m in model_names if m != "lstm"]
    best_opp = min(opponents, key=lambda m: float(e[m].mean()))
    d = e["lstm"] - e[best_opp]
    lo_ci, hi_ci = bootstrap_ci(d)
    comparison = {
        "best_opponent": best_opp,
        "lstm_step1_mae": float(e["lstm"].mean()),
        "opponent_step1_mae": float(e[best_opp].mean()),
        "mae_difference": float(d.mean()),
        "paired_origins": int(d.size),
        "wilcoxon_p": wilcoxon_p(e["lstm"], e[best_opp]),
        "bootstrap_ci_95": [lo_ci, hi_ci],
        "spans_zero": bool(lo_ci <= 0.0 <= hi_ci),
    }

    return {
        "lookback": lookback,
        "horizon": horizon,
        "test_origins": [periods[o] for o in origins],
        "train_windows_min": min(train_sizes) if train_sizes else None,
        "train_windows_max": max(train_sizes) if train_sizes else None,
        "lstm_parameters": params,
        "clipped_steps": clipped,
        "metrics": metrics,
        "comparison": comparison,
        "per_origin": per_origin,
    }


# --------------------------------------------------------------------------
# commands
# --------------------------------------------------------------------------


def cmd_inspect(args) -> None:
    series = load_series(Path(args.series), args)
    recs = series["records"]
    print(f"Series file     : {args.series}")
    print(f"Month field     : {series['period_field']}")
    print(f"Value field     : {series['value_field']}   <- this is what gets forecast")
    print(f"Count field     : {series['count_field'] or 'none found'}")
    print(f"Usable months   : {len(recs)}")
    drops = series["dropped"]
    if any(drops.values()):
        print(f"Dropped         : {drops['after_until']} after --until, "
              f"{drops['thin']} thin, {drops['low_coverage']} below coverage floor")
    if not recs:
        return
    print(f"Range           : {recs[0]['period']} to {recs[-1]['period']}")

    gaps = check_contiguous([r["period"] for r in recs])
    print(f"Gaps            : {'none' if not gaps else '; '.join(gaps)}")

    levels, diffs, names = build_channels(recs, use_volume=not args.no_volume)
    print(f"Channels        : {', '.join(names)}")
    print(f"Monthly change  : mean {diffs[:, 0].mean() * 100:+.2f}%  "
          f"sd {diffs[:, 0].std() * 100:.2f}%  "
          f"min {diffs[:, 0].min() * 100:+.2f}%  "
          f"max {diffs[:, 0].max() * 100:+.2f}%")

    warn = partial_month_warning(recs)
    if warn:
        print()
        print(f"Partial month   : {warn}")

    print()
    print(f"{'month':<10}{'index':>14}{'change':>10}{'listings':>10}{'cover':>8}")
    for i, rec in enumerate(recs):
        chg = "" if i == 0 else f"{diffs[i - 1, 0] * 100:+.2f}%"
        cnt = "" if rec["count"] is None else f"{rec['count']:.0f}"
        cov = "" if rec["coverage"] is None else f"{float(rec['coverage']):.3f}"
        print(f"{rec['period']:<10}{rec['value']:>14,.0f}{chg:>10}{cnt:>10}{cov:>8}")


def cmd_fit(args) -> None:
    series = load_series(Path(args.series), args)
    recs = series["records"]
    periods = [r["period"] for r in recs]

    if len(recs) < 12:
        print(f"Only {len(recs)} usable months. Too few to train anything honest.")
        sys.exit(1)

    gaps = check_contiguous(periods)
    levels, diffs, channel_names = build_channels(recs, use_volume=not args.no_volume)
    warn = partial_month_warning(recs)

    print("=" * 74)
    print("SEQUENCE TO SEQUENCE LSTM RENT FORECASTER")
    print("=" * 74)
    print(f"Series file   : {args.series}")
    print(f"Forecasting   : {series['value_field']}")
    print(f"Sample size   : {series['count_field'] or 'no count field'}")
    print(f"Months        : {len(recs)}  ({periods[0]} to {periods[-1]})")
    print(f"Gaps          : {'none' if not gaps else '; '.join(gaps)}")
    print(f"Channels      : {', '.join(channel_names)}")
    print(f"Horizon       : {args.horizon} months, scored step by step")
    print(f"Test months   : last {args.test_periods}, walk forward, expanding window")
    print(f"Seeds         : {args.seeds} per origin, median prediction")
    if warn:
        print(f"Partial month : {warn}")
    print()

    lookbacks = [int(x) for x in str(args.lookbacks).split(",") if x.strip()]
    results = {}

    for pos, lookback in enumerate(lookbacks):
        tag = "primary" if pos == 0 else "secondary"
        print("-" * 74)
        print(f"LOOKBACK {lookback} MONTHS  ({tag})")
        print("-" * 74)
        res = run_lookback(levels, diffs, periods, lookback, args.horizon,
                           args.test_periods, args)
        if not res:
            continue
        results[f"lookback_{lookback}"] = res

        print(f"  training windows per origin : "
              f"{res['train_windows_min']} to {res['train_windows_max']}")
        print(f"  LSTM weights                : {res['lstm_parameters']:,}")
        print(f"  clipped forecast steps      : {res['clipped_steps']}")
        print()
        head = f"  {'model':<14}"
        for h in range(1, args.horizon + 1):
            head += f"{'h' + str(h) + ' MAE':>11}"
        head += f"{'h1 MAPE':>10}{'all MAE':>11}"
        print(head)
        for name, by_h in res["metrics"].items():
            line = f"  {name:<14}"
            for h in range(1, args.horizon + 1):
                m = by_h.get(f"h{h}")
                line += f"{m['mae']:>11,.0f}" if m and m["mae"] is not None else f"{'-':>11}"
            m1 = by_h.get("h1")
            line += f"{m1['mape']:>9.2f}%" if m1 and m1["mape"] is not None else f"{'-':>10}"
            allm = by_h.get("all_horizons")
            line += f"{allm['mae']:>11,.0f}" if allm and allm["mae"] is not None else f"{'-':>11}"
            print(line)

        comp = res["comparison"]
        print()
        print(f"  one month ahead, LSTM vs {comp['best_opponent']} "
              f"(the strongest opponent):")
        print(f"    LSTM MAE        {comp['lstm_step1_mae']:,.0f} GHS")
        print(f"    opponent MAE    {comp['opponent_step1_mae']:,.0f} GHS")
        print(f"    difference      {comp['mae_difference']:+,.0f} GHS "
              f"({'LSTM better' if comp['mae_difference'] < 0 else 'LSTM worse'})")
        wp = comp["wilcoxon_p"]
        print(f"    Wilcoxon p      {wp:.3f}" if wp is not None else
              "    Wilcoxon p      not computable")
        print(f"    bootstrap 95%   {comp['bootstrap_ci_95'][0]:+,.0f} to "
              f"{comp['bootstrap_ci_95'][1]:+,.0f} GHS")
        if comp["spans_zero"]:
            verdict = "not distinguishable, the interval spans zero"
        elif comp["mae_difference"] < 0:
            verdict = "a real difference, the LSTM wins"
        else:
            verdict = "a real difference, the simple model wins"
        print(f"    verdict         {verdict}")
        print(f"    paired origins  {comp['paired_origins']} "
              f"(too few for the p value to carry weight, read the interval)")
        print()

        print("  forecast one month ahead, origin by origin:")
        print(f"    {'origin':<10}{'target':<10}{'actual':>12}{'LSTM':>12}"
              f"{'naive':>12}{'LSTM err':>11}")
        for row in res["per_origin"]:
            if not row["steps"]:
                continue
            s = row["steps"][0]
            print(f"    {row['origin_period']:<10}{s['period']:<10}"
                  f"{s['actual']:>12,.0f}{s['lstm']:>12,.0f}"
                  f"{s['naive_last']:>12,.0f}{s['lstm'] - s['actual']:>+11,.0f}")
        print()

    if not results:
        print("No lookback produced a scorable walk forward. Lower --test-periods "
              "or --min-train, or collect more months.")
        sys.exit(1)

    # cross reference the standalone baseline run, if it is on disk
    stored = None
    if BASELINE_PATH.exists():
        try:
            stored = json.loads(BASELINE_PATH.read_text(encoding="utf-8"))
        except Exception:
            stored = None

    primary_key = f"lookback_{lookbacks[0]}"
    primary = results.get(primary_key) or next(iter(results.values()))

    print("=" * 74)
    print("WHAT TO WRITE DOWN")
    print("=" * 74)
    comp = primary["comparison"]
    print(f"Forecast target : {series['value_field']}, "
          f"{len(recs)} months, {periods[0]} to {periods[-1]}")
    print(f"Primary setting : lookback {primary['lookback']}, "
          f"horizon {primary['horizon']}")
    print(f"LSTM one month ahead : MAE {comp['lstm_step1_mae']:,.0f} GHS, "
          f"MAPE {primary['metrics']['lstm']['h1']['mape']:.2f}%")
    print(f"Best opponent        : {comp['best_opponent']}, "
          f"MAE {comp['opponent_step1_mae']:,.0f} GHS, "
          f"MAPE {primary['metrics'][comp['best_opponent']]['h1']['mape']:.2f}%")
    if comp["spans_zero"]:
        print("Finding              : the deep model is not measurably better or worse")
        print("                       than the simple one on this series.")
    elif comp["mae_difference"] < 0:
        print("Finding              : the deep model beats the simple one.")
    else:
        print("Finding              : the simple one beats the deep model.")
    print(f"Reason to report     : {len(recs)} monthly points give at most "
          f"{primary['train_windows_max']} training windows. That is")
    print("                       not enough data for a recurrent network to earn its")
    print("                       keep, and saying so with numbers is a result, not a")
    print("                       failure.")
    if stored:
        print()
        print("Cross reference, the earlier standalone baseline run:")
        print(f"  {BASELINE_PATH.name} is on disk. Note its split may differ from the")
        print("  walk forward used here, so quote the numbers above for this model.")

    OUT_PATH.parent.mkdir(parents=True, exist_ok=True)
    payload = {
        "model": "seq2seq_lstm_forecaster",
        "series_path": str(args.series),
        "fields": {
            "period": series["period_field"],
            "value": series["value_field"],
            "count": series["count_field"],
        },
        "filters": {
            "until": args.until or None,
            "drop_thin": bool(args.drop_thin),
            "min_coverage": args.min_coverage,
            "dropped": series["dropped"],
        },
        "months": len(recs),
        "period_range": [periods[0], periods[-1]],
        "gaps": gaps,
        "partial_month_warning": warn,
        "channels": channel_names,
        "config": {
            "lookbacks": lookbacks,
            "primary_lookback": lookbacks[0],
            "horizon": args.horizon,
            "test_periods": args.test_periods,
            "units": args.units,
            "dropout": args.dropout,
            "learning_rate": args.lr,
            "epochs": args.epochs,
            "batch_size": args.batch,
            "seeds": args.seeds,
            "evaluation": "walk forward, expanding window, retrained at every origin",
        },
        "results": results,
        "series_values": [{"period": r["period"], "value": r["value"],
                           "count": r["count"]} for r in recs],
    }
    OUT_PATH.write_text(json.dumps(payload, indent=2), encoding="utf-8")
    print()
    print(f"Written: {OUT_PATH}")


# --------------------------------------------------------------------------


def main() -> None:
    parser = argparse.ArgumentParser(
        description="Sequence to sequence LSTM forecaster for the RentRadar rent index")
    sub = parser.add_subparsers(dest="command", required=True)

    def common(p):
        p.add_argument("--series", default=str(SERIES_PATH),
                       help="path to the monthly index jsonl")
        p.add_argument("--period-field", default=None)
        p.add_argument("--value-field", default=None,
                       help="the column to forecast, for example index_ghs")
        p.add_argument("--count-field", default=None)
        p.add_argument("--no-volume", action="store_true",
                       help="ignore the listing count channel")
        p.add_argument("--until", default=None,
                       help="drop months after this one, for example 2026-09, "
                            "to cut a month that is still being collected")
        p.add_argument("--drop-thin", action="store_true",
                       help="drop months the series builder marked thin")
        p.add_argument("--min-coverage", type=float, default=0.0,
                       help="drop months whose weight_coverage is below this")

    p_in = sub.add_parser("inspect", help="show the series and the detected fields")
    common(p_in)
    p_in.set_defaults(func=cmd_inspect)

    p_fit = sub.add_parser("fit", help="train and score the forecaster")
    common(p_fit)
    p_fit.add_argument("--lookbacks", default="6",
                       help="comma separated input window lengths, first is primary, "
                            "for example 6,4,3")
    p_fit.add_argument("--horizon", type=int, default=3,
                       help="months the decoder emits")
    p_fit.add_argument("--test-periods", type=int, default=6,
                       help="how many months at the end to forecast")
    p_fit.add_argument("--min-train", type=int, default=5,
                       help="fewest training windows an origin may have")
    p_fit.add_argument("--units", type=int, default=12)
    p_fit.add_argument("--dropout", type=float, default=0.1)
    p_fit.add_argument("--lr", type=float, default=0.01)
    p_fit.add_argument("--epochs", type=int, default=250)
    p_fit.add_argument("--batch", type=int, default=0,
                       help="0 means one full batch per step, right for this few samples")
    p_fit.add_argument("--seeds", type=int, default=3)
    p_fit.set_defaults(func=cmd_fit)

    args = parser.parse_args()
    args.func(args)


if __name__ == "__main__":
    main()