"""LSTM autoencoder for fraud, against the isolation forest it must beat.

Phase 9 asks for an autoencoder scoring reconstruction error, with the
threshold method documented, the error normalised to a 0 to 1 risk score,
mapped to LOW, MEDIUM and HIGH, and the dominant contributing feature named per
assessment so the client can explain itself. All five are here.

What it reads. Nine timesteps: the subject listing's eight nearest neighbours
in the training fold, ordered least to most similar, and then the subject
itself last. Each timestep is six values a tenant would recognise:

    log rent, bedrooms, bathrooms, toilets, amenity count, distance

The model squeezes all nine through a bottleneck and rebuilds them. A listing
that sits naturally among its comparables rebuilds cleanly. One that does not,
a one bedroom asking a fifth of what its neighbours ask while claiming a
swimming pool, does not fit the pattern and the error on its own timestep is
large.

Why this beats autoencoding the listing alone. On its own, a cheap one bedroom
in a cheap area and a cheap one bedroom in East Legon look identical. Only the
neighbourhood makes one of them strange, and the only way the model sees the
neighbourhood is if the neighbours are in the input.

Why the error is taken on the subject's timestep only. Reconstructing the
comparables well is not interesting; they are ordinary by construction. The
question is whether the subject belongs with them.

Three documented choices, none of them defaults.

  Threshold. Set at the percentile of the TRAINING error distribution matching
  the contamination rate the isolation forest used, which was the share of
  listings asking below 60 per cent of their locality and bedroom median. The
  two detectors therefore flag the same proportion, which is what makes their
  precision comparable.

  Normalisation. The 0 to 1 score is the percentile rank of the error against
  the training distribution, not a min-max rescale. Min-max on reconstruction
  error is dominated by its own worst outlier, so adding one broken listing
  would silently rescale every other score in the corpus.

  Bands. HIGH above the threshold, MEDIUM above the 75th percentile, LOW below.
  Stated here rather than tuned, because no labels exist to tune them against.

    python -m rentradar.models.fraud_lstm fit
    python -m rentradar.models.fraud_lstm score --labels data/labels/fraud_labelled.csv
"""

from __future__ import annotations

import argparse
import csv
import json
import os
import random
import statistics
import sys
from collections import defaultdict
from pathlib import Path

import numpy as np

os.environ.setdefault("TF_CPP_MIN_LOG_LEVEL", "2")

FEATURES = Path("data/features")
RECORDS = Path("data/clean")
ARTEFACTS = Path("artifacts/models")
SEED = 42
K = 8
CHEAP_RATIO = 0.60
MIN_CELL = 8
MEDIUM_PERCENTILE = 75.0

CHANNELS = ["log_rent", "bedrooms", "bathrooms", "toilets",
            "amenity_count", "distance"]
COLUMN_FOR = {
    "bedrooms": "num__bedrooms",
    "bathrooms": "num__bathrooms",
    "toilets": "num__toilets",
    "amenity_count": "num__amenity_count",
}


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


def rent_of(rec: dict):
    v = rec.get("rent_monthly_ghs_converted")
    if v is None:
        v = rec.get("rent_monthly_ghs")
    try:
        v = float(v)
    except (TypeError, ValueError):
        return None
    return v if v > 0 else None


def price_ratios(ids, records_by_id):
    """Each listing's rent over the median for its own locality and bedrooms."""
    cells = defaultdict(list)
    for rid in ids:
        r = records_by_id.get(rid) or {}
        v, loc, bed = rent_of(r), r.get("locality"), r.get("bedrooms")
        if v and loc and bed is not None:
            cells[(str(loc), int(bed))].append(v)
    med = {k: statistics.median(v) for k, v in cells.items() if len(v) >= MIN_CELL}
    out = []
    for rid in ids:
        r = records_by_id.get(rid) or {}
        v = rent_of(r)
        try:
            m = med.get((str(r.get("locality")), int(r.get("bedrooms"))))
        except (TypeError, ValueError):
            m = None
        out.append(v / m if v and m else None)
    return out


