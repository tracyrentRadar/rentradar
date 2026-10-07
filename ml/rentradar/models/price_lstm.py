"""Stacked LSTM for price, reading comparable listings as a sequence.

Phase 9 specifies a stacked LSTM producing a point estimate and a 90 per cent
interval from the residual distribution. Three ways of applying a recurrent
model are implemented and all three are reported, because the ablation is the
finding.

  naive-features   124 timesteps of one value each. The literal reading of
                   "LSTM on tabular data". Column order comes from the feature
                   builder and means nothing, so this asks the model to find
                   sequence in an arbitrary ordering.

  naive-single     one timestep of 124 values. The LSTM degenerates to a gated
                   dense layer. Honest, but not really a sequence model.

  comparables      the subject's eight nearest neighbours in the TRAINING set,
                   ordered least to most similar, each carrying its features,
                   its actual rent and its distance from the subject. The model
                   reads the comparables and then prices the subject.

Four things keep the comparables version numerically sane, three of which are
repairs to an earlier version that produced predictions of 72 billion cedis.

  Anchored target. The model predicts the DIFFERENCE between the subject and
  the average of its comparables, not the price. The target is then centred
  near zero with small spread instead of a log rent around 9.2, and the
  neighbours do the heavy lifting. This is also closer to how valuation works:
  find comparables, then adjust.

  Channels standardised. Neighbour log rents near 9.2 and euclidean distances
  near 15 were being fed alongside features scaled to unit variance. Every
  channel is now standardised on training statistics.

  Gradients clipped. clipnorm on the optimiser, so one bad batch cannot throw
  the weights somewhere they never recover from.

  Output bounded. Predictions are clipped to the range of log rents actually
  seen in training, and the number clipped is reported. A model that has never
  seen a rent above 246,200 has no business predicting one, and a metric
  destroyed by a single runaway row tells you nothing about the other 812.

Fairness. Giving the LSTM neighbour prices and withholding them from gradient
boosting would make the comparison meaningless. Boosted trees are fitted twice,
once on the original features and once with the same neighbour summary
appended. Both appear in the table.

    python -m rentradar.models.price_lstm
    python -m rentradar.models.price_lstm --mode naive-features
    python -m rentradar.models.price_lstm --no-search --seeds 1
"""

from __future__ import annotations

import argparse
import json
import os
import random
import sys
from pathlib import Path

import numpy as np

os.environ.setdefault("TF_CPP_MIN_LOG_LEVEL", "2")

FEATURES = Path("data/features")
ARTEFACTS = Path("artifacts/models")
SEED = 42
INTERVAL = 0.90
K_COMPARABLES = 8
# How far outside the training range of log rent a prediction may stray.
CLIP_MARGIN = 0.25


def section(title: str) -> None:
    print("\n" + "-" * 72)
    print(title)
    print("-" * 72)


def metrics(y_log_true, y_log_pred) -> dict:
    """Scored in cedis, matching baseline.py exactly so the tables combine."""
    actual = np.expm1(np.asarray(y_log_true, dtype=float))
    pred = np.expm1(np.asarray(y_log_pred, dtype=float))
    err = pred - actual
    ss_res = float(np.sum((actual - pred) ** 2))
    ss_tot = float(np.sum((actual - np.mean(actual)) ** 2))
    ok = actual > 0
    return {
        "mae_ghs": float(np.mean(np.abs(err))),
        "median_ae_ghs": float(np.median(np.abs(err))),
        "rmse_ghs": float(np.sqrt(np.mean(err ** 2))),
        "mape_pct": float(np.mean(np.abs(err[ok]) / actual[ok]) * 100),
        "r2": 1.0 - ss_res / ss_tot if ss_tot > 0 else float("nan"),
        "within_10pct": float(np.mean(np.abs(err) / np.maximum(actual, 1) <= 0.10) * 100),
        "within_20pct": float(np.mean(np.abs(err) / np.maximum(actual, 1) <= 0.20) * 100),
    }


def interval_from_residuals(y_val_log, pred_val_log, level=INTERVAL):
    resid = np.asarray(y_val_log) - np.asarray(pred_val_log)
    lo_q = (1.0 - level) / 2.0
    return float(np.quantile(resid, lo_q)), float(np.quantile(resid, 1.0 - lo_q))


def coverage(y_true_log, pred_log, lo, hi) -> float:
    return float(np.mean((y_true_log >= pred_log + lo) &
                         (y_true_log <= pred_log + hi)) * 100.0)


