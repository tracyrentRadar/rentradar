"""ARIMA baseline for the price series, and the naive baselines it must beat.

Phase 8 asks for ARIMA(2,1,2) with an augmented Dickey-Fuller test, the
differencing order justified by that test rather than assumed, and AIC and BIC
reported. This does that, and adds the three naive forecasters that any
serious evaluation needs beside it.

The naive ones are not filler. On a flat series, "tomorrow equals today" is
extremely hard to beat, and a deep model that loses to it has told you
something true. Reporting ARIMA alone, with no floor underneath it, is how a
mediocre result gets dressed up as a good one.

Two honesty notes built into the output.

  Parameters against observations. ARIMA(2,1,2) fits five parameters. With
  roughly twenty training points that is one parameter per four observations,
  which is overfitting by any normal standard. The ratio is printed so nobody
  has to work it out later.

  The series is dollar asking prices wearing a cedi label. Ghana Property
  Centre quotes in dollars and every record was converted at one current rate,
  so this series cannot show the cedi moving. The MAE below is in cedis only
  because the index is, and the acceptance threshold of 15 GHS was written for
  a different kind of series than the one that exists.

    python -m rentradar.models.forecast_baseline
    python -m rentradar.models.forecast_baseline --holdout 6 --log
"""

from __future__ import annotations

import argparse
import json
import math
import statistics
import sys
from pathlib import Path
from typing import Optional

import numpy as np

DEFAULT_SERIES = "data/series/accra_month_bedrooms.jsonl"
MAE_TARGET_GHS = 15.0


def load_series(path: Path, drop_thin: bool) -> tuple[list[str], list[float]]:
    periods, values = [], []
    with path.open(encoding="utf-8") as fh:
        for line in fh:
            line = line.strip()
            if not line:
                continue
            r = json.loads(line)
            if r.get("index_ghs") is None:
                continue
            if drop_thin and r.get("thin"):
                continue
            periods.append(r["period"])
            values.append(float(r["index_ghs"]))
    return periods, values


def section(title: str) -> None:
    print("\n" + "-" * 72)
    print(title)
    print("-" * 72)


def metrics(actual: list[float], pred: list[float]) -> dict:
    a = np.asarray(actual, dtype=float)
    p = np.asarray(pred, dtype=float)
    err = a - p
    return {
        "mae": float(np.mean(np.abs(err))),
        "rmse": float(math.sqrt(np.mean(err ** 2))),
        "mape": float(np.mean(np.abs(err / a)) * 100.0),
    }


# ------------------------------------------------------------- naive
def naive_last(train: list[float], h: int) -> list[float]:
    """Tomorrow equals today. The floor every other model has to clear."""
    return [train[-1]] * h


def naive_mean(train: list[float], h: int) -> list[float]:
    return [statistics.fmean(train)] * h


def naive_drift(train: list[float], h: int) -> list[float]:
    """Straight line through the first and last training points."""
    if len(train) < 2:
        return naive_last(train, h)
    slope = (train[-1] - train[0]) / (len(train) - 1)
    return [train[-1] + slope * (i + 1) for i in range(h)]


def seasonal_naive(train: list[float], h: int, period: int = 12) -> Optional[list[float]]:
    """Same month last year. Needs a full cycle of history to mean anything."""
    if len(train) < period:
        return None
    return [train[-period + (i % period)] for i in range(h)]