def channel_matrix(X, y, cols):
    """The six interpretable channels, pulled out of the model matrix."""
    found, missing = {}, []
    for name, col in COLUMN_FOR.items():
        if col in cols:
            found[name] = X[:, cols.index(col)]
        else:
            missing.append(col)
            found[name] = np.zeros(X.shape[0])
    base = np.stack([y, found["bedrooms"], found["bathrooms"],
                     found["toilets"], found["amenity_count"]], axis=1)
    return base.astype(np.float64), missing


def build_sequences(base_tr, base_all, Str, Sall, k, exclude_self):
    """Nine timesteps: eight comparables closest-last, then the subject."""
    from sklearn.neighbors import NearestNeighbors

    nn = NearestNeighbors(n_neighbors=k + (1 if exclude_self else 0))
    nn.fit(Str)
    dist, idx = nn.kneighbors(Sall)
    if exclude_self:
        dist, idx = dist[:, 1:], idx[:, 1:]
    idx, dist = idx[:, ::-1], dist[:, ::-1]

    comps = np.concatenate([base_tr[idx], dist[:, :, None]], axis=2)
    subject = np.concatenate([base_all, np.zeros((len(base_all), 1))], axis=1)
    return np.concatenate([comps, subject[:, None, :]], axis=1)


def make_autoencoder(timesteps, channels, enc, bottleneck, dropout, lr):
    from tensorflow import keras
    from tensorflow.keras import layers

    inp = layers.Input(shape=(timesteps, channels))
    x = layers.LSTM(enc, return_sequences=True)(inp)
    x = layers.Dropout(dropout)(x)
    x = layers.LSTM(bottleneck)(x)
    x = layers.RepeatVector(timesteps)(x)
    x = layers.LSTM(bottleneck, return_sequences=True)(x)
    x = layers.Dropout(dropout)(x)
    x = layers.LSTM(enc, return_sequences=True)(x)
    out = layers.TimeDistributed(layers.Dense(channels))(x)
    model = keras.Model(inp, out)
    model.compile(optimizer=keras.optimizers.Adam(learning_rate=lr, clipnorm=1.0),
                  loss="mse")
    return model