def neighbours(Str, ytr, Squery, k, exclude_self):
    """Indices and distances of the k nearest TRAINING rows, closest last.

    exclude_self is set only when the query rows are the training rows
    themselves, where the nearest neighbour would be the listing asking the
    question and its own rent would be handed to the model.
    """
    from sklearn.neighbors import NearestNeighbors

    nn = NearestNeighbors(n_neighbors=k + (1 if exclude_self else 0))
    nn.fit(Str)
    dist, idx = nn.kneighbors(Squery)
    if exclude_self:
        dist, idx = dist[:, 1:], idx[:, 1:]
    return idx[:, ::-1], dist[:, ::-1]


def make_model(mode, shapes, units1, units2, dropout, lr):
    from tensorflow import keras
    from tensorflow.keras import layers

    if mode == "comparables":
        seq_in = layers.Input(shape=shapes["seq"], name="comparables")
        own_in = layers.Input(shape=shapes["own"], name="subject")
        x = layers.LSTM(units1, return_sequences=True)(seq_in)
        x = layers.Dropout(dropout)(x)
        x = layers.LSTM(units2)(x)
        x = layers.Dropout(dropout)(x)
        o = layers.Dense(48, activation="relu")(own_in)
        h = layers.Concatenate()([x, o])
        h = layers.Dense(48, activation="relu")(h)
        h = layers.Dropout(dropout)(h)
        out = layers.Dense(1)(h)
        model = keras.Model([seq_in, own_in], out)
    else:
        inp = layers.Input(shape=shapes["seq"])
        x = layers.LSTM(units1, return_sequences=True)(inp)
        x = layers.Dropout(dropout)(x)
        x = layers.LSTM(units2)(x)
        x = layers.Dropout(dropout)(x)
        x = layers.Dense(32, activation="relu")(x)
        out = layers.Dense(1)(x)
        model = keras.Model(inp, out)

    model.compile(
        optimizer=keras.optimizers.Adam(learning_rate=lr, clipnorm=1.0),
        loss=keras.losses.Huber(delta=1.0), metrics=["mae"])
    return model