# ------------------------------------------------------------- main
def main(argv=None) -> int:
    p = argparse.ArgumentParser(description="ARIMA baseline for the price series")
    p.add_argument("--series", default=DEFAULT_SERIES)
    p.add_argument("--holdout", type=int, default=6,
                   help="periods held out at the end, never seen in fitting")
    p.add_argument("--keep-thin", action="store_true",
                   help="include periods below the coverage bar")
    p.add_argument("--log", action="store_true",
                   help="model log(index) and exponentiate back")
    args = p.parse_args(argv)

    try:
        from statsmodels.tsa.arima.model import ARIMA
        from statsmodels.tsa.stattools import adfuller, kpss
    except Exception as e:
        print("statsmodels is needed for this baseline and did not import.")
        print(f"  {type(e).__name__}: {e}")
        print("\nInstall a mature release rather than the newest one:")
        print("  py -m pip install statsmodels==0.14.2")
        return 1

    path = Path(args.series)
    if not path.exists():
        print(f"No series at {path}. Build one first:")
        print("  py -m rentradar.collect.series build --source ghana_property_centre "
              "--since 2024-10-01 --cells bedrooms")
        return 1

    periods, values = load_series(path, drop_thin=not args.keep_thin)
    n = len(values)
    if n < 12:
        print(f"Only {n} usable periods in {path}. Too short to fit anything.")
        return 1

    section("The series")
    print(f"  file              {path}")
    print(f"  periods           {n}, {periods[0]} to {periods[-1]}")
    print(f"  mean              {statistics.fmean(values):,.0f} GHS")
    print(f"  std deviation     {statistics.pstdev(values):,.0f} GHS")
    print(f"  min to max        {min(values):,.0f} to {max(values):,.0f} GHS")
    first_half = statistics.fmean(values[: n // 2])
    second_half = statistics.fmean(values[n // 2:])
    print(f"  first half mean   {first_half:,.0f}")
    print(f"  second half mean  {second_half:,.0f}")
    print(f"  change            {(second_half / first_half - 1) * 100:+.1f}%")

    work = [math.log(v) for v in values] if args.log else list(values)
    unit = "log GHS" if args.log else "GHS"

    # ------------------------------------------------- stationarity
    section("Stationarity, and what differencing order it justifies")
    def adf(series, label):
        # 9 lags on a 25 point series leaves 15 observations and the test has
        # no power left. Two lags is the most a series this short supports.
        stat, pval, lags, nobs, crit, _ = adfuller(series, maxlag=2, autolag="AIC")
        print(f"  {label}")
        print(f"    ADF statistic   {stat:.4f}")
        print(f"    p value         {pval:.4f}")
        print(f"    lags used       {lags}, observations {nobs}")
        print(f"    critical 5%     {crit['5%']:.4f}")
        verdict = ("rejects a unit root, so this is already stationary"
                   if pval < 0.05 else
                   "cannot reject a unit root, so this needs differencing")
        print(f"    verdict         {verdict}")
        return pval

    p_levels = adf(work, "on the levels")
    diffed = list(np.diff(work))
    print()
    p_diff = adf(diffed, "on the first difference")

    d_justified = 0 if p_levels < 0.05 else (1 if p_diff < 0.05 else 2)
    print(f"\n  differencing order justified by the test: d = {d_justified}")
    if d_justified != 1:
        print("  Phase 8 specifies ARIMA(2,1,2). The test does not support d=1 here.")
        print("  Both orders are fitted below and both are reported, because a")
        print("  specification followed against the evidence is worth less than")
        print("  a deviation that is explained.")

    # ------------------------------------------------- split
    h = min(args.holdout, max(3, n // 4))
    train, test = work[: n - h], work[n - h:]
    test_actual = values[n - h:]
    print(f"\n  train {len(train)} periods, held out {h} "
          f"({periods[n - h]} to {periods[-1]})")

    rows = []

    def record(name: str, forecast_work: Optional[list[float]], extra: str = "") -> None:
        if forecast_work is None:
            return
        fc = [math.exp(v) for v in forecast_work] if args.log else list(forecast_work)
        m = metrics(test_actual, fc)
        rows.append((name, m, extra))

    record("naive, last value", naive_last(train, h))
    record("naive, mean", naive_mean(train, h))
    record("naive, drift", naive_drift(train, h))
    record("seasonal naive, 12", seasonal_naive(train, h, 12))

    # ------------------------------------------------- ARIMA
    orders = [(2, 1, 2)]
    if d_justified != 1:
        orders.append((2, d_justified, 2))
    orders.append((1, d_justified, 0))
    orders.append((0, d_justified, 1))

    section("ARIMA fits")
    for order in orders:
        k = order[0] + order[2] + 1
        try:
            model = ARIMA(train, order=order,
                          enforce_stationarity=False,
                          enforce_invertibility=False)
            fit = model.fit()
            fc = list(np.asarray(fit.forecast(steps=h), dtype=float))
            label = f"ARIMA{order}"
            print(f"  {label:<16} AIC {fit.aic:>9.2f}   BIC {fit.bic:>9.2f}   "
                  f"{k} parameters on {len(train)} points "
                  f"({len(train) / k:.1f} per parameter)")
            record(label, fc, f"AIC {fit.aic:.1f}")
        except Exception as e:
            print(f"  ARIMA{order:<10} failed: {type(e).__name__}: {e}")

    # ------------------------------------------------- results
    section("Held-out performance, all models on the same periods")
    print(f"  {'model':<22} {'MAE GHS':>10} {'RMSE':>10} {'MAPE':>8}")
    best = None
    for name, m, extra in sorted(rows, key=lambda r: r[1]["mae"]):
        print(f"  {name:<22} {m['mae']:>10,.0f} {m['rmse']:>10,.0f} "
              f"{m['mape']:>7.1f}%")
        if best is None:
            best = (name, m)

    section("What this means")
    if best:
        name, m = best
        print(f"  best on held-out MAE: {name} at {m['mae']:,.0f} GHS "
              f"({m['mape']:.1f}%)")
        arima_rows = [r for r in rows if r[0].startswith("ARIMA")]
        naive_rows = [r for r in rows if r[0].startswith(("naive", "seasonal"))]
        if arima_rows and naive_rows:
            best_arima = min(arima_rows, key=lambda r: r[1]["mae"])
            best_naive = min(naive_rows, key=lambda r: r[1]["mae"])
            if best_arima[1]["mae"] <= best_naive[1]["mae"]:
                print(f"  ARIMA beats the best naive forecaster by "
                      f"{best_naive[1]['mae'] - best_arima[1]['mae']:,.0f} GHS.")
            else:
                print(f"  ARIMA LOSES to {best_naive[0]} by "
                      f"{best_arima[1]['mae'] - best_naive[1]['mae']:,.0f} GHS.")
                print("  On a flat series that is the expected outcome, and it is")
                print("  the number the deep model must now be compared against,")
                print("  not the ARIMA one.")

        print(f"\n  acceptance threshold: MAE <= {MAE_TARGET_GHS:,.0f} GHS")
        print(f"  actual:               MAE = {m['mae']:,.0f} GHS")
        print(f"  missed by a factor of {m['mae'] / MAE_TARGET_GHS:,.0f}")
        print("\n  The threshold was written for a series of a different kind.")
        print("  An index averaging around 22,000 cedis cannot be forecast to")
        print("  within 15 cedis by anything; that is 0.07 per cent. Restating")
        print("  the target as a percentage is a proposal change, not a modelling")
        print("  failure, and it needs the supervisor's agreement.")

    out = Path("artifacts/models")
    out.mkdir(parents=True, exist_ok=True)
    payload = {
        "series_file": str(path),
        "periods": n,
        "range": [periods[0], periods[-1]],
        "holdout": h,
        "log_transform": bool(args.log),
        "adf_p_levels": p_levels,
        "adf_p_first_difference": p_diff,
        "d_justified": d_justified,
        "results": [{"model": nm, **m} for nm, m, _ in rows],
    }
    (out / "forecast_baseline_results.json").write_text(
        json.dumps(payload, indent=2), encoding="utf-8")
    print(f"\n  wrote {out / 'forecast_baseline_results.json'}")
    print("  This is the bar. Any sequence model goes in the same table or it does not ship.")
    return 0


if __name__ == "__main__":
    sys.exit(main())