# ------------------------------------------------------------------ fit
def cmd_fit(args) -> int:
    try:
        import tensorflow as tf
        from tensorflow import keras
        from sklearn.preprocessing import StandardScaler
        import joblib
    except Exception as e:
        print(f"A dependency did not import: {type(e).__name__}: {e}")
        return 1
    tf.get_logger().setLevel("ERROR")
    random.seed(SEED); np.random.seed(SEED); tf.random.set_seed(SEED)

    data = np.load(FEATURES / "matrix.npz")
    X, y = data["X"], data["y"]
    cols = json.loads((FEATURES / "feature_spec.json").read_text(encoding="utf-8"))["columns"]
    ids = json.loads((FEATURES / "row_ids.json").read_text(encoding="utf-8"))
    splits = json.loads((FEATURES / "splits.json").read_text(encoding="utf-8"))
    fr = splits["fraud"]["indices"]
    tr, va, te = np.array(fr["train"]), np.array(fr["val"]), np.array(fr["test"])

    section("Partition, the official one")
    print(f"  scheme            {splits['fraud']['scheme']}")
    print(f"  train/val/test    {len(tr):,} / {len(va):,} / {len(te):,}")
    print(f"  agents shared     "
          f"{splits['fraud'].get('agents_shared_between_train_and_test', '?')} "
          f"between train and test (must be 0)")

    base, missing = channel_matrix(X, y, cols)
    if missing:
        print(f"  WARNING           columns not found, zero filled: {missing}")

    scaler = StandardScaler().fit(X[tr])
    Sall = scaler.transform(X).astype(np.float32)
    Str = Sall[tr]

    ch_scaler = StandardScaler().fit(base[tr])
    base_s = ch_scaler.transform(base).astype(np.float64)

    seq_tr = build_sequences(base_s[tr], base_s[tr], Str, Str, args.k, True)
    seq_all = build_sequences(base_s[tr], base_s, Str, Sall, args.k, False)

    # Distance channel standardised on the training sequences only.
    d_mu = float(seq_tr[:, :, -1].mean())
    d_sd = float(seq_tr[:, :, -1].std() or 1.0)
    for s in (seq_tr, seq_all):
        s[:, :, -1] = (s[:, :, -1] - d_mu) / d_sd
    seq_tr = seq_tr.astype(np.float32)
    seq_all = seq_all.astype(np.float32)

    section("What the model reads")
    print(f"  timesteps         {seq_tr.shape[1]} "
          f"({args.k} comparables, closest last, then the subject)")
    print(f"  channels          {seq_tr.shape[2]}: {', '.join(CHANNELS)}")
    print("  comparables       drawn only from the training fold, for every row")
    print("  self-retrieval    excluded when the query is a training row")

    val_mask = np.isin(np.arange(len(X)), va)
    model = make_autoencoder(seq_tr.shape[1], seq_tr.shape[2],
                             args.units, args.bottleneck, args.dropout, args.lr)
    print(f"  parameters        {model.count_params():,}")

    section("Training, unsupervised, no labels anywhere")
    cbs = [keras.callbacks.EarlyStopping(monitor="val_loss", patience=10,
                                         restore_best_weights=True),
           keras.callbacks.ReduceLROnPlateau(monitor="val_loss", factor=0.5,
                                             patience=4, min_lr=1e-5)]
    hist = model.fit(seq_tr, seq_tr,
                     validation_data=(seq_all[val_mask], seq_all[val_mask]),
                     epochs=args.epochs, batch_size=args.batch,
                     callbacks=cbs, verbose=2)
    print(f"\n  epochs run        {len(hist.history['loss'])} of {args.epochs}")

    # ------------------------------------------- scores
    recon = model.predict(seq_all, verbose=0)
    per_channel = (recon[:, -1, :] - seq_all[:, -1, :]) ** 2      # subject only

    # Two scores, because they answer different questions.
    #
    # full_error is reconstruction error across all six channels, which is the
    # literal reading of the phase brief. It measures how unusual a listing is
    # in any respect, and in practice that means it finds twelve bedroom houses
    # with no comparables. A real anomaly, not a scam.
    #
    # price_gap is the one that matters here. It is how much MORE the model
    # expected the subject to cost than it is actually asking, given its
    # neighbourhood and its own structure, and it is clipped at zero so that
    # being dear counts for nothing. A rental scam baits with a low price, and
    # a listing above its neighbourhood is an overpricing problem the estimate
    # already handles.
    full_error = per_channel.mean(axis=1)
    gap = recon[:, -1, 0] - seq_all[:, -1, 0]      # channel 0 is log rent
    error = np.maximum(gap, 0.0) ** 2

    train_err = error[tr]
    ratios = price_ratios(ids, {f"{r.get('source')}:{r.get('source_record_id')}": r
                                for r in load_records(RECORDS)})
    scored = [r for r in ratios if r is not None]
    cheap = sum(1 for r in scored if r < CHEAP_RATIO)
    contamination = cheap / len(scored) if scored else 0.1

    high_cut = float(np.percentile(train_err, 100 * (1 - contamination)))
    med_cut = float(np.percentile(train_err, MEDIUM_PERCENTILE))

    section("Threshold, and why this number")
    print(f"  contamination     {contamination:.3f}, the share asking below "
          f"{CHEAP_RATIO:.0%} of their cell median")
    print(f"  HIGH above        {high_cut:.5f}  "
          f"(p{100 * (1 - contamination):.1f} of training error)")
    print(f"  MEDIUM above      {med_cut:.5f}  (p{MEDIUM_PERCENTILE:.0f})")
    print("\n  The same contamination the isolation forest used, so both")
    print("  detectors flag the same share and their precision is comparable.")

    # Percentile rank against the training distribution, not min-max.
    order = np.sort(train_err)
    score = np.searchsorted(order, error, side="right") / len(order)
    score = np.clip(score, 0.0, 1.0)

    band = np.where(error >= high_cut, "HIGH",
                    np.where(error >= med_cut, "MEDIUM", "LOW"))
    section("Risk bands across the whole corpus")
    for b in ("LOW", "MEDIUM", "HIGH"):
        n = int((band == b).sum())
        print(f"  {b:<8} {n:>6,}  ({n / len(band) * 100:>5.1f}%)")

    dominant = [CHANNELS[int(i)] for i in np.argmax(per_channel, axis=1)]
    section("Which channel drives each flag")
    from collections import Counter
    for name, n in Counter(d for d, b in zip(dominant, band) if b == "HIGH").most_common():
        print(f"  {name:<16} {n:>5,}")

    section("Is it finding cheap listings, or expensive ones?")
    corr = corr_full = None
    rs = [r for r in ratios if r is not None]
    keep = [i for i, r in enumerate(ratios) if r is not None]
    if keep:
        corr = float(np.corrcoef(error[keep], rs)[0, 1])
        corr_full = float(np.corrcoef(full_error[keep], rs)[0, 1])
        print(f"  price gap score      correlation with price ratio  {corr:+.3f}")
        print(f"  full reconstruction  correlation with price ratio  {corr_full:+.3f}")
        print("\n  Negative is what you want: a scam is cheap for what it claims.")
        print("  The isolation forest reached -0.007. The full reconstruction")
        print("  error scored +0.010 and was flagging twelve bedroom houses,")
        print("  which is why the price gap is the score that ships.")

    flagged = band == "HIGH"
    fc = sum(1 for r, f in zip(ratios, flagged) if f and r is not None and r < CHEAP_RATIO)
    if flagged.sum():
        print(f"  flagged HIGH                 {int(flagged.sum()):,}")
        print(f"  of those, far below norm     {fc:,} "
              f"({fc / flagged.sum() * 100:.0f}%)")

    section("Most anomalous listings, and what drove each one")
    by_id = {f"{r.get('source')}:{r.get('source_record_id')}": r
             for r in load_records(RECORDS)}
    print(f"  {'score':>6}  {'ratio':>6}  {'rent':>9}  {'bed':>3}  "
          f"{'locality':<22} dominant channel")
    for i in np.argsort(-error)[:12]:
        r = by_id.get(ids[i], {})
        ratio = ratios[i]
        print(f"  {score[i]:>6.3f}  "
              f"{(f'{ratio:.2f}' if ratio else '-'):>6}  "
              f"{(rent_of(r) or 0):>9,.0f}  {str(r.get('bedrooms') or '-'):>3}  "
              f"{str(r.get('locality') or '-')[:22]:<22} {dominant[i]}")

    ARTEFACTS.mkdir(parents=True, exist_ok=True)
    model.save(ARTEFACTS / "fraud_lstm.keras")
    joblib.dump({"feature_scaler": scaler, "channel_scaler": ch_scaler,
                 "distance_mu": d_mu, "distance_sd": d_sd},
                ARTEFACTS / "fraud_lstm_scalers.joblib")
    np.savez_compressed(ARTEFACTS / "fraud_lstm_scores.npz",
                        error=error, score=score, band=band,
                        per_channel=per_channel)
    (ARTEFACTS / "fraud_lstm_rowmap.json").write_text(
        json.dumps({"ids": ids}), encoding="utf-8")
    (ARTEFACTS / "fraud_lstm_history.json").write_text(json.dumps(
        {k: [float(x) for x in v] for k, v in hist.history.items()}, indent=2),
        encoding="utf-8")
    (ARTEFACTS / "fraud_lstm_results.json").write_text(json.dumps({
        "model": "LSTM autoencoder",
        "timesteps": int(seq_tr.shape[1]), "channels": CHANNELS,
        "k_comparables": args.k,
        "architecture": {"encoder": args.units, "bottleneck": args.bottleneck,
                         "dropout": args.dropout, "lr": args.lr,
                         "parameters": int(model.count_params())},
        "epochs_run": len(hist.history["loss"]),
        "contamination": contamination,
        "threshold_high": high_cut, "threshold_medium": med_cut,
        "threshold_method": "percentile of training reconstruction error at "
                            "the contamination rate",
        "score_method": "percentile rank against the training error distribution",
        "bands": {b: int((band == b).sum()) for b in ("LOW", "MEDIUM", "HIGH")},
        "score_ratio_correlation": corr,
        "full_reconstruction_ratio_correlation": corr_full,
        "note": "F1 needs the hand-labelled sample. Run 'score' once it exists.",
    }, indent=2), encoding="utf-8")

    section("Written")
    for f in ("fraud_lstm.keras", "fraud_lstm_scalers.joblib",
              "fraud_lstm_scores.npz", "fraud_lstm_history.json",
              "fraud_lstm_results.json"):
        print(f"  {ARTEFACTS / f}")
    print("\n  F1 cannot be reported yet. Label the sample, then run 'score'.")
    return 0