def fit_once(mode, shapes, in_tr, t_tr, in_va, t_va, cfg, seed,
             epochs, batch, patience):
    import tensorflow as tf
    from tensorflow import keras

    random.seed(seed); np.random.seed(seed); tf.random.set_seed(seed)
    model = make_model(mode, shapes, cfg["u1"], cfg["u2"], cfg["dropout"], cfg["lr"])
    cbs = [
        keras.callbacks.EarlyStopping(monitor="val_loss", patience=patience,
                                      restore_best_weights=True),
        keras.callbacks.ReduceLROnPlateau(monitor="val_loss", factor=0.5,
                                          patience=max(3, patience // 3),
                                          min_lr=1e-5),
    ]
    hist = model.fit(in_tr, t_tr, validation_data=(in_va, t_va),
                     epochs=epochs, batch_size=batch, callbacks=cbs, verbose=0)
    return model, hist


def main(argv=None) -> int:
    p = argparse.ArgumentParser(description="Stacked LSTM price regressor")
    p.add_argument("--mode", default="comparables",
                   choices=["comparables", "naive-single", "naive-features"])
    p.add_argument("--k", type=int, default=K_COMPARABLES)
    p.add_argument("--epochs", type=int, default=80)
    p.add_argument("--batch", type=int, default=64)
    p.add_argument("--patience", type=int, default=12)
    p.add_argument("--seeds", type=int, default=3)
    p.add_argument("--no-search", action="store_true")
    args = p.parse_args(argv)

    try:
        import tensorflow as tf
        from sklearn.preprocessing import StandardScaler
        from sklearn.ensemble import HistGradientBoostingRegressor
        import joblib
    except Exception as e:
        print(f"A dependency did not import: {type(e).__name__}: {e}")
        return 1
    tf.get_logger().setLevel("ERROR")

    data = np.load(FEATURES / "matrix.npz")
    X, y = data["X"], data["y"]
    splits = json.loads((FEATURES / "splits.json").read_text(encoding="utf-8"))
    idx = splits["price"]["indices"]
    tr, va, te = np.array(idx["train"]), np.array(idx["val"]), np.array(idx["test"])

    section("Partition, as written by partition.py")
    print(f"  scheme            {splits['price']['scheme']}")
    print(f"  train/val/test    {len(tr):,} / {len(va):,} / {len(te):,}")
    print(f"  features          {X.shape[1]}")
    print(f"  overlap check     {len(set(tr.tolist()) & set(te.tolist()))} rows in both")

    scaler = StandardScaler().fit(X[tr])
    Str, Sva, Ste = (scaler.transform(X[tr]).astype(np.float32),
                     scaler.transform(X[va]).astype(np.float32),
                     scaler.transform(X[te]).astype(np.float32))
    ytr, yva, yte = y[tr], y[va], y[te]

    lo_clip = float(ytr.min() - CLIP_MARGIN)
    hi_clip = float(ytr.max() + CLIP_MARGIN)

    extra_tr = extra_va = extra_te = None
    if args.mode == "comparables":
        section(f"Comparables, k = {args.k}, drawn only from training rows")
        i_tr, d_tr = neighbours(Str, ytr, Str, args.k, exclude_self=True)
        i_va, d_va = neighbours(Str, ytr, Sva, args.k, exclude_self=False)
        i_te, d_te = neighbours(Str, ytr, Ste, args.k, exclude_self=False)

        # Standardise the two non-feature channels on training statistics,
        # so nothing enters the recurrent layers an order of magnitude larger
        # than everything else.
        rent_mu, rent_sd = float(ytr.mean()), float(ytr.std() or 1.0)
        dist_mu, dist_sd = float(d_tr.mean()), float(d_tr.std() or 1.0)

        def pack(i, d):
            return np.concatenate([
                Str[i],
                ((ytr[i] - rent_mu) / rent_sd)[:, :, None],
                ((d - dist_mu) / dist_sd)[:, :, None],
            ], axis=2).astype(np.float32)

        def summary(i, d):
            return np.stack([ytr[i].mean(axis=1), ytr[i[:, -1]],
                             ytr[i].std(axis=1), d.mean(axis=1)], axis=1).astype(np.float32)

        seq_tr, seq_va, seq_te = pack(i_tr, d_tr), pack(i_va, d_va), pack(i_te, d_te)
        extra_tr, extra_va, extra_te = (summary(i_tr, d_tr), summary(i_va, d_va),
                                        summary(i_te, d_te))

        # Anchor: the model predicts the gap from the comparables' average.
        a_tr, a_va, a_te = extra_tr[:, 0], extra_va[:, 0], extra_te[:, 0]

        shapes = {"seq": seq_tr.shape[1:], "own": (Str.shape[1],)}
        in_tr, in_va, in_te = [seq_tr, Str], [seq_va, Sva], [seq_te, Ste]
        print(f"  sequence shape    {seq_tr.shape[1]} timesteps x {seq_tr.shape[2]} values")
        print("  ordering          least similar first, closest match last")
        print("  self-retrieval    excluded for training rows")
        print(f"  anchor            comparables' mean log rent, "
              f"spread {a_tr.std():.3f}")
        print(f"  target            subject minus anchor, spread "
              f"{(ytr - a_tr).std():.3f} instead of {ytr.std():.3f}")
    else:
        if args.mode == "naive-single":
            rs = lambda A: A.reshape((A.shape[0], 1, A.shape[1]))
        else:
            rs = lambda A: A.reshape((A.shape[0], A.shape[1], 1))
        in_tr, in_va, in_te = rs(Str), rs(Sva), rs(Ste)
        shapes = {"seq": in_tr.shape[1:]}
        a_tr = np.full(len(tr), float(ytr.mean()), dtype=np.float32)
        a_va = np.full(len(va), float(ytr.mean()), dtype=np.float32)
        a_te = np.full(len(te), float(ytr.mean()), dtype=np.float32)

    t_tr, t_va = ytr - a_tr, yva - a_va

    def to_log(raw, anchor):
        p = np.asarray(raw).ravel() + anchor
        n_clipped = int(np.sum((p < lo_clip) | (p > hi_clip)))
        return np.clip(p, lo_clip, hi_clip), n_clipped

    grid = [
        {"u1": 48, "u2": 24, "dropout": 0.20, "lr": 1e-3},
        {"u1": 64, "u2": 32, "dropout": 0.30, "lr": 1e-3},
        {"u1": 32, "u2": 16, "dropout": 0.10, "lr": 1e-3},
        {"u1": 64, "u2": 32, "dropout": 0.20, "lr": 3e-4},
    ]
    if args.no_search:
        grid = grid[:1]

    section("Hyperparameter search, selected on validation MAE")
    print("  Validation only. The test fold is untouched until the final table.\n")
    print(f"  {'lstm1':>6}{'lstm2':>7}{'dropout':>9}{'lr':>8}"
          f"{'val MAE GHS':>13}{'clipped':>9}{'epochs':>8}")
    best_cfg, best_mae = None, float("inf")
    for cfg in grid:
        model, hist = fit_once(args.mode, shapes, in_tr, t_tr, in_va, t_va,
                               cfg, SEED, args.epochs, args.batch, args.patience)
        pred, nclip = to_log(model.predict(in_va, verbose=0), a_va)
        m = metrics(yva, pred)["mae_ghs"]
        print(f"  {cfg['u1']:>6}{cfg['u2']:>7}{cfg['dropout']:>9.2f}"
              f"{cfg['lr']:>8.0e}{m:>13,.0f}{nclip:>9}"
              f"{len(hist.history['loss']):>8}")
        if m < best_mae:
            best_cfg, best_mae = cfg, m
    print(f"\n  chosen            lstm {best_cfg['u1']}/{best_cfg['u2']}, "
          f"dropout {best_cfg['dropout']}, lr {best_cfg['lr']:.0e}")

    section(f"Final fit, {args.seeds} seed(s) averaged")
    val_raw, test_raw, histories = [], [], []
    last_model = None
    for s in range(args.seeds):
        seed = SEED + s
        model, hist = fit_once(args.mode, shapes, in_tr, t_tr, in_va, t_va,
                               best_cfg, seed, args.epochs, args.batch, args.patience)
        val_raw.append(np.asarray(model.predict(in_va, verbose=0)).ravel())
        test_raw.append(np.asarray(model.predict(in_te, verbose=0)).ravel())
        histories.append({k: [float(x) for x in v] for k, v in hist.history.items()})
        vp, _ = to_log(val_raw[-1], a_va)
        print(f"  seed {seed}: {len(hist.history['loss'])} epochs, "
              f"val MAE {metrics(yva, vp)['mae_ghs']:,.0f} GHS")
        last_model = model

    lstm_val, nclip_val = to_log(np.mean(val_raw, axis=0), a_va)
    lstm_test, nclip_test = to_log(np.mean(test_raw, axis=0), a_te)
    print(f"  parameters        {last_model.count_params():,} on {len(tr):,} rows "
          f"({len(tr) / max(1, last_model.count_params()):.2f} rows per parameter)")
    print(f"  predictions clipped to the training range: "
          f"{nclip_val} of {len(va)} validation, {nclip_test} of {len(te)} test")

    def champion(Xa, Xb, Xc):
        gb = HistGradientBoostingRegressor(
            max_iter=400, learning_rate=0.06, max_depth=None,
            min_samples_leaf=15, l2_regularization=1.0, random_state=42,
        ).fit(Xa, ytr)
        return gb.predict(Xb), gb.predict(Xc)

    gb_val, gb_test = champion(X[tr], X[va], X[te])
    results = {f"lstm_{args.mode}": metrics(yte, lstm_test),
               "gradient_boosting": metrics(yte, gb_test)}
    opponents = {"gradient_boosting": (gb_val, gb_test)}

    if extra_tr is not None:
        gbn_val, gbn_test = champion(np.hstack([X[tr], extra_tr]),
                                     np.hstack([X[va], extra_va]),
                                     np.hstack([X[te], extra_te]))
        results["gradient_boosting_with_comparables"] = metrics(yte, gbn_test)
        opponents["gradient_boosting_with_comparables"] = (gbn_val, gbn_test)

    section("Held-out test fold, every model on identical rows")
    width = max(len(k) for k in results) + 2
    print(f"  {'model':<{width}}{'MAE GHS':>10}{'MedAE':>9}{'MAPE %':>9}"
          f"{'R2':>8}{'<=10%':>8}{'<=20%':>8}")
    for name in sorted(results, key=lambda k: results[k]["mae_ghs"]):
        m = results[name]
        print(f"  {name:<{width}}{m['mae_ghs']:>10,.0f}{m['median_ae_ghs']:>9,.0f}"
              f"{m['mape_pct']:>9.1f}{m['r2']:>8.3f}"
              f"{m['within_10pct']:>8.1f}{m['within_20pct']:>8.1f}")
    print("\n  Phase 8 reported gradient boosting at R2 0.706 on this partition.")

    section(f"{INTERVAL:.0%} interval from validation residuals")
    pairs = [(f"lstm_{args.mode}", lstm_val, lstm_test)]
    pairs += [(n, v, t) for n, (v, t) in opponents.items()]
    for name, vpred, tpred in pairs:
        lo, hi = interval_from_residuals(yva, vpred)
        cov = coverage(yte, tpred, lo, hi)
        mid = np.expm1(tpred)
        wide = np.expm1(tpred + hi) - np.expm1(tpred + lo)
        results[name].update({"interval_lo_log": lo, "interval_hi_log": hi,
                              "coverage_test_pct": cov,
                              "median_width_ghs": float(np.median(wide)),
                              "median_estimate_ghs": float(np.median(mid))})
        print(f"  {name}")
        print(f"    residual quantiles  {lo:+.4f} to {hi:+.4f} in log space")
        print(f"    coverage on test    {cov:.1f}%  (claimed {INTERVAL:.0%})")
        print(f"    median width        {np.median(wide):,.0f} GHS around "
              f"{np.median(mid):,.0f}")

    section("Is the LSTM's difference real, or noise?")
    err_lstm = np.abs(np.expm1(yte) - np.expm1(lstm_test))
    for name, (_, tpred) in opponents.items():
        err_opp = np.abs(np.expm1(yte) - np.expm1(tpred))
        diff = err_lstm - err_opp
        print(f"\n  versus {name}")
        print(f"    mean |error| difference   {diff.mean():+,.0f} GHS "
              f"({'LSTM better' if diff.mean() < 0 else 'LSTM worse'})")
        try:
            from scipy.stats import wilcoxon
            stat, pval = wilcoxon(err_lstm, err_opp)
            print(f"    Wilcoxon signed rank      W={stat:,.0f}, p={pval:.4g}")
            print("    verdict                   " + (
                "unlikely to be chance" if pval < 0.05
                else "within what chance would produce"))
            results.setdefault("tests", {})[name] = {
                "wilcoxon_W": float(stat), "p_value": float(pval)}
        except Exception as e:
            print(f"    scipy unavailable: {e}")
        rng = np.random.default_rng(SEED)
        boots = [float(diff[rng.integers(0, len(diff), len(diff))].mean())
                 for _ in range(2000)]
        lo_b, hi_b = np.percentile(boots, [2.5, 97.5])
        print(f"    bootstrap 95% CI          {lo_b:+,.0f} to {hi_b:+,.0f} GHS")
        if lo_b < 0 < hi_b:
            print("    spans zero, so the two are not distinguishable here")
        results.setdefault("tests", {}).setdefault(name, {})["bootstrap_ci"] = \
            [float(lo_b), float(hi_b)]

    ARTEFACTS.mkdir(parents=True, exist_ok=True)
    tag = args.mode.replace("-", "_")
    last_model.save(ARTEFACTS / f"price_lstm_{tag}.keras")
    joblib.dump(scaler, ARTEFACTS / "price_scaler.joblib")
    (ARTEFACTS / f"price_lstm_{tag}_history.json").write_text(
        json.dumps(histories, indent=2), encoding="utf-8")
    (ARTEFACTS / f"price_lstm_{tag}_results.json").write_text(json.dumps({
        "mode": args.mode,
        "k_comparables": args.k if args.mode == "comparables" else None,
        "anchored_target": True, "loss": "huber", "clipnorm": 1.0,
        "clip_range_log": [lo_clip, hi_clip],
        "clipped_val": nclip_val, "clipped_test": nclip_test,
        "seed": SEED, "seeds_averaged": args.seeds,
        "search_grid": grid, "chosen": best_cfg,
        "parameters": int(last_model.count_params()),
        "partition": {"train": len(tr), "val": len(va), "test": len(te),
                      "scheme": splits["price"]["scheme"]},
        "interval_level": INTERVAL, "results": results,
    }, indent=2), encoding="utf-8")

    section("Written")
    print(f"  {ARTEFACTS / f'price_lstm_{tag}.keras'}")
    print(f"  {ARTEFACTS / 'price_scaler.joblib'}   <- serving must apply this exact scaler")
    print(f"  {ARTEFACTS / f'price_lstm_{tag}_history.json'}")
    print(f"  {ARTEFACTS / f'price_lstm_{tag}_results.json'}")
    return 0


if __name__ == "__main__":
    sys.exit(main())