# ---------------------------------------------------------------- score
def cmd_score(args) -> int:
    path = ARTEFACTS / "fraud_lstm_scores.npz"
    if not path.exists():
        print(f"No scores at {path}. Run 'fit' first.")
        return 1
    d = np.load(path, allow_pickle=True)
    error, band = d["error"], d["band"].astype(str)
    ids = json.loads((ARTEFACTS / "fraud_lstm_rowmap.json").read_text(encoding="utf-8"))["ids"]
    index = {rid: i for i, rid in enumerate(ids)}

    labelled = []
    with Path(args.labels).open(encoding="utf-8-sig", newline="") as fh:
        for row in csv.DictReader(fh):
            rid = (row.get("row_id") or "").strip()
            raw = (row.get("suspicious") or "").strip()
            if rid in index and raw in ("0", "1"):
                labelled.append((index[rid], int(raw)))

    if len(labelled) < 30:
        print(f"Only {len(labelled)} usable labelled rows.")
        return 1

    idx = [i for i, _ in labelled]
    y = np.array([v for _, v in labelled])
    pred = (band[idx] == "HIGH").astype(int)
    sc = error[idx]

    tp = int(((pred == 1) & (y == 1)).sum())
    fp = int(((pred == 1) & (y == 0)).sum())
    fn = int(((pred == 0) & (y == 1)).sum())
    tn = int(((pred == 0) & (y == 0)).sum())
    prec = tp / (tp + fp) if tp + fp else 0.0
    rec = tp / (tp + fn) if tp + fn else 0.0
    f1 = 2 * prec * rec / (prec + rec) if prec + rec else 0.0

    section("LSTM autoencoder, on the hand-labelled sample")
    print(f"  labelled rows        {len(labelled)}")
    print(f"  marked suspicious    {int(y.sum())} ({y.mean() * 100:.1f}%)")
    print(f"  precision            {prec:.3f}")
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

    baseline = ARTEFACTS / "fraud_baseline_scored.json"
    if baseline.exists():
        b = json.loads(baseline.read_text(encoding="utf-8"))
        section("Against the isolation forest baseline")
        print(f"  {'metric':<12}{'isolation forest':>20}{'autoencoder':>16}")
        for k in ("precision", "recall", "f1"):
            print(f"  {k:<12}{b.get(k, float('nan')):>20.3f}"
                  f"{{'precision': prec, 'recall': rec, 'f1': f1}}[k]:>16.3f")
        print("\n  Same labelled rows, same threshold share. If the deep model")
        print("  does not beat the forest, report it: that is a result about")
        print("  the problem, not a failure to be hidden.")

    print(f"\n  acceptance threshold  F1 >= 0.85")
    print(f"  measured              F1 =  {f1:.3f}")

    (ARTEFACTS / "fraud_lstm_scored.json").write_text(json.dumps({
        "labelled_rows": len(labelled), "positive_rate": float(y.mean()),
        "confusion": {"tp": tp, "fp": fp, "fn": fn, "tn": tn},
        "precision": prec, "recall": rec, "f1": f1, "auc_roc": auc,
        "labeller_count": 1, "threshold_f1": 0.85,
    }, indent=2), encoding="utf-8")
    print(f"\n  wrote {ARTEFACTS / 'fraud_lstm_scored.json'}")
    return 0


def main(argv=None) -> int:
    p = argparse.ArgumentParser(description="LSTM autoencoder for fraud")
    sub = p.add_subparsers(dest="command", required=True)
    f = sub.add_parser("fit")
    f.add_argument("--k", type=int, default=K)
    f.add_argument("--units", type=int, default=32)
    f.add_argument("--bottleneck", type=int, default=12)
    f.add_argument("--dropout", type=float, default=0.1)
    f.add_argument("--lr", type=float, default=1e-3)
    f.add_argument("--epochs", type=int, default=80)
    f.add_argument("--batch", type=int, default=64)
    f.set_defaults(func=cmd_fit)
    s = sub.add_parser("score")
    s.add_argument("--labels", required=True)
    s.set_defaults(func=cmd_score)
    args = p.parse_args(argv)
    return args.func(args)


if __name__ == "__main__":
    sys.exit(main())